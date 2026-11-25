package dev.xiaoman.app.settings

import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import kotlinx.serialization.Serializable
import java.net.URI

/** One OpenAI-compatible endpoint as typed into Settings. [toString] never shows [apiKey]. */
data class ModelSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelConfig(role: ModelRole) = ModelConfig(
        role = role,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        temperature = temperature,
    )

    override fun toString() =
        "ModelSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

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
 * One OpenAI-compatible audio endpoint as typed into Settings: [VoiceSettings] for speech to text
 * (`/audio/transcriptions`) and text to speech (`/audio/speech`, [voice] is the speaker name).
 */
data class VoiceSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val voice: String = "",
    /**
     * 朗读语速；只对 CosyVoice2 生效。默认 1.1 比原速略快，更接近日常说话的节奏；
     * 设置页的滑杆直接改这个值，逐句合成时每句都用同一个语速。
     */
    val speed: Float = 1.1f,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    override fun toString() =
        "VoiceSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, voice=$voice, speed=$speed)"

    companion object {
        /** SiliconFlow: both audio endpoints are OpenAI-compatible. */
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
        const val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B"
        /** 不在安装时写入具体音色：留空，实际音色由性别决定（见 Voices.effective）。 */
        const val DEFAULT_TTS_VOICE = ""

        /** Alibaba DashScope: OpenAI-Realtime-compatible event protocol, reachable from mainland China. */
        const val DEFAULT_REALTIME_BASE_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        const val DEFAULT_REALTIME_MODEL = "qwen-omni-turbo-realtime"
        const val DEFAULT_REALTIME_VOICE = "Cherry"
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
    val asr: VoiceSettings = VoiceSettings(model = VoiceSettings.DEFAULT_ASR_MODEL),
    val tts: VoiceSettings = VoiceSettings(model = VoiceSettings.DEFAULT_TTS_MODEL, voice = VoiceSettings.DEFAULT_TTS_VOICE),
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

    /** 实际用于朗读/语音消息的音色：人格设定里留空时按性别默认（女 claire / 男 alex）。 */
    val speechVoice: String
        get() = dev.xiaoman.app.persona.Voices.effective(persona.voice, persona.gender)

    /** Values to mask in traces and logs. */
    val secrets: List<String>
        get() = listOf(chat.apiKey, operator.apiKey, asr.apiKey, tts.apiKey, realtime.apiKey, memoryEmbedding.apiKey)
            .filter { it.isNotBlank() }

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
    val asr: VoiceForm = VoiceForm(model = VoiceSettings.DEFAULT_ASR_MODEL),
    val tts: VoiceForm = VoiceForm(model = VoiceSettings.DEFAULT_TTS_MODEL, voice = VoiceSettings.DEFAULT_TTS_VOICE),
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
    ) {
        val temperatureValid: Boolean get() = parseTemperature(temperature) != null || temperature.isBlank()

        fun toSettings(): ModelSettings? {
            val t = if (temperature.isBlank()) null else parseTemperature(temperature) ?: return null
            return ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), t)
        }

        override fun toString() =
            "ModelForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

        companion object {
            fun of(s: ModelSettings) = ModelForm(s.baseUrl, s.model, s.apiKey, s.temperature?.toString().orEmpty())
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

    val maxStepsValid: Boolean get() = maxSteps.trim().toIntOrNull()?.let { it in AppSettings.MAX_STEPS_RANGE } == true

    /** Null while a field is invalid. */
    fun toSettings(): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        val embedding = memoryEmbedding.normalized()
        if (embedding.enabled && !embedding.configured) return null
        return AppSettings(
            chat = chat.toSettings() ?: return null,
            operator = operator.toSettings() ?: return null,
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
            asr = VoiceForm.of(s.asr),
            tts = VoiceForm.of(s.tts),
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
