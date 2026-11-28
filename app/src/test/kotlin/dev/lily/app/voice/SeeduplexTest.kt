package dev.lily.app.voice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 豆包实时语音模型 3.0（Seeduplex）事件层的序列化与解析。
 *
 * 这些断言锁住「协议形状」：改错了服务端会直接报错或静默不回话，比 UI 出问题更难查。
 */
class SeeduplexTest {

    private fun parse(json: String): JSONObject = JSONObject(json)

    @Test fun `session create carries model instructions and audio config`() {
        val tools = JSONArray().put(JSONObject()
            .put("type", "function")
            .put("name", "run_phone_task"))
        val event = parse(Seeduplex.sessionCreate("你是小夏", "zh_female_vv_uranus_bigtts", tools))

        assertEquals("session.create", event.getString("type"))
        val session = event.getJSONObject("session")
        // 全双工版本的模型是固定单值，不是用户填的模型名。
        assertEquals("1.2.6.1", session.getString("model"))
        assertEquals("你是小夏", session.getString("instructions"))

        val audio = session.getJSONObject("audio")
        // 输入 16k PCM。
        assertEquals("pcm", audio.getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(16_000, audio.getJSONObject("input").getJSONObject("format").getInt("rate"))
        // 输出 24k 的 16bit 小端 PCM：播放端不用重采样。
        assertEquals("pcm_s16le", audio.getJSONObject("output").getJSONObject("format").getString("type"))
        assertEquals(24_000, audio.getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("zh_female_vv_uranus_bigtts", audio.getJSONObject("output").getString("voice"))
        assertEquals(1, session.getJSONArray("tools").length())
    }

    @Test fun `session create without tools omits the field`() {
        val event = parse(Seeduplex.sessionCreate("你好", "zh_female_vv_uranus_bigtts"))
        assertFalse(event.getJSONObject("session").has("tools"))
    }

    @Test fun `session create can resume a previous session id`() {
        val event = parse(Seeduplex.sessionCreate("你好", "zh_female_vv_uranus_bigtts", sessionId = "sess-1"))
        assertEquals("sess-1", event.getJSONObject("session").getString("id"))
    }

    @Test fun `audio append base64 encodes the pcm payload`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5)
        val event = parse(Seeduplex.audioAppend(pcm))
        assertEquals("input_audio_buffer.append", event.getString("type"))
        val decoded = Base64.decode(event.getString("audio"), Base64.DEFAULT)
        assertEquals(pcm.toList(), decoded.toList())
    }

    @Test fun `turn control events use the documented names`() {
        assertEquals("input_audio_buffer.commit", parse(Seeduplex.audioCommit()).getString("type"))
        assertEquals("input_audio_mute.commit", parse(Seeduplex.audioMute()).getString("type"))
        assertEquals("input_audio_unmute.commit", parse(Seeduplex.audioUnmute()).getString("type"))
        assertEquals("response.cancel", parse(Seeduplex.responseCancel()).getString("type"))
        assertEquals("session.close", parse(Seeduplex.sessionClose()).getString("type"))
    }

    @Test fun `tool result uses items array with role tool and matching call id`() {
        val event = parse(Seeduplex.toolResult("call-9", "执行完了"))
        assertEquals("conversation.item.create", event.getString("type"))
        val items = event.getJSONArray("items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        // call_id 必须与下行一致，否则服务端配不上这次调用。
        assertEquals("call-9", item.getString("call_id"))
        assertEquals("tool", item.getString("role"))
        val content = item.getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("执行完了", content.getJSONObject(0).getString("text"))
    }

    @Test fun `item delete carries the id in an items array`() {
        val event = parse(Seeduplex.itemDelete("item-3"))
        assertEquals("conversation.item.delete", event.getString("type"))
        assertEquals("item-3", event.getJSONArray("items").getJSONObject(0).getString("id"))
    }

    @Test fun `parallel function calls are all extracted`() {
        val event = JSONObject()
            .put("type", "response.function_call_arguments.done")
            .put("items", JSONArray()
                .put(JSONObject().put("call_id", "c1").put("name", "run_phone_task").put("arguments", "{\"goal\":\"打开设置\"}"))
                .put(JSONObject().put("call_id", "c2").put("name", "run_phone_task").put("arguments", "{\"goal\":\"看天气\"}")))
        val calls = Seeduplex.functionCalls(event)
        assertEquals(2, calls.size)
        assertEquals("c1", calls[0].callId)
        assertEquals("打开设置", JSONObject(calls[0].arguments).getString("goal"))
        assertEquals("c2", calls[1].callId)
    }

    @Test fun `function calls without call id are ignored`() {
        val event = JSONObject()
            .put("type", "response.function_call_arguments.done")
            .put("items", JSONArray().put(JSONObject().put("name", "run_phone_task")))
        assertTrue(Seeduplex.functionCalls(event).isEmpty())
    }

    @Test fun `text is read from text delta or item content`() {
        assertEquals("你好", Seeduplex.textOf(JSONObject().put("text", " 你好 ")))
        assertEquals("你好", Seeduplex.textOf(JSONObject().put("delta", "你好")))
        assertEquals(
            "在的",
            Seeduplex.textOf(JSONObject().put("item", JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", "在的"))))),
        )
        assertEquals("", Seeduplex.textOf(JSONObject()))
    }

    @Test fun `audio payload is read from delta or audio`() {
        assertEquals("AAA", Seeduplex.audioOf(JSONObject().put("delta", "AAA")))
        assertEquals("BBB", Seeduplex.audioOf(JSONObject().put("audio", "BBB")))
        assertEquals("", Seeduplex.audioOf(JSONObject()))
    }

    @Test fun `error text carries code and message when both present`() {
        val event = JSONObject().put("type", "error").put("error", JSONObject()
            .put("code", "45000003")
            .put("message", "Abnormal silence audio"))
        assertEquals("45000003 Abnormal silence audio", Seeduplex.errorOf(event))
    }
}
