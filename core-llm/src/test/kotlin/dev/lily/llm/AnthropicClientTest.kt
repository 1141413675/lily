package dev.lily.llm

import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.ChatRole
import dev.lily.api.ContentPart
import dev.lily.api.LlmException
import dev.lily.api.ModelConfig
import dev.lily.api.ModelRole
import dev.lily.api.ToolCall
import dev.lily.api.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicClientTest {
    private val apiKey = "sk-ant-secret-123456"
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun config(maxTokens: Int? = null) = ModelConfig(
        role = ModelRole.CHAT,
        baseUrl = server.url("/v1/").toString(),
        model = "claude-test",
        apiKey = apiKey,
        temperature = 0.2,
        maxTokens = maxTokens,
        timeoutSeconds = 10,
        protocol = dev.lily.api.ApiProtocol.ANTHROPIC,
    )

    private fun client(maxRetries: Int = 2, maxTokens: Int? = null) =
        AnthropicClient(config(maxTokens), maxRetries = maxRetries, baseDelayMillis = 100, sleep = { sleeps += it })

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `request uses messages endpoint, native headers and content blocks`() = runTest {
        server.enqueue(ok("""{"content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}"""))
        val png = byteArrayOf(1, 2, 3, 4)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
        }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage.system("be brief"),
                ChatMessage(ChatRole.USER, listOf(ContentPart.Text("what is this?"), ContentPart.ImagePng(png))),
                ChatMessage(ChatRole.ASSISTANT, emptyList(), toolCalls = listOf(ToolCall("call_1", "weather", """{"city":"北京"}"""))),
                ChatMessage.tool("call_1", "sunny"),
            ),
            tools = listOf(ToolSpec("weather", "Get the weather", schema)),
        )

        val response = client(maxTokens = 256).complete(request)
        assertEquals("hi", response.text)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.path)
        assertEquals(apiKey, recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        // Anthropic 不走 Bearer。
        assertEquals(null, recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-test", body["model"]!!.jsonPrimitive.content)
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("be brief", body["system"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val userBlocks = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", userBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", userBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", userBlocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = userBlocks[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(png), source["data"]!!.jsonPrimitive.content)

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val toolUse = assistant["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("weather", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals("北京", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)

        val toolResult = messages[2].jsonObject
        assertEquals("user", toolResult["role"]!!.jsonPrimitive.content)
        val result = toolResult["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", result["tool_use_id"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("weather", tool["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses text, tool_use and usage from native response`() = runTest {
        server.enqueue(
            ok(
                """
                {"id":"msg_1","type":"message","role":"assistant","content":[
                  {"type":"text","text":"我来查"},
                  {"type":"tool_use","id":"toolu_1","name":"weather","input":{"city":"北京"}}
                ],"stop_reason":"tool_use","usage":{"input_tokens":11,"output_tokens":7}}
                """.trimIndent(),
            ),
        )
        val response = client().complete(ChatRequest(listOf(ChatMessage.user("weather?"))))
        assertEquals("我来查", response.text)
        assertEquals("tool_use", response.finishReason)
        assertEquals(ToolCall("toolu_1", "weather", """{"city":"北京"}"""), response.toolCalls.single())
        assertEquals(11, response.usage?.promptTokens)
        assertEquals(7, response.usage?.completionTokens)
    }

    @Test
    fun `errors keep the api key out of the message and expose the provider code`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}""",
            ),
        )
        val error = assertFailsWith<LlmException> { client(maxRetries = 0).complete(ChatRequest(listOf(ChatMessage.user("hi")))) }
        assertEquals(400, error.statusCode)
        assertEquals("invalid_request_error", error.providerCode)
        assertFalse(apiKey in error.message.orEmpty())
        assertTrue("bad model" in error.message.orEmpty())
    }
}
