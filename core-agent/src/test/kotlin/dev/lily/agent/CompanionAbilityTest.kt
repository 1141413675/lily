package dev.lily.agent

import dev.lily.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「她的身体」：发图与发动态这两个工具。
 *
 * 覆盖三件事：
 * 1. 设备给不了的能力不出现在工具清单里（她不该假装能做到）；
 * 2. 工具结果会回灌给模型（她知道到底成没成）；
 * 3. 同一轮里同一项能力只生效一次（不能一口气刷五条动态）。
 */
class CompanionAbilityTest {

    private fun harness(
        sendImage: (suspend (String, Boolean?) -> String)? = { _, _ -> "已经发出去了。" },
        postDynamic: (suspend (String) -> String)? = { "动态已经发出去了。" },
    ) = Harness(sendImage = sendImage, postDynamic = postDynamic)

    @Test
    fun toolsAreOfferedWhenTheDeviceCanDoThem() = runTest {
        val h = harness()
        h.chat.reply("好")

        h.agent.handle("在吗", h.events, h.stop)

        val names = h.chat.requests[0].tools.map { it.name }
        assertTrue(AgentTools.SEND_IMAGE in names)
        assertTrue(AgentTools.POST_DYNAMIC in names)
    }

    @Test
    fun toolsAreAbsentWhenTheDeviceCannotDoThem() = runTest {
        val h = harness(sendImage = null, postDynamic = null)
        h.chat.reply("好")

        h.agent.handle("在吗", h.events, h.stop)

        val names = h.chat.requests[0].tools.map { it.name }
        assertFalse(AgentTools.SEND_IMAGE in names)
        assertFalse(AgentTools.POST_DYNAMIC in names)
        // 其它工具照旧在。
        assertTrue(AgentTools.MEMORY_SEARCH in names)
    }

    @Test
    fun sendImageResultGoesBackToTheModel() = runTest {
        val seen = mutableListOf<String>()
        val h = harness(sendImage = { description, _ ->
            seen += description
            "已经发出去了。"
        })
        h.chat.tools(ToolCall("c1", AgentTools.SEND_IMAGE, """{"description":"窗外的雨"}"""))
        h.chat.reply("给你拍了一张")

        h.agent.handle("给你看看窗外", h.events, h.stop)

        assertEquals(listOf("窗外的雨"), seen)
        val toolMessage = h.chat.requests[1].messages.last()
        assertTrue("已经发出去了" in toolMessage.text)
    }

    @Test
    fun postDynamicResultGoesBackToTheModel() = runTest {
        val posted = mutableListOf<String>()
        val h = harness(postDynamic = { text ->
            posted += text
            "动态已经发出去了。"
        })
        h.chat.tools(ToolCall("c1", AgentTools.POST_DYNAMIC, """{"text":"今天写了点东西"}"""))
        h.chat.reply("记下来了")

        h.agent.handle("你今天做了什么", h.events, h.stop)

        assertEquals(listOf("今天写了点东西"), posted)
        assertTrue("动态已经发出去了" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun theSameAbilityRunsOnlyOncePerTurn() = runTest {
        var dynamicCalls = 0
        val h = harness(postDynamic = { _ ->
            dynamicCalls++
            "动态已经发出去了。"
        })
        // 模型在同一轮里连着点了两次发动态。
        h.chat.tools(
            ToolCall("c1", AgentTools.POST_DYNAMIC, """{"text":"第一条"}"""),
            ToolCall("c2", AgentTools.POST_DYNAMIC, """{"text":"第二条"}"""),
        )
        h.chat.reply("写了两条")

        h.agent.handle("你今天做了什么", h.events, h.stop)

        assertEquals(1, dynamicCalls)
        val toolMessage = h.chat.requests[1].messages.last()
        assertTrue("已经发过一条" in toolMessage.text)
    }

    @Test
    fun imageAndDynamicCanBothHappenInOneTurn() = runTest {
        var images = 0
        var dynamics = 0
        val h = harness(
            sendImage = { _, _ -> images++; "已经发出去了。" },
            postDynamic = { dynamics++; "动态已经发出去了。" },
        )
        h.chat.tools(
            ToolCall("c1", AgentTools.SEND_IMAGE, """{"description":"雨"}"""),
            ToolCall("c2", AgentTools.POST_DYNAMIC, """{"text":"今天下雨"}"""),
        )
        h.chat.reply("都发了")

        h.agent.handle("记录一下今天", h.events, h.stop)

        assertEquals(1, images)
        assertEquals(1, dynamics)
    }

    @Test
    fun aFailedToolCallBecomesAnErrorResultTheModelSees() = runTest {
        val h = harness(postDynamic = { error("发不出去") })
        h.chat.tools(ToolCall("c1", AgentTools.POST_DYNAMIC, """{"text":"今天"}"""))
        h.chat.reply("没发出去")

        h.agent.handle("记一下今天", h.events, h.stop)

        val toolMessage = h.chat.requests[1].messages.last()
        assertTrue("error" in toolMessage.text)
    }
}
