package dev.xiaoman.app.media

import java.io.File
import java.util.UUID

/** 聊天照片：从相册或相机复制到 `<files>/photos`，聊天行只存路径，原图不动。 */
class PhotoStore(private val dir: File) {

    /** 复制 [source] 进照片目录；成功返回落盘文件，失败为 null。 */
    fun save(source: File): File? {
        if (!source.isFile) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val ext = source.extension.lowercase().ifBlank { "jpg" }
        val target = File(dir, "img-${UUID.randomUUID().toString().take(8)}.$ext")
        return try {
            source.copyTo(target, overwrite = false)
            target
        } catch (e: Exception) {
            null
        }
    }

    /** 删除一条聊天照片（设置里清空或删除消息时用）。 */
    fun delete(path: String): Boolean = path.isNotBlank() && File(path).delete()
}
