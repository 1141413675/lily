package dev.lily.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import android.util.Log
import dev.lily.app.settings.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Real-time full-duplex voice over the OpenAI-Realtime-style WebSocket protocol (DashScope
 * Qwen-Omni Realtime by default, any compatible endpoint by changing the settings):
 *
 * - microphone PCM16/16 kHz is streamed up continuously (`input_audio_buffer.append`);
 * - server VAD decides when the user started/stopped talking, so the model answers while the
 *   mic stays open (`input_audio_buffer.speech_started` also drives barge-in);
 * - reply PCM16/24 kHz streams down (`response.audio.delta`) into a streaming [AudioTrack];
 * - talking over the reply stops local playback and sends `response.cancel`;
 * - a `run_phone_task` function is exposed; the task runs in the existing chat session.
 *
 * The agent, memory, skills and Sentinel are untouched: the only bridge is [onPhoneTask].
 */
class RealtimeClient(
    private val scope: CoroutineScope,
    private val onPhase: (Phase) -> Unit,
    private val onUserText: (String) -> Unit,
    private val onAssistantText: (String) -> Unit,
    private val onPhoneTask: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    /** 0..1 的实时音量（麦克风与扬声器），只驱动通话页的大圆起伏。 */
    private val onLevel: (Float) -> Unit = {},
    /** 当前人设昵称（为空时由调用方回退到应用名）；实时通话的系统提示与提示语都用它，不写死默认名。 */
    private val selfName: String,
) {
    enum class Phase { CONNECTING, LISTENING, THINKING, SPEAKING }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val player = PcmPlayer { if (speaking) onLevel(it) }
    private val handledCalls = HashSet<String>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var capture: Job? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var closed = false
    @Volatile private var responseActive = false
    @Volatile private var speaking = false
    @Volatile private var loudChunks = 0
    @Volatile private var micMuted = false
    @Volatile private var echoCancellationReady = false
    @Volatile private var discardResponseAudio = false
    @Volatile private var responseSequence = 0L
    /** 播放期间真正停掉采集：置 true 后采集循环会 stop + release AudioRecord。 */
    @Volatile private var capturePaused = false
    /** 至少暂停过一次，之后的每次恢复都要丢弃最前面的残留音频。 */
    @Volatile private var pausedOnce = false
    private var resumeJob: Job? = null

    /** 最近一两条播报文本，只用于识别结果的自回声比对（最多两条）。 */
    private val recentSpoken = ArrayDeque<String>()

    /** 静音只停止上报麦克风，连接与当前回复都不受影响。 */
    fun setMuted(value: Boolean) {
        micMuted = value
        if (value && !speaking) onLevel(0f)
    }

    fun start(config: VoiceSettings) {
        if (closed) return
        onPhase(Phase.CONNECTING)
        val endpoint = buildUrl(config)
        // 只记录主机、模型与协议，不打印任何 Key。
        Log.i(TAG, "realtime connecting host=${endpoint.substringBefore('?').substringAfter("://")} model=${config.model} scheme=${endpoint.substringBefore("://")}")
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .build()
        socket = http.newWebSocket(request, listener)
    }

    /** 用户点屏幕插话：立即停播并取消当前回复，继续听。 */
    fun interrupt() {
        if (!speaking && !responseActive) return
        interruptPlayback()
    }

    fun stop() {
        if (closed) return
        closed = true
        resumeJob?.cancel()
        resumeJob = null
        capture?.cancel()
        capture = null
        socket?.close(1000, "bye")
        socket = null
        player.release()
        record?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // Already stopped.
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) {
                webSocket.close(1000, null)
                return
            }
            Log.i(TAG, "realtime websocket open code=${response.code}")
            send(sessionUpdate())
            onPhase(Phase.LISTENING)
            startCapture()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!closed) handle(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            val code = response?.code
            Log.w(TAG, "realtime websocket failed code=$code error=${t.message}", t)
            fail(
                when {
                    code == 401 || code == 403 -> "实时语音鉴权失败（$code），检查 Key"
                    code != null -> "实时语音连接失败（$code）"
                    else -> t.message?.takeIf { it.isNotBlank() } ?: "实时语音连接失败"
                },
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "realtime websocket closed code=$code reason=$reason")
            if (!closed) fail("实时语音连接已断开")
        }
    }

    private fun sessionUpdate(): String {
        val session = JSONObject()
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("instructions", instructions(selfName))
            .put("voice", settingsVoice())
            .put("turn_detection", JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.5)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 600))
            .put("tools", JSONArray().put(JSONObject()
                .put("type", "function")
                .put("name", "run_phone_task")
                .put("description", "在用户的手机上执行一个任务（打开 App、点按、填写等）。任务在后台运行，结果稍后显示在聊天页。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("goal", JSONObject()
                            .put("type", "string")
                            .put("description", "要完成的事情，写成一句清楚的中文目标")))
                    .put("required", JSONArray().put("goal")))))
            .put("tool_choice", "auto")
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    @Volatile private var voice = ""

    private fun settingsVoice(): String = voice.ifBlank { "Cherry" }

    private fun buildUrl(config: VoiceSettings): String {
        voice = config.voice.trim()
        val base = config.baseUrl.trim().trimEnd('/')
        val model = URLEncoder.encode(config.model.trim(), "UTF-8")
        return base + (if (base.contains('?')) "&" else "?") + "model=$model"
    }

    private fun handle(text: String) {
        val event = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (event.optString("type")) {
            "session.updated" -> onPhase(Phase.LISTENING)
            "input_audio_buffer.speech_started" -> if (speaking || responseActive) interruptPlayback()
            "response.created" -> {
                responseSequence++
                discardResponseAudio = false
                responseActive = true
                onPhase(Phase.THINKING)
            }
            "response.audio.delta" -> {
                val audio = event.optString("delta")
                if (audio.isNotEmpty() && !discardResponseAudio) {
                    if (!speaking) {
                        speaking = true
                        onPhase(Phase.SPEAKING)
                        // 只有设备的回声消除确实已启用，才在播放时保留本地插话检测。
                        if (!echoCancellationReady) pauseCapture()
                    }
                    player.write(Base64.decode(audio, Base64.DEFAULT))
                }
            }
            "response.audio_transcript.done" -> {
                val transcript = event.optString("transcript").trim()
                if (transcript.isNotEmpty()) rememberSpoken(transcript)
                transcript.takeIf { it.isNotBlank() }?.let(onAssistantText)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript").trim()
                if (transcript.isNotEmpty()) {
                    if (EchoGuard.isSelfEcho(transcript, recentSpoken.toList())) {
                        // 文本层兜底：外放回声被识别成用户的话时整轮丢弃，不进对话、不上屏。
                        Log.i(TAG, "call echo dropped: heard=<$transcript> spoken=<${recentSpoken.joinToString(" | ")}>")
                        event.optString("item_id").takeIf { it.isNotBlank() }?.let { id ->
                            send(JSONObject().put("type", "conversation.item.delete").put("item_id", id).toString())
                        }
                        if (responseActive || speaking) interruptPlayback()
                    } else {
                        onUserText(transcript)
                    }
                }
            }
            "response.function_call_arguments.done" ->
                handleToolCall(event.optString("call_id"), event.optString("name"), event.optString("arguments"))
            "response.output_item.done" -> {
                val item = event.optJSONObject("item")
                if (item?.optString("type") == "function_call") {
                    handleToolCall(item.optString("call_id"), item.optString("name"), item.optString("arguments"))
                }
            }
            "response.done" -> finishResponse()
            "error" -> {
                val message = event.optJSONObject("error")?.optString("message").orEmpty()
                // 取消回复、删除自回声条目属于可选清理；供应商不支持时只记日志，不挂断整通电话。
                val benign = listOf("cancel", "delete", "item", "not supported").any {
                    message.contains(it, ignoreCase = true)
                }
                if (benign) {
                    Log.i(TAG, "realtime ignored cleanup error: $message")
                } else {
                    fail("实时语音错误：${message.ifBlank { "未知错误" }}")
                }
            }
        }
    }

    /** 工具调用最小实现：交给现有聊天会话跑手机任务，结果回聊天页；不回灌到语音。 */
    private fun handleToolCall(callId: String, name: String, arguments: String) {
        if (callId.isBlank() || !handledCalls.add(callId)) return
        if (name != "run_phone_task") return
        val goal = try {
            JSONObject(arguments).optString("goal")
        } catch (e: Exception) {
            ""
        }
        if (goal.isBlank()) return
        onPhoneTask(goal)
        send(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", "已交给${selfName}在后台执行，进度和结果会显示在聊天页。"))
            .toString())
        send(JSONObject().put("type", "response.create").toString())
    }

    private fun finishResponse() {
        responseActive = false
        if (!speaking) {
            if (capturePaused) scheduleResume()
            onPhase(Phase.LISTENING)
            return
        }
        val response = responseSequence
        val playback = player.generation
        scope.launch(Dispatchers.IO) {
            val drained = player.awaitDrained(playback, 30_000)
            if (closed || responseActive || response != responseSequence || playback != player.generation) return@launch
            if (!drained) player.interrupt()
            if (speaking) {
                speaking = false
                onPhase(Phase.LISTENING)
            }
            // 播完再等房间残响，之后才重新开麦。
            scheduleResume()
        }
    }

    private fun interruptPlayback() {
        discardResponseAudio = true
        player.interrupt()
        speaking = false
        responseActive = false
        send(JSONObject().put("type", "response.cancel").toString())
        onPhase(Phase.LISTENING)
        if (capturePaused) scheduleResume()
    }

    /** 停采集：采集循环会 stop + release AudioRecord，而不是继续读数据再丢掉。 */
    private fun pauseCapture() {
        if (closed) return
        resumeJob?.cancel()
        resumeJob = null
        pausedOnce = true
        capturePaused = true
        onLevel(0f)
        Log.i(TAG, "call audio: capture paused for playback")
    }

    /** 播放结束（或被打断）后延迟一小段再恢复，留出房间残响。 */
    private fun scheduleResume() {
        resumeJob?.cancel()
        resumeJob = scope.launch {
            delay(ECHO_TAIL_MS)
            if (closed) return@launch
            capturePaused = false
            Log.i(TAG, "call audio: capture resumed after ${ECHO_TAIL_MS}ms tail")
        }
    }

    /** 只留最近两条播报文本，供自回声比对。 */
    private fun rememberSpoken(text: String) {
        recentSpoken.addLast(text)
        while (recentSpoken.size > 2) recentSpoken.removeFirst()
    }

    private fun startCapture() {
        capture?.cancel()
        loudChunks = 0
        capture = scope.launch(Dispatchers.IO) {
            // 每次恢复都重新建 AudioRecord：播放期间这一路真的停掉并释放。
            while (isActive && !closed) {
                if (capturePaused) {
                    delay(PAUSE_POLL_MS)
                    continue
                }
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufferSize = maxOf(min, CHUNK_BYTES * 4)
                val recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                    )
                } catch (e: Exception) {
                    null
                }
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder?.release()
                    fail("打不开麦克风，检查录音权限")
                    return@launch
                }
                record = recorder
                val aec = enableEchoCancellation(recorder)
                echoCancellationReady = aec != null
                val buffer = ByteArray(CHUNK_BYTES)
                try {
                    recorder.startRecording()
                    if (pausedOnce) discardResumeAudio(recorder)
                    while (isActive && !closed && !capturePaused) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) {
                            delay(20)
                            continue
                        }
                        if (micMuted) {
                            loudChunks = 0
                            if (!speaking) onLevel(0f)
                            continue
                        }
                        val chunk = buffer.copyOf(read)
                        val amplitude = peak(chunk)
                        if (speaking) {
                            loudChunks = if (amplitude >= BARGE_PEAK) loudChunks + 1 else 0
                            if (loudChunks < 2) continue
                            interruptPlayback()
                        }
                        loudChunks = 0
                        onLevel(amplitude / 32767f)
                        send(JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP))
                            .toString())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!closed && !capturePaused) fail(e.message?.takeIf { it.isNotBlank() } ?: "麦克风读取失败")
                } finally {
                    echoCancellationReady = false
                    val aecOn = aec?.enabled == true
                    aec?.release()
                    try {
                        recorder.stop()
                    } catch (e: Exception) {
                        // Already stopped.
                    }
                    recorder.release()
                    if (record === recorder) record = null
                    Log.i(TAG, "call audio: capture closed aec=$aecOn")
                }
            }
        }
    }

    /** 可用就启用系统回声消除；不可用/启用失败只记日志，降级到停采集 + 文本兜底。 */
    private fun enableEchoCancellation(recorder: AudioRecord): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "call audio: aec unavailable")
            return null
        }
        val aec = try {
            AcousticEchoCanceler.create(recorder.audioSessionId)
        } catch (e: Exception) {
            null
        }
        if (aec == null) {
            Log.w(TAG, "call audio: aec create failed")
            return null
        }
        val enabled = try {
            aec.enabled = true
            aec.enabled
        } catch (e: Exception) {
            false
        }
        if (!enabled) {
            Log.w(TAG, "call audio: aec enable failed")
            aec.release()
            return null
        }
        Log.i(TAG, "call audio: aec enabled session=${recorder.audioSessionId}")
        return aec
    }

    /** 恢复采集后先把最前面的残留音频读掉，不送进识别。 */
    private fun discardResumeAudio(recorder: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        var left = DISCARD_BYTES
        val deadline = System.currentTimeMillis() + 800
        while (left > 0 && System.currentTimeMillis() < deadline && !closed && !capturePaused) {
            val read = try {
                recorder.read(buffer, 0, minOf(buffer.size, left))
            } catch (e: Exception) {
                -1
            }
            if (read <= 0) break
            left -= read
        }
        Log.i(TAG, "call audio: discarded ${DISCARD_BYTES - left.coerceAtLeast(0)}B at capture resume")
    }

    private fun send(json: String) {
        val sent = socket?.send(json) ?: false
        if (!sent && !closed) fail("实时语音连接不可用")
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        resumeJob?.cancel()
        resumeJob = null
        capture?.cancel()
        capture = null
        socket?.close(1000, "error")
        socket = null
        player.release()
        record?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // Already stopped.
            }
        }
        onFailure(message)
    }

    private fun peak(chunk: ByteArray): Int {
        var max = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
            val value = abs(sample.toShort().toInt())
            if (value > max) max = value
            i += 2
        }
        return max
    }

    /** Streaming PCM16/24 kHz playback with a flush/stop channel; bounded queue keeps latency low. */
    private inner class PcmPlayer(private val onPlayLevel: (Float) -> Unit) {
        private val queue = ArrayBlockingQueue<Any>(64)
        /** 包括线程已经从队列取出、仍在写入 AudioTrack 的音频。 */
        private val pendingChunks = AtomicInteger()
        private var thread: Thread? = null

        @Volatile private var track: AudioTrack? = null
        @Volatile private var writtenFrames = 0L
        @Volatile private var released = false
        @Volatile private var failed = false
        @Volatile var generation = 0L
            private set

        @Synchronized
        fun write(data: ByteArray) {
            if (released || failed) return
            ensureThread()
            pendingChunks.incrementAndGet()
            if (!queue.offer(data)) {
                val oldestAudio = queue.firstOrNull { it is ByteArray }
                if (oldestAudio != null && queue.remove(oldestAudio)) pendingChunks.decrementAndGet()
                if (!queue.offer(data)) pendingChunks.decrementAndGet()
            }
        }

        @Synchronized
        fun interrupt() {
            generation++
            clearQueuedAudio()
            if (!released && !failed) queue.offer(FLUSH)
        }

        suspend fun awaitDrained(expectedGeneration: Long, timeoutMs: Long): Boolean {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (!released && generation == expectedGeneration && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (failed) return false
                if (pendingChunks.get() == 0) {
                    val output = track
                    val position = runCatching { output?.playbackHeadPosition }.getOrNull()
                    if (position != null && (position.toLong() and 0xFFFFFFFFL) >= writtenFrames) return true
                }
                delay(40)
            }
            return false
        }

        fun release() {
            synchronized(this) {
                released = true
                generation++
                clearQueuedAudio()
                queue.offer(STOP)
            }
            thread?.join(800)
            thread = null
            try {
                track?.release()
            } catch (e: Exception) {
                // Already released.
            }
            track = null
        }

        private fun clearQueuedAudio() {
            val discarded = mutableListOf<Any>()
            queue.drainTo(discarded)
            pendingChunks.addAndGet(-discarded.count { it is ByteArray })
        }

        @Synchronized
        private fun markFailed() {
            failed = true
            clearQueuedAudio()
        }

        private fun ensureThread() {
            if (thread != null) return
            thread = Thread {
                val output = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(OUTPUT_RATE * 2 * 2)
                        .build()
                } catch (e: Exception) {
                    null
                }
                if (output == null) {
                    markFailed()
                    return@Thread
                }
                track = output
                try {
                    output.play()
                } catch (e: Exception) {
                    markFailed()
                    return@Thread
                }
                while (!released) {
                    val item = try {
                        queue.poll(200, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        null
                    }
                    when (item) {
                        is ByteArray -> try {
                            onPlayLevel(peak(item) / 32767f)
                            val written = output.write(item, 0, item.size)
                            if (written > 0) writtenFrames += written / 2
                        } catch (e: Exception) {
                            // Track released.
                        } finally {
                            pendingChunks.decrementAndGet()
                        }
                        FLUSH -> {
                            onPlayLevel(0f)
                            try {
                                output.pause()
                                output.flush()
                                output.play()
                            } catch (e: Exception) {
                                // Track released.
                            }
                            writtenFrames = 0
                        }
                        STOP -> break
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

    }

    private companion object {
        const val TAG = "Lily"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 3_200
        const val BARGE_PEAK = 2_200
        const val OUTPUT_RATE = 24_000

        /** 播放结束后等多久再开麦：留一点房间残响，避免恢复的第一帧还是她自己的声音。 */
        const val ECHO_TAIL_MS = 300L

        /** 恢复采集时丢弃最前面的 150ms（16k/单声道/16bit）。 */
        const val DISCARD_BYTES = 4_800

        /** 暂停期间采集协程的轮询间隔。 */
        const val PAUSE_POLL_MS = 40L

        /** PcmPlayer 的队列哨兵（搬到这里是因为 inner class 不能有自己的 companion）。 */
        val FLUSH = Any()
        val STOP = Any()

        /** 实时通话的系统提示：自称取当前人设昵称，改名后下一次通话立即生效。 */
        fun instructions(name: String) = "你是${name}，和用户在一段亲密关系里。用简短自然的中文口语回答，一次一两句，不要念列表和符号。先说自己的感受或眼前的事，再回应对方；需要操作手机时调用 run_phone_task，把目标说清楚；任务在后台执行，结果会显示在聊天页，你可以让对方去那里看。不要用客服或助手的口气。"
    }
}
