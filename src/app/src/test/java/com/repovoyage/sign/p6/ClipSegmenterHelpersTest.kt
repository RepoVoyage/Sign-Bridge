package com.repovoyage.sign.p6

import com.repovoyage.sign.video.annexBVclSample
import com.repovoyage.sign.video.extractParameterSets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 切片器纯函数验收（MediaMuxer 交互留真机联调）：Annex-B 样本保留
 * 与 CSD 提取——MP4 段可解码性的前提。
 */
class ClipSegmenterHelpersTest {

    private fun nal(vararg bytes: Int): ByteArray = bytes.map { it.toByte() }.toByteArray()

    private val sc4 = nal(0, 0, 0, 1)
    private val sc3 = nal(0, 0, 1)

    @Test
    fun `MediaMuxer 样本只保留 VCL NAL 并使用 Annex-B 起始码`() {
        // SPS(67) + PPS(68) + IDR(65 AA BB) + 非 IDR slice(41 CC)，3/4 字节起始码混用
        val data = sc4 + nal(0x67, 0x42, 0x00, 0x1E) +
            sc3 + nal(0x68, 0xCE, 0x38, 0x80) +
            sc4 + nal(0x65, 0xAA, 0xBB) +
            sc3 + nal(0x41, 0xCC)
        val sample = annexBVclSample(data)!!
        val expected = sc4 + nal(0x65, 0xAA, 0xBB) + sc4 + nal(0x41, 0xCC)
        assertArrayEquals(expected, sample)
    }

    @Test
    fun `样本裁掉 4 字节起始码归入前一 NAL 尾部的前导零`() {
        // IDR 载荷以非零结尾，后随 4 字节起始码的 slice：IDR 长度不得含前导 00
        val data = sc4 + nal(0x65, 0x11, 0x22) + sc4 + nal(0x41, 0x33)
        val sample = annexBVclSample(data)!!
        assertArrayEquals(sc4 + nal(0x65, 0x11, 0x22) + sc4 + nal(0x41, 0x33), sample)
    }

    @Test
    fun `纯参数集帧无 VCL——样本返回 null`() {
        val data = sc4 + nal(0x67, 0x42) + sc3 + nal(0x68, 0xCE)
        assertNull(annexBVclSample(data))
    }

    @Test
    fun `CSD 提取——SPS 与 PPS 各带 4 字节起始码`() {
        val data = sc3 + nal(0x67, 0x42, 0x00, 0x1E) + sc4 + nal(0x68, 0xCE, 0x38) + sc3 + nal(0x65, 0xAA)
        val (sps, pps) = extractParameterSets(data)!!
        assertArrayEquals(sc4 + nal(0x67, 0x42, 0x00, 0x1E), sps)
        assertArrayEquals(sc4 + nal(0x68, 0xCE, 0x38), pps)
    }

    @Test
    fun `CSD 不齐返回 null`() {
        val data = sc4 + nal(0x67, 0x42) + sc3 + nal(0x65, 0xAA)
        assertNull(extractParameterSets(data))
    }
}
