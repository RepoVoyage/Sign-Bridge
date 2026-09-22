package com.repovoyage.sign

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.core.callback.BleScanCallback
import com.arashivision.sdk.camera.core.model.ConnectType
import com.repovoyage.sign.camera.CameraSession
import com.repovoyage.sign.service.CameraBridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P2 真机验证用最小操作面板：权限 → BLE 扫描 → 选中设备连接（经 FGS 持有的
 * CameraSession）→ 观察 state/events。正式 UI 在后续阶段另做。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: CameraSession? = null
    private val sessionJobs = mutableListOf<Job>()
    private val scannedDevices = mutableListOf<BleDeviceCore>()

    private lateinit var stateText: TextView
    private lateinit var statusText: TextView
    private lateinit var deviceContainer: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var stopButton: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startScan()
            else statusText.text = getString(R.string.permission_denied_hint)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildViews()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildViews() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        stateText = TextView(this).apply {
            text = getString(R.string.state_idle_hint)
            textSize = 16f
            setPadding(0, pad, 0, pad / 2)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.scan_hint)
            textSize = 14f
            setPadding(0, 0, 0, pad)
        }
        scanButton = Button(this).apply {
            text = getString(R.string.scan_button)
            setOnClickListener { onScanClick() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.stop_button)
            setOnClickListener { onStopClick() }
        }
        deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(stateText)
        root.addView(statusText)
        root.addView(scanButton)
        root.addView(stopButton)
        root.addView(deviceContainer)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderDevices() {
        deviceContainer.removeAllViews()
        scannedDevices.forEachIndexed { index, device ->
            deviceContainer.addView(
                Button(this).apply {
                    text = device.name ?: getString(R.string.unnamed_device, index + 1)
                    isAllCaps = false
                    setOnClickListener { onDeviceClick(device) }
                }
            )
        }
    }

    // ---------------------------------------------------------------- 权限

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // manifest 中 BLUETOOTH_SCAN 为 neverForLocation，无需位置权限
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun hasRuntimePermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ----------------------------------------------------------------- 扫描

    private fun onScanClick() {
        if (hasRuntimePermissions()) startScan()
        else permissionLauncher.launch(requiredPermissions())
    }

    private fun startScan() {
        scannedDevices.clear()
        renderDevices()
        statusText.text = getString(R.string.scanning)
        // BLE 扫描是独立于会话的 UI 层职责（CameraSession 只接收已发现的设备）
        CameraDevice.get(ConnectType.BLE).scan(
            SCAN_DURATION_MS,
            object : BleScanCallback {
                override fun onStarted() {}

                override fun onScanning(bleDevice: BleDeviceCore) {
                    // 回调线程不定，回主线程处理 UI
                    scope.launch {
                        if (scannedDevices.contains(bleDevice)) return@launch
                        scannedDevices.add(bleDevice)
                        renderDevices()
                        statusText.text = getString(R.string.found_n_devices, scannedDevices.size)
                    }
                }

                override fun onFinished(bleDeviceList: List<BleDeviceCore>) {
                    scope.launch {
                        scannedDevices.clear()
                        scannedDevices.addAll(bleDeviceList)
                        renderDevices()
                        statusText.text = getString(R.string.scan_finished, scannedDevices.size)
                    }
                }

                override fun onError(throwable: Throwable) {
                    scope.launch {
                        statusText.text = getString(R.string.scan_failed, throwable.message ?: "?")
                    }
                }
            },
        )
    }

    // ----------------------------------------------------------------- 连接

    private fun onDeviceClick(device: BleDeviceCore) {
        statusText.text = getString(R.string.connecting_device, device.name ?: "?")
        CameraBridgeForegroundService.start(this)
        scope.launch {
            // 等 FGS 创建会话（onCreate 同步执行，一般一轮即可）
            var s = CameraBridgeForegroundService.session
            var tries = 0
            while (s == null && tries++ < 50) {
                delay(100)
                s = CameraBridgeForegroundService.session
            }
            if (s == null) {
                statusText.text = getString(R.string.service_not_ready)
                return@launch
            }
            attachSession(s)
            s.start(device)
        }
    }

    private fun attachSession(s: CameraSession) {
        if (session === s) return
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        session = s
        sessionJobs += scope.launch {
            s.state.collect { state -> stateText.text = getString(R.string.state_format, state) }
        }
        sessionJobs += scope.launch {
            s.events.collect { event -> statusText.text = getString(R.string.event_format, event) }
        }
    }

    private fun onStopClick() {
        scope.launch {
            session?.stop()
            CameraBridgeForegroundService.stop(this@MainActivity)
            stateText.text = getString(R.string.state_idle_hint)
            statusText.text = getString(R.string.scan_hint)
        }
    }

    private companion object {
        const val SCAN_DURATION_MS = 10_000L
    }
}
