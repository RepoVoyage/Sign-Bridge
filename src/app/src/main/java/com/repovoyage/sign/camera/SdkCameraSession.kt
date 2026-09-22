package com.repovoyage.sign.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.BatteryListener
import com.arashivision.sdk.camera.api.param.listener.DisconnectListener
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.option.BatteryData
import com.arashivision.sdk.camera.core.model.option.WiFiData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * CameraSession 的 Insta360 SDK 实现（P2：连接链路 + 断连重连；取流在 P3 接入，
 * 故正常路径止于 Preparing，Authorizing 语义暂映射为 WIFI connect 全程）。
 *
 * 连接链路照 Demo ConnectionViewModel 抄录：
 * BLE connect(isBleOnly=false) → ensureApMode → getWifiData → connectSystemWifi
 * → bindProcessToNetwork → release BLE → WIFI connect(networkHandle)。
 *
 * 线程模型：所有状态机变更与 SDK 调用收敛在 scope 的单线程上下文（Main）；
 * DisconnectListener / BatteryListener 回调先 hop 回该上下文再处理。
 * 日志红线：不得输出热点 SSID/密码与 networkHandle。
 */
class SdkCameraSession(
    context: Context,
    private val scope: CoroutineScope,
) : CameraSession {

    private val machine = SessionStateMachine()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    private var currentDevice: CameraDevice? = null
    private var listenersDevice: CameraDevice? = null
    private var connectionAttemptJob: Job? = null
    private var attemptWatchdogJob: Job? = null
    private var healthWatchJob: Job? = null
    private var systemWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** stop()/release() 触发的 SDK 断连回调不算被动断连（Demo 同名开关） */
    @Volatile
    private var suppressDisconnectCallback = false

    /** 同一次物理断连可能同时触发 listener 与健康监测，2s 内去重防止重试名额被双计 */
    private var lastDisconnectHandledAtMs = 0L

    private var lastBleDevice: BleDeviceCore? = null
    private var lastWifi: WiFiData? = null

    /**
     * 初连失败的瞬时性重试计数（真机验证：相机深度休眠时唤醒握手失败、
     * 停止后快速重连 GATT 133 均为瞬时错误，直接报 Error 无法满足连续
     * 连接成功率）。重试走完整 BLE 链路，成功后清零。
     */
    private var initialAttemptFailures = 0

    // ---------------------------------------------------------------- 回调

    private val disconnectListener = object : DisconnectListener {
        override fun onDisconnect(throwable: Throwable?) {
            if (suppressDisconnectCallback) {
                suppressDisconnectCallback = false
                return
            }
            val cause = if (throwable == null) DisconnectCause.WIFI_LINK_LOST
            else DisconnectCause.SDK_ERROR
            scope.launch { handleDisconnected(cause) }
        }
    }

    private val batteryListener = object : BatteryListener {
        override fun onBatteryLevelChange(batteryData: BatteryData) {
            // 常规电量变化不广播，仅 UI 主动查询时关心
        }

        override fun onLowBatteryWarning() {
            scope.launch {
                val device = currentDevice ?: return@launch
                device.system.getBatteryData().onSuccess {
                    _events.emit(SessionEvent.BatteryLow(it.level))
                }
            }
        }
    }

    // ---------------------------------------------------------------- API

    override suspend fun start(bleDevice: BleDeviceCore) {
        if (!machine.start()) return
        publish()
        lastBleDevice = bleDevice
        lastWifi = null
        initialAttemptFailures = 0
        launchAttempt { connectViaBle(bleDevice) }
    }

    override suspend fun stop() {
        machine.onUserStop()
        publish()
        suppressDisconnectCallback = true
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        cancelAttemptWatchdog()
        healthWatchJob?.cancel()
        healthWatchJob = null
        unregisterSystemListeners()
        val device = currentDevice
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = null
        // release() 内含断连语义，须在解绑进程网络前完成，否则相机占着会话导致快速重连冲突
        runCatching { device?.release() }
        unbindNetwork()
        machine.transitionTo(SessionState.Idle)
        publish()
    }

    override suspend fun resumeAfterCooldown() {
        if (!machine.resumeAfterCooldown()) return
        publish()
        lastBleDevice?.let { launchAttempt { connectViaBle(it) } }
    }

    // ------------------------------------------------------- 连接链路（初连）

    /** BLE 引导 → 系统热点 → WIFI connect */
    private suspend fun connectViaBle(bleDevice: BleDeviceCore) {
        goto(SessionState.BleConnecting(bleDevice.name))
        val bleCamera = CameraDevice.get(ConnectType.BLE)
        setCurrentDevice(bleCamera)
        bleCamera
            // BLE 不是最终目标，isBleOnly = false
            .connect(bleDevice, false)
            .onSuccess { launchOnAttempt { connectViaWifi(bleCamera) } }
            .onFailure { failAttempt(it.message) }
    }

    private suspend fun connectViaWifi(bleCamera: CameraDevice) {
        goto(SessionState.WifiConnecting)
        ensureApMode(bleCamera)?.let {
            failAttempt(it)
            return
        }
        val wifi = bleCamera.system.getWifiData().getOrElse {
            failAttempt(it.message ?: "getWifiData failed")
            return
        }
        lastWifi = wifi
        // 系统对不可用网络可能拖 30s+ 才报 onUnavailable，自设上限保证退避节奏可控
        val network = withTimeoutOrNull(SYSTEM_WIFI_TIMEOUT_MS) { connectSystemWifi(wifi.ssid, wifi.pwd) }
        if (network == null) {
            failAttempt("system wifi unavailable")
            return
        }
        if (!bindNetwork(network)) {
            failAttempt("bindProcessToNetwork failed")
            return
        }
        // BLE 与 WIFI 是独立通道，拿到 Network 即可释放 BLE 控制通道
        runCatching { bleCamera.release() }
        val wifiCamera = CameraDevice.get(ConnectType.WIFI)
        setCurrentDevice(wifiCamera)
        authorizeAndConnect(wifiCamera, network.networkHandle, isRetry = false)
    }

    /** WIFI connect + 连接后置；重连时与初连共用（网络/授权/准备重新执行） */
    private suspend fun authorizeAndConnect(
        camera: CameraDevice,
        networkHandle: Long,
        isRetry: Boolean,
    ) {
        goto(SessionState.Authorizing(AuthStatus.WAITING))
        camera.connect(networkHandle)
            .onSuccess { launchOnAttempt { onConnected(camera) } }
            .onFailure {
                if (isRetry) onReconnectAttemptFailed(it.message)
                else failAttempt(it.message)
            }
    }

    private suspend fun onConnected(camera: CameraDevice) {
        goto(SessionState.Preparing)
        initialAttemptFailures = 0
        registerSystemListeners(camera)
        startHealthWatch(camera)
        // P3 接入：loadJson/能力查询/开流 → Streaming(params)，streamGeneration 递增
    }

    // ------------------------------------------------------------- 重连

    /** 非主动断连：立即通知 → Reconnecting(退避) → 到点重走网络/授权/准备（无 BLE 重扫） */
    private fun handleDisconnected(cause: DisconnectCause) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDisconnectHandledAtMs < DISCONNECT_DEDUPE_MS) return
        lastDisconnectHandledAtMs = now

        stopHealthWatch()
        // 取消整条连接尝试链路，防止迟到的模式切换超时覆盖刚写入的断连状态（Demo 教训）
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        emitEvent(SessionEvent.Disconnected(cause))
        // 无论是否重连都要清理：不 release 的话相机会占着会话，快速重连返回冲突错误码；
        // ≥1s 的退避等待同时给了 release 送达的时间窗
        releaseDeviceAndUnbind()
        if (!machine.onDisconnected()) {
            // 连接尝试期（BLE/WIFI 连接中/Checking/Activating）断连：尝试链已取消，
            // 必须显式失败，否则状态永远停在连接中（Stopping/Idle 时转移不合法，自然跳过）
            machine.transitionTo(SessionState.Error(SessionError.UNRECOVERABLE))
            publish()
            return
        }
        publish()
        if (machine.state is SessionState.Error) {
            emitEvent(SessionEvent.ReconnectFailed(ReconnectPolicy.MAX_ATTEMPTS))
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val st = machine.state as? SessionState.Reconnecting ?: return
        connectionAttemptJob = scope.launch {
            delay(st.nextRetryInMs)
            // 退避等待期间用户可能已停止会话
            if (machine.state !is SessionState.Reconnecting) return@launch
            armAttemptWatchdog { onReconnectAttemptFailed("attempt timeout") }
            runReconnect()
        }
    }

    /** 重连：凭缓存的相机凭据重走 系统热点 → 授权 → 准备 */
    private suspend fun runReconnect() {
        val wifi = lastWifi
        if (wifi == null) {
            // 无凭据（理论上不会出现）：退回 BLE 全链路
            val ble = lastBleDevice
            if (ble == null) {
                onReconnectAttemptFailed("no cached wifi credentials")
                return
            }
            connectViaBle(ble)
            return
        }
        goto(SessionState.WifiConnecting)
        val network = withTimeoutOrNull(SYSTEM_WIFI_TIMEOUT_MS) { connectSystemWifi(wifi.ssid, wifi.pwd) }
        if (network == null) {
            onReconnectAttemptFailed("system wifi unavailable")
            return
        }
        if (!bindNetwork(network)) {
            onReconnectAttemptFailed("bindProcessToNetwork failed")
            return
        }
        val camera = CameraDevice.get(ConnectType.WIFI)
        setCurrentDevice(camera)
        authorizeAndConnect(camera, network.networkHandle, isRetry = true)
    }

    /** 重连尝试失败：消耗一次重试名额，进入下一轮退避或耗尽报错 */
    private fun onReconnectAttemptFailed(message: String?) {
        Log.w(TAG, "reconnect attempt failed: $message")
        stopHealthWatch()
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        releaseDeviceAndUnbind()
        if (!machine.onDisconnected()) {
            publish()
            return
        }
        publish()
        if (machine.state is SessionState.Error) {
            emitEvent(SessionEvent.ReconnectFailed(ReconnectPolicy.MAX_ATTEMPTS))
            return
        }
        scheduleReconnect()
    }

    /** 初连失败：瞬时性错误先自动重试（完整链路），耗尽才 Error */
    private fun failAttempt(message: String?) {
        Log.w(TAG, "connect attempt failed: $message")
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        stopHealthWatch()
        if (initialAttemptFailures < MAX_INITIAL_RETRIES) {
            initialAttemptFailures += 1
            Log.i(TAG, "initial connect auto-retry $initialAttemptFailures/$MAX_INITIAL_RETRIES")
            connectionAttemptJob = scope.launch {
                delay(INITIAL_RETRY_DELAY_MS)
                // 等待期间用户可能已停止（Stopping/Idle/Error）则放弃重试
                val s = machine.state
                val inConnectLadder = s is SessionState.Checking ||
                    s is SessionState.BleConnecting ||
                    s is SessionState.WifiConnecting ||
                    s is SessionState.Authorizing ||
                    s is SessionState.Activating ||
                    s is SessionState.Preparing
                val ble = lastBleDevice
                if (!inConnectLadder || ble == null) return@launch
                armAttemptWatchdog { failAttempt("attempt timeout") }
                connectViaBle(ble)
            }
            return
        }
        machine.transitionTo(SessionState.Error(SessionError.UNRECOVERABLE))
        publish()
        releaseDeviceAndUnbind()
    }

    // ------------------------------------------------------------ SDK 细节

    /**
     * 确保相机 WiFi 处于 AP 模式（照 Demo 抄录）：切模式后相机重启 WiFi，
     * 轮询确认完成，最多约 5s。已是 AP 返回 null（无错误）。
     */
    private suspend fun ensureApMode(bleCamera: CameraDevice): String? {
        val currentMode = bleCamera.system.fetchWifiData().getOrNull()?.mode
        if (currentMode == WiFiData.Mode.AP) return null
        if (!bleCamera.system.setWifiMode(WiFiData.Mode.AP, "").isSuccess) {
            return "setWifiMode failed"
        }
        return if (awaitApWifiData(bleCamera) != null) null else "AP mode switch timeout"
    }

    private suspend fun awaitApWifiData(bleCamera: CameraDevice): WiFiData? {
        repeat(AP_MODE_POLL_TIMES) {
            val data = bleCamera.system.fetchWifiData().getOrNull()
            if (data?.mode == WiFiData.Mode.AP) return data
            delay(AP_MODE_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * 经 WifiNetworkSpecifier 发起进程专属系统热点连接（照 Demo 抄录）。
     * 回调必须持续注册以维持 Network 存活，直到断连/清理才 unregister。
     */
    private suspend fun connectSystemWifi(ssid: String, password: String): Network? {
        if (!wifiManager.isWifiEnabled) return null
        unregisterSystemWifiNetworkCallback()
        return suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var finished = false

                private fun finishOnce(network: Network?) {
                    if (finished) return
                    finished = true
                    cont.resume(network)
                }

                override fun onAvailable(network: Network) = finishOnce(network)

                override fun onUnavailable() = finishOnce(null)
            }
            connectivityManager.requestNetwork(request, callback)
            systemWifiNetworkCallback = callback
            // 尝试链路被取消时同步撤销请求，避免陈旧 Network 干扰下一次同 SSID 重连
            cont.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun unregisterSystemWifiNetworkCallback() {
        val callback = systemWifiNetworkCallback ?: return
        systemWifiNetworkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun bindNetwork(network: Network): Boolean =
        ProcessNetworkBinder.bind(connectivityManager, network)

    private fun unbindNetwork() {
        ProcessNetworkBinder.unbind(connectivityManager)
    }

    private fun setCurrentDevice(device: CameraDevice?) {
        if (currentDevice === device) return
        unregisterSystemListeners()
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = device
        // 连接期间（含 SDK 内部同步阶段）即注册，否则窗口期断连被无声丢弃（Demo 教训）
        device?.registerDisconnectListener(disconnectListener)
    }

    private fun registerSystemListeners(device: CameraDevice) {
        unregisterSystemListeners()
        device.system.registerBatteryListener(batteryListener)
        listenersDevice = device
    }

    private fun unregisterSystemListeners() {
        val d = listenersDevice ?: return
        d.system.unregisterBatteryListener(batteryListener)
        listenersDevice = null
    }

    /** isConnected() 轮询兜底：listener 丢失的静默断连（如 MIUI 冻结）也能发现 */
    private fun startHealthWatch(device: CameraDevice) {
        healthWatchJob?.cancel()
        healthWatchJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (currentDevice !== device) return@launch
                val connected = runCatching { device.isConnected() }.getOrDefault(false)
                if (!connected) {
                    scope.launch { handleDisconnected(DisconnectCause.HEALTH_CHECK_TIMEOUT) }
                    return@launch
                }
            }
        }
    }

    private fun stopHealthWatch() {
        healthWatchJob?.cancel()
        healthWatchJob = null
    }

    /**
     * 断连/失败后的清理：release 须在解绑进程网络前送达（否则相机占着会话，
     * 快速重连返回冲突错误码）。异步执行，不阻塞状态广播。
     */
    private fun releaseDeviceAndUnbind() {
        stopHealthWatch()
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        suppressDisconnectCallback = true
        unregisterSystemListeners()
        val device = currentDevice
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = null
        scope.launch {
            runCatching { device?.release() }
            unbindNetwork()
            unregisterSystemWifiNetworkCallback()
        }
    }

    // ---------------------------------------------------------------- 框架

    /** 新连接尝试：取消旧链路，root Job 挂在 scope 上，后续步骤以它为父 */
    private fun launchAttempt(block: suspend CoroutineScope.() -> Unit) {
        connectionAttemptJob?.cancel()
        connectionAttemptJob = scope.launch { coroutineScope(block) }
        armAttemptWatchdog { failAttempt("attempt timeout") }
    }

    /**
     * 尝试级 watchdog：SDK 的 BLE connect 在相机半死状态下可能内循环重试
     * 永不返回（真机挂死 108s），无自身超时。60s 上限（正常成功路径 ≤29s），
     * 超时按尝试失败处理，走既有自动重试/退避。
     */
    private fun armAttemptWatchdog(onTimeout: () -> Unit) {
        val attemptJob = connectionAttemptJob ?: return
        attemptWatchdogJob?.cancel()
        attemptWatchdogJob = scope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            if (attemptJob.isActive) {
                Log.w(TAG, "connect attempt watchdog fired after ${ATTEMPT_TIMEOUT_MS}ms")
                onTimeout()
            }
        }
    }

    private fun cancelAttemptWatchdog() {
        attemptWatchdogJob?.cancel()
        attemptWatchdogJob = null
    }

    /** 在当前尝试链路内续挂步骤（onSuccess 回调非 suspend，需显式挂到 root Job） */
    private fun launchOnAttempt(block: suspend CoroutineScope.() -> Unit) {
        val parent = connectionAttemptJob
        if (parent != null) scope.launch(parent) { block() }
        else scope.launch { block() }
    }

    private fun emitEvent(event: SessionEvent) {
        scope.launch { _events.emit(event) }
    }

    private fun goto(candidate: SessionState): Boolean {
        val ok = machine.transitionTo(candidate)
        publish()
        return ok
    }

    private fun publish() {
        _state.value = machine.state
    }

    private companion object {
        const val TAG = "SdkCameraSession"
        const val HEALTH_CHECK_INTERVAL_MS = 1_000L
        const val AP_MODE_POLL_TIMES = 10
        const val AP_MODE_POLL_INTERVAL_MS = 500L
        const val DISCONNECT_DEDUPE_MS = 2_000L
        const val MAX_INITIAL_RETRIES = 2
        const val INITIAL_RETRY_DELAY_MS = 2_000L
        const val SYSTEM_WIFI_TIMEOUT_MS = 10_000L
        const val ATTEMPT_TIMEOUT_MS = 60_000L
    }
}
