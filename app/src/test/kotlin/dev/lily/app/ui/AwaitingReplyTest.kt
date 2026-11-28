package dev.lily.app.ui

import dev.lily.app.session.ChatItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「正在输入」与「正在准备声音」互斥。
 *
 * 两个阶段都会画三个点：等她开口是聊天流里的「正在输入」，她开口后、语音还没合成好是
 * 语音气泡自己的点。两者同时出现会让人以为有两件事在发生，所以先开口的那个要让位。
 */
class AwaitingReplyTest {
    private fun rows(vararg items: ChatItem): List<TimelineRow> = chatTimeline(items.toList())

    @Test fun `shows the typing indicator right after I speak`() {
        assertTrue(awaitingReply(rows(ChatItem.User(1, "在吗"))))
    }

    @Test fun `stops the typing indicator once she answers in text`() {
        assertFalse(
            awaitingReply(rows(ChatItem.User(1, "在吗"), ChatItem.Assistant(2, "在呀"))),
        )
    }

    @Test fun `stops the typing indicator once her voice bubble lands`() {
        // 语音回复先落一条空的气泡（声音还没合成好），由它自己显示三个点。
        assertFalse(
            awaitingReply(rows(ChatItem.User(1, "在吗"), ChatItem.AssistantVoice(2, "", 0))),
        )
    }

    @Test fun `stops the typing indicator when she asks a question`() {
        assertFalse(
            awaitingReply(rows(ChatItem.User(1, "在吗"), ChatItem.Question(2, "你在做什么", false))),
        )
    }

    @Test fun `a new turn starts the typing indicator again`() {
        assertTrue(
            awaitingReply(
                rows(
                    ChatItem.User(1, "在吗"),
                    ChatItem.Assistant(2, "在呀"),
                    ChatItem.User(3, "在干嘛"),
                ),
            ),
        )
    }

    @Test fun `her reply from an earlier turn does not hide the indicator`() {
        assertTrue(
            awaitingReply(
                rows(
                    ChatItem.Assistant(1, "在呀"),
                    ChatItem.User(2, "在干嘛"),
                ),
            ),
        )
    }

    @Test fun `nothing is pending before I say anything`() {
        assertFalse(awaitingReply(rows(ChatItem.Assistant(1, "在呀"))))
        assertFalse(awaitingReply(emptyList()))
    }

    @Test fun `process rows between the two do not count as her speaking`() {
        // 手机任务的步骤属于过程，不算她开口：「正在输入」继续显示。
        assertTrue(
            awaitingReply(
                rows(
                    ChatItem.User(1, "打开设置"),
                    ChatItem.Progress(2, "第 1 步：点击 设置"),
                ),
            ),
        )
    }
}
