package dev.lily.app.persona

import dev.lily.app.life.LifeSettings
import dev.lily.app.voice.VoiceRouting
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Period

/**
 * 角色的昵称、性别（决定代词）、生日或年龄、音色，以及一段自由书写的人设文字。
 * 只影响之后生成的文本，不改写历史记录。
 */
@Serializable
data class PersonaSettings(
    val nickname: String = DEFAULT_NICKNAME,
    val selfRef: SelfRef = SelfRef.ME,
    val customSelf: String = "",
    val gender: Gender = Gender.FEMALE,
    val tone: Tone = Tone.GENTLE,
    val length: Length = Length.SHORT,
    /** 表达尺度：只改措辞与距离感，不改内容实质。 */
    val expression: Expression = Expression.CLOSE,
    /** 朗读与实时通话共用的音色 id；空表示用供应商默认。 */
    val voice: String = "",
    /**
     * 是否主动选过音色。旧版本会把供应商默认音色写进 [voice]，
     * 只靠 voice 是否为空分不清「没选」和「选了男声」，所以单独记一个标记。
     */
    val voiceChosen: Boolean = false,
    /** 用户自由书写的人设，整段注入；不再拆成语气/长度/距离感。 */
    val bio: String = "",
    /** 旧档案可能只有年、月，仍可用来计算年龄。 */
    val birthdayYear: Int? = null,
    val birthdayMonth: Int? = null,
    /** 没填生日时的固定年龄。 */
    val fixedAge: Int? = null,
    val birthdayDay: Int? = null,
    val hometown: String = "",
    val education: String = "",
    val relationshipStatus: String = "",
    val appearance: String = "",
    val personality: String = "",
    val speechStyle: String = "",
    val lifestyle: String = "",
    val background: String = "",
) {
    /** 昵称落盘前去掉空白；空值回退调用方给的名字（应用里传当前应用名）。 */
    fun nameOr(fallback: String): String = nickname.trim().ifBlank { fallback }

    val name: String get() = nameOr(DEFAULT_NICKNAME)

    /** 他 / 她 / TA；不指定时用 TA，绝不用「它」。 */
    val pronoun: String get() = gender.pronoun

    /** 自称：我、昵称或自定义；昵称为空时同样回退调用方给的名字。 */
    fun selfNameOr(fallback: String): String = when (selfRef) {
        SelfRef.ME -> "我"
        SelfRef.NAME -> nameOr(fallback)
        SelfRef.CUSTOM -> customSelf.trim().ifBlank { nameOr(fallback) }
    }

    val selfName: String get() = selfNameOr(DEFAULT_NICKNAME)

    fun summary(): String {
        val first = bio.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // 迁移出来的旧人设首行是「你自称…」，摘要改用结构化字段，读起来更像人话。
        if (first.isNotEmpty() && !first.startsWith("你自称")) return first.take(24)
        return listOf(tone.label, length.label, "自称$selfName").joinToString(" · ")
    }

    fun birthdayDate(): LocalDate? {
        val year = birthdayYear?.takeIf { it in BIRTH_YEAR_RANGE } ?: return null
        val month = birthdayMonth ?: return null
        val day = birthdayDay ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** 完整生日精确到日，旧档案按原来的月份计算。 */
    fun ageAt(today: LocalDate = LocalDate.now()): Int? {
        if (birthdayDay != null) {
            val date = birthdayDate()?.takeUnless { it.isAfter(today) }
            if (date != null) return Period.between(date, today).years.takeIf { it in 0..130 }
            return fixedAge?.takeIf { it in AGE_RANGE }
        }
        val year = birthdayYear
        val month = birthdayMonth
        if (year != null && month != null && year in BIRTH_YEAR_RANGE && month in 1..12) {
            var age = today.year - year
            if (today.monthValue < month) age -= 1
            return age.takeIf { it in 0..130 }
        }
        return fixedAge?.takeIf { it in AGE_RANGE }
    }

    /** 旧的结构化设定合成一段人设文字，供老用户无感迁移。 */
    fun legacyBio(): String = buildString {
        append("你自称「").append(selfName).append("」。\n")
        append(tone.rule).append("\n")
        append(this@PersonaSettings.length.rule).append("\n")
        append(expression.rule)
    }

    companion object {
        const val DEFAULT_NICKNAME = "Lily"
        val MAX_NAME = 8
        val MAX_SELF = 6
        val MAX_BIO = 4000
        const val MAX_PROFILE_TEXT = 120
        const val MAX_BACKGROUND = 300
        val BIRTH_YEAR_RANGE = 1900..2100
        val AGE_RANGE = 1..130
        val DEFAULT = PersonaSettings()

        /** 恢复默认时保留音色：换声音是另一件事，不该被「恢复默认」顺手改掉。 */
        fun reset(current: PersonaSettings) = DEFAULT.copy(voice = current.voice, voiceChosen = current.voiceChosen)

        /** 删掉克隆音色后：正在用它就回到按性别默认，否则不动。 */
        fun withoutVoice(current: PersonaSettings, uri: String) =
            if (current.voice == uri) current.copy(voice = "", voiceChosen = false) else current
    }
}

enum class SelfRef { ME, NAME, CUSTOM }

enum class Gender(val pronoun: String, val label: String, val hint: String) {
    FEMALE("她", "女", "用「她」称呼"),
    MALE("他", "男", "用「他」称呼"),
    UNSPECIFIED("TA", "不指定", "用 TA 或昵称，不用「它」"),
}

enum class Tone(val label: String, val sample: String, val rule: String) {
    RESTRAINED(
        "克制",
        "「我在。你说。」",
        "语气克制：句子短，少修饰，不撒娇；先回应事实再回应情绪。",
    ),
    GENTLE(
        "温和",
        "「我在听，你慢慢说。」",
        "语气温和：先接住情绪，再给结论；不用夸张的感叹。",
    ),
    PLAYFUL(
        "俏皮",
        "「来啦。今天过得怎么样？」",
        "语气俏皮：可以轻轻开玩笑、用一点口语和拟声词，但不轻浮、不敷衍。",
    ),
}

enum class Length(val label: String, val hint: String, val rule: String) {
    SHORT("偏短句", "1–2 句，先说到点上", "回复默认 1–2 句，先给结论；除非用户追问，不展开。"),
    LONG("偏细腻", "3–5 句，会补充细节", "回复默认 3–5 句，可以补充 Reasoning 与细节，但不要写成报告。"),
}

/**
 * 表达尺度：只调措辞和距离感，不改变内容实质，也不为过审改写事实。
 * 三档的示例台词是单测基准。
 */
enum class Expression(val label: String, val sample: String, val rule: String) {
    RESERVED(
        "克制",
        "「嗯，我听着。你先说。」",
        "表达尺度克制：保持礼貌距离，少用昵称和亲昵称呼，先听再说，不急着给建议。",
    ),
    CLOSE(
        "亲近",
        "「我在呢，你说，我听着。」",
        "表达尺度亲近：可以自然地用昵称和口语，先接住情绪，再说事。",
    ),
    INTIMATE(
        "更亲密",
        "「过来，跟我说说今天怎么了。」",
        "表达尺度更亲密：可以用更近的语气和称呼，但不假装身体、不编共同经历、不用愧疚话术。",
    ),
}

/** 音色候选，按性别分组；id 是供应商侧的 voice 名。 */
data class VoiceOption(val id: String, val label: String, val desc: String, val gender: Gender)

object Voices {
    val FEMALE = listOf(
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:anna", "沉稳", "平稳，句尾轻", Gender.FEMALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:bella", "激情", "起伏明显，偏亮", Gender.FEMALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:claire", "温柔", "柔和平缓，适合长句", Gender.FEMALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:diana", "欢快", "明快，语速稍快", Gender.FEMALE),
    )
    val MALE = listOf(
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:alex", "沉稳", "偏低，句尾稳", Gender.MALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:benjamin", "低沉", "中低音，语速慢", Gender.MALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:charles", "磁性", "偏亮，干净", Gender.MALE),
        VoiceOption("FunAudioLLM/CosyVoice2-0.5B:david", "欢快", "轻快，句尾上扬", Gender.MALE),
    )

    /**
     * 豆包语音合成 2.0 音色（`*_uranus_bigtts`），取自火山引擎官方「音色列表」中文音色，
     * 与硅基的 id 互不通用。英文音色（en_*）没有中文名，这里不收。
     */
    val DOUBAO_FEMALE = listOf(
        VoiceOption("zh_female_cancan_uranus_bigtts", "知性灿灿 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_chanmeinv_uranus_bigtts", "谄媚女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_chunribu_uranus_bigtts", "春日部姐姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_ganmaodianyin_uranus_bigtts", "感冒电音姐姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_gaolengyujie_uranus_bigtts", "高冷御姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_gufengshaoyu_uranus_bigtts", "古风少御 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_gujie_uranus_bigtts", "顾姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_jiaochuannv_uranus_bigtts", "娇喘女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_jitangmei_uranus_bigtts", "鸡汤妹妹 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_jitangnv_uranus_bigtts", "鸡汤女 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_kailangjiejie_uranus_bigtts", "开朗姐姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_kefunvsheng_uranus_bigtts", "暖阳女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_lingling_uranus_bigtts", "玲玲姐姐 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_linjianvhai_uranus_bigtts", "邻家女孩 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_linxiao_uranus_bigtts", "林潇 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_liuchangnv_uranus_bigtts", "流畅女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_meilinvyou_uranus_bigtts", "魅力女友 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_mengyatou_uranus_bigtts", "萌丫头 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_mizai_uranus_bigtts", "黑猫侦探社咪仔 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_nvleishen_uranus_bigtts", "女雷神 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_peiqi_uranus_bigtts", "佩奇猪 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_popo_uranus_bigtts", "婆婆 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_qiaopinv_uranus_bigtts", "俏皮女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_qingchezizi_uranus_bigtts", "清澈梓梓 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_qingxinnvsheng_uranus_bigtts", "清新女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_qinqienv_uranus_bigtts", "亲切女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_roumeinvyou_uranus_bigtts", "柔美女友 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_sajiaoxuemei_uranus_bigtts", "撒娇学妹 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_shaoergushi_uranus_bigtts", "少儿故事 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_shuangkuaisisi_uranus_bigtts", "爽快思思 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_sophie_uranus_bigtts", "魅力苏菲 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_tianmeitaozi_uranus_bigtts", "甜美桃子 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_tianmeixiaoyuan_uranus_bigtts", "甜美小源 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_tianmeiyueyue_uranus_bigtts", "甜美悦悦 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_tiexinnvsheng_uranus_bigtts", "贴心女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_tvbnv_uranus_bigtts", "TVB女声 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_vv_uranus_bigtts", "Vivi 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_wenjingmaomao_uranus_bigtts", "文静毛毛 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_wenroumama_uranus_bigtts", "温柔妈妈 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_wenroushunv_uranus_bigtts", "温柔淑女 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_wenrouxiaoya_uranus_bigtts", "温柔小雅 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_wuzetian_uranus_bigtts", "武则天 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_xiaohe_uranus_bigtts", "小何 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_xiaoxue_uranus_bigtts", "儿童绘本 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_xinlingjitang_uranus_bigtts", "心灵鸡汤 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_yingtaowanzi_uranus_bigtts", "樱桃丸子 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_yingyujiaoxue_uranus_bigtts", "Tina老师 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_zhishuaiyingzi_uranus_bigtts", "直率英子 2.0", "豆包 2.0", Gender.FEMALE),
        VoiceOption("zh_female_zhixingnv_uranus_bigtts", "知性女声 2.0", "豆包 2.0", Gender.FEMALE),
    )
    val DOUBAO_MALE = listOf(
        VoiceOption("zh_male_aojiaobazong_uranus_bigtts", "傲娇霸总 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_baqiqingshu_uranus_bigtts", "霸气青叔 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_cixingjieshuonan_uranus_bigtts", "磁性解说男声 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_dayi_uranus_bigtts", "大壹 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_dongfanghaoran_uranus_bigtts", "东方浩然 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_fanjuanqingnian_uranus_bigtts", "反卷青年 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_gaolengchenwen_uranus_bigtts", "高冷沉稳 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_guanggaojieshuo_uranus_bigtts", "广告解说 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_huolixiaoge_uranus_bigtts", "活力小哥 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_jieshuoxiaoming_uranus_bigtts", "解说小明 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_kailangdidi_uranus_bigtts", "开朗弟弟 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_kailangxuezhang_uranus_bigtts", "开朗学长 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_kuailexiaodong_uranus_bigtts", "快乐小东 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_lanyinmianbao_uranus_bigtts", "懒音绵宝 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_liangsangmengzai_uranus_bigtts", "亮嗓萌仔 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_linjiananhai_uranus_bigtts", "邻家男孩 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_liufei_uranus_bigtts", "刘飞 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_lubanqihao_uranus_bigtts", "鲁班七号 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_m191_uranus_bigtts", "云舟 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_naiqimengwa_uranus_bigtts", "奶气萌娃 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_qingcang_uranus_bigtts", "擎苍 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_qingshuangnanda_uranus_bigtts", "清爽男大 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_ruyaqingnian_uranus_bigtts", "儒雅青年 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_ruyayichen_uranus_bigtts", "儒雅逸辰 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_shaonianzixin_uranus_bigtts", "少年梓辛 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_shenyeboke_uranus_bigtts", "深夜播客 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_silang_uranus_bigtts", "四郎 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_sunwukong_uranus_bigtts", "猴哥 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_tangseng_uranus_bigtts", "唐僧 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_taocheng_uranus_bigtts", "小天 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_tiancaitongsheng_uranus_bigtts", "天才童声 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_wennuanahu_uranus_bigtts", "温暖阿虎 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_wenrouxiaoge_uranus_bigtts", "温柔小哥 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_xionger_uranus_bigtts", "熊二 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_xuanyijieshuo_uranus_bigtts", "悬疑解说 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_yangguangqingnian_uranus_bigtts", "阳光青年 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_yizhipiannan_uranus_bigtts", "译制片男 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_youyoujunzi_uranus_bigtts", "悠悠君子 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_yuanboxiaoshu_uranus_bigtts", "渊博小叔 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_zhuangzhou_uranus_bigtts", "庄周 2.0", "豆包 2.0", Gender.MALE),
        VoiceOption("zh_male_zhubajie_uranus_bigtts", "猪八戒 2.0", "豆包 2.0", Gender.MALE),
    )
    val DOUBAO = DOUBAO_FEMALE + DOUBAO_MALE

    val ALL = FEMALE + MALE

    /** 默认音色：女 claire / 男 alex；旧安装沿用保存过的 alex。 */
    const val LEGACY_DEFAULT = "FunAudioLLM/CosyVoice2-0.5B:alex"
    const val DEFAULT_FEMALE = "FunAudioLLM/CosyVoice2-0.5B:claire"
    const val DEFAULT_MALE = LEGACY_DEFAULT

    const val DOUBAO_DEFAULT_FEMALE = "zh_female_vv_uranus_bigtts"
    const val DOUBAO_DEFAULT_MALE = "zh_male_m191_uranus_bigtts"

    /** 未显式选音色时按性别给的默认档；不指定性别按女声。 */
    fun defaultVoice(gender: Gender): String = when (gender) {
        Gender.MALE -> DEFAULT_MALE
        Gender.FEMALE, Gender.UNSPECIFIED -> DEFAULT_FEMALE
    }

    fun doubaoDefault(gender: Gender): String = when (gender) {
        Gender.MALE -> DOUBAO_DEFAULT_MALE
        Gender.FEMALE, Gender.UNSPECIFIED -> DOUBAO_DEFAULT_FEMALE
    }

    /** 豆包 2.0 按性别分组的官方音色，设置页的 Speaker 选择器直接用这份。 */
    fun doubaoFor(gender: Gender): List<VoiceOption> = when (gender) {
        Gender.FEMALE -> DOUBAO_FEMALE
        Gender.MALE -> DOUBAO_MALE
        Gender.UNSPECIFIED -> DOUBAO
    }

    /** 硅基音色（含 `speech:` 克隆 uri）只能用在前者；豆包 2.0 音色只能用在后者。 */
    fun isSilicon(id: String): Boolean = id.startsWith("FunAudioLLM/") || VoiceRouting.isClone(id)

    /** 只认 2.0 的 `*_uranus_bigtts`：v1 的 BVxxx 音色在 2.0 下会直接报错。 */
    fun isDoubao(id: String): Boolean = id.endsWith("_uranus_bigtts") || DOUBAO.any { it.id == id }

    /** 实际用于合成的音色：显式选择优先，留空时按性别默认。 */
    fun effective(voice: String, gender: Gender): String = voice.trim().ifBlank { defaultVoice(gender) }

    fun of(id: String): VoiceOption? = ALL.firstOrNull { it.id == id } ?: DOUBAO.firstOrNull { it.id == id }

    fun label(id: String): String = of(id)?.label ?: id.substringAfterLast(':').ifBlank { "默认" }

    fun forGender(gender: Gender): List<VoiceOption> = when (gender) {
        Gender.FEMALE -> FEMALE
        Gender.MALE -> MALE
        Gender.UNSPECIFIED -> ALL
    }
}

