package com.repovoyage.sign.sentence

/**
 * 段状态机（API.md §5 / ARCHITECTURE §2.4.3）：
 * 重叠窗口更新同段草稿（revision 递增）；RELIABLE 边界→FINALIZING、UNCERTAIN→待核对；
 * FINAL 冻结后迟到推理丢弃；sequenceEpoch 前进中断未确认段且旧 epoch 迟到结果过期。
 */
class SentenceManager(
    private val sessionId: String,
    private val streamGeneration: Long,
) {

    /** 提交识别更新；迟到/过期（旧 epoch、已冻结/中断段）返回空列表 */
    fun submit(update: RecognitionUpdate): List<SentenceEvent> = TODO("P6")

    /** RELIABLE 边界后收尾冻结为 FINAL（2 秒超时由集成层驱动调用） */
    fun finalize(segmentId: String, startPtsUs: Long, endPtsUs: Long): List<SentenceEvent> = TODO("P6")

    /** 用户放弃待核对项 → DISCARDED */
    fun discard(segmentId: String): List<SentenceEvent> = TODO("P6")
}
