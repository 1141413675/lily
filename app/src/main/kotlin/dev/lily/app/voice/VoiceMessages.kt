package dev.lily.app.voice

import android.content.Context
import dev.lily.app.settings.ReplyMode
import dev.lily.app.settings.AppSettings
import dev.lily.app.session.AgentSession
import dev.lily.app.session.ChatItem
import dev.lily.app.session.ReplyPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** State of the chat input while a voice message is being recorded or transcribed. */
sealed interface VoiceSend {
    data object Idle : VoiceSend
    data object Recording : VoiceSend
    data object Transcribing : VoiceSend
    data class Failed(val message: String) : VoiceSend
}

/**
 * The chat-side voice layer: record → transcribe → `session.submit(text)` with a
 * [ChatItem.UserVoice] bubble that plays the original recording, and replies presented according
 * to the locked [ReplyMode]. The agent, memory, skills and Sentinel are untouched.
 *
 * Audio lives in [AudioStore] (`<files>/audio`), never in memory; the chat rows only keep paths.
 */
class VoiceMessages(
    context: Context,
    private val session: AgentSession,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val call: () -> CallController,
    private val openCall: () -> Unit,
    audioDir: File = File(context.filesDir, "audio"),
) {
    val audio = AudioStore(audioDir)
    val player = AudioPlayer()
    private val recorder = Recorder(context)
    private val api = VoiceApi()
    private val engine = TtsEngine(api)
    private val clipDir = File(context.cacheDir, "tts").apply { mkdirs() }

    private val _send = MutableStateFlow<VoiceSend>(VoiceSend.Idle)
    val send: StateFlow<VoiceSend> = _send.asStateFlow()

    /** Pressed the mic: start capturing; the recording stays local until it is transcribed. */
    fun startRecording() {
        if (_send.value == VoiceSend.Recording) return
        if (!settings().asr.configured) {
            val missing = settings().asr.missing.joinToString("、")
            _send.value = VoiceSend.Failed("还没有配置语音识别，去设置里补上：$missing。")
            return
        }
        player.stop()
        if (!recorder.start()) {
            _send.value = VoiceSend.Failed("打不开麦克风，检查录音权限。")
            return
        }
        _send.value = VoiceSend.Recording
    }

    /** Released the mic: transcribe, keep the audio, and hand only the text to the agent. */
    fun stopAndSend() {
        if (_send.value != VoiceSend.Recording) return
        val recording = recorder.stop()
        if (recording == null) {
            _send.value = VoiceSend.Failed("没有录到声音，再试一次。")
            return
        }
        _send.value = VoiceSend.Transcribing
        scope.launch {
            val duration = AudioStore.durationMs(recording)
            val text = try {
                // 按设置里的引擎识别；豆包失败会自动回落到已配置的硅基。
                api.transcribe(settings().asr, recording)
            } catch (e: CancellationException) {
                recording.delete()
                throw e
            } catch (e: Exception) {
                recording.delete()
                _send.value = VoiceSend.Failed(e.message ?: "识别失败")
                return@launch
            }
            send(text, recording, duration)
        }
    }

    /** 上滑取消或录音过短：停掉采集、删掉临时文件，不发也不落盘。 */
    fun cancelRecording() {
        if (_send.value != VoiceSend.Recording) return
        recorder.stop()?.delete()
        _send.value = VoiceSend.Idle
    }

    fun dismiss() {
        if (_send.value is VoiceSend.Failed || _send.value == VoiceSend.Transcribing) _send.value = VoiceSend.Idle
    }

    /** Runs on a background thread: the recording is moved into [audio] under the new chat row id. */
    private fun send(text: String, recording: File, durationMs: Int) {
        val result = session.submit(text, display = { id ->
            val saved = audio.adoptUser(id, recording)
            if (saved == null) {
                ChatItem.User(id, text)
            } else {
                val ms = durationMs.takeIf { it > 0 } ?: AudioStore.durationMs(saved)
                ChatItem.UserVoice(id, saved.absolutePath, ms, text)
            }
        })
        recording.delete()
        audio.cleanup()
        _send.value = when (result) {
            AgentSession.Submit.Busy -> VoiceSend.Failed("手上还有事没做完，等一下再说。")
            AgentSession.Submit.NotConfigured -> VoiceSend.Failed("还没有配置聊天模型和操作模型。")
            else -> VoiceSend.Idle
        }
    }

    /** 短清唱由用户主动请求，AUTO 下也生成语音；TEXT 仍只显示歌词。 */
    fun onReply(text: String, id: Long): ChatItem? {
        val (parsedFirst, hint) = ReplyPresentation.parse(text)
        // 出口检查兜底：系统提示/技能说明残留不朗读、不显示。
        val clean = dev.lily.app.session.ReplyGuard.sanitize(parsedFirst)
        val song = parseSongReply(clean)
        val mode = settings().replyMode
        // 整条都在括号里、或剔除旁白后只剩标点：不发语音，退回文字气泡。
        val speakable = TtsScript.readable(song?.lyrics ?: clean)
        if (song != null) return when (mode) {
            ReplyMode.TEXT -> ChatItem.Assistant(id, song.lyrics)
            ReplyMode.CALL -> {
                if (speakable) offerIncomingReply(song.lyrics)
                ChatItem.Assistant(id, song.lyrics)
            }
            ReplyMode.AUTO, ReplyMode.VOICE ->
                if (speakable) voiceReply(song.lyrics, id, song) else ChatItem.Assistant(id, song.lyrics)
        }
        return when (mode) {
            // 看情况：优先信模型的标记；没写标记时只把短句当语音。
            ReplyMode.AUTO -> when {
                !speakable -> null
                hint == true -> voiceReply(clean, id)
                hint == false -> null
                shouldSpeak(TtsScript.clean(clean)) -> voiceReply(clean, id)
                else -> null
            }
            ReplyMode.TEXT -> null
            ReplyMode.VOICE -> if (speakable) voiceReply(clean, id) else null
            ReplyMode.CALL -> {
                if (speakable) offerIncomingReply(clean)
                ChatItem.Assistant(id, clean)
            }
        }
    }

    private fun offerIncomingReply(text: String) {
        scope.launch(Dispatchers.Main.immediate) {
            if (call().ringIncoming(text)) openCall()
        }
    }

    /**
     * 模型漏写标记时的兜底：一两句、没有列表/解释结构的短话才读出来。
     * 真正「讲事」的内容宁可显示文字，也不要在气泡里念一长段。
     */
    private fun shouldSpeak(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.lines().size > 2) return false
        if (t.length > 32) return false
        if (t.contains("：") || t.contains(":") || t.startsWith("- ") || t.contains("\n- ")) return false
        if (Regex("""(?m)^\s*\d+[.、]""").containsMatchIn(t)) return false
        return true
    }

    /**
     * Adds the bubble immediately (so ordering is kept), then synthesizes sentence by sentence and
     * plays each sentence as soon as it is ready. The combined mp3 is written to the bubble at the
     * end so replaying later does not hit the network again.
     */
    private fun voiceReply(text: String, id: Long, song: SongReply? = null): ChatItem {
        fun fallback() = ChatItem.Assistant(id, song?.let {
            "给你唱两句 · TTS 清唱\n${it.lyrics}\n语音合成失败，先给你看歌词。"
        } ?: text)
        scope.launch {
            val current = settings()
            // 按当前引擎换算音色（硅基 / 豆包 的 voice id 不通用），切换后立刻用新引擎。
            val tts = current.ttsRequest
            if (!tts.configured) {
                session.replace(id, fallback())
                return@launch
            }
            // 一条回复只用一个表演指令：CosyVoice2 的情绪只能写在 input 里（没有 emotion 参数）。
            // 指令只写情绪，不写语速/音量；模型给的 emotion 若带了这类说明，一并剔除。
            val instruction = song?.let { "用${it.emotion}的情绪清唱以下歌词。" }
                ?.let { TtsScript.withoutDirection(it).ifBlank { "语气自然。" } }
                ?: TtsScript.performance(text)
            val buffer = java.io.ByteArrayOutputStream()
            val stopped = object : Exception() {}
            try {
                engine.stream(tts, text, instruction, current.ttsFallback) { _, bytes ->
                    buffer.write(bytes)
                    // 用户点了停止或离开聊天：不再为没人听的部分发请求，已合成的留作气泡。
                    if (!player.playClipAwait(id, clipDir, bytes)) throw stopped
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !== stopped || buffer.size() == 0) {
                    session.replace(id, fallback())
                    return@launch
                }
            }
            val file = audio.saveReply(id, buffer.toByteArray()) ?: run {
                session.replace(id, fallback())
                return@launch
            }
            session.replace(id, ChatItem.AssistantVoice(
                id = id,
                file = file.absolutePath,
                durationMs = AudioStore.durationMs(file),
                songLyrics = song?.lyrics,
                songEmotion = song?.emotion,
                spokenText = text,
            ))
            audio.cleanup()
        }
        return ChatItem.AssistantVoice(id, "", 0, songLyrics = song?.lyrics, songEmotion = song?.emotion, spokenText = text)
    }
}

internal data class SongReply(val lyrics: String, val emotion: String)

private val SONG_REPLY = Regex("^\\s*\\[\\[sing:([^\\r\\n]+?)]]\\s*([\\s\\S]+)$")

internal fun parseSongReply(text: String): SongReply? {
    val match = SONG_REPLY.matchEntire(text) ?: return null
    val emotion = match.groupValues[1].trim()
    val lyrics = match.groupValues[2].trim()
    if (emotion.isEmpty() || emotion.length > 24 || lyrics.isEmpty()) return null
    return SongReply(lyrics, emotion)
}
