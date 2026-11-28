package dev.lily.app.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * 两个人的动态：`<files>/feed/posts.json`，只存本机。
 * `me` 是用户发的，`her` 是她的日常；点赞与评论都落在本机。
 * 用户动态会被 [FeedMemory] 自动抽成记忆，[Post.memoryIds] 只存 permalink，不复制正文。
 */
class FeedStore(private val dir: File) {

    /**
     * 一条评论。[id] 是回复时要引用的父级锚点；[parentId] 指向被回复的那条顶层评论。
     * 旧数据没有这两个字段，[read] 会补一个稳定的旧 id，回复仍能挂上去。
     */
    data class Comment(
        val who: String,
        val text: String,
        val at: Long,
        val id: String = "",
        val parentId: String? = null,
    )

    data class Post(
        val id: String,
        val text: String,
        val at: Long,
        val author: String = AUTHOR_HER,
        /** 动态图片的本地绝对路径；复制进 `feed/images`，原图不动。 */
        val images: List<String> = emptyList(),
        val likes: Int = 0,
        /** 你赞了她的动态。 */
        val liked: Boolean = false,
        /** 她赞了你的动态。 */
        val likedByHer: Boolean = false,
        val comments: List<Comment> = emptyList(),
        /** `model` 表示模型生成，`template` 表示模板兜底；只用于排查，不展示。 */
        val source: String = SOURCE_TEMPLATE,
        /** 这条动态已经抽取到的记忆 permalink。 */
        val memoryIds: List<String> = emptyList(),
        /** 是否已做过记忆抽取；用户新帖为 false，抽取后置 true。 */
        val memoryChecked: Boolean = true,
    )

    companion object {
        const val SOURCE_TEMPLATE = "template"
        const val SOURCE_MODEL = "model"
        const val AUTHOR_ME = "me"
        const val AUTHOR_HER = "her"
        const val MAX_POSTS = 40
        const val MAX_IMAGES = 9
        const val IMAGE_DIR = "images"
    }

    private val file = File(dir, "posts.json")
    private val imageDir = File(dir, IMAGE_DIR)
    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    init {
        val stored = read()
        _posts.value = stored.orEmpty()
        _loadFailed.value = stored == null
    }

    @Synchronized
    fun retryLoad(): Boolean {
        if (!_loadFailed.value) return true
        val stored = read() ?: return false
        _posts.value = stored
        _loadFailed.value = false
        return true
    }

    fun latest(): Post? = _posts.value.firstOrNull()

    /** 默认角色被彻底删除后清空内存动态；文件由调用方删除。 */
    @Synchronized
    fun reset() {
        _posts.value = emptyList()
        _loadFailed.value = false
    }

    fun imagesDir(): File = imageDir

    /** 她的动态（模型或模板）。保留旧签名，现有生成逻辑不用改。 */
    @Synchronized
    fun add(text: String, at: Long, source: String): Post? = addHer(text, at, source)

    @Synchronized
    fun addHer(text: String, at: Long, source: String, images: List<String> = emptyList()): Post? {
        val body = text.trim()
        if (body.isEmpty() && images.isEmpty()) return null
        val post = Post(
            id = "fd-" + UUID.randomUUID().toString().take(8),
            text = body,
            at = at,
            author = AUTHOR_HER,
            images = images.take(MAX_IMAGES),
            source = source,
            memoryChecked = true,
        )
        return post.takeIf { commit((listOf(post) + _posts.value).take(MAX_POSTS)) }
    }

    /** 用户发的动态：进入时间线后立即等待自动抽取记忆。 */
    @Synchronized
    fun addMine(text: String, images: List<String>, at: Long): Post? {
        val body = text.trim()
        if (body.isEmpty() && images.isEmpty()) return null
        val post = Post(
            id = "fd-" + UUID.randomUUID().toString().take(8),
            text = body,
            at = at,
            author = AUTHOR_ME,
            images = images.take(MAX_IMAGES),
            source = SOURCE_TEMPLATE,
            memoryChecked = false,
        )
        return post.takeIf { commit((listOf(post) + _posts.value).take(MAX_POSTS)) }
    }

    /** 点赞可来回切；计数不会掉到负数。自己和她发的动态都适用。 */
    @Synchronized
    fun toggleLike(id: String): Boolean {
        val current = _posts.value
        if (current.none { it.id == id }) return false
        return commit(current.map {
            if (it.id == id) {
                it.copy(liked = !it.liked, likes = (it.likes + if (it.liked) -1 else 1).coerceAtLeast(0))
            } else {
                it
            }
        })
    }

    /** 她赞了你的动态；已经赞过就不再重复。 */
    @Synchronized
    fun likeByHer(id: String): Boolean {
        val current = _posts.value
        val post = current.firstOrNull { it.id == id } ?: return false
        if (post.likedByHer) return true
        return commit(current.map {
            if (it.id == id) it.copy(likedByHer = true, likes = it.likes + 1) else it
        })
    }

