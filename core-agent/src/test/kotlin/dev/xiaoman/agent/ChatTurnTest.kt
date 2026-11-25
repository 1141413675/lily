package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatTurnTest {
    @Test
    fun replyWithSkillIndexMemoryHitsAndHistory() = runTest {
        val h = Harness(skills = FakeSkills(skill("taxi-home", "打车回家")))
        h.memory.write(Note(title = "家", permalink = "places/家", observations = listOf(Observation("address", "北京市海淀区某某路 1 号"))))
        h.chat.reply("你好，我是小满。").reply("你家在海淀。")

        h.agent.handle("你好，某某路是我家吗", h.events, h.stop)
        assertEquals(listOf("你好，我是小满。"), h.events.of<AgentEvent.Reply>().map { it.text })
        val first = h.chat.requests[0]
        val system = first.messages[0].text
        assertTrue(system.startsWith("你是小满"))
        assertTrue("[[sing:情绪]]" in system)
        assertTrue("- taxi-home: 打车回家" in system)
        assertTrue("places/家" in system && "某某路" in system)
        assertEquals(AgentTools.specs.map { it.name }, first.tools.map { it.name })

        h.agent.handle("再说一遍", h.events, h.stop)
        val second = h.chat.requests[1].messages
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), second.map { it.role })
        assertEquals("你好，某某路是我家吗", second[1].text)
    }

    @Test
    fun timeContextIsInjectedEveryTurn() = runTest {
        val lateNight = java.time.ZonedDateTime.of(2026, 11, 22, 2, 0, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val h = Harness(clock = { lateNight }, lastInteraction = { lateNight - 2 * 3_600_000L })
        h.chat.reply("嗯").reply("在的")
        h.agent.handle("在吗", h.events, h.stop)
        h.agent.handle("睡了吗", h.events, h.stop)
        val system = h.chat.requests[0].messages[0].text
        assertTrue("深夜" in system)
        assertTrue("距上次互动：2 小时前" in system)
        assertTrue("不问候错时段" in system && "不假定用户所在时区" in system)
        // 第二轮仍要带时间，不只在首轮注入。
        assertTrue("深夜" in h.chat.requests[1].messages[0].text)
    }

    @Test
    fun historyIsBounded() = runTest {
        val h = Harness()
        h.chat.fallback = { dev.xiaoman.api.ChatResponse("嗯") }
        repeat(15) { h.agent.handle("消息 $it", h.events, h.stop) }
        val last = h.chat.requests.last().messages
        assertEquals(1 + XiaomanAgent.HISTORY_LIMIT + 1, last.size)
        assertEquals(ChatRole.USER, last[1].role)
    }

    @Test
    fun trimmedHistoryIsSummarizedIntoTheSystemPrompt() = runTest {
        val h = Harness()
        h.chat.fallback = { dev.xiaoman.api.ChatResponse("嗯") }
        repeat(15) { h.agent.handle("消息 $it", h.events, h.stop) }
        val system = h.chat.requests.last().messages[0].text
        assertTrue("## 更早的对话（摘要）" in system)
        // 最早的一轮已滑出原始窗口，但仍以摘要形式留在提示里。
        assertTrue("用户：消息 0" in system)
        assertTrue(system.indexOf("## 更早的对话（摘要）") < system.indexOf("## 可能相关的记忆").let { if (it < 0) Int.MAX_VALUE else it })
    }

    @Test
    fun stablePrefixStaysIdenticalBetweenTurns() = runTest {
        val h = Harness()
        h.chat.reply("嗯").reply("好")
        h.agent.handle("在吗", h.events, h.stop)
        h.agent.handle("再说", h.events, h.stop)
        fun stable(text: String) = text.substringBefore(Prompts.NOW_HEADER)
        assertEquals(stable(h.chat.requests[0].messages[0].text), stable(h.chat.requests[1].messages[0].text))
        // 时间与记忆都在稳定段之后，切换一轮不会改动前缀。
        assertTrue(Prompts.NOW_HEADER in h.chat.requests[1].messages[0].text)
    }

    @Test
    fun memoryWriteTool() = runTest {
        val h = Harness()
        h.chat.tools(
            ToolCall(
                "c1", AgentTools.MEMORY_WRITE,
                """{"title": "家", "permalink": "places/家", "type": "place", "observations": [{"category": "address", "content": "北京市海淀区某某路 1 号", "tags": ["常用"], "context": "用户告知"}], "relations": [{"type": "lives_in", "target": "北京"}]}""",
            ),
        ).reply("记住了。")

        h.agent.handle("我家在北京市海淀区某某路 1 号", h.events, h.stop)

        val note = assertNotNull(h.memory.notes["places/家"])
        assertEquals("place", note.type)
        assertEquals(listOf(Observation("address", "北京市海淀区某某路 1 号", listOf("常用"), "用户告知")), note.observations)
        assertTrue("- [address] 北京市海淀区某某路 1 号 #常用 (用户告知)" in note.body)
        assertTrue("- lives_in [[北京]]" in note.body)
        assertEquals(listOf("places/家"), h.events.of<AgentEvent.MemoryUpdated>().map { it.permalink })
        assertEquals("记住了。", h.events.of<AgentEvent.Reply>().single().text)

        val toolMsg = h.chat.requests[1].messages.last()
        assertEquals(ChatRole.TOOL, toolMsg.role)
        assertEquals("c1", toolMsg.toolCallId)
        assertTrue("\"ok\":true" in toolMsg.text)
        assertTrue(h.chat.requests[1].messages.any { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() })

        // Writing the same observation again does not duplicate it.
        h.chat.tools(ToolCall("c2", AgentTools.MEMORY_WRITE, """{"title": "家", "observations": [{"category": "address", "content": "北京市海淀区某某路 1 号"}, {"category": "note", "content": "小区东门"}]}""")).reply("好")
        h.agent.handle("家在东门", h.events, h.stop)
        assertEquals(2, h.memory.notes["places/家"]!!.observations.size)
    }

    @Test
    fun badToolArgumentsGoBackToTheModel() = runTest {
        val h = Harness()
        h.chat.tools(
            ToolCall("c1", AgentTools.MEMORY_WRITE, "{not json"),
            ToolCall("c2", AgentTools.MEMORY_READ, "{}"),
            ToolCall("c3", "no_such_tool", "{}"),
        // 带角色语气，别让拒答兜底把它当成客服式拒答替换掉。
        ).reply("抱歉呀，出错了。")

        h.agent.handle("记一下", h.events, h.stop)

        val tools = h.chat.requests[1].messages.filter { it.role == ChatRole.TOOL }
        assertEquals(3, tools.size)
        assertTrue(tools.all { "\"error\"" in it.text })
        assertTrue("identifier" in tools[1].text)
        assertEquals("抱歉呀，出错了。", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun searchReadForgetAndListSkills() = runTest {
        val h = Harness(skills = FakeSkills(skill("taxi-home", "打车回家")))
        h.memory.write(Note(title = "家", permalink = "places/家", observations = listOf(Observation("address", "海淀区"))))
        h.chat.tools(
            ToolCall("a", AgentTools.MEMORY_SEARCH, """{"query": "海淀区"}"""),
            ToolCall("b", AgentTools.MEMORY_READ, """{"identifier": "家"}"""),
            ToolCall("c", AgentTools.LIST_SKILLS, ""),
        ).tools(ToolCall("d", AgentTools.MEMORY_FORGET, """{"identifier": "places/家"}""")).reply("忘掉了")

        h.agent.handle("忘掉我家", h.events, h.stop)
        val r1 = h.chat.requests[1].messages.filter { it.role == ChatRole.TOOL }.map { it.text }
        assertTrue("places/家" in r1[0])
        assertTrue("\"found\":true" in r1[1] && "- [address] 海淀区" in r1[1])
        assertTrue("taxi-home" in r1[2])
        assertTrue("\"deleted\":true" in h.chat.requests[2].messages.last().text)
        assertTrue(h.memory.notes.isEmpty())
        assertEquals(listOf("places/家"), h.events.of<AgentEvent.MemoryUpdated>().map { it.permalink })
    }

    @Test
    fun toolRoundsAreBounded() = runTest {
        val h = Harness()
        h.chat.fallback = { dev.xiaoman.api.ChatResponse("", listOf(ToolCall("x", AgentTools.LIST_SKILLS, "{}"))) }
        h.agent.handle("循环", h.events, h.stop)
        assertEquals(XiaomanAgent.MAX_TOOL_ROUNDS + 1, h.chat.requests.size)
        assertTrue(h.chat.requests.last().tools.isEmpty())
        assertEquals(Prompts.TOOL_ROUNDS_EXCEEDED, h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun llmFailureBecomesFriendlyFailed() = runTest {
        val h = Harness()
        h.chat.fail()
        h.agent.handle("你好", h.events, h.stop)
        val failed = h.events.of<AgentEvent.Failed>().single()
        assertTrue(failed.message.startsWith(Prompts.LLM_FAILED))
        assertTrue(h.events.of<AgentEvent.Reply>().isEmpty())
    }
}
