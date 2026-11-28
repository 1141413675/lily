package dev.lily.app.voice

import org.junit.Assert.assertEquals
import dev.lily.app.settings.VoiceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音色与通道的兼容判断：克隆 uri 只能交给 SiliconFlow，DashScope 用它自己的音色名。 */
class VoiceRoutingTest {

    @Test fun cloneUriOnlyGoesToSiliconFlow() {
        val clone = "speech:my-voice:abc:xyz"
        assertTrue(VoiceRouting.isClone(clone))
        assertTrue(VoiceRouting.realtimeSupports("https://api.siliconflow.cn/v1", clone))
        assertFalse(VoiceRouting.realtimeSupports("wss://dashscope.aliyuncs.com/api-ws/v1/realtime", clone))
    }

    @Test fun dashscopeRejectsProviderVoiceIdsAndKeepsItsOwn() {
        val dash = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        assertFalse(VoiceRouting.realtimeSupports(dash, "FunAudioLLM/CosyVoice2-0.5B:claire"))
        assertTrue(VoiceRouting.realtimeSupports(dash, "Cherry"))
        // 空值表示沿用通道默认，不算冲突。
        assertTrue(VoiceRouting.realtimeSupports(dash, ""))
    }

    @Test fun unknownChannelsAcceptProviderVoices() {
        assertTrue(VoiceRouting.realtimeSupports("https://example.com/v1", "FunAudioLLM/CosyVoice2-0.5B:alex"))
    }

    @Test fun cosyVoiceUsesNaturalLanguageEmotion() {
        val config = VoiceSettings(model = VoiceSettings.DEFAULT_TTS_MODEL)
        assertEquals(
            "请用开心的情绪。<|endofprompt|>今天放假。",
            expressiveSpeechInput(config, "今天放假。", "请用开心的情绪。"),
        )
        assertTrue(expressiveSpeechInput(config, "我在。").startsWith("语气自然"))
    }

    @Test fun otherSpeechModelsKeepPlainInput() {
        val config = VoiceSettings(model = "other-tts")
        assertEquals("晚安。", expressiveSpeechInput(config, "晚安。", "请温柔一些。"))
    }

    @Test fun songDirectiveIsParsedWithoutLeakingMarker() {
        assertEquals(
            SongReply("今晚的月亮很安静，\n把疲惫轻轻放下去。", "温柔"),
            parseSongReply("[[sing:温柔]]\n今晚的月亮很安静，\n把疲惫轻轻放下去。"),
        )
        assertNull(parseSongReply("普通回复不会变成歌曲。"))
        assertNull(parseSongReply("[[sing:温柔]]"))
    }
}
