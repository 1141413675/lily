package dev.lily.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 方舟模型清单与价格。
 *
 * 价格是直接显示给用户看的数字，错一位就是错的信息；模型 ID 写错则调用直接失败。
 * 所以这里把几个关键数字钉住。
 */
class ArkCatalogTest {

    @Test
    fun modelIdsAreUnique() {
        val ids = ArkCatalog.chatModels.map { it.id } + ArkCatalog.imageModels.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun everyModelHasAPrice() {
        (ArkCatalog.chatModels + ArkCatalog.imageModels).forEach { model ->
            when (model.modality) {
                ArkModality.CHAT -> {
                    assertNotNull(model.inputPerMTok, "${model.id} 缺输入价")
                    assertNotNull(model.outputPerMTok, "${model.id} 缺输出价")
                }
                ArkModality.IMAGE -> assertNotNull(model.perImage, "${model.id} 缺每张价")
            }
        }
    }

    @Test
    fun outputIsNeverCheaperThanInput() {
        ArkCatalog.chatModels.forEach { model ->
            val input = model.inputPerMTok!!
            val output = model.outputPerMTok!!
            assertTrue(output > input, "${model.id} 输出价不应低于输入价")
        }
    }

    @Test
    fun theDefaultModelsExist() {
        assertNotNull(ArkCatalog.byId(ArkCatalog.DEFAULT_CHAT_MODEL))
        assertNotNull(ArkCatalog.byId(ArkCatalog.DEFAULT_IMAGE_MODEL))
    }

    @Test
    fun unknownModelHasNoPrice() {
        // 用户自己填了清单外的模型 ID 时，宁可没有价格，也不给一个错的数字。
        assertNull(ArkCatalog.byId("some-model-i-made-up"))
        assertEquals("", ArkCatalog.priceTextOf("some-model-i-made-up"))
    }

    @Test
    fun priceTextMentionsBothSides() {
        val text = ArkCatalog.chatModels.first().priceText
        assertTrue("输入" in text)
        assertTrue("输出" in text)
        assertTrue("tokens" in text)
    }

    @Test
    fun imagePriceIsPerPicture() {
        val text = ArkCatalog.imageModels.first().priceText
        assertTrue("张" in text)
    }

    @Test
    fun estimateScalesWithTokens() {
        val model = ArkCatalog.chatModels.first { it.id == "doubao-seed-2-0-mini-260428" }
        val one = model.estimate(1000, 1000)
        val two = model.estimate(2000, 2000)
        assertTrue(two > one)
        // 每百万 token 0.20 元输入、2.00 元输出：1000 进 + 1000 出 = 0.0022 元
        assertEquals(0.0022, one, 0.00001)
    }
}
