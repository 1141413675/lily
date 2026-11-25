package dev.xiaoman.app.chat

/**
 * 表情尺度：只控制文字里的 emoji / 颜文字，不碰语音气泡与贴纸。
 * 配额按「每 N 条回复里有 1 条带表情」计算，单条最多 [maxPerMessage] 个。
 */
enum class EmojiStyle(val label: String, val everyN: Int, val maxPerMessage: Int) {
    RESTRAINED("克制", 6, 1),
    NATURAL("自然", 3, 2),
    RICH("丰富", 2, 2),
}

object Emoji {
    /** 第 [replyIndex] 条回复（从 0 起）是否允许带表情。 */
    fun allowed(style: EmojiStyle, replyIndex: Int): Boolean =
        replyIndex >= 0 && replyIndex % style.everyN == 0

    /** 把超出 [max] 的表情删掉，保留正文；正文不受影响。ZWJ 组合算一个表情。 */
    fun clip(text: String, max: Int): String {
        var kept = 0
        var afterZwj = false
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp == 0x200D -> {
                    if (kept > 0) out.appendCodePoint(cp)
                    afterZwj = true
                }
                cp == 0xFE0F -> if (kept > 0) out.appendCodePoint(cp)
                isEmoji(cp) -> if (afterZwj) {
                    // 组合表情的后半段：不单独计数。
                    if (kept in 1..max) out.appendCodePoint(cp)
                } else if (kept < max) {
                    out.appendCodePoint(cp)
                    kept++
                }
                else -> out.appendCodePoint(cp)
            }
            if (cp != 0x200D) afterZwj = false
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    fun strip(text: String): String = clip(text, 0).trimEnd()

    /** 进入提示词的规则文本。 */
    fun rule(style: EmojiStyle): String =
        "表情尺度：${style.label}。约每 ${style.everyN} 条回复里可以有 1 条带 emoji 或颜文字，" +
            "单条不超过 ${style.maxPerMessage} 个；其余回复不带，不要用表情代替文字表达。"

    fun isEmoji(cp: Int): Boolean =
        (cp in 0x1F300..0x1FAFF) || (cp in 0x2600..0x27BF) || cp == 0xFE0F || cp == 0x200D
}
