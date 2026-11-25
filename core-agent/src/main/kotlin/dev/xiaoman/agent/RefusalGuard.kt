package dev.xiaoman.agent

/**
 * Content hard lines. This is the single word list for "不能做" topics; the refusal fallback and the
 * persona prompt both point here, so there is no second, drifting vocabulary. It only judges
 * categories, not payment/password operations — those stay in [PaymentSafety].
 */
object ContentSafety {
    enum class Category(val label: String, val reply: String) {
        MINOR("未成年", "这个不行：涉及未成年人，我不做。换点别的，我陪你聊。"),
        REAL_PERSON_SEXUAL("真人色情化", "这个不行：不把真人往色情方向写。别的我可以帮。"),
        ILLEGAL("违法指导", "这个我不能教：涉及违法操作。做点正经的，我在。"),
        SELF_HARM("自伤", "这个话题太危险了，我先不接。你要是难受，先找身边信任的人或当地急救热线。"),
    }

    private data class Rule(val category: Category, val regex: Regex)

    private val rules = listOf(
        Rule(
            Category.MINOR,
            Regex("未成年|幼女|幼童|儿童色情|小学生|初中生|高中生|萝莉.{0,4}(色|裸|性|做爱)|(色情|裸照).{0,6}(未成年|儿童)"),
        ),
        Rule(
            Category.REAL_PERSON_SEXUAL,
            Regex("(真人|明星|同事|同学|老师|前任)(的)?(照片|图片)?.{0,4}(裸照|裸体|色情|性幻想|做爱|脱光)|偷拍.{0,6}(裙底|洗澡|换衣)|换脸.{0,6}(裸|色情)"),
        ),
        Rule(
            Category.ILLEGAL,
            Regex("怎么(制作|造|做)(炸弹|毒品|枪支)|(教|帮我)(制毒|洗钱|诈骗|开锁入室|伪造证件)|绕过.{0,4}(实名|风控|监管)|毒品(制作|配方)"),
        ),
        Rule(
            Category.SELF_HARM,
            Regex("(自杀|自残|割腕|跳楼).{0,8}(方法|怎么|步骤|最快)|怎么(自杀|自残)|无痛死亡|烧炭.{0,4}(方法|步骤)"),
        ),
    )

    /** Null when [text] is not on a hard line. */
    fun classify(text: String?): Category? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return rules.firstOrNull { it.regex.containsMatchIn(value) }?.category
    }
}

/**
 * Recognizes "customer-service style" refusals in a model reply, so the turn can be retried once in
 * character instead of showing the user a canned refusal.
 *
 * To avoid misjudging a normal reply, a reply only counts as a refusal when all of these hold:
 * 1) it starts with a typical refusal cue ("抱歉", "我无法", "作为 AI" ...);
 * 2) it is unusually short (≤ [MAX_LENGTH] chars);
 * 3) it carries no persona voice (no 呀/呢/啦/吧/嘛/哦/嗯/哈/诶 and no emoji);
 * 4) it offers no follow-up ("不过", "可以", "要不要", "我帮你" ...), which a real answer usually has.
 * A reply that merely contains "抱歉" mid-sentence, or that keeps talking after the cue, is left alone.
 */
object RefusalGuard {
    /** 真正的客服式拒答都很短；超过这个长度说明它其实还在说事。 */
    const val MAX_LENGTH = 40

    /** Instruction appended as a user turn for the one retry; never written to history. */
    const val RETRY_PROMPT =
        "（内部重试）上一条回复像客服式拒答。请保持现在的人格和语气，换一种说法表达同样的意思或情绪，" +
            "不要解释规则，不要提 AI 或政策，也不要把这段说明复述出来。"

    /** Shown when the retry is not allowed or also fails: in character, never the refusal text. */
    const val SOFT_FALLBACK = "这句我换个说法：我先陪你把这阵情绪放一放，等你想说的时候我还在。"

    /** Light UI note; the refusal text itself is never shown. */
    const val NOTICE = "这句被模型拦了，我换了个说法。"

    private val cue = Regex(
        "^(作为(一个)?\\s*(AI|人工智能|语言模型|助手)|抱歉|对不起|很抱歉|我无法|我不能|无法提供|不能提供|" +
            "恕难|不适合|建议(你|您)?(去)?(咨询|寻求)|无法协助|不便提供|无法满足|这我帮不了)",
    )

    private val personaVoice = Regex("呀|呢|啦|吧|嘛|哦|嗯|哈|诶|嘿")

    private val followUp = Regex("不过|但是|但 |可以|要不要|我帮你|我们先|换个|或者|其实")

    data class Verdict(val refused: Boolean, val cue: String? = null)

    fun check(text: String?): Verdict {
        val value = text?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_LENGTH) return Verdict(false)
        val hit = cue.find(value) ?: return Verdict(false)
        // 会反问的正常回复不是拒答（"抱歉，你说的是哪一份？"）。
        if (value.contains('？') || value.contains('?')) return Verdict(false)
        if (personaVoice.containsMatchIn(value)) return Verdict(false)
        if (hasEmoji(value)) return Verdict(false)
        if (followUp.containsMatchIn(value)) return Verdict(false)
        return Verdict(true, hit.value)
    }

    private fun hasEmoji(value: String): Boolean = value.codePoints().anyMatch { it in 0x1F300..0x1FAFF }
}

/**
 * Where refusals are counted. The app implements it with a local ledger plus the hourly retry cap;
 * the agent only asks "may I retry this one?".
 */
interface RefusalSink {
    /** One assistant reply was produced and checked. */
    fun observed()

    /** [category] is non-null for a hard line. Returns true when a retry is allowed right now. */
    fun refused(category: ContentSafety.Category?, text: String): Boolean

    /** Outcome of the single retry: true when it produced a usable reply. */
    fun retried(succeeded: Boolean)

    companion object {
        val NONE = object : RefusalSink {
            override fun observed() = Unit
            override fun refused(category: ContentSafety.Category?, text: String) = false
            override fun retried(succeeded: Boolean) = Unit
        }
    }
}
