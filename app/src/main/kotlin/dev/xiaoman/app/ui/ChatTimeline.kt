package dev.xiaoman.app.ui

import dev.xiaoman.app.session.ChatItem

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
