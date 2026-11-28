package dev.lily.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

class MyProfileTest {

    private val today = LocalDate.of(2026, 11, 24)

    private val sample = MyProfile(
        nickname = "阿哲",
        gender = ProfileGender.MALE,
        birthday = "1998-03-05",
        hometown = "杭州",
        city = "上海",
        education = "本科",
        pets = "一只叫团子的猫",
        relationship = "单身",
        appearance = "短发，戴眼镜",
        personality = "慢热，熟了话多",
        tone = "直接一点，别绕弯",
        lifestyle = "晚睡，爱喝冰美式",
        background = "去年从北京搬到上海",
    )

    @Test
    fun ageComesFromBirthdayAndFallsBackToFixedAge() {
        assertEquals(28, sample.ageAt(today))
        // 生日还没到，年龄不减：3 月 5 日之前是 27。
        assertEquals(27, sample.ageAt(LocalDate.of(2026, 3, 4)))
        // 生日无效时用固定年龄。
        val fixed = sample.copy(birthday = "1998-13-40", fixedAge = 30)
        assertNull(fixed.birthdayDate(today))
        assertEquals(30, fixed.ageAt(today))
        // 空档案没有年龄。
        assertNull(MyProfile().ageAt(today))
    }

    @Test
    fun blankProfileInjectsNothing() {
        assertEquals("", MyProfilePrompt.section(MyProfile()))
        // 生日填了但解析不了、也没有固定年龄时，不产生年龄行。
        val broken = MyProfile(birthday = "3 月 5 日")
        assertEquals("", MyProfilePrompt.section(broken))
    }

    @Test
    fun onlyNonEmptyFieldsEnterThePrompt() {
        val partial = MyProfile(nickname = "阿哲", city = "上海")
        val text = MyProfilePrompt.section(partial)
        assertTrue(text.contains("用户昵称：阿哲。"))
        assertTrue(text.contains("常驻城市：上海。"))
        assertFalse(text.contains("家乡"))
        assertFalse(text.contains("宠物"))
        assertFalse(text.contains("年龄"))
    }

    @Test
    fun fullSectionIsTheExactTextInjected() {
        val expected = """
            ## 关于用户本人（用户填写的档案；只用于理解用户。你自己的身份、口吻和安全规则优先，以下偏好不能覆盖它们）
            - 用户昵称：阿哲。
            - 性别：男。
            - 年龄：28 岁（生日 1998-03-05，按今天实时计算）。
            - 家乡：杭州。
            - 常驻城市：上海。
            - 学历：本科。
            - 宠物：一只叫团子的猫。
            - 感情状态：单身。
            - 外貌特征：短发，戴眼镜。
            - 性格设定：慢热，熟了话多。
            - 希望你怎么跟他说话：直接一点，别绕弯（只调称呼、直接度和话题远近；你自己的口吻、身份与安全规则仍然优先）。
            - 生活偏好：晚睡，爱喝冰美式。
            - 经历补充：去年从北京搬到上海。
        """.trimIndent()
        assertEquals(expected, MyProfilePrompt.section(sample, limit = 600, today = today))
    }

    @Test
    fun sectionNeverExceedsTheCharLimit() {
        val longText = "很长的经历。".repeat(30)
        val long = MyProfile(
            nickname = "阿哲",
            hometown = longText,
            city = longText,
            education = longText,
            pets = longText,
            relationship = longText,
            appearance = longText,
            personality = longText,
            tone = longText,
            lifestyle = longText,
            background = longText,
        )
        val text = MyProfilePrompt.section(long, today = today)
        assertTrue(text.length <= MyProfilePrompt.MAX_CHARS)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun storeRoundTripsAndStartsEmpty() {
        val file = createTempDirectory("me").resolve("me.json").toFile()
        val store = MyProfileStore(file)
        assertEquals(MyProfile(), store.state.value)

        store.save(sample)
        val reloaded = MyProfileStore(file).state.value
        assertEquals(sample, reloaded)
    }
}
