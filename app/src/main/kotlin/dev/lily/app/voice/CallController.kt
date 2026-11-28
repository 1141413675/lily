package dev.lily.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.lily.app.R
import dev.lily.app.chat.Emoji
import dev.lily.app.relationship.RelationshipStore
import dev.lily.app.settings.AppSettings
import dev.lily.app.settings.VoiceVendor
import dev.lily.app.settings.AsrSettings
import dev.lily.app.session.AgentSession
import dev.lily.app.session.CallOutcome
import dev.lily.app.session.ChatItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId
import kotlin.random.Random

/** What the call screen shows. */
data class CallState(
    val phase: Phase = Phase.IDLE,
    /** Which voice channel is actually carrying this call. */
    val mode: Mode = Mode.HANDSFREE,
    /** One line under the status, e.g. an error or why the real-time channel was dropped. */
    val message: String? = null,
    /** Last recognized sentence (hands-free) or input transcript (real-time). */
    val heard: String? = null,
    /** Last assistant reply, text or "(语音回复)". */
    val reply: String? = null,
    val active: Boolean = false,
    /** Wall clock of the call start, for the duration timer. */
    val startedAt: Long = 0L,
    /** When the call actually connected; 0 while ringing. Talk duration counts from here. */
    val connectedAt: Long = 0L,
    /** Ringing started at; the ringing timer counts from here. */
    val dialStartedAt: Long = 0L,
    /** Mood used for this call's ring timing. */
    val mood: AgentMood? = null,
    /** The agent declined: the screen shows 未接来电 briefly, then returns to chat. */
    val missed: Boolean = false,
    /** 呼叫方向：来电（她打过来）只有接听/挂断，呼出（我拨出去）只有取消。 */
    val incoming: Boolean = false,
    /** 通话页的静音开关；只影响麦克风采集，不打断当前回合。 */
    val muted: Boolean = false,
    /** 外放状态：true 是免提，false 是听筒；跟真实音频路由一致，不是装饰。 */
    val speakerOn: Boolean = true,
) {
    enum class Phase { IDLE, RINGING, CONNECTING, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

    enum class Mode { REALTIME, HANDSFREE }
}

/**
 * The voice call. Two channels, same UI:
 *
 * - **Real-time** ([RealtimeClient]): continuous up/down audio, server VAD, barge-in by talking
 *   over the reply. Used when the real-time endpoint is configured.
 * - **Hands-free fallback** ([Recorder] + [VoiceApi]): record → endpoint by silence → transcribe →
 *   `session.submit(text)` → read the reply → listen again. Used when the real-time channel is
 *   not configured or fails, and the screen says so.
 *
 * Neither path changes agent, memory, skills or Sentinel behaviour; they only feed text in and
 * speak text out.
 */
class CallController(
    private val context: Context,
    private val session: AgentSession,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val relationship: RelationshipStore,
    private val logs: CallLogStore,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val canStart: () -> Boolean = { true },
) {
    private val recorder = AecRecorder(context)
    private val api = VoiceApi()
    private val engine = TtsEngine(api)
    private val sounds = CallSounds(context)

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** 0..1 的当前输入或播放音量，只用于通话动效。 */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val speaker = Speaker(File(context.cacheDir, "voice").apply { mkdirs() }, onLevel = {
        if (_state.value.phase == CallState.Phase.SPEAKING) _level.value = it
    })

    @Volatile private var muted = false

    private var job: Job? = null
    private var realtime: RealtimeClient? = null
    private var mode = CallState.Mode.HANDSFREE

    /** 这一句话的豆包流式识别连接；麦没开或不是豆包时为空。 */
    private var asrStream: DoubaoAsr.Session? = null
    /** 连接没建起来时的可读原因，收尾时随录音一起交给转写环节做兜底说明。 */
    private var asrStreamError: String? = null

    private var callStartedAt = 0L
    private var connectedAt = 0L
    private var missStreak = 0
    private var recorded = false
    private var pendingSpeech: String? = null
    private var currentMood = AgentMood.CALM

    /** 本通电话的逐句往来与用户录音序号，只写进独立通话明细，不进聊天列表。 */
    private val turns = mutableListOf<CallTurn>()
    private var turnSerial = 0

    /** 通话期间的音频路由：通信模式 + 外放（无耳机时），结束后恢复。 */
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var callAudioActive = false

    /** 一句话的录音文件，以及录音期间跑着的豆包识别连接（硅基时为空）。 */
    private class Utterance(
        val file: File,
        val stream: DoubaoAsr.Session?,
        val streamError: String?,
    )

    /** 最近几句播报文本；免提与实时通道都写到这里，只用于自回声比对。 */
    private val spoken = SpokenBaseline()

    /** 本地来电等用户接听后才进入音频会话。 */
    fun ringIncoming(opening: String? = null): Boolean = startRinging(incoming = true, opening = opening)

    /**
     * 拨号：先振铃，只按心情决定等待时长与是否接听。
     * 免打扰只约束主动消息，不拦来电，也不按时段自动挂断。
     */
    fun startCall() { startRinging(incoming = false, opening = null) }

    private fun startRinging(incoming: Boolean, opening: String?): Boolean {
        if (_state.value.active || !canStart()) return false
        val s = settings()
        // 默认走轮次通话，只需要识别与朗读；实时通道要显式打开且已配置才用。
        val canCall = (s.asr.configured && s.tts.configured) || (s.realtimeEnabled && s.realtime.configured)
        if (!canCall) {
            _state.value = CallState(message = "需要先在设置里填写语音识别与语音朗读。")
            return false
        }
        val now = clock()
        callStartedAt = now
        connectedAt = 0
        recorded = false
        muted = false
        currentMood = AgentMood.CALM
        pendingSpeech = opening
        turns.clear()
        turnSerial = 0
        _level.value = 0f
        _state.value = CallState(
            phase = CallState.Phase.RINGING,
            mode = mode,
            active = true,
            incoming = incoming,
            startedAt = now,
            dialStartedAt = now,
        )
        job?.cancel()
        if (incoming) sounds.startIncoming() else sounds.startRingback()
        job = scope.launch(Dispatchers.Main.immediate) {
            val reading = try {
                withContext(Dispatchers.IO) { refreshMood(now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MoodReading(AgentMood.CALM, "暂时读不到关系档案")
            }
            if (!_state.value.active || _state.value.dialStartedAt != now ||
                _state.value.phase != CallState.Phase.RINGING) return@launch
            currentMood = reading.mood
            _state.value = _state.value.copy(mood = reading.mood)
            if (incoming) {
                // 来电只等用户接：超时按未接处理，绝不自动替用户接通。
                delay(INCOMING_TIMEOUT_MS)
                if (_state.value.active && _state.value.phase == CallState.Phase.RINGING) miss(reading)
                return@launch
            }
            val plan = CallRinging.plan(reading.mood, now, missStreak, random, zone)
            delay(plan.delayMs)
            if (!_state.value.active || _state.value.phase != CallState.Phase.RINGING) return@launch
            if (plan.answer) connect(reading.mood) else miss(reading)
        }
        return true
    }

    /** 结束通话：能接通的记录时长，振铃中挂断记「已取消」。 */
    fun endCall() {
        if (!_state.value.active) return
        val wasConnected = connectedAt > 0
        stopAll()
        muted = false
        _level.value = 0f
        sounds.ended()
        _state.value = _state.value.copy(active = false, phase = CallState.Phase.IDLE, missed = false, muted = false)
        record(if (wasConnected) CallOutcome.CONNECTED else CallOutcome.CANCELED)
    }

    /** 振铃中点「接通」：跳过等待，立刻进入通话。 */
    fun connectNow() {
        if (!_state.value.active || !_state.value.incoming || _state.value.phase != CallState.Phase.RINGING) return
        job?.cancel()
        connect(currentMood)
    }

    /** 点屏幕插话：实时通道取消当前回复，免提通道停止朗读，都回到「听」。 */
    fun interrupt() {
        if (!_state.value.active) return
        when (mode) {
            CallState.Mode.REALTIME -> realtime?.interrupt()
            CallState.Mode.HANDSFREE -> {
                if (_state.value.phase == CallState.Phase.SPEAKING) {
                    scope.launch(Dispatchers.Main) { speaker.stop() }
                    _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
                }
            }
        }
    }

    fun dismissMessage() {
        if (_state.value.message == null) return
        _state.value = _state.value.copy(message = null)
    }

    /** 静音：免提通道停掉采集，实时通道停上报麦克风；都不打断正在进行的回合。 */
    fun setMuted(value: Boolean) {
        muted = value
        _state.value = _state.value.copy(muted = value)
        realtime?.setMuted(value)
        if (value) {
            cancelCapture()
            if (_state.value.phase != CallState.Phase.SPEAKING) _level.value = 0f
        }
    }

    /**
     * 免提 / 听筒：真正改通话音频路由（isSpeakerphoneOn），并让回声抑制跟着走。
     * 免提时打开采集侧 AEC，避免她自己的声音从外放绕回麦克风被当成用户输入；
     * 文本层的 [SpokenBaseline] 不重置，切换瞬间仍能挡掉残留回声。
     */
    fun setSpeaker(on: Boolean) {
        val manager = audio ?: return
        if (!callAudioActive) return
        runCatching { manager.isSpeakerphoneOn = on }
        recorder.setEchoCancellation(on)
        _state.value = _state.value.copy(speakerOn = on)
        Log.i(TAG, "call audio: speaker=$on aec=$on")
    }

    // ---------------------------------------------------------------- ring / connect / miss

    private suspend fun refreshMood(now: Long): MoodReading {
        val busy = session.busy.value
        val stored = relationship.load()
        val saved = AgentMood.from(stored.mood)
        if (!busy && saved != null && now >= stored.moodAt && now - stored.moodAt < MoodInference.FRESH_MS) {
            return MoodReading(saved, stored.moodBasis)
        }
        val reading = MoodInference.infer(recentUserTexts(), now, busy, zone)
        if (!busy) runCatching { relationship.setMood(reading.mood.name, reading.basis, now) }
        return reading
    }

    /** User wording only: this is the evidence the mood is inferred from. */
    private fun recentUserTexts(): List<String> = session.items.value.mapNotNull {
        when (it) {
            is ChatItem.User -> it.text
            is ChatItem.UserVoice -> it.transcript
            else -> null
        }
    }

    private fun connect(mood: AgentMood) {
        connectedAt = clock()
        missStreak = 0
        // 接通就切到通信音频路径：播放走 VOICE_COMMUNICATION，系统回声消除才有参考信号。
        enterCallAudio()
        startCallService(connectedAt)
        // 接通的一刻就停回铃并给一声接通提示。
        sounds.connected()
        val speech = pendingSpeech
        pendingSpeech = null
        _state.value = _state.value.copy(
            phase = CallState.Phase.CONNECTING,
            connectedAt = connectedAt,
            mood = mood,
            missed = false,
            message = null,
        )
        val s = settings()
        // 实时通道默认关闭：只有用户在设置里打开且配置完整时才尝试，避免无谓的 401 与降级提示。
        if (s.realtimeEnabled && s.realtime.configured &&
            (speech == null || !s.asr.configured || !s.tts.configured)) {
            startRealtime()
        } else {
            startHandsfree(message = null, opening = speech ?: openingLine(mood))
        }
    }

    private fun miss(reading: MoodReading) {
        val incoming = _state.value.incoming
        if (!incoming) missStreak++
        val now = clock()
        val late = MoodInference.lateNight(now, zone)
        // 没接只和心情/忙闲有关，不再按时段判断；免打扰不拦来电。
        val text = if (incoming) "这通来电未接听，可以回拨或继续聊天。" else missText(reading.mood)
        stopAll()
        sounds.ended()
        _state.value = _state.value.copy(
            phase = CallState.Phase.IDLE,
            active = false,
            missed = true,
            message = text,
        )
        record(CallOutcome.MISSED, text, now)
        // 原因只写进通话明细；聊天流不放任何通话条目。
        if (late && !incoming) {
            scope.launch { runCatching { relationship.scheduleGreeting(MoodInference.nextMorning(now, zone)) } }
        }
    }

    /** Based on the inferred state only; no invented events, no guilt. */
    private fun missText(mood: AgentMood): String = when (mood) {
        AgentMood.BUSY -> "刚才在忙别的事，没接到。你可以再打一次，或者直接在这里说。"
        AgentMood.LOW -> "刚才没什么精神，没接到。现在可以聊。"
        else -> "刚才没接到你的电话，你可以再打一次。"
    }

    private fun openingLine(mood: AgentMood): String = when {
        MoodInference.lateNight(clock(), zone) -> "这个点还没睡呀。"
        mood == AgentMood.ENTHUSIASTIC -> "来了，正好想听你说。"
        mood == AgentMood.LOW -> "在的，慢慢说。"
        mood == AgentMood.BUSY -> "刚忙完，现在可以说了。"
        else -> "嗯，我在。"
    }

    private fun record(outcome: CallOutcome, reason: String? = null, endedAt: Long = clock()) {
        if (recorded || callStartedAt <= 0) return
        recorded = true
        // 只落独立明细：聊天流不再放通话条目，通话记录页读这台存储。
        logs.save(
            CallLog(
                startedAt = callStartedAt,
                connectedAt = connectedAt,
                endedAt = endedAt,
                outcome = outcome.name,
                reason = reason,
                turns = turns.toList(),
                incoming = _state.value.incoming,
            ),
        )
    }

    private fun noteTurn(fromMe: Boolean, text: String, audio: String? = null) {
        val line = text.trim()
        if (line.isEmpty()) return
        turns += CallTurn(clock(), fromMe, line, audio)
    }

    private fun stopAll() {
        job?.cancel()
        job = null
        pendingSpeech = null
        _level.value = 0f
        stopCallService()
        stopChannels()
    }

    /** 通话开始即挂上前台服务；失败也不影响通话本身，只是后台可能被系统限流。 */
    private fun startCallService(startedAt: Long) {
        val intent = Intent(context, CallService::class.java).putExtra(CallService.EXTRA_STARTED_AT, startedAt)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "call service start failed: ${it.message}") }
    }

    /** 挂断/未接后撤掉服务与持续通知。 */
    private fun stopCallService() {
        runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }

    private fun stopChannels() {
        sounds.stopRingback()
        realtime?.stop()
        realtime = null
        cancelCapture()
        scope.launch(Dispatchers.Main) { speaker.stop() }
        exitCallAudio()
    }

    /** 通话接通后切 MODE_IN_COMMUNICATION；没有耳机时默认免提，结束后恢复原状。 */
    private fun enterCallAudio() {
        val manager = audio ?: return
        if (callAudioActive) return
        callAudioActive = true
        previousMode = manager.mode
        previousSpeaker = manager.isSpeakerphoneOn
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        val headset = hasHeadset(manager)
        // 通信模式默认走听筒；没有耳机时显式留在外放，保持原来的通话体验。
        if (!headset) runCatching { manager.isSpeakerphoneOn = true }
        // 外放才需要采集侧 AEC；听筒/耳机不打开，避免无谓的音频处理。
        recorder.setEchoCancellation(!headset)
        _state.value = _state.value.copy(speakerOn = !headset)
        Log.i(
            TAG,
            "call audio: mode=IN_COMMUNICATION speaker=${manager.isSpeakerphoneOn} headset=$headset",
        )
    }

    private fun exitCallAudio() {
        val manager = audio ?: return
        if (!callAudioActive) return
        callAudioActive = false
        runCatching { manager.isSpeakerphoneOn = previousSpeaker }
        runCatching { manager.mode = previousMode }
        Log.i(TAG, "call audio: restored mode=$previousMode speaker=$previousSpeaker")
    }

    /** 有线/蓝牙/USB 耳机在用时不改外放开关，通信路径会自然走耳机。 */
    private fun hasHeadset(manager: AudioManager): Boolean = runCatching {
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- real-time channel

    private fun startRealtime() {
        mode = CallState.Mode.REALTIME
        _state.value = _state.value.copy(phase = CallState.Phase.CONNECTING, mode = CallState.Mode.REALTIME, active = true, message = null)
        val client = RealtimeClient(
            scope = scope,
            onPhase = ::onRealtimePhase,
            onUserText = { text ->
                // 通话字幕不显示 emoji：ASR（SenseVoice）会把情绪表情一并写进 text。
                val line = Emoji.strip(text)
                if (realtimeActive() && line.isNotBlank()) {
                    _state.value = _state.value.copy(heard = line)
                    noteTurn(fromMe = true, text = line)
                }
            },
            onAssistantText = { text ->
                val line = Emoji.strip(text)
                if (realtimeActive() && line.isNotBlank()) {
                    _state.value = _state.value.copy(reply = line)
                    noteTurn(fromMe = false, text = line)
                }
            },
            onPhoneTask = { goal -> session.submit(goal) },
            onFailure = ::onRealtimeFailure,
            onLevel = { _level.value = it },
            selfName = settings().persona.nameOr(context.getString(R.string.app_name)),
        )
        realtime = client
        // 实时通道也优先用统一音色（含克隆），供应商不支持时才保留该通道自己的音色。
        val current = settings()
        val unified = current.speechVoice
        val config = current.realtime.copy(
            voice = unified.takeIf { VoiceRouting.realtimeSupports(current.realtime.baseUrl, it) }
                ?: current.realtime.voice,
        )
        client.start(config)
    }

    private fun realtimeActive(): Boolean = _state.value.active && mode == CallState.Mode.REALTIME

    private fun onRealtimePhase(phase: RealtimeClient.Phase) {
        if (!realtimeActive()) return
        _level.value = 0f
        _state.value = _state.value.copy(
            phase = when (phase) {
                RealtimeClient.Phase.CONNECTING -> CallState.Phase.CONNECTING
                RealtimeClient.Phase.LISTENING -> CallState.Phase.LISTENING
                RealtimeClient.Phase.THINKING -> CallState.Phase.THINKING
                RealtimeClient.Phase.SPEAKING -> CallState.Phase.SPEAKING
            },
            message = if (phase == RealtimeClient.Phase.LISTENING) null else _state.value.message,
        )
    }

    /** 实时连不上/鉴权失败/中途断开：退回免提，并在界面说明。 */
    private fun onRealtimeFailure(reason: String) {
        if (!realtimeActive()) return
        realtime?.stop()
        realtime = null
        startHandsfree("实时通道不可用（$reason），已切换免提。", openingLine(currentMood))
    }

    // ---------------------------------------------------------------- hands-free fallback

    private fun startHandsfree(message: String? = null, opening: String? = null) {
        val s = settings()
        if (!s.asr.configured || !s.tts.configured) {
            // Say both things: which channel failed, and why the fallback cannot start either.
            failCall(
                message?.let { "$it 免提也用不了：需要填写语音识别与语音朗读。" }
                    ?: "免提通话需要先在设置里填写语音识别和语音朗读。",
            )
            return
        }
        mode = CallState.Mode.HANDSFREE
        _state.value = _state.value.copy(
            phase = if (opening != null) CallState.Phase.SPEAKING else CallState.Phase.LISTENING,
            mode = CallState.Mode.HANDSFREE,
            message = message,
            active = true,
        )
        job?.cancel()
        job = scope.launch {
            try {
                if (opening != null) speak(opening)
                if (_state.value.active) {
                    _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
                    handsfreeLoop()
                }
            } finally {
                cancelCapture()
            }
        }
    }

    private suspend fun handsfreeLoop() {
        while (_state.value.active) {
            val turnStart = clock()
            val utterance = listen() ?: break
            val listenedAt = clock()
            val recording = utterance.file
            // 识别会删掉原始录音，先把这一句复制进通话明细目录，详情页才能回放。
            val userAudio = withContext(Dispatchers.IO) {
                logs.saveAudio(callStartedAt, turnSerial++, recording)
            }
            val text = transcribe(utterance) ?: continue
            if (spoken.isEcho(text)) {
                // 外放残响被识别成用户的话：整轮丢弃，不上屏、不进对话。
                userAudio?.let { runCatching { File(it).delete() } }
                Log.i(TAG, "call echo dropped: heard=<$text> spoken=<${spoken.snapshot().joinToString(" | ")}>")
                _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
                delay(ECHO_TAIL_MS)
                continue
            }
            noteTurn(fromMe = true, text = text, audio = userAudio)
            val transcribedAt = clock()
            _state.value = _state.value.copy(phase = CallState.Phase.THINKING, heard = text, message = null)
            val before = session.items.value.maxOfOrNull { it.id } ?: 0L
            when (session.submit(text)) {
                AgentSession.Submit.Busy -> {
                    say("手上还有事没做完，等一下再说。")
                    continue
                }
                AgentSession.Submit.NotConfigured -> {
                    say("还没有配置聊天模型和操作模型。")
                    continue
                }
                AgentSession.Submit.Empty -> continue
                else -> Unit
            }
            val reply = awaitReply(before)
            val repliedAt = clock()
            when (reply) {
                null -> say("这句话没有回复，再试一次。")
                is Reply.Failed -> say(reply.text)
                is Reply.Text -> {
                    // 她的回复也可能带 emoji；通话气泡不显示，朗读本来也会剔掉。
                    val line = Emoji.strip(reply.text)
                    _state.value = _state.value.copy(reply = line.takeIf { it.isNotBlank() })
                    speak(line)
                }
                is Reply.Voice -> {
                    // 语音回复只展示真正说了什么；没有文本时留空，不写「（语音回复）」这类内部标记。
                    val line = Emoji.strip(reply.spokenText.orEmpty())
                    _state.value = _state.value.copy(
                        reply = line.takeIf { it.isNotBlank() },
                        phase = CallState.Phase.SPEAKING,
                    )
                    noteTurn(fromMe = false, text = line.ifBlank { "（语音回复）" })
                    // 语音文件走 MediaPlayer：同样要把这句话记进比对基准，否则外放回声会被当成用户输入。
                    spoken.remember(line)
                    playVoice(reply.file)
                }
            }
            Log.i(
                TAG,
                "call turn: listen=${listenedAt - turnStart}ms asr=${transcribedAt - listenedAt}ms " +
                    "reply=${repliedAt - transcribedAt}ms speak=${clock() - repliedAt}ms",
            )
            if (_state.value.active) {
                _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
                // 播完再等房间残响，之后才重新开麦。
                delay(ECHO_TAIL_MS)
            }
        }
    }

    /**
     * Records until the endpoint: waits for speech, then for [END_SILENCE_MS] of silence, and
     * restarts the file every [QUIET_RESTART_MS] while nobody speaks so nothing grows unbounded.
     * 豆包引擎时，每一段录音都同时挂一条流式识别连接，边录边发。
     */
    private suspend fun listen(): Utterance? {
        if (!_state.value.active) return null
        _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
        if (!startCapture()) {
            abort("打不开麦克风，检查录音权限。")
            return null
        }
        var heard = false
        var quiet = 0L
        var elapsed = 0L
        var recording = true
        try {
            while (true) {
                if (!_state.value.active) return null
                delay(POLL_MS)
                // 静音：摘掉麦克风并重置这一句的判定；取消静音后从新的一句重新开始。
                if (muted) {
                    if (recording) {
                        cancelCapture()
                        recording = false
                    }
                    heard = false
                    quiet = 0
                    elapsed = 0
                    _level.value = 0f
                    continue
                }
                if (!recording) {
                    if (!startCapture()) {
                        abort("麦克风中断了。")
                        return null
                    }
                    recording = true
                    elapsed = 0
                }
                elapsed += POLL_MS
                val amplitude = recorder.amplitude()
                _level.value = amplitude / 32767f
                if (!heard) {
                    if (amplitude >= SPEECH_PEAK) {
                        heard = true
                        quiet = 0
                    } else if (elapsed >= QUIET_RESTART_MS) {
                        cancelCapture()
                        if (!startCapture()) {
                            abort("麦克风中断了。")
                            return null
                        }
                        elapsed = 0
                    }
                } else if (amplitude >= SPEECH_PEAK) {
                    quiet = 0
                } else {
                    quiet += POLL_MS
                    if (quiet >= END_SILENCE_MS) return finishUtterance()
                }
                if (heard && elapsed >= MAX_UTTERANCE_MS) return finishUtterance()
            }
        } finally {
            // Cancellation (end call / leaving the screen) also stops the microphone.
            // 成功收尾时 finishUtterance 已经把文件和连接取走，这里是取消/静音/异常时的兜底。
            cancelCapture()
            _level.value = 0f
        }
    }

    /** 开麦；豆包时同时连一条流式识别，`onChunk` 把 PCM 直接推出去。 */
    private fun startCapture(): Boolean {
        cancelCapture()
        val asr = settings().asr
        val session = if (asr.vendor == VoiceVendor.DOUBAO) openAsrStream(asr) else null
        val sink: ((ByteArray, Int) -> Unit)? =
            session?.let { s -> { bytes: ByteArray, count: Int -> s.sendPcm(bytes, 0, count) } }
        if (!recorder.start(onChunk = sink)) {
            session?.cancel()
            return false
        }
        asrStream = session
        return true
    }

    private fun openAsrStream(asr: AsrSettings): DoubaoAsr.Session? = try {
        DoubaoAsr.open(asr.active.copy(resourceId = asr.resourceId))
    } catch (e: Exception) {
        // 连不上也继续录，收尾时用硅基兜底，并把原因带上。
        asrStreamError = e.message ?: "豆包语音识别连接失败"
        Log.w(TAG, "call asr: doubao open failed: ${e.message}")
        null
    }

    /** 停止采集，并取消这一句还没收尾的识别连接；临时文件一并删掉。 */
    private fun cancelCapture() {
        val session = asrStream
        asrStream = null
        asrStreamError = null
        session?.cancel()
        recorder.stop()?.delete()
    }

    private fun finishUtterance(): Utterance? {
        val stream = asrStream
        val streamError = asrStreamError
        asrStream = null
        asrStreamError = null
        val file = recorder.stop()
        if (file == null || file.length() <= 0) {
            file?.delete()
            stream?.cancel()
            return null
        }
        return Utterance(file, stream, streamError)
    }

    private suspend fun transcribe(utterance: Utterance): String? {
        _state.value = _state.value.copy(phase = CallState.Phase.TRANSCRIBING)
        val recording = utterance.file
        try {
            val asr = settings().asr
            var failure: String? = null
            val stream = utterance.stream
            if (stream != null) {
                // 流式识别已经在录音过程中跑完了：这里只发负包取最终文本。
                val raw = try {
                    withContext(Dispatchers.IO) { stream.finish() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: "语音识别失败"
                    null
                }
                // 豆包只取文本，不显示情绪/语速；表情同样剥掉，避免空气泡。
                val line = raw?.let { Emoji.strip(it) }
                if (!line.isNullOrBlank()) return line
                if (failure == null) failure = "语音识别失败：豆包没有识别出内容。"
            } else if (asr.vendor == VoiceVendor.DOUBAO) {
                failure = utterance.streamError ?: "语音识别失败：豆包连接没有建立。"
            }

            // 硅基：当前引擎时就是主路径，豆包失败时是兜底。录音一直是 WAV，两边都能用。
            val silicon = asr.configOf(VoiceVendor.SILICONFLOW)
            if (!silicon.configured) {
                say(failure?.let { "$it 硅基兜底也没有配置，去设置里补上语音识别。" } ?: "还没有配置语音识别。")
                return null
            }
            val text = try {
                Emoji.strip(api.transcribe(silicon, recording))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "识别失败"
                say(failure?.let { "$it 硅基兜底也失败：$reason" } ?: reason)
                return null
            }
            if (text.isBlank()) {
                if (failure != null) say("$failure 硅基也没有识别出内容。")
                else Log.i(TAG, "call: transcription empty after emoji strip")
                return null
            }
            return text
        } finally {
            recording.delete()
        }
    }

    private sealed interface Reply {
        data class Text(val text: String) : Reply
        /** [spokenText] 是这条语音实际朗读的文本，用于自回声比对；可能为空。 */
        data class Voice(val file: String, val spokenText: String?) : Reply
        data class Failed(val text: String) : Reply
    }

    /** Waits for the turn to finish and returns the newest reply (text, voice bubble or error). */
    private suspend fun awaitReply(before: Long): Reply? {
        val finished = withTimeoutOrNull(TURN_TIMEOUT_MS) {
            combine(session.items, session.busy) { items, busy -> items to busy }
                .first { (items, busy) -> !busy && items.any { it.id > before } }
                .first
        } ?: return null
        val fresh = finished.filter { it.id > before }
        fresh.lastOrNull { it is ChatItem.Assistant }?.let { return Reply.Text((it as ChatItem.Assistant).text) }
        fresh.lastOrNull { it is ChatItem.AssistantVoice }?.let {
            val voice = it as ChatItem.AssistantVoice
            return Reply.Voice(voice.file, voice.spokenText)
        }
        fresh.lastOrNull { it is ChatItem.Error }?.let { return Reply.Failed((it as ChatItem.Error).text) }
        return null
    }

    /**
     * 和聊天气泡共用一套分句合成：第一句就绪就播，后面的句子在这句播放时已经在合成。
     * 括号旁白、Markdown、列表符号在 [TtsScript] 里剔掉，不会被念出来。
     */
    private suspend fun speak(text: String) {
        _state.value = _state.value.copy(phase = CallState.Phase.SPEAKING, message = null)
        _level.value = 0f
        val current = settings()
        // 按当前朗读引擎换算音色；硅基与豆包 的 voice id 不通用。
        val tts = current.ttsRequest
        // 通话字幕与朗读共用同一份正文：先剥 emoji，空了就当作没有可说内容，直接回到听。
        val line = Emoji.strip(text)
        if (!TtsScript.readable(line)) {
            // 整条都在括号里，没什么可念的：直接回到听。
            _state.value = _state.value.copy(phase = CallState.Phase.LISTENING)
            return
        }
        noteTurn(fromMe = false, text = line)
        spoken.remember(line)
        try {
            engine.stream(tts, line, fallback = current.ttsFallback) { _, bytes ->
                if (!_state.value.active || _state.value.phase != CallState.Phase.SPEAKING) return@stream
                playClip { done -> speaker.play(bytes, done) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(e.message ?: "朗读失败")
        }
    }

    private suspend fun playVoice(path: String) {
        _level.value = 0f
        val file = File(path)
        if (!file.isFile) {
            // 文件被清理属于内部状态：只记日志，不在通话页说这句话。
            Log.w(TAG, "call voice missing: $path")
            return
        }
        playClip { done -> speaker.play(file, done) }
    }

    /**
     * Plays a clip to the end without opening the microphone: recording during playback would
     * pick up her own voice. Returns when playback finishes or the call ends.
     */
    private suspend fun playClip(start: (onDone: () -> Unit) -> Unit) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { start { done.complete(Unit) } }
        try {
            while (_state.value.active && _state.value.phase == CallState.Phase.SPEAKING &&
                !done.isCompleted) delay(POLL_MS)
        } finally {
            withContext(Dispatchers.Main) { speaker.stop() }
            _level.value = 0f
        }
    }

    private fun say(message: String) {
        _state.value = _state.value.copy(phase = CallState.Phase.LISTENING, message = message)
    }

    private fun abort(message: String) {
        val wasConnected = connectedAt > 0
        stopAll()
        _state.value = _state.value.copy(active = false, phase = CallState.Phase.IDLE, message = message)
        record(if (wasConnected) CallOutcome.CONNECTED else CallOutcome.FAILED, message)
    }

    private fun failCall(message: String) {
        val wasConnected = connectedAt > 0
        stopAll()
        _state.value = _state.value.copy(active = false, phase = CallState.Phase.IDLE, message = message)
        record(if (wasConnected) CallOutcome.CONNECTED else CallOutcome.FAILED, message)
    }

    private companion object {
        const val TAG = "Lily"
        const val POLL_MS = 100L
        const val SPEECH_PEAK = 1_800
        const val END_SILENCE_MS = 1_200L
        const val QUIET_RESTART_MS = 10_000L
        const val MAX_UTTERANCE_MS = 30_000L
        const val TURN_TIMEOUT_MS = 180_000L
        /** 来电最多响 30 秒，没人接就记为未接。 */
        const val INCOMING_TIMEOUT_MS = 30_000L

        /** 播完后等多久再开麦，留一点房间残响。 */
        const val ECHO_TAIL_MS = 300L
    }
}
