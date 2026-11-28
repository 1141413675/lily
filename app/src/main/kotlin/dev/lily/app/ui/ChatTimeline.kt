package dev.lily.app.ui

import dev.lily.app.session.ChatItem

/** Fold only adjacent diagnostic entries. Questions, failures and replies always remain visible. */
internal sealed class TimelineRow {
    abstract val id: Long
    data class Message(val item: ChatItem) : TimelineRow() { override val id = item.id }
    data class Process(val entries: List<ChatItem.Progress>) : TimelineRow() { override val id = entries.first().id }
}

internal fun chatTimeline(items: List<ChatItem>): List<TimelineRow> = buildList {
    val pending = mutableListOf<ChatItem.Progress>()
    fun flush() {
        if (pending.isNotEmpty()) { add(TimelineRow.Process(pending.toList())); pending.clear() }
    }
    items.forEach { item ->
        // 旧版留在聊天流里的通话条目不再显示；通话记录统一在通话记录页。
        if (item is ChatItem.CallRecord) return@forEach
        if (item is ChatItem.Progress) pending += item else { flush(); add(TimelineRow.Message(item)) }
    }
    flush()
}

/**
 * 她这一轮开口了没有：只看「我最后一条消息之后」有没有她的话。
 *
 * 用来决定「正在输入」还显不显示。她的回复一落进列表就让位——
 * 语音回复时那条气泡自己会显示「正在准备声音」的三个点，
 * 两套点同时出现会让人以为有两件事在发生。
 */
internal fun awaitingReply(rows: List<TimelineRow>): Boolean {
    val lastMine = rows.indexOfLast { it is TimelineRow.Message && senderKey(it.item) == 0 }
    if (lastMine < 0) return false
    return rows.drop(lastMine + 1).none { it is TimelineRow.Message && sheSpoke(it.item) }
}

/** 她开口了：文字、语音、图片，以及她抛给你的问题。 */
private fun sheSpoke(item: ChatItem): Boolean = when (item) {
    is ChatItem.Assistant, is ChatItem.AssistantImage, is ChatItem.AssistantVoice, is ChatItem.Question -> true
    else -> false
}
