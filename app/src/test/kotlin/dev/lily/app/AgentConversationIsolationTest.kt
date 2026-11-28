package dev.lily.app

import android.app.Application
import android.content.Context
import dev.lily.api.Note
import dev.lily.app.persona.AgentDirectory
import dev.lily.app.persona.PersonaSettings
import dev.lily.app.session.ChatItem
import dev.lily.app.settings.RelationSettings
import dev.lily.app.settings.SettingsStore
import dev.lily.app.voice.CallLog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentConversationIsolationTest {
    @Test fun `companionship stores and switches belong to their own conversation`() = runBlocking {
        val graph = AppGraph(RuntimeEnvironment.getApplication())
        try {
            val primary = assertNotNull(graph.conversation(AgentDirectory.PRIMARY_ID))
            val firstId = assertNotNull(graph.agentDirectory.create(PersonaSettings(nickname = "海风"))).id
            val secondId = assertNotNull(graph.agentDirectory.create(PersonaSettings(nickname = "月光"))).id
            val first = assertNotNull(graph.conversation(firstId))
            val second = assertNotNull(graph.conversation(secondId))

            val note = Note(title = "一起去海边", permalink = "moments/sea")
            first.memory.write(note)
            first.relationship.setAddress("海里的朋友")
            first.callLogs.save(CallLog(2L, 3L, 4L, "CONNECTED", null, emptyList()))
            first.session.postAssistant("只属于海风的夜间记录")
            assertFalse(first.dreamStore.config().enabled)
            first.dreamStore.saveConfig(first.dreamStore.config().copy(enabled = true))
            assertTrue(graph.agentDirectory.updateRelation(firstId, RelationSettings(proactive = true, maxPerDay = 5)))

            assertEquals(note.title, first.memory.read(note.permalink)?.title)
            assertEquals("海里的朋友", first.relationship.load().address)
            assertEquals(listOf(2L), first.callLogs.list().map { it.startedAt })
            assertTrue("只属于海风的夜间记录" in first.dreamStore.recentConversation())
            assertTrue(first.dreamStore.config().enabled)
            assertEquals(true, graph.agentDirectory.find(firstId)?.relation?.proactive)
            assertEquals(5, graph.agentDirectory.find(firstId)?.relation?.maxPerDay)
            assertNull(primary.memory.read(note.permalink))
            assertNull(second.memory.read(note.permalink))
            assertTrue(second.callLogs.list().isEmpty())
            assertTrue(second.dreamStore.recentConversation().isEmpty())
            assertFalse(second.dreamStore.config().enabled)
            // 主动默认全开；第二个人仍是自己的默认值，没有沾到第一个人改过的频率。
            assertEquals(RelationSettings(), graph.agentDirectory.find(secondId)?.relation)
        } finally {
            graph.scope.cancel()
        }
    }

    @Test fun `additional conversations retain their own rows and edits when switching roles`() {
        val context = RuntimeEnvironment.getApplication()
        // Simulate the installed primary settings without invoking the Android keystore in this test.
        fun installedSettings() = SettingsStore(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
            context.getSharedPreferences("conversation-test-secrets", Context.MODE_PRIVATE),
        )
        val primaryStore = installedSettings()
        val original = primaryStore.current.copy(persona = PersonaSettings(nickname = "小鱼", bio = "原人设"))
        primaryStore.save(original)

        val graph = AppGraph(context)
        try {
            val firstProfile = assertNotNull(graph.agentDirectory.create(PersonaSettings(nickname = "海风")))
            val secondProfile = assertNotNull(graph.agentDirectory.create(PersonaSettings(nickname = "月光")))
            val primary = assertNotNull(graph.conversation(AgentDirectory.PRIMARY_ID))
            val first = assertNotNull(graph.conversation(firstProfile.id))
            val second = assertNotNull(graph.conversation(secondProfile.id))
            assertSame(graph.session, primary.session)
            assertNotSame(primary.session, first.session)
            assertNotSame(first.session, second.session)

            primary.session.postAssistant("原对话")
            first.session.postAssistant("海风的对话")
            second.session.postAssistant("月光的对话")
            assertSame(first, graph.conversation(firstProfile.id))
            assertEquals(listOf("原对话"), primary.session.items.value.filterIsInstance<ChatItem.Assistant>().map { it.text })
            assertEquals(listOf("海风的对话"), first.session.items.value.filterIsInstance<ChatItem.Assistant>().map { it.text })
            assertEquals(listOf("月光的对话"), second.session.items.value.filterIsInstance<ChatItem.Assistant>().map { it.text })

            val edited = firstProfile.persona.copy(bio = "海风的新设定")
            assertTrue(first.saveSettings(firstProfile.effectiveSettings(original).copy(persona = edited)))
            assertEquals(edited, graph.agentDirectory.find(firstProfile.id)?.persona)
            assertEquals(secondProfile.persona, graph.agentDirectory.find(secondProfile.id)?.persona)
            assertEquals(original.persona, installedSettings().current.persona)
        } finally {
            graph.scope.cancel()
        }
    }
}
