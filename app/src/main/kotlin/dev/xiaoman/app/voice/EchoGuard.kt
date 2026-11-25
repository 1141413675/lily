package dev.xiaoman.app.voice

/** 最近播报文本的环形缓冲：免提与实时通道共用同一套自回声比对口径。 */
class SpokenBaseline(private val limit: Int = 4) {
    private val items = ArrayDeque<String>()

    /** 记录一句即将播出的文本；空白忽略。 */
    @Synchronized
    fun remember(text: String?) {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return
        items.addLast(value)
        while (items.size > limit) items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = items.toList()

    /** 识别结果是否像是最近播报过的那几句。 */
    @Synchronized
    fun isEcho(heard: String): Boolean = EchoGuard.isSelfEcho(heard, items.toList())
}

/**
 * 文本层兜底：外放时麦克风可能收到她自己的声音，识别结果会变成一轮假的用户输入。
 * 把她最近一两条播报文本与识别结果都归一化（小写、去掉标点与空白）后比对，
 * 相似度达到 [THRESHOLD] 就判为自回声，整轮丢弃。
 */
object EchoGuard {

    /** 判为自回声的相似度阈值。 */
    const val THRESHOLD = 0.8

    /** 少于这个字数的短句不判回声：真实的「嗯」「好呀」不该被吃掉。 */
    private const val MIN_CHARS = 4

    /** 只留字母和数字，去掉标点、空白与符号。 */
    fun normalize(text: String): String = buildString {
        for (ch in text.lowercase()) if (ch.isLetterOrDigit()) append(ch)
    }

    /** 归一化后的 1 - 编辑距离 / 较长长度；0 表示完全无关。 */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    /** 识别结果是否像是她刚说过的话。 */
    fun isSelfEcho(heard: String, recentSpoken: List<String>): Boolean {
        val h = normalize(heard)
        if (h.length < MIN_CHARS) return false
        for (spoken in recentSpoken) {
            val s = normalize(spoken)
            if (s.length < MIN_CHARS) continue
            if (similarity(h, s) >= THRESHOLD) return true
            // 只收到后半句/片段时编辑距离不够，但片段被完整包含也算回声。
            if (s.contains(h)) return true
        }
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
