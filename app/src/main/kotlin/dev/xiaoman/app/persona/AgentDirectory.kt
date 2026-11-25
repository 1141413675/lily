package dev.xiaoman.app.persona

import android.content.Context
import dev.xiaoman.app.chat.EmojiStyle
import dev.xiaoman.app.life.LifeSettings
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.RelationSettings
import dev.xiaoman.app.settings.TtsProvider
import dev.xiaoman.app.settings.VoiceSettings
import dev.xiaoman.app.voice.VoiceRouting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Editable settings owned by a single added agent. Connection details stay in shared settings. */
@Serializable
data class AgentProfile(
    val id: String,
    val persona: PersonaSettings,
    val life: LifeSettings = LifeSettings(),
    val emojiStyle: EmojiStyle = EmojiStyle.NATURAL,
    val speechSpeed: Float = VoiceSettings().speed,
    val relation: RelationSettings = RelationSettings(proactive = false),
) {
    fun effectiveSettings(shared: AppSettings): AppSettings {
        val roleVoice = persona.voice.trim()
        val realtimeVoice = roleVoice.takeIf { VoiceRouting.realtimeSupports(shared.realtime.baseUrl, it) }
            ?: VoiceSettings.DEFAULT_REALTIME_VOICE
        // 音色只属于这个角色（persona.voice），不再写进全局朗读配置；
        // 全局只带语速，运行时 TtsSettings.request 会按角色音色/性别默认换算。
        val tts = shared.tts.withSpeed(speechSpeed)
        return shared.copy(
            persona = persona,
            life = life,
            emojiStyle = emojiStyle,
            relation = relation,
            tts = tts,
            realtime = shared.realtime.copy(voice = realtimeVoice),
            personaVoiceNotice = false,
        )
    }
}

/** Added agents have stable IDs. The original agent continues to use the installed settings. */
class AgentDirectory internal constructor(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loadFailed = false
    private val _agents = MutableStateFlow(load())
    val agents: StateFlow<List<AgentProfile>> = _agents.asStateFlow()

    fun find(id: String): AgentProfile? = _agents.value.firstOrNull { it.id == id }

    @Synchronized
    fun create(persona: PersonaSettings): AgentProfile? {
        val name = persona.nickname.trim()
        if (name.isEmpty() || name.length > PersonaSettings.MAX_NAME) return null
        val agent = AgentProfile(UUID.randomUUID().toString(), persona.copy(nickname = name))
        return agent.takeIf { save(_agents.value + agent) }
    }

    @Synchronized
    fun update(id: String, settings: AppSettings): Boolean {
        val existing = find(id) ?: return false
        val updated = existing.copy(
            persona = settings.persona.copy(nickname = settings.persona.nickname.trim()),
            life = settings.life,
            emojiStyle = settings.emojiStyle,
            speechSpeed = settings.tts.speed,
        )
        return replace(updated)
    }

    @Synchronized
    fun updateRelation(id: String, relation: RelationSettings): Boolean {
        val existing = find(id) ?: return false
        return replace(existing.copy(relation = relation))
    }

    private fun replace(agent: AgentProfile): Boolean =
        save(_agents.value.map { if (it.id == agent.id) agent else it })

    private fun load(): List<AgentProfile> = try {
        if (file.exists()) {
            json.decodeFromString<List<AgentProfile>>(file.readText()).also { stored ->
                val ids = stored.map { it.id }
                require(ids.distinct().size == ids.size)
                require(ids.all { UUID.fromString(it).toString() == it })
            }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        loadFailed = true
        emptyList()
    }

    private fun save(agents: List<AgentProfile>): Boolean {
        if (loadFailed) return false
        val directory = file.parentFile ?: return false
        var staging: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
            val snapshot = json.encodeToString(agents).toByteArray(Charsets.UTF_8)
            val temp = File.createTempFile("agent-directory-", ".tmp", directory)
            staging = temp
            FileOutputStream(temp).use { stream ->
                stream.write(snapshot)
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            return false
        } finally {
            staging?.let { runCatching { it.delete() } }
        }
        _agents.value = agents
        return true
    }

    companion object {
        const val PRIMARY_ID = "primary"

        fun create(context: Context): AgentDirectory =
            AgentDirectory(File(context.filesDir, "agent-directory.json"))
    }
}
