package dev.xiaoman.app.settings

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val key = "ark-0123456789abcdef"

    private fun store() = SettingsStore(
        context.getSharedPreferences("plain-test", Context.MODE_PRIVATE),
        context.getSharedPreferences("secret-test", Context.MODE_PRIVATE),
    )

    private val sample = AppSettings(
        chat = ModelSettings(model = "doubao-chat", apiKey = key, temperature = 0.3),
        operator = ModelSettings(baseUrl = "https://example.com/v1", model = "doubao-vision", apiKey = key),
        maxSteps = 12,
        learnFromSuccess = false,
    )

    @Test fun `defaults point at Volcano Ark and are not configured`() {
        val s = store().current
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", s.chat.baseUrl)
        assertFalse(s.configured)
    }

    @Test fun `round trip keeps every field and keys stay out of the plain file`() {
        store().save(sample)
        val loaded = store().current
        // 朗读与通话音色在升级后统一为一份：取朗读那处，写回朗读并记进人格设定。
        // 实时通道是 DashScope，它不认识 SiliconFlow 音色名，所以保留自己的预置音色。
        val voice = sample.tts.voice
        val expected = sample.copy(
            tts = sample.tts.copy(voice = voice),
            realtime = sample.realtime,
            persona = sample.persona.copy(voice = voice),
            // 两处旧音色不同，首次统一时给用户一条确认提示。
            personaVoiceNotice = true,
        )
        assertEquals(expected, loaded)
        assertTrue(loaded.configured)
        val plain = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(key in plain)
        val secret = context.getSharedPreferences("secret-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertTrue(key in secret)
    }

    @Test fun `fresh install defaults to claire for her and alex for him`() {
        val fresh = store().current
        assertEquals(dev.xiaoman.app.persona.Voices.DEFAULT_FEMALE, fresh.speechVoice)
        assertEquals("", fresh.persona.voice)
        val male = fresh.copy(persona = fresh.persona.copy(gender = dev.xiaoman.app.persona.Gender.MALE))
        assertEquals(dev.xiaoman.app.persona.Voices.DEFAULT_MALE, male.speechVoice)
    }

    @Test fun `clone voice does not overwrite an incompatible realtime voice`() {
        val store = store()
        val base = store.current
        val clone = "speech:my-voice:abc:xyz"
        store.save(base.copy(persona = base.persona.copy(voice = clone)))
        val saved = store.current
        assertEquals(clone, saved.tts.voice)
        // DashScope 实时通道保留原音色，不静默写成克隆 uri。
        assertEquals(AppSettings().realtime.voice, saved.realtime.voice)
        assertEquals(clone, saved.speechVoice)
    }

    @Test fun `toString never shows the api key`() {
        assertFalse(key in sample.toString())
        assertFalse(key in sample.chat.toString())
        assertFalse(key in sample.chat.toModelConfig(dev.xiaoman.api.ModelRole.CHAT).toString())
        assertFalse(key in SettingsForm.of(sample).toString())
        assertTrue("***" in sample.chat.toString())
    }

    @Test fun `form parses and validates numbers`() {
        val form = SettingsForm.of(sample)
        assertEquals(sample, form.toSettings())
        assertNull(form.copy(maxSteps = "0").toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "hot")).toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "")).toSettings()?.chat?.temperature)
    }
}
