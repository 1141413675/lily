package dev.xiaoman.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 克隆音色的本地索引：`<files>/voices/index.json`，只存云端 uri、名称与创建时间。
 * 样本本身不落盘；删除时由调用方先删云端音色，再从索引里移除。
 */
class ClonedVoiceStore(private val dir: File) {

    data class ClonedVoice(
        val id: String,
        /** SiliconFlow 返回的 `speech:...`，合成时直接作为 `voice` 传入。 */
        val uri: String,
        val name: String,
        val createdAt: Long,
    )

    private val indexFile = File(dir, "index.json")
    private val _voices = MutableStateFlow<List<ClonedVoice>>(emptyList())
    val voices: StateFlow<List<ClonedVoice>> = _voices.asStateFlow()

    init {
        _voices.value = read()
    }

    fun findById(id: String): ClonedVoice? = _voices.value.firstOrNull { it.id == id }

    /** 写入一条已生成的音色；名称重复时自动加序号，避免列表里分不清。 */
    @Synchronized
    fun add(uri: String, name: String, now: Long = System.currentTimeMillis()): ClonedVoice {
        val clean = name.trim().take(12).ifBlank { "我的声音" }
        var unique = clean
        var n = 2
        while (_voices.value.any { it.name == unique }) {
            unique = "$clean · $n"
            n += 1
        }
        val voice = ClonedVoice("cv-" + UUID.randomUUID().toString().take(8), uri, unique, now)
        _voices.value = _voices.value + voice
        persist()
        return voice
    }

    /** 只删本地记录；调用方负责先删云端。 */
    @Synchronized
    fun remove(id: String) {
        _voices.value = _voices.value.filterNot { it.id == id }
        persist()
    }

    private fun read(): List<ClonedVoice> {
        if (!indexFile.isFile) return emptyList()
        return try {
            val array = JSONObject(indexFile.readText()).optJSONArray("voices") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                if (uri.isBlank()) return@mapNotNull null
                ClonedVoice(
                    id = o.optString("id").ifBlank { "cv-" + UUID.randomUUID().toString().take(8) },
                    uri = uri,
                    name = o.optString("name").ifBlank { "我的声音" },
                    createdAt = o.optLong("createdAt"),
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
            _voices.value.forEach { v ->
                array.put(
                    JSONObject()
                        .put("id", v.id)
                        .put("uri", v.uri)
                        .put("name", v.name)
                        .put("createdAt", v.createdAt),
                )
            }
            indexFile.writeText(JSONObject().put("voices", array).toString())
        } catch (e: Exception) {
            // 写盘失败只影响下次启动；内存里仍可用。
        }
    }
}
