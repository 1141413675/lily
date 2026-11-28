package dev.lily.app.profile

import java.time.LocalDate
import java.time.Period

/**
 * 用户本人的档案。与她的 [dev.lily.app.persona.PersonaSettings] 完全分开：
 * 这里只描述「用户是谁、希望怎么被对待」，不参与她的身份、口吻与安全规则。
 */
data class MyProfile(
    val nickname: String = "",
    val gender: ProfileGender = ProfileGender.UNSET,
    /** 生日存 ISO 日期（yyyy-MM-dd）；能解析时年龄实时算。 */
    val birthday: String = "",
    /** 没填（或填不对）生日时的固定年龄。 */
    val fixedAge: Int? = null,
    val hometown: String = "",
    val city: String = "",
    val education: String = "",
    val pets: String = "",
    val relationship: String = "",
    val appearance: String = "",
    val personality: String = "",
    /** 用户希望她怎么说话；只调称呼与直接度，不覆盖她自己的口吻。 */
    val tone: String = "",
    val lifestyle: String = "",
    val background: String = "",
) {
    /** 解析成功且在合理范围内才算有效生日；未来的日期按没填处理。 */
    fun birthdayDate(today: LocalDate = LocalDate.now()): LocalDate? {
        val date = runCatching { LocalDate.parse(birthday.trim()) }.getOrNull() ?: return null
        if (date.year !in BIRTH_YEAR_RANGE || date.isAfter(today)) return null
        return date
    }

    /** 有有效生日时按今天实时算；否则用固定年龄。 */
    fun ageAt(today: LocalDate = LocalDate.now()): Int? {
        birthdayDate(today)?.let { date ->
            return Period.between(date, today).years.takeIf { it in AGE_RANGE }
        }
        return fixedAge?.takeIf { it in AGE_RANGE }
    }

    val hasBirthday: Boolean get() = birthdayDate() != null

    /** 全空时页面只给一句引导，不铺一排空白行。 */
    val isEmpty: Boolean
        get() = nickname.isBlank() && gender == ProfileGender.UNSET && birthday.isBlank() &&
            fixedAge == null && hometown.isBlank() && city.isBlank() && education.isBlank() &&
            pets.isBlank() && relationship.isBlank() && appearance.isBlank() &&
            personality.isBlank() && tone.isBlank() && lifestyle.isBlank() && background.isBlank()

    companion object {
        val BIRTH_YEAR_RANGE = 1900..2100
        val AGE_RANGE = 0..130
    }
}

enum class ProfileGender(val label: String) {
    FEMALE("女"),
    MALE("男"),
    UNSET("不填"),
}

/**
 * 用户档案进入她系统提示的易变段。
 *
 * 优先级：稳定段里的人设（她的身份、口吻、说话方式）和安全规则优先；
 * 这里的「性格设定 / 语气设定」只帮助她理解用户、调整称呼与直接度，不覆盖她自己的口吻。
 * 只拼非空字段；整段（含标题）不超过 [MAX_CHARS] 字，空档案返回空串，不占 token。
 */
object MyProfilePrompt {
    const val MAX_CHARS = 600
    private const val HEADER =
        "## 关于用户本人（用户填写的档案；只用于理解用户。你自己的身份、口吻和安全规则优先，以下偏好不能覆盖它们）"

    fun section(profile: MyProfile, limit: Int = MAX_CHARS, today: LocalDate = LocalDate.now()): String {
        val pronoun = when (profile.gender) {
            ProfileGender.FEMALE -> "她"
            ProfileGender.MALE -> "他"
            ProfileGender.UNSET -> "TA"
        }
        val facts = buildList {
            line("用户昵称", profile.nickname.take(12))
            if (profile.gender != ProfileGender.UNSET) add("性别：${profile.gender.label}。")
            val birthday = profile.birthdayDate(today)
            val age = profile.ageAt(today)
            when {
                birthday != null -> add("年龄：$age 岁（生日 ${birthday}，按今天实时计算）。")
                age != null -> add("年龄：$age 岁。")
            }
            line("家乡", profile.hometown)
            line("常驻城市", profile.city)
            line("学历", profile.education)
            line("宠物", profile.pets)
            line("感情状态", profile.relationship)
            line("外貌特征", profile.appearance)
            line("性格设定", profile.personality)
            line(
                "希望你怎么跟${pronoun}说话",
                profile.tone,
                suffix = "（只调称呼、直接度和话题远近；你自己的口吻、身份与安全规则仍然优先）",
            )
            line("生活偏好", profile.lifestyle)
            line("经历补充", profile.background)
        }
        if (facts.isEmpty()) return ""
        val builder = StringBuilder(HEADER)
        for (fact in facts) {
            val next = "\n- $fact"
            if (builder.length + next.length > limit) {
                val room = limit - builder.length
                // 至少留得下一行有意义的残句；否则整段截到这里。
                if (room > 8) builder.append("\n- ").append(fact.take(room - 4)).append("…")
                break
            }
            builder.append(next)
        }
        return builder.toString()
    }

    private fun MutableList<String>.line(label: String, raw: String, suffix: String = "") {
        val value = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("；")
            .take(120)
        if (value.isNotEmpty()) add("$label：$value$suffix。")
    }
}
