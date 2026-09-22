package com.repovoyage.sign.p7

import org.junit.Ignore
import org.junit.Test

/**
 * P7 语言处理 / TTS / 缓存验收（plan.md §1 P7；契约见 API.md §6/§7/§8）。
 * 可单测部分：去重键、队列与超时状态机、缓存保留策略（Room 用 instrumented 或
 * 抽象出纯策略函数后 JVM 测）。保真样本集（否定/数字）靠验收样本。P7 开工时逐条写成真测试。
 */
@Ignore("P7：待 TtsManager/缓存策略实现（契约见 API.md §6/§7/§8）")
class TtsAndCacheTest {

    // ---- TTS（§7）----

    @Test
    fun `自动播报去重键为 sessionId segmentId language`() {
        fail("P7 待实现：同键二次 enqueue → Duplicate")
    }

    @Test
    fun `串行一次一条且待播缓存 3 句`() {
        fail("P7 待实现：第 4 句入队 → Rejected(容量满)")
    }

    @Test
    fun `进入待播 5 秒未开始转 MarkedUnspoken`() {
        fail("P7 待实现：fake clock 驱动 5s 超时 → MarkedUnspoken 事件")
    }

    @Test
    fun `迟到回调只处理匹配当前 utteranceId`() {
        fail("P7 待实现：旧 utteranceId 的 onDone/onError 不推进新任务；同步 ERROR 与回调幂等终结")
    }

    @Test
    fun `仅 READY 文本进入播报`() {
        fail("P7 待实现：DRAFT/FINALIZING/待核对/中断文本 enqueue 被拒绝")
    }

    // ---- 缓存（§8）----

    @Test
    fun `保留策略为 90 天且一万条任一超限清理`() {
        fail("P7 待实现：注入 wallMs，超龄或超量触发清理且只清理到限内")
    }

    @Test
    fun `缓存关闭后停止写入`() {
        fail("P7 待实现：开关 off 时 upsert 不落库，写入失败不影响实时字幕")
    }

    // ---- 语言处理（§6）----

    @Test
    fun `去重键为 sessionId segmentId revision language`() {
        fail("P7 待实现：同键仅保留一个有效结果，旧 settingsRevision 结果拒绝")
    }

    @Test
    fun `超时语言标记 UNAVAILABLE 不冒充译文`() {
        fail("P7 待实现：本地 3s/云端 10s 期限（fake clock），text=null + UNAVAILABLE")
    }

    private fun fail(message: String): Nothing = throw NotImplementedError(message)
}
