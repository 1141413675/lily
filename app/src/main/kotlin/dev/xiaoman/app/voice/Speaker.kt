package dev.xiaoman.app.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Plays one reply at a time. [stop] is the "打断朗读" action: it only stops playback and never
 * touches the agent task running in the session.
 */
class Speaker(private val cacheDir: File) {

    private var player: MediaPlayer? = null
    private var file: File? = null
    private var transient = false

    /** Plays [audio] written to a temporary file; [onDone] runs once when playback ends, fails or is stopped. */
    fun play(audio: ByteArray, onDone: () -> Unit) {
        val target = try {
            File.createTempFile("reply-", ".mp3", cacheDir).also { it.writeBytes(audio) }
        } catch (e: Exception) {
            onDone()
            return
        }
        start(target, transient = true, onDone)
    }

    /** Plays an existing audio file (a chat voice reply) and keeps it; [onDone] runs when playback ends. */
    fun play(source: File, onDone: () -> Unit) {
        if (!source.isFile) {
            onDone()
            return
        }
        start(source, transient = false, onDone)
    }

    fun stop() {
        val p = player ?: return
        player = null
        try {
            if (p.isPlaying) p.stop()
        } catch (e: Exception) {
            // Already completed or released.
        }
        p.release()
        if (transient) file?.delete()
        file = null
    }

    private fun start(source: File, transient: Boolean, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        file = source
        this.transient = transient
        p.setOnCompletionListener { finish(onDone) }
        p.setOnErrorListener { _, _, _ ->
            finish(onDone)
            true
        }
        try {
            // 通话里走通信路径而不是媒体外放，平台回声消除才有参考信号。
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(source.absolutePath)
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            finish(onDone)
        }
    }

    private fun finish(onDone: () -> Unit) {
        if (player == null) return
        stop()
        onDone()
    }
}
