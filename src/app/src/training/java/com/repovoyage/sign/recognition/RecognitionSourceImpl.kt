package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.TokenSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * training：桩识别源——循环播放脚本句（每句 3 次草稿修订 → RELIABLE/UNCERTAIN
 * 边界），供模型就绪前联调字幕/TTS/缓存全链路。每次 start() 递增 epoch
 * （旧段随新 epoch 中断，符合 P6 语义）。仅 training flavor 存在；
 * production 无桩（P8 验收：成品不输出伪造结果）。
 */
object RecognitionSourceImpl : RecognitionSource {

    override val isAvailable: Boolean = true

    private val _updates = MutableSharedFlow<RecognitionUpdate>(extraBufferCapacity = 64)
    override val updates: Flow<RecognitionUpdate> = _updates

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var epoch = 0L
    private var segmentSeq = 0

    override fun start() {
        if (job?.isActive == true) return
        epoch++
        job = scope.launch {
            while (isActive) {
                for (scripted in SCRIPT) {
                    emitSentence(scripted)
                    delay(PAUSE_MS)
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun emitSentence(scripted: ScriptedSentence) {
        val seg = "stub-seg-${++segmentSeq}"
        val basePts = segmentSeq * SPAN_PTS_US
        scripted.drafts.forEachIndexed { index, draft ->
            _updates.emit(
                RecognitionUpdate(
                    sequenceEpoch = epoch,
                    segmentId = seg,
                    draftText = draft,
                    tokenSpans = listOf(
                        TokenSpan(draft, basePts, basePts + SPAN_PTS_US, stable = index == scripted.drafts.lastIndex),
                    ),
                    confidence = 0.5f + 0.2f * index,
                    boundary = null,
                ),
            )
            delay(REVISION_MS)
        }
        _updates.emit(
            RecognitionUpdate(
                sequenceEpoch = epoch,
                segmentId = seg,
                draftText = scripted.drafts.last(),
                tokenSpans = listOf(TokenSpan(scripted.drafts.last(), basePts, basePts + SPAN_PTS_US, stable = true)),
                confidence = 0.9f,
                boundary = BoundarySignal(
                    cutoffPtsUs = basePts + SPAN_PTS_US + 500_000,
                    requiredFutureContextUs = 500_000,
                    reliability = scripted.reliability,
                    source = BoundarySource.MODEL,
                ),
            ),
        )
    }

    private data class ScriptedSentence(val drafts: List<String>, val reliability: BoundaryReliability)

    private val SCRIPT = listOf(
        ScriptedSentence(listOf("我", "我需要", "我需要帮助"), BoundaryReliability.RELIABLE),
        ScriptedSentence(listOf("请", "请跟我", "请跟我来"), BoundaryReliability.RELIABLE),
        ScriptedSentence(listOf("今天", "今天天气", "今天天气很好"), BoundaryReliability.RELIABLE),
        // 待核对路径（UNCERTAIN 边界 → NeedsConfirmation，不阻塞后续表达）
        ScriptedSentence(listOf("可能", "可能是", "可能是低电量"), BoundaryReliability.UNCERTAIN),
        ScriptedSentence(listOf("谢谢", "谢谢你"), BoundaryReliability.RELIABLE),
        // 保真样本（plan.md P7 验收：否定/数字不得被 LLM 整理改写语义）
        ScriptedSentence(listOf("请", "请不要", "请不要碰我的头"), BoundaryReliability.RELIABLE),
        ScriptedSentence(listOf("我有", "我有3个", "我有3个孩子"), BoundaryReliability.RELIABLE),
        ScriptedSentence(listOf("我", "我没说", "我没说明天去"), BoundaryReliability.RELIABLE),
        ScriptedSentence(listOf("药", "药每次", "药每次吃2片"), BoundaryReliability.RELIABLE),
    )

    private const val PAUSE_MS = 3_000L
    private const val REVISION_MS = 600L
    private const val SPAN_PTS_US = 2_000_000L
}
