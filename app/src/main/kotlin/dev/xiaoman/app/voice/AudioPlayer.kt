package dev.xiaoman.app.voice

import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays one chat voice bubble at a time. [playingId] is the row being played (null = silent);
 * [stop] never touches the agent task behind the message.
 *
 * [playClipAwait] 用于分句流式：一句合成好就先播，播完返回，下一句此时已在后台合成。
 */
class AudioPlayer {

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var player: MediaPlayer? = null
    private var current: Long? = null

    /** 分句播放时等这一句结束；[stop] 会以 false 完成它。 */
    private var pending: CompletableDeferred<Boolean>? = null

    /** Plays [file] for chat row [id], or stops when that row is already playing. */
    fun toggle(id: Long, file: File) {
        if (current == id) {
            stop()
            return
        }
        stop()
        if (!file.isFile) return
        val p = MediaPlayer()
        player = p
        current = id
        _playingId.value = id
        p.setOnCompletionListener { finish() }
        p.setOnErrorListener { _, _, _ ->
            finish()
            true
        }
        try {
            p.setDataSource(file.absolutePath)
            p.prepareAsync()
            p.setOnPreparedListener { if (current == id) it.start() }
        } catch (e: Exception) {
            finish()
        }
    }

    /**
     * Plays one transient clip for row [id] and suspends until it ends. Returns false when playback
     * was stopped or failed (user taps the bubble again / leaves the screen), so the caller can stop
     * synthesizing the rest instead of burning requests nobody will hear.
     */
    suspend fun playClipAwait(id: Long, cacheDir: File, bytes: ByteArray): Boolean {
        val clip = try {
            File.createTempFile("tts-", ".mp3", cacheDir).also { it.writeBytes(bytes) }
        } catch (e: Exception) {
            return false
        }
        stop()
        val p = MediaPlayer()
        player = p
        current = id
        _playingId.value = id
        val done = CompletableDeferred<Boolean>()
        pending = done
        p.setOnCompletionListener { finishAwait(true) }
        p.setOnErrorListener { _, _, _ ->
            finishAwait(false)
            true
        }
        try {
            p.setDataSource(clip.absolutePath)
            p.prepareAsync()
            p.setOnPreparedListener { if (current == id) it.start() }
        } catch (e: Exception) {
            finishAwait(false)
        }
        return try {
            done.await()
        } catch (e: CancellationException) {
            stop()
            throw e
        } finally {
            clip.delete()
        }
    }

    fun stop() {
        val p = player
        val pendingNow = pending
        pending = null
        player = null
        current = null
        _playingId.value = null
        if (p != null) {
            try {
                if (p.isPlaying) p.stop()
            } catch (e: Exception) {
                // Completed or already released.
            }
            p.release()
        }
        pendingNow?.complete(false)
    }

    private fun finish() {
        if (player == null) return
        stop()
    }

    private fun finishAwait(result: Boolean) {
        val p = player ?: return
        val done = pending
        pending = null
        player = null
        current = null
        _playingId.value = null
        try {
            if (p.isPlaying) p.stop()
        } catch (e: Exception) {
            // Already completed.
        }
        p.release()
        done?.complete(result)
    }
}
