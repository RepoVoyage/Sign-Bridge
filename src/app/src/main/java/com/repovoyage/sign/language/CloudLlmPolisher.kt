package com.repovoyage.sign.language

import android.os.SystemClock
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端失败（非 2xx / 网络 / 校验）：LanguageProcessor 据此判断降级与重试，
 * 期限内不自动重试（agent/docs/API.md §4：retryable 仅表示技术上可重试）。
 */
class CloudPolishException(
    val code: String,
    val retryable: Boolean,
    message: String,
) : Exception(message) {
    companion object {
        const val DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"      // 预算耗尽，未发起请求
        const val RESPONSE_MISMATCH = "RESPONSE_MISMATCH"      // 回显 segmentId/revision 不符
        const val MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"      // 网络层异常
        const val INVALID_RESPONSE = "MODEL_INVALID_RESPONSE"  // 响应无法解析/字段非法
    }
}

/**
 * P7 云端 LlmPolisher（agent/docs/API.md：POST /v1/polish）。
 * org.json + HttpURLConnection，无新增依赖；OkHttp/网络绑定（蜂窝 Network）
 * 与 HTTPS 信任配置由装配层注入。凭据 [serviceApiKey] 不写日志。
 *
 * 期限：deadlineMonoMs 为手机单调时钟绝对时刻；剩余预算经
 * `X-Remaining-Budget-Ms` 头告知服务端（1–10000），connect/read 超时同值。
 */
class CloudLlmPolisher(
    private val baseUrl: String,
    private val serviceApiKey: String,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
) : LlmPolisher {

    override suspend fun polish(input: PolishInput, deadlineMonoMs: Long): PolishOutput =
        withContext(Dispatchers.IO) { polishBlocking(input, deadlineMonoMs) }

    private fun polishBlocking(input: PolishInput, deadlineMonoMs: Long): PolishOutput {
        val startedAt = monoMs()
        val remaining = deadlineMonoMs - startedAt
        if (remaining <= 0) {
            throw CloudPolishException(
                CloudPolishException.DEADLINE_EXCEEDED, false, "期限已耗尽，不发起请求",
            )
        }
        val budgetMs = remaining.coerceAtMost(MAX_BUDGET_MS)
        val url = URL(baseUrl.trimEnd('/') + "/v1/polish")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = budgetMs.coerceAtLeast(1).toInt()
            conn.readTimeout = budgetMs.coerceAtLeast(1).toInt()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $serviceApiKey")
            conn.setRequestProperty("X-Remaining-Budget-Ms", budgetMs.coerceAtLeast(1).toString())
            conn.outputStream.use { it.write(encodeRequest(input).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { runCatching { it.readBytes().toString(Charsets.UTF_8) }.getOrNull() }
                ?: ""
            if (status in 200..299) {
                return decodeResponse(input, body, monoMs() - startedAt)
            }
            throw decodeError(status, body)
        } catch (e: IOException) {
            throw CloudPolishException(
                CloudPolishException.MODEL_UNAVAILABLE, true, "云端网络异常：${e.message}",
            )
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------- 编解码

    private fun encodeRequest(input: PolishInput): String {
        val root = JSONObject()
            .put("sessionId", input.sessionId)
            .put("segmentId", input.segmentId)
            .put("revision", input.revision)
            .put("rawChinese", input.rawChinese)
        root.put("targetLanguages", JSONArray(input.targetLanguages.map { it.tag }))
        // 上下文：已确认句子简化为 {segmentId, rawChinese}，最近优先最多 5 条
        val context = JSONArray()
        input.contextSentences.takeLast(MAX_CONTEXT).forEach {
            context.put(JSONObject().put("segmentId", it.segmentId).put("rawChinese", it.rawChinese))
        }
        root.put("context", context)
        return root.toString()
    }

    private fun decodeResponse(input: PolishInput, body: String, elapsedMs: Long): PolishOutput {
        val root = try {
            JSONObject(body)
        } catch (e: org.json.JSONException) {
            throw CloudPolishException(
                CloudPolishException.INVALID_RESPONSE, true, "响应 JSON 解析失败",
            )
        }
        if (root.optString("segmentId") != input.segmentId || root.optInt("revision") != input.revision) {
            throw CloudPolishException(
                CloudPolishException.RESPONSE_MISMATCH, false,
                "回显 segmentId/revision 与请求不符，结果丢弃",
            )
        }
        try {
            val polished = if (root.isNull("polishedChinese")) null else root.getString("polishedChinese")
            val translations = mutableMapOf<LangCode, String>()
            root.optJSONObject("translations")?.let { t ->
                t.keys().forEach { tag -> translations[LangCode(tag)] = t.getString(tag) }
            }
            val issues = mutableListOf<FidelityIssue>()
            root.optJSONArray("issues")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val issue = arr.getJSONObject(i)
                    issues += FidelityIssue(
                        language = LangCode(issue.getString("language")),
                        code = FidelityIssueCode.valueOf(issue.getString("code")),
                        message = issue.getString("message"),
                    )
                }
            }
            return PolishOutput(polished, translations, issues, OutputSource.CLOUD, elapsedMs)
        } catch (e: IllegalArgumentException) {
            throw CloudPolishException(
                CloudPolishException.INVALID_RESPONSE, true, "issue code 非法：${e.message}",
            )
        } catch (e: org.json.JSONException) {
            throw CloudPolishException(
                CloudPolishException.INVALID_RESPONSE, true, "响应字段非法",
            )
        }
    }

    private fun decodeError(status: Int, body: String): CloudPolishException {
        try {
            val error = JSONObject(body).getJSONObject("error")
            return CloudPolishException(
                code = error.getString("code"),
                retryable = error.getBoolean("retryable"),
                message = error.getString("message"),
            )
        } catch (_: Exception) {
            // 非 JSON 错误体（网关/代理层）：5xx/网关类视为可重试
            return CloudPolishException(
                "HTTP_$status", status >= 500 || status == 429, "HTTP $status（无错误体）",
            )
        }
    }

    private companion object {
        const val MAX_BUDGET_MS = 10_000L      // agent 契约：单请求模型时限上限 10s
        const val MAX_CONTEXT = 5              // agent 契约：context 最多 5 项
    }
}
