package dev.lily.app.settings

/** 方舟一体能接管的能力。 */
enum class ArkCapability { CHAT, OPERATOR, IMAGE, VOICE }

/**
 * 火山方舟一体：一个 API Key 打通聊天、操作、发图、朗读、识别与通话。
 *
 * ## Key 的两种类型，差别很大
 *
 * | Key | 创建入口 | 能用在哪 |
 * |---|---|---|
 * | 方舟 API Key（`ark-` 开头） | 方舟控制台 | **只有方舟**：聊天、操作、发图 |
 * | IAM API Key | 火山引擎 IAM 控制台 | 方舟 **+ 豆包语音**（朗读、识别、通话） |
 *
 * 方舟的 Key「仅用于调用方舟内模型或服务」，拿它调豆包语音会认证失败。
 * 想要一把 Key 打通全部，就得在 IAM 控制台建 Key，并给它的 IAM 用户
 * 绑定豆包语音的权限策略。
 *
 * 开关是分开的：聊天用方舟、操作用方舟、发图用方舟、语音用豆包，各���独立。
 * 关掉某一项，那一项就还归下面各厂商自己的设置管，互不干扰。
 */
data class ArkSettings(
    /** 方舟的 API Key（ark- 开头）。空着就等于没启用方舟一体。 */
    val apiKey: String = "",
    /** 聊天用哪个模型。 */
    val chatModel: String = ArkCatalog.DEFAULT_CHAT_MODEL,
    /** 操作模型用哪个：负责看屏幕、决定下一步动作。 */
    val operatorModel: String = ArkCatalog.DEFAULT_CHAT_MODEL,
    /** 发图用哪个模型。 */
    val imageModel: String = ArkCatalog.DEFAULT_IMAGE_MODEL,
    /** 聊天走方舟。 */
    val useChat: Boolean = false,
    /** 操作模型走方舟。 */
    val useOperator: Boolean = false,
    /** 发图走方舟。 */
    val useImage: Boolean = false,
    /** 朗读、语音识别、通话都走豆包语音（同一个 Key）。 */
    val useVoice: Boolean = false,
    /**
     * 火山引擎 Access Key ID。
     *
     * 只有它能让 App 调方舟的**管控面**接口：查官方价格、查官方用量。
     * 数据面（模型调用）仍然用上面的 API Key，两者是两套凭证。
     *
     * 注意主账号的 Access Key 权限很大，官方建议用 IAM 子账号。
     */
    val accessKeyId: String = "",
    /** 火山引擎 Secret Access Key。只在本机加密保存。 */
    val secretAccessKey: String = "",
) {
    /** 填了 Key 才算启用；没填的话下面的开关没有意义。 */
    val enabled: Boolean get() = apiKey.isNotBlank()

    /**
     * 看着像方舟的 Key（ark- 开头）。
     *
     * 这种 Key 只能用于方舟的模型，**调不了豆包语音**——用户开了语音开关又填了
     * 这种 Key 时，设置页要提醒一句，否则会白等一场认证失败。
     */
    val looksLikeArkOnlyKey: Boolean get() = apiKey.trim().startsWith("ark-", ignoreCase = true)

    /** 方舟的地址是固定的，文本模型与图片模型共用这一个。 */
    val baseUrl: String get() = ModelVendor.ARK.baseUrl

    /** 聊天用的那套连接配置。 */
    fun chatSettings() = ModelSettings(
        baseUrl = baseUrl,
        model = chatModel,
        apiKey = apiKey,
        vendor = ModelVendor.ARK,
        protocol = ModelVendor.ARK.protocol,
    )

    /** 操作模型用的那套连接配置。 */
    fun operatorSettings() = ModelSettings(
        baseUrl = baseUrl,
        model = operatorModel,
        apiKey = apiKey,
        vendor = ModelVendor.ARK,
        protocol = ModelVendor.ARK.protocol,
    )

    /** 开启了哪几项；一项都没开的话这个面板没什么用。 */
    val anyEnabled: Boolean get() = enabled && (useChat || useOperator || useImage || useVoice)

    /** 能不能调管控面接口（查官方价格与用量）。 */
    val hasAccount: Boolean get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    override fun toString() =
        "ArkSettings(enabled=$enabled, useChat=$useChat, useOperator=$useOperator, " +
            "useImage=$useImage, useVoice=$useVoice, chatModel=$chatModel, " +
            "operatorModel=$operatorModel, imageModel=$imageModel, " +
            "apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"})"
}
