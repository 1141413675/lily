package dev.lily.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 朗读文本处理：括号旁白不能念、只剩旁白时不发语音、分句要短、一条回复一个语气。 */
class TtsScriptTest {

    @Test fun bracketsAndActionsAreNotRead() {
        assertEquals("好呀，我在。", TtsScript.clean("（笑）好呀，我在。"))
        assertEquals("在呢", TtsScript.clean("*伸懒腰* 在呢"))
        assertEquals("今天好累", TtsScript.clean("今天好累【叹气】"))
        assertEquals("等你", TtsScript.clean("(轻轻) 等你"))
    }

    @Test fun onlyBracketsIsNotSpeakable() {
        assertFalse(TtsScript.readable("（笑了笑，没说话）"))
        assertFalse(TtsScript.readable("……"))
        assertTrue(TtsScript.readable("（笑）嗯"))
    }

    @Test fun emotionEmojiNeverReachesTheSynthesizer() {
        // SenseVoice 会把情绪表情写进识别文本；朗读与通话字幕都必须先剥掉。
        assertEquals("没有啊，我很开心哦。", TtsScript.clean("没有啊，我很开心哦。😔"))
        assertFalse(TtsScript.readable("😔"))
    }

    @Test fun markdownListsEmojiAndUrlsAreStripped() {
        val raw = """
            ## 今天
            - 吃了 **面** 🍜
            1. 然后 https://example.com/x
        """.trimIndent()
        val clean = TtsScript.clean(raw)
        assertFalse(clean.contains("#"))
        assertFalse(clean.contains("- "))
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("http"))
        assertFalse(clean.contains("🍜"))
        assertTrue(clean.contains("吃了"))
    }

    @Test fun sentencesAreShortAndBracketsAlreadyGone() {
        val clean = TtsScript.clean("（点头）今天有点累。你吃了吗？我还没呢！")
        assertEquals(listOf("今天有点累。", "你吃了吗？", "我还没呢！"), TtsScript.sentences(clean))
        assertTrue(TtsScript.sentences(clean).none { it.contains("点头") })
    }

    @Test fun longSentenceIsSplitAtCommas() {
        val long = "我跟你说今天外面下了很大的雨我在地铁口等了很久才等到车然后回家的时候鞋都湿了".repeat(2)
        val parts = TtsScript.sentences(long)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.length <= 42 })
    }

    @Test fun punctuationIsKept() {
        val raw = "嗯……我想想——算了，明天再说吧？"
        assertEquals(raw, TtsScript.clean(raw))
    }

    @Test fun laughterTagOnlyWhenLaughing() {
        assertTrue(TtsScript.laughter("哈哈笑死我了"))
        assertTrue(TtsScript.laughter("（自嘲）行吧"))
        assertFalse(TtsScript.laughter("今天有点累……"))
    }

    @Test fun onePerformancePerReply() {
        val a = TtsScript.performance("哈哈你太可爱了")
        assertEquals(a, TtsScript.performance("哈哈你太可爱了"))
        assertTrue(a.contains("笑意"))
        assertTrue(TtsScript.performance("今天有点难过").contains("低落"))
        assertTrue(TtsScript.performance("早点睡，晚安").contains("温柔"))
        assertTrue(TtsScript.performance("吃了吗").contains("轻松"))
    }

    @Test fun promptLeaksAreNeverRead() {
        val raw = """
            ### 说话方式
            - 少用书面连接词，不要播音腔。
            嗯，我在呢。
            [语音]
        """.trimIndent()
        val clean = TtsScript.clean(raw)
        assertEquals("嗯，我在呢。", clean)
        assertFalse(TtsScript.readable("### 说话方式\n- 不要播音腔"))
    }

    @Test fun selfReferentialSentenceIsNotSpoken() {
        assertEquals(
            "嗯，我在呢。你说吧。",
            TtsScript.clean("嗯，我在呢。我知道啦，不用播音腔。你说吧。"),
        )
        assertEquals("好呀。", TtsScript.clean("好呀。我的语气指令里没这句。"))
    }

    @Test fun directionWordingIsRemovedFromTheBody() {
        assertEquals("今天有点累。", TtsScript.clean("今天有点累。语速稍慢。"))
        assertEquals("早点睡吧。", TtsScript.clean("早点睡吧。声音轻一点，说给你听。"))
        assertEquals("", TtsScript.clean("语速放慢，声音轻一点。"))
        assertFalse(TtsScript.readable("（语速稍慢）"))
    }

    @Test fun performanceInstructionsAreNotReadableDirections() {
        val replies = listOf(
            "哈哈你太可爱了",
            "今天有点难过",
            "早点睡，晚安",
            "吃了吗",
        )
        replies.map(TtsScript::performance).forEach { instruction ->
            assertTrue(instruction.startsWith("语气"))
            assertEquals(null, TtsScript.directionLeak(instruction))
            assertFalse(instruction.contains("语速"))
            assertFalse(instruction.contains("声音"))
        }
    }

    @Test fun speechTimeEstimateGrowsWithTextAndSpeed() {
        assertTrue(TtsScript.expectedSpeechMs(40, 1f) > TtsScript.expectedSpeechMs(20, 1f))
        assertTrue(TtsScript.expectedSpeechMs(40, 0.5f) > TtsScript.expectedSpeechMs(40, 1f))
        assertEquals(300L, TtsScript.expectedSpeechMs(1, 1f))
    }

    @Test fun instructionNeverEntersTheSpokenBody() {
        val body = "今天有点累，早点睡吧。"
        val instruction = TtsScript.performance(body)
        val cosy = expressiveSpeechInput(
            dev.lily.app.settings.VoiceSettings(model = "FunAudioLLM/CosyVoice2-0.5B"),
            body,
            instruction,
        )
        assertTrue(cosy.startsWith(instruction))
        assertTrue(cosy.contains("<|endofprompt|>"))
        val spoken = cosy.substringAfter("<|endofprompt|>")
        assertEquals(body, spoken)
        assertFalse(spoken.contains(instruction))

        val other = expressiveSpeechInput(
            dev.lily.app.settings.VoiceSettings(model = "some/Piper"),
            body,
            instruction,
        )
        assertEquals(body, other)
        assertFalse(other.contains("<|endofprompt|>"))
    }
}
