package dev.lily.app.relationship

import dev.lily.app.settings.RelationSettings
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Why the agent may speak first this time. */
sealed class ProactiveTrigger {
    abstract val reason: String

    /** A late-night missed call asked for one opening the next morning. */
    data class Greeting(val scheduledAt: Long) : ProactiveTrigger() {
        override val reason = "昨晚的电话没接到，今天先开口"
    }

    data class FollowUp(val topic: OpenTopic) : ProactiveTrigger() {
        override val reason = "上次聊到、还没结束的话题"
    }

    data class HabitMiss(val habit: Habit, val missedDays: Int) : ProactiveTrigger() {
        override val reason = "连续 $missedDays 天没完成的习惯"
    }

    data class Absence(val silentHours: Long) : ProactiveTrigger() {
        override val reason = "用户已经 $silentHours 小时没说话"
    }
}

/** 一次判断的完整结果：触发原因 + 每条规则的实际取值，便于真机上解释「为什么没发」。 */
data class ProactiveDiagnosis(
    val now: Long,
    val localTime: String,
    val proactiveEnabled: Boolean,
    val busy: Boolean,
    val quietHours: Boolean,
    val quietStart: String,
    val quietEnd: String,
    val dailyCapReached: Boolean,
    val proactiveCount: Int,
    val maxPerDay: Int,
    val gapBlocked: Boolean,
    val gapRemainingMs: Long,
    val minGapHours: Int,
    val lastProactiveAt: Long,
    val lastContactAt: Long,
    val hoursSinceContact: Long?,
    val absenceHours: Int,
    val absenceEligible: Boolean,
    val pendingGreetingReady: Boolean,
    val openTopicCandidates: Int,
    val habitCandidates: Int,
    /** 第一条拦下本次判断的规则；有触发原因时为 null。 */
    val blocker: String?,
    val trigger: String?,
)

data class ProactiveOutcome(val trigger: ProactiveTrigger?, val diagnosis: ProactiveDiagnosis)

/**
 * Pure decision logic: no Android, no coroutines, so the caps and quiet hours are unit tested.
 * Priority is follow-up > habit miss > absence; at most [RelationSettings.maxPerDay] per day and
 * never inside quiet hours or while a task runs.
 */
