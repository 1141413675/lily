package dev.lily.app.voice

import dev.lily.app.settings.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs 朗读。
 *
 * 接口形态与 OpenAI 兼容的那套不一样：
 * - 鉴权头是 `xi-api-key`（不是 `Authorization: Bearer`）；
 * - 音色 id 放在 URL 路径里：`POST {base}/text-to-speech/{voice_id}`；
 * - 响应是裸音频字节。
 */
object ElevenLabsTts {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun speak(config: VoiceSettings, text: String): ByteArray = withContext(Dispatchers.IO) {
        if (config.voice.isBlank()) throw error("ElevenLabs 需要填音色 id")
        val url = "${config.baseUrl.trimEnd('/')}/text-to-speech/${config.voice.trim()}"
        val body = buildString {
            append("{\"text\":").append(quote(text))
            append(",\"model_id\":").append(quote(config.model))
            append(",\"voice_settings\":{\"stability\":0.5,\"similarity_boost\":0.5}")
            append(",\"output_format\":\"mp3_44100_128\"}")
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("xi-api-key", config.apiKey.trim())
            .header("Accept", "audio/mpeg")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw error("ElevenLabs 朗读失败（${response.code}）：${response.body?.string().orEmpty().take(200)}")
                }
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
                    ?: throw error("ElevenLabs 没有返回音频")
            }
        } catch (e: IOException) {
            throw error("ElevenLabs 连不上：${e.message ?: "网络错误"}")
        }
    }

    /** JSON 字符串转义。 */
    internal fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
