package com.repovoyage.sign.p7

import com.repovoyage.sign.history.ConversationGroup
import com.repovoyage.sign.history.GroupMode
import com.repovoyage.sign.history.LanguageResultRecord
import com.repovoyage.sign.history.SentenceRecord
import com.repovoyage.sign.history.SentenceWithResults
import com.repovoyage.sign.history.groupConversations
import com.repovoyage.sign.sentence.LangCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史对话归并验收（2026-09-23 用户需求）：按会话分组保持原语义；
 * 按时间分组以 30 分钟间隔切分，边界恰等于间隔不切分。
 */
class ConversationGrouperTest {

    private fun entry(session: String, seg: String, atMs: Long) = SentenceWithResults(
        sentence = SentenceRecord(
            sessionId = session, segmentId = seg, streamGeneration = 1, sequenceEpoch = 1,
            revision = 1, startPtsUs = 0, endPtsUs = 1, wallTimeStart = atMs,
            wallTimeEnd = atMs + 1, rawChinese = "文本$seg", confidence = null,
            userConfirmed = false, modelVersion = "", specChecksum = "",
        ),
        results = listOf(
            LanguageResultRecord(
                sessionId = session, segmentId = seg, language = LangCode("zh-CN"),
                sentenceRevision = 1, settingsRevision = 0, text = "t", status = "READY",
                source = "CLOUD", processedAtWallMs = atMs,
            ),
        ),
    )

    private val gap = ConversationGroup.TIME_GAP_MS

    @Test
    fun `按会话分组以 sessionId 为准`() {
        val entries = listOf(
            entry("s2", "b", 5_000),      // 降序输入
            entry("s1", "a", 4_000),
            entry("s2", "c", 3_000),
        )
        val groups = groupConversations(entries, GroupMode.SESSION)
        assertEquals(setOf("s2", "s1"), groups.map { it.key }.toSet())
        assertEquals(2, groups.first { it.key == "s2" }.entries.size)
        assertEquals(setOf("s2"), groups.first { it.key == "s2" }.sessionIds)
    }

    @Test
    fun `按时间分组以 30 分钟间隔切分`() {
        val t0 = 1_000_000_000L
        val entries = listOf(
            entry("s1", "e", t0),                        // 新对话
            entry("s1", "d", t0 - 300_000),             // 同对话（距 e 5 分钟）
            entry("s1", "c", t0 - gap - 600_000),       // 距 d 35 分钟 → 新对话
            entry("s2", "b", t0 - gap - 900_000),       // 同对话（距 c 5 分钟，跨会话也合并）
            entry("s2", "a", t0 - 4 * gap),             // 更早的对话
        )
        val groups = groupConversations(entries, GroupMode.TIME_GAP)
        assertEquals(3, groups.size)
        assertEquals(listOf("e", "d"), groups[0].entries.map { it.sentence.segmentId })
        assertEquals(listOf("c", "b"), groups[1].entries.map { it.sentence.segmentId })
        assertEquals(setOf("s1", "s2"), groups[1].sessionIds)   // 时间分组可跨会话
        assertEquals((t0 - gap - 900_000).toString(), groups[1].key)  // key = 组内最早句墙钟
    }

    @Test
    fun `间隔恰等于阈值不切分`() {
        val t0 = 1_000_000_000L
        val entries = listOf(entry("s1", "b", t0), entry("s1", "a", t0 - gap))
        val groups = groupConversations(entries, GroupMode.TIME_GAP)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().entries.size)
    }

    @Test
    fun `空历史无分组`() {
        assertEquals(emptyList<ConversationGroup>(), groupConversations(emptyList(), GroupMode.TIME_GAP))
        assertEquals(emptyList<ConversationGroup>(), groupConversations(emptyList(), GroupMode.SESSION))
    }
}
