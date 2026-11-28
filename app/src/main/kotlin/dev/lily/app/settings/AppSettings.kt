package dev.lily.app.settings

import dev.lily.api.AgentConfig
import dev.lily.api.ApiProtocol
import dev.lily.api.ModelConfig
import dev.lily.api.ModelRole
import kotlinx.serialization.Serializable
import java.net.URI

/** One OpenAI-compatible endpoint as typed into Settings. [toString] never shows [apiKey]. */
data class ModelSettings(
    val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    /** 选中的厂商预设；决定默认地址、常用模型与走哪套协议。 */
    val vendor: ModelVendor = ModelVendor.ARK,
    /** 实际协议；预设厂商固定，自定义时可声明 OpenAI 兼容或 Anthropic 原生。 */
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
) {
    /** 本机服务（Ollama、LM Studio）没有厂商 Key，地址与模型填好就算配置好了。 */
    val configured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || vendor.keyOptional)


    fun toModelConfig(role: ModelRole) = ModelConfig(
        role = role,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        temperature = temperature,
        protocol = protocol,
    )

    override fun toString() =
        "ModelSettings(vendor=$vendor, protocol=$protocol, baseUrl=$baseUrl, model=$model, " +
            "apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

    companion object {
        /** Volcano Ark. */
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

/** The separate OpenAI-compatible endpoint for semantic memory search. */
data class MemoryEmbeddingSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    val requestUrl: String?
        get() {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty() || base.length > MAX_BASE_URL) return null
            val uri = runCatching { URI(base) }.getOrNull() ?: return null
            val host = uri.host?.removeSurrounding("[", "]")?.lowercase() ?: return null
            val protocol = uri.scheme?.lowercase() ?: return null
            if (protocol != "https" && !(protocol == "http" && host in LOOPBACK_HOSTS)) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            if (uri.rawPath.trimEnd('/').endsWith("/embeddings", ignoreCase = true)) return null
            return "${uri.toASCIIString()}/embeddings"
        }

    val modelValid: Boolean get() = model.isNotBlank() && model.length <= MAX_MODEL && model.none(Char::isISOControl)
    val keyValid: Boolean get() = apiKey.length <= MAX_API_KEY && apiKey.all { it in '!'..'~' }
    val configured: Boolean get() = requestUrl != null && modelValid && keyValid
    val active: Boolean get() = enabled && configured

    internal fun normalized(): MemoryEmbeddingSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        apiKey = apiKey.trim(),
    )

    // Form values can contain secrets pasted into the wrong field before validation.
    override fun toString(): String = "MemoryEmbeddingSettings(enabled=$enabled, configured=$configured)"

    companion object {
        const val MAX_BASE_URL = 2048
        const val MAX_MODEL = 256
        const val MAX_API_KEY = 4096
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}

/**
 * 一个语音接口的设置。
 *
 * [vendor] 决定协议：
 * - OpenAI 兼容（硅基、OpenAI、Groq）：`POST {baseUrl}/audio/speech` 与 `/audio/transcriptions`；
 * - 豆包：WebSocket 双向流，鉴权用 [apiKey]（`X-Api-Key`）与 [resourceId]（`X-Api-Resource-Id`）；
 * - MiniMax：JSON 请求，`data.audio` 是 hex 编码；
 * - ElevenLabs：`xi-api-key` 头，音色 id 放在 URL 路径里。
 *
 * [voice] 是音色 id；各家不一样，豆包 2.0 是 `*_uranus_bigtts`。
 */
