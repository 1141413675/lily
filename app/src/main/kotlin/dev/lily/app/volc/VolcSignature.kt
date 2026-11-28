package dev.lily.app.volc

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 火山引擎管控面接口的 V4 签名。
 *
 * 管控面（`ark.cn-beijing.volcengineapi.com`）的接口只认 Access Key：
 * 方舟的定价（`ListModelPrices`）与官方用量（`GetInferenceUsage`）都在这一面。
 * 数据面（`ark.cn-beijing.volces.com`）只认 API Key，两者不通用。
 *
 * 签名算法与 AWS SigV4 同形，官方文档：
 * https://docs.volcengine.com/docs/4640/2122634
 *
 * ```
 * CanonicalRequest = Method \n URI \n Query \n CanonicalHeaders \n SignedHeaders \n HexEncode(SHA256(payload))
 * StringToSign    = "HMAC-SHA256" \n RequestDate \n CredentialScope \n HexEncode(SHA256(CanonicalRequest))
 * kSigning        = HMAC(HMAC(HMAC(HMAC(SK, Date), Region), Service), "request")
 * Signature       = HexEncode(HMAC(kSigning, StringToSign))
 * ```
 */
object VolcSignature {

    /** 方舟管控面。 */
    const val HOST = "ark.cn-beijing.volcengineapi.com"
    const val REGION = "cn-beijing"
    const val SERVICE = "ark"
    const val ALGORITHM = "HMAC-SHA256"

    /**
     * 算出 `Authorization` 头的值。
     *
     * @param accessKeyId Access Key ID
     * @param secretAccessKey Secret Access Key
     * @param date 请求时间（毫秒）。签名用它的 UTC 形式，所以不受本机时区影响。
     * @param query 已经按 key 排好序的查询串，例如 `Action=ListModelPrices&Version=2024-01-01`
     * @param body 请求体；空串表示没有 body。
     */
    fun authorization(
        accessKeyId: String,
        secretAccessKey: String,
        date: Long,
        method: String = "POST",
        uri: String = "/",
        query: String,
        body: String,
        host: String = HOST,
    ): String {
        val requestDate = formatDate(date)
        val day = requestDate.take(8)
        val contentSha = hex(sha256(body.toByteArray()))

        val headers = sortedMapOf(
            "host" to host,
            "x-content-sha256" to contentSha,
            "x-date" to requestDate,
        )
        val canonicalHeaders = headers.entries.joinToString("") { (k, v) -> "$k:$v\n" }
        val signedHeaders = headers.keys.joinToString(";")

        val canonicalRequest = listOf(
            method,
            uri,
            query,
            canonicalHeaders,
            signedHeaders,
            hex(sha256(body.toByteArray())),
        ).joinToString("\n")

        val scope = "$day/$REGION/$SERVICE/request"
        val stringToSign = listOf(
            ALGORITHM,
            requestDate,
            scope,
            hex(sha256(canonicalRequest.toByteArray())),
        ).joinToString("\n")

        val signingKey = hmacChain(secretAccessKey, day, REGION, SERVICE)
        val signature = hex(hmac(signingKey, stringToSign.toByteArray()))

        return "$ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /** `X-Content-Sha256` 头的值。 */
    fun contentSha256(body: String): String = hex(sha256(body.toByteArray()))

    /** UTC 时间，格式 `yyyyMMdd'T'HHmmss'Z'`。 */
    fun formatDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format(
            java.util.Locale.US,
            "%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    private fun signingKey(secret: String, day: String, region: String, service: String): ByteArray =
        hmacChain(secret, day, region, service)

    private fun hmacChain(secret: String, day: String, region: String, service: String): ByteArray {
        var key = hmac(secret.toByteArray(), day.toByteArray())
        key = hmac(key, region.toByteArray())
        key = hmac(key, service.toByteArray())
        return hmac(key, "request".toByteArray())
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
