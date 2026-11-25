package dev.xiaoman.app.settings

import dev.xiaoman.api.ApiProtocol

/**
 * 文本模型厂商预设：选厂商 → 只填 API Key，地址与常用模型已经填好。
 *
 * [models] 只是内置兜底；设置页能拿到接口的 `/models` 就用接口结果，拉不到仍用这份。
 * [protocol] 决定走哪套客户端：除 Anthropic 原生 `/messages` 外都是 OpenAI 兼容的
 * `/chat/completions`。
 */
enum class ModelVendor(
    val displayName: String,
    val baseUrl: String,
    val models: List<String>,
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
    val keyHint: String = "",
) {
    ARK(
        displayName = "火山方舟",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        models = listOf(
            "doubao-seed-1-6-250615",
            "doubao-seed-1-6-flash-250615",
            "doubao-1-5-pro-32k-250115",
            "deepseek-v3-250324",
        ),
        keyHint = "方舟的 API Key（ark- 开头）",
    ),
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini"),
        keyHint = "OpenAI 的 API Key（sk- 开头）",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        keyHint = "DeepSeek 的 API Key（sk- 开头）",
    ),
    MOONSHOT(
        displayName = "月之暗面 Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        models = listOf("kimi-k2-0711-preview", "kimi-k2-turbo-preview", "moonshot-v1-32k", "moonshot-v1-128k"),
        keyHint = "Moonshot 开放平台的 API Key（sk- 开头）",
    ),
    ZHIPU(
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        models = listOf("glm-4.5", "glm-4.5-air", "glm-4-plus", "glm-4-flash"),
        keyHint = "智谱开放平台的 API Key",
    ),
    DASHSCOPE(
        displayName = "通义百炼",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models = listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen3-235b-a22b"),
        keyHint = "阿里云百炼的 API Key（sk- 开头）",
    ),
    MINIMAX(
        displayName = "MiniMax",
        baseUrl = "https://api.minimax.chat/v1",
        models = listOf("MiniMax-Text-01", "MiniMax-M2", "abab6.5s-chat"),
        keyHint = "MiniMax 的 API Key",
    ),
    SILICONFLOW(
        displayName = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3-235B-A22B", "moonshotai/Kimi-K2-Instruct"),
        keyHint = "硅基流动的 API Key（sk- 开头）",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("openai/gpt-4.1", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
        keyHint = "OpenRouter 的 API Key（sk-or- 开头）",
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        models = listOf("claude-sonnet-4-20250514", "claude-opus-4-1-20250805", "claude-3-5-haiku-20241022"),
        protocol = ApiProtocol.ANTHROPIC,
        keyHint = "Anthropic 的 API Key（sk-ant- 开头）",
    ),
    GEMINI(
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        models = listOf("gemini-2.5-pro", "gemini-2.5-flash"),
        keyHint = "Google AI Studio 的 API Key（AIza 开头）",
    ),

    /** 自己填地址与模型名；[protocol] 可声明是 OpenAI 兼容还是 Anthropic 原生。 */
    CUSTOM(
        displayName = "自定义",
        baseUrl = "",
        models = emptyList(),
        keyHint = "该服务的 API Key",
    );

    val defaultModel: String get() = models.firstOrNull().orEmpty()

    /** 该厂商标注的 Key 提示；自定义或有地址但没有提示时退回通用文案。 */
    val keyHintText: String get() = keyHint.ifBlank { "该厂商的 API Key" }

    companion object {
        fun from(name: String?): ModelVendor = entries.firstOrNull { it.name == name } ?: ARK

        /** 某个厂商在设置里的初始形态：地址与首选模型预填，Key 留空。 */
        fun preset(vendor: ModelVendor) = ModelSettings(
            baseUrl = vendor.baseUrl,
            model = vendor.defaultModel,
            vendor = vendor,
            protocol = vendor.protocol,
        )
    }
}
