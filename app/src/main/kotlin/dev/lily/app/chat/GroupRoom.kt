package dev.lily.app.chat

import dev.lily.app.session.ChatItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 一间群聊。
 *
 * 规则只有一条：你说一句，群里每个人各得到一次说话的机会，依次来。
 * 她可以说话，也可以选择不说 —— 真人在群里也常常看一眼就走，
 * 所以模型回「…」时我们就当她没出声，不硬凑一句附和。
 *
 * 群聊不写私聊记录，也不碰她的来电、发布和主动消息。
 */
class GroupRoom(
    private val group: () -> GroupChat?,
    private val nameOf: (String) -> String,
    private val ask: suspend (String, String) -> String?,
    private val scope: CoroutineScope,
) {
    private val ids = AtomicLong(0)
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var job: Job? = null

    /** 你说一句，依次给每个成员一次机会。返回 false 表示空消息或正在忙。 */
    fun send(text: String): Boolean {
        val chat = group() ?: return false
        val message = text.trim()
        if (message.isEmpty() || _busy.value) return false
        _items.update { (it + ChatItem.User(ids.incrementAndGet(), message)).takeLast(MAX_ITEMS) }
        _busy.value = true
        job = scope.launch {
            try {
                chat.memberIds.forEachIndexed { index, member ->
                    val reply = ask(member, buildPrompt(chat, message))?.trim().orEmpty()
                    if (reply.isEmpty() || reply in SILENT) return@forEachIndexed
                    _items.update {
                        (it + ChatItem.Assistant(ids.incrementAndGet(), reply, from = member)).takeLast(MAX_ITEMS)
                    }
                    if (index != chat.memberIds.lastIndex) delay(BETWEEN_MS)
                }
            } finally {
                _busy.value = false
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        _busy.value = false
    }

    /** 解散或清空时把内存里的消息放掉；群本身由 GroupChatStore 管。 */
    fun clear() {
        stop()
        _items.value = emptyList()
    }

    private fun buildPrompt(chat: GroupChat, message: String): String {
        val transcript = _items.value.takeLast(TRANSCRIPT).mapNotNull { item ->
            when (item) {
                is ChatItem.User -> "我：${item.text}"
                is ChatItem.Assistant -> "${item.from?.let(nameOf)?.takeIf { it.isNotBlank() } ?: "某人"}：${item.text}"
                else -> null
            }
        }.joinToString("\n")
        val members = chat.memberIds.joinToString("、") { nameOf(it) }
        return buildString {
            append("你现在在一个群聊里，群名「${chat.displayName(nameOf)}」，群里的人：$members，还有我。")
            if (transcript.isNotBlank()) {
                append("\n最近的发言：\n")
                append(transcript)
            }
            append("\n\n我刚刚说：「$message」")
            append("\n\n用你自己平时说话的方式接一句，像真人在群里随手回：短，口语，最多两行。")
            append("如果你此刻没什么想说的、或者说了也只是附和，就只回一个「…」，别的什么都别写。")
            append("不要在话里提到你是 AI、模型、设定或群聊规则。")
        }
    }

    companion object {
        private const val MAX_ITEMS = 300
        private const val TRANSCRIPT = 14
        private const val BETWEEN_MS = 400L
        private val SILENT = setOf("…", "...", "。", "……")
    }
}
