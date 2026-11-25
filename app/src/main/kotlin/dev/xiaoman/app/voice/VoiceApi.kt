package dev.xiaoman.app.voice

import android.util.Log
import dev.xiaoman.app.settings.TtsProvider
import dev.xiaoman.app.settings.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The two OpenAI-compatible audio endpoints the call screen needs:
 * `POST {baseUrl}/audio/transcriptions` (multipart, returns JSON with `text`) and
 * `POST {baseUrl}/audio/speech` (JSON, returns MP3 bytes). Nothing here touches the agent.
 */
class VoiceApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Speech to text; throws with a readable message on failure. */
    suspend fun transcribe(config: VoiceSettings, audio: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("file", audio.name, audio.asRequestBody(audioType(audio)))
            .build()
        client.newCall(request(config, "audio/transcriptions", body)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(failure("识别失败", response.code, text))
            text.field("text")?.takeIf { it.isNotBlank() } ?: throw error("识别结果为空")
        }
    }

    /**
     * 声音复刻：把一段 8–30 秒的样本（wav/mp3/pcm/opus）上传到 SiliconFlow，返回可当 `voice`
     * 使用的 `speech:` uri。样本只用于生成音色，本方法不保存任何副本。
     */
    suspend fun cloneVoice(config: VoiceSettings, name: String, text: String, audio: File): String =
        withContext(Dispatchers.IO) {
            if (!audio.isFile) throw error("样本文件不存在")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("customName", name)
                .addFormDataPart("text", text)
                .addFormDataPart("audio", audio.name, audio.asRequestBody(audioType(audio)))
                .build()
            client.newCall(request(config, "uploads/audio/voice", body)).execute().use { response ->
                val json = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw error(failure("生成音色失败", response.code, json))
                json.field("uri")?.takeIf { it.isNotBlank() } ?: throw error("生成音色失败：没有返回音色标识")
            }
        }

    /** 删除云端克隆音色；调用方负责同时删掉本地记录。 */
    suspend fun deleteVoice(config: VoiceSettings, uri: String): Unit = withContext(Dispatchers.IO) {
        val json = "{\"uri\":${uri.quoted()}}"
        client.newCall(request(config, "audio/voices/deletions", json.toRequestBody(JSON))).execute().use { response ->
            if (!response.isSuccessful) {
                throw error("删除云端音色失败（${response.code}）：${response.body?.string().orEmpty().message()}")
            }
        }
    }

    /**
     * Text to speech；返回可直接播放的音频字节（两套供应商都是 mp3）。
     * [instruction] 只用于 CosyVoice2 的自然语言情绪控制；豆包没有这个入口，正文里也绝不拼指令。
     */
    suspend fun speak(
        config: VoiceSettings,
        text: String,
        instruction: String? = null,
        fallback: VoiceSettings? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        // 出口最后一道校验：正文里不允许残留表演说明（「语速稍慢」等），有就剔除并留痕。
        val spoken = TtsScript.withoutDirection(text)
        if (spoken != text) {
            Log.w("Xiaoman", "tts: stripped direction wording <${TtsScript.directionLeak(text)}> from body")
        }
        if (config.provider == TtsProvider.DOUBAO) {
            // 豆包出错（错误帧、超时、断线）时若硅基还配着，就降级到硅基，保证通话不会整段没声音。
            return@withContext try {
                doubaoSpeak(config, spoken, instruction)
            } catch (e: Exception) {
                val alt = fallback?.takeIf { it.configured && it.provider == TtsProvider.SILICONFLOW }
                if (alt == null) throw e
                Log.w("Xiaoman", "doubao tts failed, fallback to siliconflow: ${e.message}")
                return@withContext siliconSpeak(alt, spoken, instruction)
            }
        }
        return@withContext siliconSpeak(config, spoken, instruction)
    }

    /** 硅基 CosyVoice2 的 OpenAI 兼容请求；豆包失败时也用它兜底。 */
    private fun siliconSpeak(config: VoiceSettings, spoken: String, instruction: String?): ByteArray {
        val input = expressiveSpeechInput(config, spoken, instruction)
        val json = buildString {
            append("{\"model\":").append(config.model.quoted())
            append(",\"input\":").append(input.quoted())
            if (config.voice.isNotBlank()) append(",\"voice\":").append(config.voice.quoted())
            // 硅基的 speed / gain 只对 CosyVoice2 有明确语义；其它供应商不认识这些字段，可能直接 400。
            if (config.model.startsWith("FunAudioLLM/CosyVoice2")) {
                append(",\"speed\":").append(number(config.speed.coerceIn(0.25f, 4f)))
                // 固定增益：逐句合成时让每句响度一致，避免句子之间忽大忽小。
                append(",\"gain\":").append(number(TTS_GAIN))
            }
            append(",\"response_format\":\"mp3\"}")
        }
        // 脱敏留痕：只记分界符前后的结构与长度，便于核对指令没有被拼进正文段。
        val split = "<|endofprompt|>"
        val before = if (split in input) input.substringBefore(split) else "<无指令>"
        val after = if (split in input) input.substringAfter(split) else input
        Log.d(
            "Xiaoman",
            "tts body: model=${config.model} voice=${if (config.voice.isNotBlank()) "set" else "none"} " +
                "speed=${number(config.speed)} gain=${number(TTS_GAIN)} " +
                "before=<${before.take(40)}> after=<${after.take(20)}…> len=${input.length}",
        )
        return client.newCall(request(config, "audio/speech", json.toRequestBody(JSON))).execute().use { response ->
            if (!response.isSuccessful) throw error(failure("朗读失败", response.code, response.body?.string().orEmpty()))
            response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: throw error("朗读结果为空")
        }
    }

    /**
     * 豆包语音合成 2.0：WebSocket 双向流。连接复用、一句一个 session，
     * 音频从 AudioOnlyServer 帧的裸二进制负载里取（见 [DoubaoTts]）。
     */
    private fun doubaoSpeak(config: VoiceSettings, text: String, instruction: String?): ByteArray {
        Log.d(
            "Xiaoman",
            "tts body: provider=doubao voice=${config.voice.ifBlank { "none" }} resource=${config.resourceId} len=${text.length}",
        )
        return DoubaoTts.speak(config, text, instruction)
    }

    private fun request(config: VoiceSettings, path: String, body: okhttp3.RequestBody): Request =
        post(config.baseUrl.trim().trimEnd('/') + "/" + path, body, config)

    private fun post(url: String, body: okhttp3.RequestBody, config: VoiceSettings): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .post(body)
            .build()

    /** 统一失败文案：日志只记状态码与服务端 message，不打印 Key；401 时点明 Key 与服务商不匹配。 */
    private fun failure(label: String, code: Int, body: String): String {
        val detail = body.message()
        Log.w("Xiaoman", "voice $label HTTP $code detail=$detail")
        val hint = if (code == 401 || code == 403) {
            "。这个地址只认对应服务商的 Key（硅基、方舟、豆包 的 Key 互不通用）"
        } else {
            ""
        }
        return "$label（$code）：$detail$hint"
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        /** 统一的响度增益（dB）；逐句合成时固定，句与句之间不会忽大忽小。 */
        const val TTS_GAIN = 0f

        /** 两位小数的 JSON 数字，避免 Locale 把小数点写成逗号。 */
        fun number(value: Float): String = String.format(java.util.Locale.ROOT, "%.2f", value)

        /** 供应商只接受 mp3 / wav / pcm / opus；其它扩展名按二进制上传，失败时由返回信息提示。 */
        fun audioType(file: File) = when (file.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "pcm" -> "audio/pcm"
            "opus" -> "audio/opus"
            else -> "application/octet-stream"
        }.toMediaType()
    }
}

