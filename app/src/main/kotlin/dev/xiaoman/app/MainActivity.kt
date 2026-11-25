package dev.xiaoman.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.ui.XiaomanRoot
import dev.xiaoman.app.ui.XiaomanTheme
import dev.xiaoman.app.voice.CallService

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as XiaomanApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as XiaomanApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as XiaomanApp).graph
        openCallFrom(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            XiaomanTheme(mode = settings.appearance.mode) { XiaomanRoot(graph) }
        }
    }

    /** 点状态栏的通话胶囊回到通话页；事件只消费一次，避免之后每次进前台都弹。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openCallFrom(intent)
    }

    private fun openCallFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(CallService.EXTRA_OPEN_CALL, false) != true) return
        intent.removeExtra(CallService.EXTRA_OPEN_CALL)
        (application as XiaomanApp).graph.openCall.value = true
    }
}
