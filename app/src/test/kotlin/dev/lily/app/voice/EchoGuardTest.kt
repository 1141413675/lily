package dev.lily.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文本层自回声判定：归一化、相似度阈值与短句豁免。 */
class EchoGuardTest {

    @Test fun punctuationAndSpacesAreIgnored() {
        assertEquals("今天天气真好", EchoGuard.normalize("今天 天气，真好！"))
        assertEquals(1.0, EchoGuard.similarity("今天天气真好！", "今天天气真好"), 0.0001)
    }

    @Test fun sameSentenceIsEcho() {
        assertTrue(EchoGuard.isSelfEcho("我在呢，你说。", listOf("我在呢，你说。")))
    }

    @Test fun nearCopyIsStillEcho() {
        // 多一个「吧」：编辑距离 1，相似度 5/6 ≈ 0.833，高于 0.8。
        assertTrue(EchoGuard.isSelfEcho("我在呢你说吧", listOf("我在呢你说")))
    }

    @Test fun unrelatedSentenceIsNotEcho() {
        assertFalse(
            EchoGuard.isSelfEcho(
                "帮我打开相机",
                listOf("那我这么说，是不是就不像啦？"),
            ),
        )
    }

    @Test fun shortBackchannelIsNotEcho() {
        // 「嗯」「好呀」这类短回应经常和她的口头禅重合，不能吃掉。
        assertFalse(EchoGuard.isSelfEcho("嗯嗯", listOf("嗯嗯")))
        assertFalse(EchoGuard.isSelfEcho("好呀", listOf("好呀")))
    }

    @Test fun partialTailIsEcho() {
        // 只收到后半句时编辑距离不够，但片段被完整包含仍判回声。
        assertTrue(EchoGuard.isSelfEcho("是不是就不像啦", listOf("那我这么说，是不是就不像啦？")))
    }

    @Test fun voiceReplyTextEntersTheBaseline() {
        // 免提的语音回复走 MediaPlayer，同样要把朗读文本记进基准：播完立刻收到同一句必须判回声。
        val baseline = SpokenBaseline()
        baseline.remember("我到楼下了，你慢慢来")
        assertTrue(baseline.isEcho("我到楼下了，你慢慢来"))
        assertTrue(baseline.isEcho("我到楼下了你慢慢来"))
        assertFalse(baseline.isEcho("帮我打开相机"))
    }
}
