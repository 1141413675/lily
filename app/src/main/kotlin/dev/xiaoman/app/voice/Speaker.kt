package dev.xiaoman.app.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import java.io.File
import kotlin.math.sqrt

/**
 * Plays one reply at a time. [stop] is the "打断朗读" action: it only stops playback and never
 * touches the agent task running in the session.
 */
class Speaker(
    private val cacheDir: File,
    /** Optional playback level in 0..1. Receives zero when the current playback stops. */
    private val onLevel: ((Float) -> Unit)? = null,
) {

    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var file: File? = null
    private var transient = false

    /** Plays [audio] written to a temporary file. [onDone] runs when playback ends or fails. */
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
        val meter = visualizer
        visualizer = null
        if (meter != null) releaseMeter(meter)
        try {
            if (p.isPlaying) p.stop()
        } catch (e: Exception) {
            // Already completed or released.
        }
        p.release()
        if (transient) file?.delete()
        file = null
        onLevel?.invoke(0f)
    }

    private fun start(source: File, transient: Boolean, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        file = source
        this.transient = transient
        p.setOnCompletionListener { finish(it, onDone) }
        p.setOnErrorListener { failed, _, _ ->
            finish(failed, onDone)
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
            p.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    try {
                        prepared.start()
                        startMeter(prepared)
                    } catch (e: IllegalStateException) {
                        finish(prepared, onDone)
                    }
                }
            }
            p.prepareAsync()
        } catch (e: Exception) {
            finish(p, onDone)
        }
    }

    private fun finish(completed: MediaPlayer, onDone: () -> Unit) {
        if (player !== completed) return
        stop()
        onDone()
    }

    private fun startMeter(source: MediaPlayer) {
        val publish = onLevel ?: return
        val sessionId = source.audioSessionId
        if (sessionId <= 0) return
        val meter = try {
            Visualizer(sessionId)
        } catch (e: RuntimeException) {
            // Playback works even if the device or the granted permissions do not allow metering.
            return
        }
        try {
            val listener = object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer,
                    waveform: ByteArray,
                    samplingRate: Int,
                ) {
                    if (this@Speaker.visualizer !== visualizer || waveform.isEmpty()) return
                    var energy = 0
                    for (byte in waveform) {
                        val sample = (byte.toInt() and 0xff) - 128
                        energy += sample * sample
                    }
                    val rms = sqrt(energy.toFloat() / waveform.size) / 128f
                    publish((rms * 3f).coerceIn(0f, 1f))
                }

                override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
            }
            meter.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
            val rate = minOf(30_000, Visualizer.getMaxCaptureRate())
            check(meter.setDataCaptureListener(listener, rate, true, false) == Visualizer.SUCCESS)
            check(meter.setEnabled(true) == Visualizer.SUCCESS)
            visualizer = meter
        } catch (e: RuntimeException) {
            releaseMeter(meter)
        }
    }

    private fun releaseMeter(meter: Visualizer) {
        runCatching { meter.setEnabled(false) }
        runCatching { meter.release() }
    }
}
