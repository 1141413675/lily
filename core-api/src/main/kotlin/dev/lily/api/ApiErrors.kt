package dev.lily.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 用户能看懂的错误分类；界面按它给不同的下一步。 */
enum class ApiErrorKind { AUTH, QUOTA, PARAM, MODEL, SERVER, NETWORK, UNKNOWN }

/**
 * 一次接口失败的「可展示」结果。只保留服务端 message 与一句建议，
 * 不携带原始 JSON / 请求头，避免把 Key 或整段报文带到界面和日志。
 */
data class ApiFailure(
    val kind: ApiErrorKind,
    val statusCode: Int?,
    val serverMessage: String?,
    val advice: String,
    val field: String? = null,
) {
    /** 例：语音合成失败（HTTP 401）：Token is invalid.。Key 无效…… */
    fun message(prefix: String): String = buildString {
        append(prefix)
        statusCode?.let { append("（HTTP ").append(it).append("）") }
        if (!serverMessage.isNullOrBlank()) append("：").append(serverMessage)
        if (advice.isNotBlank()) {
            if (!serverMessage.isNullOrBlank() && !serverMessage.endsWith("。")) append("。")
            append(advice)
        }
    }
}

/**
 * 把各家 provider 的错误响应收敛成同一种说法：
 * 401/403 → Key，429 → 额度/频率，400/422 → 具体字段，404 → 地址/模型名，5xx → 稍后重试。
 * 只解析常见信封（OpenAI / Anthropic / 火山 / 硅基 / DashScope），不把原始 JSON 抛给用户。
 */
object ApiErrors {
    private val json = Json { ignoreUnknownKeys = true }

    fun explain(status: Int?, body: String?, service: String): ApiFailure {
        val message = extractMessage(body)
        val lowered = message.orEmpty().lowercase()
        val field = fieldOf(lowered)
        val kind = when {
            status == 401 || status == 403 -> ApiErrorKind.AUTH
            status == 429 -> ApiErrorKind.QUOTA
            status == 400 || status == 422 -> ApiErrorKind.PARAM
            status == 404 -> ApiErrorKind.MODEL
            status == 408 -> ApiErrorKind.NETWORK
            status != null && status in 500..599 -> ApiErrorKind.SERVER
            // 没有 HTTP 状态码（例如豆包 WebSocket 错误帧）时按 message 兜底。
            status == null && message != null -> when {
                lowered.contains("401") || lowered.contains("unauthorized") ||
                    lowered.contains("invalid api key") || lowered.contains("token is invalid") ||
                    lowered.contains("authentication") -> ApiErrorKind.AUTH
                lowered.contains("quota") || lowered.contains("limit") || lowered.contains("balance") ->
                    ApiErrorKind.QUOTA
                lowered.contains("cannot unmarshal") || lowered.contains("invalid") &&
                    lowered.contains("field") -> ApiErrorKind.PARAM
                else -> ApiErrorKind.UNKNOWN
            }
            else -> ApiErrorKind.UNKNOWN
        }
        return ApiFailure(kind, status, message, advice(kind, service, field), field)
    }

    /** 从各家错误信封里只取 message 文本；拿不到就返回 null，绝不回传整段 body。 */
    fun extractMessage(body: String?): String? {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("{") && !raw.startsWith("[")) {
            // 纯文本错误（如 "Token is invalid."）可以展示，但压成一行并限长。
            return raw.lineSequence().firstOrNull()?.take(200)?.ifBlank { null }
        }
        return runCatching {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val error = root["error"]
            when (error) {
                is JsonObject -> error.string("message") ?: error.string("msg") ?: error.string("code")
                is JsonPrimitive -> error.contentOrNull
                else -> root.string("message") ?: root.string("msg") ?: root.string("error_msg")
                    ?: (root["error"] as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()?.take(300)?.ifBlank { null }
    }

    private fun fieldOf(lowered: String): String? = FIELDS.firstOrNull { (key, _) -> key in lowered }?.second

    private fun advice(kind: ApiErrorKind, service: String, field: String?): String = when (kind) {
        ApiErrorKind.AUTH ->
            "Key 无效、过期或与当前服务商不匹配。请到 $service 控制台重新复制对应服务的 Key；不同厂商的 Key 不能混用。"
        ApiErrorKind.QUOTA ->
            "额度或频率超限。请检查 $service 的余额/配额，或稍后再试。"
        ApiErrorKind.PARAM ->
            "请求参数与该模型不匹配：请检查${field ?: "模型名、音色与音频格式"}。"
        ApiErrorKind.MODEL ->
            "接口地址或模型名不对。请检查 Base URL 与模型名是否属于当前厂商。"
        ApiErrorKind.SERVER ->
            "$service 服务端暂时不可用，请稍后重试；持续失败可换一个模型或厂商。"
        ApiErrorKind.NETWORK ->
            "网络不可达或超时。请检查网络、代理与 Base URL 是否可访问。"
        ApiErrorKind.UNKNOWN ->
            "请检查 Base URL、模型名和 Key 是否都属于 $service。"
    }

    private val FIELDS = listOf(
        "additions" to "表演指令字段 additions（2.0 要求是字符串）",
        "speaker" to "Speaker 音色 ID",
        "voice_type" to "Speaker 音色 ID",
        "voice" to "音色 ID",
        "resource" to "Resource ID",
        "sample_rate" to "采样率",
        "audio_params" to "音频参数",
        "format" to "音频格式",
        "model" to "模型名",
        "text" to "文本内容",
    )

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
