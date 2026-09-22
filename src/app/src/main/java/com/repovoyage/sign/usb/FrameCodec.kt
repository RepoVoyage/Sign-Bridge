package com.repovoyage.sign.usb

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * USB 线协议分帧 codec（API.md §9.2 / ARCHITECTURE.md §2.8.2）：
 * `uint32 大端 headerLen | UTF-8 JSON header | payload`，接收端循环读满。
 *
 * 校验失败（长度不符/JSON 非法/超上限/EOF 不满/超截止时间）一律返回 null，
 * 由调用方断开连接。上限【初始值】：JSON header ≤ 16 KiB；图像 payload（FRAME）
 * ≤ 32 MiB；控制消息 payload ≤ 64 KiB。整条消息用单一截止时间（不按小片续期）。
 */
class FrameCodec(private val input: InputStream) {

    data class Message(val header: JSONObject, val payload: ByteArray)

    /**
     * 读一条完整消息；流正常结束返回 null。
     * @param deadlineMonoMs 整条消息的截止时刻（mono 时钟）；null = 不限时（测试用）
     */
    fun readMessage(deadlineMonoMs: Long? = null): Message? {
        val headerLen = readFull(4, deadlineMonoMs)?.let { it.beUint32() } ?: return null
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) return null
        val header = readFull(headerLen, deadlineMonoMs)
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            ?: return null
        val payloadLen = header.optLong("payloadLen", 0L)
        if (payloadLen < 0 || payloadLen > payloadCapFor(header)) return null
        val payload = readFull(payloadLen.toInt(), deadlineMonoMs) ?: return null
        return Message(header, payload)
    }

    /** 循环读满 len 字节；EOF 不满/超截止时间返回 null */
    private fun readFull(len: Int, deadlineMonoMs: Long?): ByteArray? {
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        var filled = 0
        while (filled < len) {
            if (deadlineMonoMs != null && SystemClock.elapsedRealtime() > deadlineMonoMs) return null
            val n = input.read(out, filled, len - filled)
            if (n < 0) return null
            filled += n
        }
        return out
    }

    private fun ByteArray.beUint32(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_IMAGE_PAYLOAD_BYTES = 32 * 1024 * 1024
        const val MAX_CONTROL_PAYLOAD_BYTES = 64 * 1024

        /** 图像帧按图像 payload 上限，其余消息按控制消息上限 */
        internal fun payloadCapFor(header: JSONObject): Long =
            if (header.optString("type") == "FRAME") MAX_IMAGE_PAYLOAD_BYTES.toLong()
            else MAX_CONTROL_PAYLOAD_BYTES.toLong()

        /** 写方向编码（双向一致分帧） */
        fun encode(header: JSONObject, payload: ByteArray = ByteArray(0)): ByteArray {
            val json = header.toString().toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(4 + json.size + payload.size)
            out.write(
                byteArrayOf(
                    (json.size ushr 24).toByte(), (json.size ushr 16).toByte(),
                    (json.size ushr 8).toByte(), json.size.toByte(),
                ),
            )
            out.write(json)
            out.write(payload)
            return out.toByteArray()
        }
    }
}
