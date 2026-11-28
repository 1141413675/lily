package dev.lily.app.voice

import dev.lily.app.settings.AsrProvider
import dev.lily.app.settings.AsrSettings
import dev.lily.app.settings.TtsProvider
import dev.lily.app.settings.VoiceSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 豆包流式语音识别 2.0：首包参数、音频包序号/负包、gzip 响应、文本拼接与错误解析。 */
class DoubaoAsrTest {

    private val doubao = VoiceSettings(
        baseUrl = VoiceSettings.DOUBAO_ASR_BASE_URL,
        apiKey = "api-key",
        provider = TtsProvider.DOUBAO,
        resourceId = VoiceSettings.DOUBAO_ASR_RESOURCE_ID,
    )

    // ---- 请求构造 ----

    @Test fun firstRequestJsonCarriesTypedAudioAndRequestFields() {
        val root = Json.parseToJsonElement(DoubaoAsr.firstRequestJson()).jsonObject
        assertEquals("lily", root["user"]!!.jsonObject["uid"]!!.jsonPrimitive.content)
        assertEquals("Android", root["user"]!!.jsonObject["platform"]!!.jsonPrimitive.content)

        val audio = root["audio"]!!.jsonObject
        assertEquals("pcm", audio["format"]!!.jsonPrimitive.content)
        assertEquals("raw", audio["codec"]!!.jsonPrimitive.content)
        // 三个数值字段必须是数字，不是字符串。
        assertEquals(16000, audio["rate"]!!.jsonPrimitive.int)
        assertEquals(16, audio["bits"]!!.jsonPrimitive.int)
        assertEquals(1, audio["channel"]!!.jsonPrimitive.int)
        assertFalse(audio["rate"]!!.jsonPrimitive.isString)

        val request = root["request"]!!.jsonObject
        assertEquals("bigmodel", request["model_name"]!!.jsonPrimitive.content)
        assertTrue(request["enable_itn"]!!.jsonPrimitive.boolean)
        assertTrue(request["enable_punc"]!!.jsonPrimitive.boolean)
        assertTrue(request["show_utterances"]!!.jsonPrimitive.boolean)
        assertEquals("full", request["result_type"]!!.jsonPrimitive.content)
    }

    @Test fun fullRequestFrameIsJsonWithGzipPayload() {
        val frame = DoubaoAsr.buildFullRequest("""{"a":1}""")
        // 协议 1 / 头长 1 字；full client request、flags 0；JSON + gzip。
        assertEquals(0x11, frame[0].toInt() and 0xFF)
        assertEquals(0x10, frame[1].toInt() and 0xFF)
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        val size = int32(frame, 4)
        assertEquals(frame.size, 8 + size)
        assertEquals("""{"a":1}""", ungzip(frame.copyOfRange(8, 8 + size)))
    }

    @Test fun audioPacketUsesIncrementingSequenceAndNegativeLastPacket() {
        val pcm = ByteArray(320) { (it and 0xFF).toByte() }
        val first = DoubaoAsr.audioPacket(1, pcm)
        assertEquals(0x11, first[0].toInt() and 0xFF)
        // message type 2 + flags 1（正序号）。
        assertEquals(0x21, first[1].toInt() and 0xFF)
        // 音频是 raw 序列化 + gzip。
        assertEquals(0x01, first[2].toInt() and 0xFF)
        assertEquals(1, int32(first, 4))
        val firstSize = int32(first, 8)
        assertEquals(first.size, 12 + firstSize)
        assertEquals(0x1f, first[12].toInt() and 0xFF)
        assertEquals(0x8b, first[13].toInt() and 0xFF)
        assertArrayEquals(pcm, bytes(first.copyOfRange(12, 12 + firstSize)))

        val third = DoubaoAsr.audioPacket(3, pcm, offset = 0, count = pcm.size)
        assertEquals(3, int32(third, 4))

        val last = DoubaoAsr.audioPacket(3, pcm, last = true)
        // 最后一包 flags 3，序号取负数。
        assertEquals(0x23, last[1].toInt() and 0xFF)
        assertEquals(-3, int32(last, 4))
        // 没录到任何音频时也要能收尾：负序号从 -1 开始。
        assertEquals(-1, int32(DoubaoAsr.audioPacket(0, ByteArray(0), last = true), 4))
    }

    // ---- 响应解析 ----

    @Test fun gzippedServerResponseIsInflatedAndTextIsTaken() {
        val payload = """{"result":{"text":"今天天气不错"},"audio_info":{"duration":1200}}"""
        val frame = DoubaoAsr.parseFrame(
            serverFrame(DoubaoAsr.Msg.SERVER_FULL_RESPONSE, flags = 0, payload = gzip(payload.toByteArray()), compression = 1),
        )
        assertEquals(DoubaoAsr.Msg.SERVER_FULL_RESPONSE, frame.messageType)
        assertFalse(frame.isError)
        assertEquals(payload, frame.payload.toString(Charsets.UTF_8))
        assertEquals("今天天气不错", frame.text)
    }