data class VoiceSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val voice: String = "",
    /**
     * 朗读语速；各家都有对应参数。默认 1.1 比原速略快，更接近日常说话的节奏；
     * 设置页的滑杆直接改这个值，逐句合成时每句都用同一个语速。
     */
    val speed: Float = 1.1f,
    /** 这一份配置属于哪家。 */
    val vendor: VoiceVendor = VoiceVendor.SILICONFLOW,
    /** 豆包才需要：模型版本，2.0 固定 `seed-tts-2.0`，放在 `X-Api-Resource-Id` 头里。 */
    val resourceId: String = "",
    /** MiniMax 部分账号要按组授权，缺了会 401；有就填。 */
    val groupId: String = "",
) {
    val configured: Boolean
        get() = when (vendor) {
            VoiceVendor.DOUBAO -> baseUrl.isNotBlank() && apiKey.isNotBlank() &&
                resourceId.isNotBlank()
            else -> baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
        }

    override fun toString() =
        "VoiceSettings(vendor=$vendor, baseUrl=$baseUrl, model=$model, " +
            "apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, voice=$voice, " +
            "speed=$speed, groupId=$groupId)"

    companion object {
        /** SiliconFlow: both audio endpoints are OpenAI-compatible. */
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
        const val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B"
        /** 不在安装时写入具体音色：留空，实际音色由性别决定（见 Voices.effective）。 */
        const val DEFAULT_TTS_VOICE = ""

        /** 豆包语音合成 2.0：WebSocket 双向流式（不是方舟 Ark，也不是 v1 的 api/v1/tts）。 */
        const val DOUBAO_BASE_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        const val DOUBAO_RESOURCE_ID = "seed-tts-2.0"

        /** 豆包流式语音识别 2.0（大模型双向流式），地址固定，不用旧 v2 的 AppID/Cluster。 */
        const val DOUBAO_ASR_BASE_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val DOUBAO_ASR_RESOURCE_ID = "volc.seedasr.sauc.duration"

        /**
         * 豆包实时语音模型 3.0（Seeduplex）全双工端点。
         *
         * 豆包语音的接口都在 `openspeech.bytedance.com` 下，鉴权用 `X-Api-Key`，
         * 与豆包 TTS/ASR 是同一个 Key，不需要另外申请。
         *
         * 注意：这个 Key 不能用方舟控制台创建的 `ark-` Key——方舟的 Key 只管方舟内
         * 的模型与服务。要一把 Key 通吃，得用火山引擎 IAM 控制台创建的 Key，
         * 并给它绑上豆包语音的权限策略。
         */
        const val DEFAULT_REALTIME_BASE_URL = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"

        /** 全双工版本的模型固定值，由服务端按 `session.model` 判定，不随设置变化。 */
        const val DEFAULT_REALTIME_MODEL = "1.2.6.1"

        /** 兜底音色：官方 S2S 音色列表里的 Vivi 2.0。 */
        const val DEFAULT_REALTIME_VOICE = "zh_female_vv_uranus_bigtts"
    }
}

/**
 * 朗读设置：挑一家，每家一份配置都留在本机。
 *
 * 切换只改 [vendor]，各家的地址、Key、音色都还在，切回去不用重填。
 */
data class TtsSettings(
    val vendor: VoiceVendor = VoiceVendor.SILICONFLOW,
    /** 每家一份配置；没存过的用 [VoiceVendor.ttsPreset] 补。 */
    val byVendor: Map<VoiceVendor, VoiceSettings> = emptyMap(),
) {
    /** 某家的配置。 */
    fun configOf(vendor: VoiceVendor): VoiceSettings =
        byVendor[vendor] ?: vendor.ttsPreset()

    /** 当前这一家的原始配置。 */
    val active: VoiceSettings get() = configOf(vendor)

    val configured: Boolean get() = active.configured

    val speed: Float get() = active.speed

    /** 改语速：所有已存的配置一起改，当前这家即使没存过也要跟上。 */
    fun withSpeed(value: Float): TtsSettings {
        val updated = byVendor.mapValues { (_, c) -> c.copy(speed = value) }.toMutableMap()
        updated[vendor] = configOf(vendor).copy(speed = value)
        return copy(byVendor = updated)
    }

    /**
     * 发请求用的配置：只用角色自己的音色；角色没设过、或音色不属于当前引擎时，
     * 按性别取该引擎默认，不回落任何全局音色。
     */
    fun request(personaVoice: String, gender: dev.lily.app.persona.Gender): VoiceSettings {
        val picked = personaVoice.trim()
        return when (vendor) {
            VoiceVendor.DOUBAO -> {
                val voice = picked.takeIf { dev.lily.app.persona.Voices.isDoubao(it) }
                    ?: dev.lily.app.persona.Voices.doubaoDefault(gender)
                configOf(vendor).copy(voice = voice, vendor = vendor)
            }
            VoiceVendor.SILICONFLOW -> {
                val voice = picked.takeIf { dev.lily.app.persona.Voices.isSilicon(it) }
                    ?: dev.lily.app.persona.Voices.defaultVoice(gender)
                configOf(vendor).copy(voice = voice, vendor = vendor)
            }
            // 其它家音色 id 各不相同，人设里选的那个多半不属于它，就用这一家自己的默认。
            else -> configOf(vendor).copy(
                voice = picked.takeIf { it.isNotBlank() } ?: vendor.defaultVoice,
                vendor = vendor,
            )
        }
    }
}

