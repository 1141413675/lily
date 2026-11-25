package dev.xiaoman.app.voice

import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 克隆素材的初步体检：时长是否在 8–10 秒、底噪是否明显。
 *
 * 只对 [SampleRecorder] 产出的 16k / 单声道 / 16bit wav 做完整分析；用户选进来的其它格式只能查
 * 时长。是否只有一个人说话无法可靠自动判断，页面上用文字提示，不在这里假装检测。
 */
object SampleCheck {

    /** 返回一句「建议重录」的原因；达标返回 null。 */
    fun advise(file: File, durationMs: Long): String? {
        val issues = mutableListOf<String>()
        when {
            durationMs in 1..7_999 -> issues += "只有 ${durationMs / 1000} 秒，短于 8 秒"
            durationMs > 10_500 -> issues += "${durationMs / 1000} 秒偏长，8–10 秒效果最好"
        }
        val noise = wavNoise(file)
        if (noise != null && noise.snrDb < 12f) {
            issues += "底噪偏大（信噪比约 ${noise.snrDb.toInt()} dB）"
        }
        if (issues.isEmpty()) return null
        return "建议重录：${issues.joinToString("；")}。"
    }

    private data class Noise(val snrDb: Float)

    /** 按 160ms 一帧估信噪比：安静段的响度当底噪，最响的几帧当人声。 */
    private fun wavNoise(file: File): Noise? {
        if (file.extension.lowercase() != "wav") return null
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val dataAt = findData(bytes) ?: return null
        val frameSamples = SAMPLE_RATE * 160 / 1000
        val frameBytes = frameSamples * 2
        val levels = ArrayList<Double>()
        var offset = dataAt
        while (offset + frameBytes <= bytes.size) {
            var sum = 0.0
            var i = offset
            while (i < offset + frameBytes) {
                val v = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
                sum += (v.toDouble() * v)
                i += 2
            }
            levels += sqrt(sum / frameSamples)
            offset += frameBytes
        }
        if (levels.size < 4) return null
        levels.sort()
        val noise = levels[(levels.size * 0.2).toInt().coerceIn(0, levels.lastIndex)].coerceAtLeast(1.0)
        val speech = levels[(levels.size * 0.9).toInt().coerceIn(0, levels.lastIndex)].coerceAtLeast(1.0)
        return Noise((20 * log10(speech / noise)).toFloat())
    }

    private fun findData(bytes: ByteArray): Int? {
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                ((bytes[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return i + 8
            if (size <= 0) return null
            i += 8 + size + (size and 1)
        }
        return null
    }

    private const val SAMPLE_RATE = 16_000
}
