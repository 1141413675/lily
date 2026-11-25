package dev.xiaoman.app.voice

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Local audio of chat voice messages: `u-<id>.m4a` (what the user said) and `a-<id>.mp3` (the
 * spoken reply), under `<files>/audio`. Chat rows hold absolute paths, so playback streams from
 * the file and never loads audio into memory.
 *
 * [cleanup] bounds the folder by total size, file count and age (oldest first), run at startup and
 * after every save so neither storage nor memory grows without limit.
 */
class AudioStore(val dir: File) {

    init {
        dir.mkdirs()
    }

    fun userFile(id: Long) = File(dir, "u-$id.m4a")

    fun replyFile(id: Long) = File(dir, "a-$id.mp3")

    /** Moves the finished recording into the user slot of chat row [id]; null when it cannot be stored. */
    fun adoptUser(id: Long, source: File): File? = try {
        val target = userFile(id)
        if (source.renameTo(target)) {
            target
        } else {
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        }
    } catch (e: Exception) {
        null
    }

    fun saveReply(id: Long, audio: ByteArray): File? = try {
        replyFile(id).apply { writeBytes(audio) }
    } catch (e: Exception) {
        null
    }

    /** Deletes oldest files until the folder is within all three limits. */
    @Synchronized
    fun cleanup(now: Long = System.currentTimeMillis()) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            val expired = now - file.lastModified() > MAX_AGE_MS
            if (!expired && total <= MAX_TOTAL_BYTES && count <= MAX_FILES) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                count--
            }
        }
    }

    companion object {
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
        const val MAX_FILES = 200
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /** Duration of [file] in milliseconds, or 0 when it cannot be read. */
        fun durationMs(file: File): Int {
            if (!file.isFile) return 0
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.toInt() ?: 0
            } catch (e: Exception) {
                0
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Already released.
                }
            }
        }

        /**
         * 内存里 MP3 的时长：逐帧读头累加采样数，跳过 ID3v2。读不出帧（不是 MP3）时返回 0。
         * 只给合成出口的异常检测用，允许有几十毫秒误差。
         */
        fun mp3DurationMs(bytes: ByteArray): Int {
            var i = 0
            // ID3v2 头 10 字节，长度是 4 个 syncsafe 字节。
            if (bytes.size > 10 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
                val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
                    ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
                i = (10 + size).coerceAtMost(bytes.size)
            }
            var seconds = 0.0
            while (i + 4 <= bytes.size) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if (bytes[i] != 0xFF.toByte() || (b1 and 0xE0) != 0xE0) {
                    i++
                    continue
                }
                val version = (b1 shr 3) and 0x03 // 0: 2.5, 2: 2, 3: 1
                val layer = (b1 shr 1) and 0x03 // 1: III, 2: II, 3: I
                val bitrateIndex = (b2 shr 4) and 0x0F
                val rateIndex = (b2 shr 2) and 0x03
                val padding = (b2 shr 1) and 0x01
                if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
                    i++
                    continue
                }
                val mpeg1 = version == 3
                val kbps = when {
                    layer == 3 -> if (mpeg1) BITRATE_V1_L1 else BITRATE_V2_L1
                    layer == 2 -> if (mpeg1) BITRATE_V1_L2 else BITRATE_V2_L2L3
                    else -> if (mpeg1) BITRATE_V1_L3 else BITRATE_V2_L2L3
                }[bitrateIndex]
                val sampleRate = when (version) {
                    3 -> intArrayOf(44100, 48000, 32000)
                    2 -> intArrayOf(22050, 24000, 16000)
                    else -> intArrayOf(11025, 12000, 8000)
                }[rateIndex]
                val bitrate = kbps * 1000
                if (bitrate <= 0) {
                    i++
                    continue
                }
                val samples = when {
                    layer == 3 -> 384
                    layer == 2 -> 1152
                    mpeg1 -> 1152
                    else -> 576
                }
                val frameBytes = when {
                    layer == 3 -> (12 * bitrate / sampleRate + padding) * 4
                    layer == 1 && !mpeg1 -> 72 * bitrate / sampleRate + padding
                    else -> 144 * bitrate / sampleRate + padding
                }
                if (frameBytes <= 4) {
                    i++
                    continue
                }
                seconds += samples.toDouble() / sampleRate
                i += frameBytes
            }
            return (seconds * 1000).toInt()
        }

        private val BITRATE_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        private val BITRATE_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATE_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val BITRATE_V2_L2L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    }
}
