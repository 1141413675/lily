package dev.lily.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingClientTest {
    private lateinit var server: MockWebServer
    private val endpoint get() = server.url("/v1/embeddings").toString()

    @BeforeTest fun setup() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest fun teardown() {
        server.shutdown()
    }

    @Test fun `standard request returns vectors in input order`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[{"index":1,"embedding":[0.3,1.0]},{"index":0,"embedding":[1.0,0.2]}]}
        """.trimIndent()))
        val result = EmbeddingClient().embed(endpoint, "embedding-model", "test-token", listOf("第一条", "第二条"))

        assertContentEquals(floatArrayOf(1.0f, 0.2f), result[0])
        assertContentEquals(floatArrayOf(0.3f, 1.0f), result[1])
        val request = assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/v1/embeddings", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "input"), payload.keys)
        assertEquals("embedding-model", payload.getValue("model").jsonPrimitive.content)
        assertEquals(listOf("第一条", "第二条"), payload.getValue("input").jsonArray.map { it.jsonPrimitive.contentOrNull })
    }

    @Test fun `loopback permits an empty credential and error details are sanitized`() = runBlocking {
        val key = "test-secret"
        val note = "private-note"
        server.enqueue(MockResponse().setResponseCode(403).setBody("echo $key for $note"))
        val rejected = assertFailsWith<EmbeddingException> {
            EmbeddingClient().embed(endpoint, "model", key, listOf(note))
        }
        assertEquals(403, rejected.statusCode)
        assertFalse(key in rejected.toString())
        assertFalse(note in rejected.toString())
        server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[0,1]}]}"""))
        EmbeddingClient().embed(endpoint, "model", " ", listOf("local text"))

        assertEquals("Bearer $key", assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)).getHeader("Authorization"))
        assertNull(assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)).getHeader("Authorization"))
    }

    @Test fun `redirects and provider retries never repeat an embedding request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(500))
        val client = EmbeddingClient()
        for (status in listOf(307, 503)) {
            val failure = assertFailsWith<EmbeddingException> {
                client.embed(endpoint, "model", "test-token", listOf("test text"))
            }
            assertEquals(status, failure.statusCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `malformed indexes and invalid vectors fail before returning partial data`() = runBlocking {
        val invalid = listOf(
            """{"data":[{"index":0,"embedding":[1]},{"index":0,"embedding":[1]}]}""",
            """{"data":[{"index":2,"embedding":[1]},{"index":1,"embedding":[1]}]}""",
            """{"data":[{"index":0,"embedding":[1]},{"index":1,"embedding":[1,2]}]}""",
            """{"data":[{"index":0,"embedding":[0]},{"index":1,"embedding":[1]}]}""",
            """{"data":[{"index":0,"embedding":[1e99]},{"index":1,"embedding":[1]}]}""",
            """{"data":[{"index":0,"embedding":["1"]},{"index":1,"embedding":[1]}]}""",
            """{"data":[{"embedding":[1]},{"index":1,"embedding":[1]}]}""",
        )
        val client = EmbeddingClient()
        for (body in invalid) {
            server.enqueue(MockResponse().setBody(body))
            val failure = assertFailsWith<EmbeddingException> {
                client.embed(endpoint, "model", "", listOf("a", "b"))
            }
            assertEquals("Invalid embedding response.", failure.message)
        }
    }

    @Test fun `invalid endpoints and oversized inputs are rejected before network`() = runBlocking {
        val client = EmbeddingClient()
        for (url in listOf("http://example.com/v1/embeddings", "$endpoint?secret=value", server.url("/chat").toString())) {
            assertFailsWith<EmbeddingException> { client.embed(url, "model", "", listOf("a")) }
        }
        assertFailsWith<EmbeddingException> {
            client.embed(endpoint, "model", "", List(EmbeddingClient.MAX_INPUT_ITEMS + 1) { "a" })
        }
        assertFailsWith<EmbeddingException> {
            client.embed(endpoint, "model", "", List(EmbeddingClient.MAX_INPUT_ITEMS) { "记".repeat(EmbeddingClient.MAX_INPUT_CHARS) })
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `oversized chunked response is rejected while reading`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(" ".repeat(4 * 1024 * 1024 + 1), 8192))
        val failure = assertFailsWith<EmbeddingException> {
            EmbeddingClient().embed(endpoint, "model", "", listOf("a"))
        }
        assertEquals("Embedding response is too large.", failure.message)
    }

    @Test fun `cancelling an embedding cancels the HTTP call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()
        val running = async(Dispatchers.Default) {
            EmbeddingClient(http).embed(endpoint, "model", "", listOf("test"))
        }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        running.cancelAndJoin()
        assertTrue(cancelled.get())
    }
}
