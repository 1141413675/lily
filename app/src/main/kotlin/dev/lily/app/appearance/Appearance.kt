package dev.lily.app.appearance

/** 明暗三选一；暗色是单独配色，不是亮色反相。 */
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("一直亮"), DARK("一直暗") }

/** 没有自定义图片时的两套内置聊天背景。 */
enum class ChatBackdrop(val label: String) {
    GLOW("淡紫柔光"),
    XIAOMAN("隐百合暗纹"),
}

/** 聊天背景：内置底色优先，上传本地图片后以图片为准。 */
data class AppearanceSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** 本地图片副本路径，空表示未选。 */
    val imagePath: String = "",
    /** 暗化 0–70%。 */
    val dim: Int = 0,
    /** 模糊 0–14。 */
    val blur: Int = 0,
    /** 没有图片时使用的内置背景。 */
    val backdrop: ChatBackdrop = ChatBackdrop.GLOW,
) {
    val hasImage: Boolean get() = imagePath.isNotBlank()
}
