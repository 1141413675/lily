package dev.lily.app.voice

/**
 * 朗读前的文本处理：先剔除不该念出来的内容（括号旁白、动作描写、Markdown、列表符号、
 * emoji、链接），再切短句。气泡里仍然显示原文，这里只服务语音。
 *
 * 顺序很重要：先剔除再分句，避免把半句括号切开后留下残句。
 */
object TtsScript {

    /** 一条回复最多合成几句；再多就截断，避免一长段解释被逐句念完。 */
    const val MAX_SENTENCES = 12

    /** 单句最长字数；超出的按逗号再切，保证第一句尽快出声。 */
    private const val MAX_SENTENCE_CHARS = 42

    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val HTML = Regex("""<[^>\n]{1,40}>""")
    private val FENCE = Regex("""(?m)^\s*```.*$""")

    /** 整段括号/旁白：中文圆括号、英文圆括号、方头括号、直角方括号、书名号旁的旁白不在此列。 */
    private val BRACKETED = Regex("""（[^（）]*）|\([^()]*\)|【[^【】]*】|［[^［］]*］|\[[^\[\]]*]""")

    /** 用 * 或 ** 包起来的动作描写，如 *伸懒腰*、**叹气**。 */
    private val ACTION = Regex("""\*{1,2}[^*\n]+\*{1,2}""")

    private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
    private val QUOTE = Regex("""(?m)^\s*>\s?""")
    private val LIST = Regex("""(?m)^\s{0,3}(?:[-*+•·]|\d{1,2}[.、)])\s+""")
    private val RULE = Regex("""(?m)^\s*(?:[-*_]\s*){3,}$""")
    private val CODE = Regex("""`{1,3}[^`]*`{1,3}""")
    private val EMPHASIS = Regex("""\*\*|__|~~|\*""")

    /**
     * 系统提示/技能说明漏进正文时整行丢弃，绝不朗读。
     * 只匹配固定特征片段，正常聊天不会命中。
     */
    private val LEAK = Regex(
        """(?m)^.*(?:你叫「|说话方式|人格设定|每次都必须写标记|replyMode|system prompt|phone_task 的 skill 参数).*$""",
    )

    /** 自指词所在的那一句整句不念：她是来说话的，不是来解释自己怎么说话的。 */
    private val SELF_REF = listOf("播音腔", "表演指令", "语气指令", "系统提示", "prompt")

    /**
     * 表演说明式的措辞（「语速稍慢」「声音轻一点」）也不能念出来：情绪交给表演指令，
     * 语速交给 `speed` 参数，正文里不该出现这些可被朗读的备注。命中就整句丢掉。
     */
    private val DIRECTION = Regex(
        """语速|放慢语速|声音(?:放轻|轻一?点|轻些|小一?点)|音量(?:放低|小一?点|低一?点)|压低声音|提高音量|咬字|朗读时|念得(?:慢|快)""",
    )

    private val EMOJI = Regex(
        "[" +
            "\\x{2600}-\\x{27BF}" + // ☀…➿
            "\\x{2B00}-\\x{2BFF}" +
            "\\x{FE0F}\\x{200D}\\x{20E3}" +
            "\\x{1F000}-\\x{1FAFF}" +
            "]",
    )

    private val SPACE = Regex("""[ \t\u00A0\u3000]+""")
    private val EMPTY_LINES = Regex("""\n{3,}""")
    private val SENTENCE_END = Regex("""(?<=[。！？!?；;…\n])""")
    private val SOFT_BREAK = Regex("""(?<=[，,、：:])""")

    /** 剔除不该念的内容，剩下的才是可朗读文本；空串表示整条都不该念。 */
    fun clean(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        text = FENCE.replace(text, "")
        text = IMAGE.replace(text, "")
        text = LINK.replace(text, "$1")
        text = URL.replace(text, "")
        text = HTML.replace(text, "")
        text = ACTION.replace(text, "")
        text = BRACKETED.replace(text, "")
        text = CODE.replace(text, "")
        text = HEADING.replace(text, "")
        text = QUOTE.replace(text, "")
        text = LIST.replace(text, "")
        text = RULE.replace(text, "")
        text = EMPHASIS.replace(text, "")
        text = LEAK.replace(text, "")
        // 按句去掉自指句和表演说明，再做标点分句；顺序保证不会留下半句。
        text = withoutSelfReference(text)
        text = withoutDirection(text)
        text = EMOJI.replace(text, "")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
        text = SPACE.replace(text, " ")
        text = text.replace(Regex("""\s+([，。！？；：、,.!?;:])"""), "$1")
        text = EMPTY_LINES.replace(text, "\n\n")
        return text.trim()
    }

