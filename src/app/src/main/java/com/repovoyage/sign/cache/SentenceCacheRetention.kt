package com.repovoyage.sign.cache

data class CachedSentenceRecord(
    val key: String,                      // (sessionId, segmentId) 的持久化主键
    val processedAtWallMs: Long,
)

/**
 * 缓存保留策略（API.md §8 初始值）：最近 90 天且 ≤10000 条，任一超限清理。
 */
class SentenceCacheRetention(
    private val maxAgeDays: Long = 90,
    private val maxCount: Int = 10_000,
) {

    /** 返回应删除的记录：超龄全部删除；超量时再删最老直至回到限内 */
    fun evict(records: List<CachedSentenceRecord>, nowWallMs: Long): List<CachedSentenceRecord> = TODO("P7")
}