/**
 * 语音识别设置：挑一家，每家一份配置都留在本机。
 *
 * 各家都放在 [byVendor] 里；切换只改 [vendor]。
 */
data class AsrSettings(
    val vendor: VoiceVendor = VoiceVendor.SILICONFLOW,
    val byVendor: Map<VoiceVendor, VoiceSettings> = emptyMap(),
) {
    /** 某家的配置。 */
    fun configOf(vendor: VoiceVendor): VoiceSettings =
        byVendor[vendor] ?: vendor.asrPreset()

    /** 当前这一家的原始配置。 */
    val active: VoiceSettings get() = configOf(vendor)

    /**
     * 豆包只要 API Key；Resource ID 留空时按官方默认小时版处理。
     * 不复用 [VoiceSettings.configured]：那套规则是朗读的（还要求音色）。
     */
    val configured: Boolean
        get() = when (vendor) {
            VoiceVendor.DOUBAO -> active.apiKey.isNotBlank()
            else -> active.configured
        }

    /** 实际发请求用的 Resource ID。 */
    val resourceId: String
        get() = active.resourceId.trim().ifBlank { VoiceSettings.DOUBAO_ASR_RESOURCE_ID }

    /** 豆包失败时用于兜底的硅基配置；没填就是 null。 */
    val siliconFallback: VoiceSettings?
        get() = configOf(VoiceVendor.SILICONFLOW).takeIf { it.configured }

    /** 还缺哪些必填项（可读文字）；空表示当前引擎已配好。 */
    val missing: List<String>
        get() {
            val current = active
            return when (vendor) {
                VoiceVendor.DOUBAO -> if (current.apiKey.isBlank()) listOf("API Key") else emptyList()
                else -> buildList {
                    if (current.baseUrl.isBlank()) add("地址")
                    if (current.model.isBlank()) add("模型")
                    if (current.apiKey.isBlank()) add("API Key")
                }
            }
        }

    override fun toString() = "AsrSettings(vendor=$vendor, byVendor=${byVendor.keys})"
}

/**
 * How an assistant reply reaches the user: [AUTO] keeps the default (text, no reading), [TEXT] is
 * text only, [VOICE] posts a voice bubble without the text body, [CALL] opens the call screen and
 * reads the reply. Locked here, so the model is never asked to choose.
 */
enum class ReplyMode { AUTO, TEXT, VOICE, CALL }

/**
 * 她主动发图：开关、生成内容是否包含自拍。
 * 自拍用头像做参考图；没设头像时自动退回生活场景。
 *
 * 不限次数：一张图约 ¥0.12，费用由用户自己的方舟账号承担，所以这里不设每日上限。
 */
data class ImageSettings(
    val enabled: Boolean = true,
    val includeSelfie: Boolean = true,
    /** 方舟图片模型，可在「发图」页改；留空时回退 [DEFAULT_MODEL]。 */
    val model: String = DEFAULT_MODEL,
) {
    val effectiveModel: String get() = model.trim().ifBlank { DEFAULT_MODEL }

    companion object {
        /** 方舟当前在售的 Seedream 5.0 flash；旧 4.0 已下线。 */
        const val DEFAULT_MODEL = "doubao-seedream-5-0-flash-260915"
    }
}

/**
 * 主动性设置。关系档案本身在 Markdown 记忆里，这里只放频率、免打扰与降级开关。
 * [neutralTone] 是降级档：关闭主动、去掉亲昵语气、把间隔拉长。
 */
@Serializable
data class RelationSettings(
    val proactive: Boolean = true,
    /** 每天最多主动开口几次。 */
    val maxPerDay: Int = 1,
    /** 两次主动之间的最小间隔。 */
    val minGapHours: Int = 8,
    /** 免打扰起止，本地 `HH:mm`；跨夜时 start > end。 */
    val quietStart: String = "22:30",
    val quietEnd: String = "08:00",
    /** 习惯连续多少天没完成才提醒。 */
    val habitMissDays: Int = 3,
    /** 距用户最后一次发言多少小时算「到点没联系」。 */
    val absenceHours: Int = 20,
    val neutralTone: Boolean = false,
) {
    companion object {
        val MAX_PER_DAY_RANGE = 1..3
        val MIN_GAP_RANGE = 1..48
        val HABIT_MISS_RANGE = 1..14
        val ABSENCE_RANGE = 4..72

        /** 一键降级：关主动、中性语气、24 小时间隔。 */
        fun degraded() = RelationSettings(proactive = false, neutralTone = true, minGapHours = 24)
    }
}

