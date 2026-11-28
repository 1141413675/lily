package dev.lily.app.debug

import dev.lily.app.RunIds

/** A parsed debug-bridge broadcast (docs/ARCHITECTURE.md, Debug bridge). */
sealed class DebugCommand {
    data class Run(val goal: String, val skill: String?, val runId: String) : DebugCommand()

    /** A RUN that cannot run; the receiver still ends the run with Failed so `xiaoman run --follow` returns. */
    data class InvalidRun(val runId: String, val message: String) : DebugCommand()

    data object Stop : DebugCommand()

    data object ReloadSkills : DebugCommand()

    /** Types [text] into the chat as if the user sent it (recording demos without the soft keyboard). */
    data class Say(val text: String) : DebugCommand()

    /** Posts the call notification once, to check whether the system promotes it to a live update. */
    data object CallNotification : DebugCommand()

    /** Triggers one of her own abilities directly, without going through the model. */
    data class CompanionTool(val tool: String, val text: String) : DebugCommand()

    companion object {
        const val ACTION_RUN = "dev.lily.debug.RUN"
        const val ACTION_STOP = "dev.lily.debug.STOP"
        const val ACTION_RELOAD_SKILLS = "dev.lily.debug.RELOAD_SKILLS"
        const val ACTION_SAY = "dev.lily.debug.SAY"
        const val ACTION_CALL_NOTIFICATION = "dev.lily.debug.CALL_NOTIFICATION"
        const val ACTION_COMPANION_TOOL = "dev.lily.debug.COMPANION_TOOL"
        const val EXTRA_TEXT = "text"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_SKILL = "skill"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_TOOL = "tool"

        /**
         * @param extra string extra lookup (`Intent::getStringExtra`)
         * @param newRunId used when `run_id` is missing; an invalid `run_id` is rejected.
         * @return null for an unknown action.
         */
        fun parse(action: String?, extra: (String) -> String?, newRunId: () -> String): DebugCommand? = when (action) {
            ACTION_RUN -> parseRun(extra, newRunId)
            ACTION_STOP -> Stop
            ACTION_RELOAD_SKILLS -> ReloadSkills
            ACTION_SAY -> extra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let(::Say)
            ACTION_CALL_NOTIFICATION -> CallNotification
            ACTION_COMPANION_TOOL -> extra(EXTRA_TOOL)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { CompanionTool(it, extra(EXTRA_TEXT).orEmpty()) }
            else -> null
        }

        private fun parseRun(extra: (String) -> String?, newRunId: () -> String): DebugCommand {
            val rawId = extra(EXTRA_RUN_ID)?.trim()?.takeIf { it.isNotEmpty() }
            if (rawId != null && !RunIds.isValid(rawId)) {
                return InvalidRun(newRunId(), "invalid run_id '$rawId': use letters, digits, '.', '_' or '-'")
            }
            val runId = rawId ?: newRunId()
            val goal = extra(EXTRA_GOAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return InvalidRun(runId, "missing extra 'goal'")
            val skill = extra(EXTRA_SKILL)?.trim()?.takeIf { it.isNotEmpty() }
            return Run(goal, skill, runId)
        }
    }
}