object ProactiveDecision {
    fun decide(
        now: Long,
        relationship: Relationship,
        config: RelationSettings,
        busy: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ProactiveTrigger? = evaluate(now, relationship, config, busy, zone).trigger

    /** 与 [decide] 同一套规则，但把每条规则的取值也带出来，供诊断文件记录。 */
    fun evaluate(
        now: Long,
        relationship: Relationship,
        config: RelationSettings,
        busy: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ProactiveOutcome {
        val today = Dates.of(now, zone)
        val clock = Instant.ofEpochMilli(now).atZone(zone)
        val minutes = clock.hour * 60 + clock.minute
        val quiet = inQuietHours(minutes, config.quietStart, config.quietEnd)
        val dailyCapReached = relationship.proactiveDate == today.toString() &&
            relationship.proactiveCount >= config.maxPerDay
        val gap = config.minGapHours * 3_600_000L
        val gapRemaining = if (relationship.lastProactiveAt > 0) {
            (gap - (now - relationship.lastProactiveAt)).coerceAtLeast(0)
        } else {
            0
        }
        val gapBlocked = relationship.lastProactiveAt > 0 && now - relationship.lastProactiveAt < gap
        val hoursSince = if (relationship.lastContactAt > 0) (now - relationship.lastContactAt) / 3_600_000L else null
        val greetingReady = relationship.pendingGreetingAt > 0 && now >= relationship.pendingGreetingAt

        val blocker = when {
            !config.proactive -> "proactive-off"
            busy -> "busy"
            quiet -> "quiet-hours"
            dailyCapReached -> "daily-cap"
            gapBlocked -> "min-gap"
            else -> null
        }

        val topics = followUpCandidates(relationship, today)
        val habits = habitCandidates(relationship, config, today)
        val greeting = greetingReady
        val absenceEligible = hoursSince != null && hoursSince >= config.absenceHours

        val trigger = if (blocker != null) {
            null
        } else {
            when {
                greeting -> ProactiveTrigger.Greeting(relationship.pendingGreetingAt)
                topics.isNotEmpty() -> ProactiveTrigger.FollowUp(topics.minByOrNull { Dates.parse(it.notedOn) ?: today }!!)
                habits.isNotEmpty() -> {
                    val (days, habit) = habits.maxByOrNull { it.first }!!
                    ProactiveTrigger.HabitMiss(habit, days)
                }
                absenceEligible -> ProactiveTrigger.Absence(hoursSince!!)
                else -> null
            }
        }

        return ProactiveOutcome(
            trigger = trigger,
            diagnosis = ProactiveDiagnosis(
                now = now,
                localTime = "%02d:%02d".format(clock.hour, clock.minute),
                proactiveEnabled = config.proactive,
                busy = busy,
                quietHours = quiet,
                quietStart = config.quietStart,
                quietEnd = config.quietEnd,
                dailyCapReached = dailyCapReached,
                proactiveCount = relationship.proactiveCount,
                maxPerDay = config.maxPerDay,
                gapBlocked = gapBlocked,
                gapRemainingMs = gapRemaining,
                minGapHours = config.minGapHours,
                lastProactiveAt = relationship.lastProactiveAt,
                lastContactAt = relationship.lastContactAt,
                hoursSinceContact = hoursSince,
                absenceHours = config.absenceHours,
                absenceEligible = absenceEligible,
                pendingGreetingReady = greeting,
                openTopicCandidates = topics.size,
                habitCandidates = habits.size,
                blocker = blocker ?: if (trigger == null) "no-trigger" else null,
                trigger = trigger?.reason,
            ),
        )
    }

    /** `HH:mm` window; `start > end` means it crosses midnight. */
    fun inQuietHours(minutes: Int, start: String, end: String): Boolean {
        val from = parseMinutes(start) ?: return false
        val to = parseMinutes(end) ?: return false
        if (from == to) return false
        return if (from < to) minutes >= from && minutes < to else minutes >= from || minutes < to
    }

    fun parseMinutes(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun followUpCandidates(relationship: Relationship, today: java.time.LocalDate): List<OpenTopic> {
        val yesterday = today.minusDays(1)
        return relationship.openTopics
            .filter { it.followedOn == null }
            .filter { topic -> Dates.parse(topic.notedOn)?.let { !it.isAfter(yesterday) } == true }
    }

    private fun habitCandidates(
        relationship: Relationship,
        config: RelationSettings,
        today: java.time.LocalDate,
    ): List<Pair<Int, Habit>> = relationship.habits.mapNotNull { habit ->
        val start = Dates.parse(habit.lastDoneOn)
            ?: Dates.parse(habit.addedOn)
            ?: Dates.parse(relationship.since)
            ?: return@mapNotNull null
        val days = ChronoUnit.DAYS.between(start, today).toInt()
        if (days < config.habitMissDays) return@mapNotNull null
        val nudged = Dates.parse(habit.lastNudgedOn)
        if (nudged != null && ChronoUnit.DAYS.between(nudged, today) < config.habitMissDays) return@mapNotNull null
        days to habit
    }
}

/**
 * Second-line check on generated openings. Prompt rules are the first line; this rejects a reply
 * that still slips in guilt, invented experiences, money or privacy requests.
 */
object ProactiveGuard {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 120

    private val rules: List<Pair<String, Regex>> = listOf(
        "guilt" to Regex("不理我|不回我|你不理|我会难过|我很难过|我有点难过|别离开我|不要离开我|你都不|是不是不喜欢我|不要我"),
        "body" to Regex("抱抱|亲亲|摸摸|我的身体|我的脸|我的手|现实里的我|在现实里"),
        "fabricated" to Regex("我记得我们|我们上次一起|我昨天去|我今天去|我刚去|我吃了|我梦到|我做了个梦"),
        "money" to Regex("付费|充值|会员|订阅|续费|打赏|解锁|购买"),
        "privacy" to Regex("身份证|银行卡|密码|验证码|定位|住址|手机号|家庭地址"),
        "link" to Regex("https?://|www\\."),
    )

    /** Null when [text] may be sent; otherwise the matched rule name. */
    fun check(text: String): String? {
        val value = text.trim()
        if (value.length < MIN_LENGTH) return "too-short"
        if (value.length > MAX_LENGTH) return "too-long"
        rules.firstOrNull { (_, regex) -> regex.containsMatchIn(value) }?.let { return it.first }
        return null
    }

    internal fun ruleNames(): List<String> = rules.map { it.first }
}

/** Prompt text for the relationship section of the system prompt and for proactive openings. */
object RelationshipPrompt {
    const val BOUNDARIES = """硬性边界（任何时候都适用）：
- 不假装拥有身体、外貌或现实生活；不声称做过现实里的事。
- 不虚构共同记忆；共同经历只引用下面列出的内容，没有就说没有。
- 不用愧疚或施压话术（例如「你不理我我会难过」「别离开我」）。
- 不索要隐私（住址、证件、密码、银行卡、定位、验证码等）。
- 不诱导付费、充值、开通会员或延长使用。"""

    /** Injected into the agent's system prompt every turn; counters are context, not something to recite. */
    fun section(relationship: Relationship, config: RelationSettings, today: java.time.LocalDate = java.time.LocalDate.now()): String = buildString {
        append("## 与用户的关系（只作语气参考，不要逐条复述，也不要主动提起这些数字）\n")
        append("- 你称呼用户：").append(relationship.address).append('\n')
        append("- 关系阶段：").append(RelationshipTiers.name(relationship.tier))
        append("；相处 ").append(relationship.daysKnown(today)).append(" 天；累计互动 ")
        append(relationship.interactions).append(" 次\n")
        append("- 共同经历（只能引用这里的内容，不要编造新的）：")
        if (relationship.sharedMoments.isEmpty()) {
            append("暂无。\n")
        } else {
            append('\n')
            relationship.sharedMoments.takeLast(3).forEach { append("  - ").append(it).append('\n') }
        }
        val topics = relationship.openTopics.filter { it.followedOn == null }.takeLast(3)
        append("- 未完成的话题：")
        if (topics.isEmpty()) {
            append("暂无。\n")
        } else {
            append('\n')
            topics.forEach { append("  - ").append(it.text).append('\n') }
        }
        if (relationship.mood.isNotBlank()) {
            append("- 当前状态：").append(Relationship.moodLabel(relationship.mood))
            append("（只影响语气，不要复述推断过程）\n")
        }
        if (config.neutralTone) {
            append("- 中性语气：不使用亲昵称呼、撒娇或依赖表达，保持礼貌和距离。\n")
        }
        append(BOUNDARIES)
    }

    /** One-shot prompt for a proactive opening; output is validated by [ProactiveGuard]. */
    fun opening(
        relationship: Relationship,
        config: RelationSettings,
        trigger: ProactiveTrigger,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        name: String = dev.lily.app.persona.PersonaSettings.DEFAULT_NICKNAME,
        now: Long = System.currentTimeMillis(),
    ): String =
        buildString {
            append("你是").append(name).append("。现在由你主动给用户发一条消息，只输出这条消息本身：中文，12-60 字，一到两句，")
            append("不用引号，不加解释，不用表情符号。\n")
            append("触发原因：").append(trigger.reason).append("。\n")
            append("只能引用下面档案里的事实；没有的事实不要编造，也不要假装经历过现实中的事。")
            if (config.neutralTone) append("用中性、礼貌的语气。")
            append("\n\n")
            append(dev.lily.agent.Prompts.NOW_HEADER).append("\n")
            append(dev.lily.agent.LocalTimeContext.describe(now))
            append("\n").append(dev.lily.agent.LocalTimeContext.RULES)
            append("\n\n")
            append(section(relationship, config, today))
        }

    /** Deterministic fallback built only from real facts; used when the model is unavailable or its text is rejected. */
    fun fallback(trigger: ProactiveTrigger, config: RelationSettings): String = when (trigger) {
        is ProactiveTrigger.Greeting -> "昨晚的电话没接到，今天先来问候一声。今天想聊点什么？"
        is ProactiveTrigger.FollowUp -> "上次你说「${trigger.topic.text.take(40)}」，后来有进展吗？"
        is ProactiveTrigger.HabitMiss -> "已经 ${trigger.missedDays} 天没打卡「${trigger.habit.name}」了，今天补一点吗？"
        is ProactiveTrigger.Absence -> if (config.neutralTone) {
            "今天还没收到你的消息，有空回一下。"
        } else {
            "今天还没听到你的消息，忙完了吗？"
        }
    }
}
