package dev.lily.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import dev.lily.app.R

/**
 * 通话提示音：全部来自 res/raw，不用网络音源。
 * 回铃音循环到接通或挂断；音频属性走 VOICE_COMMUNICATION，跟随通话音量；静音模式不响。
 */
class CallSounds(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var ringback: MediaPlayer? = null
    private var incoming: Ringtone? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val silent: Boolean
        get() = audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT

    /** 拨出即响，循环播放；静音模式下直接不播。 */
    fun startRingback() {
        if (silent) return
        stopRingback()
        ringback = play(R.raw.call_ringback, loop = true)
    }

    fun startIncoming() {
        stopRingback()
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        incoming = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
        }.getOrNull()
    }

    /** 接通、挂断、未接都先停回铃。 */
    fun stopRingback() {
        incoming?.let { runCatching { it.stop() } }
        incoming = null
        ringback?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        ringback = null
    }

    fun connected() {
        stopRingback()
        play(R.raw.call_connect, loop = false)
    }

    fun ended() {
        stopRingback()
        play(R.raw.call_end, loop = false)
    }

    fun release() = stopRingback()

    private fun play(resId: Int, loop: Boolean): MediaPlayer? {
        if (silent) return null
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.resources.openRawResourceFd(resId).use { fd ->
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                isLooping = loop
                setOnCompletionListener { if (!loop) it.release() }
                prepare()
                start()
            }
        }.getOrNull()
    }
}
