package com.repovoyage.sign.p2

import com.repovoyage.sign.camera.AuthStatus
import com.repovoyage.sign.camera.SessionError
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.camera.SessionStateMachine
import com.repovoyage.sign.camera.StreamParams
import com.repovoyage.sign.camera.VideoEncodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * P2 状态机验收（ARCHITECTURE.md §2.1.3 状态图 + API.md §1 契约）。
 * 真机连续 10 次连接成功率等集成验收在 CameraSession 装机后进行，不在此文件。
 */
class SessionStateMachineTest {

    private val machine = SessionStateMachine()

    private fun streamParams(generation: Long = 1) =
        StreamParams(640, 384, 30, VideoEncodeType.H264, generation)

    private fun reachStreaming() {
        assertTrue(machine.start())
        assertTrue(machine.transitionTo(SessionState.BleConnecting("GO 3S")))
        assertTrue(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING)))
        assertTrue(machine.transitionTo(SessionState.Preparing))
        assertTrue(machine.transitionTo(SessionState.Streaming(streamParams())))
    }

    @Test
    fun `正常路径完整走通`() {
        reachStreaming()
        assertTrue(machine.state is SessionState.Streaming)
    }

    @Test
    fun `连接丢失进入 Reconnecting 退避递增且从头恢复`() {
        reachStreaming()
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(2, 2_000L), machine.state)
        // 恢复需重新执行网络、授权及准备步骤（不走 BLE 重扫）
        assertTrue(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING)))
        assertTrue(machine.transitionTo(SessionState.Preparing))
        assertTrue(machine.transitionTo(SessionState.Streaming(streamParams(generation = 2))))
    }

    @Test
    fun `重试耗尽进入 Error`() {
        reachStreaming()
        repeat(5) { assertTrue(machine.onDisconnected()) }
        assertEquals(SessionState.Reconnecting(5, 16_000L), machine.state)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Error(SessionError.RETRY_EXHAUSTED), machine.state)
        // Error 后仅允许用户重试（→ Checking）
        assertFalse(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Checking))
    }

    @Test
    fun `成功出图后重试计数清零`() {
        reachStreaming()
        repeat(3) { machine.onDisconnected() }
        // 恢复须重走网络/授权/准备步骤，不允许从 Reconnecting 直达 Streaming
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Preparing)
        machine.transitionTo(SessionState.Streaming(streamParams(generation = 2)))
        machine.onDisconnected()
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
    }

    @Test
    fun `用户停止优先于延迟重试`() {
        reachStreaming()
        machine.onDisconnected()
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        // 停止后迟到断连事件不再推进状态
        assertFalse(machine.onDisconnected())
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `权限拒绝走停止不进重试`() {
        assertTrue(machine.start())
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `过热暂停且冷却后重新开始`() {
        reachStreaming()
        assertTrue(machine.onOverheat())
        assertEquals(SessionState.PausedHot, machine.state)
        assertTrue(machine.resumeAfterCooldown())
        assertEquals(SessionState.Checking, machine.state)
    }

    @Test
    fun `无初始化直连被拒绝`() {
        assertFalse(machine.transitionTo(SessionState.Streaming(streamParams())))
        assertEquals(SessionState.Idle, machine.state)
        assertFalse(machine.transitionTo(SessionState.Reconnecting(1, 1_000L)))
        assertFalse(machine.transitionTo(SessionState.WifiConnecting))
    }

    @Test
    fun `迟到事件不推进已停止的会话`() {
        reachStreaming()
        machine.onUserStop()
        machine.transitionTo(SessionState.Idle)
        assertFalse(machine.onDisconnected())
        assertFalse(machine.onOverheat())
        assertEquals(SessionState.Idle, machine.state)
    }

    @Test
    @Ignore("P2：streamGeneration 递增与旧代次数据抑制在 CameraSession 集成层验证")
    fun `旧代次数据不推进新会话`() {
        throw NotImplementedError("P2 集成项：重连后旧代次帧/回调不得更新当前状态或句子")
    }
}
