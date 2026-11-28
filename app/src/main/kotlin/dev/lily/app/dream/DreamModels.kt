package dev.lily.app.dream

import kotlinx.serialization.Serializable

/** One observation a dream proposes to write into a note. */
@Serializable
data class DreamObservation(
    val category: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val context: String? = null,
)

/** A general rule induced from several memories; only confirmed rules reach the system prompt. */
@Serializable
data class DreamRule(
    /** Stable slug of the rule, e.g. `preference-spicy`; a rejected key is never proposed again. */
    val key: String,
    val kind: String = "preference",
    val statement: String,
    val confidence: Double = 0.7,
    /** Memory permalinks or conversation dates that support the rule. */
    val evidence: List<String> = emptyList(),
)

/** What applying an item changed, so low-risk auto changes can be undone as a batch. */
@Serializable
data class DreamUndo(
    val added: List<DreamObservation> = emptyList(),
    val createdNote: Boolean = false,
    /** Absolute paths of notes moved to the recycle bin; restored on undo. */
    val trashFiles: List<String> = emptyList(),
)

@Serializable
data class DreamItem(
    val id: String,
    /** remember | merge | forget | contradiction | rule */
    val type: String,
    val title: String,
    val detail: String = "",
    /** Note the item writes to (remember/merge/rule target, forget/contradiction target). */
    val permalink: String = "",
    val target: String = "",
    val observations: List<DreamObservation> = emptyList(),
    /** Notes merged away or cited by the item. */
    val sources: List<String> = emptyList(),
    /** Why: newer/more specific/source conversation, required for contradictions. */
    val reason: String = "",
    /** Contradiction: exact old observation contents to remove from the note. */
    val remove: List<String> = emptyList(),
    val rule: DreamRule? = null,
    val selected: Boolean = true,
    val applied: Boolean = false,
    /** Applied by the low-risk auto mode, undoable as one batch. */
    val auto: Boolean = false,
    val undo: DreamUndo? = null,
    val error: String? = null,
)

@Serializable
data class DreamDay(
    /** Local date `yyyy-MM-dd`. */
    val date: String,
    val createdAt: Long,
    val summary: String = "",
    /** proposed | applied | failed */
    val status: String = "proposed",
    val error: String? = null,
    val appliedAt: Long? = null,
    val calls: Int = 0,
    val items: List<DreamItem> = emptyList(),
) {
    val pending: Int get() = items.count { it.selected && !it.applied }
    val autoApplied: Boolean get() = items.any { it.auto && it.applied }
}

@Serializable
data class DreamConfig(
    val enabled: Boolean = true,
    /** Local `HH:mm`. */
    val time: String = "23:30",
    /** Auto-apply remember/merge only; forget, contradictions and rules always need a tap. */
    val autoLowRisk: Boolean = false,
    /** Last date a dream ran, so a day runs at most once. */
    val lastDreamDate: String = "",
)

/** A note in the recycle bin, restorable from the dream screen. */
@Serializable
data class TrashEntry(
    val date: String,
    /** Path relative to the dream folder. */
    val file: String,
    val permalink: String,
    val title: String,
    val deletedAt: Long,
    val reason: String = "",
)

/** One active or rejected rule as shown in the dream screen. */
data class RuleView(
    val permalink: String,
    val key: String,
    val title: String,
    val statement: String,
    val kind: String,
    val status: String,
    val confidence: Double,
    val evidence: List<String>,
    val created: String,
)

object DreamTypes {
    const val REMEMBER = "remember"
    const val MERGE = "merge"
    const val FORGET = "forget"
    const val CONTRADICTION = "contradiction"
    const val RULE = "rule"

    const val PROPOSED = "proposed"
    const val APPLIED = "applied"
    const val FAILED = "failed"

    /** Types the auto mode may apply without a tap (still logged and undoable). */
    val LOW_RISK = setOf(REMEMBER, MERGE)

    /** Rules below this confidence are stored but never put into the system prompt. */
    const val MIN_RULE_CONFIDENCE = 0.6
}
