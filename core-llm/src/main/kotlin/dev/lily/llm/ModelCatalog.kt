package dev.lily.llm

import dev.lily.api.ApiProtocol
import dev.lily.api.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lists the models a vendor currently offers, for the settings dropdown.
 *
 * Best-effort only: any network or auth failure returns an empty list so the caller keeps the
 * built-in defaults and saving is never blocked. Never logs the API key or the response body.
 */
object ModelCatalog {
    /** Models available at [config]'s endpoint, or an empty list when the vendor cannot be asked. */
    suspend fun list(config: ModelConfig, http: OkHttpClient = LlmClients.sharedHttp): List<String> =
        withContext(Dispatchers.IO) {
            val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            val builder = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/models")
                .get()
            when (config.protocol) {
                ApiProtocol.ANTHROPIC -> {
                    builder.header("x-api-key", config.apiKey)
                    builder.header("anthropic-version", "2023-06-01")
                }
                ApiProtocol.OPENAI -> builder.header("Authorization", "Bearer ${config.apiKey}")
            }
            runCatching {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<String>()
                    parse(response.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList<String>())
        }

    internal fun parse(body: String): List<String> = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        data.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }.getOrDefault(emptyList())

    private const val TIMEOUT_SECONDS = 15L
    private val json = Json { ignoreUnknownKeys = true }
}
