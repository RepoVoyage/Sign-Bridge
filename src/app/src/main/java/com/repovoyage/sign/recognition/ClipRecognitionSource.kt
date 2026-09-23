package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.TokenSpan
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.RecognitionTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** 切片识别传输（HTTP 实现见 [HttpClipTransport]；JVM 测试注入替身） */
interface ClipTransport {
    suspend fun recognize(videoBytes: ByteArray, token: String): CvResult
    suspend fun compose(
        sessionId: String,
        segmentId: String,
        revision: Int,
        gestures: List<CvResult>,
        token: String,
    ): ComposeResult
}

/**
 * 切片供给：把相机编码帧流接到切片器，产出的每个 MP4 段回调 [onSegment]。
 * @return attach 失败原因（展示用）；null = 成功
 */
interface ClipFeed {
    fun attach(outputDir: File, windowUs: Long, scope: CoroutineScope, onSegment: (File, Long, Long) -> Unit): String?
    fun detach()
}

/**
 * 模型 B 固定窗口切片识别源（P6 联调，2026-09-23 用户定义切分 + 直接进
 * 字幕管线）：相机编码帧 → 固定时长 MP4 段 → 词级 CV 候选 → 用户「完成本句」
 * → Agent 组句 → [RecognitionUpdate] 进入既有管线（段状态机/翻译/TTS/缓存）。
 *
 * 置信度映射（2026-09-23 定稿策略）：Agent 无数值句子置信度，
 * needsConfirmation 布尔直接映射边界可靠性——true → UNCERTAIN（待核对+震动，
 * 管线既有路径），false → RELIABLE（正常收尾）。CV 侧词级不确定（非 OK 状态）
 * 不入句，仅触发重打提示（needs_repeat），不打扰、不震动。
 */
