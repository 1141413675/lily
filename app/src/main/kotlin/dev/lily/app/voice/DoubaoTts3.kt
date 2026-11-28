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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包语音合成大模型 3.0。
 *
 * 与 2.0 完全不是一套接入方式：
 * - 地址在 `voice.cn-beijing.volces.com`（2.0 是 `openspeech.bytedance.com`）；
 * - 音色 id 放在 **URL 路径**里（2.0 放在 body 的 `speaker`）；
 * - 走 HTTP 单向流式，响应是 **JSONL**：逐行读，`audio` 事件里是 base64 音频片段，
 *   拼起来就是一段完整音频；收到 `final` 事件收尾；
 * - 音色 id 也换了：3.0 是 `multi_xxx_v3_uranus_bigtts`，**2.0 的音色在 3.0 上用不了**。
 *
 * 但好处是 3.0 支持**复刻音色**——2.0 只支持官方音色。
 *
 * 文档：
 * - 单向流式：https://docs.volcengine.com/docs/6561/2850788
 * - 音色注册：https://docs.volcengine.com/docs/6561/2850791
 * - 音色设计：https://docs.volcengine.com/docs/6561/2850794
 */
object DoubaoTts3 {

    /** 接入域名。 */
    const val BASE_URL = "https://voice.cn-beijing.volces.com"

    /** 默认模型。 */
    const val MODEL_ID = "doubao-seed-tts-3.0"

