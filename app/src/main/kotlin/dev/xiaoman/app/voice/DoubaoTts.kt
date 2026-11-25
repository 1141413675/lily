package dev.xiaoman.app.voice

import android.util.Log
import dev.xiaoman.app.settings.VoiceSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包语音合成 2.0（WebSocket 双向流）。
 *
 * 一次连接里可以跑多个 session：我们按句合成，所以「一句 = 一个 session」，
 * 连接在多次朗读之间复用，空闲超过 [IDLE_MS] 才断开，避免每句都握手。
 * 服务端音频只在 AudioOnlyServer 帧里，是裸二进制；全量响应帧才是 JSON。
 */
internal object DoubaoTts {

    /** 官方事件号。 */
    object Event {
        const val START_CONNECTION = 1
        const val FINISH_CONNECTION = 2
        const val CONNECTION_STARTED = 50
        const val CONNECTION_FINISHED = 51
        const val CONNECTION_FAILED = 52
        const val START_SESSION = 100
        const val FINISH_SESSION = 102
        const val SESSION_STARTED = 150
        /** 官方：151 SessionCanceled、152 SessionFinished、153 SessionFailed。 */
        const val SESSION_CANCELED = 151
        const val SESSION_FINISHED = 152
        const val SESSION_FAILED = 153
        const val TASK_REQUEST = 200
        const val TTS_SENTENCE_START = 350
        const val TTS_SENTENCE_END = 351
        const val TTS_RESPONSE = 352
    }

    /** 官方消息类型。 */
    object Msg {
        const val CLIENT_FULL_REQUEST = 0x1
        const val SERVER_FULL_RESPONSE = 0x9
        const val AUDIO_ONLY_SERVER = 0xB
        const val SERVER_ERROR = 0xF
    }

    private const val PROTOCOL_VERSION = 1
    private const val HEADER_WORDS = 1
    private const val FLAG_WITH_EVENT = 0x04
    private const val SERIALIZATION_JSON = 1
    private const val COMPRESSION_GZIP = 1

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val SESSION_TIMEOUT_MS = 20_000L
    private const val IDLE_MS = 55_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var stream: ConnectStream? = null
    private var openedAt = 0L
    private var connectedConfig: VoiceSettings? = null

    /** 一帧解析结果；[payload] 已按压缩标志解压。 */
    data class Frame(
        val messageType: Int,
        val flags: Int,
        val event: Int?,
        val sessionId: String?,
        val payload: ByteArray,
        val errorCode: Int? = null,
    ) {
        val isAudio: Boolean get() = messageType == Msg.AUDIO_ONLY_SERVER
        val isError: Boolean get() = messageType == Msg.SERVER_ERROR || event == Event.SESSION_FAILED || event == Event.CONNECTION_FAILED
    }

    /**
     * 合成一句；抛异常表示这句失败（调用方会降级到硅基或跳过），不会静默返回半段音频。
     */
    @Synchronized
    fun speak(config: VoiceSettings, text: String, instruction: String?, timeoutMs: Long = SESSION_TIMEOUT_MS): ByteArray {
        require(text.isNotBlank()) { "朗读文本为空" }
        try {
            val (ws, frames) = ensureConnection(config)
            openedAt = System.currentTimeMillis()
            val sessionId = UUID.randomUUID().toString()
            send(ws, buildFrame(Event.START_SESSION, sessionId, sessionPayload(config, instruction)))

            awaitEvent(frames, Event.SESSION_STARTED, timeoutMs)
            send(ws, buildFrame(Event.TASK_REQUEST, sessionId, taskPayload(text)))
            send(ws, buildFrame(Event.FINISH_SESSION, sessionId, """{"event":${Event.FINISH_SESSION}}"""))

            val audio = ByteArrayOutputStream()
            while (true) {
                val frame = frames.take(timeoutMs)
                when {
                    frame.isError -> throw error(
                        "朗读失败（${frame.errorCode ?: frame.event}）：${frame.payload.toString(Charsets.UTF_8).message()}",
                    )
                    frame.isAudio && frame.event == Event.TTS_RESPONSE -> audio.write(frame.payload)
                    frame.event == Event.SESSION_FINISHED || frame.event == Event.SESSION_CANCELED -> break
                }
            }
            if (audio.size() == 0) throw error("朗读结果为空：服务端没有返回音频")
            return audio.toByteArray()
        } catch (e: Exception) {
            // 出错就丢掉这条连接，避免后续句子在半坏状态上继续跑。
            closeSocket()
            throw e
        }
    }

    /** 空闲太久的连接会被服务端回收，下次用之前直接换新；否则复用同一条连接跑新 session。 */
    private fun ensureConnection(config: VoiceSettings): Pair<WebSocket, ConnectStream> {
        val existing = socket
        val reusable = existing != null && stream != null && connectedConfig == config &&
            System.currentTimeMillis() - openedAt < IDLE_MS
        if (reusable) {
            // 清掉上一次会话残留的帧，避免把旧事件当成这次的。
            stream!!.drain()
            return existing!! to stream!!
        }
        closeSocket()
        val frames = ConnectStream()
        val request = Request.Builder()
            .url(config.baseUrl.trim().trimEnd('/'))
            .header("X-Api-Key", config.apiKey.trim())
            .header("X-Api-Resource-Id", config.resourceId.trim().ifBlank { VoiceSettings.DOUBAO_RESOURCE_ID })
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .header("X-Control-Require-Usage-Tokens-Return", "*")
            .build()
        val ws = client.newWebSocket(request, frames)
        frames.attach(ws)
        send(ws, buildFrame(Event.START_CONNECTION, null, """{"event":${Event.START_CONNECTION},"req_params":{}}"""))
        awaitEvent(frames, Event.CONNECTION_STARTED, CONNECT_TIMEOUT_MS)
        socket = ws
        stream = frames
        connectedConfig = config
        openedAt = System.currentTimeMillis()
        return ws to frames
    }

