package dev.lily.app.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 豆包 3.0 的预置音色清单。
 *
 * 3.0 与 2.0 的音色 id 尾缀都是 `_uranus_bigtts`，2.0 的判断函数如果不排除 3.0，
 * 就会把 3.0 的音色当成 2.0 的送进去——那会直接报错。
 */
class Doubao3VoicesTest {

    @Test
    fun everyVoiceLooksLikeA3_0Id() {
        assertTrue(Doubao3Voices.ALL.size >= 60, "官方 3.0 音色明显不止这么多：${Doubao3Voices.ALL.size}")
        Doubao3Voices.ALL.forEach { voice ->
            assertTrue(
                voice.id.startsWith("multi_"),
                "${voice.id} 不是 3.0 的 multi_ 前缀",
            )
            assertTrue(
                voice.id.endsWith("_v3_uranus_bigtts"),
                "${voice.id} 不是 3.0 的音色 id",
            )
            assertTrue(voice.label.isNotBlank(), "${voice.id} 缺中文名")
        }
        assertEquals(Doubao3Voices.ALL.size, Doubao3Voices.ALL.map { it.id }.distinct().size, "音色 id 有重复")
    }

    @Test
    fun bothGendersArePresent() {
        assertTrue(Doubao3Voices.FEMALE.isNotEmpty())
        assertTrue(Doubao3Voices.MALE.isNotEmpty())
        assertTrue(Doubao3Voices.FEMALE.all { it.gender == Gender.FEMALE })
        assertTrue(Doubao3Voices.MALE.all { it.gender == Gender.MALE })
    }

    @Test
    fun theDefaultsAreInTheList() {
        assertTrue(Voices.isDoubao3(Voices.DOUBAO3_DEFAULT_FEMALE))
        assertTrue(Voices.isDoubao3(Voices.DOUBAO3_DEFAULT_MALE))
    }

    @Test
    fun a3VoiceIsNotMistakenForA2Voice() {
        val id = Doubao3Voices.FEMALE.first().id
        assertTrue(Voices.isDoubao3(id), "3.0 音色要认成 3.0")
        assertFalse(Voices.isDoubao(id), "3.0 音色不能被当成 2.0 的")
    }

    @Test
    fun a2VoiceIsNotMistakenForA3Voice() {
        val id = Voices.DOUBAO_FEMALE.first().id
        assertTrue(Voices.isDoubao(id), "2.0 音色要认成 2.0")
        assertFalse(Voices.isDoubao3(id), "2.0 音色不能被当成 3.0 的")
    }
}
