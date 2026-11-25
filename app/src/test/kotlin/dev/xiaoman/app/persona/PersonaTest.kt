package dev.xiaoman.app.persona

import dev.xiaoman.agent.Prompts
import dev.xiaoman.app.life.LifeSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test
    fun blankNicknameFallsBackToAppNameInsteadOfHardcodedDefault() {
        val p = PersonaSettings(nickname = "")
        val prompt = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, p, fallbackName = "Lily")
        assertTrue(prompt.contains("你是Lily"))
        assertTrue(prompt.contains("你的昵称：Lily"))
        assertFalse(prompt.contains("小满"))
    }

    @Test
    fun nicknameAndRulesEnterThePrompt() {
        val p = PersonaSettings(nickname = "阿岚")
        val prompt = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, p)
        assertTrue(prompt.contains("你是阿岚"))
        assertFalse(prompt.contains("你是小满"))
        assertTrue(prompt.contains("你的昵称：阿岚"))
        assertTrue(prompt.contains("「她」"))
        // 安全与行为规则仍然在。
        assertTrue(prompt.contains("付款、转账"))
    }

    @Test
    fun toneAndLengthChangeThePrompt() {
        val restrained = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, PersonaSettings(tone = Tone.RESTRAINED, length = Length.SHORT))
        val playful = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, PersonaSettings(tone = Tone.PLAYFUL, length = Length.LONG))
        assertTrue(restrained.contains(Tone.RESTRAINED.rule))
        assertTrue(restrained.contains(Length.SHORT.rule))
        assertTrue(playful.contains(Tone.PLAYFUL.rule))
        assertTrue(playful.contains(Length.LONG.rule))
        assertNotEquals(restrained, playful)
    }

    @Test
    fun genderDecidesPronounAndNeverUsesIt() {
        assertEquals("她", PersonaSettings(gender = Gender.FEMALE).pronoun)
        assertEquals("他", PersonaSettings(gender = Gender.MALE).pronoun)
        assertEquals("TA", PersonaSettings(gender = Gender.UNSPECIFIED).pronoun)
        Gender.entries.forEach {
            assertNotEquals("它", it.pronoun)
            assertTrue(PersonaPrompt.section(PersonaSettings(gender = it)).contains("「${it.pronoun}」"))
        }
    }

    @Test
    fun selfReferenceFollowsTheChoice() {
        assertEquals("我", PersonaSettings(selfRef = SelfRef.ME).selfName)
        assertEquals("Lily", PersonaSettings(selfRef = SelfRef.NAME).selfName)
        assertEquals("本人", PersonaSettings(selfRef = SelfRef.CUSTOM, customSelf = "本人").selfName)
        // 自定义留空时回退昵称，不出现空自称。
        assertEquals("阿岚", PersonaSettings(nickname = "阿岚", selfRef = SelfRef.CUSTOM, customSelf = "  ").selfName)
    }

    @Test
    fun resetReturnsDefaultsButKeepsTheVoice() {
        val changed = PersonaSettings(nickname = "阿岚", gender = Gender.MALE, tone = Tone.PLAYFUL, length = Length.LONG, voice = "v-1")
        val reset = PersonaSettings.reset(changed)
        assertEquals(PersonaSettings.DEFAULT_NICKNAME, reset.nickname)
        assertEquals(Gender.FEMALE, reset.gender)
        assertEquals(Tone.GENTLE, reset.tone)
        assertEquals(Length.SHORT, reset.length)
        assertEquals("v-1", reset.voice)
    }

    @Test
    fun blankNicknameFallsBackInsteadOfEmpty() {
        assertEquals(PersonaSettings.DEFAULT_NICKNAME, PersonaSettings(nickname = "   ").name)
    }

    @Test
    fun expressionScaleChangesWordingAndDefaultsToClose() {
        val reserved = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, PersonaSettings(expression = Expression.RESERVED))
        val intimate = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, PersonaSettings(expression = Expression.INTIMATE))
        assertTrue(reserved.contains(Expression.RESERVED.rule))
        assertTrue(intimate.contains(Expression.INTIMATE.rule))
        assertNotEquals(reserved, intimate)
        assertEquals(Expression.CLOSE, PersonaSettings.DEFAULT.expression)
        // 三档示例台词必须彼此不同，作为「看得出差别」的基准。
        assertEquals(3, Expression.entries.map { it.sample }.distinct().size)
    }

    @Test
    fun voiceListIsGroupedByGenderAndKeepsLegacyDefault() {
        assertTrue(Voices.forGender(Gender.FEMALE).all { it.gender == Gender.FEMALE })
        assertTrue(Voices.forGender(Gender.MALE).all { it.gender == Gender.MALE })
        assertEquals(Voices.ALL.size, Voices.forGender(Gender.UNSPECIFIED).size)
        assertTrue(Voices.of(Voices.LEGACY_DEFAULT) != null)
    }

    @Test
    fun defaultVoiceIsClaireForHerAndAlexForHim() {
        assertEquals(Voices.DEFAULT_FEMALE, Voices.defaultVoice(Gender.FEMALE))
        assertEquals(Voices.DEFAULT_MALE, Voices.defaultVoice(Gender.MALE))
        assertEquals(Voices.DEFAULT_FEMALE, Voices.defaultVoice(Gender.UNSPECIFIED))
        // 显式选择优先，切换性别不会静默改掉已有音色。
        val male = PersonaSettings(gender = Gender.MALE, voice = Voices.DEFAULT_FEMALE)
        assertEquals(Voices.DEFAULT_FEMALE, male.voice)
        assertEquals(Voices.DEFAULT_FEMALE, Voices.effective(male.voice, male.gender))
        assertEquals(Voices.DEFAULT_FEMALE, Voices.effective("", Gender.FEMALE))
        assertEquals(Voices.DEFAULT_MALE, Voices.effective("  ", Gender.MALE))
    }

    @Test
    fun deletingACloneFallsBackToTheGenderDefault() {
        val clone = "speech:mine:a:b"
        val using = PersonaSettings(gender = Gender.FEMALE, voice = clone)
        assertEquals("", PersonaSettings.withoutVoice(using, clone).voice)
        // 不在用的音色被删时不动当前选择。
        assertEquals(clone, PersonaSettings.withoutVoice(using, "speech:other").voice)
        assertEquals(Voices.DEFAULT_FEMALE, Voices.effective(PersonaSettings.withoutVoice(using, clone).voice, Gender.FEMALE))
    }

    @Test
    fun exactBirthdayUsesTheDayAndLegacyBirthdayKeepsMonthPrecision() {
        val legacy = PersonaSettings(birthdayYear = 2000, birthdayMonth = 2)
        val exact = legacy.copy(birthdayDay = 29)
        val beforeBirthday = LocalDate.of(2028, 2, 28)
        assertEquals(null, legacy.birthdayDate())
        assertEquals(28, legacy.ageAt(beforeBirthday))
        assertEquals(LocalDate.of(2000, 2, 29), exact.birthdayDate())
        assertEquals(27, exact.ageAt(beforeBirthday))
        assertEquals(28, exact.ageAt(LocalDate.of(2028, 2, 29)))
        assertEquals(null, legacy.copy(birthdayDay = 30).birthdayDate())
    }

    @Test
    fun rolePromptUsesItsOwnProfileAndLifeFields() {
        val role = PersonaSettings(
            nickname = "海风",
            birthdayYear = 1997,
            birthdayMonth = 3,
            birthdayDay = 5,
            hometown = "苏州",
            education = "本科",
            relationshipStatus = "有伴侣",
            appearance = "短发",
            personality = "爱观察",
            speechStyle = "直爽",
            lifestyle = "爱逛旧书店",
            background = "修过旧书",
        )
        val prompt = PersonaPrompt.rewrite(Prompts.DEFAULT_PERSONA, role, life = LifeSettings(city = "杭州", pet = "猫"))
        for (fact in listOf(
            "生日：1997-03-05", "家乡：苏州", "学历：本科", "感情状态：有伴侣", "外貌特征：短发",
            "性格设定：爱观察", "说话语气：直爽", "生活偏好：爱逛旧书店", "经历补充：修过旧书",
            "常驻城市：杭州", "宠物：猫",
        )) {
            assertTrue(prompt.contains(fact))
        }
        assertFalse(prompt.contains(Tone.GENTLE.rule))
        assertFalse(PersonaPrompt.section(PersonaSettings()).contains("你的角色档案"))
    }
}
