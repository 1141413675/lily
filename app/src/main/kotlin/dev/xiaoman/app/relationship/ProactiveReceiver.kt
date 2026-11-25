package dev.xiaoman.app.relationship

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xiaoman.app.XiaomanApp

/** Periodic proactive check; re-arms the next alarm every time it fires. */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val graph = (context.applicationContext as XiaomanApp).graph
        graph.proactive.check()
        graph.proactiveScheduler.schedule()
    }

    companion object {
        const val ACTION_CHECK = "dev.xiaoman.app.action.PROACTIVE"
    }
}
