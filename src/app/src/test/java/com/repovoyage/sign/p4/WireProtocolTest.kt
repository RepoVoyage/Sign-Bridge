package com.repovoyage.sign.p4

import org.junit.Ignore
import org.junit.Test

/**
 * P4 USB Bridge 线协议验收（plan.md §1 P4；协议 v1 见 API.md §9）。
 * 纯编码/分帧/校验逻辑全部可单测（fake clock 测超时），真实吞吐靠 USB 实测。
 * P4 开工时逐条写成真测试。
 */
@Ignore("P4：待 WireCodec/USB 桥实现（线协议 v1 见 API.md §9）")
class WireProtocolTest {

    @Test
    fun `分帧编解码 roundtrip`() {
        fail("P4 待实现：uint32 大端 headerLen + UTF-8 JSON header + payload，encode→decode 还原")
    }

    @Test
    fun `协议版本必须为 1`() {
        fail("P4 待实现：header.proto != 1 → 断开")
    }

    @Test
    fun `长度上限校验`() {
        fail("P4 待实现：JSON header > 16 KiB / 图像 payload > 32 MiB / 控制消息 > 64 KiB → 断开")
    }

    @Test
    fun `FRAME 声明长度与尺寸计算一致`() {
        fail("P4 待实现：payloadLen == width×height×格式字节数（I420: 1.5 Bpp / RGB888: 3 Bpp），不符拒绝")
    }

    @Test
    fun `认证 5 秒超时关闭并恢复可连接`() {
        fail("P4 待实现：fake clock 推进，连接接受后 5s 未完成 AUTH → 关闭且端口可再连")
    }

    @Test
    fun `单条消息 2 秒读满否则断开`() {
        fail("P4 待实现：收到首字节后 2s 内未读满整条（不按小片续期）→ 断开")
    }

    @Test
    fun `心跳 2 秒空闲发送 6 秒未收断开`() {
        fail("P4 待实现：fake clock 驱动 HEARTBEAT 间隔与超时判定")
    }

    @Test
    fun `序号连续性在 END 时核对`() {
        fail("P4 待实现：帧序号不连续 → END 不允许发 COMPLETE（status=INCOMPLETE）")
    }

    private fun fail(message: String): Nothing = throw NotImplementedError(message)
}
