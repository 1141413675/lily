package dev.xiaoman.app.relationship

import dev.xiaoman.api.Note
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One unfinished topic; [followedOn] is the day the agent already asked about it once. */
data class OpenTopic(
    val text: String,
    val notedOn: String,
    val followedOn: String? = null,
)

/** A habit the user wants to keep; dates are local `yyyy-MM-dd`. */
data class Habit(
    val name: String,
    val lastDoneOn: String? = null,
    val addedOn: String = "",
    /** The day the agent last nudged about it, so a miss is mentioned only once per window. */
    val lastNudgedOn: String? = null,
)

/**
 * The editable relationship state. It lives in the existing Markdown memory as
 * `relationship/profile.md` (no new storage): scalars in frontmatter, the three lists as readable
 * sections so the user can edit them in Obsidian or from the relationship screen.
 *
 * Interaction counters are only ever incremented from actual user messages; shared moments and
 * open topics come from the user, never invented by the model.
 */
data class Relationship(
    val address: String = DEFAULT_ADDRESS,
    /** 0 初识, 1 熟悉, 2 亲近, 3 亲密. Raising it always needs the user's tap. */
    val tier: Int = 0,
    val interactions: Int = 0,
    /** Local `yyyy-MM-dd` of the first interaction. */
    val since: String = "",
    val lastContactAt: Long = 0,
    /** 上一次互动之前的那次互动时间；「距上次互动」用它与当前时间做差，避免被本轮消息覆盖。 */
    val previousContactAt: Long = 0,
    val lastProactiveAt: Long = 0,
    /** Local date of [proactiveCount], so the daily cap resets. */
    val proactiveDate: String = "",
    val proactiveCount: Int = 0,
    val sharedMoments: List<String> = emptyList(),
    val openTopics: List<OpenTopic> = emptyList(),
    val habits: List<Habit> = emptyList(),
    /** Call mood inferred from recent tone + time (`ENTHUSIASTIC`/`CALM`/`LOW`/`BUSY`). */
    val mood: String = "",
    /** Short, user-readable reason for [mood]; shown on the relationship screen. */
    val moodBasis: String = "",
    val moodAt: Long = 0,
    /** A late-night missed call asks for one opening after this time (next morning). */
    val pendingGreetingAt: Long = 0,
) {
    fun daysKnown(today: LocalDate = LocalDate.now()): Int {
        val start = Dates.parse(since) ?: return 1
        return (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceAtLeast(1)
    }

    /** The tier the counters would unlock; the stored tier never exceeds it (no skipping). */
    fun maxTier(today: LocalDate = LocalDate.now()): Int =
        RelationshipTiers.maxUnlocked(interactions, daysKnown(today))

    companion object {
        const val PERMALINK = "relationship/profile"
        const val TITLE = "关系档案"
        const val DEFAULT_ADDRESS = "你"

        fun moodLabel(mood: String): String = when (mood) {
            "ENTHUSIASTIC" -> "兴致高"
            "LOW" -> "低落"
            "BUSY" -> "忙碌"
            "CALM" -> "平静"
            else -> "未知"
        }
    }
}

/** Stage thresholds: concrete numbers, one stage at a time, user-confirmed. */
object RelationshipTiers {
    val NAMES = listOf("初识", "熟悉", "亲近", "亲密")

    const val FAMILIAR_INTERACTIONS = 20
    const val CLOSE_INTERACTIONS = 100
    const val CLOSE_DAYS = 7
    const val INTIMATE_INTERACTIONS = 300
    const val INTIMATE_DAYS = 30

    fun name(tier: Int): String = NAMES[tier.coerceIn(0, NAMES.lastIndex)]

    fun maxUnlocked(interactions: Int, days: Int): Int = when {
        interactions >= INTIMATE_INTERACTIONS && days >= INTIMATE_DAYS -> 3
        interactions >= CLOSE_INTERACTIONS && days >= CLOSE_DAYS -> 2
        interactions >= FAMILIAR_INTERACTIONS -> 1
        else -> 0
    }

    /** Only the next stage can be raised, and only when the counters already allow it. */
    fun canUpgradeTo(current: Int, target: Int, interactions: Int, days: Int): Boolean =
        target == current + 1 && target <= maxUnlocked(interactions, days)

    /** What still blocks the next stage, for the relationship screen. */
    fun requirementFor(tier: Int): String = when (tier) {
        1 -> "累计互动 $FAMILIAR_INTERACTIONS 次"
        2 -> "累计互动 $CLOSE_INTERACTIONS 次且相处 $CLOSE_DAYS 天"
        3 -> "累计互动 $INTIMATE_INTERACTIONS 次且相处 $INTIMATE_DAYS 天"
        else -> ""
    }
}

internal object Dates {
    fun of(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun text(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = of(millis, zone).toString()

    fun parse(text: String?): LocalDate? = text?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
}

/** Note codec: `relationship/profile.md` in the Basic Memory format. */
object RelationshipFormat {
    private const val SHARED = "共同经历"
    private const val TOPICS = "未完成的话题"
    private const val HABITS = "习惯打卡"

    fun toNote(relationship: Relationship): Note = Note(
        title = Relationship.TITLE,
        permalink = Relationship.PERMALINK,
        type = "relationship",
        tags = listOf("关系"),
        extraFrontmatter = linkedMapOf(
            "address" to relationship.address,
            "tier" to relationship.tier,
            "interactions" to relationship.interactions,
            "since" to relationship.since,
            "lastContact" to relationship.lastContactAt,
            "previousContact" to relationship.previousContactAt,
            "lastProactive" to relationship.lastProactiveAt,
            "proactiveDate" to relationship.proactiveDate,
            "proactiveCount" to relationship.proactiveCount,
            "mood" to relationship.mood,
            "moodBasis" to relationship.moodBasis,
            "moodAt" to relationship.moodAt,
            "pendingGreetingAt" to relationship.pendingGreetingAt,
        ),
        body = renderBody(relationship),
    )

    fun fromNote(note: Note): Relationship {
        val extras = note.extraFrontmatter
        val sections = readSections(note.body)
        return Relationship(
            address = extras["address"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: Relationship.DEFAULT_ADDRESS,
            tier = number(extras["tier"]),
            interactions = number(extras["interactions"]),
            since = extras["since"]?.toString()?.trim().orEmpty(),
            lastContactAt = long(extras["lastContact"]),
            previousContactAt = long(extras["previousContact"]),
            lastProactiveAt = long(extras["lastProactive"]),
            proactiveDate = extras["proactiveDate"]?.toString()?.trim().orEmpty(),
            proactiveCount = number(extras["proactiveCount"]),
            sharedMoments = sections[SHARED].orEmpty().filter { it.isNotBlank() },
            openTopics = sections[TOPICS].orEmpty().mapNotNull(::parseTopic),
            habits = sections[HABITS].orEmpty().mapNotNull(::parseHabit),
            mood = extras["mood"]?.toString()?.trim().orEmpty(),
            moodBasis = extras["moodBasis"]?.toString()?.trim().orEmpty(),
            moodAt = long(extras["moodAt"]),
            pendingGreetingAt = long(extras["pendingGreetingAt"]),
        )
    }

    fun renderBody(relationship: Relationship): String = buildString {
        append("# ").append(Relationship.TITLE).append('\n')
        append("\n## ").append(SHARED).append('\n')
        relationship.sharedMoments.forEach { append("- ").append(oneLine(it)).append('\n') }
        append("\n## ").append(TOPICS).append('\n')
        relationship.openTopics.forEach { topic ->
            append("- ").append(oneLine(topic.text)).append(" | ").append(topic.notedOn)
            topic.followedOn?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            append('\n')
        }
        append("\n## ").append(HABITS).append('\n')
        relationship.habits.forEach { habit ->
            append("- ").append(oneLine(habit.name))
            append(" | ").append(habit.lastDoneOn.orEmpty())
            append(" | ").append(habit.addedOn)
            habit.lastNudgedOn?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            append('\n')
        }
    }

    private fun parseTopic(line: String): OpenTopic? {
        val parts = line.split('|').map { it.trim() }
        val text = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val noted = parts.getOrNull(1)?.takeIf { Dates.parse(it) != null }.orEmpty()
        val followed = parts.getOrNull(2)?.takeIf { Dates.parse(it) != null }
        return OpenTopic(text, noted, followed)
    }

    private fun parseHabit(line: String): Habit? {
        val parts = line.split('|').map { it.trim() }
        val name = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Habit(
            name = name,
            lastDoneOn = parts.getOrNull(1)?.takeIf { Dates.parse(it) != null },
            addedOn = parts.getOrNull(2)?.takeIf { Dates.parse(it) != null }.orEmpty(),
            lastNudgedOn = parts.getOrNull(3)?.takeIf { Dates.parse(it) != null },
        )
    }

    /** `## heading` → the `- ` lines below it, until the next level-2 heading. */
    private fun readSections(body: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                current = line.removePrefix("##").trim()
                result.getOrPut(current) { mutableListOf() }
            } else if (current != null && line.startsWith("- ")) {
                result.getValue(current).add(line.removePrefix("- ").trim())
            }
        }
        return result
    }

    private fun number(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0

    private fun long(value: Any?): Long = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace("|", "／").trim()
}
