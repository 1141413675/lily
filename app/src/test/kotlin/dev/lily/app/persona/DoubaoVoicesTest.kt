package dev.lily.app.persona

import dev.lily.app.settings.TtsSettings
import dev.lily.app.settings.VoiceSettings
import dev.lily.app.settings.VoiceVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 豆包 2.0 官方音色清单与默认选择。 */
class DoubaoVoicesTest {
    @Test
    fun `every doubao voice is a 2_0 uranus id in the right gender group`() {
        val all = Voices.DOUBAO_FEMALE + Voices.DOUBAO_MALE
        assertTrue(all.size >= 40, "官方 2.0 中文音色明显不止 ${all.size} 个，清单可能被截断了")
        assertTrue(Voices.DOUBAO_FEMALE.all { it.gender == Gender.FEMALE })
        assertTrue(Voices.DOUBAO_MALE.all { it.gender == Gender.MALE })
        for (voice in all) {
            assertTrue(voice.id.endsWith("_uranus_bigtts"), "${voice.id} 不是 2.0 音色")
            assertTrue(voice.id.contains("_female_") || voice.id.contains("_male_"), "${voice.id} 缺性别前缀")
            assertTrue(voice.label.isNotBlank(), "${voice.id} 缺中文名")
            assertEquals(voice, Voices.of(voice.id))
        }
        assertEquals(all.size, all.map { it.id }.distinct().size, "音色 id 有重复")
    }

    @Test
    fun `default speaker comes from the agent gender, not a global value`() {
        val defaults = TtsSettings()
        assertEquals("", defaults.configOf(VoiceVendor.DOUBAO).voice, "设置里不再保存全局音色")
        assertEquals("", defaults.configOf(VoiceVendor.SILICONFLOW).voice, "设置里不再保存全局音色")

        val doubao = TtsSettings(vendor = VoiceVendor.DOUBAO)
        assertEquals(Voices.DOUBAO_DEFAULT_FEMALE, doubao.request("", Gender.FEMALE).voice)
        assertEquals(Voices.DOUBAO_DEFAULT_FEMALE, doubao.request("", Gender.UNSPECIFIED).voice)
        assertEquals(Voices.DOUBAO_DEFAULT_MALE, doubao.request("", Gender.MALE).voice)
        // 两个默认档都在各自的官方列表里。
        assertTrue(Voices.DOUBAO_FEMALE.any { it.id == Voices.DOUBAO_DEFAULT_FEMALE })
        assertTrue(Voices.DOUBAO_MALE.any { it.id == Voices.DOUBAO_DEFAULT_MALE })
    }

    @Test
    fun `global voice value is ignored even if an old build stored one`() {
        val doubao = TtsSettings(
            vendor = VoiceVendor.DOUBAO,
            byVendor = mapOf(
                VoiceVendor.DOUBAO to VoiceSettings(
                    baseUrl = VoiceSettings.DOUBAO_BASE_URL,
                    voice = "zh_female_cancan_uranus_bigtts",
                    vendor = VoiceVendor.DOUBAO,
                    resourceId = VoiceSettings.DOUBAO_RESOURCE_ID,
                ),
            ),
        )
        assertEquals(Voices.DOUBAO_DEFAULT_MALE, doubao.request("", Gender.MALE).voice)

        val silicon = TtsSettings(
            vendor = VoiceVendor.SILICONFLOW,
            byVendor = mapOf(
                VoiceVendor.SILICONFLOW to VoiceSettings(
                    model = VoiceSettings.DEFAULT_TTS_MODEL,
                    voice = "zh_female_cancan_uranus_bigtts",
                ),
            ),
        )
        assertEquals(Voices.defaultVoice(Gender.FEMALE), silicon.request("", Gender.FEMALE).voice)
    }

    @Test
    fun `gender groups line up with the helper`() {
        assertEquals(Voices.DOUBAO_FEMALE, Voices.doubaoFor(Gender.FEMALE))
        assertEquals(Voices.DOUBAO_MALE, Voices.doubaoFor(Gender.MALE))
        assertTrue(Voices.isDoubao(Voices.DOUBAO_FEMALE.first().id))
        // 硅基音色不能被当成豆包音色送进 2.0。
        assertTrue(!Voices.isDoubao(Voices.DEFAULT_FEMALE))
    }
}
