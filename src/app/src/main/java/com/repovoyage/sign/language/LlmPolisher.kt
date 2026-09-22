package com.repovoyage.sign.language

import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode

/**
 * API.md §6.2 — LOCAL / CLOUD 两个实现共享的引擎契约。
 * deadline 到达必须返回未完成标记或抛出，不得无限等待。
 */
interface LlmPolisher {
    /**
     * 只整理 rawChinese（语序、虚词、标点），并按 targetLanguages 翻译。
     * 禁止新增事实；否定/数字/专名/有意重复必须保留（ARCHITECTURE.md §2.4.5）。
     */
    suspend fun polish(input: PolishInput, deadlineMonoMs: Long): PolishOutput
}

/** issue 代码（云端契约 agent/docs/API.md §3）：映射 App 输出状态见 LanguageProcessor */
enum class FidelityIssueCode { AMBIGUITY, FIDELITY_CHECK_FAILED, UNAVAILABLE }

data class FidelityIssue(
    val language: LangCode,
    val code: FidelityIssueCode,
    val message: String,
)

data class PolishInput(
    /** 目标句身份：云端 HTTP 契约要求随请求发送并回显校验（agent/docs/API.md §2） */
    val sessionId: String,
    val segmentId: String,
    val revision: Int,
    val rawChinese: String,
    /** 仅用户选定的语言；仅中文时不含外语 */
    val targetLanguages: List<LangCode>,
    /** 已确认句子的受限衔接上下文，最近优先，超出上限丢弃最旧 */
    val contextSentences: List<ConfirmedSentence>,
)

data class PolishOutput(
    val polishedChinese: String?,             // 中文整理结果；无法完成为 null
    val translations: Map<LangCode, String>,  // 按需翻译（不含 zh-CN）
    val issues: List<FidelityIssue>,          // 该语言转 NEEDS_CONFIRMATION/UNAVAILABLE，不自动 TTS
    val source: OutputSource,
    val elapsedMs: Long,                      // 手机单调时钟，含排队
)