data class AppSettings(
    val chat: ModelSettings = ModelSettings(),
    val operator: ModelSettings = ModelSettings(),
    /**
     * 每个厂商各自的聊天配置。切换厂商只改 [chat]，这里的其他厂商（含 Key）原样保留，
     * 切回去不用重填。当前生效的配置始终以 [chat] 为准。
     */
    val chatVendors: Map<ModelVendor, ModelSettings> = emptyMap(),
    /** 操作模型同理，按厂商各存一份。 */
    val operatorVendors: Map<ModelVendor, ModelSettings> = emptyMap(),
    val asr: AsrSettings = AsrSettings(),
    /**
     * 模型怎么接：一体化（一个 Key 打通全部）还是自定义（各挑各的）。
     */
    val provisioning: ProvisioningMode = ProvisioningMode.CUSTOM,
    /**
     * 一体化时用的那一个服务方。填一个 Key，下面的模型都从这一家挑。
     */
    val unified: UnifiedProvider = UnifiedProvider(),
    /** 朗读引擎：硅基 / 豆包 两套配置都保留，切换立即生效。 */
    val tts: TtsSettings = TtsSettings(),
    /** Real-time full-duplex call channel; empty key means it is off and the hands-free fallback is used. */
    val realtime: VoiceSettings = VoiceSettings(
        baseUrl = VoiceSettings.DEFAULT_REALTIME_BASE_URL,
        model = VoiceSettings.DEFAULT_REALTIME_MODEL,
        voice = VoiceSettings.DEFAULT_REALTIME_VOICE,
    ),
    /** 默认关闭：通话走「录音 → 识别 → 对话 → 朗读」的轮次链路。开启后才尝试实时通道。 */
    val realtimeEnabled: Boolean = false,
    val replyMode: ReplyMode = ReplyMode.AUTO,
    /** 旧版本只有「文字/语音」两个强制档，那些值不算主动选择；迁移时按情况处理。 */
    val replyModeChosen: Boolean = false,
    /** 迁移过旧的两档选择时置 true，设置页提示一次。 */
    val replyModeNotice: Boolean = false,
    val relation: RelationSettings = RelationSettings(),
    /** Lily是谁：昵称、自称、性别、语气、说话长短、统一音色。 */
    val persona: dev.lily.app.persona.PersonaSettings = dev.lily.app.persona.PersonaSettings(),
    /** 表情尺度：克制 / 自然 / 丰富，只影响文字里的 emoji 与颜文字。 */
    val emojiStyle: dev.lily.app.chat.EmojiStyle = dev.lily.app.chat.EmojiStyle.NATURAL,
    /** 明暗模式与聊天背景。 */
    val appearance: dev.lily.app.appearance.AppearanceSettings = dev.lily.app.appearance.AppearanceSettings(),
    /** 她自己的生活设定：只用于她写的动态。 */
    val life: dev.lily.app.life.LifeSettings = dev.lily.app.life.LifeSettings(),
    /** 她主动发图：开关、每日生成上限、是否包含自拍。 */
    val image: ImageSettings = ImageSettings(),
    /** 旧的两处音色不一致时置 true，设置页提示用户确认统一后的音色。 */
    val personaVoiceNotice: Boolean = false,
    val maxSteps: Int = AgentConfig().maxSteps,
    val learnFromSuccess: Boolean = AgentConfig().learnFromSuccess,
    val memoryEmbedding: MemoryEmbeddingSettings = MemoryEmbeddingSettings(),
) {
    val configured: Boolean get() = chat.configured && operator.configured

    /** 实际用于朗读/语音消息的音色：人格设定里留空时按性别默认；豆包音色不送进硅基实时通道。 */
    val speechVoice: String
        get() = if (dev.lily.app.persona.Voices.isDoubao(persona.voice.trim())) {
            dev.lily.app.persona.Voices.defaultVoice(persona.gender)
        } else {
            dev.lily.app.persona.Voices.effective(persona.voice, persona.gender)
        }

    /** 本次朗读要用的接口配置（含供应商、音色、Resource ID）。 */
    val ttsRequest: VoiceSettings get() = tts.request(persona.voice, persona.gender)

    /** 豆包出错时的兜底：硅基还配着就用它，保证通话不会整段没声音。 */
    val ttsFallback: VoiceSettings?
        get() = if (tts.vendor == VoiceVendor.DOUBAO) {
            tts.copy(vendor = VoiceVendor.SILICONFLOW)
                .request(persona.voice, persona.gender)
                .takeIf { it.configured }
        } else {
            null
        }

    /** Values to mask in traces and logs. 所有厂商的 Key 都算，包括暂时没在用的那几套。 */
    val secrets: List<String>
        get() = buildList {
            add(chat.apiKey)
            add(operator.apiKey)
            chatVendors.values.forEach { add(it.apiKey) }
            operatorVendors.values.forEach { add(it.apiKey) }
            asr.byVendor.values.forEach { add(it.apiKey) }
            add(unified.apiKey)
            add(unified.secretAccessKey)
            tts.byVendor.values.forEach { add(it.apiKey) }
            add(realtime.apiKey)
            add(memoryEmbedding.apiKey)
        }.filter { it.isNotBlank() }.distinct()

    fun agentConfig() = AgentConfig(maxSteps = maxSteps, learnFromSuccess = learnFromSuccess)

    companion object {
        val MAX_STEPS_RANGE = 1..200
    }
}

