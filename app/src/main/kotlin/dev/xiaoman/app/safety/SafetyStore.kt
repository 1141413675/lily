package dev.xiaoman.app.safety

import android.util.Log
import dev.xiaoman.agent.ScreenText
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.Decision
import dev.xiaoman.api.SafetyBlock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One line of the local safety log: a refusal or an approval decision. */
@Serializable
data class SafetyEntry(
    val time: Long,
    /** `chat` | `task` | `action` | `confirm`. */
    val stage: String,
    /** The user's instruction / task goal, or the attempted action for confirmations. */
    val instruction: String,
    /** The rule name, or the approval prompt. */
    val rule: String,
    /** The matched word, the app package, or 已允许/已拒绝. */
    val matched: String = "",
)

/**
 * Append-only local audit under `<files>/safety/blocks.jsonl`. It records every hard refusal
 * (time, original instruction, matching rule) and every confirmation round-trip, and keeps the file
 * bounded so it cannot grow forever.
 */
class SafetyStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun record(block: SafetyBlock) = append(
        SafetyEntry(clock(), block.stage, block.instruction.oneLine(), block.rule, block.matched.oneLine()),
    )

    /** Only approval decisions are logged; plain allows would flood the file. */
    fun record(record: AuditRecord) {
        if (record.decision !is Decision.Ask) return
        val prompt = (record.decision as Decision.Ask).prompt
        val result = when (record.approved) {
            true -> "已允许"
            false -> "已拒绝"
            null -> "未答复"
        }
        append(SafetyEntry(clock(), "confirm", ScreenText.describe(record.action), prompt, result))
    }

    /** Newest first. */
    fun entries(limit: Int = 200): List<SafetyEntry> = synchronized(lock) {
        read().takeLast(limit).asReversed()
    }

    fun clear(): Boolean = synchronized(lock) {
        runCatching { file.delete() }.getOrDefault(false)
    }

    private fun append(entry: SafetyEntry) = synchronized(lock) {
        try {
            dir.mkdirs()
            file.appendText(json.encodeToString(SafetyEntry.serializer(), entry) + "\n")
            if (file.length() > MAX_BYTES) trim()
        } catch (e: IOException) {
            log("safety: cannot write audit: ${e.message}")
        }
    }

    /** Keeps the newest [MAX_ENTRIES] lines. */
    private fun trim() {
        val lines = file.readLines().takeLast(MAX_ENTRIES)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun read(): List<SafetyEntry> {
        if (!file.isFile) return emptyList()
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching { json.decodeFromString(SafetyEntry.serializer(), line) }.getOrNull()
                }.toList()
            }
        } catch (e: IOException) {
            log("safety: cannot read audit: ${e.message}")
            emptyList()
        }
    }

    private fun String.oneLine(): String = replace('\n', ' ').trim().take(MAX_TEXT)

    companion object {
        private const val TAG = "Xiaoman"
        private const val FILE_NAME = "blocks.jsonl"
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_ENTRIES = 1000
        private const val MAX_TEXT = 300
    }
}
