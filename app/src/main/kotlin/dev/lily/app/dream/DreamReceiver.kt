package dev.lily.app.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lily.app.LilyApp
import dev.lily.app.persona.AgentDirectory

/** Fires the nightly dream alarm, then arms tomorrow's alarm. */
class DreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DREAM) return
        val graph = (context.applicationContext as LilyApp).graph
        val id = intent.getStringExtra(EXTRA_AGENT_ID) ?: AgentDirectory.PRIMARY_ID
        val dream = graph.conversation(id)?.dream ?: return
        dream.runIfDue()
        dream.schedule()
    }

    companion object {
        const val ACTION_DREAM = "dev.lily.app.action.DREAM"
        const val EXTRA_AGENT_ID = "dream_agent_id"
    }
}
