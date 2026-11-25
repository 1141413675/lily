package dev.xiaoman.app.dream

import dev.xiaoman.api.Note
import dev.xiaoman.memory.BasicMemoryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private val defaultConfig: DreamConfig = DreamConfig(),
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
    private val logJson = Json(json) { prettyPrint = false }

    fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Synchronized
    fun config(): DreamConfig = readJson(configFile, defaultConfig) ?: defaultConfig

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
        val line = logJson.encodeToString(LogLine(clock(), role, trimmed.take(MAX_LOG_CHARS)))
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
        val trimmed = text.trim().take(MAX_LOG_CHARS)
        if (trimmed.isEmpty()) return
        val dates = listOf(today(), LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)).distinct()
        synchronized(this) {
            for (date in dates) {
                val file = File(logDir, "$date.jsonl")
                if (!file.isFile) continue
                val records = readLog(file).toMutableList()
                val index = records.indexOfLast { record ->
                    record.entry?.let { it.r == role && it.x == trimmed } == true
                }
                if (index < 0) continue
                records.removeAt(index)
                runCatching {
                    writeAtomic(file, if (records.isEmpty()) "" else records.joinToString("\n", postfix = "\n") { it.source })
                }
                return
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
            readLog(file).forEach { record ->
                val entry = record.entry ?: return@forEach
                if (entry.t < since) return@forEach
                lines += entry.t to "[${time(entry.t)} ${if (entry.r == "user") "用户" else "她"}] ${entry.x}"
            }
        }
        val text = lines.sortedBy { it.first }.joinToString("\n") { it.second }
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    /** Older versions appended pretty-printed objects to JSONL. Preserve even unreadable records when editing. */
    private fun readLog(file: File): List<LogRecord> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val records = mutableListOf<LogRecord>()
        fun add(source: String) {
            val entry = runCatching { logJson.decodeFromString<LogLine>(source) }.getOrNull()
            records += LogRecord(source, entry)
        }

        var legacy: StringBuilder? = null
        for (line in lines) {
            val token = line.trim()
            val block = legacy
            when {
                token.startsWith("{") -> {
                    block?.let { add(it.toString()) }
                    legacy = if (token == "{") StringBuilder(line) else null
                    if (legacy == null) add(line)
                }
                block == null -> add(line)
                else -> {
                    block.append('\n').append(line)
                    if (token == "}") {
                        add(block.toString())
                        legacy = null
                    }
                }
            }
        }
        legacy?.let { add(it.toString()) }
        return records
    }

    /** Stages a durable copy of [note] and returns its path only after both files are written. */
    @Synchronized
    fun trash(note: Note, reason: String, date: String): String? {
        val dir = File(trashDir, date).apply { mkdirs() }
        val base = note.permalink.replace('/', '_').ifBlank { "note" }
        var file = File(dir, "$base.md")
        var n = 2
        while (file.exists() || File(file.path + ".json").exists()) file = File(dir, "$base-${n++}.md")
        val metadata = File(file.path + ".json")
        return try {
            writeAtomic(file, BasicMemoryFormat.render(note))
            val entry = TrashEntry(date, file.relativeTo(root).invariantSeparatorsPath, note.permalink, note.title, clock(), reason)
            writeAtomic(metadata, json.encodeToString(entry))
            file.absolutePath
        } catch (e: Exception) {
            file.delete()
            metadata.delete()
            null
        }
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
        val dir = file.parentFile ?: throw IOException("无法确定文件目录")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建文件目录")
        val temp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    @Serializable
    private data class LogLine(val t: Long, val r: String, val x: String)
    private data class LogRecord(val source: String, val entry: LogLine?)

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
