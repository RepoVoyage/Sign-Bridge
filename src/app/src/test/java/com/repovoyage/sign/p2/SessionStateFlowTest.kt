package com.repovoyage.sign.p2

import org.junit.Ignore
import org.junit.Test

/**
 * P2 CameraSession 状态机验收（plan.md §1 P2；状态图见 ARCHITECTURE.md §2.1.3，
 * 状态/事件类型契约见 API.md §1）。P2 开工时按下列断言逐条写成真测试（先红后绿），
 * 并将 @Ignore 移到尚未覆盖的场景上。
 */
@Ignore("P2：待 CameraSession 状态机实现（SessionState 契约见 API.md §1.1）")
class SessionStateFlowTest {

    @Test
    fun `正常路径完整走通`() {
        fail("P2 待实现：Idle→Checking→BleConnecting→WifiConnecting→Authorizing→Preparing→Streaming")
    }

    @Test
    fun `连接丢失进入 Reconnecting 并从头恢复`() {
        fail("P2 待实现：Disconnected 事件后 Reconnecting(attempt, nextRetryInMs)，恢复需重新走网络/授权/准备步骤")
    }

    @Test
    fun `权限拒绝或用户停止不进入重试`() {
        fail("P2 待实现：→Stopping→Idle；stop() 优先于一切延迟重试")
    }

    @Test
    fun `相机过热进入 PausedHot 冷却后恢复`() {
        fail("P2 待实现：Overheat 事件→PausedHot，resumeAfterCooldown 后重新进入连接流程")
    }

    @Test
    fun `无初始化直连`() {
        fail("P2 待实现：任何状态不得跳过 Checking 直达 Streaming/Reconnecting")
    }

    @Test
    fun `停止清理不泄漏不重叠会话`() {
        fail("P2 待实现：杀进程/锁屏/切后停止后重开，旧会话回调不再推进新状态（streamGeneration 区分）")
    }

    private fun fail(message: String): Nothing = throw NotImplementedError(message)
}
