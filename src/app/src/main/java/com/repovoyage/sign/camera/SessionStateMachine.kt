package com.repovoyage.sign.camera

/**
 * 会话状态机（ARCHITECTURE.md §2.1.3）：纯逻辑、无 SDK/协程依赖。
 * CameraSession 驱动本机并在 StateFlow 上广播状态；streamGeneration 的递增
 * 与旧代次数据抑制在 CameraSession 集成层处理，不在此处。
 *
 * 规则：用户停止/权限拒绝/过热不进入断线重试；用户停止优先于任何延迟重试。
 */
class SessionStateMachine(
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {

    var state: SessionState = SessionState.Idle
        private set

    /** 开始会话：Idle → Checking */
    fun start(): Boolean = transitionTo(SessionState.Checking)

    /** 非主动断连（仅 Streaming/Reconnecting）：退避重连，重试耗尽 → Error(RETRY_EXHAUSTED) */
    fun onDisconnected(): Boolean {
        val from = state
        if (from !is SessionState.Streaming && from !is SessionState.Reconnecting) return false
        val delayMs = reconnectPolicy.onDisconnected() ?: run {
            state = SessionState.Error(SessionError.RETRY_EXHAUSTED)
            return true
        }
        val attempt = (from as? SessionState.Reconnecting)?.attempt?.plus(1) ?: 1
        state = SessionState.Reconnecting(attempt, delayMs)
        return true
    }

    /** 用户停止/权限拒绝：任何状态 → Stopping（优先于延迟重试） */
    fun onUserStop(): Boolean = transitionTo(SessionState.Stopping)

    /** 相机过热：Streaming → PausedHot */
    fun onOverheat(): Boolean {
        if (state !is SessionState.Streaming) return false
        state = SessionState.PausedHot
        return true
    }

    /** 冷却后用户确认重新开始：仅 PausedHot → Checking */
    fun resumeAfterCooldown(): Boolean {
        if (state != SessionState.PausedHot) return false
        return transitionTo(SessionState.Checking)
    }

    /** 通用合法转移：合法则更新状态并返回 true；非法保持原状态返回 false */
    fun transitionTo(candidate: SessionState): Boolean {
        if (!isLegal(state, candidate)) return false
        if (candidate is SessionState.Streaming) reconnectPolicy.onStreamRecovered()
        state = candidate
        return true
    }

    /** §2.1.3 状态图的转移合法性 */
    private fun isLegal(from: SessionState, to: SessionState): Boolean = when (from) {
        SessionState.Idle -> to == SessionState.Checking
        SessionState.Checking -> to is SessionState.BleConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.BleConnecting -> to == SessionState.WifiConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.WifiConnecting -> to is SessionState.Authorizing ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Authorizing -> to == SessionState.Activating ||
            to == SessionState.Preparing || to == SessionState.Stopping || to is SessionState.Error
        SessionState.Activating -> to == SessionState.Preparing ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.Preparing -> to is SessionState.Streaming ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Streaming -> to is SessionState.Reconnecting ||
            to == SessionState.PausedHot || to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Reconnecting -> to == SessionState.WifiConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.Stopping -> to == SessionState.Idle
        SessionState.PausedHot -> to == SessionState.Checking || to == SessionState.Stopping
        is SessionState.Error -> to == SessionState.Checking || to == SessionState.Stopping
    }
}
