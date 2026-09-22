package com.repovoyage.sign.tts

import com.repovoyage.sign.sentence.LangCode

/** API.md §7 — 播报请求 */
data class SpeakRequest(
    val sessionId: String,
    val segmentId: String,
    val language: LangCode,
    val text: String,
    val orderKey: Long,                   // 源媒体时间序，按表达顺序播报
)

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object Duplicate : EnqueueResult    // 自动播报键 (sessionId, segmentId, language) 已存在
    data class Rejected(val reason: RejectReason) : EnqueueResult
}

enum class RejectReason { QUEUE_FULL, VOICE_NOT_READY }

/**
 * 自动播报队列纯逻辑（API.md §7 初始值）：串行一次一条、待播缓存 3 句、
 * 去重键 (sessionId, segmentId, language) 会话内终身有效、
 * 进入待播 5 秒未开始 → 未播报。时钟（monoMs）由调用方注入。
 */
class AutoSpeakQueue(
    private val capacity: Int = 3,
    private val unspokenAfterMs: Long = 5_000,
) {

    fun enqueue(request: SpeakRequest, nowMonoMs: Long): EnqueueResult = TODO("P7")

    /** 取队头开始播报；上一条未结束时返回 null（串行一次一条） */
    fun pollNext(): SpeakRequest? = TODO("P7")

    /** 当前播报结束，允许下一条 */
    fun onPlaybackFinished(): Unit = TODO("P7")

    /** 返回进入待播超过 5 秒仍未开始的请求（MarkedUnspoken） */
    fun checkOverdue(nowMonoMs: Long): List<SpeakRequest> = TODO("P7")
}
