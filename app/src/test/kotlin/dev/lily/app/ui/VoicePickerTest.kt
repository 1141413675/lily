package dev.lily.app.ui

import dev.lily.app.persona.Voices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色选择器的取名与搜索规则。
 *
 * 原来每行显示「清澈梓梓 2.0 · zh_female_qingchezizi_uranus_bigtts」和一行「豆包 2.0」——
 * 原始 id 是工程术语，说明文字 90 个全一样，等于没有信息。
 * 这里把「界面上不给人看的东西」钉住，免得以后又被加回去。
 */
class VoicePickerTest {
    @Test fun doubaoLabelsDropTheSharedVersionSuffix() {
        val voices = Voices.DOUBAO_FEMALE.toPickerVoices()
        assertTrue(voices.isNotEmpty())
        // 「 2.0」整组共有，不带区分度，去掉。
        assertTrue(voices.none { it.name.contains("2.0") })
        assertTrue(voices.any { it.name == "清澈梓梓" })
    }

    @Test fun siliconLabelsKeepTheirOwnNames() {
        val voices = Voices.FEMALE.toPickerVoices()
        // 硅基这组没有共同后缀，名字原样保留。
        assertEquals(listOf("沉稳", "激情", "温柔", "欢快"), voices.map { it.name })
    }

    @Test fun doubaoDescriptionsAreAllIdenticalSoTheyAreHidden() {
        // 90 个全是「豆包 2.0」，没有信息量。
        assertFalse(Voices.DOUBAO_FEMALE.toPickerVoices().hasDistinctDescriptions())
    }

    @Test fun siliconDescriptionsDifferSoTheyAreKept() {
        // 硅基每个音色的说明不同，有信息量，保留。
        assertTrue(Voices.FEMALE.toPickerVoices().hasDistinctDescriptions())
    }

    @Test fun searchFindsByNameAcrossGroups() {
        val all = Voices.DOUBAO_FEMALE.toPickerVoices() + Voices.DOUBAO_MALE.toPickerVoices()
        val hits = all.searchVoices("御姐")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.name.contains("御姐") })
        assertTrue(hits.any { it.name == "高冷御姐" })
    }

    @Test fun searchIsTrimmedAndCaseInsensitive() {
        val all = Voices.DOUBAO_FEMALE.toPickerVoices()
        assertEquals(all.searchVoices("  灿灿  ").size, all.searchVoices("灿灿").size)
        assertEquals(all.size, all.searchVoices("   ").size)
    }

    @Test fun searchWithNoMatchReturnsEmpty() {
        assertTrue(Voices.DOUBAO_FEMALE.toPickerVoices().searchVoices("不存在的音色").isEmpty())
    }

    @Test fun theRawVoiceIdNeverReachesTheName() {
        val voices = Voices.DOUBAO_FEMALE.toPickerVoices()
        assertTrue(voices.none { it.name.contains("_") })
        assertTrue(voices.none { it.name.contains("uranus") })
    }
}
