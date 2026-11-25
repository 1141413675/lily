package dev.xiaoman.app.settings

import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.ApiProtocol
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
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
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

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

/** 朗读引擎。两套配置都留在本机，切换只改 provider，另一套的地址/Key/音色不会丢。 */
enum class TtsProvider { SILICONFLOW, DOUBAO }

/**
 * One audio endpoint as typed into Settings: [VoiceSettings] for speech to text
 * (`/audio/transcriptions`) and text to speech. [provider] 只对朗读有意义：
 * 硅基走 OpenAI 兼容的 `/audio/speech`；豆包走「豆包语音合成 2.0」的 WebSocket 双向流，
 * 鉴权用 [apiKey]（`X-Api-Key`）与 [resourceId]（`X-Api-Resource-Id`），
 * [voice] 是 2.0 音色 id（`*_uranus_bigtts`）。
 */
data class VoiceSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val voice: String = "",
    /**
     * 朗读语速；两套引擎都有对应参数。默认 1.1 比原速略快，更接近日常说话的节奏；
     * 设置页的滑杆直接改这个值，逐句合成时每句都用同一个语速。
     */
    val speed: Float = 1.1f,
    val provider: TtsProvider = TtsProvider.SILICONFLOW,
    /** 豆包才需要：模型版本，2.0 固定 `seed-tts-2.0`，放在 `X-Api-Resource-Id` 头里。 */
    val resourceId: String = "",
) {
    val configured: Boolean
        get() = when (provider) {
            TtsProvider.SILICONFLOW -> baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
            TtsProvider.DOUBAO -> baseUrl.isNotBlank() && apiKey.isNotBlank() &&
                resourceId.isNotBlank() && voice.isNotBlank()
        }

    override fun toString() =
        "VoiceSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, voice=$voice, speed=$speed, provider=$provider)"

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

        /** Alibaba DashScope: OpenAI-Realtime-compatible event protocol, reachable from mainland China. */
        const val DEFAULT_REALTIME_BASE_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        const val DEFAULT_REALTIME_MODEL = "qwen-omni-turbo-realtime"
        const val DEFAULT_REALTIME_VOICE = "Cherry"
    }
}

/**
 * 朗读设置：硅基与豆包 各留一份，[provider] 决定现在用哪套。切回去时音色、Key 都还在。
 * 语速对两套共用（设置页只有一个滑杆），因此 [withSpeed] 会同时写两边。
 */
data class TtsSettings(
    val provider: TtsProvider = TtsProvider.SILICONFLOW,
    val silicon: VoiceSettings = VoiceSettings(model = VoiceSettings.DEFAULT_TTS_MODEL),
    val doubao: VoiceSettings = VoiceSettings(
        baseUrl = VoiceSettings.DOUBAO_BASE_URL,
        apiKey = "",
        voice = dev.xiaoman.app.persona.Voices.DOUBAO_DEFAULT_FEMALE,
        provider = TtsProvider.DOUBAO,
        resourceId = VoiceSettings.DOUBAO_RESOURCE_ID,
    ),
) {
    /** 当前供应商的原始配置，设置页编辑的就是它。 */
    val active: VoiceSettings
        get() = when (provider) {
            TtsProvider.SILICONFLOW -> silicon
            TtsProvider.DOUBAO -> doubao.copy(provider = TtsProvider.DOUBAO)
        }

    val configured: Boolean get() = active.configured

    val speed: Float get() = active.speed

    fun withSpeed(value: Float): TtsSettings =
        copy(silicon = silicon.copy(speed = value), doubao = doubao.copy(speed = value))

    /** 多智能体按角色音色改配置时用：只改当前引擎那份，另一套原样留着。 */
    fun withVoice(voice: String, speedValue: Float = speed): TtsSettings = when (provider) {
        TtsProvider.SILICONFLOW -> copy(silicon = silicon.copy(voice = voice, speed = speedValue))
        TtsProvider.DOUBAO -> copy(doubao = doubao.copy(voice = voice, speed = speedValue))
    }

    /**
     * 发请求用的配置：人格里选的音色属于当前供应商就用它，否则退回该供应商自己的音色，
     * 再不行按性别取默认。切换引擎后旧音色 id 不会把新引擎打成 400。
     */
    fun request(personaVoice: String, gender: dev.xiaoman.app.persona.Gender): VoiceSettings {
        val picked = personaVoice.trim()
        return when (provider) {
            TtsProvider.SILICONFLOW -> {
                val voice = when {
                    picked.isEmpty() -> silicon.voice
                    dev.xiaoman.app.persona.Voices.isSilicon(picked) -> picked
                    else -> silicon.voice
                }
                silicon.copy(
                    voice = voice.ifBlank { dev.xiaoman.app.persona.Voices.defaultVoice(gender) },
                    provider = TtsProvider.SILICONFLOW,
                )
            }
            TtsProvider.DOUBAO -> {
                val voice = when {
                    picked.isEmpty() -> doubao.voice
                    dev.xiaoman.app.persona.Voices.isDoubao(picked) -> picked
                    else -> doubao.voice
                }
                doubao.copy(
                    voice = voice.ifBlank { dev.xiaoman.app.persona.Voices.doubaoDefault(gender) },
                    provider = TtsProvider.DOUBAO,
                )
            }
        }
    }
}

