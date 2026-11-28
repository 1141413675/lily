package dev.lily.app.voice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 豆包实时语音模型 3.0（Seeduplex）全双工协议的事件层。
 *
 * 与 3.0 之前那套 OpenAI-Realtime 风格的协议**不是一回事**，事件名、字段位置、鉴权方式
 * 全都换过：
 *
 * | 语义 | 3.0 事件名 | 旧事件名（仅供对照） |
 * |---|---|---|
 * | 建会话 | `session.create` | `session.update` |
 * | 追加音频 | `input_audio_buffer.append` | 同名 |
 * | 强制判停 | `input_audio_buffer.commit` | 无 |
 * | 静音保活 | `input_audio_mute.commit` / `input_audio_unmute.commit` | 无 |
 * | 打断 | `response.cancel` | 同名 |
 * | 工具结果回传 | `conversation.item.create`（role=tool） | 同名但结构不同 |
 * | 结束会话 | `session.close` | 无 |
 *
 * 下行：`session.created` / `response.output_audio.delta` / `response.output_text.done` /
 * `conversation.item.input_audio_transcription.completed` /
 * `response.function_call_arguments.done` / `response.done` / `error`。
 *
 * 官方文档：https://docs.volcengine.com/docs/6561/2549732?lang=zh
 * 接入必读：https://docs.volcengine.com/docs/doubao-voice/access-must-read?lang=zh
 *
 * 这里只负责「事件 ⇄ JSON」，不碰网络与音频，便于单测。
 */
internal object Seeduplex {

    /** 全双工版本的模型固定值，不再由用户配置。 */
    const val MODEL = "1.2.6.1"

    /** 上行事件名。 */
    object Event {
        const val SESSION_CREATE = "session.create"
        const val SESSION_CLOSE = "session.close"
        const val AUDIO_APPEND = "input_audio_buffer.append"
        const val AUDIO_COMMIT = "input_audio_buffer.commit"
        const val AUDIO_MUTE = "input_audio_mute.commit"
        const val AUDIO_UNMUTE = "input_audio_unmute.commit"
        const val ITEM_CREATE = "conversation.item.create"
        const val ITEM_DELETE = "conversation.item.delete"
        const val RESPONSE_CANCEL = "response.cancel"
    }

    /** 下行事件名。 */
    object Down {
        const val SESSION_CREATED = "session.created"
        const val SESSION_CLOSED = "session.closed"
        const val AUDIO_COMMITTED = "input_audio_buffer.committed"
        const val TRANSCRIPTION_COMPLETED = "conversation.item.input_audio_transcription.completed"
        const val TRANSCRIPTION_FAILED = "conversation.item.input_audio_transcription.failed"
        const val OUTPUT_TEXT_DONE = "response.output_text.done"
        const val OUTPUT_AUDIO_STARTED = "response.output_audio.started"
        const val OUTPUT_AUDIO_DELTA = "response.output_audio.delta"
        const val OUTPUT_AUDIO_DONE = "response.output_audio.done"
        const val FUNCTION_CALL_DONE = "response.function_call_arguments.done"
        const val RESPONSE_DONE = "response.done"
        const val RESPONSE_CANCELED = "response.canceled"
        const val ERROR = "error"
    }

    /**
     * 建会话。
     *
     * @param instructions 系统提示词。
     * @param voice 音色 id，取自官方 S2S 音色列表（与豆包 TTS 2.0 同一套 `*_uranus_bigtts`）。
     * @param tools 函数声明；为空就不带 tools 字段。
     */
    fun sessionCreate(
        instructions: String,
        voice: String,
        tools: JSONArray? = null,
        sessionId: String? = null,
    ): String {
        val audio = JSONObject()
            .put("input", JSONObject()
                .put("format", JSONObject().put("type", "pcm").put("rate", 16_000)))
            // 输出固定 24kHz 的 16bit 小端 PCM：播放端不必再做重采样。
            .put("output", JSONObject()
                .put("format", JSONObject().put("type", "pcm_s16le").put("rate", 24_000))
                .put("voice", voice))
        val session = JSONObject()
            .put("model", MODEL)
            .put("instructions", instructions)
            .put("audio", audio)
        if (sessionId != null && sessionId.isNotBlank()) session.put("id", sessionId)
        if (tools != null && tools.length() > 0) session.put("tools", tools)
        return JSONObject().put("type", Event.SESSION_CREATE).put("session", session).toString()
    }