    /** 剔除后还有没有能念的字；只剩标点、空白、符号时返回 false。 */
    fun readable(raw: String): Boolean = clean(raw).any { it.isLetterOrDigit() }

    /** 把清理后的文本切成短句；过短的碎片并到相邻句，过长的一句按逗号再切。 */
    fun sentences(cleaned: String): List<String> {
        val text = cleaned.trim()
        if (text.isEmpty()) return emptyList()
        val parts = SENTENCE_END.split(text)
            .flatMap { splitLong(it.trim()) }
            .filter { part -> part.any { it.isLetterOrDigit() } }
        val merged = mutableListOf<String>()
        for (part in parts) {
            val last = merged.lastOrNull()
            // 「嗯。」这类一两字的短句跟着下一句走，避免每句都单独发一次请求。
            if (last != null && last.length < 5) {
                merged[merged.lastIndex] = last + part
            } else {
                merged += part
            }
        }
        return merged.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SENTENCES)
    }

    /**
     * 逐字符扫句子，命中自指词就整句丢掉；标点、省略号、换行原样保留。
     * 不用正则切句，避免「……」被切丢一半。
     */
    fun withoutSelfReference(text: String): String =
        withoutSentences(text) { sentence -> SELF_REF.none { it in sentence.lowercase() } }

    /** 同 [withoutSelfReference]，把「语速稍慢」这类表演说明所在的句子整句丢掉。 */
    fun withoutDirection(text: String): String =
        withoutSentences(text) { sentence -> !DIRECTION.containsMatchIn(sentence) }

    /** 正文里残留的表演说明措辞；出口校验用来记日志，正常应当为 null。 */
    fun directionLeak(text: String): String? = DIRECTION.find(text)?.value

    /**
     * 粗略的朗读时长预估（毫秒）：中文口语约每秒 4 字，再按 `speed` 缩放。
     * 只用于发现「明显超出正文长度」的异常音频（模型把指令也念了），不参与播放。
     */
    fun expectedSpeechMs(chars: Int, speed: Float): Long {
        val rate = 4f * speed.coerceIn(0.25f, 4f)
        return (chars / rate * 1000).toLong().coerceAtLeast(300L)
    }

    private inline fun withoutSentences(text: String, keep: (String) -> Boolean): String {
        val kept = StringBuilder()
        val sentence = StringBuilder()
        for (ch in text) {
            sentence.append(ch)
            if (ch in "。！？!?…\n") {
                if (keep(sentence.toString())) kept.append(sentence)
                sentence.clear()
            }
        }
        if (sentence.isNotEmpty() && keep(sentence.toString())) kept.append(sentence)
        return kept.toString()
    }

    /**
     * 一条回复只挑一个表演指令，放在 `<|endofprompt|>` 前面；逐句换语气会听起来精神分裂。
     * 指令会被原样送进合成接口，所以只写短的行为描述，不写语速/音量这类可被朗读的说明
     * （语速由 `VoiceSettings.speed` 控制）。顺序：笑意 > 低落 > 温柔 > 轻松随口。
     */
    fun performance(raw: String): String {
        val text = clean(raw)
        return when {
            SMILE.any { it in text } -> "语气带一点笑意。"
            LOW.any { it in text } -> "语气低落。"
            GENTLE.any { it in text } -> "语气温柔。"
            else -> "语气轻松自然。"
        }
    }

    /**
     * 明显在笑、自嘲时才允许用一次 `[laughter]` 富文本标签（硅基 CosyVoice2 文档示例支持）。
     * 只给第一句加，一条回复最多一个；不满足条件就完全不写进请求。
     */
    fun laughter(raw: String): Boolean =
        raw.contains("哈哈") || raw.contains("笑死") || raw.contains("笑出声") || raw.contains("自嘲")

    /** 长句在逗号处切开；仍超长的按字数硬切，保证每次请求都短。 */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE_CHARS) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_SENTENCE_CHARS) {
            val window = rest.take(MAX_SENTENCE_CHARS)
            val cut = window.lastIndexOfAny(charArrayOf('，', ',', '、', '：', ':'))
            val at = if (cut >= 8) cut + 1 else MAX_SENTENCE_CHARS
            out += rest.take(at)
            rest = rest.drop(at)
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    private val SMILE = listOf("哈", "嘿", "嘻", "笑", "开心", "可爱", "好玩", "喜欢", "呀～", "嘿嘿")
    private val LOW = listOf("难过", "委屈", "累", "失望", "孤单", "想哭", "不开心", "难受", "算了")
    private val GENTLE = listOf("抱", "睡", "晚安", "乖", "别怕", "心疼", "慢慢", "没关系", "在呢")
}
