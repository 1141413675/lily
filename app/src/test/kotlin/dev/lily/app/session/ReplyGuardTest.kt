package dev.lily.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 出口检查：标记和系统提示残留要剥掉，正常聊天原样保留。 */
class ReplyGuardTest {

    @Test fun stripsPresentationTags() {
        assertEquals("今天好累。", ReplyGuard.sanitize("[语音] 今天好累。"))
        assertEquals("嗯。", ReplyGuard.sanitize("[文字]\n嗯。"))
    }

    @Test fun dropsPromptAndSkillLines() {
        val raw = """
            ## 人格设定
            - 你叫「Lily」；被问名字只说这个名字。
            ### 说话方式
            - 不要播音腔。
            我在呢。
            - 每次都必须写标记。
        """.trimIndent()
        assertEquals("我在呢。", ReplyGuard.sanitize(raw))
    }

    @Test fun keepsNormalText() {
        val text = "哈哈，今天可真累啊……先睡啦。"
        assertEquals(text, ReplyGuard.sanitize(text))
        assertTrue(ReplyGuard.readable(text))
    }

    @Test fun leakOnlyTextIsNotReadable() {
        assertFalse(ReplyGuard.readable("## 人格设定\n- 你叫「Lily」"))
        assertFalse(ReplyGuard.readable("[语音]"))
    }

    @Test fun dropsSelfReferentialSentence() {
        assertEquals("嗯，我在。", ReplyGuard.sanitize("嗯，我在。我知道啦，不用播音腔。"))
        assertEquals("好呀。", ReplyGuard.sanitize("好呀。系统提示里不是这么写的。"))
        assertFalse(ReplyGuard.readable("不用播音腔。"))
    }
}
