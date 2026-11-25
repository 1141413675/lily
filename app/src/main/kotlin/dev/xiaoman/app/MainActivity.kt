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
import dev.xiaoman.app.persona.AgentDirectory
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
        openDestinationFrom(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            XiaomanTheme(mode = settings.appearance.mode) { XiaomanRoot(graph) }
        }
    }

    /** 通知指定的是会话或正在进行的通话，导航只消费一次。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDestinationFrom(intent)
    }

    private fun openDestinationFrom(intent: Intent?) {
        if (intent == null) return
        val graph = (application as XiaomanApp).graph
        val openCall = intent.getBooleanExtra(CallService.EXTRA_OPEN_CALL, false)
        val openChat = intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)
        val chatAgentId = intent.getStringExtra(EXTRA_OPEN_CHAT_AGENT)
        intent.removeExtra(CallService.EXTRA_OPEN_CALL)
        intent.removeExtra(EXTRA_OPEN_CHAT)
        intent.removeExtra(EXTRA_OPEN_CHAT_AGENT)
        if (openCall) {
            graph.openCall.value = graph.activeCallAgentId()
        } else if (openChat) {
            graph.openChat.value = chatAgentId ?: AgentDirectory.PRIMARY_ID
        }
    }

    companion object {
        internal const val EXTRA_OPEN_CHAT = "dev.xiaoman.app.extra.OPEN_CHAT"
        internal const val EXTRA_OPEN_CHAT_AGENT = "dev.xiaoman.app.extra.OPEN_CHAT_AGENT"
    }
}
