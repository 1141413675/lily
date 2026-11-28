package dev.lily.app.persona

import android.content.Context
import dev.lily.app.chat.EmojiStyle
import dev.lily.app.life.LifeSettings
import dev.lily.app.settings.AppSettings
import dev.lily.app.settings.RelationSettings
import dev.lily.app.settings.VoiceVendor
import dev.lily.app.settings.VoiceSettings
import dev.lily.app.voice.VoiceRouting
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
    /** 和默认角色同一套默认值：新建的人也能主动找你，不存在先天的低一等。 */
    val relation: RelationSettings = RelationSettings(),
    /**
     * 进「最近删除」的时刻。null 表示她还在列表里；
     * 有值时只是从列表隐藏，她的对话、照片、记忆一样没删，所以随时能恢复。
     */
    val deletedAt: Long? = null,
    /** 拉黑：她的主动消息、来电、做梦推送都停掉，但历史与我的发言都还在。 */
    val blocked: Boolean = false,
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

    /**
     * 默认角色和她跟别人一样能被删除、被拉黑，只是她不在 [agents] 里
     * —— 她的设定住在 settings，索引里没有她的记录，所以标记单独存一份。
     */
    @Serializable
    data class PrimaryState(val deletedAt: Long? = null, val blocked: Boolean = false)

    private val primaryFile = File(file.parentFile, "primary-state.json")
    private val _primary = MutableStateFlow(loadPrimary())
    val primary: StateFlow<PrimaryState> = _primary.asStateFlow()

    fun primaryState(): PrimaryState = _primary.value

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

    /**
     * 删除的第一段：移入「最近删除」。
     * 只打时间戳，一个字节都不删 —— 恢复就是把时间戳抹掉。
     * 默认角色走同一套语义，只是标记存在 [primaryFile]。
     */
    @Synchronized
    fun trash(id: String, now: Long = System.currentTimeMillis()): Boolean {
        if (id == PRIMARY_ID) return trashPrimary(now)
        val existing = find(id) ?: return false
        if (existing.deletedAt != null) return false
        return replace(existing.copy(deletedAt = now))
    }

    /** 从「最近删除」捞回来。 */
    @Synchronized
    fun restore(id: String): Boolean {
        if (id == PRIMARY_ID) return restorePrimary()
        val existing = find(id) ?: return false
        if (existing.deletedAt == null) return false
        return replace(existing.copy(deletedAt = null))
    }

    /** 拉黑 / 解除拉黑。拉黑不隐藏她，只断她的主动。 */
    @Synchronized
    fun setBlocked(id: String, blocked: Boolean): Boolean {
        if (id == PRIMARY_ID) return setPrimaryBlocked(blocked)
        val existing = find(id) ?: return false
        if (existing.blocked == blocked) return true
        return replace(existing.copy(blocked = blocked))
    }

    @Synchronized
    fun trashPrimary(now: Long = System.currentTimeMillis()): Boolean {
        val state = _primary.value
        if (state.deletedAt != null) return false
        return savePrimary(state.copy(deletedAt = now))
    }

    @Synchronized
    fun restorePrimary(): Boolean {
        val state = _primary.value
        if (state.deletedAt == null) return false
        return savePrimary(state.copy(deletedAt = null))
    }

    @Synchronized
    fun setPrimaryBlocked(blocked: Boolean): Boolean {
        val state = _primary.value
        if (state.blocked == blocked) return true
        return savePrimary(state.copy(blocked = blocked))
    }

    /** 彻底删除之后，标记也一并清掉。 */
    @Synchronized
    fun clearPrimary(): Boolean = savePrimary(PrimaryState())

    /** 已经过了保留期的那些，供启动时清理。 */
    fun expired(keepMillis: Long, now: Long = System.currentTimeMillis()): List<AgentProfile> =
        _agents.value.filter { profile ->
            val since = profile.deletedAt ?: return@filter false
            now - since >= keepMillis
        }

    /** 到期的 id，默认角色也算在里面 —— 她跟别人一样会被清掉。 */
    fun expiredIds(keepMillis: Long, now: Long = System.currentTimeMillis()): List<String> {
        val others = explodedIds(keepMillis, now)
        val primarySince = _primary.value.deletedAt
        val primaryDue = primarySince != null && now - primarySince >= keepMillis
        return if (primaryDue) others + PRIMARY_ID else others
    }

    private fun explodedIds(keepMillis: Long, now: Long): List<String> =
        _agents.value.filter { profile ->
            val since = profile.deletedAt ?: return@filter false
            now - since >= keepMillis
        }.map { it.id }

    fun inTrash(): List<AgentProfile> =
        _agents.value.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }

    /**
     * 删除的第二段：真正从索引里摘掉。
     * 只在「彻底删除」与过期清理时调用，之后由 AppGraph 去清文件。
     */
    @Synchronized
    fun remove(id: String): Boolean {
        if (id == PRIMARY_ID) return false
        val remaining = _agents.value.filterNot { it.id == id }
        if (remaining.size == _agents.value.size) return false
        return save(remaining)
    }

    private fun replace(agent: AgentProfile): Boolean =
        save(_agents.value.map { if (it.id == agent.id) agent else it })

    private fun loadPrimary(): PrimaryState = try {
        if (primaryFile.exists()) json.decodeFromString<PrimaryState>(primaryFile.readText()) else PrimaryState()
    } catch (_: Exception) {
        PrimaryState()
    }

    private fun savePrimary(state: PrimaryState): Boolean {
        val directory = primaryFile.parentFile ?: return false
        var staging: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
            val temp = File.createTempFile("primary-state-", ".tmp", directory)
            staging = temp
            FileOutputStream(temp).use { stream ->
                stream.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), primaryFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            return false
        } finally {
            staging?.let { runCatching { it.delete() } }
        }
        _primary.value = state
        return true
    }

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
