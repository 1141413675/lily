package dev.xiaoman.app.feed

import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.memory.FileMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

/** 动态自动进记忆：规则兜底、去重、来源字段、心情分流，以及她的短回应只来一次。 */
class FeedMemoryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val unconfigured = LlmClientFactory { error("模型未配置时不应该被调用") }

    private fun configured(): AppSettings = AppSettings(
        chat = ModelSettings(model = "test-chat", apiKey = "test-key"),
    )

    private fun fakeClient(text: String): LlmClientFactory = LlmClientFactory { config ->
        object : LlmClient {
            override val config: ModelConfig = config
            override suspend fun complete(request: ChatRequest) = ChatResponse(text)
        }
    }

    private fun extractor(
        store: FeedStore,
        memory: FileMemoryStore,
        onMood: suspend (String, String) -> Unit = { _, _ -> },
    ) = FeedMemory(store, memory, { AppSettings() }, unconfigured, scope, onMood)

    @Test
    fun heuristicWritesFactsAndMoodWithoutDuplicates() = runBlocking {
        val memory = FileMemoryStore(createTempDirectory("memory").toFile())
        val store = FeedStore(createTempDirectory("feed").toFile())
        var mood: String? = null
        val feedMemory = extractor(store, memory) { code, _ -> mood = code }
        val post = store.addMine("我喜欢下雨天。我住在杭州。今天有点累，不想说话。", emptyList(), 1_000L)!!

        val ids = feedMemory.extractNow(post.id)

        assertEquals(2, ids.size)
        assertEquals("LOW", mood)
        assertEquals(2, memory.list().size)
        val liked = memory.list().first { it.title == "喜欢下雨天" }
        assertEquals(post.id, liked.extraFrontmatter[FeedMemory.SOURCE_KEY])
        assertTrue(store.posts.value.single().memoryChecked)

        val again = store.addMine("我还是喜欢下雨天。", emptyList(), 2_000L)!!
        assertTrue(feedMemory.extractNow(again.id).isEmpty())
        assertEquals(2, memory.list().size)
    }

    @Test
    fun herPostsAreNeverExtracted() = runBlocking {
        val memory = FileMemoryStore(createTempDirectory("memory").toFile())
        val store = FeedStore(createTempDirectory("feed").toFile())
        val post = store.addHer("我喜欢下雨天。", 1_000L, FeedStore.SOURCE_TEMPLATE)!!
        assertTrue(extractor(store, memory).extractNow(post.id).isEmpty())
        assertTrue(memory.list().isEmpty())
    }

    @Test
    fun parseModelFactsDropsIncompleteEntries() {
        val memory = FileMemoryStore(createTempDirectory("memory").toFile())
        val store = FeedStore(createTempDirectory("feed").toFile())
        val raw = """
            ```json
            [{"title":"喜欢猫","type":"preference","category":"偏好","content":"喜欢猫","tags":["宠物"],"confidence":0.9},
             {"title":"","content":"缺标题"}]
            ```
        """.trimIndent()
        val facts = extractor(store, memory).parseFacts(raw)
        assertNotNull(facts)
        assertEquals(1, facts!!.size)
        assertEquals("喜欢猫", facts.first().title)
        assertEquals("preference", facts.first().type)
    }

    @Test
    fun modelEmptyArrayMeansNothingToRemember() {
        val memory = FileMemoryStore(createTempDirectory("memory").toFile())
        val store = FeedStore(createTempDirectory("feed").toFile())
        val facts = extractor(store, memory).parseFacts("[]")
        assertNotNull(facts)
        assertTrue(facts!!.isEmpty())
    }

    @Test
    fun reactionHappensOnceAndUsesHerName() = runBlocking {
        val store = FeedStore(createTempDirectory("feed").toFile())
        val settings = AppSettings()
        val reactions = FeedReactions(store, { settings }, unconfigured, scope)
        val post = store.addMine("今天加班到现在。", emptyList(), 1_000L)!!

        val comment = reactions.reactNow(post.id)
        assertNotNull(comment)
        assertEquals(settings.persona.name, comment!!.who)
        assertTrue(store.posts.value.single().likedByHer)

        assertNull(reactions.reactNow(post.id))
        assertEquals(1, store.posts.value.single().comments.size)
    }

    @Test
    fun reactionSanitizeRejectsGreetings() {
        val store = FeedStore(createTempDirectory("feed").toFile())
        val reactions = FeedReactions(store, { AppSettings() }, unconfigured, scope)
        assertNull(reactions.sanitize("晚安，早点睡"))
        assertNull(reactions.sanitize("  "))
        assertEquals("这张拍得好看。", reactions.sanitize("「这张拍得好看。」"))
        assertFalse(reactions.sanitize("看到啦。").isNullOrBlank())
    }

    @Test
    fun modelFactsAreWrittenAndMoodApplied() = runBlocking {
        val memory = FileMemoryStore(createTempDirectory("memory").toFile())
        val store = FeedStore(createTempDirectory("feed").toFile())
        val settings = configured()
        val json = """
            [{"title":"喜欢猫","type":"preference","category":"偏好","content":"喜欢猫","tags":["宠物"],"confidence":0.9},
             {"title":"低落","type":"mood","category":"心情","content":"低落","tags":[],"confidence":0.8}]
        """.trimIndent()
        var mood: String? = null
        val extractor = FeedMemory(
            store, memory, { settings }, fakeClient(json), scope,
            onMood = { code, _ -> mood = code },
        )
        val post = store.addMine("今天路边有只猫跟着我走了一段。", emptyList(), 1_000L)!!

        val ids = extractor.extractNow(post.id)

        assertEquals(1, ids.size)
        assertEquals("LOW", mood)
        val note = memory.list().single()
        assertEquals("喜欢猫", note.title)
        assertEquals(post.id, note.extraFrontmatter[FeedMemory.SOURCE_KEY])
    }

    @Test
    fun modelCompletesReactionWhenConfigured() = runBlocking {
        val store = FeedStore(createTempDirectory("feed").toFile())
        val settings = configured()
        val reactions = FeedReactions(store, { settings }, fakeClient("这张拍得好看。"), scope)
        val post = store.addMine("随手拍的。", emptyList(), 1_000L)!!

        val comment = reactions.reactNow(post.id)

        assertEquals("这张拍得好看。", comment!!.text)
        assertTrue(store.posts.value.single().likedByHer)
    }
}
