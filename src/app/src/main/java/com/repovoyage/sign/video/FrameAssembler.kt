package com.repovoyage.sign.video

/**
 * 分片聚合器（ARCHITECTURE.md §2.2.1）：
 * 同 timestamp 分片累积，timestamp 切换提交上一帧；超限/超时/代次切换丢弃当前
 * 聚合并重同步（忽略同 timestamp 残片，等新 timestamp 干净起步）；
 * 非视频分片忽略；流结束时未确认完整的尾帧丢弃。
 * isSyncPoint 由解码准备链的关键帧验证器标记，本层恒为 false。
 */
class FrameAssembler(
    private val maxFrameBytes: Int = 4 * 1024 * 1024,
    private val maxSameTimestampSpanMs: Long = 500,
) {

    /** 投递一个分片；返回因 timestamp 切换而完成的帧（0 或 1 个） */
    fun offer(chunk: StreamChunk): List<EncodedFrame> = TODO("P3")

    /** 流结束/停止：未确认完整的尾帧丢弃，返回空 */
    fun finish(): List<EncodedFrame> = TODO("P3")
}
