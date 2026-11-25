package dev.xiaoman.app.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 动态收藏：本机单独一个 JSON 文件，不写动态库、不进记忆、不通知她。
 * 存的是发布时的快照，原帖被删掉后收藏仍然能看。
 */
class SavedPostsStore(private val file: File) {

    data class Saved(
        val id: String,
        val author: String,
        val authorName: String,
        val text: String,
        val images: List<String>,
        val at: Long,
        val savedAt: Long,
    )

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Saved>> = _items.asStateFlow()

    fun isSaved(id: String): Boolean = _items.value.any { it.id == id }

    /** 收藏 / 取消收藏；收藏只在本机，不触发任何模型或记忆动作。 */
    fun toggle(post: FeedStore.Post, authorName: String) {
        val current = _items.value
        val next = if (current.any { it.id == post.id }) {
            current.filterNot { it.id == post.id }
        } else {
            listOf(
                Saved(
                    id = post.id,
                    author = post.author,
                    authorName = authorName,
                    text = post.text,
                    images = post.images,
                    at = post.at,
                    savedAt = System.currentTimeMillis(),
                ),
            ) + current
        }
        _items.value = next
        persist(next)
    }

    fun remove(id: String) {
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        persist(next)
    }

    private fun load(): List<Saved> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Saved(
                id = id,
                author = o.optString("author", FeedStore.AUTHOR_HER),
                authorName = o.optString("authorName"),
                text = o.optString("text"),
                images = o.optJSONArray("images")?.let { images ->
                    (0 until images.length()).map { images.optString(it) }
                }.orEmpty(),
                at = o.optLong("at"),
                savedAt = o.optLong("savedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(items: List<Saved>) {
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("author", item.author)
                        put("authorName", item.authorName)
                        put("text", item.text)
                        put("images", JSONArray(item.images))
                        put("at", item.at)
                        put("savedAt", item.savedAt)
                    },
                )
            }
            file.writeText(arr.toString())
        }
    }
}
