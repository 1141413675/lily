package dev.lily.app.session

/**
 * 出口检查：模型偶尔会把系统提示片段、呈现标记或技能说明写进正文。
 * 这里只剥掉明确的提示词特征，不做泛化改写；剥完没有可读内容时返回空串，
 * 调用方据此退回文字、并且绝不送进 TTS。
 */
object ReplyGuard {

    /** 呈现标记与富文本标签不该出现在气泡里。 */
    private val TAGS = Regex("""\[(语音|文字|voice|text|laughter)]""", RegexOption.IGNORE_CASE)

    /** 只匹配提示词/技能说明的特征片段，避免误伤正常聊天。 */
    private val LEAK = listOf(
        "你叫「",
        "### 说话方式",
        "## 人格设定",
        "少用书面连接词",
        "每次都必须写标记",
        "客户端会去掉标记",
        "replyMode",
        "phone_task 的 skill 参数",
    )

    /** 标题行整行丢弃；其余只在命中特征时丢弃该行。 */
    private val HEADING = Regex("""^\s*#{1,6}\s""")

    fun sanitize(raw: String): String {
        val stripped = TAGS.replace(raw, "")
        val kept = stripped.lineSequence().filterNot { line ->
            val text = line.trim()
            text.isEmpty() || HEADING.containsMatchIn(text) || LEAK.any { it in text }
        }.joinToString("\n")
        // 再按句过一遍自指词：只去掉说出这些词的那一句，保留前后正常内容。
        return dev.lily.app.voice.TtsScript.withoutSelfReference(kept).trim()
    }

    /** 剥离后是否还剩能展示的内容。 */
    fun readable(raw: String): Boolean = sanitize(raw).any { it.isLetterOrDigit() }
}
