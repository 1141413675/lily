package dev.lily.app.voice

import dev.lily.app.persona.Gender
import dev.lily.app.settings.TtsProvider
import dev.lily.app.settings.TtsSettings
import dev.lily.app.settings.VoiceSettings
import kotlinx.serialization.json.Json
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
import java.util.zip.GZIPOutputStream

/** 豆包语音合成 2.0（WebSocket 双向流）：请求帧、服务端帧解析、gzip、音色换算。 */
class DoubaoTtsTest {

    private val doubao = VoiceSettings(
        baseUrl = VoiceSettings.DOUBAO_BASE_URL,
        apiKey = "api-key",
        voice = "zh_female_vv_uranus_bigtts",
        speed = 1.1f,
        provider = TtsProvider.DOUBAO,
        resourceId = VoiceSettings.DOUBAO_RESOURCE_ID,
    )

    // ---- 请求构造 ----

    @Test fun requestFrameHeaderAndEventAreEncoded() {
        val frame = DoubaoTts.buildFrame(DoubaoTts.Event.START_SESSION, "sid-1", """{"event":100}""")
        // 协议版本 1、头长 1 个字；客户端全量请求 + 带 event；JSON + gzip。
        assertEquals(0x11, frame[0].toInt() and 0xFF)
        assertEquals(0x14, frame[1].toInt() and 0xFF)
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        // event 紧随 4 字节头。
        assertEquals(DoubaoTts.Event.START_SESSION, int32(frame, 4))
        // session id 长度 + 内容。
        assertEquals(5, int32(frame, 8))
        assertEquals("sid-1", String(frame, 12, 5, Charsets.UTF_8))
        // 之后是 gzip payload 长度与内容。
        val payloadSize = int32(frame, 17)
        assertTrue(payloadSize > 0)
        assertEquals(frame.size, 21 + payloadSize)
        assertEquals(0x1f, frame[21].toInt() and 0xFF)
        assertEquals(0x8b, frame[22].toInt() and 0xFF)
    }

    @Test fun sessionPayloadCarriesSpeakerAndAudioParamsOnce() {
        val json = DoubaoTts.sessionPayload(doubao, "语气轻一点。")
        assertTrue(json.contains("\"speaker\":\"zh_female_vv_uranus_bigtts\""))
        assertTrue(json.contains("\"format\":\"mp3\""))
        assertTrue(json.contains("\"sample_rate\":24000"))
        assertTrue(json.contains("\"speech_rate\":10"))
        // additions 必须是 JSON 字符串；服务端对对象会报 55000000。
        val additions = Json.parseToJsonElement(json).jsonObject["req_params"]!!
            .jsonObject["additions"]!!.jsonPrimitive
        assertTrue(additions.isString)
        assertEquals("""{"context_texts":["语气轻一点。"]}""", additions.content)
        // 表演要求只出现在 context_texts，不会被当成正文。
        assertFalse(json.contains("\"text\""))

        val plain = DoubaoTts.sessionPayload(doubao, null)
        assertFalse(plain.contains("context_texts"))
    }

    @Test fun taskPayloadOnlyContainsTheSentence() {
        val json = DoubaoTts.taskPayload("今天下班路上看到一只小猫。")
        assertEquals("""{"event":200,"req_params":{"text":"今天下班路上看到一只小猫。"}}""", json)
        assertEquals(1, Regex("\"text\":").findAll(json).count())
    }

    @Test fun speedIsMappedToVolcenginePercentage() {
        assertEquals(0, DoubaoTts.doubaoSpeed(1f))
        assertEquals(10, DoubaoTts.doubaoSpeed(1.1f))
        assertEquals(100, DoubaoTts.doubaoSpeed(9f))
        assertEquals(-50, DoubaoTts.doubaoSpeed(0.1f))
    }

    // ---- 服务端帧解析 ----

    @Test fun parsesSessionEventWithSessionId() {
        val payload = """{"usage":{"text_words":13}}"""
        val frame = DoubaoTts.parseFrame(
            serverFrame(DoubaoTts.Msg.SERVER_FULL_RESPONSE, DoubaoTts.Event.SESSION_FINISHED, "s-1", payload.toByteArray()),
        )
        assertEquals(DoubaoTts.Msg.SERVER_FULL_RESPONSE, frame.messageType)
        assertEquals(DoubaoTts.Event.SESSION_FINISHED, frame.event)
        assertEquals("s-1", frame.sessionId)
        assertEquals(payload, frame.payload.toString(Charsets.UTF_8))
        assertFalse(frame.isAudio)
    }

    @Test fun parsesConnectionEventWithoutSessionId() {
        val frame = DoubaoTts.parseFrame(
            serverFrame(DoubaoTts.Msg.SERVER_FULL_RESPONSE, DoubaoTts.Event.CONNECTION_STARTED, null, "{}".toByteArray()),
        )
        assertEquals(DoubaoTts.Event.CONNECTION_STARTED, frame.event)
        assertNull(frame.sessionId)
    }

