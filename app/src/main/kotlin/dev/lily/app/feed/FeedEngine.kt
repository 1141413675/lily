package dev.lily.app.feed

import dev.lily.agent.DayPart
import dev.lily.agent.LocalTimeContext
import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.ModelRole
import dev.lily.app.life.FeedTexts
import dev.lily.app.life.LifeSettings
import dev.lily.app.settings.AppSettings
import dev.lily.llm.LlmClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 她的文字动态：按自己的生活设定 + 设备本地时间生一条（模型可用就用模型），
 * 模型没配 / 调用失败 / 返回不合规时退回模板，永远不空着、不报错。
 *
 * 时间一律取本机时区；23:00–4:59 不自动发，模板也不含「早安/晚安」这类会错时段的问候。
 */
class FeedEngine(
    private val store: FeedStore,
    private val settings: () -> AppSettings,
    private val llm: LlmClientFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        const val MAX_PER_DAY = 3
        const val MIN_GAP_MILLIS = 4 * 60 * 60 * 1000L
        const val MIN_HOUR = 5
        const val MAX_HOUR = 22
        const val MAX_LENGTH = 80
    }

    private val refreshLock = Mutex()
    private val _writing = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /** 打开动态页时调用；已有一条够新的动态就什么都不做。 */
    fun refresh() {
        if (_writing.value) return
        scope.launch(Dispatchers.IO) { runCatching { refreshNow() } }
    }

    /** 可等待版本，便于单测。 */
    suspend fun refreshNow(): FeedStore.Post? {
        if (!refreshLock.tryLock()) return null
        try {
            if (store.loadFailed.value) return null
            val current = settings()
            val life = current.life.trimmed()
            if (life.isEmpty) return null
            val now = clock()
            if (!shouldPost(now, store.posts.value)) return null
            _writing.value = true
            val part = partAt(now)
            val fromModel = if (current.chat.configured) {
                try {
                    modelPost(current, life, part, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            val text = fromModel ?: FeedTexts.template(life, part, now)
            return store.add(text, now, if (fromModel != null) FeedStore.SOURCE_MODEL else FeedStore.SOURCE_TEMPLATE)
        } finally {
            _writing.value = false
            refreshLock.unlock()
        }
    }

    fun shouldPost(now: Long, posts: List<FeedStore.Post>): Boolean {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        if (zoned.hour !in MIN_HOUR..MAX_HOUR) return false
        val today = zoned.toLocalDate()
        val characterPosts = posts.filter { it.author == FeedStore.AUTHOR_HER }
        val todayCount = characterPosts.count { localDate(it.at) == today }
        if (todayCount >= MAX_PER_DAY) return false
        val last = characterPosts.maxOfOrNull { it.at } ?: 0L
        return last == 0L || now - last >= MIN_GAP_MILLIS
    }

    fun partAt(now: Long): DayPart = LocalTimeContext.dayPart(Instant.ofEpochMilli(now).atZone(zone).hour)

    private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private suspend fun modelPost(settings: AppSettings, life: LifeSettings, part: DayPart, now: Long): String? {
        val request = ChatRequest(
            listOf(
                ChatMessage.system(
                    "你在写一条只给自己看的日常动态，会显示在「动态」页。要求：" +
                        "中文，1–2 句，不超过 45 字；写今天做了什么、看了什么、心情如何；用第一人称；" +
                        "不写问候语（不要出现早安、晚安、早点睡）；不编造设定里没有的地点、宠物或事件；" +
                        "严格按给出的时段写。只输出动态正文，不要引号，不要解释。",
                ),
                ChatMessage.user(userPrompt(life, part, now)),
            ),
        )
        val text = llm.create(settings.chat.toModelConfig(ModelRole.CHAT)).complete(request).text
        return sanitize(text)
    }

    private fun userPrompt(life: LifeSettings, part: DayPart, now: Long): String = buildString {
        append("现在：").append(LocalTimeContext.describe(now, zone)).append("（只为区分时段，不要把时间写进正文）。\n")
        append("我的生活设定：\n")
        if (life.city.isNotBlank()) append("- 城市：").append(life.city).append('\n')
        if (life.doing.isNotBlank()) append("- 在做什么：").append(life.doing).append('\n')
        if (life.routine.isNotBlank()) append("- 作息：").append(life.routine).append('\n')
        if (life.pet.isNotBlank()) append("- 宠物：").append(life.pet).append('\n')
        if (life.media.isNotBlank()) append("- 在读 / 在追：").append(life.media).append('\n')
        if (life.bio.isNotBlank()) append("- 一句自述：").append(life.bio).append('\n')
        append("时段：").append(part.label).append("（").append(part.range).append("）。")
    }

    /** 只留第一行正文；含错时段问候或过长时判为不合规，交给模板。 */
    fun sanitize(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (FeedTexts.FORBIDDEN.any { line.contains(it) }) return null
        val cleaned = line.trim('"', '“', '”', '\'', '「', '」', ' ').replace(Regex("^动态[:：]"), "").trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_LENGTH) return null
        return cleaned.ifBlank { null }
    }
}
