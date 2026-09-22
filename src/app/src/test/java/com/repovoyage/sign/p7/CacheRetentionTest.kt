package com.repovoyage.sign.p7

import com.repovoyage.sign.cache.CachedSentenceRecord
import com.repovoyage.sign.cache.SentenceCacheRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 缓存保留策略验收（API.md §8 初始值：最近 90 天且 ≤10000 条，任一超限清理）。
 */
class CacheRetentionTest {

    private val policy = SentenceCacheRetention()
    private val dayMs = 24 * 3600 * 1000L
    private val now = 1_000_000_000L

    private fun rec(key: String, ageDays: Long) = CachedSentenceRecord(key, now - ageDays * dayMs)

    @Test
    fun `超龄记录删除未满保留`() {
        val evict = policy.evict(listOf(rec("a", 91), rec("b", 89), rec("c", 90)), now)
        assertEquals(listOf("a"), evict.map { it.key })
    }

    @Test
    fun `恰满 90 天不删`() {
        assertEquals(0, policy.evict(listOf(rec("a", 90)), now).size)
    }

    @Test
    fun `超量删最老回到限内`() {
        // 10001 条全部在 90 天内（毫秒级年龄区分新旧）：it=1 最新 … it=10001 最老
        // （原用例以天为年龄单位，10001 条中 9911 条超龄，与「超龄必删」用例矛盾）
        val records = (1..10_001).map { CachedSentenceRecord("k%05d".format(10_001 - it), now - it) }
        val evict = policy.evict(records, now)
        assertEquals(1, evict.size)
        assertEquals("k00000", evict.single().key)     // 最老的被清
    }

    @Test
    fun `恰一万条不删`() {
        val records = (1..10_000).map { rec("k$it", 1) }
        assertEquals(0, policy.evict(records, now).size)
    }

    @Test
    fun `超龄与超量同时触发各自清理`() {
        // 9999 条新鲜 + 2 条超龄 = 10001 条：删 2 条超龄后回到限内，不再按量删
        val records = (1..9_999).map { rec("n$it", 1) } + listOf(rec("old1", 200), rec("old2", 300))
        val evictKeys = policy.evict(records, now).map { it.key }.toSet()
        assertEquals(setOf("old1", "old2"), evictKeys)
    }

    @Test
    fun `空缓存与空入参安全`() {
        assertTrue(policy.evict(emptyList(), now).isEmpty())
    }
}
