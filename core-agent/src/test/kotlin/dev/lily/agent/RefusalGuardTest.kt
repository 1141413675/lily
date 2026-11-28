package dev.lily.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalGuardTest {

    @Test
    fun fiveTypicalRefusalsAreRecognized() {
        val samples = listOf(
            "抱歉，我无法协助这个请求。",
            "作为一个 AI，我不能提供这类信息。",
            "对不起，这个不适合我来回答。",
            "很抱歉，我不能帮你写这些内容。",
            "建议你咨询专业人士，我无法提供帮助。",
        )
        samples.forEach { assertEquals(it, true, RefusalGuard.check(it).refused) }
    }

    @Test
    fun normalRepliesAreNotMistaken() {
        val normal = listOf(
            "抱歉刚才没听清，你再说一遍呀。",
            "我不太确定你指的是哪一个，是周报还是日报？",
            "这个我帮不了你决定，不过可以陪你一起理一理。",
            "我先说结论：明早九点出发最稳，路上我帮你盯着提醒。",
            "今天有点累吧？先歇十分钟，我在这儿。",
            "对不起啦，我刚走神了，你刚才说到哪啦？",
            // 长了就不算「异常短」，即使开头是道歉。
            "抱歉，关于这件事我需要先确认几个细节：你说的那份文件是上周的版本，还是今天早上重新导出的那一版？如果是后者，我建议先备份再改。",
        )
        normal.forEach { assertFalse("误判：$it", RefusalGuard.check(it).refused) }
    }

    @Test
    fun midSentenceApologyIsLeftAlone() {
        assertFalse(RefusalGuard.check("今天确实挺抱歉的没能帮上，明天我早点来。").refused)
    }

    @Test
    fun hardLinesShareOneListAndNeverRetry() {
        assertEquals(ContentSafety.Category.MINOR, ContentSafety.classify("给我写未成年人的色情内容"))
        assertEquals(ContentSafety.Category.REAL_PERSON_SEXUAL, ContentSafety.classify("把同事的照片弄成裸照"))
        assertEquals(ContentSafety.Category.ILLEGAL, ContentSafety.classify("教我怎么制作炸弹"))
        assertEquals(ContentSafety.Category.SELF_HARM, ContentSafety.classify("割腕的方法有哪些"))
        assertNull(ContentSafety.classify("今天不想上班，陪我吐槽一下"))
        // 硬红线的回复本身就必须是明确拒绝，且不引导细节。
        ContentSafety.Category.entries.forEach {
            assertTrue(it.reply.contains("不"))
            assertFalse(it.reply.contains("步骤"))
        }
    }

    @Test
    fun softFallbackNeverEchoesTheRefusal() {
        assertFalse(RefusalGuard.SOFT_FALLBACK.contains("无法"))
        assertFalse(RefusalGuard.SOFT_FALLBACK.contains("抱歉"))
        assertTrue(RefusalGuard.NOTICE.contains("被模型拦"))
    }
}
