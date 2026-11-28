package dev.lily.app.feed

import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.ModelRole
import dev.lily.app.life.FeedTexts
import dev.lily.app.settings.AppSettings
import dev.lily.llm.LlmClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 你发动态后，她来点赞并留一句短评论。
 * 模型可用就写，失败或没配就退回 [FeedTexts.reaction]；同一条动态只回应一次。
 */
class FeedReactions(
    private val store: FeedStore,
    private val settings: () -> AppSettings,
    private val llm: LlmClientFactory,
    private val scope: CoroutineScope,
    /** 昵称为空时的回退名（当前应用名）。 */
    private val appName: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_LENGTH = 30
        private const val USER = "你"
    }

    fun react(postId: String) {
        scope.launch(Dispatchers.IO) { runCatching { reactNow(postId) } }
    }

    suspend fun reactNow(postId: String): FeedStore.Comment? {
        val post = store.posts.value.firstOrNull { it.id == postId } ?: return null
        if (post.author != FeedStore.AUTHOR_ME) return null
        val name = settings().persona.nameOr(appName)
        // 已经留过她的评论，就不再重复。
        if (post.comments.any { it.who != USER }) return null
        store.likeByHer(postId)
        val current = settings()
        val fromModel = if (current.chat.configured) runCatching { modelReaction(current, name, post) }.getOrNull() else null
        val text = fromModel ?: FeedTexts.reaction(post.text, post.images.isNotEmpty(), post.at)
        return store.addComment(postId, name, text, clock())?.comments?.lastOrNull()
    }

    private suspend fun modelReaction(settings: AppSettings, name: String, post: FeedStore.Post): String? {
        val body = buildString {
            append(post.text)
            if (post.images.isNotEmpty()) append(if (post.text.isBlank()) "（发了一张图）" else "（还配了图）")
        }
        val request = ChatRequest(
            listOf(
                ChatMessage.system(
                    "你是$name，看到对方刚发的动态，留一句自然的短评论。要求：中文，不超过 20 字，一句话；" +
                        "像熟人随口接话，可以问一个具体的小问题；不写早安、晚安、早点睡这类问候；" +
                        "不说教，不用感叹号堆情绪。只输出评论本身，不要引号，不要解释。",
                ),
                ChatMessage.user("对方的动态：$body"),
            ),
        )
        val raw = llm.create(settings.chat.toModelConfig(ModelRole.CHAT)).complete(request).text
        return sanitize(raw)
    }

    /** 只留第一行，去掉引号与「评论：」前缀；含错时段问候或过长时判为不合规。 */
    fun sanitize(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (FeedTexts.FORBIDDEN.any { line.contains(it) }) return null
        val cleaned = line.trim('"', '“', '”', '\'', '「', '」', ' ').replace(Regex("^评论[:：]"), "").trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_LENGTH) return null
        return cleaned
    }
}