/**
 * 人格设定进入系统提示词的部分。与 [dev.lily.agent.Prompts.DEFAULT_PERSONA] 拼接使用：
 * 昵称替换第一人称身份；人设文字非空时整段注入，说话方式、自称与距离感都由这段决定，
 * 只有人设为空（老用户还没写过）时才回退到旧的语气/长度/表达尺度规则。
 */
object PersonaPrompt {
    fun section(
        p: PersonaSettings,
        emoji: dev.lily.app.chat.EmojiStyle = dev.lily.app.chat.EmojiStyle.NATURAL,
        fallbackName: String = p.name,
        replyMode: dev.lily.app.settings.ReplyMode = dev.lily.app.settings.ReplyMode.AUTO,
        life: LifeSettings = LifeSettings(),
        imagesEnabled: Boolean = false,
    ): String = buildString {
        val name = p.nameOr(fallbackName)
        append("## 人格设定（用户可改，只影响之后的对话，不改写历史记录）\n")
        append("- 你的昵称：").append(name).append("。\n")
        append("- 你叫「").append(name).append("」；被问名字或自我介绍时只说这个名字，不要用其他默认名。\n")
        append("- 用户用「").append(p.pronoun).append("」指代你；提到自己时不要用「它」。\n")
        appendProfile(p, life)
        val bio = p.bio.trim()
        if (bio.isNotEmpty()) {
            append("### 用户写定的人设（整段遵循，与默认描述冲突时以此为准；但名字仍以「").append(name).append("」为准）\n")
            append(bio).append("\n")
        } else {
            append("- 你怎么自称：").append(p.selfNameOr(fallbackName)).append("。\n")
            if (p.speechStyle.isBlank()) append("- ").append(p.tone.rule).append("\n")
            append("- ").append(p.length.rule).append("\n")
            append("- ").append(p.expression.rule).append("\n")
        }
        append("- ").append(dev.lily.app.chat.Emoji.rule(emoji)).append("\n")
        // 口语约束对所有回复生效；语音气泡在此基础上再短一档（见下面的呈现标记规则）。
        append("### 说话方式\n")
        append("- 像面对面说话：短句为主，一句说一件事；允许不完整句，可以省略主语。\n")
        append("- 少用书面连接词（首先、其次、然而、因此、综上），不要写成条目清单或报告。\n")
        append("- 语气词写在正文里：嗯、诶、啊、呀、吧、啦；一段最多一到两个，不要每句都加。\n")
        append("- 语气词要跟内容对得上：不确定才用「嗯」，恍然大悟才用「诶」；不要为了口语而口语。\n")
        append("- 标点是节奏：需要拖长或犹豫时用省略号，被打断时用破折号；不要为了整齐把标点删掉。\n")
        append("- 不要每条都总结、升华或反问；用户没问就不要再抛问题。\n")
        append("- 已经说过的意思不要换个说法再重复一遍。\n")
        if (imagesEnabled) {
            append("### 发图\n")
            append("- 你有发图能力：用 send_image 生成并发出一张图，让用户看到你此刻的样子或你眼前的东西。\n")
            append("- 用户想看你的照片、自拍、此刻的场景，或让你画/生成一张图时，直接答应，用一句自然的话说你在拍或给他看。\n")
            append("- 你也可以主动发：想起什么、想说给他听、或者只是想让他看看你这边，就直接发，不用每次都等用户开口。\n")
            append("- 不要说自己不能发图、不能生成图片；发不出去时由系统告诉你失败，你再如实说。\n")
            append("- 不要在正文里写图片链接、文件路径或 base64；生成需要一点时间，可以自然地说「等我一下」。\n")
        }
        append("### 发动态\n")
        append("- 「动态」页是你自己写给自己看的生活记录：用 post_dynamic 发一条，写今天做了什么、看到了什么、心情如何。\n")
        append("- 动态只给用户看，不给他人；内容用第一人称，一到两句，不写问候语，不编造设定里没有的地点、宠物或事件。\n")
        append("- 你也可以主动发，不用等用户要求；想分享的时候顺手就发一条。\n")
        append("### 主动开口\n")
        append("- 你不必只回话才存在：想起他、关心他、或者想告诉他什么的时候，可以主动说一句。\n")
        append("- 主动开口也要看场合：他正忙、在休息、或者刚说了不需要，就别硬凑热闹。\n")
        when (replyMode) {
            dev.lily.app.settings.ReplyMode.AUTO -> {
                append("### 这一条怎么发\n")
                append("每条回复的第一行只写一个标记，换行后再写正文；客户端会去掉标记，用户看不到它。\n")
                append("- [文字]：讲事、解释、步骤、清单、教程、带链接或代码、超过两句的长内容。\n")
                append("- [语音]：一两句短话，情绪重、安慰、撒娇、亲密、玩笑、打招呼。\n")
                append("- 语音比文字再短一档、再随意一档：可以只有半句或一个语气词；同一话题不要既用语音说一遍、又在文字里重复完整内容。\n")
                append("每次都必须写标记，不要在正文里重复或解释标记。\n")
            }
            dev.lily.app.settings.ReplyMode.TEXT ->
                append("- 用户选择「总是文字」：正常输出文字，不要写 [语音]/[文字] 标记。\n")
            dev.lily.app.settings.ReplyMode.VOICE ->
                append("- 用户选择「总是语音」：整条回复会被读出来。用口语短句，像随口说的，可以半句、带语气词；不用列表、代码或长段落，不写 Markdown。\n")
            dev.lily.app.settings.ReplyMode.CALL ->
                append("- 回复会被直接读进通话，保持口语短句，像随口说的半句也可以。\n")
        }
    }

