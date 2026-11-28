package dev.lily.app.settings

/** 语音服务方的协议形态，决定请求怎么发、响应怎么读。 */
enum class VoiceProtocol {
    /** OpenAI 兼容：`POST {base}/audio/speech` 与 `POST {base}/audio/transcriptions`。 */
    OPENAI_COMPATIBLE,

    /** 豆包语音：WebSocket 双向流，鉴权用 `X-Api-Key` + `X-Api-Resource-Id`。 */
    DOUBAO,

    /** 豆包 3.0：HTTP 单向流式，音色 id 放 URL 路径；支持复刻与设计音色。 */
    DOUBAO_TTS3,

    /** MiniMax：JSON 请求，`data.audio` 是 **hex 编码**（不是 base64）。 */
    MINIMAX,

    /** ElevenLabs：`xi-api-key` 头，音色 id 放在 URL 路径里，响应是裸音频字节。 */
    ELEVENLABS,
}

/**
 * 语音服务方：朗读与识别能用哪些家。
 *
 * 一体化接法只管「方舟一家」；自定义接法下这里每一家都能单独挑——
 * 语言用 A 家、朗读用 B 家、识别用 C 家都行。
 *
 * 枚举名会写进设置，改名会让已存的配置读不出来，所以别改老名字。
 */
enum class VoiceVendor(
    val displayName: String,
    val protocol: VoiceProtocol,
    /** 朗读的默认地址。 */
    val ttsBaseUrl: String = "",
    /** 识别的默认地址；与朗读不同厂商时分开。 */
    val asrBaseUrl: String = "",
    /** 常用的朗读模型。 */
    val ttsModels: List<String> = emptyList(),
    /** 常用的识别模型。 */
    val asrModels: List<String> = emptyList(),
) {
    SILICONFLOW(
        displayName = "硅基流动",
        protocol = VoiceProtocol.OPENAI_COMPATIBLE,
        ttsBaseUrl = "https://api.siliconflow.cn/v1",
        asrBaseUrl = "https://api.siliconflow.cn/v1",
        ttsModels = listOf("FunAudioLLM/CosyVoice2-0.5B"),
        asrModels = listOf("FunAudioLLM/SenseVoiceSmall"),
    ),
    DOUBAO(
        displayName = "豆包 2.0",
        protocol = VoiceProtocol.DOUBAO,
        ttsBaseUrl = "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
        asrBaseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
        ttsModels = listOf("seed-tts-2.0"),
        asrModels = listOf("volc.seedasr.sauc.duration"),
    ),
    DOUBAO3(
        displayName = "豆包 3.0",
        protocol = VoiceProtocol.DOUBAO_TTS3,
        ttsBaseUrl = "https://voice.cn-beijing.volces.com",
        ttsModels = listOf("doubao-seed-tts-3.0"),
    ),
    OPENAI(
        displayName = "OpenAI",
        protocol = VoiceProtocol.OPENAI_COMPATIBLE,
        ttsBaseUrl = "https://api.openai.com/v1",
        asrBaseUrl = "https://api.openai.com/v1",
        ttsModels = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
        asrModels = listOf("whisper-1", "gpt-4o-transcribe"),
    ),
    GROQ(
        displayName = "Groq",
        protocol = VoiceProtocol.OPENAI_COMPATIBLE,
        ttsBaseUrl = "https://api.groq.com/openai/v1",
        asrBaseUrl = "https://api.groq.com/openai/v1",
        ttsModels = listOf("playai-tts", "eleven-labs_v3"),
        asrModels = listOf("whisper-large-v3", "whisper-large-v3-turbo"),
    ),
    MINIMAX(
        displayName = "MiniMax",
        protocol = VoiceProtocol.MINIMAX,
        ttsBaseUrl = "https://api.minimax.chat/v1/t2a_v2",
        ttsModels = listOf("speech-02-hd", "speech-02-turbo", "speech-2.6-hd", "speech-2.6-turbo"),
    ),
    ELEVENLABS(
        displayName = "ElevenLabs",
        protocol = VoiceProtocol.ELEVENLABS,
        ttsBaseUrl = "https://api.elevenlabs.io/v1",
        ttsModels = listOf("eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_v3"),
    ),
    ;

    /** 这一家管朗读吗。 */
    val supportsTts: Boolean get() = ttsModels.isNotEmpty() || protocol == VoiceProtocol.DOUBAO
    /** 这一家管识别吗。 */
    val supportsAsr: Boolean get() = asrModels.isNotEmpty() || protocol == VoiceProtocol.DOUBAO

    /** 这一家能不能做音色复刻与设计。只有豆包 3.0 行。 */
    val supportsVoiceCloning: Boolean get() = protocol == VoiceProtocol.DOUBAO_TTS3

    /** 朗读这一档的初始配置：地址与常用模型预填，Key 留空。 */
    fun ttsPreset() = VoiceSettings(
        baseUrl = ttsBaseUrl,
        model = ttsModels.firstOrNull().orEmpty(),
        apiKey = "",
        voice = defaultVoice,
        vendor = this,
    )

    /** 识别这一档的初始配置。 */
    fun asrPreset() = VoiceSettings(
        baseUrl = asrBaseUrl,
        model = asrModels.firstOrNull().orEmpty(),
        apiKey = "",
        vendor = this,
    )

    /** 这一家的默认音色。留空则由人设页按性别给。 */
    val defaultVoice: String
        get() = when (this) {
            OPENAI -> "alloy"
            GROQ -> "Arista-PlayAI"
            MINIMAX -> "Chinese (Mandarin)_Warm_Bestie"
            ELEVENLABS -> ""
            else -> ""
        }

    /** 设置页里显示的 Key 提示。 */
    val keyHint: String
        get() = when (this) {
            SILICONFLOW -> "硅基流动的 API Key（sk- 开头）"
            DOUBAO -> "豆包语音的 API Key（控制台「API Key 管理」里取）"
            DOUBAO3 -> "豆包语音的 API Key（3.0 的音色复刻与设计也要用它）"
            OPENAI -> "OpenAI 的 API Key（sk- 开头）"
            GROQ -> "Groq 的 API Key（gsk_ 开头）"
            MINIMAX -> "MiniMax 的 API Key；部分账号还要填 GroupId"
            ELEVENLABS -> "ElevenLabs 的 API Key"
        }

    companion object {
        /** 从设置里读回厂商名；认不出就当硅基（老配置的默认值）。 */
        fun from(name: String?): VoiceVendor = entries.firstOrNull { it.name == name } ?: SILICONFLOW
    }
}
