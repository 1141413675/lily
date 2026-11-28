package dev.lily.app.settings

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
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

    private val chat = ModelSettings(model = "doubao-chat", apiKey = key, temperature = 0.3)
    private val operatorModel =
        ModelSettings(baseUrl = "https://example.com/v1", model = "doubao-vision", apiKey = key)
    private val sample = AppSettings(
        chat = chat,
        operator = operatorModel,
        chatVendors = mapOf(chat.vendor to chat),
        operatorVendors = mapOf(operatorModel.vendor to operatorModel),
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
        // 朗读与通话音色在升级后统一为一份：取当前朗读引擎那处，写回朗读并记进人格设定。
        // 实时通道是 DashScope，它不认识朗读引擎音色名，所以保留自己的预置音色。
        val voice = sample.tts.active.voice
        val expected = sample.copy(
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
        assertEquals(dev.lily.app.persona.Voices.DEFAULT_FEMALE, fresh.speechVoice)
        assertEquals("", fresh.persona.voice)
        val male = fresh.copy(persona = fresh.persona.copy(gender = dev.lily.app.persona.Gender.MALE))
        assertEquals(dev.lily.app.persona.Voices.DEFAULT_MALE, male.speechVoice)
    }

    @Test fun `clone voice does not overwrite an incompatible realtime voice`() {
        val store = store()
        val base = store.current
        val clone = "speech:my-voice:abc:xyz"
        store.save(base.copy(persona = base.persona.copy(voice = clone)))
        val saved = store.current
        // 统一音色写在人格里；朗读按当前引擎换算后会拿到克隆 uri。
        assertEquals(clone, saved.persona.voice)
        assertEquals(clone, saved.ttsRequest.voice)
        // DashScope 实时通道保留原音色，不静默写成克隆 uri。
        assertEquals(AppSettings().realtime.voice, saved.realtime.voice)
        assertEquals(clone, saved.speechVoice)
    }

    @Test fun `switching text vendor keeps every vendor's key`() {
        val store = store()
        val base = store.current
        val ark = base.chat.copy(
            model = "doubao-seed-1-6-250615",
            apiKey = "ark-keep-me",
            vendor = ModelVendor.ARK,
        )
        store.save(base.copy(chat = ark, chatVendors = base.chatVendors + (ModelVendor.ARK to ark)))

        val deepseek = ModelSettings(
            baseUrl = ModelVendor.DEEPSEEK.baseUrl,
            model = "deepseek-chat",
            apiKey = "sk-deepseek-keep-me",
            vendor = ModelVendor.DEEPSEEK,
        )
        store.save(
            store.current.copy(
                chat = deepseek,
                chatVendors = store.current.chatVendors + (ModelVendor.DEEPSEEK to deepseek),
            ),
        )

        val current = store.current
        assertEquals("sk-deepseek-keep-me", current.chat.apiKey)
        assertEquals("ark-keep-me", current.chatVendors[ModelVendor.ARK]?.apiKey)

        // 切回方舟：地址、模型、Key 都从档案里回来，另一家的 Key 也还在。
        val back = SettingsForm.of(current).withChatVendor(ModelVendor.ARK)
        assertEquals(ModelVendor.ARK, back.chat.vendor)
        assertEquals("ark-keep-me", back.chat.apiKey)
        assertEquals("doubao-seed-1-6-250615", back.chat.model)
        store.save(back.toSettings()!!)
        val saved = store.current
        assertEquals("ark-keep-me", saved.chat.apiKey)
        assertEquals("sk-deepseek-keep-me", saved.chatVendors[ModelVendor.DEEPSEEK]?.apiKey)
        // 两把 Key 都参与脱敏，不会写进 trace 或日志。
        assertTrue("ark-keep-me" in saved.secrets)
        assertTrue("sk-deepseek-keep-me" in saved.secrets)
    }

    @Test fun `custom protocol survives a round trip`() {
        val store = store()
        val custom = ModelSettings(
            baseUrl = "https://gateway.example.com/v1",
            model = "claude-proxy",
            apiKey = "custom-key",
            vendor = ModelVendor.CUSTOM,
            protocol = dev.lily.api.ApiProtocol.ANTHROPIC,
        )
        store.save(store.current.copy(chat = custom))
        val loaded = store.current
        assertEquals(ModelVendor.CUSTOM, loaded.chat.vendor)
        assertEquals(dev.lily.api.ApiProtocol.ANTHROPIC, loaded.chat.protocol)
        assertEquals("https://gateway.example.com/v1", loaded.chat.baseUrl)
        // 请求配置也带上了声明的协议。
        assertEquals(
            dev.lily.api.ApiProtocol.ANTHROPIC,
            loaded.chat.toModelConfig(dev.lily.api.ModelRole.CHAT).protocol,
        )
    }

    @Test fun `toString never shows the api key`() {
        assertFalse(key in sample.toString())
        assertFalse(key in sample.chat.toString())
        assertFalse(key in sample.chat.toModelConfig(dev.lily.api.ModelRole.CHAT).toString())
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

    @Test fun `explicitly selected call reply survives reloading the settings`() {
        val settings = store()
        settings.save(settings.current.copy(replyMode = ReplyMode.CALL, replyModeChosen = true))
        val reloaded = store().current
        assertEquals(ReplyMode.CALL, reloaded.replyMode)
        assertTrue(reloaded.replyModeChosen)
        assertFalse(reloaded.replyModeNotice)
    }

    @Test fun `realtime voice can be enabled and disabled across reloads`() {
        val settings = store()
        val enabled = requireNotNull(SettingsForm.of(settings.current).copy(realtimeEnabled = true).toSettings())
        settings.save(enabled)
        assertTrue(store().current.realtimeEnabled)

        val disabled = requireNotNull(SettingsForm.of(store().current).copy(realtimeEnabled = false).toSettings())
        settings.save(disabled)
        assertFalse(store().current.realtimeEnabled)
    }

    @Test fun `memory embeddings are off by default and persist separately from chat`() {
        val settings = store()
        assertFalse(settings.current.memoryEmbedding.active)
        val embeddingKey = "embedding-private-key"
        val draft = MemoryEmbeddingSettings(
            enabled = true,
            baseUrl = " https://embedding.example.com/v1/ ",
            model = " test-model ",
            apiKey = embeddingKey,
        )
        val form = SettingsForm.of(sample).copy(memoryEmbedding = draft)
        val configured = requireNotNull(form.toSettings())
        settings.save(configured)

        val restored = store().current
        assertEquals(configured.memoryEmbedding, restored.memoryEmbedding)
        assertTrue(restored.memoryEmbedding.active)
        assertEquals(key, restored.chat.apiKey)
        assertTrue(embeddingKey in restored.secrets)
        assertFalse(embeddingKey in restored.toString())
        assertFalse(embeddingKey in form.toString())
        val plain = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE)
        val secret = context.getSharedPreferences("secret-test", Context.MODE_PRIVATE)
        assertFalse(embeddingKey in plain.all.values)
        assertFalse(plain.contains("memoryEmbedding.baseUrl"))
        assertEquals(embeddingKey, secret.getString("memoryEmbedding.apiKey", null))

        settings.save(restored.copy(memoryEmbedding = restored.memoryEmbedding.copy(enabled = false)))
        val disabled = store().current.memoryEmbedding
        assertFalse(disabled.active)
        assertEquals(embeddingKey, disabled.apiKey)
        assertEquals("https://embedding.example.com/v1", disabled.baseUrl)
    }

    @Test fun `failed save cannot activate remote embeddings`() {
        val plain = context.getSharedPreferences("embedding-failed-plain", Context.MODE_PRIVATE)
        val disk = context.getSharedPreferences("embedding-failed-secret", Context.MODE_PRIVATE)
        val unwritable = object : SharedPreferences by disk {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by disk.edit() {
                override fun commit(): Boolean = false
            }
        }
        val store = SettingsStore(plain, unwritable)
        val enabled = store.current.copy(memoryEmbedding = MemoryEmbeddingSettings(
            enabled = true,
            baseUrl = "https://embedding.example/v1",
            model = "test-embedding",
        ))
        assertFalse(store.save(enabled))
        assertFalse(store.current.memoryEmbedding.active)
        assertFalse(SettingsStore(plain, disk).current.memoryEmbedding.active)
    }

    @Test fun `memory embedding accepts optional credentials with secure or loopback URLs`() {
        val localhostUrls = listOf(
            "http://127.0.0.1:8080/v1",
            "http://localhost:8080/v1",
            "http://[::1]:8080/v1",
        )
        for (url in localhostUrls + "https://example.com/api/v3") {
            val embedding = MemoryEmbeddingSettings(enabled = true, baseUrl = url, model = "test-model")
            assertTrue(embedding.active)
            assertEquals("$url/embeddings", embedding.requestUrl)
        }
        val invalidUrls = listOf(
            "http://192.0.2.3/v1",
            "http://localhost.example.com/v1",
            "https://name:password@example.com/v1",
            "https://example.com/v1?token=secret",
            "https://example.com/v1#fragment",
            "https://example.com/v1/embeddings",
        )
        for (url in invalidUrls) {
            val embedding = MemoryEmbeddingSettings(enabled = true, baseUrl = url, model = "test-model")
            assertFalse(embedding.active)
            assertNull(SettingsForm(memoryEmbedding = embedding).toSettings())
            assertFalse(requireNotNull(SettingsForm(memoryEmbedding = embedding.copy(enabled = false)).toSettings())
                .memoryEmbedding.active)
        }
    }

    @Test fun `asr provider and doubao key survive a round trip and stay encrypted`() {
        val store = store()
        val doubaoKey = "doubao-asr-key"
        val siliconKey = "sk-asr-silicon"
        val asr = AsrSettings(
            provider = AsrProvider.DOUBAO,
            silicon = VoiceSettings(
                baseUrl = "https://api.siliconflow.cn/v1",
                model = VoiceSettings.DEFAULT_ASR_MODEL,
                apiKey = siliconKey,
            ),
            doubao = VoiceSettings(
                baseUrl = VoiceSettings.DOUBAO_ASR_BASE_URL,
                apiKey = doubaoKey,
                provider = TtsProvider.DOUBAO,
                resourceId = "volc.seedasr.sauc.concurrent",
            ),
        )
        store.save(store.current.copy(asr = asr))

        val restored = store.current.asr
        assertEquals(AsrProvider.DOUBAO, restored.provider)
        assertEquals(doubaoKey, restored.doubao.apiKey)
        assertEquals("volc.seedasr.sauc.concurrent", restored.resourceId)
        assertEquals(siliconKey, restored.silicon.apiKey)
        // 地址固定，不随存盘值变化。
        assertEquals(VoiceSettings.DOUBAO_ASR_BASE_URL, restored.doubao.baseUrl)
        assertTrue(restored.configured)
        val saved = store.current
        assertTrue(doubaoKey in saved.secrets)
        assertTrue(siliconKey in saved.secrets)

        val plain = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE).all.values.joinToString()
        val secret = context.getSharedPreferences("secret-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(doubaoKey in plain)
        assertFalse(siliconKey in plain)
        assertTrue(doubaoKey in secret)
        assertTrue(siliconKey in secret)

        // 切回硅基只改 provider，豆包 Key 不会被清掉。
        store.save(store.current.copy(asr = store.current.asr.copy(provider = AsrProvider.SILICONFLOW)))
        assertEquals(AsrProvider.SILICONFLOW, store.current.asr.provider)
        assertEquals(doubaoKey, store.current.asr.doubao.apiKey)
    }

    @Test fun `primary role retains legacy birthdays and round trips new profile fields`() {
        val prefs = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE)
        prefs.edit().putInt("persona.birthdayYear", 2000).putInt("persona.birthdayMonth", 2).commit()
        val settings = store()
        val previous = settings.current
        assertNull(previous.persona.birthdayDay)
        assertEquals(28, previous.persona.ageAt(LocalDate.of(2028, 2, 28)))

        val updated = previous.persona.copy(
            birthdayDay = 29, hometown = "苏州", education = "本科", relationshipStatus = "有伴侣",
            appearance = "短发", personality = "爱观察", speechStyle = "直爽", lifestyle = "爱阅读", background = "修过旧书",
        )
        settings.save(previous.copy(persona = updated))
        assertEquals(updated, store().current.persona)
    }
}
