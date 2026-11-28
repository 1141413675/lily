package dev.lily.app.voice

import dev.lily.app.settings.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MiniMax 朗读。
 *
 * 两个容易踩的坑：
 * 1. 响应的 `data.audio` 是 **hex 编码**（不是 base64），得按 hex 解；
 * 2. 部分账号按组授权，没有 GroupId 会 401——所以设置里留了这个字段。
 */
object MiniMaxTts {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun speak(config: VoiceSettings, text: String): ByteArray = withContext(Dispatchers.IO) {
        val url = buildUrl(config)
        val body = buildString {
            append("{\"model\":").append(ElevenLabsTts.quote(config.model))
            append(",\"text\":").append(ElevenLabsTts.quote(text))
            append(",\"stream\":false")
            append(",\"voice_setting\":{")
            append("\"voice_id\":").append(ElevenLabsTts.quote(config.voice))
            append(",\"speed\":").append(config.speed.coerceIn(0.5f, 2.0f))
            append("}")
            append(",\"audio_setting\":{\"format\":\"mp3\",\"sample_rate\":32000,\"bitrate\":128000,\"channel\":1}")
            append("}")
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw error("MiniMax 朗读失败（${response.code}）：${text.take(200)}")
                }
                parseAudio(text)
            }
        } catch (e: IOException) {
            throw error("MiniMax 连不上：${e.message ?: "网络错误"}")
        }
    }

    /** 带上 GroupId（地址里已经有了就不重复加）。 */
    internal fun buildUrl(config: VoiceSettings): String {
        val base = config.baseUrl.trim()
        val group = config.groupId.trim()
        if (group.isEmpty() || base.contains("GroupId=")) return base
        val separator = if (base.contains('?')) "&" else "?"
        return "$base$separator" + "GroupId=$group"
    }

    /** 从响应里取出音频字节。 */
    internal fun parseAudio(body: String): ByteArray {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw error("MiniMax 返回的不是 JSON：${body.take(120)}")
        }
        val baseResp = root.optJSONObject("base_resp")
        val code = baseResp?.optInt("status_code", 0) ?: 0
        if (code != 0) {
            throw error("MiniMax 朗读失败：${baseResp?.optString("status_msg") ?: "错误码 $code"}")
        }
        val hex = root.optJSONObject("data")?.optString("audio").orEmpty()
        if (hex.isBlank()) throw error("MiniMax 没有返回音频")
        return decodeHex(hex)
    }

    /** hex 字符串转字节；不是合法 hex 就给个能看懂的错。 */
    internal fun decodeHex(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) throw error("MiniMax 返回的音频数据不完整")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw error("MiniMax 返回的音频数据不是有效的 hex")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
