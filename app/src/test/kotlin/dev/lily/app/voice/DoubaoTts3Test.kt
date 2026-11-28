package dev.lily.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 豆包语音合成 3.0 的响应解析。
 *
 * 响应是 JSONL：一行一个事件，`audio` 事件里的 base64 片段要按顺序拼起来。
 * 拼错顺序、漏掉解码，出来的音频就是坏的。
 */
class DoubaoTts3Test {

    @Test
    fun audioChunksAreConcatenatedInOrder() {
        val body = """
            {"event":"ready","session_id":"abc"}
            {"event":"audio","audio":"${base64("AAA")}"}
            {"event":"audio","audio":"${base64("BBB")}"}
            {"event":"final","is_final":true,"reason":"TTS_STREAM_FINAL_REASON_CLOSED"}
        """.trimIndent()

        val audio = DoubaoTts3.parseAudioStream(body)
        assertEquals("AAABBB", String(audio, Charsets.ISO_8859_1))
    }

    @Test
    fun aChunkSplitAcrossLinesIsStillParsed() {
        // 一个 HTTP 块可能带着半行；按行拼起来再解析。
        val body = "{\"event\":\"audio\",\"audio\":\"${base64("XYZ")}\"}\n"
        assertEquals("XYZ", String(DoubaoTts3.parseAudioStream(body), Charsets.ISO_8859_1))
    }

    @Test
    fun anErrorEventIsSurfaced() {
        val body = """
            {"event":"error","error":{"type":"validation_error","code":"4001","message":"音色不存在"}}
        """.trimIndent()

        val e = assertFailsWith<Exception> { DoubaoTts3.parseAudioStream(body) }
        assertTrue("音色不存在" in (e.message ?: ""), "错误原因要能看懂：${e.message}")
    }

    @Test
    fun anEmptyStreamIsAnError() {
        val e = assertFailsWith<Exception> { DoubaoTts3.parseAudioStream("{\"event\":\"ready\"}") }
        assertTrue("没有返回音频" in (e.message ?: ""), "空结果要说清楚：${e.message}")
    }

    @Test
    fun jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\"", DoubaoTts3.jsonString("a\"b"))
        assertEquals("\"a\\nb\"", DoubaoTts3.jsonString("a\nb"))
        assertEquals("\"中文\"", DoubaoTts3.jsonString("中文"))
    }

    private fun base64(text: String): String =
        android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
}