    @Test fun audioFrameKeepsRawBytes() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val frame = DoubaoTts.parseFrame(
            serverFrame(DoubaoTts.Msg.AUDIO_ONLY_SERVER, DoubaoTts.Event.TTS_RESPONSE, "s-1", audio),
        )
        assertTrue(frame.isAudio)
        assertEquals(DoubaoTts.Event.TTS_RESPONSE, frame.event)
        assertArrayEquals(audio, frame.payload)
    }

    @Test fun gzipPayloadIsInflated() {
        val json = """{"event":151,"session_id":"s-1"}"""
        val frame = DoubaoTts.parseFrame(
            serverFrame(
                DoubaoTts.Msg.SERVER_FULL_RESPONSE,
                DoubaoTts.Event.SESSION_FINISHED,
                "s-1",
                gzip(json.toByteArray()),
                compression = 1,
            ),
        )
        assertEquals(json, frame.payload.toString(Charsets.UTF_8))
    }

    @Test fun errorFrameExposesCode() {
        val frame = DoubaoTts.parseFrame(errorFrame(45000001, """{"message":"invalid speaker"}"""))
        assertTrue(frame.isError)
        assertEquals(45000001, frame.errorCode)
        assertTrue(frame.payload.toString(Charsets.UTF_8).contains("invalid speaker"))
    }

    @Test fun sessionFailedEventIsAnError() {
        val frame = DoubaoTts.parseFrame(
            serverFrame(DoubaoTts.Msg.SERVER_FULL_RESPONSE, DoubaoTts.Event.SESSION_FAILED, "s-1", """{"message":"bad"}""".toByteArray()),
        )
        assertTrue(frame.isError)
    }

    @Test fun officialEventNumbersMatchTheProtocol() {
        assertEquals(151, DoubaoTts.Event.SESSION_CANCELED)
        assertEquals(152, DoubaoTts.Event.SESSION_FINISHED)
        assertEquals(153, DoubaoTts.Event.SESSION_FAILED)
    }

    @Test fun sessionFinishedWithUsageIsNotAnError() {
        // 官方 SessionFinished 事件号是 152，payload 带 usage；曾把 152 当失败，导致每通电话无声。
        val frame = DoubaoTts.parseFrame(
            serverFrame(
                DoubaoTts.Msg.SERVER_FULL_RESPONSE,
                DoubaoTts.Event.SESSION_FINISHED,
                "s-1",
                """{"usage":{"text_words":8}}""".toByteArray(),
            ),
        )
        assertEquals(152, frame.event)
        assertFalse(frame.isError)
    }

    // ---- 配置与音色 ----

    @Test fun requestSwapsVoicesThatBelongToTheOtherProvider() {
        val tts = TtsSettings(
            provider = TtsProvider.DOUBAO,
            silicon = VoiceSettings(model = VoiceSettings.DEFAULT_TTS_MODEL),
            doubao = VoiceSettings(
                baseUrl = VoiceSettings.DOUBAO_BASE_URL,
                apiKey = "api-key",
                resourceId = VoiceSettings.DOUBAO_RESOURCE_ID,
            ),
        )
        assertEquals("zh_female_vv_uranus_bigtts", tts.request("FunAudioLLM/CosyVoice2-0.5B:claire", Gender.FEMALE).voice)
        assertEquals("zh_male_m191_uranus_bigtts", tts.request("", Gender.MALE).voice)
        // v1 的 BVxxx 音色在 2.0 下不可用，退回默认。
        assertEquals("zh_female_vv_uranus_bigtts", tts.request("BV700_streaming", Gender.FEMALE).voice)
        assertEquals("zh_female_xiaohe_uranus_bigtts", tts.request("zh_female_xiaohe_uranus_bigtts", Gender.FEMALE).voice)

        val back = tts.copy(provider = TtsProvider.SILICONFLOW)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:claire", back.request("zh_female_vv_uranus_bigtts", Gender.FEMALE).voice)
    }

    @Test fun doubaoNeedsKeyAndResourceButNotAGlobalVoice() {
        val tts = TtsSettings(
            provider = TtsProvider.DOUBAO,
            doubao = VoiceSettings(
                baseUrl = VoiceSettings.DOUBAO_BASE_URL,
                apiKey = "api-key",
                provider = TtsProvider.DOUBAO,
                resourceId = VoiceSettings.DOUBAO_RESOURCE_ID,
            ),
        )
        // 音色归角色，账号配置里没有 Speaker 也算完整。
        assertTrue(tts.configured)
        assertFalse(tts.copy(doubao = tts.doubao.copy(resourceId = " ")).configured)
        assertFalse(tts.copy(doubao = tts.doubao.copy(apiKey = "")).configured)
    }

    @Test fun fallbackIsSiliconOnlyWhenItIsConfigured() {
        val base = TtsSettings(provider = TtsProvider.DOUBAO, doubao = doubao)
        val app = dev.lily.app.settings.AppSettings(tts = base)
        assertNull(app.ttsFallback)

        val withSilicon = base.copy(
            silicon = VoiceSettings(
                baseUrl = "https://api.siliconflow.cn/v1",
                model = VoiceSettings.DEFAULT_TTS_MODEL,
                apiKey = "sk-test",
            ),
        )
        val configured = dev.lily.app.settings.AppSettings(tts = withSilicon).ttsFallback
        assertNotNull(configured)
        assertEquals(TtsProvider.SILICONFLOW, configured!!.provider)
        assertTrue(configured.configured)
    }

    // ---- 构造服务端帧的辅助 ----

    private fun serverFrame(
        messageType: Int,
        event: Int,
        sessionId: String?,
        payload: ByteArray,
        compression: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, ((messageType shl 4) or 0x04).toByte(), (0x10 or compression).toByte(), 0))
        out.write(int32(event))
        if (sessionId != null) {
            val bytes = sessionId.toByteArray(Charsets.UTF_8)
            out.write(int32(bytes.size))
            out.write(bytes)
        }
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

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

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
