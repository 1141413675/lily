package dev.xiaoman.app.voice

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Records one utterance to a temporary m4a file; the caller owns (and deletes) the result. */
class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /**
     * Starts a new recording; false when the microphone cannot be opened. [source] is
     * `MIC` for the utterance itself and `VOICE_COMMUNICATION` for barge-in monitoring
     * (that source has echo cancellation against our own playback).
     */
    @Synchronized
    fun start(source: Int = MediaRecorder.AudioSource.MIC): Boolean {
        stop()
        val target = File.createTempFile("utterance-", ".m4a", context.cacheDir)
        val r = MediaRecorder()
        return try {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            true
        } catch (e: Exception) {
            r.release()
            target.delete()
            false
        }
    }

    /** Peak amplitude since the previous call (0..32767); 0 while not recording. */
    @Synchronized
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (e: Exception) {
        0
    }

    /** Stops and returns the recording, or null when nothing usable was captured. */
    @Synchronized
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val target = file
        file = null
        return try {
            r.stop()
            target?.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            // Thrown for a recording shorter than the encoder needs; drop the file.
            target?.delete()
            null
        } finally {
            r.release()
        }
    }
}
