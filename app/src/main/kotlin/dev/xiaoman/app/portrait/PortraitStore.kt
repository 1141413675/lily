package dev.xiaoman.app.portrait

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** 当前头像；文件在 `<files>/portrait`，只存本机。 */
data class PortraitState(val current: Portrait? = null)

/** 一张已选好的头像；[path] 是本机绝对路径。 */
data class Portrait(val path: String, val at: Long)

/**
 * 头像存储：`current.jpg` 是当前头像，`portrait.json` 只记它的路径。
 * 兼容旧版定妆照留下的 `portrait.json`：只要记的图还在，就继续用，不清空。
 */
class PortraitStore(private val dir: File) {

    private val file = File(dir, "portrait.json")
    private val currentFile = File(dir, "current.jpg")
    private val _state = MutableStateFlow(PortraitState())
    val state: StateFlow<PortraitState> = _state.asStateFlow()

    init {
        _state.value = read()
    }

    fun displayFile(): File? = _state.value.current?.path?.let(::File)?.takeIf { it.isFile }

    /** 收下相册里裁好的方图：复制成固定的 `current.jpg`，旧头像文件顺手删掉。 */
    @Synchronized
    fun saveAvatar(source: File): Boolean {
        if (!source.isFile) return false
        dir.mkdirs()
        runCatching { source.copyTo(currentFile, overwrite = true) }
        if (!currentFile.isFile) return false
        val previous = _state.value.current?.path
        if (previous != null && File(previous) != currentFile) runCatching { File(previous).delete() }
        _state.value = PortraitState(Portrait(currentFile.absolutePath, System.currentTimeMillis()))
        persist()
        return true
    }

    private fun read(): PortraitState {
        val saved = if (file.isFile) {
            runCatching {
                JSONObject(file.readText()).optJSONObject("current")?.optString("path").orEmpty()
            }.getOrDefault("")
        } else {
            ""
        }
        // 旧版定妆照的 json 与 current.jpg 都认；任何一处还在就继续显示。
        val path = listOf(saved, currentFile.absolutePath).firstOrNull { it.isNotBlank() && File(it).isFile }
            ?: return PortraitState()
        return PortraitState(Portrait(path, File(path).lastModified()))
    }

    private fun persist() {
        runCatching {
            dir.mkdirs()
            val path = _state.value.current?.path ?: return@runCatching
            file.writeText(
                JSONObject()
                    .put("current", JSONObject().put("path", path).put("at", System.currentTimeMillis()))
                    .toString(),
            )
        }
    }
}
