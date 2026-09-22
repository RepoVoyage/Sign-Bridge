package com.repovoyage.sign.p7

import com.repovoyage.sign.language.CloudLlmPolisher
import com.repovoyage.sign.language.CloudPolishException
import com.repovoyage.sign.language.FidelityIssueCode
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.PolishInput
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 云端 LlmPolisher × agent /v1/polish 契约联调（真实 loopback HTTP）。
 * 覆盖：请求字段/头/上下文简化、200 响应映射、issues 解析、错误码/可重试、
 * 回显不符、期限耗尽不发起、网络异常映射。
 */
class CloudLlmPolisherTest {

    private lateinit var server: AgentServer

    @Before
    fun setUp() {
        server = AgentServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun polisher() = CloudLlmPolisher(
        baseUrl = "http://127.0.0.1:${server.port}",
        serviceApiKey = "test-key",
        monoMs = { 1_000L },
    )

    private fun contextSentence(seg: String, text: String) = ConfirmedSentence(
        sessionId = "11111111-2222-3333-4444-555555555555",
        streamGeneration = 1, sequenceEpoch = 1, segmentId = seg, revision = 1,
        startPtsUs = 0, endPtsUs = 9_000, rawChinese = text,
        confidence = null, userConfirmed = false,
    )

    private val input = PolishInput(
        sessionId = "11111111-2222-3333-4444-555555555555",
        segmentId = "seg-1",
        revision = 3,
        rawChinese = "我 需要 帮助",
        targetLanguages = listOf(LangCode("zh-CN"), LangCode("en-US")),
        contextSentences = listOf(contextSentence("seg-0", "你好"), contextSentence("seg-00", "谢谢")),
    )

    private fun okBody() = """
        {"segmentId":"seg-1","revision":3,"polishedChinese":"我需要帮助。",
         "translations":{"en-US":"I need help."},"issues":[]}
    """.trimIndent()

    @Test
    fun `请求契约与正常响应映射`() = runBlocking {
        server.start(listOf(AgentServer.ScriptedResponse(200, okBody())))
        val out = polisher().polish(input, deadlineMonoMs = 11_000)

        assertEquals("我需要帮助。", out.polishedChinese)
        assertEquals(mapOf(LangCode("en-US") to "I need help."), out.translations)
        assertTrue(out.issues.isEmpty())
        assertEquals(OutputSource.CLOUD, out.source)
        assertTrue(out.elapsedMs >= 0)

        val req = synchronized(server.requests) { server.requests.single() }
        assertEquals("/v1/polish", req.path)
        assertEquals("Bearer test-key", req.headers["authorization"])
        assertEquals("10000", req.headers["x-remaining-budget-ms"])   // min(剩余 10s, 上限 10s)
        org.json.JSONObject(req.body).let { body ->
            assertEquals("11111111-2222-3333-4444-555555555555", body.getString("sessionId"))
            assertEquals("seg-1", body.getString("segmentId"))
            assertEquals(3, body.getInt("revision"))
            assertEquals("我 需要 帮助", body.getString("rawChinese"))
            val langs = body.getJSONArray("targetLanguages")
            assertEquals(listOf("zh-CN", "en-US"), (0 until langs.length()).map { langs.getString(it) })
            val ctx = body.getJSONArray("context")
            assertEquals(2, ctx.length())
            assertEquals("seg-0", ctx.getJSONObject(0).getString("segmentId"))
            assertEquals("你好", ctx.getJSONObject(0).getString("rawChinese"))
        }
    }

    @Test
    fun `issues 逐语言解析`() = runBlocking {
        val body = """
            {"segmentId":"seg-1","revision":3,"polishedChinese":"我需要帮助。",
             "translations":{"en-US":"I need help."},
             "issues":[
               {"language":"ja-JP","code":"UNAVAILABLE","message":"该语言翻译未完成。"},
               {"language":"en-US","code":"AMBIGUITY","message":"请核对原句所指对象。"}]}
        """.trimIndent()
        server.start(listOf(AgentServer.ScriptedResponse(200, body)))
        val out = polisher().polish(input, deadlineMonoMs = 11_000)
        assertEquals(2, out.issues.size)
        val ja = out.issues.first { it.language == LangCode("ja-JP") }
        assertEquals(FidelityIssueCode.UNAVAILABLE, ja.code)
        val en = out.issues.first { it.language == LangCode("en-US") }
        assertEquals(FidelityIssueCode.AMBIGUITY, en.code)
    }

    @Test
    fun `错误响应携带 code 与 retryable`() = runBlocking {
        val body = """
            {"segmentId":"seg-1","revision":3,
             "error":{"code":"MODEL_TIMEOUT","message":"期限内未完成。","retryable":true}}
        """.trimIndent()
        server.start(listOf(AgentServer.ScriptedResponse(504, body)))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 11_000)
            error("应抛出 CloudPolishException")
        } catch (e: CloudPolishException) { e }
        assertEquals("MODEL_TIMEOUT", e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun `回显 segmentId 不符视为无效响应`() = runBlocking {
        val body = """
            {"segmentId":"seg-OTHER","revision":3,"polishedChinese":null,
             "translations":{},"issues":[]}
        """.trimIndent()
        server.start(listOf(AgentServer.ScriptedResponse(200, body)))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 11_000)
            error("应抛出 CloudPolishException")
        } catch (e: CloudPolishException) { e }
        assertEquals(CloudPolishException.RESPONSE_MISMATCH, e.code)
    }

    @Test
    fun `期限已耗尽不发起请求`() = runBlocking {
        server.start(listOf(AgentServer.ScriptedResponse(200, okBody())))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 1_000)   // 已到期限（monoMs=1000）
            error("应抛出 CloudPolishException")
        } catch (e: CloudPolishException) { e }
        assertEquals(CloudPolishException.DEADLINE_EXCEEDED, e.code)
        assertTrue(synchronized(server.requests) { server.requests.isEmpty() })
    }

    @Test
    fun `网络连接异常映射为可重试失败`() = runBlocking {
        server.close()   // 无监听端口
        val e = try {
            polisher().polish(input, deadlineMonoMs = 11_000)
            error("应抛出 CloudPolishException")
        } catch (e: CloudPolishException) { e }
        assertTrue(e.retryable)
    }
}