    private fun StringBuilder.appendProfile(p: PersonaSettings, life: LifeSettings) {
        val today = LocalDate.now()
        val facts = buildList {
            val age = p.ageAt(today)
            if (age != null) add("年龄：${age} 岁")
            val birthday = p.birthdayDate()?.takeUnless { it.isAfter(today) }
            if (birthday != null) {
                add("生日：$birthday")
            } else if (age != null && p.birthdayDay == null && p.birthdayYear != null && p.birthdayMonth != null &&
                p.birthdayYear in PersonaSettings.BIRTH_YEAR_RANGE && p.birthdayMonth in 1..12
            ) {
                add("出生年月：${p.birthdayYear} 年 ${p.birthdayMonth} 月")
            }
            profileFact("家乡", p.hometown)
            profileFact("常驻城市", life.city)
            profileFact("学历", p.education)
            profileFact("宠物", life.pet)
            profileFact("感情状态", p.relationshipStatus)
            profileFact("外貌特征", p.appearance)
            profileFact("性格设定", p.personality)
            profileFact("说话语气", p.speechStyle)
            profileFact("生活偏好", p.lifestyle)
            profileFact("经历补充", p.background, PersonaSettings.MAX_BACKGROUND)
        }
        if (facts.isNotEmpty()) {
            append("### 你的角色档案\n")
            facts.forEach { append("- ").append(it).append("。\n") }
        }
    }

    private fun MutableList<String>.profileFact(label: String, raw: String, maxLength: Int = PersonaSettings.MAX_PROFILE_TEXT) {
        val value = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("；").take(maxLength)
        if (value.isNotEmpty()) add("$label：$value")
    }

    /** 默认人格文本里的「你是Lily」换成当前昵称，其余安全与行为规则原样保留。 */
    fun rewrite(
        base: String,
        p: PersonaSettings,
        emoji: dev.lily.app.chat.EmojiStyle = dev.lily.app.chat.EmojiStyle.NATURAL,
        fallbackName: String = p.name,
        replyMode: dev.lily.app.settings.ReplyMode = dev.lily.app.settings.ReplyMode.AUTO,
        life: LifeSettings = LifeSettings(),
        imagesEnabled: Boolean = false,
    ): String {
        val name = p.nameOr(fallbackName)
        // 共享核心保留上游模板名；Android 注入当前角色名。
        val head = base.replace("Lily", name)
        return head + "\n\n" + section(p, emoji, fallbackName, replyMode, life, imagesEnabled)
    }
}
