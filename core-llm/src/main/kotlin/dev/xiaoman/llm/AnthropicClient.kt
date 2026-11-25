package dev.xiaoman.llm

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmClient] for Anthropic's native `/messages` API (`x-api-key` + `anthropic-version`).
 *
 * Anthropic does not expose an OpenAI-compatible `/chat/completions`, so this client maps the
 * shared [ChatRequest] shape (system messages, images, tool calls, tool results) onto Anthropic's
 * content blocks. Retries 408/409/429/5xx and transport errors like [OpenAiCompatibleClient].
 * Error messages never contain the API key.
 */
class AnthropicClient(
    override val config: ModelConfig,
    http: OkHttpClient = LlmClients.sharedHttp,
    private val maxRetries: Int = 2,
    private val baseDelayMillis: Long = 500,
    /** Backoff sleep; replace in tests to avoid real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LlmClient {
    private val http = http.newBuilder()
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()
    private val url = config.baseUrl.trimEnd('/') + "/messages"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        var attempt = 0
        while (true) {
            val result = try {
                send(buildBody(request))
            } catch (e: IOException) {
                if (attempt < maxRetries) {
                    sleep(backoff(attempt++, null))
                    continue
                }
                throw LlmException(redact("Request to $url failed: ${e.message ?: e.javaClass.simpleName}"), retryable = true, cause = e)
            }
            if (result.code in 200..299) return parseResponse(result.body)
            val providerCode = providerCode(result.body)
            val retryable = isRetryable(result.code) && providerCode != "rate_limit_exceeded_billing"
            if (retryable && attempt < maxRetries) {
                sleep(backoff(attempt++, result.retryAfter))
                continue
            }
            throw LlmException(redact("HTTP ${result.code} from $url: ${errorDetail(result.body)}"), result.code, retryable,
                providerCode = providerCode)
        }
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", config.model)
        // Anthropic requires max_tokens. 2048 covers normal replies; callers can lower it.
        put("max_tokens", config.maxTokens ?: DEFAULT_MAX_TOKENS)
        val system = request.messages
            .filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (system.isNotEmpty()) put("system", system)
        putJsonArray("messages") {
            anthropicMessages(request.messages).forEach { (role, blocks) ->
                addJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                }
            }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    }
                }
            }
        }
        config.temperature?.let { put("temperature", it) }
    }

    /** Converts chat messages to Anthropic messages, merging adjacent same-role turns. */
    private fun anthropicMessages(messages: List<ChatMessage>): List<Pair<String, List<JsonObject>>> {
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (message in messages) {
            if (message.role == ChatRole.SYSTEM) continue
            val role = if (message.role == ChatRole.ASSISTANT) "assistant" else "user"
            val blocks = blocksOf(message)
            if (blocks.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.first == role) last.second.addAll(blocks) else merged += role to blocks.toMutableList()
        }
        return merged
    }

    private fun blocksOf(message: ChatMessage): List<JsonObject> = when (message.role) {
        ChatRole.TOOL -> listOf(
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", message.toolCallId.orEmpty())
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", message.text)
                    }
                }
            },
        )
        ChatRole.ASSISTANT -> buildList {
            if (message.text.isNotEmpty()) add(textBlock(message.text))
            message.toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", arguments(call.argumentsJson))
                    },
                )
            }
        }
        else -> message.parts.mapNotNull { part ->
            when (part) {
                is ContentPart.Text -> textBlock(part.text)
                is ContentPart.ImagePng -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", "image/png")
                        put("data", Base64.getEncoder().encodeToString(part.bytes))
                    }
                }
            }
        }
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** Anthropic expects `input` as a JSON object; an unparsable argument string becomes `{}`. */
    private fun arguments(raw: String): JsonObject = try {
        json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    private fun parseResponse(body: String): ChatResponse {
        val root = try {
            json.parseToJsonElement(body) as JsonObject
        } catch (e: Exception) {
            throw LlmException(redact("Malformed response from $url: ${body.take(200)}"), 200, cause = e)
        }
        val blocks = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = blocks.mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            if (block.string("type") == "text") block.string("text") else null
        }.joinToString("")
        val toolCalls = blocks.mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            if (block.string("type") != "tool_use") return@mapNotNull null
            ToolCall(
                id = block.string("id").orEmpty(),
                name = block.string("name").orEmpty(),
                argumentsJson = (block["input"] as? JsonObject)?.toString() ?: "{}",
            )
        }
        val usage = (root["usage"] as? JsonObject)?.let {
            Usage(it.int("input_tokens") ?: 0, it.int("output_tokens") ?: 0)
        }
        return ChatResponse(text, toolCalls, root.string("stop_reason"), usage)
    }

    private suspend fun send(body: JsonObject): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        config.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val call = http.newCall(builder.build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                val result = call.execute().use { response ->
                    HttpResult(response.code, response.body?.string().orEmpty(), response.header("Retry-After"))
                }
                cont.resume(result)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }
            ?: (baseDelayMillis shl attempt.coerceAtMost(20))

    /** Extracts `error.message` when the body is Anthropic's error envelope. */
    private fun errorDetail(body: String): String {
        val message = runCatching {
            val error = (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
            error?.string("message")
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: body.take(300)
    }

    private fun providerCode(body: String): String? = runCatching {
        val error = (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
        error?.string("type")
    }.getOrNull()

    private fun redact(text: String): String =
        if (config.apiKey.isBlank()) text else text.replace(config.apiKey, "***")

    private class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 2048
        val json = Json { ignoreUnknownKeys = true }
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        fun isRetryable(code: Int) = code == 408 || code == 409 || code == 429 || code in 500..599
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
