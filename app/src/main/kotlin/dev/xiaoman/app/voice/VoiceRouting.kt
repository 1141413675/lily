package dev.xiaoman.app.voice

/**
 * 一个音色 id 能用在哪些通道。克隆音色的 uri 以 `speech:` 开头，只有 SiliconFlow 的实时/朗读
 * 接口认识；DashScope 实时通话只接受它自己的预置音色名。这里只做判断，不替用户改设置。
 */
object VoiceRouting {

    const val CLONE_PREFIX = "speech:"

    fun isClone(voice: String): Boolean = voice.trim().startsWith(CLONE_PREFIX)

    /** [voice] 能否直接交给 [baseUrl] 对应的实时通话通道。空值表示沿用通道默认音色。 */
    fun realtimeSupports(baseUrl: String, voice: String): Boolean {
        val id = voice.trim()
        if (id.isEmpty()) return true
        val url = baseUrl.trim().lowercase()
        return when {
            isClone(id) -> "siliconflow" in url
            "dashscope" in url -> !id.startsWith("FunAudioLLM/")
            else -> true
        }
    }
}
