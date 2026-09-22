package com.repovoyage.sign.language

import com.repovoyage.sign.sentence.LangCode

enum class LanguageBackend { LOCAL, CLOUD }
enum class OutputSource { LOCAL, CLOUD, FALLBACK }
enum class OutputStatus { READY, NEEDS_CONFIRMATION, UNAVAILABLE }

/** API.md §6.1 — 语言处理结果 */
data class LanguageResult(
    val sessionId: String,
    val streamGeneration: Long,
    val sequenceEpoch: Long,
    val segmentId: String,
    val sentenceRevision: Int,
    val settingsRevision: Long,
    val language: LangCode,
    val text: String?,                        // UNAVAILABLE 时为 null
    val status: OutputStatus,
    val source: OutputSource,
    val elapsedMs: Long,
)

/**
 * 语言结果门（API.md §6.2）：去重键 (sessionId, segmentId, sentenceRevision, language)
 * 仅保留一个有效结果；settingsRevision 落后于已见快照的迟到结果拒绝。
 */
class LanguageResultGate {

    fun accept(result: LanguageResult): Boolean = TODO("P7")
}
