package dev.xiaoman.app.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * 免提通话的录音：AudioRecord（VOICE_COMMUNICATION）→ 16 kHz / 单声道 / 16-bit WAV，
 * 并在同一条 AudioRecord 会话上尝试平台回声消除（AcousticEchoCanceler）。
 * AEC 不可用或 enable 失败只记日志并降级，不影响录音本身。
 */
class AecRecorder(context: Context) {

    private val cacheDir = context.cacheDir
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var file: File? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var peak = 0

    /** 外放时才开 AEC；听筒/耳机下关掉，切换时对当前录音会话立即生效。 */
    @Volatile private var echoCancellation = true

    /** 切换免提时同步回声抑制；已经录着的话直接改当前会话，不必重开麦克风。 */
    @Synchronized
    fun setEchoCancellation(enabled: Boolean) {
        echoCancellation = enabled
        val r = record ?: return
        if (!enabled) {
            aec?.let { runCatching { it.enabled = false } }
            Log.i(TAG, "call aec: disabled for earpiece")
        } else if (aec == null) {
            aec = enableAec(r.audioSessionId)
        } else {
            aec?.let { runCatching { it.enabled = true } }
            Log.i(TAG, "call aec: re-enabled for speaker")
        }
    }

    /**
     * 开一条新的录音；打不开麦克风时返回 false。
     * [onChunk] 每收到一小段 16k 单声道 PCM16（小端）回调一次，供流式识别边录边发。
     */
    @Synchronized
    fun start(onChunk: ((ByteArray, Int) -> Unit)? = null): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (min <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(min, SAMPLE_RATE / 5 * 2),
            )
        } catch (e: Exception) {
            Log.w(TAG, "call aec: AudioRecord create failed (${e.javaClass.simpleName})")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        val target = try {
            File.createTempFile("utterance-", ".wav", cacheDir).also { created ->
                RandomAccessFile(created, "rw").use { it.setLength(HEADER_SIZE.toLong()) }
            }
        } catch (e: Exception) {
            r.release()
            return false
        }
        aec = if (echoCancellation) enableAec(r.audioSessionId) else null
        val started = try {
            r.startRecording()
            r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (e: Exception) {
            false
        }
        if (!started) {
            aec?.let { runCatching { it.release() } }
            aec = null
            r.release()
            target.delete()
            return false
        }
        record = r
        file = target
        peak = 0
        running = true
        worker = thread(name = "call-recorder", isDaemon = true) { pump(r, target, onChunk) }
        return true
    }

    /** 自上次调用以来的峰值（0..32767）。 */
    @Synchronized
    fun amplitude(): Int = peak.also { peak = 0 }

    /** 停止并返回录音文件；没有可用音频时返回 null。 */
    @Synchronized
    fun stop(): File? {
        val r = record ?: return null
        record = null
        running = false
        try {
            r.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        worker?.let { runCatching { it.join(800) } }
        worker = null
        try {
            r.release()
        } catch (e: Exception) {
            // Already released.
        }
        aec?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        aec = null
        peak = 0
        val target = file
        file = null
        if (target == null) return null
        val bytes = (target.length() - HEADER_SIZE).coerceAtLeast(0)
        if (bytes <= 0) return null
        writeHeader(target, bytes)
        return target
    }

    private fun enableAec(sessionId: Int): AcousticEchoCanceler? = try {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "call aec: available=false, recording without it")
            null
        } else {
            AcousticEchoCanceler.create(sessionId)?.also { effect ->
                effect.enabled = true
                Log.i(TAG, "call aec: available=true enabled=${effect.enabled}")
            } ?: run {
                Log.i(TAG, "call aec: create returned null")
                null
            }
        }
    } catch (e: Exception) {
        Log.i(TAG, "call aec: enable failed (${e.javaClass.simpleName})")
        null
    }

    private fun pump(r: AudioRecord, target: File, onChunk: ((ByteArray, Int) -> Unit)?) {
        val buffer = ShortArray(1600)
        val bytes = ByteArray(buffer.size * 2)
        try {
            RandomAccessFile(target, "rw").use { out ->
                out.seek(HEADER_SIZE.toLong())
                while (running) {
                    val count = try {
                        r.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        -1
                    }
                    if (count <= 0) continue
                    var max = 0
                    for (i in 0 until count) {
                        val value = buffer[i].toInt()
                        val abs = if (value < 0) -value else value
                        if (abs > max) max = abs
                        bytes[i * 2] = (value and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    if (max > peak) peak = max
                    out.write(bytes, 0, count * 2)
                    onChunk?.invoke(bytes.copyOf(count * 2), count * 2)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "call recorder: write failed (${e.javaClass.simpleName})")
        }
    }

    private fun writeHeader(target: File, dataBytes: Long) {
        runCatching {
            RandomAccessFile(target, "rw").use { out ->
                out.seek(0)
                out.writeBytes("RIFF")
                out.writeIntLe((36 + dataBytes).toInt())
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.writeIntLe(16)
                out.writeShortLe(1)
                out.writeShortLe(1)
                out.writeIntLe(SAMPLE_RATE)
                out.writeIntLe(SAMPLE_RATE * 2)
                out.writeShortLe(2)
                out.writeShortLe(16)
                out.writeBytes("data")
                out.writeIntLe(dataBytes.toInt())
            }
        }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private companion object {
        const val TAG = "Xiaoman"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val HEADER_SIZE = 44
    }
}
