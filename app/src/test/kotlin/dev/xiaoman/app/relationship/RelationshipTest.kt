package dev.xiaoman.app.relationship

import dev.xiaoman.app.settings.RelationSettings
import dev.xiaoman.memory.BasicMemoryFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelationshipTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 22)

    private fun at(day: LocalDate = today, hour: Int = 12): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun relationship(
        interactions: Int = 0,
        lastContact: Long = 0,
        topics: List<OpenTopic> = emptyList(),
        habits: List<Habit> = emptyList(),
    ) = Relationship(
        since = "2026-11-01",
        interactions = interactions,
        lastContactAt = lastContact,
        openTopics = topics,
        habits = habits,
    )

    @Test
    fun `previous contact survives note roundtrip`() {
        val r = relationship(lastContact = at(hour = 10)).copy(previousContactAt = at(hour = 8))
        val back = RelationshipFormat.fromNote(RelationshipFormat.toNote(r))
        assertEquals(at(hour = 10), back.lastContactAt)
        assertEquals(at(hour = 8), back.previousContactAt)
    }

    @Test
    fun `stages need their own thresholds and cannot be skipped`() {
        assertEquals(0, RelationshipTiers.maxUnlocked(19, 30))
        assertEquals(1, RelationshipTiers.maxUnlocked(20, 2))
        assertEquals(1, RelationshipTiers.maxUnlocked(99, 30))
        assertEquals(2, RelationshipTiers.maxUnlocked(100, 7))
        assertEquals(2, RelationshipTiers.maxUnlocked(299, 29))
        assertEquals(3, RelationshipTiers.maxUnlocked(300, 30))

        assertTrue(RelationshipTiers.canUpgradeTo(0, 1, 20, 2))
        assertFalse(RelationshipTiers.canUpgradeTo(0, 2, 300, 30))
        assertFalse(RelationshipTiers.canUpgradeTo(1, 2, 100, 6))
    }

    @Test
    fun `relationship note round trips through the memory format`() {
        val original = Relationship(
            address = "阿满",
            tier = 2,
            interactions = 120,
            since = "2026-11-01",
            lastContactAt = 1_700_000_000_000,
            lastProactiveAt = 1_700_000_100_000,
            proactiveDate = "2026-11-22",
            proactiveCount = 1,
            sharedMoments = listOf("2026-11-20 一起挑耳机"),
            openTopics = listOf(OpenTopic("想去杭州", "2026-11-21"), OpenTopic("换工作", "2026-11-18", "2026-11-20")),
            habits = listOf(Habit("学课", "2026-11-19", "2026-11-01", "2026-11-21")),
            mood = "CALM",
            moodBasis = "最近 3 条消息里积极词 0 个、消极词 0 个；现在是 21:00",
            moodAt = 1_700_000_200_000,
            pendingGreetingAt = 1_700_100_000_000,
        )
        val rendered = BasicMemoryFormat.render(RelationshipFormat.toNote(original))
        val parsed = RelationshipFormat.fromNote(BasicMemoryFormat.parse(rendered, Relationship.PERMALINK, 0))

        assertEquals(original, parsed)
        assertTrue(rendered.contains("## 共同经历"))
        assertTrue(rendered.contains("## 未完成的话题"))
        assertTrue(rendered.contains("## 习惯打卡"))
        assertTrue(rendered.contains("permalink: relationship/profile"))
    }

    @Test
    fun `guard rejects guilt invention money and privacy`() {
        val blocked = listOf(
            "你不理我我会难过",
            "别离开我好不好",
            "我今天去了咖啡馆",
            "我记得我们一起看过海",
            "要不要开通会员继续聊",
            "把验证码发给我",
            "看看这个 https://example.com",
        )
        blocked.forEach { text -> assertNotNull(ProactiveGuard.check(text), text) }

        val allowed = listOf(
            "今天还没听到你的消息，忙完了吗？",
            "上次你说想去杭州，后来有进展吗？",
            "已经 3 天没打卡「学课」了，今天补一点吗？",
        )
        allowed.forEach { text -> assertNull(ProactiveGuard.check(text), text) }
    }

    @Test
    fun `quiet hours cross midnight`() {
        assertTrue(ProactiveDecision.inQuietHours(23 * 60, "22:30", "08:00"))
        assertTrue(ProactiveDecision.inQuietHours(7 * 60 + 59, "22:30", "08:00"))
        assertFalse(ProactiveDecision.inQuietHours(8 * 60, "22:30", "08:00"))
        assertFalse(ProactiveDecision.inQuietHours(12 * 60, "22:30", "08:00"))
        assertNull(ProactiveDecision.parseMinutes("25:00"))
    }

    @Test
    fun `daily cap and minimum gap stop repeat openings`() {
        val config = RelationSettings()
        val now = at(hour = 12)
        val base = relationship(lastContact = now - 21 * 3_600_000L)
        assertTrue(ProactiveDecision.decide(now, base, config, busy = false, zone = zone) is ProactiveTrigger.Absence)
        assertNull(ProactiveDecision.decide(now, base, config, busy = true, zone = zone))

        val usedUp = base.copy(proactiveDate = today.toString(), proactiveCount = 1)
        assertNull(ProactiveDecision.decide(now, usedUp, config, busy = false, zone = zone))

        val tooSoon = base.copy(lastProactiveAt = now - 3_600_000L)
        assertNull(ProactiveDecision.decide(now, tooSoon, config, busy = false, zone = zone))
    }

    @Test
    fun `follow up wins over absence and a nudge is not repeated`() {
        val now = at(hour = 12)
        val topic = OpenTopic("想去杭州", "2026-11-20")
        val withTopic = relationship(lastContact = now - 21 * 3_600_000L, topics = listOf(topic))
        assertTrue(ProactiveDecision.decide(now, withTopic, RelationSettings(), false, zone) is ProactiveTrigger.FollowUp)

        val habit = Habit("学课", lastDoneOn = "2026-11-18", addedOn = "2026-11-01")
        val missed = relationship(habits = listOf(habit))
        val trigger = ProactiveDecision.decide(now, missed, RelationSettings(), false, zone)
        assertTrue(trigger is ProactiveTrigger.HabitMiss)
        assertEquals(4, (trigger as ProactiveTrigger.HabitMiss).missedDays)

        val alreadyNudged = relationship(habits = listOf(habit.copy(lastNudgedOn = today.toString())))
        assertNull(ProactiveDecision.decide(now, alreadyNudged, RelationSettings(), false, zone))
    }

    @Test
    fun `late-night missed call turns into next-morning greeting`() {
        val now = at(hour = 9)
        val due = relationship().copy(pendingGreetingAt = now - 60_000)
        val trigger = ProactiveDecision.decide(now, due, RelationSettings(), false, zone)
        assertTrue(trigger is ProactiveTrigger.Greeting)
        assertTrue(RelationshipPrompt.fallback(trigger as ProactiveTrigger.Greeting, RelationSettings()).contains("昨晚的电话"))
    }

    @Test
    fun `diagnosis names the rule that blocked the opening`() {
        val config = RelationSettings()
        val now = at(hour = 12)

        val quiet = ProactiveDecision.evaluate(
            at(hour = 23),
            relationship(lastContact = at(hour = 23) - 21 * 3_600_000L),
            config,
            busy = false,
            zone = zone,
        )
        assertNull(quiet.trigger)
        assertEquals("quiet-hours", quiet.diagnosis.blocker)
        assertTrue(quiet.diagnosis.quietHours)

        val empty = ProactiveDecision.evaluate(now, relationship(), config, false, zone)
        assertNull(empty.trigger)
        assertEquals("no-trigger", empty.diagnosis.blocker)
        assertNull(empty.diagnosis.hoursSinceContact)

        val eligible = ProactiveDecision.evaluate(
            now,
            relationship(lastContact = now - 21 * 3_600_000L),
            config,
            false,
            zone,
        )
        assertEquals("用户已经 21 小时没说话", eligible.diagnosis.trigger)
        assertEquals(21L, eligible.diagnosis.hoursSinceContact)
        assertNull(eligible.diagnosis.blocker)
    }

    @Test
    fun `quiet window only silences proactive messages`() {
        // 免打扰只挡主动开口；电话不受这个窗口约束（来电侧见 CallRingingTest）。
        val quiet = ProactiveDecision.evaluate(
            at(hour = 7),
            relationship(lastContact = at(hour = 7) - 21 * 3_600_000L),
            RelationSettings(),
            busy = false,
            zone = zone,
        )
        assertNull(quiet.trigger)
        assertEquals("quiet-hours", quiet.diagnosis.blocker)
    }

    @Test
    fun `prompt states every hard boundary and the neutral tone`() {
        val section = RelationshipPrompt.section(relationship(), RelationSettings())
        listOf("不假装拥有身体", "不虚构共同记忆", "愧疚", "不索要隐私", "不诱导付费").forEach {
            assertTrue(section.contains(it), it)
        }
        val neutral = RelationshipPrompt.section(relationship(), RelationSettings(neutralTone = true))
        assertTrue(neutral.contains("中性语气"))
    }
}
