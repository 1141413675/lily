package dev.xiaoman.app.image

import dev.xiaoman.app.life.LifeSettings
import dev.xiaoman.app.media.SceneryStore
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.ImageSettings
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 生成链路与落盘配额的离线验证：不访问真实方舟，也不写应用目录。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageGenerationTest {

    @Test
    fun generatorReadsBase64Response() = runBlocking {
        val payload = "fake-image".toByteArray()
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(payload)}"}]}"""
        val (server, port) = stubOnce(json)
        try {
            val got = ImageGenerator().generate(
                baseUrl = "http://127.0.0.1:$port",
                apiKey = "test-key",
                model = ImageSettings.DEFAULT_MODEL,
                prompt = "窗边的下午",
                reference = null,
            )
            assertEquals("fake-image", got?.decodeToString())
        } finally {
            server.close()
        }
    }

    @Test
    fun seedream5RequestUsesConfiguredModelAndSkipsLegacyGrouping() = runBlocking {
        val payload = "fake-image".toByteArray()
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(payload)}"}]}"""
        val captured = mutableListOf<String>()
        val (server, port) = stubOnce(json, captured)
        try {
            val got = ImageGenerator().generate(
                baseUrl = "http://127.0.0.1:$port",
                apiKey = "test-key",
                model = ImageSettings.DEFAULT_MODEL,
                prompt = "窗边的下午",
                reference = null,
            )
            assertNotNull(got)
            val body = captured.single()
            assertTrue(body.contains("\"model\":\"${ImageSettings.DEFAULT_MODEL}\""))
            assertTrue(body.contains("\"size\":\"2K\""))
            assertTrue(body.contains("\"response_format\":\"b64_json\""))
            // 5.x 文档没有组图参数，带上会被当未知参数拒掉。
            assertFalse(body.contains("sequential_image_generation"))
        } finally {
            server.close()
        }
    }

    @Test
    fun storeCountsGeneratedPerDayAndCleansOldFiles() {
        val dir = createTempDirectory("chat-images").toFile()
        var now = 1_000L
        val store = ChatImageStore(dir) { now }
        assertTrue(store.canGenerate(3))
        repeat(3) { store.markGenerated() }
        assertFalse(store.canGenerate(3))

        val saved = store.save("x".toByteArray())
        assertNotNull(saved)
        assertTrue(saved.isFile)

        // 超过 30 天的生成图会在清理时被删掉。
        now += 31L * 24 * 60 * 60 * 1000
        saved.setLastModified(1L)
        store.cleanup()
        assertFalse(saved.exists())
    }

    @Test
    fun promptKeepsReferenceHintForSelfie() {
        val prompt = ImagePrompt.build(
            selfie = true,
            context = "看看你今天的样子",
            name = "小满",
            life = LifeSettings(city = "杭州", pet = "一只叫年糕的猫"),
        )
        assertTrue(prompt.contains("参考图"))
        assertTrue(prompt.contains("小满"))
        assertFalse(ImagePrompt.build(false, "在干嘛", "小满", LifeSettings()).contains("参考图"))
    }

    @Test
    fun chatImagesReusesOldPhotoForRecallAndGeneratesForWatchRequest() = runBlocking {
        val root = createTempDirectory("chat-images").toFile()
        val old = File(root, "old.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val posted = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        var generates = 0
        val chatImages = ChatImages(
            scope = this,
            settings = { AppSettings(image = ImageSettings(enabled = true, dailyLimit = 3)) },
            items = { listOf(ChatItem.AssistantImage(1, old.absolutePath)) },
            portraitFile = { null },
            store = ChatImageStore(File(root, "images")),
            scenery = SceneryStore(File(root, "scenery")),
            post = { posted += it },
            generate = { prompt, _ ->
                generates++
                prompts += prompt
                "generated".toByteArray()
            },
            promptFor = { _, context -> "prompt:$context" },
        )

        // 回忆过去：直接引用聊天里的原图，不调用生成。
        chatImages.share("你昨天拍的那张照片呢", "")
        assertEquals(listOf(old.absolutePath), posted)
        assertEquals(0, generates)

        // 想看此刻：按对话细节生成并落应用私有目录。
        posted.clear()
        chatImages.share("想看看你现在在喝什么", "我在喝咖啡")
        assertEquals(1, generates)
        assertTrue(prompts.single().contains("想看看你现在在喝什么"))
        assertEquals(1, posted.size)
        assertTrue(File(posted.single()).isFile)
    }

    @Test
    fun failedGenerationAndEmptyLibraryStaySilent() = runBlocking {
        val root = createTempDirectory("chat-images").toFile()
        val posted = mutableListOf<String>()
        val chatImages = ChatImages(
            scope = this,
            settings = { AppSettings(image = ImageSettings(enabled = true, dailyLimit = 3)) },
            items = { emptyList() },
            portraitFile = { null },
            store = ChatImageStore(File(root, "images")),
            scenery = SceneryStore(File(root, "scenery")),
            post = { posted += it },
            generate = { _, _ -> null },
            promptFor = { _, _ -> "prompt" },
        )

        chatImages.share("给我看看你在干嘛", "")
        assertTrue(posted.isEmpty())
    }

    /** 只回一次 JSON 的最小 HTTP 桩，避免额外测试依赖。 */
    private fun stubOnce(json: String, captured: MutableList<String>? = null): Pair<ServerSocket, Int> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    // ISO-8859-1 一字节一字符，按 Content-Length 读请求体不会因中文多字节卡住。
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var contentLength = 0
                    var line = input.readLine()
                    while (line != null && line.isNotEmpty()) {
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        line = input.readLine()
                    }
                    if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(chars, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        captured?.add(String(chars, 0, read).toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8))
                    }
                    val body = json.toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return server to server.localPort
    }
}
