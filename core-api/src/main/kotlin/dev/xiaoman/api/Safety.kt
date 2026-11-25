package dev.xiaoman.api

/**
 * One thing the safety layer refused or asked about, for the local audit log.
 *
 * [stage] is `chat` (the user's message), `task` (a phone-task goal), `action` (one operator step)
 * or `confirm` (an approval request).
 */
data class SafetyBlock(
    val stage: String,
    val instruction: String,
    val rule: String,
    val matched: String = "",
)

/** Local, append-only audit of refused instructions and approval decisions. */
fun interface SafetyAudit {
    fun blocked(block: SafetyBlock)

    companion object {
        val NONE = SafetyAudit { }
    }
}
