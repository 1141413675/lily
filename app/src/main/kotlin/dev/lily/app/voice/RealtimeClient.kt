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
 * Real-time full-duplex voice over **豆包实时语音模型 3.0（Seeduplex）** 的 Realtime 协议。
 *
 * 协议细节集中在 [Seeduplex]，这里只管连接、音频与状态：
 *
 * - 麦克风 PCM16/16 kHz 按 20ms 一包持续上行（`input_audio_buffer.append`）；
 * - 3.0 没有服务端 VAD 事件，**由本地判停**：静默达到阈值就发 `input_audio_buffer.commit`
 *   强制模型判停，她就能在说话的同时先想好怎么答；
 * - 麦克风静音时不发音频帧，必须发 `input_audio_mute.commit` 保活，否则服务端判无输入而超时；
 * - 回复音频 `pcm_s16le`/24 kHz 直接进 [PcmPlayer]，不用重采样；
 * - 说话打断回复：停播 + `response.cancel`；
 * - `run_phone_task` 函数；任务在现有聊天会话里跑，结果回聊天页。
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

    /** 服务端回的 session.id；下一次建会话时带上它可以接续历史对话。 */
    @Volatile private var sessionId = ""

    /** 正在优雅收尾：已发 session.close，等 session.closed 或宽限超时再断链。 */
    @Volatile private var closing = false

    /**
     * 本地判停状态。
     *
     * 3.0 没有服务端 VAD 事件，模型不会自己知道「用户说完了」，所以客户端要在
     * 静音达到 [SILENCE_MS] 时发一次 `input_audio_buffer.commit` 强制判停。
     * 说话期间（[speaking]）不判停：她正在回话时用户插话，那属于抢话，不该被判成一轮结束。
     */
    @Volatile private var turnSpeaking = false
    @Volatile private var silenceMs = 0

    /** 最近一两条播报文本，只用于识别结果的自回声比对（最多两条）。 */
    private val recentSpoken = ArrayDeque<String>()

    /** 静音只停止上报麦克风，连接与当前回复都不受影响。 */
    fun setMuted(value: Boolean) {
        if (micMuted == value) return
        micMuted = value
        // 3.0 靠音频流保活：停发音频帧时必须显式发静音事件，否则服务端会判定无输入而超时。
        if (!closed) {
            send(if (value) Seeduplex.audioMute() else Seeduplex.audioUnmute())
        }
        if (value) {
            turnSpeaking = false
            silenceMs = 0
            if (!speaking) onLevel(0f)
        }
    }

    fun start(config: VoiceSettings) {
        if (closed) return
        onPhase(Phase.CONNECTING)
        val endpoint = buildUrl(config)
        // 只记录主机、模型与协议，不打印任何 Key。
        Log.i(TAG, "realtime connecting host=${endpoint.substringBefore('?').substringAfter("://")} model=${config.model} scheme=${endpoint.substringBefore("://")}")
        val request = Request.Builder()
            .url(endpoint)
            // 豆包 3.0 用 X-Api-Key，与豆包 TTS/ASR 同一个 Key，不需要另外申请。
            .header("X-Api-Key", config.apiKey.trim())
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
        // 3.0 要求先发 session.close、等 session.closed 回执再断链，直接断会被记成
        // ContextCanceled（55000001）。所以这里只发事件，断链交给 onClosed。
        closing = true
        runCatching { socket?.send(Seeduplex.sessionClose()) }
        // 服务端不回执也要能收尾：给一段宽限时间后强制关。
        scope.launch {
            delay(CLOSE_GRACE_MS)
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
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) {
                webSocket.close(1000, null)
                return
            }
            Log.i(TAG, "realtime websocket open code=${response.code}")
            // 3.0 用 session.create 建会话，不是 session.update。
            send(Seeduplex.sessionCreate(instructions(selfName), settingsVoice(), tools()))
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
            socket = null
            player.release()
            // 正常收尾不算失败。
            if (!closed && !closing) fail("实时语音连接已断开")
        }
    }

    /** 函数声明：只暴露「在手机上执行一个任务」这一件事。 */
    private fun tools(): JSONArray = JSONArray().put(JSONObject()
        .put("type", "function")
        .put("name", "run_phone_task")
        .put("description", "在用户的手机上执行一个任务（打开 App、点按、填写等）。任务在后台运行，结果稍后显示在聊天页。")
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("goal", JSONObject()
                    .put("type", "string")
                    .put("description", "要完成的事情，写成一句清楚的中文目标")))
            .put("required", JSONArray().put("goal"))))

    @Volatile private var voice = ""

    private fun settingsVoice(): String = voice.ifBlank { DEFAULT_VOICE }

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
            Seeduplex.Down.SESSION_CREATED -> {
                // 3.0 在这里回 session.id（即原来的 dialog.id）：带上它就能接续历史对话。
                sessionId = event.optJSONObject("session")?.optString("id").orEmpty()
                    .ifBlank { event.optString("id") }
                onPhase(Phase.LISTENING)
            }
            // 3.0 没有 speech_started 事件：抢话靠本地音量检测（见采集循环）。
            Seeduplex.Down.OUTPUT_AUDIO_STARTED -> {
                responseSequence++
                discardResponseAudio = false
                responseActive = true
                onPhase(Phase.THINKING)
            }
            Seeduplex.Down.OUTPUT_AUDIO_DELTA -> {
                val audio = Seeduplex.audioOf(event)
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
            Seeduplex.Down.OUTPUT_TEXT_DONE -> {
                val transcript = Seeduplex.textOf(event)
                if (transcript.isNotEmpty()) rememberSpoken(transcript)
                transcript.takeIf { it.isNotBlank() }?.let(onAssistantText)
            }
            Seeduplex.Down.TRANSCRIPTION_COMPLETED -> {
                val transcript = Seeduplex.textOf(event)
                if (transcript.isNotEmpty()) {
                    if (EchoGuard.isSelfEcho(transcript, recentSpoken.toList())) {
                        // 文本层兜底：外放回声被识别成用户的话时整轮丢弃，不进对话、不上屏。
                        Log.i(TAG, "call echo dropped: heard=<$transcript> spoken=<${recentSpoken.joinToString(" | ")}>")
                        itemIdOf(event)?.let { id ->
                            send(Seeduplex.itemDelete(id))
                        }
                        if (responseActive || speaking) interruptPlayback()
                    } else {
                        onUserText(transcript)
                    }
                }
            }
            Seeduplex.Down.TRANSCRIPTION_FAILED -> {
                // 识别失败要有反馈：把这一轮丢掉，界面回到「在听」，不静默卡住。
                val message = Seeduplex.errorOf(event).ifBlank { "没听清" }
                Log.w(TAG, "realtime transcription failed: $message")
                turnSpeaking = false
                silenceMs = 0
                if (responseActive || speaking) interruptPlayback() else onPhase(Phase.LISTENING)
            }
            Seeduplex.Down.OUTPUT_AUDIO_DONE -> {
                // 一轮音频合成结束：回复内容已经到齐，可以准备收尾。
                Log.i(TAG, "realtime output audio done")
            }
            Seeduplex.Down.FUNCTION_CALL_DONE ->
                Seeduplex.functionCalls(event).forEach { call ->
                    handleToolCall(call.callId, call.name, call.arguments)
                }
            Seeduplex.Down.RESPONSE_DONE -> finishResponse()
            Seeduplex.Down.RESPONSE_CANCELED -> {
                responseActive = false
                if (!speaking) onPhase(Phase.LISTENING)
            }
            Seeduplex.Down.SESSION_CLOSED -> {
                // 服务端确认收尾：这时断链才是官方要求的顺序，不会被记成 ContextCanceled。
                closing = false
                socket?.close(1000, "bye")
                socket = null
            }
            Seeduplex.Down.ERROR -> {
                val message = Seeduplex.errorOf(event)
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

    /** 取识别结果所属的上下文 id，用于自回声时删掉那一轮。 */
    private fun itemIdOf(event: JSONObject): String? {
        event.optString("item_id").takeIf { it.isNotBlank() }?.let { return it }
        val item = event.optJSONObject("item") ?: return null
        return item.optString("id").takeIf { it.isNotBlank() }
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
        // 3.0 的结果回传结构：items 数组 + role=tool + content 里的 input_text，
        // 且必须带上与下行一致的 call_id，否则模型配不上这次调用。
        send(Seeduplex.toolResult(callId, "已交给${selfName}在后台执行，进度和结果会显示在聊天页。"))
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
        send(Seeduplex.responseCancel())
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

    /**
     * 上行一包音频：更新音量、推进本地判停、发给模型。
     *
     * 3.0 没有服务端 VAD，模型靠我们发的 `input_audio_buffer.commit` 才知道「用户说完了」。
     */
    private fun pushAudio(chunk: ByteArray, amplitude: Int) {
        onLevel(amplitude / 32767f)
        trackTurn(amplitude)
        send(Seeduplex.audioAppend(chunk))
    }

    /**
     * 本地判停：3.0 没有服务端 VAD，得由客户端判断「用户说完了」。
     *
     * 说话中（音量超过 [SPEECH_PEAK]）就重新计时；连续静音达到 [SILENCE_MS] 就发一次
     * `input_audio_buffer.commit`，模型据此开始作答。说完后要再等一段静音才允许下一次判停，
     * 免得模型刚开始回话又被判成新的一轮。
     */
    private fun trackTurn(amplitude: Int) {
        if (amplitude >= SPEECH_PEAK) {
            turnSpeaking = true
            silenceMs = 0
            return
        }
        if (!turnSpeaking) return
        silenceMs += CHUNK_MS
        if (silenceMs < SILENCE_MS) return
        turnSpeaking = false
        silenceMs = 0
        Log.i(TAG, "realtime: local turn end, commit audio")
        send(Seeduplex.audioCommit())
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
                            // 静音时不上行音频帧，但已经发过 input_audio_mute.commit：
                            // 服务端知道这是主动静音，不会判定无输入而超时。
                            loudChunks = 0
                            if (!speaking) onLevel(0f)
                            continue
                        }
                        val chunk = buffer.copyOf(read)
                        val amplitude = peak(chunk)
                        if (speaking) {
                            loudChunks = if (amplitude >= BARGE_PEAK) loudChunks + 1 else 0
                            if (loudChunks < 2) {
                                // 播报期间仍照常上行：用户的声音要能传到模型那儿，
                                // 只是同时在做本地抢话检测。
                                pushAudio(chunk, amplitude)
                                continue
                            }
                            interruptPlayback()
                        }
                        pushAudio(chunk, amplitude)
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
        closing = false
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

        /**
         * 一包音频的时长。
         *
         * 豆包 3.0 要求按 20ms 分包（16k/int16 即 640 字节）并贴合实时节奏发送，
         * 发送过快过慢都会触发服务端错误。麦克风缓冲给到 100ms 一包，读满就发，
         * 实际节奏由 AudioRecord 的读取阻塞自然对齐。
         */
        const val CHUNK_MS = 100

        /** 判停用的音量阈值：低于它算静音。 */
        const val SPEECH_PEAK = 1_200

        /** 连续静音多久算「这一轮说完了」。 */
        const val SILENCE_MS = 700

        /** 兜底音色：官方 S2S 音色列表里的 Vivi 2.0。 */
        const val DEFAULT_VOICE = "zh_female_vv_uranus_bigtts"

        /** 发了 session.close 之后等服务端回执的宽限时间；超时就强制断链。 */
        const val CLOSE_GRACE_MS = 1_500L

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
