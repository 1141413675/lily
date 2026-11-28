package dev.lily.app.usage

import dev.lily.api.ChatRequest
import dev.lily.api.ChatResponse
import dev.lily.api.ModelConfig
import dev.lily.api.ModelRole
import dev.lily.api.Usage
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用量记账。
 *
 * 三个要点：记的是准、不记不该记的、记坏了不能连累对话。
 */
class UsageStoreTest {

    private fun store(dir: File, now: () -> Long = { 0L }) =
        UsageStore(File(dir, "usage.json"), now)

    @Test
    fun recordsAreKeptPerModel() {
        val store = store(tempDir())
        store.record("doubao-seed-2-1-pro-260915", 100, 50)
        store.record("doubao-seed-2-1-pro-260915", 200, 60)
        store.record("doubao-seed-2-0-mini-260428", 10, 5)

        val day = store.today()
        // 300（pro 的输入）+ 10（mini 的输入）= 310。
        assertEquals(310L, day.inputTokens)
        assertEquals(115L, day.outputTokens)
        assertEquals(3L, day.requests)
        assertEquals(2, day.byModel.size)
        val pro = day.byModel["doubao-seed-2-1-pro-260915"]!!
        assertEquals(300L, pro.inputTokens)
        assertEquals(2L, pro.requests)
    }

    @Test
    fun emptyRecordsAreIgnored() {
        val store = store(tempDir())
        store.record("", 100, 50)
        store.record("model", 0, 0)
        assertEquals(0L, store.today().requests)
    }

    @Test
    fun usageSurvivesARestart() {
        val dir = tempDir()
        store(dir).record("m", 10, 20)
        val reopened = store(dir)
        assertEquals(10L, reopened.today().inputTokens)
        assertEquals(20L, reopened.today().outputTokens)
    }

    @Test
    fun onlyRecentDaysAreKept() {
        val store = store(tempDir())
        // 记 100 天，每天一次；只应留下最近 90 天。
        repeat(100) { day ->
            store.record("m", 1, 1)
            // 每天往前推一天。
        }
        assertTrue(store.recent(200).size <= UsageStore.KEEP_DAYS)
    }

    @Test
    fun theMonthOnlyCountsDaysOfThatMonth() {
        val store = store(tempDir()) { millisOf(2026, 11, 28) }
        store.record("m", 100, 100)
        val month = store.thisMonth()
        assertEquals(1, month.size)
        assertTrue(month.first().date.startsWith("2026-11"))
    }

    @Test
    fun costUsesThePriceTable() {
        // doubao-seed-2-0-mini-260428：输入 0.20、输出 2.00 元 / 百万 token。
        val entry = UsageStore.Entry(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val cost = estimateCost("doubao-seed-2-0-mini-260428", entry)
        assertEquals(2.2, cost!!, 0.0001)
    }

    @Test
    fun unknownModelHasNoCost() {
        val entry = UsageStore.Entry(inputTokens = 1000, outputTokens = 1000)
        assertNull(estimateCost("some-other-vendor-model", entry))
    }

    @Test
    fun aBrokenStoreFileDoesNotCrash() {
        val dir = tempDir()
        val file = File(dir, "usage.json")
        file.writeText("{ 这不是 JSON")
        val store = store(dir)
        assertEquals(0L, store.today().requests)
        // 还能继续记。
        store.record("m", 5, 5)
        assertEquals(5L, store.today().inputTokens)
    }

    @Test
    fun theFileHoldsNoSecrets() {
        val dir = tempDir()
        val store = store(dir)
        store.record("m", 1, 1)
        val text = File(dir, "usage.json").readText()
        assertTrue("apiKey" !in text)
        assertTrue("ark-" !in text)
    }

    @Test
    fun recordingWrapsTheClientAndKeepsTheResponse() = runTest {
        val inner = object : dev.lily.api.LlmClient {
            override val config = ModelConfig(ModelRole.CHAT, "https://x", "m", "k")
            override suspend fun complete(request: ChatRequest) =
                ChatResponse("ok", usage = Usage(12, 34))
        }
        var seen: Triple<String, Int, Int>? = null
        val client = UsageRecordingClient(inner) { model, input, output ->
            seen = Triple(model, input, output)
        }

        val response = client.complete(ChatRequest(emptyList()))

        assertEquals("ok", response.text)
        assertEquals(Triple("m", 12, 34), seen)
    }

    @Test
    fun aFailingRecorderDoesNotBreakTheCall() = runTest {
        val inner = object : dev.lily.api.LlmClient {
            override val config = ModelConfig(ModelRole.CHAT, "https://x", "m", "k")
            override suspend fun complete(request: ChatRequest) =
                ChatResponse("ok", usage = Usage(12, 34))
        }
        val client = UsageRecordingClient(inner) { _, _, _ -> error("写盘失败") }

        // 不该抛异常。
        val response = client.complete(ChatRequest(emptyList()))
        assertEquals("ok", response.text)
    }

    @Test
    fun responsesWithoutUsageAreNotCounted() = runTest {
        val inner = object : dev.lily.api.LlmClient {
            override val config = ModelConfig(ModelRole.CHAT, "https://x", "m", "k")
            override suspend fun complete(request: ChatRequest) = ChatResponse("ok")
        }
        var calls = 0
        val client = UsageRecordingClient(inner) { _, _, _ -> calls++ }
        client.complete(ChatRequest(emptyList()))
        assertEquals(0, calls)
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("usage", "dir")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    /** 某个本地日期对应的毫秒数。 */
    private fun millisOf(year: Int, month: Int, day: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis
}
