package dev.lily.app.relationship

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lily.app.LilyApp

/** Periodic proactive check; re-arms the next alarm every time it fires. */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val graph = (context.applicationContext as LilyApp).graph
        graph.checkProactiveAgents()
        graph.proactiveScheduler.schedule()
    }

    companion object {
        const val ACTION_CHECK = "dev.lily.app.action.PROACTIVE"
    }
}