/** SiliconFlow CosyVoice2 的官方自然语言控制格式；其他兼容服务仍接收纯文本。 */
internal fun expressiveSpeechInput(config: VoiceSettings, text: String, instruction: String? = null): String {
    if (!config.model.startsWith("FunAudioLLM/CosyVoice2")) return text
    // 指令只写在分隔符前；指令里若混进分隔符会打乱边界，先清掉。
    val prompt = instruction?.replace("<|endofprompt|>", " ")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "语气自然，跟着内容走。"
    return "$prompt<|endofprompt|>$text"
}

/** Escapes [this] as the inside of a JSON string. */
internal fun String.quoted(): String = buildString(length + 2) {
    append('"')
    for (c in this@quoted) {
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

/** Value of the first integer field [name] in [json]; null when absent. */
private fun String.intField(name: String): Int? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*(-?\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull()

/** Value of the first string field [name] in [json], unescaped; null when absent. */
private fun String.field(name: String): String? {
    val match = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(this) ?: return null
    return match.groupValues[1].unescaped()
}

private fun String.unescaped(): String = buildString(length) {
    var i = 0
    while (i < this@unescaped.length) {
        val c = this@unescaped[i]
        if (c != '\\' || i + 1 >= this@unescaped.length) {
            append(c)
            i++
            continue
        }
        when (val e = this@unescaped[i + 1]) {
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'b' -> append('\b')
            'f' -> append('\u000C')
            'u' -> {
                val code = this@unescaped.substring(i + 2, minOf(i + 6, this@unescaped.length)).toIntOrNull(16)
                if (code != null) {
                    append(code.toChar())
                    i += 4
                } else {
                    append(e)
                }
            }
            else -> append(e)
        }
        i += 2
    }
}

/** A short human-readable error from a provider response body. */
internal fun String.message(): String =
    field("message") ?: field("error") ?: trim().take(200).ifBlank { "没有返回内容" }
