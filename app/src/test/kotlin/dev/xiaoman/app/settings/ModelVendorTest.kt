package dev.xiaoman.app.settings

import dev.xiaoman.api.ApiProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「选厂商 → 只填 Key」的预设与切换行为。 */
class ModelVendorTest {
    @Test
    fun `every preset ships a usable address and common models`() {
        for (vendor in ModelVendor.entries) {
            assertTrue(vendor.displayName.isNotBlank(), "$vendor 缺名称")
            assertTrue(vendor.keyHintText.isNotBlank(), "$vendor 缺 Key 提示")
            if (vendor == ModelVendor.CUSTOM) {
                assertEquals("", vendor.baseUrl)
                assertTrue(vendor.models.isEmpty())
                assertEquals("", vendor.defaultModel)
            } else {
                assertTrue(vendor.baseUrl.startsWith("https://"), "$vendor 地址不对：${vendor.baseUrl}")
                assertTrue(vendor.models.isNotEmpty(), "$vendor 缺常用模型")
                assertTrue(vendor.models.all { it.isNotBlank() }, "$vendor 有空白模型名")
                assertEquals(vendor.models.first(), vendor.defaultModel)
            }
        }
        // 旧安装的默认地址仍是方舟，升级后不会被判成自定义。
        assertEquals(ModelVendor.ARK.baseUrl, ModelSettings.DEFAULT_BASE_URL)
    }

    @Test
    fun `only anthropic uses the native messages protocol`() {
        assertEquals(ApiProtocol.ANTHROPIC, ModelVendor.ANTHROPIC.protocol)
        for (vendor in ModelVendor.entries.filter { it != ModelVendor.ANTHROPIC }) {
            assertEquals(ApiProtocol.OPENAI, vendor.protocol, "$vendor 协议错了")
        }
    }

    @Test
    fun `switching vendor stashes the typed key and restores it`() {
        val form = SettingsForm(
            chat = SettingsForm.ModelForm(
                baseUrl = ModelVendor.ARK.baseUrl,
                model = "doubao-seed-1-6-250615",
                apiKey = "ark-typed-but-unsaved",
                vendor = ModelVendor.ARK,
            ),
        )

        val deepseek = form.withChatVendor(ModelVendor.DEEPSEEK)
        assertEquals(ModelVendor.DEEPSEEK, deepseek.chat.vendor)
        assertEquals(ModelVendor.DEEPSEEK.baseUrl, deepseek.chat.baseUrl)
        assertEquals(ModelVendor.DEEPSEEK.defaultModel, deepseek.chat.model)
        // 新厂商没填过 Key，不把上一家的 Key 错贴过来。
        assertEquals("", deepseek.chat.apiKey)
        assertEquals("ark-typed-but-unsaved", deepseek.chatVendors[ModelVendor.ARK]?.apiKey)

        val back = deepseek.withChatVendor(ModelVendor.ARK)
        assertEquals("ark-typed-but-unsaved", back.chat.apiKey)
        assertEquals("doubao-seed-1-6-250615", back.chat.model)
        // 切走再切回不会产生重复厂商条目。
        assertEquals(1, back.chatVendors.keys.count { it == ModelVendor.ARK })
    }

    @Test
    fun `operator vendor switching follows the same rules`() {
        val form = SettingsForm(
            operator = SettingsForm.ModelForm(
                baseUrl = ModelVendor.ARK.baseUrl,
                model = "doubao-vision",
                apiKey = "operator-key",
                vendor = ModelVendor.ARK,
            ),
        )
        val zhipu = form.withOperatorVendor(ModelVendor.ZHIPU)
        assertEquals(ModelVendor.ZHIPU, zhipu.operator.vendor)
        assertEquals("", zhipu.operator.apiKey)
        assertEquals("operator-key", zhipu.operatorVendors[ModelVendor.ARK]?.apiKey)
    }

    @Test
    fun `model choices merge fetched ids with builtin defaults without duplicates`() {
        val form = SettingsForm()
        val choices = form.modelChoices(ModelVendor.DEEPSEEK, listOf("deepseek-chat", "deepseek-v3"))
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v3"), choices)
        assertEquals(choices, choices.distinct())
    }

    @Test
    fun `form keeps other vendors' profiles when saving the active one`() {
        val ark = ModelSettings(model = "doubao", apiKey = "ark-key", vendor = ModelVendor.ARK)
        val openai = ModelSettings(
            baseUrl = ModelVendor.OPENAI.baseUrl,
            model = "gpt-4.1",
            apiKey = "sk-openai",
            vendor = ModelVendor.OPENAI,
        )
        val settings = AppSettings(
            chat = ark,
            chatVendors = mapOf(ModelVendor.ARK to ark, ModelVendor.OPENAI to openai),
        )
        val form = SettingsForm.of(settings).withChatVendor(ModelVendor.OPENAI)
        assertEquals("sk-openai", form.chat.apiKey)
        val parsed = form.toSettings() ?: error("表单应能转换为设置")
        assertEquals("ark-key", parsed.chatVendors[ModelVendor.ARK]?.apiKey)
        assertEquals("sk-openai", parsed.chatVendors[ModelVendor.OPENAI]?.apiKey)
    }
}