class ClipRecognitionSource(
    private val outputDir: File,
    private val settings: AppSettings,
    private val transport: ClipTransport,
    private val feed: ClipFeed,
    private val scope: CoroutineScope,
    private val acquireCellular: () -> Unit,
    /** 联调验尸留存目录（flavor 缝：training=cacheDir/clips_debug，production=null 不落盘） */
    private val debugRetainDir: File? = null,
) : RecognitionSource {

    override val isAvailable: Boolean get() = tokens.isConfigured

    override val sourceDescription: String
        get() = if (tokens.isConfigured) "识别源：模型 B 固定窗口切片云端识别"
        else "识别源：模型 B（识别服务令牌未配置）"

    private val _updates = MutableSharedFlow<RecognitionUpdate>(extraBufferCapacity = 64)
    override val updates: Flow<RecognitionUpdate> = _updates

    /** 联调状态行（上传/识别/组句进度与失败原因）；null = 无提示 */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 词级拒绝（TOO_SHORT 等）→ VM 转发管线 reportNeedsRepeat()（重打提示，不震动） */
    private val _needsRepeat = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val needsRepeat: SharedFlow<Unit> = _needsRepeat

    @Volatile private var tokens = RecognitionTokens("", "")
    @Volatile private var windowUs = (DEFAULT_WINDOW_SECONDS * 1_000_000).toLong()

    private var epoch = 0L
    private var sentenceSeq = 0
    private var composeRevision = 0
    private var sessionId = ""
    private var currentSegmentId: String? = null
    private var sentenceStartPtsUs = 0L
    private var lastClipEndPtsUs = 0L
    private val gestures = mutableListOf<CvResult>()
    private val lock = Mutex()

    @Volatile private var running = false

    /**
     * 待识别切片积压队列（有界）：识别速度跟不上切片速度（如服务端限流/
     * 推理慢于窗口）时不允许无限排队——溢出的切片丢弃且**本句作废**
     * （中间缺词的句子不可信），提示重打并建议加大窗口。
     */
    private data class PendingClip(val file: File, val startPtsUs: Long, val endPtsUs: Long)

    private var clipQueue: Channel<PendingClip>? = null
    private var consumerJob: Job? = null

    @Volatile private var sentenceBroken = false

    init {
        scope.launch {
            settings.recognitionTokens.collect { tokens = it }
        }
        scope.launch {
            settings.clipWindowSeconds.collect { windowUs = (it * 1_000_000L).toLong() }
        }
    }

    override fun start() {
        if (running) return
        running = true
        epoch++
        sentenceSeq = 0
        composeRevision = 0
        currentSegmentId = null
        gestures.clear()
        sentenceBroken = false
        sessionId = UUID.randomUUID().toString()
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        // §2.4.7：相机在线时进程默认网络无公网，切片上传必须走蜂窝；
        // 释放归管线 stop()（与 LLM 共用同一持有者，幂等）
        acquireCellular()
        val queue = Channel<PendingClip>(CLIP_BACKLOG_CAPACITY)
        clipQueue = queue
        consumerJob = scope.launch {
            for (clip in queue) {
                if (sentenceBroken) {
                    sentenceBroken = false
                    invalidateSentenceLocked("识别跟不上切片速度：本句已丢弃，请重打（可加大切片窗口）")
                }
                onClip(clip.file, clip.startPtsUs, clip.endPtsUs)
            }
        }
        _statusText.value = feed.attach(outputDir, windowUs, scope) { file, startPtsUs, endPtsUs ->
            val q = clipQueue
            if (q == null || q.trySend(PendingClip(file, startPtsUs, endPtsUs)).isFailure) {
                file.delete()
                sentenceBroken = true
            }
        }
    }

    override fun stop() {
        running = false
        feed.detach()
        consumerJob?.cancel()
        consumerJob = null
        // 清空积压切片文件（隐私：帧数据不滞留磁盘）
        val q = clipQueue
        clipQueue = null
        if (q != null) {
            q.close()
            while (true) {
                val clip = q.tryReceive().getOrNull() ?: break
                clip.file.delete()
            }
        }
        gestures.clear()
        currentSegmentId = null
        sentenceBroken = false
        _statusText.value = null
    }

    /** 本句作废：清空已收候选并触发重打提示（调用方持锁或在消费者协程内） */
    private suspend fun invalidateSentenceLocked(message: String) {
        lock.withLock {
            gestures.clear()
            currentSegmentId = null
        }
        _statusText.value = message
        _needsRepeat.emit(Unit)
    }

    /** 用户「完成本句」：已收集候选送 Agent 组句，结果作为一个段更新进管线 */
    fun finishSentence() {
        if (!running) return
        scope.launch {
            lock.withLock {
                if (gestures.isEmpty()) {
                    _statusText.value = "本句还没有识别到词"
                    return@launch
                }
                val segId = currentSegmentId ?: return@launch
                val revision = ++composeRevision
                _statusText.value = "正在补全句子…"
                val result = try {
                    transport.compose(sessionId, segId, revision, gestures.toList(), tokens.agentToken)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _statusText.value = error.message ?: "补全请求失败"
                    return@launch
                }
                if (!running) return@launch
                if (result.segmentId != segId || result.revision != revision) {
                    _statusText.value = "Agent 返回的段 ID 或修订号不匹配"
                    return@launch
                }
                val sentence = result.sentence
                if (sentence == null) {
                    _statusText.value = "未能确定句子：${result.status}；可继续补词或重打"
                    return@launch
                }
                _updates.emit(
                    RecognitionUpdate(
                        sequenceEpoch = epoch,
                        segmentId = segId,
                        draftText = sentence,
                        tokenSpans = listOf(
                            TokenSpan(sentence, sentenceStartPtsUs, lastClipEndPtsUs, stable = true),
                        ),
                        confidence = null,   // Agent 无数值置信度，不伪造
                        boundary = BoundarySignal(
                            cutoffPtsUs = lastClipEndPtsUs,
                            requiredFutureContextUs = 0,
                            reliability = if (result.needsConfirmation) {
                                BoundaryReliability.UNCERTAIN
                            } else {
                                BoundaryReliability.RELIABLE
                            },
                            source = BoundarySource.MODEL,
                        ),
                    ),
                )
                gestures.clear()
                currentSegmentId = null
                _statusText.value = if (result.needsConfirmation) "组句待核对" else null
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun onClip(file: File, startPtsUs: Long, endPtsUs: Long) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        if (!running || bytes == null) return
        retainForDebug(bytes)
        lock.withLock {
            if (!running) return
            _statusText.value = "正在识别第 ${gestures.size + 1} 个词…"
            val result = try {
                transport.recognize(bytes, tokens.cvToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _statusText.value = error.message ?: "识别请求失败"
                return
            }
            if (!running) return
            if (result.status == "OK" && result.candidates.isNotEmpty()) {
                if (gestures.isEmpty()) {
                    sentenceStartPtsUs = startPtsUs
                    currentSegmentId = "clip-$epoch-${++sentenceSeq}"
                }
                lastClipEndPtsUs = endPtsUs
                gestures += result
                _updates.emit(
                    RecognitionUpdate(
                        sequenceEpoch = epoch,
                        segmentId = currentSegmentId,
                        draftText = gestures.joinToString(" · ") { it.candidates.first().label },
                    ),
                )
                _statusText.value = "已收 ${gestures.size} 词；打完点「完成本句」"
            } else {
                _needsRepeat.emit(Unit)
                // 带上帧数/手部帧占比：区分「切片坏了」（帧数异常少）与
                // 「机位看不到手」（帧数正常但占比低）两类根因
                _statusText.value = "该词未加入：${result.status}" +
                    "（${result.frames} 帧、手部 ${(result.anyHandFraction * 100).toInt()}%），请重打"
            }
        }
    }

    /** training 联调验尸：滚动保留最近 [MAX_RETAINED_CLIPS] 个已上传切片 */
    private fun retainForDebug(bytes: ByteArray) {
        val dir = debugRetainDir ?: return
        runCatching {
            dir.mkdirs()
            val existing = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            existing.take(maxOf(0, existing.size - MAX_RETAINED_CLIPS + 1)).forEach { it.delete() }
            File(dir, "clip-${System.currentTimeMillis()}.mp4").writeBytes(bytes)
        }
    }

    companion object {
        /** 切片窗口默认值（秒）；用户可在设置中定义，start 时读取 */
        const val DEFAULT_WINDOW_SECONDS = 2.0

        /** 待识别积压上限（另有一段在识别中）；溢出即丢句重打，不无限排队 */
        const val CLIP_BACKLOG_CAPACITY = 2

        private const val MAX_RETAINED_CLIPS = 5
    }
}

/** HTTP 传输实现：词级 CV + 组句 Agent（客户端由 SignApp 注入蜂窝绑定 provider） */
class HttpClipTransport(
    private val cvClient: LocalVideoCvClient,
    private val composeClient: LocalVideoComposeClient,
) : ClipTransport {
    override suspend fun recognize(videoBytes: ByteArray, token: String): CvResult =
        cvClient.recognizeBytes(videoBytes, token)

    override suspend fun compose(
        sessionId: String,
        segmentId: String,
        revision: Int,
        gestures: List<CvResult>,
        token: String,
    ): ComposeResult = composeClient.compose(sessionId, segmentId, revision, gestures, token)
}
