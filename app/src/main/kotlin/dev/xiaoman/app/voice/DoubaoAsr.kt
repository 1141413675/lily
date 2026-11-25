package dev.xiaoman.app.voice

import android.util.Log
import dev.xiaoman.api.ApiErrors
import dev.xiaoman.app.settings.VoiceSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as HttpResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包「流式语音识别 2.0」（大模型双向流式，`api/v3/sauc/bigmodel_async`）。
 *
 * 二进制协议与豆包 TTS 同源但更简单：没有 event / session，只有
 * 4 字节头 + （音频包才有）4 字节序号 + 4 字节负载长度 + 负载。
 * 先发一包 full client request（JSON 参数），随后持续发 audio only request（16k 单声道 PCM16，
 * gzip），最后一包用负序号收尾；服务端只回 full server response（JSON，可能 gzip）。
 * 识别结果只取 `result.text`（为空时按顺序拼 `utterances[definite=true].text`），
 * 情绪/语速/性别等 additions 字段一律忽略。
 */
internal object DoubaoAsr {

    const val DEFAULT_BASE_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"

    /** 官方消息类型。 */
    object Msg {
        const val CLIENT_FULL_REQUEST = 0x1
        const val AUDIO_ONLY_REQUEST = 0x2
        const val SERVER_FULL_RESPONSE = 0x9
        const val SERVER_ERROR = 0xF
    }

    /** 消息专属标志位：普通包带正序号，最后一包带负序号。 */
    object Flag {
        const val POS_SEQUENCE = 0x1
        const val LAST_NO_SEQUENCE = 0x2
        const val NEG_SEQUENCE = 0x3
    }

    private const val PROTOCOL_VERSION = 1
    private const val HEADER_WORDS = 1
    private const val SERIALIZATION_JSON = 1
    private const val SERIALIZATION_RAW = 0
    private const val COMPRESSION_GZIP = 1

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val FINISH_TIMEOUT_MS = 10_000L

    /** 合成文件转写时每包 100ms（16k × 2 字节 × 0.1s）。 */
    private const val CHUNK_BYTES = 3_200

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val EMPTY = ByteArray(0)

    /** 一帧服务端响应；[payload] 已按压缩标志解压。 */
    data class Frame(
        val messageType: Int,
        val flags: Int,
        val sequence: Int?,
        val payload: ByteArray,
        /** 服务端错误码；握手失败时是 HTTP 状态码。 */
        val errorCode: Int? = null,
    ) {
        val isError: Boolean get() = messageType == Msg.SERVER_ERROR
        val isLast: Boolean get() = flags and Flag.LAST_NO_SEQUENCE != 0
        val text: String get() = if (isError) "" else textOf(payload)
    }

    /** 首包 JSON：音频参数与识别参数都在这里给一次。 */
    internal fun firstRequestJson(): String = buildString {
        append("{\"user\":{\"uid\":\"xiaoman\",\"platform\":\"Android\"}")
        append(",\"audio\":{\"format\":\"pcm\",\"codec\":\"raw\",\"rate\":16000,\"bits\":16,\"channel\":1}")
        append(",\"request\":{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true")
        append(",\"show_utterances\":true,\"result_type\":\"full\"}}")
    }

