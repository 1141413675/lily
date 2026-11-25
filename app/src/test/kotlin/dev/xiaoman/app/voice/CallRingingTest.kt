package dev.xiaoman.app.voice

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallRingingTest {
    private val zone = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 22, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `mood sets the ringing range`() {
        val cases = mapOf(
            AgentMood.ENTHUSIASTIC to (800L to 2_000L),
            AgentMood.CALM to (2_000L to 4_000L),
            AgentMood.LOW to (4_000L to 8_000L),
            AgentMood.BUSY to (8_000L to 12_000L),
        )
        for ((mood, range) in cases) {
            repeat(20) { seed ->
                val plan = CallRinging.plan(mood, at(12), 0, Random(seed), zone)
                assertTrue(plan.delayMs in range.first..range.second, "$mood ${plan.delayMs}")
                assertTrue(plan.delayMs <= CallRinging.MAX_DELAY_MS)
                if (mood == AgentMood.ENTHUSIASTIC || mood == AgentMood.CALM) {
                    assertTrue(plan.answer, "$mood should answer in the daytime")
                }
            }
        }
    }

    @Test
    fun `quiet hours do not change how a call rings`() {
        // 免打扰窗口内（22:30–08:00）：接听与等待都按心情走，和白天一致，不因时段被拦。
        val plans = (0 until 40).map { seed -> CallRinging.plan(AgentMood.CALM, at(23, 30), 0, Random(seed), zone) }
        assertTrue(plans.all { it.answer })
        assertTrue(plans.all { it.delayMs in 2_000..4_000 })
        assertTrue(plans.all { it.delayMs <= CallRinging.MAX_DELAY_MS })

        val earlyMorning = (0 until 20).map { seed -> CallRinging.plan(AgentMood.ENTHUSIASTIC, at(7, 59), 0, Random(seed), zone) }
        assertTrue(earlyMorning.all { it.answer && it.delayMs in 800..2_000 })
    }

    @Test
    fun `third call after two misses is always answered`() {
        val plans = (0 until 40).map { seed -> CallRinging.plan(AgentMood.BUSY, at(23, 30), 2, Random(seed), zone) }
        assertTrue(plans.all { it.answer })
    }

    @Test
    fun `mood is inferred from wording and time, and busy overrides`() {
        val positive = MoodInference.infer(listOf("今天太开心了", "谢谢，顺利多了"), at(21), busy = false, zone)
        assertEquals(AgentMood.ENTHUSIASTIC, positive.mood)
        assertTrue(positive.basis.isNotBlank())

        val negative = MoodInference.infer(listOf("好累", "有点烦"), at(21), busy = false, zone)
        assertEquals(AgentMood.LOW, negative.mood)

        val calm = MoodInference.infer(emptyList(), at(21), busy = false, zone)
        assertEquals(AgentMood.CALM, calm.mood)

        assertEquals(AgentMood.BUSY, MoodInference.infer(listOf("今天太开心了"), at(21), busy = true, zone).mood)
        assertTrue(MoodInference.lateNight(at(23), zone))
        assertFalse(MoodInference.lateNight(at(22, 59), zone))
        assertEquals(at(9).let { it }, MoodInference.nextMorning(at(23, 30) - 24 * 3_600_000L, zone))
    }
}
