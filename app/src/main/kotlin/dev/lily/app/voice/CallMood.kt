package dev.lily.app.voice

import java.time.Instant
import java.time.ZoneId

/** The mood the call uses for ring timing; inferred, never invented. */
enum class AgentMood {
    ENTHUSIASTIC, CALM, LOW, BUSY;

    companion object {
        fun from(value: String?): AgentMood? = entries.firstOrNull { it.name == value }
    }
}

/** Mood plus the short evidence shown on the relationship screen. */
data class MoodReading(val mood: AgentMood, val basis: String)

/**
 * Infers the mood from recent user wording and the clock, at most once per [FRESH_MS] (the caller
 * reuses the stored value), so it cannot change every turn. The basis is always written down.
 */
object MoodInference {
    const val FRESH_MS = 30 * 60 * 1000L

    private val positive = listOf("开心", "高兴", "哈哈", "喜欢", "谢谢", "太好了", "顺利", "不错", "棒", "期待", "好呀", "嘿嘿")
    private val negative = listOf("累", "烦", "难过", "生气", "焦虑", "不开心", "压力", "加班", "失眠", "崩溃", "委屈", "没精神", "郁闷")

    fun infer(userTexts: List<String>, now: Long, busy: Boolean, zone: ZoneId = ZoneId.systemDefault()): MoodReading {
        if (busy) return MoodReading(AgentMood.BUSY, "此刻有任务在跑")
        val recent = userTexts.takeLast(6).joinToString(" ")
        val time = Instant.ofEpochMilli(now).atZone(zone)
        val clock = "%02d:%02d".format(time.hour, time.minute)
        if (recent.isBlank()) return MoodReading(AgentMood.CALM, "最近还没有对话；现在是 $clock")
        val up = positive.sumOf { word -> recent.windowed(word.length).count { it == word } }
        val down = negative.sumOf { word -> recent.windowed(word.length).count { it == word } }
        val mood = when {
            up >= 2 && up > down -> AgentMood.ENTHUSIASTIC
            down >= 2 && down > up -> AgentMood.LOW
            else -> AgentMood.CALM
        }
        return MoodReading(mood, "最近 ${userTexts.takeLast(6).size} 条消息里积极词 $up 个、消极词 $down 个；现在是 $clock")
    }

    /** 23:00–06:00: mostly no answer, and one opening the next morning. */
    fun lateNight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return hour >= 23 || hour < 6
    }

    /** Local 09:00 of the day after [now]. */
    fun nextMorning(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}