    /** 组一个客户端全量请求帧：4 字节头 + 负载长度 + gzip JSON。 */
    internal fun buildFullRequest(body: String): ByteArray {
        val header = byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_WORDS).toByte(),
            ((Msg.CLIENT_FULL_REQUEST shl 4) or 0).toByte(),
            ((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP).toByte(),
            0,
        )
        val payload = gzip(body.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(int32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * 组一个音频帧：4 字节头 + 4 字节序号 + 4 字节负载长度 + gzip PCM。
     * 普通包序号从 1 递增、flags=1；最后一包 flags=3 且序号取负数。
     */
    internal fun audioPacket(
        sequence: Int,
        pcm: ByteArray,
        offset: Int = 0,
        count: Int = pcm.size,
        last: Boolean = false,
    ): ByteArray {
        val flags = if (last) Flag.NEG_SEQUENCE else Flag.POS_SEQUENCE
        val seq = if (last) -kotlin.math.max(kotlin.math.abs(sequence), 1) else sequence
        val header = byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_WORDS).toByte(),
            ((Msg.AUDIO_ONLY_REQUEST shl 4) or flags).toByte(),
            ((SERIALIZATION_RAW shl 4) or COMPRESSION_GZIP).toByte(),
            0,
        )
        val payload = gzip(if (count <= 0) EMPTY else pcm.copyOfRange(offset, offset + count))
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(int32(seq))
        out.write(int32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /** 解析服务端二进制帧；错误帧（type 15）先带错误码，再是负载长度。 */
    internal fun parseFrame(bytes: ByteArray): Frame {
        require(bytes.size >= 4) { "响应帧太短" }
        val headerWords = bytes[0].toInt() and 0x0F
        val messageType = (bytes[1].toInt() shr 4) and 0x0F
        val flags = bytes[1].toInt() and 0x0F
        val compression = bytes[2].toInt() and 0x0F
        var cursor = headerWords * 4

        if (messageType == Msg.SERVER_ERROR) {
            val code = int32At(bytes, cursor)
            cursor += 4
            val size = int32At(bytes, cursor)
            cursor += 4
            val payload = slice(bytes, cursor, size)
            return Frame(messageType, flags, null, unzip(payload, compression), code)
        }

        var sequence: Int? = null
        if (flags and Flag.POS_SEQUENCE != 0) {
            sequence = int32At(bytes, cursor)
            cursor += 4
        }
        val size = int32At(bytes, cursor)
        cursor += 4
        val payload = slice(bytes, cursor, size)
        return Frame(messageType, flags, sequence, unzip(payload, compression))
    }

    /** 只取识别文本：优先 `result.text`，为空才按顺序拼 `definite=true` 的分句。 */
    internal fun textOf(payload: ByteArray): String {
        val raw = payload.toString(Charsets.UTF_8).trim()
        if (raw.isEmpty()) return ""
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
        val result = root["result"] as? JsonObject ?: return ""
        val text = (result["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text.isNotBlank()) return text
        val utterances = result["utterances"] as? JsonArray ?: return ""
        return utterances.mapNotNull { it as? JsonObject }
            .filter { (it["definite"] as? JsonPrimitive)?.booleanOrNull == true }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("")
    }

    /** 把错误帧/握手失败收敛成一句可读原因：带 code（或 HTTP 状态）、服务端 message 与建议。 */
    internal fun failureText(code: Int?, body: String?): String {
        val detail = ApiErrors.extractMessage(body)
            ?: body?.trim()?.lineSequence()?.firstOrNull()?.take(200)?.ifBlank { null }
        val status = code?.takeIf { it in 100..599 }
        val api = ApiErrors.explain(status, body, "豆包语音识别")
        return buildString {
            append("语音识别失败")
            if (code != null) {
                append('（')
                if (status != null) append("HTTP ").append(code) else append("code ").append(code)
                append('）')
            }
            if (!detail.isNullOrBlank()) {
                append('：').append(detail)
                if (!detail.endsWith("。") && api.advice.isNotBlank()) append('。')
            } else if (api.advice.isNotBlank()) {
                append('：')
            }
            if (api.advice.isNotBlank()) append(api.advice)
        }
    }

    /** 从 wav 里取 PCM；不是 RIFF 就当已经是裸 PCM。 */
    internal fun pcmData(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return bytes
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.US_ASCII)
            val size = le32(bytes, cursor + 4)
            val start = cursor + 8
            if (id == "data") {
                val end = if (size <= 0 || size > bytes.size - start) bytes.size else start + size
                return bytes.copyOfRange(start, end)
            }
            if (size <= 0) break
            cursor = start + size + (size and 1)
        }
        return EMPTY
    }

    fun open(config: VoiceSettings): Session {
        require(config.apiKey.isNotBlank()) { "还没有填写豆包语音识别的 API Key。" }
        return Session(config)
    }

    /** 把一段完整录音（wav / 裸 PCM）当成一次流式请求发完，返回最终文本。 */
    fun transcribeFile(config: VoiceSettings, audio: File): String {
        if (!audio.isFile) throw error("语音识别失败：录音文件不存在。")
        val pcm = pcmData(audio.readBytes())
        if (pcm.isEmpty()) throw error("语音识别失败：录音里没有音频数据。")
        val session = open(config)
        try {
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(CHUNK_BYTES, pcm.size - offset)
                session.sendPcm(pcm, offset, count)
                offset += count
            }
            return session.finish()
        } finally {
            session.cancel()
        }
    }

    /** 一条识别连接：打开即发首包，录音期间持续推音频，收尾时发负序号包并等最终结果。 */
    class Session internal constructor(config: VoiceSettings) : WebSocketListener() {

        private val queue = LinkedBlockingQueue<Frame>()
        private val lock = Any()

        @Volatile private var socket: WebSocket? = null
        @Volatile private var closed = false
        private var sequence = 0
        private var failureQueued = false

        init {
            val request = Request.Builder()
                .url(config.baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
                .header("X-Api-Key", config.apiKey.trim())
                .header("X-Api-Resource-Id", config.resourceId.trim().ifBlank { DEFAULT_RESOURCE_ID })
                .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .build()
            socket = client.newWebSocket(request, this)
            send(buildFullRequest(firstRequestJson()))
        }

        /** 推一段 16k 单声道 PCM16；连接已收尾时忽略。 */
        fun sendPcm(pcm: ByteArray, offset: Int = 0, count: Int = pcm.size) {
            if (count <= 0) return
            synchronized(lock) {
                if (closed) return
                sequence += 1
                if (!send(audioPacket(sequence, pcm, offset, count))) fail("连接已断开。")
            }
        }

        /** 发最后一包（负序号）并等最终文本；超时或错误抛可读异常。 */
        fun finish(timeoutMs: Long = FINISH_TIMEOUT_MS): String {
            synchronized(lock) {
                if (closed) throw error("语音识别失败：连接已关闭。")
                closed = true
                send(audioPacket(sequence, EMPTY, 0, 0, last = true))
            }
            try {
                var text = ""
                var final = false
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val frame = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                    if (frame.isError) {
                        throw error(failureText(frame.errorCode, frame.payload.toString(Charsets.UTF_8)))
                    }
                    val latest = frame.text
                    if (latest.isNotBlank()) text = latest
                    if (frame.isLast) {
                        final = true
                        break
                    }
                }
                if (text.isBlank() && !final) {
                    throw error("语音识别超时：豆包没有在 ${timeoutMs / 1000} 秒内返回结果。")
                }
                return text
            } finally {
                close()
            }
        }

        fun cancel() {
            synchronized(lock) { closed = true }
            close()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching { parseFrame(bytes.toByteArray()) }
                .onSuccess { queue.put(it) }
                .onFailure { Log.w(TAG, "doubao asr: bad frame ${it.message}") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.w(TAG, "doubao asr: unexpected text frame")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: HttpResponse?) {
            val body = runCatching { response?.body?.string() }.getOrNull()
            val detail = body?.takeIf { it.isNotBlank() } ?: "连接已断开：${t.message ?: "网络异常"}"
            fail(detail, response?.code)
        }

        private fun send(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) == true

        /** 只保留第一条失败原因，后续断线回调不覆盖。 */
        private fun fail(message: String, code: Int? = null) {
            synchronized(lock) {
                if (failureQueued) return
                failureQueued = true
            }
            queue.put(Frame(Msg.SERVER_ERROR, 0, null, message.toByteArray(Charsets.UTF_8), code))
        }

        private fun close() {
            runCatching { socket?.close(1000, null) }
        }
    }

    // ---- 字节工具 ----

    private fun slice(bytes: ByteArray, offset: Int, size: Int): ByteArray {
        if (size <= 0) return EMPTY
        val end = minOf(offset + size, bytes.size)
        return if (offset >= end) EMPTY else bytes.copyOfRange(offset, end)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun unzip(payload: ByteArray, compression: Int): ByteArray {
        if (compression != COMPRESSION_GZIP || payload.size < 2) return payload
        if (payload[0] != 0x1f.toByte() || payload[1] != 0x8b.toByte()) return payload
        return runCatching { GZIPInputStream(payload.inputStream()).use { it.readBytes() } }
            .getOrDefault(payload)
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun int32At(bytes: ByteArray, offset: Int): Int {
        require(offset + 4 <= bytes.size) { "帧长度不足" }
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private const val TAG = "Xiaoman"
}
