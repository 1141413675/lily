package dev.lily.app.volc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 管控面签名。
 *
 * 参考向量是用 Python 的 hmac/hashlib 按官方文档独立算出来的，
 * 目的就是让实现对着一个确定的数值，而不是「看着像对的」。
 */
class VolcSignatureTest {

    private val accessKeyId = "AKLTESTKEYTESTKEYTEST"
    private val secretAccessKey = "testSecretAccessKeyTestSecretAccessKey00"
    private val body =
        """{"FoundationModelNames":["doubao-seed-2-1-pro-260915"],"Dimensions":[{"Name":"service_type","Values":["infer"]}]}"""
    private val query = "Action=ListModelPrices&Version=2024-01-01"
    private val date = 1795835045000L // 2026-11-28T03:04:05Z

    @Test
    fun contentHashMatches() {
        assertEquals(
            "cf138c0aa36269ddf46276c75fdbbf13e63c459d09b4a4a41fa2a199b73410c3",
            VolcSignature.contentSha256(body),
        )
    }

    @Test
    fun dateIsFormattedInUtc() {
        assertEquals("20261128T030405Z", VolcSignature.formatDate(date))
    }

    @Test
    fun authorizationMatchesTheReferenceVector() {
        val auth = VolcSignature.authorization(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            date = date,
            query = query,
            body = body,
        )
        assertEquals(
            "HMAC-SHA256 Credential=$accessKeyId/20261128/cn-beijing/ark/request, " +
                "SignedHeaders=host;x-content-sha256;x-date, " +
                "Signature=40279920a01d583b15a725f5e5d3413dea1af4603408907016318d329df08888",
            auth,
        )
    }

    @Test
    fun aDifferentBodyGivesADifferentSignature() {
        val a = VolcSignature.authorization(accessKeyId, secretAccessKey, date, query = query, body = body)
        val b = VolcSignature.authorization(accessKeyId, secretAccessKey, date, query = query, body = "$body ")
        assert(a != b) { "换了 body 签名必须变" }
    }

    @Test
    fun aDifferentQueryGivesADifferentSignature() {
        val a = VolcSignature.authorization(accessKeyId, secretAccessKey, date, query = query, body = body)
        val b = VolcSignature.authorization(
            accessKeyId, secretAccessKey, date,
            query = "Action=GetInferenceUsage&Version=2024-01-01",
            body = body,
        )
        assert(a != b) { "换了 query 签名必须变" }
    }

    @Test
    fun theSecretNeverAppearsInTheHeader() {
        val auth = VolcSignature.authorization(accessKeyId, secretAccessKey, date, query = query, body = body)
        assert(!auth.contains(secretAccessKey))
    }
}
