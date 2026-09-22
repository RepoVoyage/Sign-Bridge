package com.repovoyage.sign.p6

import org.junit.Ignore
import org.junit.Test

/**
 * P6 识别与句子管理验收（plan.md §1 P6；契约见 API.md §4/§5）。
 * SentenceManager 是纯状态机，全部路径可单测。P6 开工时逐条写成真测试。
 */
@Ignore("P6：待 SentenceManager 实现（契约见 API.md §4/§5）")
class SentenceManagerTest {

    @Test
    fun `重叠窗口更新同段草稿且 revision 递增`() {
        fail("P6 待实现：同一 segmentId 的 DraftUpdated revision 单调 +1，草稿覆盖")
    }

    @Test
    fun `RELIABLE 边界进入 FINALIZING 并 2 秒内 FINAL`() {
        fail("P6 待实现：BoundarySignal.RELIABLE → Finalizing → Final(ConfirmedSentence 冻结)")
    }

    @Test
    fun `UNCERTAIN 边界转待核对不阻塞后续表达`() {
        fail("P6 待实现：→NeedsConfirmation，后续新段照常推进")
    }

    @Test
    fun `FINAL 冻结后迟到推理不覆盖`() {
        fail("P6 待实现：冻结后到达的旧 revision/旧 epoch 结果丢弃")
    }

    @Test
    fun `断流缺帧过载使未生效请求过期`() {
        fail("P6 待实现：sequenceEpoch +1 后旧推理结果丢弃，自然换段不增 epoch")
    }

    @Test
    fun `INTERRUPTED 段不自动播报可补字幕`() {
        fail("P6 待实现：Interrupted 事件不进 TTS 自动队列")
    }

    @Test
    fun `用户放弃待核对项转 DISCARDED`() {
        fail("P6 待实现：Discarded 事件后该段不再出现在待核对列表")
    }

    private fun fail(message: String): Nothing = throw NotImplementedError(message)
}