    /** 追加一段麦克风音频；[pcm] 必须是 16k/单声道/int16 小端。 */
    fun audioAppend(pcm: ByteArray): String =
        JSONObject()
            .put("type", Event.AUDIO_APPEND)
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .toString()

    /** 强制判停：告诉模型「用户这段话讲完了」。 */
    fun audioCommit(): String =
        JSONObject().put("type", Event.AUDIO_COMMIT).toString()

    /** 麦克风关闭时保活：不发音频帧时必须发它，否则服务端判定无输入而超时。 */
    fun audioMute(): String =
        JSONObject().put("type", Event.AUDIO_MUTE).toString()

    /** 麦克风恢复。 */
    fun audioUnmute(): String =
        JSONObject().put("type", Event.AUDIO_UNMUTE).toString()

    /** 客户端主动打断（抢话）。 */
    fun responseCancel(): String =
        JSONObject().put("type", Event.RESPONSE_CANCEL).toString()

    /** 优雅结束会话：官方要求先发它、等 `session.closed` 再断链，否则服务端记 ContextCanceled。 */
    fun sessionClose(): String =
        JSONObject().put("type", Event.SESSION_CLOSE).toString()

    /**
     * 回传工具执行结果。
     *
     * 3.0 的结构与旧协议不同：结果放在 `items` 数组里，`role` 固定 `tool`，
     * 内容放 `content` 数组的 `input_text`，并且必须带与下行一致的 `call_id`。
     */
    fun toolResult(callId: String, output: String): String {
        val item = JSONObject()
            .put("call_id", callId)
            .put("role", "tool")
            .put("content", JSONArray().put(JSONObject()
                .put("type", "input_text")
                .put("text", output)))
        return JSONObject()
            .put("type", Event.ITEM_CREATE)
            .put("items", JSONArray().put(item))
            .toString()
    }

    /** 删除一条上下文（自回声兜底用）。3.0 用 `items` 数组传 id。 */
    fun itemDelete(itemId: String): String =
        JSONObject()
            .put("type", Event.ITEM_DELETE)
            .put("items", JSONArray().put(JSONObject().put("id", itemId)))
            .toString()

    /** 下行工具调用里的一项。 */
    data class FunctionCall(val callId: String, val name: String, val arguments: String)

    /** 从 `response.function_call_arguments.done` 里取出全部函数调用（可能并行多个）。 */
    fun functionCalls(event: JSONObject): List<FunctionCall> {
        val items = event.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<FunctionCall>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val callId = item.optString("call_id").trim()
            val name = item.optString("name").trim()
            if (callId.isEmpty() || name.isEmpty()) continue
            out += FunctionCall(callId, name, item.optString("arguments"))
        }
        return out
    }

    /** 取下行的纯文本内容（模型回复文本 / 用户识别结果）。 */
    fun textOf(event: JSONObject): String {
        // 3.0 的文本字段在不同事件里叫 text 或 delta；这里都认。
        val text = event.optString("text").ifBlank { event.optString("delta") }.trim()
        if (text.isNotEmpty()) return text
        // 有的实现把它放在 item/content 数组里。
        val item = event.optJSONObject("item")
        if (item != null) {
            val content = item.optJSONArray("content")
            if (content != null) {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val t = part.optString("text").trim()
                    if (t.isNotEmpty()) return t
                }
            }
        }
        return ""
    }

    /** 取下行的音频负载（Base64）。 */
    fun audioOf(event: JSONObject): String =
        event.optString("delta").ifBlank { event.optString("audio") }.trim()

    /** 取错误信息。 */
    fun errorOf(event: JSONObject): String {
        val error = event.optJSONObject("error")
        if (error != null) {
            val message = error.optString("message").trim()
            val code = error.optString("code").trim()
            if (message.isNotEmpty() && code.isNotEmpty()) return "$code $message"
            if (message.isNotEmpty()) return message
            if (code.isNotEmpty()) return code
        }
        return event.optString("message").trim()
    }
}
