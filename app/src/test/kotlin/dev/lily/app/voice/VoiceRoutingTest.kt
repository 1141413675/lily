package dev.lily.app.voice

import org.junit.Assert.assertEquals
import dev.lily.app.settings.VoiceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音色与通道的兼容判断：克隆 uri 只能交给 SiliconFlow，豆包 3.0 只认官方 S2S 音色 id。 */
class VoiceRoutingTest {

    @Test fun cloneUriOnlyGoesToSiliconFlow() {
        val clone = "speech:my-voice:abc:xyz"
        assertTrue(VoiceRouting.isClone(clone))
        assertTrue(VoiceRouting.realtimeSupports("https://api.siliconflow.cn/v1", clone))
        assertFalse(VoiceRouting.realtimeSupports(VoiceSettings.DEFAULT_REALTIME_BASE_URL, clone))
    }

    @Test fun doubaoRealtimeAcceptsOfficialS2SVoiceIds() {
        val ark = VoiceSettings.DEFAULT_REALTIME_BASE_URL
        // 豆包 TTS 2.0 与 S2S 全双工共用同一套 id，人设里选的音色可直接用于全双工。
        assertTrue(VoiceRouting.realtimeSupports(ark, "zh_female_gaolengyujie_uranus_bigtts"))
        assertTrue(VoiceRouting.realtimeSupports(ark, "zh_male_aojiaobazong_uranus_bigtts"))
        assertTrue(VoiceRouting.realtimeSupports(ark, "ICL_uranus_zh_female_wenrounvshen_tob"))
        // 别家引擎的音色 id 豆包不认。
        assertFalse(VoiceRouting.realtimeSupports(ark, "FunAudioLLM/CosyVoice2-0.5B:claire"))
        assertFalse(VoiceRouting.realtimeSupports(ark, "Cherry"))
        // 空值表示沿用通道默认，不算冲突。
        assertTrue(VoiceRouting.realtimeSupports(ark, ""))
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
