package dev.lily.app.settings

/**
 * 火山方舟在售的模型与官方价格。
 *
 * 数据来自火山方舟官方文档，2026-11-28 抓取：
 * - 价格：https://docs.volcengine.com/docs/82379/1544106
 * - 模型列表：https://docs.volcengine.com/docs/82379/1330310
 *
 * 价格随时会调，这里只是给用户一个「大概要花多少」的参照；实际以官方结算为准。
 * 新模型上线时往 [chatModels] / [imageModels] 里加一条即可，模型 ID 不要写错——
 * 写错的话用户选了一个并不存在的模型，调用会直接报错。
 */
enum class ArkModality { CHAT, IMAGE }

/**
 * 一个方舟模型与它的价格。
 *
 * 文本模型按「每百万 tokens」计价：[inputPerMTok] 是输入单价，[outputPerMTok] 是输出单价。
 * 图片模型按张计价：[perImage]。
 */
data class ArkModel(
    val id: String,
    val displayName: String,
    val modality: ArkModality,
    val inputPerMTok: Double? = null,
    val outputPerMTok: Double? = null,
    val perImage: Double? = null,
    /** 补充说明：分段计费、时段差异、模型特点。 */
    val note: String = "",
    /**
     * 能不能看图（多模态理解）。
     *
     * 操作模型必须能看图——它要看手机截图才知道该点哪里。
     * 聊天模型用不上，但能看图的也能当聊天模型用。
     */
    val vision: Boolean = true,
) {
    /** 展示用的一行价格。 */
    val priceText: String
        get() = when (modality) {
            ArkModality.CHAT -> "输入 ¥${money(inputPerMTok)} · 输出 ¥${money(outputPerMTok)} / 百万 tokens"
            ArkModality.IMAGE -> "¥${money(perImage)} / 张"
        }

    /**
     * 一轮对话大概多少钱。
     *
     * 用来给用户一个体感：一千来字进、五百字出，大约是几分钱。
     */
    fun estimate(inputTokens: Int, outputTokens: Int): Double {
        val inCost = (inputPerMTok ?: 0.0) * inputTokens / 1_000_000.0
        val outCost = (outputPerMTok ?: 0.0) * outputTokens / 1_000_000.0
        return inCost + outCost
    }

    private fun money(value: Double?): String =
        if (value == null) "—" else String.format("%.2f", value)

    companion object {
        /** 一轮典型对话的 token 数：用户的输入加上她的回复。 */
        const val TYPICAL_INPUT_TOKENS = 1200
        const val TYPICAL_OUTPUT_TOKENS = 600
    }
}

/**
 * 方舟的模型清单。
 *
 * 只收在售且模型 ID 已经核实过的：
 * - `doubao-seed-2-0-pro-260215` 已标「即将下线」，没有收进来；
 * - DeepSeek 的空闲 / 高峰两个时段价不同，这里记空闲时段（多数时间适用），并在备注里说明。
 */
object ArkCatalog {

