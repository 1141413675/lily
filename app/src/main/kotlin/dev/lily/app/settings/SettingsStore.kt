package dev.lily.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.lily.api.ApiProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppSettings] in [plain] and API keys in [secret] (EncryptedSharedPreferences in the app).
 * The embedding provider and its key are saved together in the encrypted preferences.
 */
class SettingsStore(private val plain: SharedPreferences, internal val secret: SharedPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun save(settings: AppSettings): Boolean {
        val previous = current
        // 音色归每个 Agent 的人设，不在设置页统一；这里原样保存，不再改写 persona.voice，
        // 也不会因为改 Key / 切引擎覆盖任何角色已经选过的音色。
        val stored = settings.copy(
            memoryEmbedding = settings.memoryEmbedding.normalized(),
            // 当前生效的那套始终进档案，其他厂商的配置一条都不删。
            chatVendors = settings.chatVendors + (settings.chat.vendor to settings.chat),
            operatorVendors = settings.operatorVendors + (settings.operator.vendor to settings.operator),
        )
        plain.edit(commit = true) {
            putModel(CHAT, stored.chat)
            putModel(OPERATOR, stored.operator)
            putVendors("chat", stored.chatVendors)
            putVendors("operator", stored.operatorVendors)
            putAsr(stored.asr)
            putTts(stored.tts)
            putVoice(REALTIME, stored.realtime)
            putBoolean(REALTIME_ENABLED, stored.realtimeEnabled)
            putString(REPLY_MODE, stored.replyMode.name)
            putBoolean(REPLY_MODE_CHOSEN, stored.replyModeChosen)
            putBoolean(REPLY_MODE_NOTICE, stored.replyModeNotice)
            putRelation(stored.relation)
            putPersona(stored.persona)
            putLife(stored.life)
            putBoolean(IMAGE_ENABLED, stored.image.enabled)
            putBoolean(IMAGE_SELFIE, stored.image.includeSelfie)
            putString(IMAGE_MODEL, stored.image.effectiveModel)
            putString(EMOJI_STYLE, stored.emojiStyle.name)
            putString(APPEARANCE_MODE, stored.appearance.mode.name)
            putString(APPEARANCE_IMAGE, stored.appearance.imagePath)
            putInt(APPEARANCE_DIM, stored.appearance.dim)
            putInt(APPEARANCE_BLUR, stored.appearance.blur)
            putString(APPEARANCE_BACKDROP, stored.appearance.backdrop.name)
            putBoolean(PERSONA_VOICE_NOTICE, stored.personaVoiceNotice)
            putInt(MAX_STEPS, stored.maxSteps)
            putBoolean(LEARN, stored.learnFromSuccess)
            putString(ARK_CHAT_MODEL, stored.ark.chatModel)
            putString(ARK_OPERATOR_MODEL, stored.ark.operatorModel)
            putString(ARK_IMAGE_MODEL, stored.ark.imageModel)
            putBoolean(ARK_USE_CHAT, stored.ark.useChat)
            putBoolean(ARK_USE_OPERATOR, stored.ark.useOperator)
            putBoolean(ARK_USE_IMAGE, stored.ark.useImage)
            putBoolean(ARK_USE_VOICE, stored.ark.useVoice)
            putString(ARK_ACCESS_KEY_ID, stored.ark.accessKeyId)
        }
        val saved = secret.edit().apply {
            putString(CHAT + API_KEY, stored.chat.apiKey)
            putString(OPERATOR + API_KEY, stored.operator.apiKey)
            putVendorKeys("chat", stored.chatVendors)
            putVendorKeys("operator", stored.operatorVendors)
            putString(ASR + API_KEY, stored.asr.silicon.apiKey)
            putString(ASR_DOUBAO + API_KEY, stored.asr.doubao.apiKey)
            putString(TTS + API_KEY, stored.tts.silicon.apiKey)
            putString(TTS_DOUBAO + API_KEY, stored.tts.doubao.apiKey)
            putString(REALTIME + API_KEY, stored.realtime.apiKey)
            putBoolean(MEMORY_EMBEDDING_ENABLED, stored.memoryEmbedding.enabled)
            putString(MEMORY_EMBEDDING + BASE_URL, stored.memoryEmbedding.baseUrl)
            putString(MEMORY_EMBEDDING + MODEL, stored.memoryEmbedding.model)
            putString(MEMORY_EMBEDDING + API_KEY, stored.memoryEmbedding.apiKey)
            putString(ARK + API_KEY, stored.ark.apiKey)
            putString(ARK + ARK_SECRET_ACCESS_KEY, stored.ark.secretAccessKey)
        }.commit()
        // A failed opt-in must never activate network requests in the running process.
        _settings.value = if (!saved && previous.memoryEmbedding != stored.memoryEmbedding) {
            stored.copy(memoryEmbedding = previous.memoryEmbedding.copy(enabled = false))
        } else {
            stored
        }
        return saved
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val persona = persona()
        // 音色统一：朗读与实时共用 persona.voice。首次升级时取朗读那处，若两处不同则提示。
        val storedTts = plain.getString(TTS + VOICE, null)
        val storedRealtime = plain.getString(REALTIME + VOICE, null)
        val voiceChosenStored = plain.getBoolean(PERSONA_VOICE_CHOSEN, false)
        val hasChosenFlag = plain.contains(PERSONA_VOICE_CHOSEN)
        // 旧版本把 tts 的默认值 alex 写进 persona.voice，女角色因此用了男声。
        // 这种「默认值被当成选择」的旧数据统一清回未选择；主动选过（voiceChosen）就保留。
        val legacySeeded = !voiceChosenStored &&
            persona.voice.ifBlank { storedTts.orEmpty() } == dev.lily.app.persona.Voices.LEGACY_DEFAULT
        val unified = if (legacySeeded) "" else persona.voice.ifBlank {
            // 新版本写入了「未选择」标记后，不再把 tts 里的旧值当成用户选择。
            if (hasChosenFlag) "" else storedTts.orEmpty()
        }
        val voiceChosen = !legacySeeded && (voiceChosenStored || (persona.voice.isBlank() && unified.isNotBlank()))
        val notice = persona.voice.isBlank() && storedTts != null && storedTts != storedRealtime.orEmpty()
        // 老版本只有「文字 / 语音」两个强制档；这些值不算主动选择，统一改回「看情况」并提示一次。
        val storedReply = plain.getString(REPLY_MODE, null)
            ?.let { name -> ReplyMode.entries.firstOrNull { it.name == name } }
        val replyChosenStored = plain.getBoolean(REPLY_MODE_CHOSEN, false)
        val replyLegacy = !replyChosenStored &&
            (storedReply == ReplyMode.TEXT || storedReply == ReplyMode.VOICE || storedReply == ReplyMode.CALL)
        val replyMode = if (replyLegacy) ReplyMode.AUTO else storedReply ?: defaults.replyMode
        val rawRealtime = voice(REALTIME, defaults.realtime)
        val realtimeVoice = when {
            unified.isBlank() -> rawRealtime.voice
            dev.lily.app.voice.VoiceRouting.realtimeSupports(rawRealtime.baseUrl, unified) -> unified
            else -> rawRealtime.voice
        }
        // 旧版本曾把供应商音色写进 DashScope 实时通道；读到时退回该通道的默认音色。
        val safeRealtime = realtimeVoice.takeIf {
            dev.lily.app.voice.VoiceRouting.realtimeSupports(rawRealtime.baseUrl, it)
        } ?: defaults.realtime.voice
        val chat = model(CHAT)
        val operator = model(OPERATOR)
        return AppSettings(
            chat = chat,
            operator = operator,
            // 档案里已有的其他厂商配置原样读回；当前这套以 chat / operator 为准。
            chatVendors = vendorProfiles("chat") + (chat.vendor to chat),
            operatorVendors = vendorProfiles("operator") + (operator.vendor to operator),
            asr = AsrSettings(
                provider = plain.getString(ASR_PROVIDER, null)
                    ?.let { name -> AsrProvider.entries.firstOrNull { it.name == name } }
                    ?: defaults.asr.provider,
                // 旧安装的 asr.* 就是硅基配置，升级后原样读回。
                silicon = voice(ASR, defaults.asr.silicon),
                doubao = voice(ASR_DOUBAO, defaults.asr.doubao).copy(
                    // 地址固定，不接受旧值或手填地址。
                    baseUrl = VoiceSettings.DOUBAO_ASR_BASE_URL,
                    provider = TtsProvider.DOUBAO,
                    resourceId = plain.getString(ASR_DOUBAO_RESOURCE_ID, null)
                        ?: defaults.asr.doubao.resourceId,
                ),
            ),
            tts = TtsSettings(
                provider = plain.getString(TTS_PROVIDER, null)
                    ?.let { name -> TtsProvider.entries.firstOrNull { it.name == name } }
                    ?: defaults.tts.provider,
                silicon = voice(TTS, defaults.tts.silicon),
                doubao = voice(TTS_DOUBAO, defaults.tts.doubao).copy(
                    provider = TtsProvider.DOUBAO,
                    resourceId = plain.getString(TTS_DOUBAO_RESOURCE_ID, null) ?: defaults.tts.doubao.resourceId,
                ),
            ),
            realtime = rawRealtime.copy(voice = safeRealtime),
            realtimeEnabled = plain.getBoolean(REALTIME_ENABLED, defaults.realtimeEnabled),
            memoryEmbedding = MemoryEmbeddingSettings(
                enabled = secret.getBoolean(MEMORY_EMBEDDING_ENABLED, false),
                baseUrl = secret.getString(MEMORY_EMBEDDING + BASE_URL, null).orEmpty(),
                model = secret.getString(MEMORY_EMBEDDING + MODEL, null).orEmpty(),
                apiKey = secret.getString(MEMORY_EMBEDDING + API_KEY, null).orEmpty(),
            ),
            ark = ArkSettings(
                apiKey = secret.getString(ARK + API_KEY, null).orEmpty(),
                chatModel = plain.getString(ARK_CHAT_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaults.ark.chatModel,
                operatorModel = plain.getString(ARK_OPERATOR_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaults.ark.operatorModel,
                imageModel = plain.getString(ARK_IMAGE_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaults.ark.imageModel,
                useChat = plain.getBoolean(ARK_USE_CHAT, defaults.ark.useChat),
                useOperator = plain.getBoolean(ARK_USE_OPERATOR, defaults.ark.useOperator),
                useImage = plain.getBoolean(ARK_USE_IMAGE, defaults.ark.useImage),
                useVoice = plain.getBoolean(ARK_USE_VOICE, defaults.ark.useVoice),
                accessKeyId = plain.getString(ARK_ACCESS_KEY_ID, null)?.trim().orEmpty(),
                secretAccessKey = secret.getString(ARK + ARK_SECRET_ACCESS_KEY, null).orEmpty(),
            ),
            replyMode = replyMode,
            replyModeChosen = replyChosenStored,
            replyModeNotice = plain.getBoolean(REPLY_MODE_NOTICE, false) || replyLegacy,
            relation = relation(defaults.relation),
            persona = persona.copy(voice = unified, voiceChosen = voiceChosen),
            life = life(),
            image = ImageSettings(
                enabled = plain.getBoolean(IMAGE_ENABLED, defaults.image.enabled),
                includeSelfie = plain.getBoolean(IMAGE_SELFIE, defaults.image.includeSelfie),
                model = plain.getString(IMAGE_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaults.image.model,
            ),
            emojiStyle = plain.getString(EMOJI_STYLE, null)
                ?.let { name -> dev.lily.app.chat.EmojiStyle.entries.firstOrNull { it.name == name } }
                ?: defaults.emojiStyle,
            appearance = dev.lily.app.appearance.AppearanceSettings(
                mode = plain.getString(APPEARANCE_MODE, null)
                    ?.let { name -> dev.lily.app.appearance.ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: defaults.appearance.mode,
                imagePath = plain.getString(APPEARANCE_IMAGE, null).orEmpty(),
                dim = plain.getInt(APPEARANCE_DIM, defaults.appearance.dim),
                blur = plain.getInt(APPEARANCE_BLUR, defaults.appearance.blur),
                backdrop = plain.getString(APPEARANCE_BACKDROP, null)
                    ?.let { name -> dev.lily.app.appearance.ChatBackdrop.entries.firstOrNull { it.name == name } }
                    ?: defaults.appearance.backdrop,
            ),
            personaVoiceNotice = plain.getBoolean(PERSONA_VOICE_NOTICE, false) || notice,
            maxSteps = plain.getInt(MAX_STEPS, defaults.maxSteps),
            learnFromSuccess = plain.getBoolean(LEARN, defaults.learnFromSuccess),
        )
    }

    private fun persona(): dev.lily.app.persona.PersonaSettings {
        val base = dev.lily.app.persona.PersonaSettings(
            // 空昵称保留为空，展示与提示词统一回退到当前应用名，不再写死「Lily」。
            nickname = plain.getString(PERSONA_NICKNAME, null)?.trim().orEmpty(),
            selfRef = plain.getString(PERSONA_SELF_REF, null)
                ?.let { name -> dev.lily.app.persona.SelfRef.entries.firstOrNull { it.name == name } }
                ?: dev.lily.app.persona.SelfRef.ME,
            customSelf = plain.getString(PERSONA_SELF_CUSTOM, null).orEmpty(),
            gender = plain.getString(PERSONA_GENDER, null)
                ?.let { name -> dev.lily.app.persona.Gender.entries.firstOrNull { it.name == name } }
                ?: dev.lily.app.persona.Gender.FEMALE,
            tone = plain.getString(PERSONA_TONE, null)
                ?.let { name -> dev.lily.app.persona.Tone.entries.firstOrNull { it.name == name } }
                ?: dev.lily.app.persona.Tone.GENTLE,
            length = plain.getString(PERSONA_LENGTH, null)
                ?.let { name -> dev.lily.app.persona.Length.entries.firstOrNull { it.name == name } }
                ?: dev.lily.app.persona.Length.SHORT,
            expression = plain.getString(PERSONA_EXPRESSION, null)
                ?.let { name -> dev.lily.app.persona.Expression.entries.firstOrNull { it.name == name } }
                ?: dev.lily.app.persona.Expression.CLOSE,
            voice = plain.getString(PERSONA_VOICE, null).orEmpty(),
            voiceChosen = plain.getBoolean(PERSONA_VOICE_CHOSEN, false),
            birthdayYear = plain.getInt(PERSONA_BIRTH_YEAR, 0).takeIf { it > 0 },
            birthdayMonth = plain.getInt(PERSONA_BIRTH_MONTH, 0).takeIf { it in 1..12 },
            fixedAge = plain.getInt(PERSONA_AGE, 0).takeIf { it > 0 },
            birthdayDay = plain.getInt(PERSONA_BIRTH_DAY, 0).takeIf { it in 1..31 },
            hometown = plain.getString(PERSONA_HOMETOWN, null).orEmpty(),
            education = plain.getString(PERSONA_EDUCATION, null).orEmpty(),
            relationshipStatus = plain.getString(PERSONA_RELATIONSHIP, null).orEmpty(),
            appearance = plain.getString(PERSONA_APPEARANCE, null).orEmpty(),
            personality = plain.getString(PERSONA_PERSONALITY, null).orEmpty(),
            speechStyle = plain.getString(PERSONA_SPEECH_STYLE, null).orEmpty(),
            lifestyle = plain.getString(PERSONA_LIFESTYLE, null).orEmpty(),
            background = plain.getString(PERSONA_BACKGROUND, null).orEmpty(),
        )
        // 首次升级：把旧的自称/语气/长度/距离感合成一段人设文字，用户不会丢任何设定。
        // 用户主动清空过（键存在且为空）就尊重空值，不再自动回填。
        val migrated = if (plain.contains(PERSONA_BIO)) {
            plain.getString(PERSONA_BIO, null).orEmpty()
        } else {
            base.legacyBio()
        }
        return base.copy(bio = migrated)
    }

    private fun SharedPreferences.Editor.putPersona(p: dev.lily.app.persona.PersonaSettings) {
        putString(PERSONA_NICKNAME, p.nickname)
        putString(PERSONA_SELF_REF, p.selfRef.name)
        putString(PERSONA_SELF_CUSTOM, p.customSelf)
        putString(PERSONA_GENDER, p.gender.name)
        putString(PERSONA_TONE, p.tone.name)
        putString(PERSONA_LENGTH, p.length.name)
        putString(PERSONA_EXPRESSION, p.expression.name)
        putString(PERSONA_VOICE, p.voice)
        putBoolean(PERSONA_VOICE_CHOSEN, p.voiceChosen)
        putString(PERSONA_BIO, p.bio)
        putInt(PERSONA_BIRTH_YEAR, p.birthdayYear ?: 0)
        putInt(PERSONA_BIRTH_MONTH, p.birthdayMonth ?: 0)
        putInt(PERSONA_AGE, p.fixedAge ?: 0)
        putInt(PERSONA_BIRTH_DAY, p.birthdayDay ?: 0)
        putString(PERSONA_HOMETOWN, p.hometown)
        putString(PERSONA_EDUCATION, p.education)
        putString(PERSONA_RELATIONSHIP, p.relationshipStatus)
        putString(PERSONA_APPEARANCE, p.appearance)
        putString(PERSONA_PERSONALITY, p.personality)
        putString(PERSONA_SPEECH_STYLE, p.speechStyle)
        putString(PERSONA_LIFESTYLE, p.lifestyle)
        putString(PERSONA_BACKGROUND, p.background)
    }

    private fun life() = dev.lily.app.life.LifeSettings(
        city = plain.getString(LIFE_CITY, null).orEmpty(),
        doing = plain.getString(LIFE_DOING, null).orEmpty(),
        routine = plain.getString(LIFE_ROUTINE, null).orEmpty(),
        pet = plain.getString(LIFE_PET, null).orEmpty(),
        media = plain.getString(LIFE_MEDIA, null).orEmpty(),
        bio = plain.getString(LIFE_BIO, null).orEmpty(),
    )

    private fun SharedPreferences.Editor.putLife(l: dev.lily.app.life.LifeSettings) {
        putString(LIFE_CITY, l.city)
        putString(LIFE_DOING, l.doing)
        putString(LIFE_ROUTINE, l.routine)
        putString(LIFE_PET, l.pet)
        putString(LIFE_MEDIA, l.media)
        putString(LIFE_BIO, l.bio)
    }

    /** 用户看过并确认了音色迁移提示。 */
    @Synchronized
    fun clearVoiceNotice() {
        plain.edit(commit = true) { putBoolean(PERSONA_VOICE_NOTICE, false) }
        _settings.value = _settings.value.copy(personaVoiceNotice = false)
    }

    private fun relation(defaults: RelationSettings) = RelationSettings(
        proactive = plain.getBoolean(PROACTIVE, defaults.proactive),
        maxPerDay = plain.getInt(PROACTIVE_MAX, defaults.maxPerDay),
        minGapHours = plain.getInt(PROACTIVE_GAP, defaults.minGapHours),
        quietStart = plain.getString(PROACTIVE_QUIET_START, null) ?: defaults.quietStart,
        quietEnd = plain.getString(PROACTIVE_QUIET_END, null) ?: defaults.quietEnd,
        habitMissDays = plain.getInt(PROACTIVE_HABIT_DAYS, defaults.habitMissDays),
        absenceHours = plain.getInt(PROACTIVE_ABSENCE, defaults.absenceHours),
        neutralTone = plain.getBoolean(PROACTIVE_NEUTRAL, defaults.neutralTone),
    )

    private fun SharedPreferences.Editor.putRelation(r: RelationSettings) {
        putBoolean(PROACTIVE, r.proactive)
        putInt(PROACTIVE_MAX, r.maxPerDay)
        putInt(PROACTIVE_GAP, r.minGapHours)
        putString(PROACTIVE_QUIET_START, r.quietStart)
        putString(PROACTIVE_QUIET_END, r.quietEnd)
        putInt(PROACTIVE_HABIT_DAYS, r.habitMissDays)
        putInt(PROACTIVE_ABSENCE, r.absenceHours)
        putBoolean(PROACTIVE_NEUTRAL, r.neutralTone)
    }

    private fun voice(prefix: String, defaults: VoiceSettings) = VoiceSettings(
        baseUrl = plain.getString(prefix + BASE_URL, null) ?: defaults.baseUrl,
        model = plain.getString(prefix + MODEL, null) ?: defaults.model,
        apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
        voice = plain.getString(prefix + VOICE, null) ?: defaults.voice,
        speed = plain.getFloat(prefix + SPEED, defaults.speed),
    )

    private fun SharedPreferences.Editor.putVoice(prefix: String, v: VoiceSettings) {
        putString(prefix + BASE_URL, v.baseUrl)
        putString(prefix + MODEL, v.model)
        putString(prefix + VOICE, v.voice)
        putFloat(prefix + SPEED, v.speed)
    }

    /** 语音识别两套分开存；硅基继续用旧的 `asr.*` 键，旧安装升级后配置不丢。 */
    private fun SharedPreferences.Editor.putAsr(a: AsrSettings) {
        putString(ASR_PROVIDER, a.provider.name)
        putVoice(ASR, a.silicon)
        putVoice(ASR_DOUBAO, a.doubao)
        putString(ASR_DOUBAO_RESOURCE_ID, a.doubao.resourceId)
    }

    /** 两套朗读配置分开存；硅基继续用旧的 `tts.*` 键，旧安装升级后配置不丢。 */
    private fun SharedPreferences.Editor.putTts(t: TtsSettings) {
        putString(TTS_PROVIDER, t.provider.name)
        putVoice(TTS, t.silicon)
        putVoice(TTS_DOUBAO, t.doubao)
        putString(TTS_DOUBAO_RESOURCE_ID, t.doubao.resourceId.trim())
    }

    private fun model(prefix: String): ModelSettings {
        val baseUrl = plain.getString(prefix + BASE_URL, null) ?: ModelSettings.DEFAULT_BASE_URL
        // 旧版本没存厂商：按地址认回预设；认不出就当自定义，别把用户的地址塞进方舟预设。
        val vendor = plain.getString(prefix + VENDOR, null)
            ?.let { name -> ModelVendor.entries.firstOrNull { it.name == name } }
            ?: inferVendor(baseUrl)
        val protocol = plain.getString(prefix + PROTOCOL, null)
            ?.let { name -> ApiProtocol.entries.firstOrNull { it.name == name } }
            ?: vendor.protocol
        return ModelSettings(
            baseUrl = baseUrl,
            model = plain.getString(prefix + MODEL, null).orEmpty(),
            apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
            temperature = plain.getString(prefix + TEMPERATURE, null)?.toDoubleOrNull(),
            vendor = vendor,
            protocol = protocol,
        )
    }

    private fun inferVendor(baseUrl: String): ModelVendor {
        val url = baseUrl.trimEnd('/')
        return ModelVendor.entries.firstOrNull {
            it != ModelVendor.CUSTOM && it.baseUrl.trimEnd('/').equals(url, ignoreCase = true)
        } ?: ModelVendor.CUSTOM
    }

    private fun SharedPreferences.Editor.putModel(prefix: String, m: ModelSettings) {
        putString(prefix + BASE_URL, m.baseUrl)
        putString(prefix + MODEL, m.model)
        putString(prefix + TEMPERATURE, m.temperature?.toString())
        putString(prefix + VENDOR, m.vendor.name)
        putString(prefix + PROTOCOL, m.protocol.name)
    }

    /** 每个厂商一份聊天/操作配置；只写不删，切厂商不会抹掉其他厂商。 */
    private fun SharedPreferences.Editor.putVendors(role: String, profiles: Map<ModelVendor, ModelSettings>) {
        profiles.forEach { (vendor, m) ->
            val prefix = vendorPrefix(role, vendor)
            putString(prefix + BASE_URL, m.baseUrl)
            putString(prefix + MODEL, m.model)
            putString(prefix + TEMPERATURE, m.temperature?.toString())
            putString(prefix + PROTOCOL, m.protocol.name)
        }
    }

    private fun SharedPreferences.Editor.putVendorKeys(role: String, profiles: Map<ModelVendor, ModelSettings>) {
        profiles.forEach { (vendor, m) -> putString(vendorPrefix(role, vendor) + API_KEY, m.apiKey) }
    }

    private fun vendorProfiles(role: String): Map<ModelVendor, ModelSettings> {
        val result = LinkedHashMap<ModelVendor, ModelSettings>()
        for (vendor in ModelVendor.entries) {
            val prefix = vendorPrefix(role, vendor)
            val present = plain.contains(prefix + BASE_URL) || plain.contains(prefix + MODEL) ||
                plain.contains(prefix + TEMPERATURE) || secret.contains(prefix + API_KEY)
            if (!present) continue
            val protocol = plain.getString(prefix + PROTOCOL, null)
                ?.let { name -> ApiProtocol.entries.firstOrNull { it.name == name } }
                ?: vendor.protocol
            result[vendor] = ModelSettings(
                baseUrl = plain.getString(prefix + BASE_URL, null) ?: vendor.baseUrl,
                model = plain.getString(prefix + MODEL, null).orEmpty(),
                apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
                temperature = plain.getString(prefix + TEMPERATURE, null)?.toDoubleOrNull(),
                vendor = vendor,
                protocol = protocol,
            )
        }
        return result
    }

    private fun vendorPrefix(role: String, vendor: ModelVendor) = "vendor.$role.${vendor.name}."

    companion object {
        private const val TAG = "Lily"
        private const val PLAIN_FILE = "settings"
        private const val SECRET_FILE = "settings-secrets"
        private const val CHAT = "chat."
        private const val OPERATOR = "operator."
        private const val ASR = "asr."
        private const val ASR_PROVIDER = "asr.provider"
        private const val ASR_DOUBAO = "asr.doubao."
        private const val ASR_DOUBAO_RESOURCE_ID = "asr.doubao.resourceId"
        private const val TTS = "tts."
        private const val TTS_DOUBAO = "tts.doubao."
        private const val TTS_PROVIDER = "tts.provider"
        private const val TTS_DOUBAO_RESOURCE_ID = "tts.doubao.resourceId"
        private const val REALTIME = "realtime."
        private const val REALTIME_ENABLED = "realtime.enabled"
        private const val MEMORY_EMBEDDING = "memoryEmbedding."
        private const val MEMORY_EMBEDDING_ENABLED = "memoryEmbedding.enabled"
        private const val ARK = "ark."
        private const val ARK_CHAT_MODEL = "ark.chatModel"
        private const val ARK_OPERATOR_MODEL = "ark.operatorModel"
        private const val ARK_IMAGE_MODEL = "ark.imageModel"
        private const val ARK_USE_CHAT = "ark.useChat"
        private const val ARK_USE_OPERATOR = "ark.useOperator"
        private const val ARK_USE_IMAGE = "ark.useImage"
        private const val ARK_USE_VOICE = "ark.useVoice"
        private const val ARK_ACCESS_KEY_ID = "ark.accessKeyId"
        private const val ARK_SECRET_ACCESS_KEY = "secretAccessKey"
        private const val BASE_URL = "baseUrl"
        private const val MODEL = "model"
        private const val API_KEY = "apiKey"
        private const val TEMPERATURE = "temperature"
        private const val VENDOR = "vendor"
        private const val PROTOCOL = "protocol"
        private const val VOICE = "voice"
        private const val SPEED = "speed"
        private const val IMAGE_MODEL = "image.model"
        private const val REPLY_MODE = "reply.mode"
        private const val REPLY_MODE_CHOSEN = "reply.modeChosen"
        private const val REPLY_MODE_NOTICE = "reply.modeNotice"
        private const val EMOJI_STYLE = "reply.emoji"
        private const val APPEARANCE_MODE = "appearance.mode"
        private const val APPEARANCE_IMAGE = "appearance.image"
        private const val APPEARANCE_DIM = "appearance.dim"
        private const val APPEARANCE_BLUR = "appearance.blur"
        private const val APPEARANCE_BACKDROP = "appearance.backdrop"
        private const val PERSONA_NICKNAME = "persona.nickname"
        private const val PERSONA_SELF_REF = "persona.selfRef"
        private const val PERSONA_SELF_CUSTOM = "persona.selfCustom"
        private const val PERSONA_GENDER = "persona.gender"
        private const val PERSONA_TONE = "persona.tone"
        private const val PERSONA_LENGTH = "persona.length"
        private const val PERSONA_EXPRESSION = "persona.expression"
        private const val PERSONA_VOICE = "persona.voice"
        private const val PERSONA_VOICE_CHOSEN = "persona.voiceChosen"
        private const val PERSONA_BIO = "persona.bio"
        private const val PERSONA_BIRTH_YEAR = "persona.birthdayYear"
        private const val PERSONA_BIRTH_MONTH = "persona.birthdayMonth"
        private const val PERSONA_AGE = "persona.age"
        private const val PERSONA_BIRTH_DAY = "persona.birthdayDay"
        private const val PERSONA_HOMETOWN = "persona.hometown"
        private const val PERSONA_EDUCATION = "persona.education"
        private const val PERSONA_RELATIONSHIP = "persona.relationshipStatus"
        private const val PERSONA_APPEARANCE = "persona.appearance"
        private const val PERSONA_PERSONALITY = "persona.personality"
        private const val PERSONA_SPEECH_STYLE = "persona.speechStyle"
        private const val PERSONA_LIFESTYLE = "persona.lifestyle"
        private const val PERSONA_BACKGROUND = "persona.background"
        private const val PERSONA_VOICE_NOTICE = "persona.voiceNotice"
        private const val LIFE_CITY = "life.city"
        private const val LIFE_DOING = "life.doing"
        private const val LIFE_ROUTINE = "life.routine"
        private const val LIFE_PET = "life.pet"
        private const val LIFE_MEDIA = "life.media"
        private const val LIFE_BIO = "life.bio"
        private const val IMAGE_ENABLED = "image.enabled"
        private const val IMAGE_SELFIE = "image.includeSelfie"
        private const val PROACTIVE = "relation.proactive"
        private const val PROACTIVE_MAX = "relation.maxPerDay"
        private const val PROACTIVE_GAP = "relation.minGapHours"
        private const val PROACTIVE_QUIET_START = "relation.quietStart"
        private const val PROACTIVE_QUIET_END = "relation.quietEnd"
        private const val PROACTIVE_HABIT_DAYS = "relation.habitMissDays"
        private const val PROACTIVE_ABSENCE = "relation.absenceHours"
        private const val PROACTIVE_NEUTRAL = "relation.neutralTone"
        private const val MAX_STEPS = "agent.maxSteps"
        private const val LEARN = "agent.learnFromSuccess"

        fun create(context: Context): SettingsStore =
            SettingsStore(context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE), encrypted(context))

        private fun encrypted(context: Context): SharedPreferences = try {
            openEncrypted(context)
        } catch (e: Exception) {
            // A keystore entry lost after a restore or OS update makes the file undecryptable.
            // The keys cannot be recovered; start over so the user can type them again.
            Log.w(TAG, "settings: encrypted preferences unreadable (${e.javaClass.simpleName}); resetting API keys")
            context.deleteSharedPreferences(SECRET_FILE)
            openEncrypted(context)
        }

        private fun openEncrypted(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                SECRET_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
