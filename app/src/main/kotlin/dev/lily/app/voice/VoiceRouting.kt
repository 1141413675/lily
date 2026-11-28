package dev.lily.app.voice

/**
 * 一个音色 id 能用在哪些通道。克隆音色的 uri 以 `speech:` 开头，只有 SiliconFlow 的实时/朗读
 * 接口认识；豆包 3.0 全双工只接受官方 S2S 音色列表里的 id。这里只做判断，不替用户改设置。
 */
object VoiceRouting {

    const val CLONE_PREFIX = "speech:"

    fun isClone(voice: String): Boolean = voice.trim().startsWith(CLONE_PREFIX)

    /**
     * [voice] 能否直接交给 [baseUrl] 对应的实时通话通道。空值表示沿用通道默认音色。
     *
     * 豆包 3.0 的 S2S 音色与豆包 TTS 2.0 是同一套 `*_uranus_bigtts` id，所以人设里选的音色
     * 可以直接用于全双工；克隆音色（`speech:` 前缀）只有 SiliconFlow 认。
     */
    fun realtimeSupports(baseUrl: String, voice: String): Boolean {
        val id = voice.trim()
        if (id.isEmpty()) return true
        val url = baseUrl.trim().lowercase()
        return when {
            isClone(id) -> "siliconflow" in url
            "openspeech" in url -> id.endsWith("_uranus_bigtts") || id.startsWith("ICL_uranus_")
            else -> true
        }
    }
}
