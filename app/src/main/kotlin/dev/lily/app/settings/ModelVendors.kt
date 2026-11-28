package dev.lily.app.settings

import dev.lily.api.ApiProtocol

/**
 * 厂商的类型：只卖自家模型的、同时代理多家模型的聚合平台、以及本机/自建服务。
 * 设置页按这个分组展示，避免 20 多个名字堆在一张列表里。
 */
enum class VendorKind {
    /** 自家模型厂商：Key 只对这家有效，模型也是它自家的。 */
    SELF,

    /** 聚合平台：一个 Key 打通多家模型（OpenRouter、硅基流动这类）。 */
    AGGREGATOR,

    /** 本机或自己搭的服务：Ollama、LM Studio，以及完全自定义的地址。 */
    LOCAL,
}

/**
 * 文本模型厂商预设：选厂商 → 只填 API Key，地址与常用模型已经填好。
 *
 * [models] 只是内置兜底；设置页能拿到接口的 `/models` 就用接口结果，拉不到仍用这份。
 * [protocol] 决定走哪套客户端：除 Anthropic 原生 `/messages` 外都是 OpenAI 兼容的
 * `/chat/completions`。
 *
 * 枚举名会写进设置（`vendor.<角色>.<枚举名>.` 前缀），改名会让已存的 Key 读不出来，
 * 所以已经发布的名字不要再改；要换厂商请新增条目。
 */
