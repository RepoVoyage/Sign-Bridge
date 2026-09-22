package com.repovoyage.sign.p3

import org.junit.Ignore
import org.junit.Test

/**
 * P3 取流 + 解码验收（plan.md §1 P3；数据契约见 API.md §2，队列初始值 §2.3）。
 * App 侧最高技术风险阶段：帧边界、时间戳、有界队列三块都可单测，
 * MediaCodec 本身靠真机压测。P3 开工时逐条写成真测试。
 */
@Ignore("P3：待帧聚合/解码链实现（契约见 API.md §2）")
class FrameAggregationTest {

    @Test
    fun `同 timestamp 分片聚合为单帧`() {
        fail("P3 待实现：多片同 ts 拼接，输出一个 EncodedFrame，长度=分片之和")
    }

    @Test
    fun `timestamp 切换时提交前一帧`() {
        fail("P3 待实现：新 ts 到达 → 先提交旧帧再开始新聚合")
    }

    @Test
    fun `未确认完整的尾帧丢弃`() {
        fail("P3 待实现：流结束时未凑齐的聚合缓冲不产出 EncodedFrame")
    }

    @Test
    fun `聚合超限丢弃并重新同步`() {
        fail("P3 待实现：单帧 4 MiB / 同 timestamp 500ms 超限 → 丢当前聚合，等参数集+关键帧重同步")
    }

    @Test
    fun `毫秒转微秒且不跨时钟比较`() {
        fail("P3 待实现：ptsUs = timestampMs × 1000；队列年龄/超时只用 monoMs")
    }

    @Test
    fun `旧 streamGeneration 数据不进入当前帧流`() {
        fail("P3 待实现：重连后旧代次分片被丢弃")
    }

    @Test
    fun `只有验证过的关键帧标记 isSyncPoint`() {
        fail("P3 待实现：参数集后首个 IDR 才可作随机访问点")
    }

    private fun fail(message: String): Nothing = throw NotImplementedError(message)
}
