package dev.xiaoman.app.image

import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 她主动发图落盘与配额：生成图只写应用私有目录 `<files>/images`。
 * 复用聊天里出现过的图、图库兜底都只记录路径，不复制文件，不重复占空间。
 */
class ChatImageStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    private val indexFile = File(dir, "index.json")
    private var day: String = LocalDate.now().toString()
    private var generatedToday: Int = 0
    private var lastShareAt: Long = 0L
    private var lastSharedPath: String = ""

    init {
        read()
    }

    @Synchronized
    fun canShare(cooldownMillis: Long): Boolean {
        rollDay()
        return lastShareAt <= 0 || now() - lastShareAt >= cooldownMillis
    }

    @Synchronized
    fun markShared(path: String) {
        lastShareAt = now()
        lastSharedPath = path
        persist()
    }

    @Synchronized
    fun canGenerate(limit: Int): Boolean {
        rollDay()
        return generatedToday < limit.coerceAtLeast(0)
    }

    @Synchronized
    fun markGenerated() {
        rollDay()
        generatedToday += 1
        persist()
    }

    @Synchronized
    fun generatedToday(): Int {
        rollDay()
        return generatedToday
    }

    /** 生成结果落盘；失败返回 null，调用方静默降级。 */
    @Synchronized
    fun save(bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, "img-${UUID.randomUUID().toString().take(8)}.jpg")
        return try {
            target.writeBytes(bytes)
            target.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /**
     * 跟随现有音频/做梦的清理方式：按数量、总体积与时间兜底，最旧的先删。
     * 只在应用私有目录内操作，不会碰用户相册或图库文件。
     */
    @Synchronized
    fun cleanup() {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("img-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        files.forEachIndexed { index, file ->
            val tooOld = now() - file.lastModified() > MAX_AGE_MILLIS
            val tooMany = index >= MAX_COUNT
            val tooBig = total > MAX_TOTAL_BYTES
            if (tooOld || tooMany || tooBig) {
                total -= file.length()
                runCatching { file.delete() }
            }
        }
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (today != day) {
            day = today
            generatedToday = 0
            persist()
        }
    }

    private fun read() {
        if (!indexFile.isFile) return
        runCatching {
            val root = JSONObject(indexFile.readText())
            day = root.optString("day").ifBlank { LocalDate.now().toString() }
            generatedToday = root.optInt("generated")
            lastShareAt = root.optLong("lastShareAt")
            lastSharedPath = root.optString("lastSharedPath")
        }.onFailure {
            generatedToday = 0
            lastShareAt = 0L
            lastSharedPath = ""
        }
    }

    private fun persist() {
        runCatching {
            dir.mkdirs()
            indexFile.writeText(
                JSONObject()
                    .put("day", day)
                    .put("generated", generatedToday)
                    .put("lastShareAt", lastShareAt)
                    .put("lastSharedPath", lastSharedPath)
                    .toString(),
            )
        }
    }

    private companion object {
        const val MAX_COUNT = 80
        const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
