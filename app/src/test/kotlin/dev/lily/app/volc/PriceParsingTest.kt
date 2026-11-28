package dev.lily.app.volc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 官方价格响应的解析。
 *
 * 这里最要紧的是**单位**：`PriceUnitCode` 决定 `Price` 是每 token、每千 token
 * 还是每百万 token。认错了单位，价格就会差一千倍或一百万倍。
 * 所以认不出来的单位一律当作「没有价格」，绝不猜。
 */
class PriceParsingTest {

    @AfterTest
    fun tearDown() {
        PriceBook.clearOfficial()
    }

    /** 文档里的示例响应。 */
    private val sample = """
        {
          "ResponseMetadata": {
            "RequestId": "20261028123456E5F607182E04DEF708",
            "Action": "ListModelPrices",
            "Version": "2024-01-01",
            "Service": "ark",
            "Region": "cn-beijing"
          },
          "Result": {
            "ModelPrices": [
              {
                "FoundationModelName": "doubao-seed-2-0-pro",
                "PriceGroups": [
                  {
                    "UsageUnit": "token",
                    "Dimensions": [ { "Name": "service_type", "Value": "infer" } ],
                    "PriceItems": [
                      { "Label": "Prompt", "Price": 6.0, "PriceUnitCode": "CNY_PER_MILLION_TOKEN" },
                      { "Label": "Completion", "Price": 30.0, "PriceUnitCode": "CNY_PER_MILLION_TOKEN" }
                    ]
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun pricesAreRead() {
        val prices = parseOfficialPrices(sample)
        val model = prices["doubao-seed-2-0-pro"]
        assertEquals(6.0, model!!.inputPerMTok!!, 0.0001)
        assertEquals(30.0, model.outputPerMTok!!, 0.0001)
    }

    @Test
    fun perThousandTokenIsConverted() {
        val body = sample
            .replace("\"Price\": 6.0", "\"Price\": 0.006")
            .replace("CNY_PER_MILLION_TOKEN", "CNY_PER_THOUSAND_TOKEN")
        val price = parseOfficialPrices(body)["doubao-seed-2-0-pro"]!!
        // 0.006 元/千 token = 6 元/百万 token。
        assertEquals(6.0, price.inputPerMTok!!, 0.0001)
    }

    @Test
    fun perTokenIsConverted() {
        val body = sample
            .replace("\"Price\": 6.0", "\"Price\": 0.000006")
            .replace("CNY_PER_MILLION_TOKEN", "CNY_PER_TOKEN")
        val price = parseOfficialPrices(body)["doubao-seed-2-0-pro"]!!
        assertEquals(6.0, price.inputPerMTok!!, 0.0001)
    }

    @Test
    fun anUnknownUnitIsIgnoredRatherThanGuessed() {
        val body = sample.replace("CNY_PER_MILLION_TOKEN", "CNY_PER_SOMETHING_ELSE")
        // 单位认不出来 → 没有价格，而不是给一个可能差一千倍的数字。
        assertTrue(parseOfficialPrices(body).isEmpty())
    }

    @Test
    fun imageModelsAreReadPerPicture() {
        val body = """
            {
              "Result": {
                "ModelPrices": [
                  {
                    "FoundationModelName": "doubao-seedream-5-0-flash",
                    "PriceGroups": [
                      {
                        "PriceItems": [
                          { "Label": "ToICompletion", "Price": 0.12, "PriceUnitCode": "CNY_PER_IMAGE" }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val price = parseOfficialPrices(body)["doubao-seedream-5-0-flash"]!!
        assertEquals(0.12, price.perImage!!, 0.0001)
    }

    @Test
    fun theDefaultTierIsPreferredOverWindowTiers() {
        val body = """
            {
              "Result": {
                "ModelPrices": [
                  {
                    "FoundationModelName": "doubao-seed-2-0-pro",
                    "PriceGroups": [
                      {
                        "Dimensions": [ { "Name": "window", "Value": "输入<=32k" } ],
                        "PriceItems": [
                          { "Label": "Prompt", "Price": 99.0, "PriceUnitCode": "CNY_PER_MILLION_TOKEN" }
                        ]
                      },
                      {
                        "Dimensions": [ { "Name": "service_type", "Value": "infer" } ],
                        "PriceItems": [
                          { "Label": "Prompt", "Price": 3.2, "PriceUnitCode": "CNY_PER_MILLION_TOKEN" }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val price = parseOfficialPrices(body)["doubao-seed-2-0-pro"]!!
        // 3.2 是默认档；99 是 32K 档，不该被选中。
        assertEquals(3.2, price.inputPerMTok!!, 0.0001)
    }

    @Test
    fun aBrokenResponseYieldsNothing() {
        assertTrue(parseOfficialPrices("not json").isEmpty())
        assertTrue(parseOfficialPrices("{}").isEmpty())
    }

    @Test
    fun officialPricesOverrideTheSnapshot() {
        PriceBook.applyOfficial(mapOf("doubao-seed-2-0-mini-260428" to OfficialPrice(inputPerMTok = 0.1)))
        val price = PriceBook.priceOf("doubao-seed-2-0-mini-260428")!!
        assertEquals(0.1, price.inputPerMTok!!, 0.0001)
        // 没被覆盖的模型仍然用内置快照。
        assertEquals(6.0, PriceBook.priceOf("doubao-seed-2-1-pro-260915")!!.inputPerMTok!!, 0.0001)
    }

    @Test
    fun clearingTheOverrideGoesBackToTheSnapshot() {
        PriceBook.applyOfficial(mapOf("doubao-seed-2-0-mini-260428" to OfficialPrice(inputPerMTok = 0.1)))
        PriceBook.clearOfficial()
        assertEquals(0.2, PriceBook.priceOf("doubao-seed-2-0-mini-260428")!!.inputPerMTok!!, 0.0001)
    }

    @Test
    fun unknownModelsHaveNoPrice() {
        assertNull(PriceBook.priceOf("not-a-model"))
    }

    @Test
    fun usageIsRead() {
        val body = """
            {
              "Result": {
                "Fields": [
                  { "Name": "Day" }, { "Name": "InputTokens" }, { "Name": "OutputTokens" }, { "Name": "ReqCnt" }
                ],
                "Data": [
                  [ "2026-11-27", 100, 50, 2 ],
                  [ "2026-11-28", 300, 150, 6 ]
                ]
              }
            }
        """.trimIndent()
        val usage = parseUsage(body)
        assertEquals(400L, usage.inputTokens)
        assertEquals(200L, usage.outputTokens)
        assertEquals(8L, usage.requests)
    }

    @Test
    fun aBrokenUsageResponseYieldsZero() {
        val usage = parseUsage("not json")
        assertEquals(0L, usage.inputTokens)
    }
}
