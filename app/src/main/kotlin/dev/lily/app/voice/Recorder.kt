package dev.lily.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * 录一句话：AudioRecord → 16 kHz / 单声道 / 16-bit PCM16，停止时补 WAV 头，
 * 既能给硅基上传，也能在录音过程中通过 [start] 的 `onChunk` 持续推给豆包流式识别。
 * 文件放在缓存目录，调用方负责删除。
 */
class Recorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var file: File? = null

    @Volatile private var running = false
    @Volatile private var peak = 0

    /**
     * Starts a new recording; false when the microphone cannot be opened. [source] is
     * `MIC` for the utterance itself and `VOICE_COMMUNICATION` for barge-in monitoring.
     * [onChunk] 每收到一小段 PCM16（小端）回调一次，长度单位是字节；回调里只做拷贝/发送，不要阻塞。
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(
        source: Int = MediaRecorder.AudioSource.MIC,
        onChunk: ((ByteArray, Int) -> Unit)? = null,
    ): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (min <= 0) return false
        val r = try {
            AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(min, SAMPLE_RATE / 5 * 2))
        } catch (e: Exception) {
            Log.w(TAG, "recorder: AudioRecord create failed (${e.javaClass.simpleName})")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        val target = try {
            File.createTempFile("utterance-", ".wav", context.cacheDir).also { created ->
                RandomAccessFile(created, "rw").use { it.setLength(HEADER_SIZE.toLong()) }
            }
        } catch (e: Exception) {
            r.release()
            return false
        }
        val started = try {
            r.startRecording()
            r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (e: Exception) {
            false
        }
        if (!started) {
            r.release()
            target.delete()
            return false
        }
        record = r
        file = target
        peak = 0
        running = true
        worker = thread(name = "chat-recorder", isDaemon = true) { pump(r, target, onChunk) }
        return true
    }

    /** Peak amplitude since the previous call (0..32767); 0 while not recording. */
    @Synchronized
    fun amplitude(): Int = peak.also { peak = 0 }

    /** Stops and returns the recording (WAV), or null when nothing usable was captured. */
    @Synchronized
    fun stop(): File? {
        val r = record ?: return null
        record = null
        running = false
        try {
            r.stop()
        } catch (e: Exception) {
            // 没录到数据时 stop 会抛；下面仍按已有字节判断。
        }
        worker?.let { runCatching { it.join(800) } }
        worker = null
        try {
            r.release()
        } catch (e: Exception) {
            // Already released.
        }
        peak = 0
        val target = file
        file = null
        if (target == null) return null
        val bytes = (target.length() - HEADER_SIZE).coerceAtLeast(0)
        if (bytes <= 0) {
            target.delete()
            return null
        }
        writeHeader(target, bytes)
        return target
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
            Log.w(TAG, "recorder: write failed (${e.javaClass.simpleName})")
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
        const val TAG = "Lily"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val HEADER_SIZE = 44
    }
}
