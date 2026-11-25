package dev.xiaoman.app.dream

import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.memory.Permalinks
import java.time.LocalDate

/**
 * Long-term rules live in the normal memory folder as `rules/<key>.md` (Basic Memory Markdown), so
 * the existing store searches and lists them and the user can open them in the memory screen.
 *
 * Frontmatter carries the machine-readable part: `kind`, `status` (active/rejected), `confidence`,
 * `statement`, `evidence` (memory permalinks), `created` and `dream` (the night that proposed it).
 * The body repeats the rule and its evidence for reading.
 */
class RuleStore(
    private val memory: MemoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun all(): List<RuleView> = memory.list(FOLDER)
        .filter { it.type == TYPE }
        .mapNotNull { it.toRule() }
        .sortedWith(compareByDescending<RuleView> { it.status == "active" }.thenByDescending { it.confidence })

    /** Active rules for the system prompt, strongest first. */
    suspend fun promptRules(limit: Int = 12): List<RuleView> = all()
        .filter { it.status == "active" && it.confidence >= DreamTypes.MIN_RULE_CONFIDENCE }
        .sortedByDescending { it.confidence }
        .take(limit)

    suspend fun rejectedKeys(): Set<String> = all()
        .filter { it.status == "rejected" }
        .map { it.key.ifBlank { Permalinks.fromTitle(it.statement) } }
        .toSet()

    suspend fun rejectedStatements(): Set<String> = all()
        .filter { it.status == "rejected" }
        .map { normalize(it.statement) }
        .toSet()

    suspend fun save(rule: DreamRule, evidence: List<String>, date: String) {
        val key = rule.key.trim().takeIf { it.isNotEmpty() }?.let { Permalinks.fromTitle(it) }
            ?: Permalinks.fromTitle(rule.statement).ifEmpty { "rule-${clock()}" }
        val permalink = "$FOLDER/$key"
        val existing = memory.read(permalink)
        // A rule the user overturned stays overturned; the same memory must not bring it back.
        if (existing?.extraString("status") == "rejected") return
        val statement = rule.statement.trim()
        if (statement.isEmpty()) return
        val title = statement.take(30)
        val evidenceLines = (rule.evidence + evidence).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val body = buildString {
            append("# ").append(title).append("\n\n## 规则\n").append(statement).append("\n")
            if (evidenceLines.isNotEmpty()) {
                append("\n## 证据\n")
                evidenceLines.forEach { append("- ").append(it).append('\n') }
            }
        }
        memory.write(
            Note(
                title = title,
                permalink = permalink,
                type = TYPE,
                tags = listOf(TYPE, rule.kind.ifBlank { "preference" }),
                extraFrontmatter = linkedMapOf(
                    "kind" to rule.kind.ifBlank { "preference" },
                    "status" to "active",
                    "confidence" to rule.confidence.coerceIn(0.0, 1.0),
                    "statement" to statement,
                    "evidence" to evidenceLines,
                    "created" to date,
                    "dream" to date,
                ),
                body = body,
                updatedAtMillis = clock(),
            ),
        )
    }

    /** The user says "我不是这样": the rule is kept for review but never applied again. */
    suspend fun reject(permalink: String): Boolean {
        val note = memory.read(permalink) ?: return false
        if (note.type != TYPE) return false
        val extras = LinkedHashMap(note.extraFrontmatter)
        extras["status"] = "rejected"
        extras["rejectedAt"] = LocalDate.now().toString()
        memory.write(note.copy(extraFrontmatter = extras, updatedAtMillis = clock()))
        return true
    }

    private fun Note.toRule(): RuleView? {
        val statement = extraString("statement")?.takeIf { it.isNotBlank() } ?: return null
        return RuleView(
            permalink = permalink,
            key = permalink.removePrefix("$FOLDER/"),
            title = title,
            statement = statement,
            kind = extraString("kind") ?: "preference",
            status = extraString("status") ?: "active",
            confidence = extraNumber("confidence") ?: 0.7,
            evidence = extraStringList("evidence"),
            created = extraString("created") ?: "",
        )
    }

    private fun Note.extraString(key: String): String? = extraFrontmatter[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun Note.extraNumber(key: String): Double? = when (val value = extraFrontmatter[key]) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull()
    }

    private fun Note.extraStringList(key: String): List<String> = when (val value = extraFrontmatter[key]) {
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> listOf(value)
        else -> emptyList()
    }

    private fun normalize(text: String) = text.replace(Regex("\\s+"), "").lowercase()

    companion object {
        const val FOLDER = "rules"
        const val TYPE = "rule"
    }
}