/**
 * The editable text of the Settings screen. Parsing lives here, not in the Composable.
 */
data class SettingsForm(
    val chat: ModelForm = ModelForm(),
    val operator: ModelForm = ModelForm(),
    /** 其他厂商已经填好的聊天配置；切厂商时原样保留，切回即用。 */
    val chatVendors: Map<ModelVendor, ModelSettings> = emptyMap(),
    val operatorVendors: Map<ModelVendor, ModelSettings> = emptyMap(),
    val asr: AsrForm = AsrForm(),
    val tts: TtsForm = TtsForm(),
    /** 模型怎么接：一体化还是自定义。 */
    val provisioning: ProvisioningMode = ProvisioningMode.CUSTOM,
    /** 一体化时用的那一个服务方。 */
    val unified: UnifiedForm = UnifiedForm(),
    val realtime: VoiceForm = VoiceForm(
        baseUrl = VoiceSettings.DEFAULT_REALTIME_BASE_URL,
        model = VoiceSettings.DEFAULT_REALTIME_MODEL,
        voice = VoiceSettings.DEFAULT_REALTIME_VOICE,
    ),
    /** 实时通道开关；默认关闭。 */
    val realtimeEnabled: Boolean = false,
    val replyMode: ReplyMode = ReplyMode.AUTO,
    val replyModeChosen: Boolean = false,
    val replyModeNotice: Boolean = false,
    val relation: RelationSettings = RelationSettings(),
    val persona: dev.lily.app.persona.PersonaSettings = dev.lily.app.persona.PersonaSettings(),
    val emojiStyle: dev.lily.app.chat.EmojiStyle = dev.lily.app.chat.EmojiStyle.NATURAL,
    val appearance: dev.lily.app.appearance.AppearanceSettings = dev.lily.app.appearance.AppearanceSettings(),
    val life: dev.lily.app.life.LifeSettings = dev.lily.app.life.LifeSettings(),
    val image: ImageSettings = ImageSettings(),
    val personaVoiceNotice: Boolean = false,
    val maxSteps: String = AgentConfig().maxSteps.toString(),
    val learnFromSuccess: Boolean = true,
    val memoryEmbedding: MemoryEmbeddingSettings = MemoryEmbeddingSettings(),
) {
    /**
     * 一体化接法的表单：一个服务方、一个 Key、几个模型。
     * 单独放一个 form 是因为它管的是多个角色，不属于任何单一角色。
     */
    data class UnifiedForm(
        val vendor: ModelVendor = ModelVendor.ARK,
        val apiKey: String = "",
        val chatModel: String = "",
        val operatorModel: String = "",
        val imageModel: String = "",
        /** 朗读用哪个模型。 */
        val ttsModel: String = "",
        /** 语音识别用哪个模型。 */
        val asrModel: String = "",
        /** 火山引擎 Access Key ID：用来调管控面（查官方价格与官方用量）。 */
        val accessKeyId: String = "",
        /** 火山引擎 Secret Access Key。 */
        val secretAccessKey: String = "",
    ) {
        fun toSettings() = UnifiedProvider(
            vendor = vendor,
            apiKey = apiKey.trim(),
            chatModel = chatModel.trim(),
            operatorModel = operatorModel.trim(),
            imageModel = imageModel.trim(),
            ttsModel = ttsModel.trim(),
            asrModel = asrModel.trim(),
            accessKeyId = accessKeyId.trim(),
            secretAccessKey = secretAccessKey.trim(),
        )

        override fun toString() =
            "UnifiedForm(vendor=$vendor, chatModel=$chatModel, operatorModel=$operatorModel, " +
                "imageModel=$imageModel, ttsModel=$ttsModel, asrModel=$asrModel, " +
                "apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"})"

        companion object {
            fun of(u: UnifiedProvider) = UnifiedForm(
                vendor = u.vendor,
                apiKey = u.apiKey,
                chatModel = u.chatModel,
                operatorModel = u.operatorModel,
                imageModel = u.imageModel,
                ttsModel = u.ttsModel,
                asrModel = u.asrModel,
                accessKeyId = u.accessKeyId,
                secretAccessKey = u.secretAccessKey,
            )
        }
    }

    data class ModelForm(
        val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
        val model: String = "",
        val apiKey: String = "",
        val temperature: String = "",
        val vendor: ModelVendor = ModelVendor.ARK,
        val protocol: ApiProtocol = ApiProtocol.OPENAI,
    ) {
        val temperatureValid: Boolean get() = parseTemperature(temperature) != null || temperature.isBlank()

        fun toSettings(): ModelSettings? {
            val t = if (temperature.isBlank()) null else parseTemperature(temperature) ?: return null
            return ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), t, vendor, protocol)
        }

        /** 切厂商时暂存当前输入；温度不合法也不阻挡切换，Key 与地址照留。 */
        fun draft(): ModelSettings = ModelSettings(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            apiKey = apiKey.trim(),
            temperature = parseTemperature(temperature),
            vendor = vendor,
            protocol = protocol,
        )

        override fun toString() =
            "ModelForm(vendor=$vendor, protocol=$protocol, baseUrl=$baseUrl, model=$model, " +
                "apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

        companion object {
            fun of(s: ModelSettings) =
                ModelForm(s.baseUrl, s.model, s.apiKey, s.temperature?.toString().orEmpty(), s.vendor, s.protocol)
        }
    }

    data class VoiceForm(
        val baseUrl: String = VoiceSettings.DEFAULT_BASE_URL,
        val model: String = "",
        val apiKey: String = "",
        val voice: String = "",
        /** 朗读语速。 */
        val speed: Float = 1.1f,
        /** 豆包要：模型版本放在 `X-Api-Resource-Id` 头里。 */
        val resourceId: String = "",
        /** MiniMax 部分账号要按组授权，缺了会 401。 */
        val groupId: String = "",
    ) {
        fun toSettings() = VoiceSettings(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            apiKey = apiKey.trim(),
            voice = voice.trim(),
            speed = speed,
            resourceId = resourceId.trim(),
            groupId = groupId.trim(),
        )

        override fun toString() =
            "VoiceForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, " +
                "voice=$voice, speed=$speed, resourceId=$resourceId, groupId=$groupId)"

        companion object {
            fun of(s: VoiceSettings) = VoiceForm(
                s.baseUrl, s.model, s.apiKey, s.voice, s.speed, s.resourceId, s.groupId,
            )
        }
    }

    /** 朗读的表单：挑一家，编辑这一家的配置；别家的原样留着。 */
    data class TtsForm(
        val vendor: VoiceVendor = VoiceVendor.SILICONFLOW,
        /** 每家一份表单；没存过的用厂商预设补。 */
        val byVendor: Map<VoiceVendor, VoiceForm> = emptyMap(),
    ) {
        /** 某家的表单。 */
        fun formOf(vendor: VoiceVendor): VoiceForm =
            byVendor[vendor] ?: VoiceForm.of(vendor.ttsPreset())

        /** 当前这一家的表单。 */
        val active: VoiceForm get() = formOf(vendor)

        /** 改当前这一家的表单；别家原样留着。 */
        fun withVendor(vendor: VoiceVendor, form: VoiceForm): TtsForm =
            copy(byVendor = byVendor + (vendor to form))

        /** 当前语速。 */
        val speed: Float get() = active.speed

        /** 改语速：所有已存的配置一起改，当前这家即使没存过也要跟上。 */
        fun withSpeed(value: Float): TtsForm {
            val updated = byVendor.mapValues { (_, f) -> f.copy(speed = value) }.toMutableMap()
            updated[vendor] = formOf(vendor).copy(speed = value)
            return copy(byVendor = updated)
        }

        fun toSettings() = TtsSettings(
            vendor = vendor,
            // 只存用户真的配过的那些家：没配过的用厂商预设，不必写进设置。
            byVendor = byVendor.mapValues { (v, f) ->
                val settings = f.toSettings().copy(vendor = v)
                // 设置页不再保存全局音色；旧版本遗留的值在这里清掉，音色只看角色档案。
                if (v == VoiceVendor.SILICONFLOW) settings.copy(voice = "") else settings
            },
        )

        override fun toString() = "TtsForm(vendor=$vendor, byVendor=${byVendor.keys})"

        companion object {
            fun of(t: TtsSettings) = TtsForm(
                vendor = t.vendor,
                byVendor = t.byVendor.mapValues { (_, s) -> VoiceForm.of(s) },
            )
        }
    }

    /** 语音识别的表单：挑一家，编辑这一家的配置；别家的原样留着。 */
    data class AsrForm(
        val vendor: VoiceVendor = VoiceVendor.SILICONFLOW,
        val byVendor: Map<VoiceVendor, VoiceForm> = emptyMap(),
    ) {
        /** 某家的表单。 */
        fun formOf(vendor: VoiceVendor): VoiceForm =
            byVendor[vendor] ?: VoiceForm.of(vendor.asrPreset())

        /** 当前这一家的表单。 */
        val active: VoiceForm get() = formOf(vendor)

        /** 改当前这一家的表单；别家原样留着。 */
        fun withVendor(vendor: VoiceVendor, form: VoiceForm): AsrForm =
            copy(byVendor = byVendor + (vendor to form))

        fun toSettings() = AsrSettings(
            vendor = vendor,
            // 只存用户真的配过的那些家：没配过的用厂商预设，不必写进设置。
            byVendor = byVendor.mapValues { (v, f) -> f.toSettings().copy(vendor = v) },
        )

        override fun toString() = "AsrForm(vendor=$vendor, byVendor=${byVendor.keys})"

        companion object {
            fun of(a: AsrSettings) = AsrForm(
                vendor = a.vendor,
                byVendor = a.byVendor.mapValues { (_, s) -> VoiceForm.of(s) },
            )
        }
    }

    val maxStepsValid: Boolean get() = maxSteps.trim().toIntOrNull()?.let { it in AppSettings.MAX_STEPS_RANGE } == true

    /** 切聊天厂商：先把当前输入（含还没保存的 Key）存进档案，再换成目标厂商的配置。 */
    fun withChatVendor(vendor: ModelVendor): SettingsForm {
        if (vendor == chat.vendor) return this
        val profiles = chatVendors + (chat.vendor to chat.draft())
        return copy(chat = ModelForm.of(profiles[vendor] ?: ModelVendor.preset(vendor)), chatVendors = profiles)
    }

    /** 切操作模型厂商，规则同 [withChatVendor]。 */
    fun withOperatorVendor(vendor: ModelVendor): SettingsForm {
        if (vendor == operator.vendor) return this
        val profiles = operatorVendors + (operator.vendor to operator.draft())
        return copy(operator = ModelForm.of(profiles[vendor] ?: ModelVendor.preset(vendor)), operatorVendors = profiles)
    }

    /** 某厂商在表单里的可用模型：内置兜底 + 接口拉到的那份。 */
    fun modelChoices(vendor: ModelVendor, fetched: List<String> = emptyList()): List<String> =
        (vendor.models + fetched).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 把一体化接法写进它覆盖的能力。
     *
     * 选了「一体化」并且填了 Key 才会生效：聊天、操作、发图都用那一个服务方；
     * 语音只有火山方舟带豆包语音，挑别家时语音仍归自定义那边管。
     * 幂等：可以反复调用。
     */
    fun applyUnified(): SettingsForm {
        if (provisioning != ProvisioningMode.UNIFIED) return this
        val u = unified.toSettings()
        if (!u.enabled) return this
        var chat = chat
        var operator = operator
        var image = image
        var tts = tts
        var asr = asr
        var realtime = realtime
        if (u.chatModel.isNotBlank()) chat = ModelForm.of(u.chatSettings())
        if (u.operatorModel.isNotBlank()) operator = ModelForm.of(u.operatorSettings())
        if (u.imageModel.isNotBlank()) image = image.copy(model = u.imageModel)
        if (u.isVolcano) {
            // 豆包语音与方舟共用一个 Key：朗读、识别、通话都换上它。
            val ttsModel = u.ttsModel.ifBlank { VoiceSettings.DOUBAO_RESOURCE_ID }
            val asrModel = u.asrModel.ifBlank { VoiceSettings.DOUBAO_ASR_RESOURCE_ID }
            tts = tts.copy(
                vendor = VoiceVendor.DOUBAO,
                byVendor = tts.byVendor + (
                    VoiceVendor.DOUBAO to SettingsForm.VoiceForm(
                        baseUrl = VoiceSettings.DOUBAO_BASE_URL,
                        model = ttsModel,
                        apiKey = u.apiKey,
                        speed = tts.active.speed,
                        resourceId = ttsModel,
                    )
                    ),
            )
            asr = asr.copy(
                vendor = VoiceVendor.DOUBAO,
                byVendor = asr.byVendor + (
                    VoiceVendor.DOUBAO to SettingsForm.VoiceForm(
                        baseUrl = VoiceSettings.DOUBAO_ASR_BASE_URL,
                        model = asrModel,
                        apiKey = u.apiKey,
                        resourceId = asrModel,
                    )
                    ),
            )
            realtime = realtime.copy(apiKey = u.apiKey)
        }
        return copy(chat = chat, operator = operator, image = image, tts = tts, asr = asr, realtime = realtime)
    }

    /** Null while a field is invalid. */
    fun toSettings(): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        val embedding = memoryEmbedding.normalized()
        if (embedding.enabled && !embedding.configured) return null
        // 一体化开着的话，它覆盖的能力以它为准。
        val effective = applyUnified()
        val chatSettings = effective.chat.toSettings() ?: return null
        val operatorSettings = effective.operator.toSettings() ?: return null
        return AppSettings(
            chat = chatSettings,
            operator = operatorSettings,
            // 当前生效的那套也进档案：下次从别的厂商切回来时，Key 和模型都还在。
            chatVendors = effective.chatVendors + (chatSettings.vendor to chatSettings),
            operatorVendors = effective.operatorVendors + (operatorSettings.vendor to operatorSettings),
            asr = effective.asr.toSettings(),
            tts = effective.tts.toSettings(),
            realtime = effective.realtime.toSettings(),
            realtimeEnabled = realtimeEnabled,
            replyMode = replyMode,
            replyModeChosen = replyModeChosen,
            replyModeNotice = replyModeNotice,
            relation = relation,
            persona = persona,
            emojiStyle = emojiStyle,
            appearance = appearance,
            life = life,
            image = effective.image,
            personaVoiceNotice = personaVoiceNotice,
            maxSteps = steps,
            learnFromSuccess = learnFromSuccess,
            memoryEmbedding = embedding,
            provisioning = provisioning,
            unified = unified.toSettings(),
        )
    }

    companion object {
        fun of(s: AppSettings) = SettingsForm(
            chat = ModelForm.of(s.chat),
            operator = ModelForm.of(s.operator),
            chatVendors = s.chatVendors,
            operatorVendors = s.operatorVendors,
            asr = AsrForm.of(s.asr),
            tts = TtsForm.of(s.tts),
            provisioning = s.provisioning,
            unified = UnifiedForm.of(s.unified),
            realtime = VoiceForm.of(s.realtime),
            realtimeEnabled = s.realtimeEnabled,
            replyMode = s.replyMode,
            replyModeChosen = s.replyModeChosen,
            replyModeNotice = s.replyModeNotice,
            relation = s.relation,
            persona = s.persona,
            emojiStyle = s.emojiStyle,
            appearance = s.appearance,
            life = s.life,
            image = s.image,
            personaVoiceNotice = s.personaVoiceNotice,
            maxSteps = s.maxSteps.toString(),
            learnFromSuccess = s.learnFromSuccess,
            memoryEmbedding = s.memoryEmbedding,
        )

        fun parseTemperature(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    }
}