    /** 音色注册与查询的路径。 */
    private const val CLONE_PATH = "/api/v3/tts/voice_clone"
    private const val GET_VOICE_PATH = "/api/v3/tts/get_voice"
    private const val DESIGN_PATH = "/api/v3/tts/voice_design"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 合成一段语音。
     *
     * 返回拼好的完整音频字节（mp3）。逐句调用即可，跟 2.0 的用法一样。
     */
    suspend fun speak(config: VoiceSettings, text: String): ByteArray = withContext(Dispatchers.IO) {
        val voiceId = config.voice.trim()
        if (voiceId.isEmpty()) throw error("豆包 3.0 需要选一个音色")
        val url = "${config.baseUrl.trimEnd('/')}/api/v3/text-to-speech/$voiceId/streaming"
        val body = buildString {
            append("{\"text\":").append(jsonString(text))
            append(",\"model_id\":").append(jsonString(config.model.ifBlank { MODEL_ID }))
            append(",\"language_code\":\"zh-CN\"")
            append(",\"output_format\":{\"format\":\"mp3\",\"sample_rate\":24000,\"bit_rate\":64000}")
            append(",\"voice_settings\":{")
            append("\"speed\":").append(config.speed.coerceIn(0.5f, 2.0f))
            append(",\"volume\":1.0")
            append("}")
            append("}")
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("X-Api-Key", config.apiKey.trim())
            .header("X-Request-Id", UUID.randomUUID().toString())
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw error(errorMessage(text) ?: "豆包 3.0 合成失败（${response.code}）")
                parseAudioStream(text)
            }
        } catch (e: IOException) {
            throw error("豆包 3.0 连不上：${e.message ?: "网络错误"}")
        }
    }

    /**
     * 解析 JSONL 响应：把 `audio` 事件的 base64 片段按顺序拼起来。
     *
     * 一个 HTTP 数据块可能包含半行或多行，所以按换行拆行、逐行解析。
     */
    internal fun parseAudioStream(body: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sawAudio = false
        var error: String? = null
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
            when (obj.optString("event")) {
                "audio" -> {
                    val chunk = obj.optString("audio")
                    if (chunk.isNotEmpty()) {
                        val bytes = runCatching { android.util.Base64.decode(chunk, android.util.Base64.DEFAULT) }
                            .getOrNull()
                        if (bytes != null) {
                            out.write(bytes)
                            sawAudio = true
                        }
                    }
                }
                "error" -> {
                    val e = obj.optJSONObject("error")
                    error = e?.let {
                        listOfNotNull(it.optString("code").takeIf { c -> c.isNotEmpty() }, it.optString("message"))
                            .joinToString("：")
                    } ?: "未知错误"
                }
            }
        }
        if (error != null) throw error("豆包 3.0 合成失败：$error")
        if (!sawAudio) throw error("豆包 3.0 没有返回音频")
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 音色复刻

    /**
     * 注册复刻音色。
     *
     * @param audio 样本音频，10–60 秒、≤10MB
     * @param promptText 样本的转写文本（部分语种必传）
     * @param language 录音语种的编号，默认 0（中文）
     * @return 服务端返回的音色 id 与训练状态
     */
    suspend fun cloneVoice(
        config: VoiceSettings,
        audio: ByteArray,
        promptText: String,
        language: Int = 0,
        demoText: String = "",
    ): CloneResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("speaker_id", "custom_speaker_id")
            put("custom_speaker_id", "LilyVoice" + UUID.randomUUID().toString().take(8))
            put("language", language)
            put("audio", JSONObject().apply {
                put("format", "wav")
                put("data", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP))
            })
            put("extra_params", JSONObject().apply {
                if (promptText.isNotBlank()) put("prompt_text", promptText)
                if (demoText.isNotBlank()) put("demo_text", demoText)
            })
        }
        val json = post(config, CLONE_PATH, body)
        val root = JSONObject(json)
        val status = root.optInt("status", 0)
        CloneResult(
            speakerId = root.optString("speaker_id"),
            status = status,
            ready = status == 2 || status == 4,
            remaining = root.optInt("available_training_times", 0),
            demoAudioBase64 = root.optJSONObject("speaker_status")
                ?.optString("demo_audio")
                ?.takeIf { it.isNotEmpty() },
            message = root.optString("message"),
        )
    }

    /** 查音色训练状态。 */
    suspend fun queryVoice(config: VoiceSettings, speakerId: String): CloneResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("speaker_id", speakerId)
            val json = post(config, GET_VOICE_PATH, body)
            val root = JSONObject(json)
            val status = root.optInt("status", 0)
            CloneResult(
                speakerId = root.optString("speaker_id").ifEmpty { speakerId },
                status = status,
                ready = status == 2 || status == 4,
                remaining = root.optInt("available_training_times", 0),
                message = root.optString("message"),
            )
        }

    /**
     * 设计音色：用文字描述或角色图生成一个音色。
     *
     * @param speakerId 账号下已购买的 `S_` 开头的预付费音色 id
     * @param textPrompt 文字描述，如「女性，语速中等偏快，语调低沉有力」
     * @param imageBytes 角色图（可选）；给了就优先用它
     */
    suspend fun designVoice(
        config: VoiceSettings,
        speakerId: String,
        textPrompt: String,
        imageBytes: ByteArray? = null,
        demoText: String = "",
    ): CloneResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("speaker_id", speakerId)
            if (demoText.isNotBlank()) put("text", demoText)
            put("prompt", JSONObject().apply {
                if (textPrompt.isNotBlank()) put("text_prompt", textPrompt)
                if (imageBytes != null) {
                    put("image_prompt", JSONObject().apply {
                        put("image_bytes", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                    })
                }
            })
        }
        val json = post(config, DESIGN_PATH, body)
        val root = JSONObject(json)
        val status = root.optInt("status", 0)
        CloneResult(
            speakerId = root.optString("speaker_id").ifEmpty { speakerId },
            status = status,
            ready = status == 2 || status == 4,
            remaining = root.optInt("available_training_times", 0),
            demoAudioBase64 = root.optJSONObject("speaker_status")
                ?.optString("demo_audio")
                ?.takeIf { it.isNotEmpty() },
            message = root.optString("message"),
        )
    }

    private fun post(config: VoiceSettings, path: String, body: JSONObject): String {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(JSON))
            .header("X-Api-Key", config.apiKey.trim())
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw error(errorMessage(text) ?: "请求失败（${response.code}）")
                }
                text
            }
        } catch (e: IOException) {
            throw error("豆包连不上：${e.message ?: "网络错误"}")
        }
    }

    /** 从错误响应里挖出可读原因。 */
    private fun errorMessage(text: String): String? = runCatching {
        val root = JSONObject(text)
        val e = root.optJSONObject("error")
        val code = e?.optString("code").orEmpty()
        val message = e?.optString("message").orEmpty()
        listOfNotNull(code.takeIf { it.isNotEmpty() }, message.takeIf { it.isNotEmpty() })
            .joinToString("：")
            .ifEmpty { null }
    }.getOrNull()

    /** JSON 字符串转义。 */
    internal fun jsonString(value: String): String = buildString {
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

/** 音色注册/查询/设计的返回。 */
data class CloneResult(
    /** 音色 id：3.0 的音色直接可以拿去合成。 */
    val speakerId: String = "",
    /** 训练状态：2 训练成功、4 已激活；这两个才可合成。 */
    val status: Int = 0,
    /** 能不能用了。 */
    val ready: Boolean = false,
    /** 剩余复刻次数。 */
    val remaining: Int = 0,
    /** 试听音频（base64）。 */
    val demoAudioBase64: String? = null,
    /** 服务端的说明。 */
    val message: String = "",
)
