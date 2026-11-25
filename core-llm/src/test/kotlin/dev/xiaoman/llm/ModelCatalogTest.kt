package dev.xiaoman.llm

import dev.xiaoman.api.ApiProtocol
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun config(protocol: ApiProtocol = ApiProtocol.OPENAI) = ModelConfig(
        role = ModelRole.CHAT,
        baseUrl = server.url("/v1/").toString(),
        model = "",
        apiKey = "sk-secret",
        protocol = protocol,
    )

    @Test
    fun `lists model ids for an OpenAI-compatible vendor`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"object":"list","data":[{"id":"b-model"},{"id":"a-model"},{"id":""},{"id":"a-model"}]}""",
            ),
        )
        val names = ModelCatalog.list(config(), LlmClients.sharedHttp)
        assertEquals(listOf("a-model", "b-model"), names)

        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `anthropic uses its own headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"claude-x"}]}"""))
        val names = ModelCatalog.list(config(ApiProtocol.ANTHROPIC), LlmClients.sharedHttp)
        assertEquals(listOf("claude-x"), names)

        val recorded = server.takeRequest()
        assertEquals("sk-secret", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
    }

    @Test
    fun `failures return an empty list so defaults are kept`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        assertTrue(ModelCatalog.list(config(), LlmClients.sharedHttp).isEmpty())

        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(ModelCatalog.list(config(), LlmClients.sharedHttp).isEmpty())
    }
}
