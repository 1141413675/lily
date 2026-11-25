package dev.xiaoman.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 设备本地时间与时段：提示词里的「现在几点」一律由这里生成，
 * 模型不得自行推断或编造（见 [RULES]）。后台调度也共享同一套时段划分。
 */
enum class DayPart(val label: String, val range: String) {
    DAWN("清晨", "5:00–8:59"),
    MORNING("上午", "9:00–11:59"),
    NOON("中午", "12:00–13:59"),
    AFTERNOON("下午", "14:00–17:59"),
    EVENING("傍晚", "18:00–19:59"),
    NIGHT("晚上", "20:00–22:59"),
    LATE_NIGHT("深夜", "23:00–4:59"),
}

object LocalTimeContext {
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.SIMPLIFIED_CHINESE)

    /** 时间常识约束：写进系统提示词，不依赖模型自觉。 */
    const val RULES = """- 时间以这里为准，不要自行推断或编造当前时间。
- 不问候错时段：清晨不说「晚安」，深夜不催人早起，也不假定用户所在时区。"""

    fun dayPart(hour: Int): DayPart = when (hour) {
        in 5..8 -> DayPart.DAWN
        in 9..11 -> DayPart.MORNING
        in 12..13 -> DayPart.NOON
        in 14..17 -> DayPart.AFTERNOON
        in 18..19 -> DayPart.EVENING
        in 20..22 -> DayPart.NIGHT
        else -> DayPart.LATE_NIGHT
    }

    /** 例如 "2026-11-22 22:14 星期六 · 晚上（20:00–22:59）"，可附一行「距上次互动」。 */
    fun describe(
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        lastInteractionMillis: Long? = null,
    ): String {
        val zoned: ZonedDateTime = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val part = dayPart(zoned.hour)
        val head = "${zoned.format(display)} · ${part.label}（${part.range}）"
        val gap = lastInteractionMillis?.takeIf { it > 0 }?.let { last ->
            "- 距上次互动：${gapText(nowMillis - last)}"
        }
        return if (gap == null) "$head\n- 距上次互动：首次对话" else "$head\n$gap"
    }

    /** 粗粒度间隔：刚刚 / N 分钟前 / N 小时前 / N 天前。 */
    fun gapText(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 24 * 60 -> "${minutes / 60} 小时前"
            else -> "${minutes / (24 * 60)} 天前"
        }
    }
}
