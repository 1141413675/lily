package dev.lily.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.ui.LilyRoot
import dev.lily.app.ui.LilyTheme
import dev.lily.app.persona.AgentDirectory
import dev.lily.app.voice.CallService

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as LilyApp).graph.uiVisible.value = true
    }

    override fun onStop() {
        (application as LilyApp).graph.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as LilyApp).graph
        openDestinationFrom(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            LilyTheme(mode = settings.appearance.mode) { LilyRoot(graph) }
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
        val graph = (application as LilyApp).graph
        val openCall = intent.getBooleanExtra(CallService.EXTRA_OPEN_CALL, false)
        val openChat = intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)
        val chatAgentId = intent.getStringExtra(EXTRA_OPEN_CHAT_AGENT)
        intent.removeExtra(CallService.EXTRA_OPEN_CALL)
        intent.removeExtra(EXTRA_OPEN_CHAT)
        intent.removeExtra(EXTRA_OPEN_CHAT_AGENT)
        if (openCall) {
            graph.openCall.value = graph.activeCallAgentId()
        } else if (openChat) {
            val target = chatAgentId ?: AgentDirectory.PRIMARY_ID
            // 她已经不在列表里（进了最近删除或被彻底删掉），就别打开一个空对话。
            if (graph.isAgentAvailable(target)) graph.openChat.value = target
        }
    }

    companion object {
        internal const val EXTRA_OPEN_CHAT = "dev.lily.app.extra.OPEN_CHAT"
        internal const val EXTRA_OPEN_CHAT_AGENT = "dev.lily.app.extra.OPEN_CHAT_AGENT"
    }
}