    @Test fun utterancesAreJoinedWhenTextIsEmpty() {
        val payload = """
            {"result":{"text":"","utterances":[
              {"text":"你好，","definite":true},
              {"text":"在吗？","definite":true},
              {"text":"还没说完","definite":false}
            ]}}
        """.trimIndent()
        assertEquals("你好，在吗？", DoubaoAsr.textOf(payload.toByteArray()))
    }

    @Test fun fullTextWinsOverUtterances() {
        val payload = """{"result":{"text":"完整句子","utterances":[{"text":"完整","definite":true}]}}"""
        assertEquals("完整句子", DoubaoAsr.textOf(payload.toByteArray()))
    }

    @Test fun lastResponseIsMarkedByFlags() {
        val payload = """{"result":{"text":"好"}}""".toByteArray()
        val withSequence = DoubaoAsr.parseFrame(
            serverFrame(DoubaoAsr.Msg.SERVER_FULL_RESPONSE, flags = 0x3, payload = payload, sequence = -2),
        )
        assertTrue(withSequence.isLast)
        assertEquals(-2, withSequence.sequence)
        assertEquals("好", withSequence.text)

        val noSequence = DoubaoAsr.parseFrame(
            serverFrame(DoubaoAsr.Msg.SERVER_FULL_RESPONSE, flags = 0x2, payload = payload),
        )
        assertTrue(noSequence.isLast)
        assertNull(noSequence.sequence)
    }

    @Test fun errorFrameExposesCodeAndMessage() {
        val frame = DoubaoAsr.parseFrame(
            errorFrame(45000001, """{"error":"invalid X-Api-Key"}"""),
        )
        assertTrue(frame.isError)
        assertEquals(45000001, frame.errorCode)
        val text = DoubaoAsr.failureText(frame.errorCode, frame.payload.toString(Charsets.UTF_8))
        assertTrue(text.contains("45000001"))
        assertTrue(text.contains("invalid X-Api-Key"))
    }

    @Test fun httpHandshakeFailureKeepsStatusAndAdvice() {
        val text = DoubaoAsr.failureText(401, """{"message":"invalid token"}""")
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("invalid token"))
        assertTrue(text.contains("Key"))
    }

    @Test fun wavPayloadIsReducedToPcm() {
        val pcm = ByteArray(640) { (it and 0xFF).toByte() }
        val wav = wav(pcm)
        assertArrayEquals(pcm, DoubaoAsr.pcmData(wav))
        assertArrayEquals(pcm, DoubaoAsr.pcmData(pcm))
    }

    // ---- 配置与硅基回落 ----

    @Test fun doubaoNeedsOnlyApiKeyAndFallsBackOnlyWhenSiliconIsConfigured() {
        val asr = AsrSettings(provider = AsrProvider.DOUBAO, doubao = doubao)
        assertTrue(asr.configured)
        assertNull(asr.siliconFallback)
        assertTrue(asr.missing.isEmpty())

        val unconfigured = asr.copy(doubao = doubao.copy(apiKey = ""))
        assertFalse(unconfigured.configured)
        assertEquals(listOf("API Key"), unconfigured.missing)

        val withSilicon = asr.copy(
            silicon = VoiceSettings(
                baseUrl = VoiceSettings.DEFAULT_BASE_URL,
                model = VoiceSettings.DEFAULT_ASR_MODEL,
                apiKey = "sk-test",
            ),
        )
        assertNotNull(withSilicon.siliconFallback)

        // 留空 Resource ID 时用官方默认小时版，不影响可用性。
        val defaulted = AsrSettings(provider = AsrProvider.DOUBAO, doubao = doubao.copy(resourceId = " "))
        assertEquals(VoiceSettings.DOUBAO_ASR_RESOURCE_ID, defaulted.resourceId)
        assertTrue(defaulted.configured)
    }

    // ---- 构造服务端帧的辅助 ----

    private fun serverFrame(
        messageType: Int,
        flags: Int,
        payload: ByteArray,
        sequence: Int? = null,
        compression: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, ((messageType shl 4) or flags).toByte(), (0x10 or compression).toByte(), 0))
        if (sequence != null) out.write(int32(sequence))
        out.write(int32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun errorFrame(code: Int, json: String): ByteArray {
        val payload = json.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, 0xF0.toByte(), 0x10, 0))
        out.write(int32(code))
        out.write(int32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + pcm.size))
        out.write("WAVEfmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))
        out.write(le16(1))
        out.write(le32(16_000))
        out.write(le32(32_000))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun bytes(payload: ByteArray): ByteArray =
        GZIPInputStream(payload.inputStream()).use { it.readBytes() }

    private fun ungzip(payload: ByteArray): String = bytes(payload).toString(Charsets.UTF_8)

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
