package dev.xiaoman.app.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xiaoman.app.XiaomanApp

/** Fires the nightly dream alarm, then arms tomorrow's alarm. */
class DreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DREAM) return
        val graph = (context.applicationContext as XiaomanApp).graph
        graph.dream.runIfDue()
        graph.dream.schedule()
    }

    companion object {
        const val ACTION_DREAM = "dev.xiaoman.app.action.DREAM"
    }
}
