package dev.xiaoman.agent

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 时间同源：五个场景确认时段档与提示词约束一致（全部用固定时区，不依赖运行机器）。 */
class LocalTimeContextTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0, zone: ZoneId = shanghai, day: Int = 22): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun twoAmIsLateNight() {
        val text = LocalTimeContext.describe(at(2), shanghai)
        assertTrue("02:00" in text)
        assertTrue("深夜" in text)
        assertEquals(DayPart.LATE_NIGHT, LocalTimeContext.dayPart(2))
    }

    @Test fun sixAmIsDawn() {
        val text = LocalTimeContext.describe(at(6), shanghai)
        assertTrue("06:00" in text)
        assertTrue("清晨" in text)
        assertEquals(DayPart.DAWN, LocalTimeContext.dayPart(6))
    }

    @Test fun noonBreakIsNoon() {
        val text = LocalTimeContext.describe(at(12, 30), shanghai)
        assertTrue("12:30" in text)
        assertTrue("中午" in text)
        assertEquals(DayPart.NOON, LocalTimeContext.dayPart(12))
    }

    @Test fun elevenPmIsLateNight() {
        val text = LocalTimeContext.describe(at(23), shanghai)
        assertTrue("23:00" in text)
        assertTrue("深夜" in text)
        assertEquals(DayPart.LATE_NIGHT, LocalTimeContext.dayPart(23))
    }

    @Test fun utcMinusEightFollowsDeviceZoneAndDate() {
        val zone = ZoneId.of("UTC-8")
        // 同一时刻：上海 11-22 14:00（下午），UTC-8 是 11-21 22:00（晚上），日期也跟着退一天。
        val instant = at(14, 0, shanghai)
        val west = LocalTimeContext.describe(instant, zone)
        assertTrue("2026-11-21" in west)
        assertTrue("晚上" in west)
        assertTrue("下午" in LocalTimeContext.describe(instant, shanghai))
        assertEquals(DayPart.EVENING, LocalTimeContext.dayPart(18))
        assertEquals(DayPart.NIGHT, LocalTimeContext.dayPart(22))
    }

    @Test fun gapTextCoversFreshHoursAndDays() {
        val now = at(22)
        assertTrue("3 小时前" in LocalTimeContext.describe(now, shanghai, now - 3 * 3_600_000L))
        assertTrue("首次对话" in LocalTimeContext.describe(now, shanghai, null))
        assertTrue("2 天前" in LocalTimeContext.describe(now, shanghai, now - 50 * 3_600_000L))
        assertEquals("刚刚", LocalTimeContext.gapText(30_000L))
        assertEquals("1 分钟前", LocalTimeContext.gapText(60_000L))
    }

    @Test fun promptSectionCarriesTimeRules() {
        val section = Prompts.nowSection(LocalTimeContext.describe(at(23), shanghai))
        assertTrue(Prompts.NOW_HEADER in section)
        assertTrue("深夜" in section)
        assertTrue("不问候错时段" in section)
        assertTrue("不假定用户所在时区" in section)
        assertTrue("reminder_set" in section)
    }
}
