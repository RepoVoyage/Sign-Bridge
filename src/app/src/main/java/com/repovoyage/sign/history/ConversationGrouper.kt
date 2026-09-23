package com.repovoyage.sign.history

/**
 * 历史对话归并（2026-09-23 用户需求）：
 * - [GroupMode.SESSION]：按 sessionId 分组（采集/会话边界）
 * - [GroupMode.TIME_GAP]：按时间间隔归并——相邻两句墙钟间隔超过
 *   [TIME_GAP_MS]（30 分钟【初始值】）即视为新的一段对话
 *
 * 输入须为墙钟降序（observeHistory 的既有顺序）；组 key 取组内最早一句的
 * wallTimeStart（字符串化，作为重命名持久化的稳定键）。
 */
enum class GroupMode { SESSION, TIME_GAP }

data class ConversationGroup(
    val key: String,
    val startMs: Long,
    val sessionIds: Set<String>,
    val entries: List<SentenceWithResults>,   // 组内新→旧
) {
    companion object {
        const val TIME_GAP_MS = 30 * 60 * 1000L
    }
}

fun groupConversations(
    entriesDescending: List<SentenceWithResults>,
    mode: GroupMode,
    gapMs: Long = ConversationGroup.TIME_GAP_MS,
): List<ConversationGroup> = when (mode) {
    GroupMode.SESSION -> entriesDescending
        .groupBy { it.sentence.sessionId }
        .map { (sessionId, list) ->
            ConversationGroup(
                key = sessionId,
                startMs = list.last().sentence.wallTimeStart,
                sessionIds = setOf(sessionId),
                entries = list,
            )
        }
    GroupMode.TIME_GAP -> {
        val groups = mutableListOf<MutableList<SentenceWithResults>>()
        for (entry in entriesDescending) {
            val current = groups.lastOrNull()
            val prev = current?.last()
            if (current == null || prev!!.sentence.wallTimeStart - entry.sentence.wallTimeStart > gapMs) {
                groups.add(mutableListOf(entry))
            } else {
                current.add(entry)
            }
        }
        groups.map { list ->
            ConversationGroup(
                key = list.last().sentence.wallTimeStart.toString(),
                startMs = list.last().sentence.wallTimeStart,
                sessionIds = list.map { it.sentence.sessionId }.toSet(),
                entries = list,
            )
        }
    }
}
