package dev.lily.app.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 景区/生活图库：用户自己放图并按地点打标签，她按聊天里出现的地点挑一张发。
 * 只存本机，不联网；图库为空时调用方静默跳过（不报错、不发明理由）。
 */
class SceneryStore(private val dir: File) {

    data class Scenery(
        val id: String,
        val file: String,
        val place: String,
        val addedAt: Long,
    )

    private val indexFile = File(dir, "index.json")
    private val _items = MutableStateFlow<List<Scenery>>(emptyList())
    val items: StateFlow<List<Scenery>> = _items.asStateFlow()

    private val _city = MutableStateFlow("")
    /** 她生活设定里的城市；只作为挑图的补充线索。 */
    val city: StateFlow<String> = _city.asStateFlow()

    private var lastShareAt = 0L

    init {
        read()
    }

    fun places(): List<String> = _items.value.map { it.place }.filter { it.isNotBlank() }.distinct().sorted()

    @Synchronized
    fun add(source: File, place: String): Scenery? {
        if (!source.isFile) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val ext = source.extension.lowercase().ifBlank { "jpg" }
        val id = "sc-" + UUID.randomUUID().toString().take(8)
        val target = File(dir, "$id.$ext")
        try {
            source.copyTo(target, overwrite = true)
        } catch (e: Exception) {
            return null
        }
        val item = Scenery(id, target.absolutePath, place.trim().take(20), System.currentTimeMillis())
        _items.value = _items.value + item
        persist()
        return item
    }

    @Synchronized
    fun setPlace(id: String, place: String) {
        val clean = place.trim().take(20)
        _items.value = _items.value.map { if (it.id == id) it.copy(place = clean) else it }
        persist()
    }

    @Synchronized
    fun setCity(city: String) {
        _city.value = city.trim().take(20)
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        val hit = _items.value.firstOrNull { it.id == id } ?: return
        File(hit.file).delete()
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    /**
     * 按聊天上下文挑图：命中地点（取最长的地点名，避免「北京」抢「北京西站」）→ 取最近导入的一张。
     * 冷却期内或没命中返回 null，调用方退回纯文字。
     */
    @Synchronized
    fun maybePick(context: String, now: Long = System.currentTimeMillis(), cooldownMillis: Long = 6 * 3_600_000L): Scenery? {
        if (_items.value.isEmpty()) return null
        if (lastShareAt > 0 && now - lastShareAt < cooldownMillis) return null
        val hit = SceneryPicker.pick(_items.value, context, _city.value) ?: return null
        lastShareAt = now
        persist()
        return hit
    }

    /** 默认角色被彻底删除后清空内存图库与冷却；文件由调用方删除。 */
    @Synchronized
    fun reset() {
        _items.value = emptyList()
        _city.value = ""
        lastShareAt = 0L
    }

    private fun read() {        if (!indexFile.isFile) return
        try {
            val root = JSONObject(indexFile.readText())
            _city.value = root.optString("city")
            lastShareAt = root.optLong("lastShareAt")
            val array = root.optJSONArray("items") ?: JSONArray()
            _items.value = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file")
                if (file.isBlank() || !File(file).isFile) return@mapNotNull null
                Scenery(
                    id = o.optString("id").ifBlank { "sc-" + UUID.randomUUID().toString().take(8) },
                    file = file,
                    place = o.optString("place"),
                    addedAt = o.optLong("addedAt"),
                )
            }
        } catch (e: Exception) {
            _items.value = emptyList()
        }
    }

    private fun persist() {
        try {
            dir.mkdirs()
            val array = JSONArray()
            _items.value.forEach { s ->
                array.put(JSONObject().put("id", s.id).put("file", s.file).put("place", s.place).put("addedAt", s.addedAt))
            }
            indexFile.writeText(JSONObject().put("city", _city.value).put("lastShareAt", lastShareAt).put("items", array).toString())
        } catch (e: Exception) {
            // 写盘失败只影响下次启动；内存里仍可用。
        }
    }
}