    private fun closeSocket() {
        val old = socket
        socket = null
        stream = null
        connectedConfig = null
        openedAt = 0
        old?.cancel()
    }

    private fun awaitEvent(frames: ConnectStream, event: Int, timeoutMs: Long) {
        while (true) {
            val frame = frames.take(timeoutMs)
            if (frame.isError) {
                throw error("朗读失败（${frame.errorCode ?: frame.event}）：${frame.payload.toString(Charsets.UTF_8).message()}")
            }
            if (frame.event == event) return
        }
    }

    private fun send(ws: WebSocket, bytes: ByteArray) {
        if (!ws.send(bytes.toByteString())) throw error("朗读失败：连接已断开")
    }

    /** 一个会话的请求体：音色与音频参数在 StartSession 里给一次，之后 TaskRequest 只带文本。 */
    internal fun sessionPayload(config: VoiceSettings, instruction: String?): String = buildString {
        append("{\"event\":").append(Event.START_SESSION)
        append(",\"req_params\":{")
        append("\"speaker\":").append(config.voice.trim().quoted())
        append(",\"audio_params\":{\"format\":\"mp3\",\"sample_rate\":24000")
        append(",\"speech_rate\":").append(doubaoSpeed(config.speed)).append("}")
        // 表演要求只走 context_texts：它是提示，不朗读、不计费。
        // 2.0 的 additions 字段要求是「JSON 字符串」，不是对象；直接塞对象会报 55000000。
        val line = instruction?.trim().orEmpty()
        if (line.isNotEmpty()) {
            val additions = "{\"context_texts\":[${line.quoted()}]}"
            append(",\"additions\":").append(additions.quoted())
        }
        append("}}")
    }

    internal fun taskPayload(text: String): String =
        """{"event":${Event.TASK_REQUEST},"req_params":{"text":${text.quoted()}}}"""

    /** 倍率转 2.0 的 [-50,100]，0 表示原速。 */
    internal fun doubaoSpeed(speed: Float): Int = ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)

    /** 组一个客户端全量请求帧：4 字节头 + 可选 event + 可选 session id + payload 长度 + gzip payload。 */
    internal fun buildFrame(event: Int, sessionId: String?, body: String?): ByteArray {
        val header = byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_WORDS).toByte(),
            ((Msg.CLIENT_FULL_REQUEST shl 4) or FLAG_WITH_EVENT).toByte(),
            ((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP).toByte(),
            0,
        )
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(int32(event))
        if (sessionId != null) {
            val bytes = sessionId.toByteArray(Charsets.UTF_8)
            out.write(int32(bytes.size))
            out.write(bytes)
        }
        val payload = gzip((body ?: "{}").toByteArray(Charsets.UTF_8))
        out.write(int32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * 解析服务端二进制帧。头部 4 字节；带 event 时先读 event，
     * 会话级事件再读 4 字节 session id 长度；随后是 payload 长度与内容。
     */
    internal fun parseFrame(bytes: ByteArray): Frame {
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
            val payload = bytes.copyOfRange(cursor, minOf(cursor + size, bytes.size))
            return Frame(messageType, flags, null, null, unzip(payload, compression), code)
        }

        var event: Int? = null
        if (flags and FLAG_WITH_EVENT != 0) {
            event = int32At(bytes, cursor)
            cursor += 4
        }

        var sessionId: String? = null
        if (event != null && event >= Event.START_SESSION) {
            val length = int32At(bytes, cursor)
            if (length in 1..128 && cursor + 4 + length <= bytes.size) {
                sessionId = String(bytes, cursor + 4, length, Charsets.UTF_8)
                cursor += 4 + length
            }
        }

        val size = int32At(bytes, cursor)
        cursor += 4
        val payload = if (size > 0 && cursor + size <= bytes.size) {
            bytes.copyOfRange(cursor, cursor + size)
        } else {
            ByteArray(0)
        }
        return Frame(messageType, flags, event, sessionId, unzip(payload, compression))
    }

    private fun unzip(payload: ByteArray, compression: Int): ByteArray {
        if (compression != COMPRESSION_GZIP || payload.size < 2) return payload
        // 音频段通常不再压缩；只有真正的 gzip 头才解，避免把裸 mp3 解坏。
        if (payload[0] != 0x1f.toByte() || payload[1] != 0x8b.toByte()) return payload
        return runCatching { GZIPInputStream(payload.inputStream()).use { it.readBytes() } }
            .getOrDefault(payload)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
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

    /** 一条连接上的接收队列。 */
    internal class ConnectStream : WebSocketListener() {
        private val queue = LinkedBlockingQueue<Frame>()
        private var ws: WebSocket? = null

        fun attach(socket: WebSocket) {
            ws = socket
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching { parseFrame(bytes.toByteArray()) }
                .onSuccess { queue.put(it) }
                .onFailure { Log.w("Xiaoman", "doubao: bad frame ${it.message}") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.w("Xiaoman", "doubao: unexpected text frame")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("Xiaoman", "doubao: socket failed ${t.message}")
            // 连接断开时塞一个中止帧，让等待方立刻失败并触发降级，而不是干等到超时。
            val reason = "连接已断开：${t.message ?: "网络异常"}".toByteArray(Charsets.UTF_8)
            queue.put(Frame(Msg.SERVER_ERROR, 0, null, null, reason, null))
        }

        fun take(timeoutMs: Long): Frame =
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw error("朗读超时：豆包没有在 ${timeoutMs / 1000} 秒内返回")

        fun drain() {
            queue.clear()
        }
    }
}
