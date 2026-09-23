package com.repovoyage.sign.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 词级 CV 服务客户端（模型 B，队友云端部署，端点/错误语义见
 * Docs/第一人称本地视频联调.md）。一段完整 MP4（≤32MiB，一个手语词）→
 * 最多三个候选 + 状态。
 *
 * [clientProvider] 注入 §2.4.7 蜂窝绑定客户端（相机在线时进程默认网络无公网）；
 * 未就绪时回落进程默认网络。改造自 codex/app-local-video-test 分支
 * （原 Uri 文件读取入口随其手动测试界面留在该分支）。
 */
class LocalVideoCvClient(private val clientProvider: () -> OkHttpClient = { OkHttpClient() }) {

    suspend fun recognizeBytes(bytes: ByteArray, token: String): CvResult =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank()) { "请填写 CV 服务令牌" }
            if (bytes.isEmpty()) throw IOException("视频片段为空")
            if (bytes.size > MAX_BYTES) throw IOException("视频超过 32 MiB")
            val request = Request.Builder()
                .url(RECOGNIZE_URL)
                .header("Authorization", "Bearer ${token.trim()}")
                .post(bytes.toRequestBody("video/mp4".toMediaType()))
                .build()
            clientProvider().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(body).optString("error") }.getOrNull()
                        throw IOException("CV HTTP ${response.code}${error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                    }
                    parseCvResponse(body)
                }
        }

    private companion object {
        const val RECOGNIZE_URL = "https://101.37.234.129/v1/recognize"
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}

data class CvCandidate(val label: String, val score: Double)

data class CvResult(
    val status: String,
    val frames: Int,
    val anyHandFraction: Double,
    val candidates: List<CvCandidate>,
    val needsConfirmation: Boolean,
)

internal fun parseCvResponse(body: String): CvResult {
    val json = JSONObject(body)
    val candidates = json.getJSONArray("candidates")
    return CvResult(
        status = json.getString("status"),
        frames = json.getInt("frames"),
        anyHandFraction = json.getDouble("any_hand_fraction"),
        candidates = (0 until candidates.length()).map { index ->
            candidates.getJSONObject(index).let { CvCandidate(it.getString("label"), it.getDouble("score")) }
        },
        needsConfirmation = json.getBoolean("needsConfirmation"),
    )
}
