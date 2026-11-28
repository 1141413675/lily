package dev.lily.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiTest {

    @Test
    fun stripRemovesAsrEmotionSuffix() {
        assertEquals("没有啊，我很开心哦。", Emoji.strip("没有啊，我很开心哦。😔"))
        assertEquals("", Emoji.strip("😔"))
        assertEquals("在呢", Emoji.strip("在呢 "))
    }

    @Test
    fun quotaFollowsTheThreeLevels() {
        // 克制：每 6 条 1 条
        assertTrue(Emoji.allowed(EmojiStyle.RESTRAINED, 0))
        (1..5).forEach { assertFalse(Emoji.allowed(EmojiStyle.RESTRAINED, it)) }
        assertTrue(Emoji.allowed(EmojiStyle.RESTRAINED, 6))
        // 自然：每 3 条 1 条，默认档
        assertTrue(Emoji.allowed(EmojiStyle.NATURAL, 0))
        assertTrue(Emoji.allowed(EmojiStyle.NATURAL, 3))
        assertFalse(Emoji.allowed(EmojiStyle.NATURAL, 1))
        // 丰富：每 2 条 1 条
        assertTrue(Emoji.allowed(EmojiStyle.RICH, 2))
        assertFalse(Emoji.allowed(EmojiStyle.RICH, 1))
        assertEquals(EmojiStyle.NATURAL, EmojiStyle.entries.first { it.label == "自然" })
    }

    @Test
    fun clipKeepsAtMostTheLimitAndLeavesTextAlone() {
        val text = "今天有点累 😮‍💨 不过还行 🌙 晚安 ✨"
        val clipped = Emoji.clip(text, 2)
        assertEquals("今天有点累 😮‍💨 不过还行 🌙 晚安 ", clipped)
        assertTrue(clipped.contains("晚安"))
        assertEquals("完全没表情", Emoji.clip("完全没表情", 2))
    }

    @Test
    fun stripRemovesEveryEmoji() {
        assertEquals("晚安啦", Emoji.strip("晚安啦 🌙✨"))
        assertEquals("", Emoji.strip("🌙"))
    }

    @Test
    fun ruleTellsTheModelTheExactQuotaPerLevel() {
        assertEquals(
            "表情尺度：克制。约每 6 条回复里可以有 1 条带 emoji 或颜文字，单条不超过 1 个；其余回复不带，不要用表情代替文字表达。",
            Emoji.rule(EmojiStyle.RESTRAINED),
        )
        assertTrue(Emoji.rule(EmojiStyle.NATURAL).contains("每 3 条"))
        assertTrue(Emoji.rule(EmojiStyle.RICH).contains("每 2 条"))
    }
}
