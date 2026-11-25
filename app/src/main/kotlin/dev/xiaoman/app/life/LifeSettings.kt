package dev.xiaoman.app.life

/**
 * 她自己的生活设定：只影响她之后写的动态与措辞，不改写历史记录。
 * 字段与设计稿「她的生活」一致；全空时动态流显示空状态，不发模板凑数。
 */
data class LifeSettings(
    val city: String = "",
    /** 例如「在小书店选书，周三休息」。 */
    val doing: String = "",
    /** 例如「七点起，十一点半睡」。 */
    val routine: String = "",
    /** 例如「一只叫年糕的橘猫」。 */
    val pet: String = "",
    /** 例如「在读《夜航西飞》」。 */
    val media: String = "",
    /** 一句自述，例如「不太会安慰人，但会一直听」。 */
    val bio: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(city, doing, routine, pet, media, bio).all { it.isBlank() }

    fun trimmed(): LifeSettings = LifeSettings(
        city = city.trim(),
        doing = doing.trim(),
        routine = routine.trim(),
        pet = pet.trim(),
        media = media.trim(),
        bio = bio.trim(),
    )

    companion object {
        const val MAX_CITY = 16
        const val MAX_DOING = 28
        const val MAX_ROUTINE = 28
        const val MAX_PET = 20
        const val MAX_MEDIA = 30
        const val MAX_BIO = 36

        val EMPTY = LifeSettings()
    }
}

/** 动态模板与评论回应：不依赖模型，任何时候都有话说，也不问候错时段。 */
object FeedTexts {

    /** 这些词只属于特定时段，模板里绝不出现，避免「凌晨说早安」。 */
    val FORBIDDEN: List<String> = listOf("早安", "早上好", "晚安", "早点睡", "早点休息")

    fun template(life: LifeSettings, part: dev.xiaoman.agent.DayPart, seed: Long = 0L): String {
        val doing = life.doing.trim()
        val media = life.media.trim()
        val city = life.city.trim()
        val pet = petName(life.pet)
        val base = when (part) {
            dev.xiaoman.agent.DayPart.DAWN -> pick(
                seed,
                "醒得比闹钟早了一点。",
                "天刚亮，屋子里还很安静。",
                "起得早，先去把窗开了。",
            )
            dev.xiaoman.agent.DayPart.MORNING -> pick(
                seed,
                "上午把桌子收拾了一遍。",
                "上午出门了一趟，路上人不多。",
                "上午脑子还算清楚，先做了点正事。",
            )
            dev.xiaoman.agent.DayPart.NOON -> pick(
                seed,
                "中午随便吃了点，坐了一会儿。",
                "中午歇了半小时，醒来有点钝。",
                "中午没什么胃口，喝了碗汤。",
            )
            dev.xiaoman.agent.DayPart.AFTERNOON -> pick(
                seed,
                "下午有点犯困，泡了杯茶。",
                "下午把手上那点事收了尾。",
                "下午光线很好，坐着发了会儿呆。",
            )
            dev.xiaoman.agent.DayPart.EVENING -> pick(
                seed,
                "傍晚收工，路上绕了一小段。",
                "天暗得比昨天早一点。",
                "傍晚在楼下站了会儿，风有点凉。",
            )
            dev.xiaoman.agent.DayPart.NIGHT -> pick(
                seed,
                "晚上把灯调暗了一点。",
                "晚上不太想说话，就听着外面的声音。",
                "晚上收拾完，终于坐下来。",
            )
            dev.xiaoman.agent.DayPart.LATE_NIGHT -> pick(
                seed,
                "夜深了，还没什么睡意。",
                "这个点外面很静，我还醒着。",
                "灯还留着一盏，慢慢等困。",
            )
        }
        val details = buildList {
            if (doing.isNotEmpty()) add(if (doing.startsWith("在")) "$doing。" else "在$doing。")
            if (media.isNotEmpty()) add("$media。")
            if (pet.isNotEmpty()) add("$pet 在旁边待着。")
            if (city.isNotEmpty() && part != dev.xiaoman.agent.DayPart.LATE_NIGHT) add("$city 今天还行。")
        }
        val mood = when (part) {
            dev.xiaoman.agent.DayPart.DAWN, dev.xiaoman.agent.DayPart.MORNING -> "心里还算踏实。"
            dev.xiaoman.agent.DayPart.NOON -> "有点懒。"
            dev.xiaoman.agent.DayPart.AFTERNOON -> "情绪平平的。"
            dev.xiaoman.agent.DayPart.EVENING -> "松下来了一点。"
            dev.xiaoman.agent.DayPart.NIGHT -> "安静。"
            dev.xiaoman.agent.DayPart.LATE_NIGHT -> "脑子有点慢。"
        }
        return (base + details.joinToString("") + mood).take(96)
    }

    /** 用户评论后的聊天回应：引用原话，按当前时段给一句合适的接法。 */
    fun commentReply(comment: String, part: dev.xiaoman.agent.DayPart): String {
        val head = "看到你在我那条动态下写的「${comment.trim().take(30)}」了。"
        val tail = when (part) {
            dev.xiaoman.agent.DayPart.DAWN -> "这会儿刚醒，回得慢一点。"
            dev.xiaoman.agent.DayPart.MORNING -> "上午事情不多，可以多说两句。"
            dev.xiaoman.agent.DayPart.NOON -> "刚坐下，正好回你。"
            dev.xiaoman.agent.DayPart.AFTERNOON -> "下午有点走神，看到你这句精神了一下。"
            dev.xiaoman.agent.DayPart.EVENING -> "今天收得早，现在有空。"
            dev.xiaoman.agent.DayPart.NIGHT -> "晚上安静，你说，我听着。"
            dev.xiaoman.agent.DayPart.LATE_NIGHT -> "我还没睡，你说。"
        }
        return head + tail
    }

    /** 她看到你发动态后的短回应：像熟人随口接一句，不问候错时段。 */
    fun reaction(text: String, hasImages: Boolean, seed: Long = 0L): String = when {
        hasImages -> pick(seed, "这张我喜欢。", "光线真好。", "是在哪儿拍的？")
        text.contains("累") || text.contains("加班") -> "今天辛苦了。"
        text.contains("吃") || text.contains("饭") -> "吃好就行。"
        text.contains("电影") || text.contains("书") -> "回头跟我说说。"
        text.contains("下雨") || text.contains("天气") -> "今天外面是有点。"
        else -> pick(seed, "看到啦。", "嗯，我在。", "这个我记住了。", "你说，我听着。")
    }

    /** 「一只叫年糕的橘猫」→「年糕」；取不到名字就用原话。 */
    fun petName(pet: String): String {
        val value = pet.trim()
        if (value.isEmpty()) return ""
        val named = Regex("叫(.+?)的").find(value)?.groupValues?.getOrNull(1)?.trim()
        if (!named.isNullOrEmpty()) return named
        return value.removePrefix("一只").ifBlank { value }
    }

    private fun pick(seed: Long, vararg options: String): String =
        options[((seed / 3_600_000L) % options.size).toInt().let { if (it < 0) 0 else it }]
}