enum class ModelVendor(
    val displayName: String,
    val baseUrl: String,
    val models: List<String>,
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
    val keyHint: String = "",
    val kind: VendorKind = VendorKind.SELF,
) {
    // ---------------------------------------------------------------- 自家模型厂商

    ARK(
        displayName = "火山方舟（豆包）",
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
        displayName = "阿里云百炼（通义）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models = listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen3-235b-a22b"),
        keyHint = "阿里云百炼的 API Key（sk- 开头）",
    ),
    HUNYUAN(
        displayName = "腾讯混元",
        baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
        models = listOf("hunyuan-lite", "hunyuan-turbos", "hy3"),
        keyHint = "腾讯云 TokenHub 的 API Key（sk- 开头）",
    ),
    QIANFAN(
        displayName = "百度千帆（文心）",
        baseUrl = "https://qianfan.baidubce.com/v2",
        models = listOf("ernie-4.0-8k", "ernie-speed-128k", "ernie-4.5-turbo-128k"),
        keyHint = "千帆控制台的 API Key（bce-v3/ 开头）",
    ),
    SPARK(
        displayName = "讯飞星火",
        baseUrl = "https://spark-api-open.xf-yun.com/v1",
        models = listOf("4.0-ultra", "max", "lite"),
        keyHint = "讯飞开放平台的 API Key（APIS 授权串）",
    ),
    MINIMAX(
        displayName = "MiniMax",
        baseUrl = "https://api.minimax.chat/v1",
        models = listOf("MiniMax-Text-01", "MiniMax-M2", "abab6.5s-chat"),
        keyHint = "MiniMax 的 API Key",
    ),
    STEPFUN(
        displayName = "阶跃星辰",
        baseUrl = "https://api.stepfun.com/v1",
        models = listOf("step-2-16k", "step-1-8k"),
        keyHint = "阶跃星辰开放平台的 API Key",
    ),
    YI(
        displayName = "零一万物",
        baseUrl = "https://api.lingyiwanwu.com/v1",
        models = listOf("yi-lightning", "yi-large"),
        keyHint = "零一万物的 API Key",
    ),
    XAI(
        displayName = "xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        models = listOf("grok-4", "grok-3-mini"),
        keyHint = "xAI 的 API Key（xai- 开头）",
    ),

    // ---------------------------------------------------------------- 聚合平台

    OPENROUTER(
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("openai/gpt-4.1", "anthropic/claude-sonnet-4", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
        keyHint = "OpenRouter 的 API Key（sk-or- 开头）",
        kind = VendorKind.AGGREGATOR,
    ),
    SILICONFLOW(
        displayName = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3-235B-A22B", "moonshotai/Kimi-K2-Instruct"),
        keyHint = "硅基流动的 API Key（sk- 开头）",
        kind = VendorKind.AGGREGATOR,
    ),
    MODELSCOPE(
        displayName = "魔搭 ModelScope",
        baseUrl = "https://api-inference.modelscope.cn/v1",
        models = listOf("Qwen/Qwen3-235B-A22B-Instruct-2507", "deepseek-ai/DeepSeek-V3"),
        keyHint = "魔搭的访问令牌（ms- 开头）",
        kind = VendorKind.AGGREGATOR,
    ),
    GITEEAI(
        displayName = "Gitee AI",
        baseUrl = "https://ai.gitee.com/v1",
        models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3-Next-80B-A3B-Instruct"),
        keyHint = "Gitee AI 的 API Key",
        kind = VendorKind.AGGREGATOR,
    ),
    TOGETHER(
        displayName = "Together AI",
        baseUrl = "https://api.together.xyz/v1",
        models = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo"),
        keyHint = "Together AI 的 API Key",
        kind = VendorKind.AGGREGATOR,
    ),
    FIREWORKS(
        displayName = "Fireworks AI",
        baseUrl = "https://api.fireworks.ai/inference/v1",
        models = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct"),
        keyHint = "Fireworks AI 的 API Key",
        kind = VendorKind.AGGREGATOR,
    ),
    DEEPINFRA(
        displayName = "DeepInfra",
        baseUrl = "https://api.deepinfra.com/v1/openai",
        models = listOf("meta-llama/Llama-3.3-70B-Instruct"),
        keyHint = "DeepInfra 的 API Key",
        kind = VendorKind.AGGREGATOR,
    ),
    NOVITA(
        displayName = "Novita AI",
        baseUrl = "https://api.novita.ai/v3/openai",
        models = listOf("meta-llama/llama-3.3-70b-instruct"),
        keyHint = "Novita AI 的 API Key",
        kind = VendorKind.AGGREGATOR,
    ),
    GROQ(
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        models = listOf("llama-3.3-70b-versatile"),
        keyHint = "Groq 的 API Key（gsk_ 开头）",
        kind = VendorKind.AGGREGATOR,
    ),

    // ---------------------------------------------------------------- 本机与自建

    OLLAMA(
        displayName = "Ollama（本机）",
        baseUrl = "http://localhost:11434/v1",
        models = emptyList(),
        keyHint = "本机 Ollama 不需要 Key，留空即可",
        kind = VendorKind.LOCAL,
    ),
    LMSTUDIO(
        displayName = "LM Studio（本机）",
        baseUrl = "http://localhost:1234/v1",
        models = emptyList(),
        keyHint = "本机 LM Studio 不需要 Key，留空即可",
        kind = VendorKind.LOCAL,
    ),

    /** 自己填地址与模型名；[protocol] 可声明是 OpenAI 兼容还是 Anthropic 原生。 */
    CUSTOM(
        displayName = "自定义",
        baseUrl = "",
        models = emptyList(),
        keyHint = "该服务的 API Key",
        kind = VendorKind.LOCAL,
    );

    val defaultModel: String get() = models.firstOrNull().orEmpty()

    /** 该厂商标注的 Key 提示；自定义或有地址但没有提示时退回通用文案。 */
    val keyHintText: String get() = keyHint.ifBlank { "该厂商的 API Key" }

    /** 本机服务没有厂商 Key 可填。 */
    val keyOptional: Boolean get() = kind == VendorKind.LOCAL && this != CUSTOM

    /** 设置页按这个顺序分组展示。 */
    val kindOrder: Int
        get() = when (kind) {
            VendorKind.SELF -> 0
            VendorKind.AGGREGATOR -> 1
            VendorKind.LOCAL -> 2
        }

    companion object {
        fun from(name: String?): ModelVendor = entries.firstOrNull { it.name == name } ?: ARK

        /** 某个厂商在设置里的初始形态：地址与首选模型预填，Key 留空。 */
        fun preset(vendor: ModelVendor) = ModelSettings(
            baseUrl = vendor.baseUrl,
            model = vendor.defaultModel,
            vendor = vendor,
            protocol = vendor.protocol,
        )

        /** 按分组排好的完整列表；设置页的厂商选择器用它。 */
        fun grouped(): List<Pair<VendorKind, List<ModelVendor>>> =
            entries.groupBy { it.kind }
                .toList()
                .sortedBy { (_, list) -> list.first().kindOrder }
                .map { (kind, list) -> kind to list }
    }
}