    /**
     * 评论落库；[parentId] 是回复的父评论 id。只保留一级：回复一条回复时挂到它的顶层父评论上，
     * 父级找不到就当作普通评论。返回更新后的动态。
     */
    @Synchronized
    fun addComment(id: String, who: String, text: String, at: Long, parentId: String? = null): Post? {
        val body = text.trim()
        if (body.isEmpty()) return null
        var updated: Post? = null
        val next = _posts.value.map { post ->
            if (post.id == id) {
                val parent = parentId?.let { pid -> post.comments.firstOrNull { it.id == pid } }
                val rootId = parent?.let { it.parentId ?: it.id }
                val comment = Comment(
                    who = who,
                    text = body.take(120),
                    at = at,
                    id = "c-" + UUID.randomUUID().toString().take(8),
                    parentId = rootId?.takeIf { root -> post.comments.any { it.id == root } },
                )
                post.copy(comments = post.comments + comment).also { updated = it }
            } else {
                post
            }
        }
        return updated?.takeIf { commit(next) }
    }

    /** 记录本动态抽取到的记忆；空列表也要落 `memoryChecked`，避免反复调用模型。 */
    @Synchronized
    fun setMemory(id: String, memoryIds: List<String>): Boolean {
        val current = _posts.value
        if (current.none { it.id == id }) return false
        return commit(current.map {
            if (it.id == id) it.copy(memoryIds = memoryIds.distinct(), memoryChecked = true) else it
        })
    }

    /** 删除动态与它自己的图片；记忆不级联删除，只留下断链。 */
    @Synchronized
    fun delete(id: String): Boolean {
        if (_loadFailed.value) return false
        val current = _posts.value
        val post = current.firstOrNull { it.id == id } ?: return true
        val next = current.filterNot { it.id == id }
        if (!commit(next)) return false
        val stillUsed = next.flatMap { it.images }.mapNotNull { path ->
            runCatching { File(path).canonicalFile }.getOrNull()
        }.toSet()
        post.images.forEach { path ->
            runCatching {
                val image = File(path)
                val canonical = image.canonicalFile
                val ownedDir = imageDir.canonicalFile
                if (image.parentFile?.canonicalFile == ownedDir &&
                    canonical.parentFile == ownedDir && canonical !in stillUsed
                ) {
                    image.delete()
                }
            }
        }
        return true
    }

    @Synchronized
    fun clear(): Boolean = commit(emptyList())

    private fun read(): List<Post>? {
        return try {
            if (Files.notExists(file.toPath())) return emptyList()
            val array = JSONObject(file.readText()).getJSONArray("posts")
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                val images = o.optJSONArray("images")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optString(j).takeIf { it.isNotBlank() } }
                }.orEmpty()
                if (text.isBlank() && images.isEmpty()) return@mapNotNull null
                val comments = o.optJSONArray("comments")?.let { a ->
                    (0 until a.length()).mapNotNull { j ->
                        a.optJSONObject(j)?.let { c ->
                            Comment(
                                who = c.optString("who").ifBlank { "你" },
                                text = c.optString("text"),
                                at = c.optLong("at"),
                                // 旧数据没有 id：用「下标 + 时间」补一个稳定锚点，回复不会在重读后丢父级。
                                id = c.optString("id").ifBlank { "c-legacy-${c.optLong("at")}-$j" },
                                parentId = c.optString("parentId").takeIf { it.isNotBlank() },
                            )
                        }
                    }
                }.orEmpty()
                val author = o.optString("author").ifBlank { AUTHOR_HER }
                Post(
                    id = o.optString("id").ifBlank { "fd-" + UUID.randomUUID().toString().take(8) },
                    text = text,
                    at = o.optLong("at"),
                    author = author,
                    images = images,
                    likes = o.optInt("likes").coerceAtLeast(0),
                    liked = o.optBoolean("liked"),
                    likedByHer = o.optBoolean("likedByHer"),
                    comments = comments,
                    source = o.optString("source").ifBlank { SOURCE_TEMPLATE },
                    memoryIds = o.optJSONArray("memoryIds")?.let { a ->
                        (0 until a.length()).mapNotNull { j -> a.optString(j).takeIf { it.isNotBlank() } }
                    }.orEmpty(),
                    // 旧数据没有这个字段：她的动态不抽取，用户动态留待补抽取。
                    memoryChecked = o.optBoolean("memoryChecked", author != AUTHOR_ME),
                )
            }.sortedByDescending { it.at }
        } catch (e: Exception) {
            null
        }
    }

    /** Publishes the next timeline only after replacing the old on-disk snapshot. */
    private fun commit(posts: List<Post>): Boolean {
        if (_loadFailed.value) return false
        var temp: File? = null
        try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val array = JSONArray()
            posts.forEach { p ->
                val comments = JSONArray()
                p.comments.forEach { c ->
                    comments.put(
                        JSONObject()
                            .put("who", c.who)
                            .put("text", c.text)
                            .put("at", c.at)
                            .put("id", c.id)
                            .put("parentId", c.parentId.orEmpty()),
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("text", p.text)
                        .put("at", p.at)
                        .put("author", p.author)
                        .put("images", JSONArray(p.images))
                        .put("likes", p.likes)
                        .put("liked", p.liked)
                        .put("likedByHer", p.likedByHer)
                        .put("comments", comments)
                        .put("source", p.source)
                        .put("memoryIds", JSONArray(p.memoryIds))
                        .put("memoryChecked", p.memoryChecked),
                )
            }
            val snapshot = JSONObject().put("posts", array).toString().toByteArray(Charsets.UTF_8)
            val staging = File.createTempFile("feed-posts-", ".tmp", dir)
            temp = staging
            FileOutputStream(staging).use { stream ->
                stream.write(snapshot)
                stream.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: Exception) {
            return false
        } finally {
            temp?.let { staging -> runCatching { staging.delete() } }
        }
        _posts.value = posts
        return true
    }
}
