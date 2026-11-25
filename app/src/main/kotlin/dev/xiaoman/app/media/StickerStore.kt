package dev.xiaoman.app.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 本机表情素材库：`<files>/stickers/<id>.<ext>` 存图片，`index.json` 存名称、标签、用量。
 * 不联网、不同步；删除只影响本机素材库，聊天记录里的旧消息不受影响。
 */
class StickerStore(private val dir: File) {

    data class Sticker(
        val id: String,
        val file: String,
        val name: String,
        val tags: List<String>,
        val animated: Boolean,
        val uses: Int,
    )

    companion object {
        /** 设计稿里的四个发送标签；导入默认进 [UNSORTED]。 */
        val PRESET_TAGS = listOf("常用", "安抚", "开心", "晚安")
        const val UNSORTED = "未分组"
        const val ALL = "全部"
    }

    private val indexFile = File(dir, "index.json")
    private val _stickers = MutableStateFlow<List<Sticker>>(emptyList())
    val stickers: StateFlow<List<Sticker>> = _stickers.asStateFlow()

    init {
        _stickers.value = read()
    }

    /** 把所有出现过的标签按预设顺序排前面，其余按字母序。 */
    fun tags(): List<String> {
        val seen = _stickers.value.flatMap { it.tags }.toSet()
        val preset = PRESET_TAGS.filter { it in seen }
        val rest = (seen - preset.toSet()).sorted()
        return preset + rest
    }

    /**
     * 复制 [source] 到素材库并归到「未分组」。返回新增的表情；源文件不存在或复制失败时为 null。
     * [name] 取文件名（去掉扩展名），过长会截断到 10 个字。
     */
    @Synchronized
    fun add(source: File, name: String, animated: Boolean): Sticker? {
        if (!source.isFile) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val ext = source.extension.lowercase().ifBlank { "png" }
        val id = "st-" + UUID.randomUUID().toString().take(8)
        val target = File(dir, "$id.$ext")
        try {
            source.copyTo(target, overwrite = true)
        } catch (e: Exception) {
            return null
        }
        val sticker = Sticker(
            id = id,
            file = target.absolutePath,
            name = name.trim().take(10).ifBlank { "表情" },
            tags = listOf(UNSORTED),
            animated = animated,
            uses = 0,
        )
        _stickers.value = _stickers.value + sticker
        persist()
        return sticker
    }

    /** 每发送一次用量加一；面板的「常用」就是按用量排。 */
    @Synchronized
    fun bumpUse(id: String) {
        _stickers.value = _stickers.value.map { if (it.id == id) it.copy(uses = it.uses + 1) else it }
        persist()
    }

    /** 覆盖标签集合；空集合回落到「未分组」。 */
    @Synchronized
    fun setTags(id: String, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        _stickers.value = _stickers.value.map {
            if (it.id == id) it.copy(tags = clean.ifEmpty { listOf(UNSORTED) }) else it
        }
        persist()
    }

    /** 删除条目与文件；聊天里已发出的旧消息仍指向旧路径，不做回溯。 */
    @Synchronized
    fun remove(id: String) {
        val hit = _stickers.value.firstOrNull { it.id == id } ?: return
        File(hit.file).delete()
        _stickers.value = _stickers.value.filterNot { it.id == id }
        persist()
    }

    private fun read(): List<Sticker> {
        if (!indexFile.isFile) return emptyList()
        return try {
            val root = JSONObject(indexFile.readText())
            val array = root.optJSONArray("stickers") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file")
                if (file.isBlank() || !File(file).isFile) return@mapNotNull null
                val tags = o.optJSONArray("tags")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty().ifEmpty { listOf(UNSORTED) }
                Sticker(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                    file = file,
                    name = o.optString("name").ifBlank { "表情" },
                    tags = tags,
                    animated = o.optBoolean("animated"),
                    uses = o.optInt("uses"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        try {
            dir.mkdirs()
            val array = JSONArray()
            _stickers.value.forEach { s ->
                array.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("file", s.file)
                        .put("name", s.name)
                        .put("tags", JSONArray(s.tags))
                        .put("animated", s.animated)
                        .put("uses", s.uses),
                )
            }
            indexFile.writeText(JSONObject().put("stickers", array).toString())
        } catch (e: Exception) {
            // 写盘失败只影响下次启动；内存里仍可用。
        }
    }
}
