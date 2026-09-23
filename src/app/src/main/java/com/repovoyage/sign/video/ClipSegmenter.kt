package com.repovoyage.sign.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 固定窗口切片器（P6 联调，2026-09-23 用户定义：固定时长窗口切分）：
 * 消费取流路径分流的编码帧（[com.repovoyage.sign.camera.SdkCameraSession]
 * encodedFrameTap，prep 之后、解码队列之前），**不转码**直接 MediaMuxer
 * 封装为 MP4 段：段起点必须是随机访问帧（IDR），窗口期满即封段并通过
 * [onKeyFrameNeeded] 请求下一个关键帧——GOP 实测超长，不能干等（边界到
 * IDR 到达之间的帧丢弃，MP4 段必须从随机访问点起才可解码）。
 *
 * 过载/换代处理：入口 Channel 溢出（丢 P 帧会破坏参考链）或流代次切换 →
 * 当前段标记作废并丢弃，等待新随机访问点重开。release() 丢弃进行中的段
 * （未满窗口的残段不产出，避免半词进入识别）。
 */
class ClipSegmenter(
    private val outputDir: File,
    private val width: Int,
    private val height: Int,
    private val windowUs: Long,
    private val onKeyFrameNeeded: () -> Unit,
) {

    private val frames = Channel<EncodedFrame>(CHANNEL_CAPACITY)
    private val _segments = Channel<ClipSegment>(Channel.UNLIMITED)

    /** 完成的 MP4 段（消费方负责上传后删除文件） */
    val segments: Flow<ClipSegment> = _segments.receiveAsFlow()

    @Volatile
    private var overflowed = false

    /** 取流协程调用（非阻塞）；溢出 = 当前段已不可用，请求关键帧重同步 */
    fun offer(frame: EncodedFrame) {
        if (frames.trySend(frame).isFailure) {
            overflowed = true
            onKeyFrameNeeded()
        }
    }

    /** 切片消费循环（调用方在自有 scope 启动）；随 frames Channel 关闭而退出 */
    suspend fun run() {
        var muxer: MediaMuxer? = null
        var track = 0
        var file: File? = null
        var startPts = 0L
        var lastPts = 0L
        var generation = -1L
        var corrupted = false
        var framesWritten = 0
        var index = 0

        fun closeSegment(emit: Boolean) {
            val m = muxer ?: return
            // stop() 成功才写全 moov：失败的段文件不可解码，一律丢弃
            val stopped = framesWritten > 0 && runCatching { m.stop() }.isSuccess
            runCatching { m.release() }
            val f = file
            if (emit && !corrupted && stopped && f != null) {
                _segments.trySend(ClipSegment(f, startPts, lastPts))
            } else {
                f?.delete()
            }
            muxer = null
            file = null
            framesWritten = 0
        }

        try {
            for (frame in frames) {
                if (overflowed) {
                    corrupted = true
                    overflowed = false
                }
                val generationChanged = muxer != null && frame.streamGeneration != generation
                if (muxer != null && (generationChanged || frame.ptsUs - startPts >= windowUs || corrupted)) {
                    // 换代残段/坏段丢弃：只有完整窗口的干净段才产出
                    closeSegment(emit = !corrupted && !generationChanged)
                    corrupted = false
                    onKeyFrameNeeded()
                }
                if (muxer == null) {
                    if (!frame.isSyncPoint) continue
                    val csd = extractParameterSets(frame.data) ?: run {
                        onKeyFrameNeeded()
                        continue
                    }
                    val next = File(outputDir, "seg-${frame.streamGeneration}-${++index}.mp4")
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.first))
                        setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd.second))
                    }
                    val m = runCatching {
                        MediaMuxer(next.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            track = it.addTrack(format)
                            it.start()
                        }
                    }.getOrElse {
                        next.delete()
                        onKeyFrameNeeded()
                        continue
                    }
                    muxer = m
                    file = next
                    startPts = frame.ptsUs
                    lastPts = frame.ptsUs
                    generation = frame.streamGeneration
                    corrupted = false
                }
                val sample = annexBToAvcc(frame.data) ?: continue
                val info = MediaCodec.BufferInfo().apply {
                    presentationTimeUs = frame.ptsUs - startPts
                    size = sample.size
                    flags = if (frame.isSyncPoint) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                // 只有真正写入成功才计数：写失败的段是空壳（track 无样本，
                // stop 出的 MP4 服务端解出 0 帧），必须按坏段丢弃
                val written = runCatching {
                    muxer!!.writeSampleData(track, java.nio.ByteBuffer.wrap(sample), info)
                }.isSuccess
                if (written) {
                    framesWritten++
                    lastPts = frame.ptsUs
                } else {
                    corrupted = true
                }
            }
        } finally {
            closeSegment(emit = false)
        }
    }

    fun release() {
        frames.close()
    }

    private companion object {
        const val CHANNEL_CAPACITY = 64
    }
}

/**
 * Annex-B → AVCC（MP4 样本格式）：仅保留 VCL NAL（type 1/5），SPS/PPS/SEI
 * 走 format CSD 不进样本；4 字节起始码的多余前导 0 归上一 NAL 尾部，按
 * [H264DecodePrep] 同款规则裁掉。无 VCL 返回 null。
 */
internal fun annexBToAvcc(data: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream()
    forEachAnnexBNal(data) { start, end ->
        val type = data[start].toInt() and 0x1F
        if (type == NAL_TYPE_SLICE || type == NAL_TYPE_IDR) {
            var e = end
            while (e > start && data[e - 1] == 0.toByte()) e--
            val len = e - start
            out.write(len ushr 24 and 0xFF)
            out.write(len ushr 16 and 0xFF)
            out.write(len ushr 8 and 0xFF)
            out.write(len and 0xFF)
            out.write(data, start, len)
        }
    }
    return if (out.size() == 0) null else out.toByteArray()
}

/** 从帧数据提取 SPS/PPS（各含 4 字节起始码，MediaFormat csd-0/csd-1）；不齐返回 null */
internal fun extractParameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    forEachAnnexBNal(data) { start, end ->
        val type = data[start].toInt() and 0x1F
        if (type != NAL_TYPE_SPS && type != NAL_TYPE_PPS) return@forEachAnnexBNal
        var e = end
        while (e > start && data[e - 1] == 0.toByte()) e--
        val nal = ByteArray(4 + e - start)
        nal[3] = 1
        System.arraycopy(data, start, nal, 4, e - start)
        if (type == NAL_TYPE_SPS) sps = nal else pps = nal
    }
    val s = sps ?: return null
    val p = pps ?: return null
    return s to p
}

/** Annex-B 扫描：以 00 00 01（或 00 00 00 01）分界，对每个 NAL 调 block(payloadStart, end) */
private inline fun forEachAnnexBNal(data: ByteArray, block: (start: Int, end: Int) -> Unit) {
    var i = 0
    var start = -1
    while (i <= data.size - 3) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            if (start >= 0) block(start, i)
            start = i + 3
            i += 3
        } else {
            i++
        }
    }
    if (start >= 0 && start < data.size) block(start, data.size)
}

private const val NAL_TYPE_SLICE = 1
private const val NAL_TYPE_IDR = 5
private const val NAL_TYPE_SPS = 7
private const val NAL_TYPE_PPS = 8
