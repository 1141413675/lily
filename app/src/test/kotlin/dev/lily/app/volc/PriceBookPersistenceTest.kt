package dev.lily.app.volc

import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 在线官方价格的覆盖与持久化。
 *
 * 在线价格是「当前账号对应的价格」（有折扣的企业用户拿到的就是折扣价），
 * 存到本机后重启还在；不存的话每次重启都得退回内置快照。
 */
class PriceBookPersistenceTest {

    @AfterTest
    fun tearDown() {
        PriceBook.reset()
    }

    @Test
    fun officialPricesSurviveAReload() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pricebook-${System.nanoTime()}")
        val file = File(dir, "ark-prices.json")

        PriceBook.applyOfficial(
            mapOf("doubao-seed-2-1-pro-260915" to OfficialPrice(inputPerMTok = 5.5, outputPerMTok = 27.5)),
            file,
        )
        assertTrue(PriceBook.usingOfficial)

        // 换个进程：只靠文件恢复。
        PriceBook.reset()
        assertNull(PriceBook.fetchedAt)

        PriceBook.loadOfficial(file)
        assertTrue(PriceBook.usingOfficial)
        assertEquals(5.5, PriceBook.priceOf("doubao-seed-2-1-pro-260915")!!.inputPerMTok!!, 0.0001)
        assertEquals(27.5, PriceBook.priceOf("doubao-seed-2-1-pro-260915")!!.outputPerMTok!!, 0.0001)
    }

    @Test
    fun aMissingFileLeavesTheSnapshotAlone() {
        PriceBook.loadOfficial(File("/definitely/not/here/ark-prices.json"))
        assertNull(PriceBook.fetchedAt)
        assertEquals(6.0, PriceBook.priceOf("doubao-seed-2-1-pro-260915")!!.inputPerMTok!!, 0.0001)
    }

    @Test
    fun aBrokenFileDoesNotCrash() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pricebook-${System.nanoTime()}")
        val file = File(dir, "ark-prices.json")
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON")

        PriceBook.loadOfficial(file)
        assertNull(PriceBook.fetchedAt)
    }

    @Test
    fun resetRemovesTheStoredPrices() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pricebook-${System.nanoTime()}")
        val file = File(dir, "ark-prices.json")
        PriceBook.applyOfficial(mapOf("doubao-seed-2-0-mini-260428" to OfficialPrice(inputPerMTok = 0.1)), file)
        assertTrue(file.isFile)

        PriceBook.reset(file)
        assertTrue(!file.isFile)
        assertTrue(!PriceBook.usingOfficial)
    }

    @Test
    fun theStoredFileHoldsNoSecrets() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pricebook-${System.nanoTime()}")
        val file = File(dir, "ark-prices.json")
        PriceBook.applyOfficial(mapOf("m" to OfficialPrice(inputPerMTok = 1.0)), file)
        val text = file.readText()
        assertTrue("apiKey" !in text)
        assertTrue("secret" !in text.lowercase())
        // 只存价格本身。
        assertTrue(JSONObject(text).has("prices"))
    }
}
