package dev.xiaoman.app.voice

import java.time.ZoneId
import kotlin.random.Random

/**
 * Ring timing and whether the agent picks up. Concrete ranges:
 * 兴致高 0.8–2s, 平静 2–4s, 低落 4–8s, 忙碌 8–12s. Hard cap 15s.
 * 免打扰只约束主动消息，不参与来电判断：夜里和白天用同一套接听规则。
 * Two consecutive misses force the third call to be answered.
 */
object CallRinging {
    const val MAX_DELAY_MS = 15_000L

    data class Plan(val delayMs: Long, val answer: Boolean)

    fun plan(
        mood: AgentMood,
        now: Long,
        missStreak: Int,
        random: Random = Random.Default,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val forced = missStreak >= 2
        val (low, high) = when (mood) {
            AgentMood.ENTHUSIASTIC -> 800L to 2_000L
            AgentMood.CALM -> 2_000L to 4_000L
            AgentMood.LOW -> 4_000L to 8_000L
            AgentMood.BUSY -> 8_000L to 12_000L
        }
        val delay = low + random.nextLong(high - low + 1)
        val missChance = when (mood) {
            AgentMood.BUSY -> 0.4
            AgentMood.LOW -> 0.1
            else -> 0.0
        }
        val answer = forced || random.nextDouble() >= missChance
        return Plan(delay.coerceAtMost(MAX_DELAY_MS), answer)
    }
}
