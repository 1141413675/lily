package dev.lily.app.feed

import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.MemoryStore
import dev.lily.api.ModelRole
import dev.lily.api.Note
import dev.lily.api.Observation
import dev.lily.app.settings.AppSettings
import dev.lily.llm.LlmClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * 动态 → 记忆的自动抽取：用户发完动态就异步跑，不做手动转换按钮。
 *
 * 模型可用时让它输出至多 3 条稳定事实（偏好/习惯/边界/地点/重要的人/关系事件/心情）；
 * 没配 Key、调用失败或返回不合规时，用保守的中文规则兜底。情绪只更新关系心情，不入记忆。
 * 写记忆时在 frontmatter 放 `source_feed`，供记忆详情跳回原动态；同一事实按正文去重。
 */
class FeedMemory(
    private val store: FeedStore,
    private val memory: MemoryStore,
    private val settings: () -> AppSettings,
    private val llm: LlmClientFactory,
    private val scope: CoroutineScope,
    private val onMood: suspend (String, String) -> Unit = { _, _ -> },
) {
    data class Fact(
        val title: String,
        val type: String,
        val category: String,
        val content: String,
        val tags: List<String> = emptyList(),
        val confidence: Double = 1.0,
    )

    companion object {
        const val MAX_FACTS = 3
        const val MIN_CONFIDENCE = 0.6
        const val SOURCE_KEY = "source_feed"
        const val MOOD = "mood"

        /** 可以写成 Basic Memory `type` 的分类，其余落 `note`。 */
        private val TYPES = setOf("preference", "habit", "boundary", "place", "person", "event", "note")
        private val CLAUSE = Regex("[。！？!?，,；;\\n]")
    }

    private val mutex = Mutex()

    fun extract(postId: String) {
        scope.launch(Dispatchers.IO) { runCatching { extractNow(postId) } }
    }

    /** 打开动态页 / 应用启动时补抽取：兜底离线、模型失败或当时被中断的帖子。 */
    fun extractPending() {
        scope.launch(Dispatchers.IO) {
            val pending = store.posts.value.filter { it.author == FeedStore.AUTHOR_ME && !it.memoryChecked }
            pending.forEach { runCatching { extractNow(it.id) } }
        }
    }

    suspend fun extractNow(postId: String): List<String> = mutex.withLock {
        val post = store.posts.value.firstOrNull { it.id == postId } ?: return@withLock emptyList()
        if (post.author != FeedStore.AUTHOR_ME || post.memoryChecked) return@withLock emptyList()
        val facts = factsFor(post)
            .filter { it.confidence >= MIN_CONFIDENCE && it.content.isNotBlank() }
            .distinctBy { normalize(it.title) + "|" + normalize(it.content) }
            .take(MAX_FACTS)
        facts.firstOrNull { it.type == MOOD }?.let { fact ->
            moodCode(fact.content)?.let { code -> runCatching { onMood(code, "动态里提到") } }
        }
        val ids = facts.filter { it.type != MOOD }.mapNotNull { write(it, post) }.distinct()
        store.setMemory(postId, ids)
        ids
    }

    private suspend fun factsFor(post: FeedStore.Post): List<Fact> {
        val current = settings()
        if (current.chat.configured) {
            val fromModel = runCatching { fromModel(current, post) }.getOrNull()
            if (fromModel != null) return fromModel
        }
        return heuristic(post.text)
    }

    private suspend fun fromModel(settings: AppSettings, post: FeedStore.Post): List<Fact>? {
        val body = buildString {
            append(post.text)
            if (post.images.isNotEmpty()) append(if (post.text.isBlank()) "（只发了一张图）" else "（配了图）")
        }
        val request = ChatRequest(
            listOf(
                ChatMessage.system(
                    "你负责从用户和她自己的动态里，抽取用户值得长期记住的事实。只输出 JSON 数组，不要解释。" +
                        "每条格式：{\"title\":\"短标题\",\"type\":\"preference|habit|boundary|place|person|event|mood\"," +
                        "\"category\":\"分类\",\"content\":\"一句话事实\",\"tags\":[\"标签\"],\"confidence\":0.0到1.0}。" +
                        "只记用户本人的稳定偏好、习惯、边界、地点、重要的人、关系事件；" +
                        "情绪状态 type 用 mood，content 只能是 低落/平静/忙碌/兴致高 之一；" +
                        "日常流水、天气、一次性的感受不要记；没有可记的就输出 []。最多 3 条。",
                ),
                ChatMessage.user("用户的动态：$body"),
            ),
        )
        val raw = llm.create(settings.chat.toModelConfig(ModelRole.CHAT)).complete(request).text
        return parseFacts(raw)
    }

    /** 解析模型输出；数组为空是合法结果（模型判断没有可记的），解析失败返回 null 走规则兜底。 */
    internal fun parseFacts(raw: String): List<Fact>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val content = o.optString("content").trim()
            if (title.isEmpty() || content.isEmpty()) return@mapNotNull null
            val tags = o.optJSONArray("tags")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optString(j).takeIf { it.isNotBlank() } }
            }.orEmpty()
            val type = o.optString("type").trim().lowercase()
            Fact(
                title = title.take(24),
                type = if (type == MOOD || type in TYPES) type else "note",
                category = o.optString("category").ifBlank { "动态" }.take(12),
                content = content.take(120),
                tags = tags.take(4),
                confidence = o.optDouble("confidence", 0.7),
            )
        }
    }

    /** 保守规则：只抓明确的第一人称偏好、习惯、边界与地点；情绪单独成条。 */
    fun heuristic(text: String): List<Fact> {
        val facts = mutableListOf<Fact>()
        moodCode(text)?.let { code ->
            facts += Fact(title = code, type = MOOD, category = "心情", content = code, confidence = 0.8)
        }
        CLAUSE.split(text).map { it.trim() }.filter { it.isNotEmpty() }.forEach { clause ->
            val sentence = "我" + clause.removePrefix("我").trim()
            fun add(pattern: Regex, type: String, category: String, prefix: String) {
                val match = pattern.find(sentence) ?: return
                val content = clean(match.groupValues[1]) ?: return
                val body = if (prefix.isEmpty()) {
                    clean(sentence.removePrefix("我")) ?: content
                } else {
                    prefix + content
                }
                facts += Fact(
                    title = body.take(20),
                    type = type,
                    category = category,
                    content = body,
                    tags = listOf(category),
                )
            }
            add(Regex("^我(?:很|挺|特别|最|也|一直|真的)?不(?:喜欢|爱)(.+)$"), "preference", "偏好", "不喜欢")
            add(Regex("^我(?:很|挺|特别|最|也|一直|真的)?喜欢(.+)$"), "preference", "偏好", "喜欢")
            add(Regex("^我(?:讨厌|受不了|怕|害怕)(.+)$"), "boundary", "边界", "")
            add(Regex("^我(?:(?:一般|通常)(?:会|是)?|每天|总是|习惯)(.+)$"), "habit", "习惯", "")
            add(Regex("^我(?:住在|家在|家住在)(.+)$"), "place", "地点", "住在")
        }
        return facts.distinctBy { it.title + "|" + it.content }.take(MAX_FACTS)
    }

    private suspend fun write(fact: Fact, post: FeedStore.Post): String? {
        val title = fact.title.trim().take(24)
        if (title.isEmpty()) return null
        val observation = Observation(
            category = fact.category.ifBlank { fact.type },
            content = fact.content.take(120),
            tags = fact.tags.take(4),
            context = "来自动态",
        )
        val existing = runCatching { memory.read(title) }.getOrNull()
        if (existing != null) {
            if (existing.observations.any { normalize(it.content) == normalize(fact.content) }) return null
            val updated = existing.copy(
                observations = existing.observations + observation,
                extraFrontmatter = existing.extraFrontmatter + (SOURCE_KEY to post.id),
            )
            return runCatching { memory.write(updated) }.getOrNull()?.permalink
        }
        val note = Note(
            title = title,
            permalink = "",
            type = fact.type.takeIf { it in TYPES } ?: "note",
            tags = fact.tags.take(4),
            observations = listOf(observation),
            extraFrontmatter = mapOf(SOURCE_KEY to post.id),
        )
        return runCatching { memory.write(note) }.getOrNull()?.permalink
    }

    /** 把心情原文归到关系档案使用的四种取值；没有明确情绪就返回 null。 */
    fun moodCode(text: String): String? {
        val t = text.trim()
        when {
            t.contains("ENTHUSIASTIC") || t.contains("兴致高") -> return "ENTHUSIASTIC"
            t.contains("LOW") || t.contains("低落") -> return "LOW"
            t.contains("BUSY") || t.contains("忙碌") -> return "BUSY"
            t.contains("CALM") || t.contains("平静") -> return "CALM"
        }
        return when {
            listOf("开心", "高兴", "兴奋", "满足", "超棒", "太好了").any { t.contains(it) } -> "ENTHUSIASTIC"
            listOf("累", "难过", "烦", "焦虑", "委屈", "失落", "不开心", "崩溃", "想哭").any { t.contains(it) } -> "LOW"
            listOf("忙", "加班", "赶工", "开会", "没空").any { t.contains(it) } -> "BUSY"
            listOf("平静", "安静", "放松", "踏实").any { t.contains(it) } -> "CALM"
            else -> null
        }
    }

    private fun clean(raw: String): String? {
        val value = raw.trim().trimEnd('。', '！', '!', '？', '?', '，', ',', '；', ';', ' ', '的', '了')
        if (value.isEmpty() || value.length > 20) return null
        return value
    }

    private fun normalize(text: String) = text.replace(Regex("\\s+"), "").trim('。', '，', ',', '！', '!')
}