    /** 文本模型：聊天与操作共用。 */
    val chatModels: List<ArkModel> = listOf(
        ArkModel(
            id = "doubao-seed-2-1-pro-260915",
            displayName = "Doubao Seed 2.1 Pro",
            modality = ArkModality.CHAT,
            inputPerMTok = 6.00,
            outputPerMTok = 30.00,
            note = "1024K 上下文，综合最强",
        ),
        ArkModel(
            id = "doubao-seed-2-1-lite-260915",
            displayName = "Doubao Seed 2.1 Lite",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.80,
            outputPerMTok = 2.70,
            note = "1024K 上下文，又快又便宜",
        ),
        ArkModel(
            id = "doubao-seed-2-1-turbo-260628",
            displayName = "Doubao Seed 2.1 Turbo",
            modality = ArkModality.CHAT,
            inputPerMTok = 3.00,
            outputPerMTok = 15.00,
            note = "256K 上下文",
        ),
        ArkModel(
            id = "doubao-seed-2-0-lite-260428",
            displayName = "Doubao Seed 2.0 Lite",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.60,
            outputPerMTok = 3.60,
            note = "长期支持版；输入超过 32K 后单价更高",
        ),
        ArkModel(
            id = "doubao-seed-2-0-mini-260428",
            displayName = "Doubao Seed 2.0 Mini",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.20,
            outputPerMTok = 2.00,
            note = "长期支持版；最便宜",
        ),
        ArkModel(
            id = "doubao-seed-evolving",
            displayName = "Doubao Seed Evolving",
            modality = ArkModality.CHAT,
            inputPerMTok = 6.00,
            outputPerMTok = 30.00,
            note = "周级迭代，偏 Coding 与 Agent",
        ),
        ArkModel(
            id = "doubao-seed-character-260628",
            displayName = "Doubao Seed Character",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.80,
            outputPerMTok = 2.00,
            note = "角色扮演；输入超过 32K 后单价更高",
        ),
        ArkModel(
            id = "doubao-seed-translation-250915",
            displayName = "Doubao Seed Translation",
            modality = ArkModality.CHAT,
            inputPerMTok = 1.20,
            outputPerMTok = 3.60,
            note = "翻译专用",
                    vision = false,
        ),
        ArkModel(
            id = "glm-5-3-flash-260828",
            displayName = "GLM 5.3 Flash",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.80,
            outputPerMTok = 2.80,
            note = "1024K 上下文",
        ),
        ArkModel(
            id = "glm-5-2-260617",
            displayName = "GLM 5.2",
            modality = ArkModality.CHAT,
            inputPerMTok = 8.00,
            outputPerMTok = 28.00,
            note = "1024K 上下文",
                    vision = false,
        ),
        ArkModel(
            id = "kimi-k3-1-pro-261024",
            displayName = "Kimi K3.1 Pro",
            modality = ArkModality.CHAT,
            inputPerMTok = 13.00,
            outputPerMTok = 68.00,
            note = "最贵；长文本见下方说明",
        ),
        ArkModel(
            id = "deepseek-v4-1-flash-261017",
            displayName = "DeepSeek V4.1 Flash",
            modality = ArkModality.CHAT,
            inputPerMTok = 1.00,
            outputPerMTok = 4.00,
            note = "工作日 9-12、14-18 点高峰价翻倍",
        ),
        ArkModel(
            id = "deepseek-v4-1-flash-economy-261017",
            displayName = "DeepSeek V4.1 Flash 经济版",
            modality = ArkModality.CHAT,
            inputPerMTok = 0.80,
            outputPerMTok = 3.20,
            note = "高峰时段单价更高",
        ),
        ArkModel(
            id = "deepseek-v4-pro-ga-260813",
            displayName = "DeepSeek V4 Pro",
            modality = ArkModality.CHAT,
            inputPerMTok = 9.00,
            outputPerMTok = 27.00,
            note = "1024K 上下文",
                    vision = false,
        ),
        ArkModel(
            id = "deepseek-v4-flash-ga-260731",
            displayName = "DeepSeek V4 Flash",
            modality = ArkModality.CHAT,
            inputPerMTok = 3.00,
            outputPerMTok = 9.00,
            note = "1024K 上下文",
                    vision = false,
        ),
    )

    /** 图片模型：她主动发图用的那一个。 */
    val imageModels: List<ArkModel> = listOf(
        ArkModel(
            id = "doubao-seedream-5-0-flash-260915",
            displayName = "Seedream 5.0 Flash",
            modality = ArkModality.IMAGE,
            perImage = 0.12,
            note = "日常够用，最便宜",
        ),
        ArkModel(
            id = "doubao-seedream-5-0-pro-260628",
            displayName = "Seedream 5.0 Pro",
            modality = ArkModality.IMAGE,
            perImage = 0.30,
            note = "首张免费；像素更高时 0.60 / 张",
        ),
        ArkModel(
            id = "doubao-seedream-5-5-261028",
            displayName = "Seedream 5.5",
            modality = ArkModality.IMAGE,
            perImage = 0.13,
            note = "最新；像素更高时 0.23 或 0.52 / 张",
        ),
    )

    /** 聊天与操作的默认模型。 */
    const val DEFAULT_CHAT_MODEL = "doubao-seed-2-1-pro-260915"

    /** 发图的默认模型。 */
    const val DEFAULT_IMAGE_MODEL = "doubao-seedream-5-0-flash-260915"

    private val all: List<ArkModel> = chatModels + imageModels

    /** 按模型 ID 找；找不到返回 null（用户自己填的 ID 也可能不在清单里）。 */
    fun byId(id: String): ArkModel? = all.firstOrNull { it.id == id }

    fun chat(id: String): ArkModel? = chatModels.firstOrNull { it.id == id }

    fun image(id: String): ArkModel? = imageModels.firstOrNull { it.id == id }

    /**
     * 用户可能自己填了清单里没有的模型 ID。
     * 这种情况下不编价格——宁可空着，也不给一个错的数字。
     */
    fun priceTextOf(id: String): String = byId(id)?.priceText.orEmpty()
}
