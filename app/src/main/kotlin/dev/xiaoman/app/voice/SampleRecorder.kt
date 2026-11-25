package dev.xiaoman.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 克隆样本录音器：16kHz / 单声道 / 16bit PCM，停止时写成一个 wav 文件。
 * 供应商不接受 MediaRecorder 的 m4a，所以这里自己攒 PCM 再补 wav 头；文件由调用方删除。
 */
class SampleRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private var target: File? = null

    @Volatile private var running = false
    @Volatile private var peak = 0
    @Volatile private var startedAt = 0L

    /** 声卡能接受的最小缓冲；打不开麦克风时返回 false。 */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        cancel()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(min, BUFFER_BYTES))
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        pcm.reset()
        peak = 0
        startedAt = System.currentTimeMillis()
        running = true
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            running = false
            return false
        }
        record = r
        worker = Thread {
            val buffer = ByteArray(BUFFER_BYTES)
            while (running) {
                val read = try {
                    r.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    -1
                }
                if (read <= 0) continue
                synchronized(pcm) { pcm.write(buffer, 0, read) }
                var max = 0
                var i = 0
                while (i + 1 < read) {
                    val v = (((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()).toInt()
                    val abs = if (v < 0) -v else v
                    if (abs > max) max = abs
                    i += 2
                }
                peak = max
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

    fun amplitude(): Int = peak

    /** 停止并落成 wav；样本太短或写盘失败时返回 null。 */
    @Synchronized
    fun stop(): File? {
        val r = record ?: return null
        running = false
        record = null
        startedAt = 0L
        try {
            r.stop()
        } catch (e: Exception) {
            // 没录到数据时 stop 会抛；下面仍按已有字节判断。
        }
        r.release()
        worker?.join(500)
        worker = null
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        pcm.reset()
        if (bytes.size < SAMPLE_RATE / 4) return null
        val file = try {
            File.createTempFile("voice-sample-", ".wav", context.cacheDir)
        } catch (e: Exception) {
            return null
        }
        return try {
            file.writeBytes(wav(bytes))
            target = file
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** 放弃当前录音并删掉文件。 */
    @Synchronized
    fun cancel() {
        running = false
        record?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // 已经停了。
            }
            it.release()
        }
        record = null
        worker?.join(300)
        worker = null
        pcm.reset()
        startedAt = 0L
        peak = 0
        target?.delete()
        target = null
    }

    private fun wav(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + data.size)
        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + data.size))
        out.write("WAVEfmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1)) // PCM
        out.write(le16(1)) // mono
        out.write(le32(SAMPLE_RATE))
        out.write(le32(SAMPLE_RATE * 2))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(data.size))
        out.write(data)
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_BYTES = 4_096
    }
}
