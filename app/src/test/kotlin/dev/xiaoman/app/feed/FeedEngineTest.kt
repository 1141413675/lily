package dev.xiaoman.app.feed

import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.LlmClient
import dev.xiaoman.app.life.LifeSettings
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.llm.LlmClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedEngineTest {
    @get:Rule val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun userPostsDoNotUseCharactersAllowanceOrCooldown() = runBlocking {
        val store = FeedStore(folder.root)
        store.add("角色早些时候发的动态", at(8), FeedStore.SOURCE_TEMPLATE)
        repeat(FeedEngine.MAX_PER_DAY) { store.addMine("用户动态 $it", emptyList(), at(12)) }
        val engine = engine(this, store, now = at(12))

        val post = assertNotNull(engine.refreshNow())
        assertEquals(FeedStore.AUTHOR_HER, post.author)
        assertNull(engine.refreshNow())
    }

    @Test
    fun legacyCharacterPostsStillUseTheDailyLimit() = runBlocking {
        folder.root.resolve("posts.json").writeText(
            """{"posts":[{"id":"a","text":"早晨","at":${at(6)}},{"id":"b","text":"午前","at":${at(10)}},{"id":"c","text":"下午","at":${at(14)}}]}""",
        )
        val store = FeedStore(folder.root)
        assertEquals(FeedEngine.MAX_PER_DAY, store.posts.value.size)
        assertTrue(store.posts.value.all { it.author == FeedStore.AUTHOR_HER })

        assertNull(engine(this, store, now = at(22)).refreshNow())
    }

    @Test
    fun concurrentRefreshesOnlyRequestAndPublishOnePost() = runBlocking {
        val store = FeedStore(folder.root)
        val release = CompletableDeferred<Unit>()
        var modelCalls = 0
        val engine = engine(this, store, model = {
            if (++modelCalls == 1) release.await()
            ChatResponse("今天在书店读了几页书。")
        })

        val first = async(start = CoroutineStart.UNDISPATCHED) { engine.refreshNow() }
        assertTrue(engine.writing.value)
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine.refreshNow() }
        assertEquals(1, modelCalls)
        release.complete(Unit)

        assertNotNull(first.await())
        assertNull(second.await())
        assertEquals(1, store.posts.value.size)
        assertFalse(engine.writing.value)
    }

    @Test
    fun cancellationReleasesGenerationWithoutPostingATemplate() = runBlocking {
        val store = FeedStore(folder.root)
        var modelCalls = 0
        val engine = engine(this, store, model = {
            if (++modelCalls == 1) awaitCancellation()
            ChatResponse("今天在书店读了几页书。")
        })

        val first = launch(start = CoroutineStart.UNDISPATCHED) { engine.refreshNow() }
        assertTrue(engine.writing.value)
        first.cancelAndJoin()
        assertTrue(store.posts.value.isEmpty())
        assertFalse(engine.writing.value)

        assertNotNull(engine.refreshNow())
        assertEquals(2, modelCalls)
    }

    private fun engine(
        scope: CoroutineScope,
        store: FeedStore,
        now: Long = at(12),
        model: (suspend () -> ChatResponse)? = null,
    ): FeedEngine = FeedEngine(
        store = store,
        settings = {
            AppSettings(
                life = LifeSettings(city = "杭州", doing = "在书店看书"),
                chat = if (model == null) ModelSettings() else ModelSettings(model = "test", apiKey = "test"),
            )
        },
        llm = LlmClientFactory { config ->
            object : LlmClient {
                override val config = config
                override suspend fun complete(request: ChatRequest) = checkNotNull(model).invoke()
            }
        },
        scope = scope,
        clock = { now },
        zone = zone,
    )

    private fun at(hour: Int): Long =
        ZonedDateTime.of(2026, 11, 22, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
}
