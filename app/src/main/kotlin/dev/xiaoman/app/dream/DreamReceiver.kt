package dev.xiaoman.app.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xiaoman.app.XiaomanApp
import dev.xiaoman.app.persona.AgentDirectory

/** Fires the nightly dream alarm, then arms tomorrow's alarm. */
class DreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DREAM) return
        val graph = (context.applicationContext as XiaomanApp).graph
        val id = intent.getStringExtra(EXTRA_AGENT_ID) ?: AgentDirectory.PRIMARY_ID
        val dream = graph.conversation(id)?.dream ?: return
        dream.runIfDue()
        dream.schedule()
    }

    companion object {
        const val ACTION_DREAM = "dev.xiaoman.app.action.DREAM"
        const val EXTRA_AGENT_ID = "dream_agent_id"
    }
}
