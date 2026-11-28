package dev.lily.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fails without including supplied credentials, input text, or the response body. */
class EmbeddingException(message: String, val statusCode: Int? = null) : IOException(message)

/** Requests dense text embeddings from an OpenAI-compatible embedding endpoint. */
class EmbeddingClient(http: OkHttpClient = LlmClients.sharedHttp) {
    private val client = http.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()

    private val localClient = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val local = http.dns.lookup(hostname).filter { it.isLoopbackAddress }
                if (local.isEmpty()) throw UnknownHostException("No loopback address")
                return local
            }
        })
        .build()

    suspend fun embed(url: String, model: String, apiKey: String, inputs: List<String>): List<FloatArray> {
        val endpoint = url.toHttpUrlOrNull()?.takeIf(::validEndpoint)
            ?: throw EmbeddingException("Invalid embedding endpoint.")
        val modelName = model.trim()
        if (modelName.isEmpty() || modelName.length > MAX_MODEL_CHARS || inputs.isEmpty() || inputs.size > MAX_INPUT_ITEMS ||
            inputs.any { it.isBlank() || it.length > MAX_INPUT_CHARS }
        ) {
            throw EmbeddingException("Invalid embedding request.")
        }
        val token = apiKey.trim()
        if (token.length > MAX_KEY_CHARS) throw EmbeddingException("Invalid embedding credentials.")
        val payload = buildJsonObject {
            put("model", modelName)
            put("input", JsonArray(inputs.map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_REQUEST_BYTES) throw EmbeddingException("Embedding request is too large.")
        val builder = Request.Builder().url(endpoint).post(EmbeddingBody(payload))
        if (token.isNotEmpty()) {
            try {
                builder.header("Authorization", "Bearer $token")
            } catch (_: IllegalArgumentException) {
                throw EmbeddingException("Invalid embedding credentials.")
            }
        }
        return execute(if (endpoint.isHttps) client else localClient, builder.build(), inputs.size)
    }

    private suspend fun execute(http: OkHttpClient, request: Request, size: Int): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    continuation.resume(call.execute().use { parse(it, size) })
                } catch (e: EmbeddingException) {
                    continuation.resumeWithException(e)
                } catch (_: IOException) {
                    continuation.resumeWithException(EmbeddingException("Embedding request failed."))
                }
            }
        }

    private fun parse(response: Response, count: Int): List<FloatArray> {
        if (!response.isSuccessful) {
            throw EmbeddingException("Embedding service returned HTTP ${response.code}.", response.code)
        }
        val body = response.body ?: invalidResponse()
        val source = body.source()
        if (body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1)) {
            throw EmbeddingException("Embedding response is too large.")
        }
        val document = try {
            Json.parseToJsonElement(source.readUtf8())
        } catch (_: IllegalArgumentException) {
            invalidResponse()
        }
        val data = (document as? JsonObject)?.get("data") as? JsonArray ?: invalidResponse()
        if (data.size != count) invalidResponse()
        val ordered = arrayOfNulls<FloatArray>(count)
        var dimensions = 0
        for (entry in data) {
            val item = entry as? JsonObject ?: invalidResponse()
            val index = (item["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalidResponse()
            if (index !in ordered.indices || ordered[index] != null) invalidResponse()
            val embedding = item["embedding"] as? JsonArray ?: invalidResponse()
            if (embedding.size !in 1..MAX_DIMENSIONS) invalidResponse()
            if (dimensions == 0) dimensions = embedding.size
            if (embedding.size != dimensions) invalidResponse()
            val vector = FloatArray(dimensions) { i ->
                (embedding[i] as? JsonPrimitive)?.takeUnless { it.isString }
                    ?.floatOrNull?.takeIf { it.isFinite() } ?: invalidResponse()
            }
            if (vector.all { it == 0f }) invalidResponse()
            ordered[index] = vector
        }
        return ordered.map { it ?: invalidResponse() }
    }

    private fun validEndpoint(endpoint: HttpUrl): Boolean {
        if (endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() ||
            endpoint.query != null || endpoint.fragment != null || !endpoint.encodedPath.endsWith("/embeddings")
        ) return false
        if (endpoint.isHttps || endpoint.host == "localhost" || endpoint.host == "::1") return true
        val octets = endpoint.host.split('.')
        return octets.size == 4 && octets[0] == "127" &&
            octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun invalidResponse(): Nothing = throw EmbeddingException("Invalid embedding response.")

    private class EmbeddingBody(private val bytes: ByteArray) : RequestBody() {
        override fun contentType(): MediaType = JSON_MEDIA_TYPE
        override fun contentLength(): Long = bytes.size.toLong()
        // OkHttp may otherwise resend a POST after a 503 with Retry-After: 0.
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    companion object {
        const val MAX_INPUT_ITEMS = 32
        const val MAX_INPUT_CHARS = 6000
        const val MAX_DIMENSIONS = 8192
        private const val MAX_MODEL_CHARS = 256
        private const val MAX_KEY_CHARS = 8192
        private const val MAX_REQUEST_BYTES = 256 * 1024
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
