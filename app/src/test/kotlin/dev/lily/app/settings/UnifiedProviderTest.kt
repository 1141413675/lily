package dev.lily.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型接法：一体化还是自定义。
 *
 * - 一体化：挑一个服务方，一个 Key 打通它的全部模型；
 * - 自定义：每一项各挑各的厂家。
 */
class UnifiedProviderTest {

    private fun form(
        mode: ProvisioningMode = ProvisioningMode.CUSTOM,
        vendor: ModelVendor = ModelVendor.ARK,
        apiKey: String = "ark-test",
        chatModel: String = "doubao-seed-2-1-pro-260915",
        operatorModel: String = "doubao-seed-2-1-lite-260915",
        imageModel: String = "doubao-seedream-5-0-flash-260915",
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
        provisioning = mode,
        unified = SettingsForm.UnifiedForm(
            vendor = vendor,
            apiKey = apiKey,
            chatModel = chatModel,
            operatorModel = operatorModel,
            imageModel = imageModel,
        ),
    )

    @Test
    fun unifiedModeTakesOverEveryModel() {
        val settings = form(mode = ProvisioningMode.UNIFIED).toSettings()!!

        assertEquals("https://ark.cn-beijing.volces.com/api/v3", settings.chat.baseUrl)
        assertEquals("ark-test", settings.chat.apiKey)
        assertEquals("doubao-seed-2-1-pro-260915", settings.chat.model)
        assertEquals(ModelVendor.ARK, settings.chat.vendor)

        assertEquals("doubao-seed-2-1-lite-260915", settings.operator.model)
        assertEquals("ark-test", settings.operator.apiKey)

        assertEquals("doubao-seedream-5-0-flash-260915", settings.image.effectiveModel)
    }

    @Test
    fun unifiedModeAlsoCoversVoiceForVolcano() {
        val settings = form(mode = ProvisioningMode.UNIFIED).toSettings()!!

        assertEquals(TtsProvider.DOUBAO, settings.tts.provider)
        assertEquals("ark-test", settings.tts.doubao.apiKey)
        assertEquals(AsrProvider.DOUBAO, settings.asr.provider)
        assertEquals("ark-test", settings.asr.doubao.apiKey)
        assertEquals("ark-test", settings.realtime.apiKey)
    }

    @Test
    fun anotherVendorLeavesVoiceAlone() {
        // 别家没有豆包语音，语音仍归自定义那边管。
        val settings = form(mode = ProvisioningMode.UNIFIED, vendor = ModelVendor.DEEPSEEK).toSettings()!!

        assertEquals(ModelVendor.DEEPSEEK, settings.chat.vendor)
        assertEquals("ark-test", settings.chat.apiKey)
        assertEquals(TtsProvider.SILICONFLOW, settings.tts.provider)
    }

    @Test
    fun customModeLeavesEverythingAlone() {
        val settings = form(mode = ProvisioningMode.CUSTOM).toSettings()!!

        assertEquals(ModelVendor.OPENAI, settings.chat.vendor)
        assertEquals("sk-openai", settings.chat.apiKey)
        assertEquals("gpt-4.1-mini", settings.operator.model)
        assertEquals(TtsProvider.SILICONFLOW, settings.tts.provider)
    }

    @Test
    fun anEmptyKeyTakesOverNothing() {
        val settings = form(mode = ProvisioningMode.UNIFIED, apiKey = "").toSettings()!!

        assertEquals(ModelVendor.OPENAI, settings.chat.vendor)
        assertEquals("sk-openai", settings.chat.apiKey)
    }

    @Test
    fun applyingTwiceChangesNothing() {
        val once = form(mode = ProvisioningMode.UNIFIED).applyUnified()
        val twice = once.applyUnified()

        assertEquals(once, twice)
    }

    @Test
    fun arkOnlyKeyIsRecognisedByItsPrefix() {
        assertTrue(UnifiedProvider(apiKey = "ark-abc").looksLikeArkOnlyKey)
        assertFalse(UnifiedProvider(apiKey = "iam-abc").looksLikeArkOnlyKey)
        assertFalse(UnifiedProvider(apiKey = "").looksLikeArkOnlyKey)
    }

    @Test
    fun onlyVolcanoIsFlaggedAsSuch() {
        assertTrue(UnifiedProvider(vendor = ModelVendor.ARK).isVolcano)
        assertFalse(UnifiedProvider(vendor = ModelVendor.DEEPSEEK).isVolcano)
    }

    @Test
    fun theKeyIsMaskedInSecrets() {
        val settings = form(mode = ProvisioningMode.UNIFIED).toSettings()!!
        assertTrue("ark-test" in settings.secrets)
    }

    @Test
    fun thePreviousVendorProfileIsKeptForSwitchingBack() {
        val openAiProfile = ModelSettings(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1",
            apiKey = "sk-openai",
            vendor = ModelVendor.OPENAI,
        )
        val settings = form(mode = ProvisioningMode.UNIFIED)
            .copy(chatVendors = mapOf(ModelVendor.OPENAI to openAiProfile))
            .toSettings()!!

        val kept = settings.chatVendors[ModelVendor.OPENAI]
        assertEquals("sk-openai", kept?.apiKey)
        assertEquals("gpt-4.1", kept?.model)
        // 当前生效的是方舟。
        assertEquals(ModelVendor.ARK, settings.chat.vendor)
    }
}
