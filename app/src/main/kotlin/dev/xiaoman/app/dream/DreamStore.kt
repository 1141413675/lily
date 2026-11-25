package dev.xiaoman.app.dream

import dev.xiaoman.api.Note
import dev.xiaoman.memory.BasicMemoryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Files of the dream feature (`<external files>/dream`):
 *
 * - `days/<date>.json` — one proposal list + summary per night;
 * - `log/<date>.jsonl` — the last days of chat text, 24h are read per dream;
 * - `trash/<date>/<note>.md` + `.json` — deleted notes, restorable;
 * - `history/<date>/<note>.md` — the version a contradiction replaced, kept for review;
 * - `config.json` — enabled, time, auto mode, last run date.
 *
 * Nothing here touches memory files: deletes go through the recycle bin and every write is done by
 * [dev.xiaoman.api.MemoryStore] in [DreamEngine].
 */
class DreamStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val daysDir = File(root, DAYS)
    private val logDir = File(root, LOGS)
    private val trashDir = File(root, TRASH)
    private val historyDir = File(root, HISTORY)
    private val configFile = File(root, CONFIG)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Synchronized
    fun config(): DreamConfig = readJson(configFile, DreamConfig()) ?: DreamConfig()

    @Synchronized
    fun saveConfig(config: DreamConfig) {
        writeAtomic(configFile, json.encodeToString(config))
    }

    @Synchronized
    fun days(): List<DreamDay> = dayFiles()
        .mapNotNull { readJson(it, null as DreamDay?) }
        .sortedByDescending { it.date }

    @Synchronized
    fun day(date: String): DreamDay? = readJson(File(daysDir, "$date.json"), null as DreamDay?)

    @Synchronized
    fun saveDay(day: DreamDay) {
        writeAtomic(File(daysDir, "${day.date}.json"), json.encodeToString(day))
    }

    /** Appends one chat line to today's log; capped per day so a long conversation cannot fill the disk. */
    fun record(role: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val line = json.encodeToString(LogLine(clock(), role, trimmed.take(MAX_LOG_CHARS)))
        synchronized(this) {
            try {
                val file = File(logDir, "${today()}.jsonl")
                if (file.length() > MAX_LOG_BYTES) return
                logDir.mkdirs()
                file.appendText(line + "\n")
            } catch (e: Exception) {
                // Logging is best-effort; a full disk must not affect the chat.
            }
        }
    }

    /** 撤回一条原话：从当天/前一天的日志里删掉它，夜里做梦不再读到。 */
    fun forget(role: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val dates = listOf(today(), LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)).distinct()
        synchronized(this) {
            dates.forEach { date ->
                val file = File(logDir, "$date.jsonl")
                if (!file.isFile) return@forEach
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).toMutableList()
                val index = lines.indexOfLast { raw ->
                    val entry = runCatching { json.decodeFromString<LogLine>(raw) }.getOrNull()
                    entry != null && entry.r == role && entry.x == trimmed
                }
                if (index < 0) return@forEach
                lines.removeAt(index)
                runCatching {
                    writeAtomic(file, if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
                }
            }
        }
    }

    /** The chat text of the last 24 hours, oldest first, at most [maxChars] characters (newest kept). */
    @Synchronized
    fun recentConversation(maxChars: Int = MAX_CONVERSATION_CHARS): String {
        val since = clock() - TimeUnit.HOURS.toMillis(24)
        val days = listOf(today(), LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE))
        val lines = mutableListOf<Pair<Long, String>>()
        for (date in days.distinct()) {
            val file = File(logDir, "$date.jsonl")
            if (!file.isFile) continue
            runCatching { file.readLines() }.getOrDefault(emptyList()).forEach { raw ->
                val entry = runCatching { json.decodeFromString<LogLine>(raw) }.getOrNull() ?: return@forEach
                if (entry.t < since) return@forEach
                lines += entry.t to "[${time(entry.t)} ${if (entry.r == "user") "用户" else "她"}] ${entry.x}"
            }
        }
        val text = lines.sortedBy { it.first }.joinToString("\n") { it.second }
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    /** Moves [note] into the recycle bin and returns the absolute path of the backup file. */
    @Synchronized
    fun trash(note: Note, reason: String, date: String): String? = try {
        val dir = File(trashDir, date).apply { mkdirs() }
        val base = note.permalink.replace('/', '_').ifBlank { "note" }
        var file = File(dir, "$base.md")
        var n = 2
        while (file.exists()) file = File(dir, "$base-${n++}.md")
        file.writeText(BasicMemoryFormat.render(note), Charsets.UTF_8)
        val entry = TrashEntry(date, file.relativeTo(root).invariantSeparatorsPath, note.permalink, note.title, clock(), reason)
        writeAtomic(File(file.path + ".json"), json.encodeToString(entry))
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun trashed(): List<TrashEntry> = trashDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".json") }
        .mapNotNull { readJson(it, null as TrashEntry?) }
        .sortedByDescending { it.deletedAt }
        .toList()

    fun pathOf(entry: TrashEntry): String = File(root, entry.file).absolutePath

    /** Parses the note stored at [entry] (absolute path) and its metadata. */
    @Synchronized
    fun readTrash(path: String): Pair<TrashEntry, Note>? {
        val file = File(path)
        val entry = readJson(File("$path.json"), null as TrashEntry?) ?: return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val note = BasicMemoryFormat.parse(text, entry.permalink, clock())
        return entry to note
    }

    @Synchronized
    fun dropTrash(path: String) {
        File(path).delete()
        File("$path.json").delete()
    }

    /** Copies [note] before a contradiction replaces it; returns the absolute backup path. */
    @Synchronized
    fun backupHistory(note: Note, date: String): String? = try {
        val dir = File(historyDir, date).apply { mkdirs() }
        val base = note.permalink.replace('/', '_').ifBlank { "note" }
        var file = File(dir, "$base.md")
        var n = 2
        while (file.exists()) file = File(dir, "$base-${n++}.md")
        file.writeText(BasicMemoryFormat.render(note), Charsets.UTF_8)
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    /** Deletes logs older than 3 days, recycle bin / history older than 30 days. */
    fun cleanup() {
        val today = LocalDate.now()
        synchronized(this) {
            logDir.listFiles()?.forEach { file ->
                val date = file.name.removeSuffix(".jsonl").let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                if (date.isBefore(today.minusDays(LOG_DAYS))) file.delete()
            }
            listOf(trashDir, historyDir).forEach { dir ->
                dir.listFiles()?.forEach { folder ->
                    val date = runCatching { LocalDate.parse(folder.name) }.getOrNull() ?: return@forEach
                    if (date.isBefore(today.minusDays(KEEP_DAYS))) folder.deleteRecursively()
                }
            }
        }
    }

    private fun dayFiles(): List<File> = daysDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()

    private fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

    private inline fun <reified T> readJson(file: File, default: T?): T? = try {
        if (file.isFile) json.decodeFromString<T>(file.readText(Charsets.UTF_8)) else default
    } catch (e: Exception) {
        default
    }

    private fun writeAtomic(file: File, text: String) {
        val dir = file.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val temp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    @Serializable
    private data class LogLine(val t: Long, val r: String, val x: String)

    companion object {
        const val DAYS = "days"
        const val LOGS = "log"
        const val TRASH = "trash"
        const val HISTORY = "history"
        const val CONFIG = "config.json"

        /** Reading cap of one dream, requirements: bounded input. */
        const val MAX_CONVERSATION_CHARS = 12_000
        const val MAX_MEMORY_CHARS = 16_000
        const val MAX_MEMORY_NOTES = 25

        private const val MAX_LOG_BYTES = 512L * 1024
        private const val MAX_LOG_CHARS = 1500
        private const val LOG_DAYS = 3L
        private const val KEEP_DAYS = 30L
    }
}
