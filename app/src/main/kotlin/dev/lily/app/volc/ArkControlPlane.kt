package dev.lily.app.volc

import dev.lily.llm.LlmClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 火山引擎的 Access Key 凭证。只在本机用，不上传。 */
data class VolcCredential(val accessKeyId: String, val secretAccessKey: String) {
    val configured: Boolean get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    override fun toString() = "VolcCredential(accessKeyId=${accessKeyId.take(4)}…, secret=***)"
}

/** 调用失败时的原因。 */
class VolcApiException(message: String) : Exception(message)

/**
 * 方舟管控面客户端。
 *
 * 管控面（`ark.cn-beijing.volcengineapi.com`）只认 Access Key：
 * - `ListModelPrices`：当前账号可见的模型价格（含账号折扣）
 * - `GetInferenceUsage`：官方口径的推理用量
 *
 * 数据面（`ark.cn-beijing.volces.com`）只认 API Key，两者不通用，
 * 所以这里必须单独收一份 AK/SK。
 */
class ArkControlPlane(
    private val http: OkHttpClient = LlmClients.sharedHttp,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 查模型价格。
     *
     * @param models 要查的模型名（用不带版本号的基础名，例如 `doubao-seed-2-1-pro`）
     */
    suspend fun listModelPrices(
        credential: VolcCredential,
        models: List<String>,
    ): Map<String, OfficialPrice> {
        if (!credential.configured) throw VolcApiException("还没填 Access Key")
        if (models.isEmpty()) return emptyMap()
        val body = buildString {
            append("{\"FoundationModelNames\":[")
            append(models.joinToString(",") { "\"$it\"" })
            append("],\"Dimensions\":[{\"Name\":\"service_type\",\"Values\":[\"infer\"]}]}")
        }
        val text = call(credential, "ListModelPrices", body)
        return parseOfficialPrices(text)
    }

    /**
     * 查官方推理用量。
     *
     * @param startDate 起始日期，`yyyy-MM-dd`（北京时间）
     * @param endDate 结束日期，`yyyy-MM-dd`
     */
    suspend fun inferenceUsage(
        credential: VolcCredential,
        startDate: String,
        endDate: String,
        interval: String = "Day",
    ): OfficialUsage {
        if (!credential.configured) throw VolcApiException("还没填 Access Key")
        val body = """{"StartTime":"$startDate","EndTime":"$endDate","QueryInterval":"$interval"}"""
        val text = call(credential, "GetInferenceUsage", body)
        return parseUsage(text)
    }

    private suspend fun call(credential: VolcCredential, action: String, body: String): String =
        withContext(Dispatchers.IO) {
            // 查询串按 key 的 ASCII 顺序排；Action 在 Version 前面。
            val query = "Action=$action&Version=2024-01-01"
            val timestamp = now()
            val authorization = VolcSignature.authorization(
                accessKeyId = credential.accessKeyId,
                secretAccessKey = credential.secretAccessKey,
                date = timestamp,
                query = query,
                body = body,
            )
            val request = Request.Builder()
                .url("https://${VolcSignature.HOST}/?$query")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Host", VolcSignature.HOST)
                .header("X-Date", VolcSignature.formatDate(timestamp))
                .header("X-Content-Sha256", VolcSignature.contentSha256(body))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", authorization)
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw VolcApiException(errorMessage(text) ?: "接口返回 ${response.code}")
                    }
                    text
                }
            } catch (e: IOException) {
                throw VolcApiException("连不上火山引擎：${e.message ?: "网络错误"}")
            }
        }

    /** 从错误响应里挖出可读的原因。 */
    private fun errorMessage(text: String): String? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val err = root["ResponseMetadata"]?.let { it as? JsonObject }?.get("Error")
            ?: root["Error"]
        val obj = err as? JsonObject ?: return@runCatching null
        val code = (obj["Code"] as? JsonPrimitive)?.content
        val message = (obj["Message"] as? JsonPrimitive)?.content
        listOfNotNull(code, message).joinToString("：").ifBlank { null }
    }.getOrNull()
}

/** 官方口径的用量。 */
data class OfficialUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val requests: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** 解析 `GetInferenceUsage` 的响应。 */
internal fun parseUsage(text: String): OfficialUsage {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return OfficialUsage()
    val result = root["Result"] as? JsonObject ?: return OfficialUsage()
    val fields = (result["Fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val names = fields.mapIndexed { index, field ->
        (field["Name"] as? JsonPrimitive)?.content ?: index.toString()
    }
    val rows = result["Data"] as? JsonArray ?: return OfficialUsage()
    var input = 0L
    var output = 0L
    var requests = 0L
    rows.forEach { row ->
        val cells = row as? JsonArray ?: return@forEach
        cells.forEachIndexed { index, cell ->
            val value = (cell as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
            when (names.getOrNull(index)) {
                "InputTokens" -> input += value.toLong()
                "OutputTokens" -> output += value.toLong()
                "ReqCnt" -> requests += value.toLong()
            }
        }
    }
    return OfficialUsage(input, output, requests)
}
