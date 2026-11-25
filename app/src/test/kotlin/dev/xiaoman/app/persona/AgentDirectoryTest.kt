package dev.xiaoman.app.persona

import dev.xiaoman.app.chat.EmojiStyle
import dev.xiaoman.app.life.LifeSettings
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.RelationSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDirectoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test fun `added roles round trip independently while getting model credentials only at runtime`() {
        val file = File(temporary.root, "agents.json")
        val directory = AgentDirectory(file)
        val added = assertNotNull(directory.create(PersonaSettings(nickname = " 小海 ")))
        val other = assertNotNull(directory.create(PersonaSettings(nickname = "小海")))
        assertNotEquals(added.id, other.id)
        assertEquals("小海", added.persona.nickname)

        val defaults = AppSettings()
        val secret = "key-kept-in-shared-settings"
        val shared = defaults.copy(
            chat = defaults.chat.copy(model = "test-model", apiKey = secret),
            persona = PersonaSettings(nickname = "小鱼", voice = "Jane"),
            realtime = defaults.realtime.copy(voice = "Jane"),
            personaVoiceNotice = true,
        )
        val cloneVoice = "speech:role-only"
        val persona = added.persona.copy(
            gender = Gender.MALE, bio = "陈述事实", voice = cloneVoice, voiceChosen = true,
            birthdayYear = 1990, birthdayMonth = 10, birthdayDay = 7, hometown = "青岛", education = "本科",
            relationshipStatus = "有伴侣", appearance = "短发", personality = "爱观察", speechStyle = "直爽",
            lifestyle = "散步", background = "写过诗",
        )
        val life = LifeSettings(city = "杭州", doing = "写作")
        assertTrue(directory.update(added.id, added.effectiveSettings(shared).copy(
            persona = persona, life = life, emojiStyle = EmojiStyle.RICH, tts = shared.tts.withSpeed(1.2f),
        )))
        assertFalse(directory.update(AgentDirectory.PRIMARY_ID, shared))
        assertFalse(secret in file.readText())

        val restored = AgentDirectory(file)
        assertEquals(listOf(added.id, other.id), restored.agents.value.map { it.id })
        assertEquals(other, restored.find(other.id))
        val effective = assertNotNull(restored.find(added.id)).effectiveSettings(shared)
        assertEquals(persona, effective.persona)
        assertEquals(life, effective.life)
        assertEquals(EmojiStyle.RICH, effective.emojiStyle)
        assertEquals(1.2f, effective.tts.speed)
        // 音色留在角色档案里，全局朗读配置不再被角色音色覆盖。
        assertEquals(cloneVoice, effective.persona.voice)
        assertEquals(defaults.tts.active.voice, effective.tts.active.voice)
        assertEquals(cloneVoice, effective.ttsRequest.voice)
        assertEquals(defaults.realtime.voice, effective.realtime.voice)
        assertEquals(shared.chat, effective.chat)
        assertFalse(effective.personaVoiceNotice)
        assertEquals("小鱼", shared.persona.nickname)
    }

    @Test fun `broken snapshots are preserved and never replaced by new roles`() {
        val file = temporary.newFile("agents.json")
        val invalid = """[{"id":"../outside","persona":{"nickname":"unsafe"}}]"""
        file.writeText(invalid)
        val directory = AgentDirectory(file)

        assertTrue(directory.agents.value.isEmpty())
        assertNull(directory.create(PersonaSettings(nickname = "新角色")))
        assertEquals(invalid, file.readText())
    }

    @Test fun `failed writes do not publish a role`() {
        val blocker = temporary.newFile("blocked")
        val directory = AgentDirectory(File(blocker, "agents.json"))

        assertNull(directory.create(PersonaSettings(nickname = "新角色")))
        assertTrue(directory.agents.value.isEmpty())
    }

    @Test fun `legacy added roles opt into proactive settings independently of profile updates`() {
        val file = temporary.newFile("agents.json")
        val oldId = "99571761-a3b7-4f5c-afc0-89bc643766ca"
        file.writeText("""[{"id":"$oldId","persona":{"nickname":"旧角色","birthdayYear":2000,"birthdayMonth":2}}]""")
        val directory = AgentDirectory(file)
        val legacy = assertNotNull(directory.find(oldId))
        assertNull(legacy.persona.birthdayDay)
        assertFalse(legacy.relation.proactive)
        val second = assertNotNull(directory.create(PersonaSettings(nickname = "新角色")))
        assertFalse(second.relation.proactive)

        val shared = AppSettings(relation = RelationSettings(proactive = true, maxPerDay = 3))
        val staleEditor = legacy.effectiveSettings(shared)
        assertFalse(staleEditor.relation.proactive)
        val optedIn = legacy.relation.copy(proactive = true, maxPerDay = 2, quietStart = "21:00")
        assertTrue(directory.updateRelation(oldId, optedIn))
        assertTrue(directory.update(oldId, staleEditor.copy(persona = legacy.persona.copy(background = "编辑后的经历"))))
        val restored = AgentDirectory(file)
        assertEquals(optedIn, assertNotNull(restored.find(oldId)).effectiveSettings(shared).relation)
        assertFalse(assertNotNull(restored.find(second.id)).effectiveSettings(shared).relation.proactive)
        assertEquals(3, shared.relation.maxPerDay)
    }

    @Test fun `two agents keep their own voices and fall back by gender after switching engines`() {
        val directory = AgentDirectory(File(temporary.root, "agents.json"))
        val firstVoice = Voices.DOUBAO_FEMALE[0].id
        val secondVoice = Voices.DOUBAO_FEMALE[1].id
        val first = assertNotNull(
            directory.create(PersonaSettings(nickname = "甲", gender = Gender.FEMALE, voice = firstVoice, voiceChosen = true)),
        )
        val second = assertNotNull(
            directory.create(PersonaSettings(nickname = "乙", gender = Gender.MALE, voice = secondVoice, voiceChosen = true)),
        )

        val doubao = AppSettings(tts = dev.xiaoman.app.settings.TtsSettings(
            provider = dev.xiaoman.app.settings.TtsProvider.DOUBAO,
            doubao = dev.xiaoman.app.settings.VoiceSettings(
                baseUrl = dev.xiaoman.app.settings.VoiceSettings.DOUBAO_BASE_URL,
                voice = "zh_male_wennuanahu_uranus_bigtts",
                provider = dev.xiaoman.app.settings.TtsProvider.DOUBAO,
                resourceId = dev.xiaoman.app.settings.VoiceSettings.DOUBAO_RESOURCE_ID,
            ),
        ))
        assertEquals(firstVoice, first.effectiveSettings(doubao).ttsRequest.voice)
        assertEquals(secondVoice, second.effectiveSettings(doubao).ttsRequest.voice)

        // 全局里放一把别的音色，不影响已经选过的角色；没选过的按性别取默认。
        val blank = assertNotNull(directory.create(PersonaSettings(nickname = "丙", gender = Gender.MALE)))
        assertEquals(Voices.doubaoDefault(Gender.MALE), blank.effectiveSettings(doubao).ttsRequest.voice)

        // 切到硅基：两个 Agent 的豆包音色都不通用，各自按性别回退，互不影响。
        val silicon = AppSettings(tts = dev.xiaoman.app.settings.TtsSettings(
            provider = dev.xiaoman.app.settings.TtsProvider.SILICONFLOW,
        ))
        assertEquals(Voices.defaultVoice(Gender.FEMALE), first.effectiveSettings(silicon).ttsRequest.voice)
        assertEquals(Voices.defaultVoice(Gender.MALE), second.effectiveSettings(silicon).ttsRequest.voice)
    }
}