/**
 * How an assistant reply reaches the user: [AUTO] keeps the default (text, no reading), [TEXT] is
 * text only, [VOICE] posts a voice bubble without the text body, [CALL] opens the call screen and
 * reads the reply. Locked here, so the model is never asked to choose.
 */
enum class ReplyMode { AUTO, TEXT, VOICE, CALL }

/**
 * 她主动发图：开关、每天最多生成几张（不含复用与图库）、生成内容是否包含自拍。
 * 自拍用头像做参考图；没设头像时自动退回生活场景。
 */
data class ImageSettings(
    val enabled: Boolean = true,
    val dailyLimit: Int = 3,
    val includeSelfie: Boolean = true,
    /** 方舟图片模型，可在「发图」页改；留空时回退 [DEFAULT_MODEL]。 */
    val model: String = DEFAULT_MODEL,
) {
    val effectiveModel: String get() = model.trim().ifBlank { DEFAULT_MODEL }

    companion object {
        val DAILY_RANGE = 1..10

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
    val asr: VoiceSettings = VoiceSettings(model = VoiceSettings.DEFAULT_ASR_MODEL),
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
    /** 小满是谁：昵称、自称、性别、语气、说话长短、统一音色。 */
    val persona: dev.xiaoman.app.persona.PersonaSettings = dev.xiaoman.app.persona.PersonaSettings(),
    /** 表情尺度：克制 / 自然 / 丰富，只影响文字里的 emoji 与颜文字。 */
    val emojiStyle: dev.xiaoman.app.chat.EmojiStyle = dev.xiaoman.app.chat.EmojiStyle.NATURAL,
    /** 明暗模式与聊天背景。 */
    val appearance: dev.xiaoman.app.appearance.AppearanceSettings = dev.xiaoman.app.appearance.AppearanceSettings(),
    /** 她自己的生活设定：只用于她写的动态。 */
    val life: dev.xiaoman.app.life.LifeSettings = dev.xiaoman.app.life.LifeSettings(),
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
        get() = if (dev.xiaoman.app.persona.Voices.isDoubao(persona.voice.trim())) {
            dev.xiaoman.app.persona.Voices.defaultVoice(persona.gender)
        } else {
            dev.xiaoman.app.persona.Voices.effective(persona.voice, persona.gender)
        }

    /** 本次朗读要用的接口配置（含供应商、音色、Resource ID）。 */
    val ttsRequest: VoiceSettings get() = tts.request(persona.voice, persona.gender)

    /** 豆包出错时的兜底：硅基还配着就用它，保证通话不会整段没声音。 */
    val ttsFallback: VoiceSettings?
        get() = if (tts.provider == TtsProvider.DOUBAO) {
            tts.copy(provider = TtsProvider.SILICONFLOW)
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
            add(asr.apiKey)
            add(tts.silicon.apiKey)
            add(tts.doubao.apiKey)
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
    val asr: VoiceForm = VoiceForm(model = VoiceSettings.DEFAULT_ASR_MODEL),
    val tts: TtsForm = TtsForm(),
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
    val persona: dev.xiaoman.app.persona.PersonaSettings = dev.xiaoman.app.persona.PersonaSettings(),
    val emojiStyle: dev.xiaoman.app.chat.EmojiStyle = dev.xiaoman.app.chat.EmojiStyle.NATURAL,
    val appearance: dev.xiaoman.app.appearance.AppearanceSettings = dev.xiaoman.app.appearance.AppearanceSettings(),
    val life: dev.xiaoman.app.life.LifeSettings = dev.xiaoman.app.life.LifeSettings(),
    val image: ImageSettings = ImageSettings(),
    val personaVoiceNotice: Boolean = false,
    val maxSteps: String = AgentConfig().maxSteps.toString(),
    val learnFromSuccess: Boolean = true,
    val memoryEmbedding: MemoryEmbeddingSettings = MemoryEmbeddingSettings(),
) {
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
    ) {
        fun toSettings() = VoiceSettings(baseUrl.trim(), model.trim(), apiKey.trim(), voice.trim())

        override fun toString() =
            "VoiceForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, voice=$voice)"

        companion object {
            fun of(s: VoiceSettings) = VoiceForm(s.baseUrl, s.model, s.apiKey, s.voice)
        }
    }

    /** 朗读两套引擎的表单：只编辑当前选中的那套，另一套原样留着。 */
    data class TtsForm(
        val provider: TtsProvider = TtsProvider.SILICONFLOW,
        val silicon: VoiceForm = VoiceForm(model = VoiceSettings.DEFAULT_TTS_MODEL),
        val doubao: DoubaoForm = DoubaoForm(),
        val speed: Float = 1.1f,
    ) {
        fun toSettings() = TtsSettings(
            provider = provider,
            silicon = silicon.toSettings().copy(model = silicon.model.trim(), speed = speed),
            doubao = doubao.toSettings(speed),
        )

        override fun toString() = "TtsForm(provider=$provider, silicon=$silicon, doubao=$doubao, speed=$speed)"

        companion object {
            fun of(t: TtsSettings) = TtsForm(
                provider = t.provider,
                silicon = VoiceForm.of(t.silicon),
                doubao = DoubaoForm.of(t.doubao),
                speed = t.active.speed,
            )
        }
    }

    /** 豆包 2.0 的必填项：API Key、Resource ID、speaker（音色 id）。 */
    data class DoubaoForm(
        val baseUrl: String = VoiceSettings.DOUBAO_BASE_URL,
        val token: String = "",
        val resourceId: String = VoiceSettings.DOUBAO_RESOURCE_ID,
        val voice: String = dev.xiaoman.app.persona.Voices.DOUBAO_DEFAULT_FEMALE,
    ) {
        fun toSettings(speed: Float) = VoiceSettings(
            baseUrl = baseUrl.trim(),
            model = "",
            apiKey = token.trim(),
            voice = voice.trim(),
            speed = speed,
            provider = TtsProvider.DOUBAO,
            resourceId = resourceId.trim(),
        )

        override fun toString() =
            "DoubaoForm(baseUrl=$baseUrl, token=${if (token.isEmpty()) "<unset>" else "***"}, resourceId=$resourceId, voice=$voice)"

        companion object {
            fun of(s: VoiceSettings) = DoubaoForm(s.baseUrl, s.apiKey, s.resourceId, s.voice)
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

    /** Null while a field is invalid. */
    fun toSettings(): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        val embedding = memoryEmbedding.normalized()
        if (embedding.enabled && !embedding.configured) return null
        val chatSettings = chat.toSettings() ?: return null
        val operatorSettings = operator.toSettings() ?: return null
        return AppSettings(
            chat = chatSettings,
            operator = operatorSettings,
            // 当前生效的那套也进档案：下次从别的厂商切回来时，Key 和模型都还在。
            chatVendors = chatVendors + (chatSettings.vendor to chatSettings),
            operatorVendors = operatorVendors + (operatorSettings.vendor to operatorSettings),
            asr = asr.toSettings(),
            tts = tts.toSettings(),
            realtime = realtime.toSettings(),
            realtimeEnabled = realtimeEnabled,
            replyMode = replyMode,
            replyModeChosen = replyModeChosen,
            replyModeNotice = replyModeNotice,
            relation = relation,
            persona = persona,
            emojiStyle = emojiStyle,
            appearance = appearance,
            life = life,
            image = image,
            personaVoiceNotice = personaVoiceNotice,
            maxSteps = steps,
            learnFromSuccess = learnFromSuccess,
            memoryEmbedding = embedding,
        )
    }

    companion object {
        fun of(s: AppSettings) = SettingsForm(
            chat = ModelForm.of(s.chat),
            operator = ModelForm.of(s.operator),
            chatVendors = s.chatVendors,
            operatorVendors = s.operatorVendors,
            asr = VoiceForm.of(s.asr),
            tts = TtsForm.of(s.tts),
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
