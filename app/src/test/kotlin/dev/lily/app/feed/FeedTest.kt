package dev.lily.app.feed

import dev.lily.agent.DayPart
import dev.lily.api.ChatRequest
import dev.lily.api.ChatResponse
import dev.lily.api.LlmClient
import dev.lily.api.LlmException
import dev.lily.api.ModelConfig
import dev.lily.app.life.FeedTexts
import dev.lily.app.life.LifeSettings
import dev.lily.app.settings.AppSettings
import dev.lily.app.settings.SettingsForm
import dev.lily.llm.LlmClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory

/** 动态流：时段约束、无 Key 兜底、间隔去重、点赞评论落盘。 */
class FeedTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val life = LifeSettings(
        city = "杭州",
        doing = "在小书店选书",
        routine = "七点起，十一点半睡",
        pet = "一只叫年糕的橘猫",
        media = "在读《夜航西飞》",
        bio = "不太会安慰人，但会一直听",
    )

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, 22, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun engine(
        dir: java.io.File = createTempDirectory("feed").toFile(),
        settings: () -> AppSettings = { AppSettings(life = life) },
        now: Long = at(10),
        llm: LlmClientFactory = LlmClientFactory { cfg -> client(cfg) { throw LlmException("not used") } },
    ): Pair<FeedEngine, FeedStore> {
        val store = FeedStore(dir)
        val engine = FeedEngine(
            store = store,
            settings = settings,
            llm = llm,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { now },
            zone = zone,
        )
        return engine to store
    }

    private fun client(cfg: ModelConfig, block: suspend (ChatRequest) -> ChatResponse): LlmClient =
        object : LlmClient {
            override val config: ModelConfig = cfg
            override suspend fun complete(request: ChatRequest): ChatResponse = block(request)
        }

    @Test
    fun templateNeverUsesWrongGreeting() {
        DayPart.entries.forEach { part ->
            val text = FeedTexts.template(life, part, at(part.ordinal + 6))
            assertTrue("模板为空：$part", text.isNotBlank())
            FeedTexts.FORBIDDEN.forEach { word ->
                assertFalse("$part 模板出现「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun lateNightTemplateStaysUp() {
        val text = FeedTexts.template(life, DayPart.LATE_NIGHT, at(23, 30))
        assertTrue(text.contains("夜深") || text.contains("醒着") || text.contains("灯"))
        assertFalse(text.contains("早"))
    }

    @Test
    fun engineWritesTemplateWithoutKey() = runBlocking {
        val (engine, store) = engine()
        val post = engine.refreshNow()
        assertNotNull(post)
        assertEquals(FeedStore.SOURCE_TEMPLATE, post!!.source)
        assertTrue(post.text.isNotBlank())
        assertEquals(1, store.posts.value.size)
    }

    @Test
    fun engineSkipsDeepNight() = runBlocking {
        val (engine, store) = engine(now = at(2, 30))
        assertNull(engine.refreshNow())
        assertTrue(store.posts.value.isEmpty())
    }

    @Test
    fun engineDoesNotRepeatWithinFourHours() = runBlocking {
        val (engine, store) = engine()
        assertNotNull(engine.refreshNow())
        assertNull(engine.refreshNow())
        assertEquals(1, store.posts.value.size)
    }

    @Test
    fun modelFailureFallsBackToTemplate() = runBlocking {
        val configured = AppSettings(
            chat = dev.lily.app.settings.ModelSettings(model = "m", apiKey = "k"),
            life = life,
        )
        val (engine, _) = engine(settings = { configured })
        val post = engine.refreshNow()
        assertNotNull(post)
        assertEquals(FeedStore.SOURCE_TEMPLATE, post!!.source)
    }

    @Test
    fun sanitizeDropsGreetingsAndKeepsBody() {
        val (engine, _) = engine()
        assertNull(engine.sanitize("晚安，早点睡"))
        assertNull(engine.sanitize("   "))
        assertEquals("今天在书店待了一下午。", engine.sanitize("「今天在书店待了一下午。」"))
    }

    @Test
    fun commentReplyQuotesUserAndRespectsPart() {
        val reply = FeedTexts.commentReply("那张图好看", DayPart.LATE_NIGHT)
        assertTrue(reply.contains("那张图好看"))
        FeedTexts.FORBIDDEN.forEach { word -> assertFalse(reply.contains(word)) }
        assertTrue(reply.contains("还没睡"))
    }

    @Test
    fun likesAndCommentsSurviveReload() {
        val dir = createTempDirectory("feed").toFile()
        val store = FeedStore(dir)
        val post = store.add("今天在书店待了一下午。", at(15), FeedStore.SOURCE_TEMPLATE)!!
        store.toggleLike(post.id)
        store.addComment(post.id, "你", "哪家书店？", at(16))

        val reloaded = FeedStore(dir).posts.value.single()
        assertEquals(1, reloaded.likes)
        assertTrue(reloaded.liked)
        assertEquals(1, reloaded.comments.size)
        assertEquals("哪家书店？", reloaded.comments.first().text)
    }

    @Test
    fun likeCanBeCancelledOnEitherAuthor() {
        val store = FeedStore(createTempDirectory("feed").toFile())
        val mine = store.addMine("我的动态", emptyList(), at(14))!!
        val hers = store.add("她的动态", at(15), FeedStore.SOURCE_TEMPLATE)!!

        listOf(mine.id, hers.id).forEach { id ->
            store.toggleLike(id)
            assertTrue(store.posts.value.first { it.id == id }.liked)
            assertEquals(1, store.posts.value.first { it.id == id }.likes)
            store.toggleLike(id)
            assertFalse(store.posts.value.first { it.id == id }.liked)
            assertEquals(0, store.posts.value.first { it.id == id }.likes)
        }
    }

    @Test
    fun repliesNestUnderTheirParentAndSurviveReload() {
        val dir = createTempDirectory("feed").toFile()
        val store = FeedStore(dir)
        val post = store.add("今天在书店待了一下午。", at(15), FeedStore.SOURCE_TEMPLATE)!!
        val root = store.addComment(post.id, "你", "哪家书店？", at(16))!!.comments.single()
        assertTrue(root.id.isNotBlank())
        assertNull(root.parentId)

        val reply = store.addComment(post.id, "你", "我也想问", at(17), root.id)!!.comments.last()
        assertEquals(root.id, reply.parentId)
        assertTrue(reply.id != root.id)

        // 回复一条回复时仍然只挂一级，父级回退到顶层评论。
        val deep = store.addComment(post.id, "她", "补一句", at(18), reply.id)!!.comments.last()
        assertEquals(root.id, deep.parentId)

        val reloaded = FeedStore(dir).posts.value.single()
        assertEquals(3, reloaded.comments.size)
        assertEquals(root.id, reloaded.comments[1].parentId)
        assertEquals(root.id, reloaded.comments[2].parentId)
    }

    @Test
    fun oldCommentsGetAStableAnchorWithoutParent() {
        val dir = createTempDirectory("feed").toFile()
        java.io.File(dir, "posts.json").writeText(
            """{"posts":[{"id":"old","text":"旧动态","at":1000,"comments":[{"who":"你","text":"嗯","at":1100}]}]}""",
        )
        val store = FeedStore(dir)
        val comment = store.posts.value.single().comments.single()
        assertTrue(comment.id.isNotBlank())
        assertNull(comment.parentId)
        // 旧评论补出的锚点必须是稳定的，重读后还能被回复引用。
        assertEquals(comment.id, FeedStore(dir).posts.value.single().comments.single().id)
        store.addComment("old", "你", "回一句", at(12), comment.id)
        val reloaded = FeedStore(dir).posts.value.single().comments.last()
        assertEquals(comment.id, reloaded.parentId)
    }

    @Test
    fun lifeSettingsSurviveSettingsForm() {
        val saved = SettingsForm.of(AppSettings(life = life)).toSettings()
        assertNotNull(saved)
        assertEquals(life, saved!!.life)
    }

    @Test
    fun oldPostsLoadAsHerAndAlreadyChecked() {
        val dir = createTempDirectory("feed").toFile()
        java.io.File(dir, "posts.json").writeText(
            """{"posts":[{"id":"old","text":"旧动态","at":1000,"likes":2,"liked":true,"comments":[{"who":"你","text":"嗯","at":1100}]}]}""",
        )
        val post = FeedStore(dir).posts.value.single()
        assertEquals(FeedStore.AUTHOR_HER, post.author)
        assertTrue(post.images.isEmpty())
        assertTrue(post.memoryChecked)
        assertEquals(1, post.comments.size)
    }

    @Test
    fun mineSurvivesReloadWithImagesAndMemory() {
        val dir = createTempDirectory("feed").toFile()
        val images = java.io.File(dir, FeedStore.IMAGE_DIR).apply { mkdirs() }
        val image = java.io.File(images, "one.jpg").apply { writeText("x") }
        val store = FeedStore(dir)
        val post = store.addMine("下班路上拍的。", listOf(image.absolutePath), at(18))!!
        assertEquals(FeedStore.AUTHOR_ME, post.author)
        assertFalse(post.memoryChecked)
        store.likeByHer(post.id)
        store.setMemory(post.id, listOf("places/杭州"))

        val reloaded = FeedStore(dir).posts.value.single()
        assertEquals(FeedStore.AUTHOR_ME, reloaded.author)
        assertEquals(listOf(image.absolutePath), reloaded.images)
        assertTrue(reloaded.likedByHer)
        assertTrue(reloaded.memoryChecked)
        assertEquals(listOf("places/杭州"), reloaded.memoryIds)
    }

    @Test
    fun deletePostRemovesItsImagesOnly() {
        val dir = createTempDirectory("feed").toFile()
        val images = java.io.File(dir, FeedStore.IMAGE_DIR).apply { mkdirs() }
        val image = java.io.File(images, "one.jpg").apply { writeText("x") }
        val outside = java.io.File(dir, "outside.jpg").apply { writeText("x") }
        val store = FeedStore(dir)
        val post = store.addMine("图", listOf(image.absolutePath, outside.absolutePath), at(18))!!
        store.delete(post.id)
        assertTrue(store.posts.value.isEmpty())
        assertFalse(image.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun petNameExtractsName() {
        assertEquals("年糕", FeedTexts.petName("一只叫年糕的橘猫"))
        assertEquals("年糕", FeedTexts.petName("年糕"))
    }
}
