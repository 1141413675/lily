package dev.lily.app.settings

/** 模型怎么接。 */
enum class ProvisioningMode {
    /**
     * 一体化：选一个服务方，一个 Key 打通它的全部模型。
     *
     * 适合那种「一个 Key 什么都有」的厂商——火山方舟就是典型：
     * 聊天、操作、发图、朗读、识别、通话都能用同一个账号。
     */
    UNIFIED,

    /**
     * 自定义：每一项各挑各的厂家。
     *
     * 比如语言模型用 A 家、语音用 B 家、通话用 C 家。
     */
    CUSTOM,
}

/**
 * 一体化接法下的那一个服务方。
 *
 * 填一个 Key，下面的模型都从这一家挑；能覆盖几项就覆盖几项——
 * 比如豆包语音只属于火山引擎，挑别家时语音就仍归自定义那边管。
 */
data class UnifiedProvider(
    /** 选的服务方。 */
    val vendor: ModelVendor = ModelVendor.ARK,
    /** 这家的 API Key。 */
    val apiKey: String = "",
    /** 聊天用哪个模型。 */
    val chatModel: String = "",
    /** 操作模型用哪个模型：负责看屏幕、决定下一步动作。 */
    val operatorModel: String = "",
    /** 发图用哪个模型；这家没有生图就留空，仍用原来的设置。 */
    val imageModel: String = "",
    /** 朗读用哪个模型。 */
    val ttsModel: String = "",
    /** 语音识别用哪个模型。 */
    val asrModel: String = "",
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
    /** 填了 Key 才算配好。 */
    val enabled: Boolean get() = apiKey.isNotBlank()

    /** 能不能调管控面接口（查官方价格与用量）。只对方舟有意义。 */
    val hasAccount: Boolean get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    /** 这一家是不是火山方舟——只有它带豆包语音。 */
    val isVolcano: Boolean get() = vendor == ModelVendor.ARK

    /** 聊天用的那套连接配置。 */
    fun chatSettings() = ModelSettings(
        baseUrl = vendor.baseUrl,
        model = chatModel,
        apiKey = apiKey,
        vendor = vendor,
        protocol = vendor.protocol,
    )

    /** 操作模型用的那套连接配置。 */
    fun operatorSettings() = ModelSettings(
        baseUrl = vendor.baseUrl,
        model = operatorModel,
        apiKey = apiKey,
        vendor = vendor,
        protocol = vendor.protocol,
    )

    /**
     * 看着像方舟的 Key（`ark-` 开头）。
     *
     * 这种 Key 只能用于方舟的模型，**调不了豆包语音**——用户开了语音又填了
     * 这种 Key 时，设置页要提醒一句，否则会白等一场认证失败。
     */
    val looksLikeArkOnlyKey: Boolean get() = apiKey.trim().startsWith("ark-", ignoreCase = true)

    override fun toString() =
        "UnifiedProvider(vendor=$vendor, enabled=$enabled, chatModel=$chatModel, " +
            "operatorModel=$operatorModel, imageModel=$imageModel, ttsModel=$ttsModel, " +
            "asrModel=$asrModel, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"})"
}
