package dev.xiaoman.app.relationship

import dev.xiaoman.api.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Reads and writes [Relationship] through the existing [MemoryStore] (`relationship/profile.md`).
 * Every change is persisted immediately; a write failure keeps the in-memory value and is logged
 * by the caller, never thrown into a chat turn.
 */
class RelationshipStore(
    private val memory: MemoryStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow(Relationship(since = nowDate()))
    val state: StateFlow<Relationship> = _state.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    suspend fun load(): Relationship = lock.withLock { loadLocked() }

    /** Persists [transform]'s result; the memory write is best effort. */
    suspend fun update(transform: (Relationship) -> Relationship): Relationship = lock.withLock {
        val next = transform(if (loaded) _state.value else loadLocked())
            .let { if (it.since.isBlank()) it.copy(since = nowDate()) else it }
        _state.value = next
        runCatching { memory.write(RelationshipFormat.toNote(next)) }
        next
    }

    /** Called from the session's utterance hook for every user message; never blocks the turn. */
    fun record(role: String, text: String) {
        if (role != "user" || text.isBlank()) return
        scope.launch {
            runCatching {
                update { it.copy(previousContactAt = it.lastContactAt, interactions = it.interactions + 1, lastContactAt = clock()) }
            }
        }
    }

    suspend fun setAddress(address: String) = update { it.copy(address = address.trim().ifEmpty { Relationship.DEFAULT_ADDRESS }) }

    suspend fun addMoment(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        update { if (value in it.sharedMoments) it else it.copy(sharedMoments = it.sharedMoments + value) }
    }

    suspend fun removeMoment(text: String) = update { it.copy(sharedMoments = it.sharedMoments - text) }

    suspend fun addTopic(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        update {
            it.copy(openTopics = it.openTopics + OpenTopic(value, nowDate()))
        }
    }

    suspend fun removeTopic(topic: OpenTopic) = update { it.copy(openTopics = it.openTopics - topic) }

    suspend fun addHabit(name: String) {
        val value = name.trim()
        if (value.isEmpty()) return
        update {
            if (it.habits.any { habit -> habit.name == value }) it
            else it.copy(habits = it.habits + Habit(value, addedOn = nowDate()))
        }
    }

    suspend fun checkIn(name: String) = update { relationship ->
        relationship.copy(habits = relationship.habits.map { if (it.name == name) it.copy(lastDoneOn = nowDate()) else it })
    }

    suspend fun removeHabit(name: String) = update { it.copy(habits = it.habits.filterNot { habit -> habit.name == name }) }

    /** Raises one stage when the counters allow it; otherwise keeps the value. */
    suspend fun upgrade() = update { relationship ->
        val today = Dates.of(clock(), zone)
        val next = relationship.tier + 1
        if (RelationshipTiers.canUpgradeTo(relationship.tier, next, relationship.interactions, relationship.daysKnown(today))) {
            relationship.copy(tier = next)
        } else {
            relationship
        }
    }

    /** Always allowed: the user can step back one stage. */
    suspend fun rollback() = update { it.copy(tier = (it.tier - 1).coerceAtLeast(0)) }

    /** Mood is inferred at call time and reused for a while, so it does not change every turn. */
    suspend fun setMood(mood: String, basis: String, at: Long) =
        update { it.copy(mood = mood, moodBasis = basis, moodAt = at) }

    /** Late-night missed call: one opening after [at] (next morning), handled by the proactive engine. */
    suspend fun scheduleGreeting(at: Long) = update { it.copy(pendingGreetingAt = at) }

    /** Records one proactive opening and marks the followed-up topic / nudged habit. */
    suspend fun markProactive(now: Long, topic: OpenTopic?, habit: Habit?, clearGreeting: Boolean = false) = update { relationship ->
        val today = Dates.text(now, zone)
        relationship.copy(
            lastProactiveAt = now,
            proactiveDate = today,
            proactiveCount = if (relationship.proactiveDate == today) relationship.proactiveCount + 1 else 1,
            pendingGreetingAt = if (clearGreeting) 0 else relationship.pendingGreetingAt,
            openTopics = if (topic == null) relationship.openTopics else relationship.openTopics.map {
                if (it.text == topic.text && it.notedOn == topic.notedOn) it.copy(followedOn = today) else it
            },
            habits = if (habit == null) relationship.habits else relationship.habits.map {
                if (it.name == habit.name) it.copy(lastNudgedOn = today) else it
            },
        )
    }

    private suspend fun loadLocked(): Relationship {
        if (loaded) return _state.value
        val note = runCatching { memory.read(Relationship.PERMALINK) }.getOrNull()
        val relationship = if (note == null) {
            Relationship(since = nowDate()).also { initial ->
                runCatching { memory.write(RelationshipFormat.toNote(initial)) }
            }
        } else {
            RelationshipFormat.fromNote(note).let { if (it.since.isBlank()) it.copy(since = nowDate()) else it }
        }
        _state.value = relationship
        loaded = true
        return relationship
    }

    private fun nowDate(): String = Dates.text(clock(), zone)
}
