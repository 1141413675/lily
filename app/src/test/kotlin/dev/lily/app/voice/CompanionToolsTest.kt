package dev.lily.app.voice

import dev.lily.agent.AgentTools
import dev.lily.api.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她的身体」这三项工具的形状：能发图、能发动态。
 *
 * 工具描述本身就是模型认识自己能力的唯一来源，所以这里把描述里的关键信息钉住：
 * 必须写明「可以主动调用」，否则她只会等用户开口。
 */
class CompanionToolsTest {

    private fun spec(name: String): ToolSpec =
        AgentTools.specs.first { it.name == name }

    @Test fun `send image tool says she may send it on her own`() {
        val tool = spec(AgentTools.SEND_IMAGE)
        assertTrue(tool.description.contains("主动"))
        assertTrue(tool.description.contains("用户"))
        // 自拍是可选参数：她可以只给一句描述，让 App 自己判断。
        assertFalse(tool.parameters.toString().contains("\"required\":[\"selfie\"]"))
    }

    @Test fun `post dynamic tool carries the text and says she may post on her own`() {
        val tool = spec(AgentTools.POST_DYNAMIC)
        assertTrue(tool.description.contains("主动"))
        assertTrue(tool.parameters.toString().contains("text"))
    }

    @Test fun `both tools are registered next to the existing ones`() {
        val names = AgentTools.specs.map { it.name }
        assertTrue(names.contains(AgentTools.SEND_IMAGE))
        assertTrue(names.contains(AgentTools.POST_DYNAMIC))
        // 名字不能和已有的工具撞。
        assertEquals(names.size, names.toSet().size)
    }
}
