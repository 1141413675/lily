package dev.lily.app.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 一个群：名字 + 成员 + 谁是群主谁是管理员。
 *
 * 跟微信一样，群名默认由成员名字拼出来，群主和管理员可以改。
 * 消息不在这里存，跟私聊一样留在会话里。
 */
@Serializable
data class GroupChat(
    val id: String,
    /** null 表示还没人起名，界面上按成员名字拼一个。 */
    val name: String? = null,
    val memberIds: List<String>,
    val createdAt: Long,
    /** 建群的人。App 里「你」没有 id，统一记成 ME。 */
    val ownerId: String = ME,
    val admins: List<String> = emptyList(),
) {
    val autoNamed: Boolean get() = name.isNullOrBlank()

    /** 只有群主和管理员能改名字；成员只能看。 */
    fun canManage(by: String): Boolean = by == ownerId || admins.contains(by)

    /**
     * 默认群名：两三个人就把名字连起来，人多了取前两个接「等 N 人」，
     * 这样一眼能看出是谁的群，也不至于长到放不下。
     */
    fun displayName(nameOf: (String) -> String): String {
        if (!autoNamed) return name!!
        val display = memberIds.map(nameOf)
        val joined = when {
            display.size <= 3 -> display.joinToString("、")
            else -> "${display.take(2).joinToString("、")}等${display.size}人"
        }
        return if (joined.length <= MAX_NAME) joined else joined.take(MAX_NAME - 1) + "…"
    }

    companion object {
        const val ME = "me"
        const val MIN_MEMBERS = 2
        const val MAX_NAME = 12
    }
}

/**
 * 群列表的落盘与增删。写法和 AgentDirectory 一致：临时文件 + 原子替换，
 * 写不进去就整条不动，宁可失败也不留半截。
 */
class GroupChatStore internal constructor(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loadFailed = false
    private val _groups = MutableStateFlow(load())
    val groups: StateFlow<List<GroupChat>> = _groups.asStateFlow()

    fun find(id: String): GroupChat? = _groups.value.firstOrNull { it.id == id }

    /**
     * 建群。名字可以不填 —— 不填就用成员名字拼，跟微信一样。
     * 至少两个人才能叫群。
     */
    fun create(memberIds: List<String>, name: String? = null): GroupChat? {
        val members = memberIds.distinct()
        if (members.size < GroupChat.MIN_MEMBERS) return null
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null && trimmed.length > GroupChat.MAX_NAME) return null
        val group = GroupChat(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            memberIds = members,
            createdAt = System.currentTimeMillis(),
        )
        return group.takeIf { save(_groups.value + group) }
    }

    /** 改群名。群主和管理员才有这个权限，其他人原样退回 false。 */
    fun rename(id: String, name: String, by: String = GroupChat.ME): Boolean {
        val existing = find(id) ?: return false
        if (!existing.canManage(by)) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > GroupChat.MAX_NAME) return false
        return replace(existing.copy(name = trimmed))
    }

    /** 改回默认（成员名拼接）。 */
    fun resetName(id: String, by: String = GroupChat.ME): Boolean {
        val existing = find(id) ?: return false
        if (!existing.canManage(by)) return false
        return replace(existing.copy(name = null))
    }

    /** 设管理员。只有群主能设，也只有群主能撤。 */
    fun setAdmin(id: String, agentId: String, admin: Boolean, by: String = GroupChat.ME): Boolean {
        val existing = find(id) ?: return false
        if (by != existing.ownerId) return false
        if (!existing.memberIds.contains(agentId)) return false
        val next = if (admin) existing.admins + agentId else existing.admins - agentId
        return replace(existing.copy(admins = next.distinct()))
    }

    fun addMember(id: String, agentId: String): Boolean {
        val existing = find(id) ?: return false
        if (existing.memberIds.contains(agentId)) return true
        return replace(existing.copy(memberIds = existing.memberIds + agentId))
    }

    /**
     * 移出一个人。群只剩一个人时不动 —— 一个人不是群，
     * 那种情况应该由界面提示「解散」，而不是悄悄变成私聊。
     */
    fun removeMember(id: String, agentId: String): Boolean {
        val existing = find(id) ?: return false
        val remaining = existing.memberIds - agentId
        if (remaining.size < GroupChat.MIN_MEMBERS) return false
        return replace(
            existing.copy(
                memberIds = remaining,
                admins = existing.admins - agentId,
                ownerId = if (existing.ownerId == agentId) GroupChat.ME else existing.ownerId,
            )
        )
    }

    fun delete(id: String): Boolean {
        val remaining = _groups.value.filterNot { it.id == id }
        if (remaining.size == _groups.value.size) return false
        return save(remaining)
    }

    /** 她被人彻底删掉时，把她的 id 从所有群里摘掉，群本身不留空壳。 */
    fun forgetAgent(agentId: String): Boolean {
        val affected = _groups.value.filter { it.memberIds.contains(agentId) }
        if (affected.isEmpty()) return true
        val next = _groups.value.mapNotNull { group ->
            if (!group.memberIds.contains(agentId)) return@mapNotNull group
            val remaining = group.memberIds - agentId
            // 摘到只剩一个人，群就没意义了，直接散掉。
            group.takeIf { remaining.size >= GroupChat.MIN_MEMBERS }?.copy(
                memberIds = remaining,
                admins = group.admins - agentId,
            )
        }
        return save(next)
    }

    private fun replace(group: GroupChat): Boolean =
        save(_groups.value.map { if (it.id == group.id) group else it })

    private fun load(): List<GroupChat> = try {
        if (file.exists()) {
            json.decodeFromString(ListSerializer(GroupChat.serializer()), file.readText()).also { stored ->
                val ids = stored.map { it.id }
                require(ids.distinct().size == ids.size)
            }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        loadFailed = true
        emptyList()
    }

    private fun save(groups: List<GroupChat>): Boolean {
        if (loadFailed) return false
        val directory = file.parentFile ?: return false
        var staging: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
            val snapshot = json.encodeToString(ListSerializer(GroupChat.serializer()), groups)
                .toByteArray(Charsets.UTF_8)
            val temp = File.createTempFile("group-chats-", ".tmp", directory)
            staging = temp
            FileOutputStream(temp).use { stream ->
                stream.write(snapshot)
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            return false
        } finally {
            staging?.let { runCatching { it.delete() } }
        }
        _groups.value = groups
        return true
    }

    companion object {
        fun create(context: Context): GroupChatStore =
            GroupChatStore(File(context.filesDir, "group-chats.json"))
    }
}
