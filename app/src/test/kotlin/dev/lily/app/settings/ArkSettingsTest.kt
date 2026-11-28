package dev.lily.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「火山方舟一体」：一个 Key 打通多项能力。
 *
 * 覆盖三件事：
 * 1. 开了哪几项，就只接管那几项，其余原样不动；
 * 2. 接管之后，各处读到的确实是方舟的地址、Key 与模型；
 * 3. 没填 Key 时什么也不接管——填错 Key 不该把用户原有的配置冲掉。
 */
class ArkSettingsTest {

    private fun form(
        apiKey: String = "ark-test",
        useChat: Boolean = false,
        useOperator: Boolean = false,
        useImage: Boolean = false,
        useVoice: Boolean = false,
    ) = SettingsForm(
        chat = SettingsForm.ModelForm(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1",
            apiKey = "sk-openai",
            vendor = ModelVendor.OPENAI,
        ),
        operator = SettingsForm.ModelForm(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1-mini",
            apiKey = "sk-openai",
            vendor = ModelVendor.OPENAI,
        ),
        ark = SettingsForm.ArkForm(
            apiKey = apiKey,
            chatModel = "doubao-seed-2-1-pro-260915",
            operatorModel = "doubao-seed-2-1-lite-260915",
            imageModel = "doubao-seedream-5-0-flash-260915",
            useChat = useChat,
            useOperator = useOperator,
            useImage = useImage,
            useVoice = useVoice,
        ),
    )

    @Test
    fun chatAndOperatorAreTakenOverByArk() {
        val settings = form(useChat = true, useOperator = true).toSettings()!!

        assertEquals("https://ark.cn-beijing.volces.com/api/v3", settings.chat.baseUrl)
        assertEquals("ark-test", settings.chat.apiKey)
        assertEquals("doubao-seed-2-1-pro-260915", settings.chat.model)
        assertEquals(ModelVendor.ARK, settings.chat.vendor)

        assertEquals("doubao-seed-2-1-lite-260915", settings.operator.model)
        assertEquals("ark-test", settings.operator.apiKey)
    }

    @Test
    fun untouchedCapabilitiesKeepTheirOwnSettings() {
        val settings = form(useChat = true).toSettings()!!

        // 操作模型没开方舟，就还是 OpenAI 那套。
        assertEquals(ModelVendor.OPENAI, settings.operator.vendor)
        assertEquals("sk-openai", settings.operator.apiKey)
        assertEquals("gpt-4.1-mini", settings.operator.model)
    }

    @Test
    fun imageModelIsTakenOver() {
        val settings = form(useImage = true).toSettings()!!

        assertEquals("doubao-seedream-5-0-flash-260915", settings.image.effectiveModel)
    }

    @Test
    fun voiceSwitchesToDoubaoWithTheSameKey() {
        val settings = form(useVoice = true).toSettings()!!

        assertEquals(TtsProvider.DOUBAO, settings.tts.provider)
        assertEquals("ark-test", settings.tts.doubao.apiKey)
        assertEquals(AsrProvider.DOUBAO, settings.asr.provider)
        assertEquals("ark-test", settings.asr.doubao.apiKey)
        assertEquals("ark-test", settings.realtime.apiKey)
    }

    @Test
    fun anEmptyKeyTakesOverNothing() {
        val settings = form(apiKey = "", useChat = true, useVoice = true).toSettings()!!

        assertEquals(ModelVendor.OPENAI, settings.chat.vendor)
        assertEquals("sk-openai", settings.chat.apiKey)
        assertEquals(TtsProvider.SILICONFLOW, settings.tts.provider)
    }

    @Test
    fun applyingTwiceChangesNothing() {
        val once = form(useChat = true, useImage = true).applyArk()
        val twice = once.applyArk()

        assertEquals(once, twice)
    }

    @Test
    fun arkKeyIsRecognisedByItsPrefix() {
        assertTrue(ArkSettings(apiKey = "ark-abc").looksLikeArkOnlyKey)
        assertFalse(ArkSettings(apiKey = "iam-abc").looksLikeArkOnlyKey)
        assertFalse(ArkSettings(apiKey = "").looksLikeArkOnlyKey)
    }

    @Test
    fun arkKeyIsMaskedInSecrets() {
        val settings = form(useChat = true).toSettings()!!
        assertTrue("ark-test" in settings.secrets)
    }

    @Test
    fun thePreviousVendorProfileIsKeptForSwitchingBack() {
        // 方舟接管聊天后，原来的 OpenAI 配置仍在档案里，切回去不用重填。
        val openAiProfile = ModelSettings(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1",
            apiKey = "sk-openai",
            vendor = ModelVendor.OPENAI,
        )
        val settings = form(useChat = true)
            .copy(chatVendors = mapOf(ModelVendor.OPENAI to openAiProfile))
            .toSettings()!!

        val kept = settings.chatVendors[ModelVendor.OPENAI]
        assertEquals("sk-openai", kept?.apiKey)
        assertEquals("gpt-4.1", kept?.model)
        // 当前生效的是方舟。
        assertEquals(ModelVendor.ARK, settings.chat.vendor)
    }
}
