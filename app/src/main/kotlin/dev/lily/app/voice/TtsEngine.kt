package dev.lily.app.voice

import android.util.Log
import dev.lily.app.settings.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 聊天语音气泡与通话朗读共用的分句合成：按标点切句，逐句请求，边合成边交给播放方。
 * 第一句的请求最先发出，后面的句子在当前句播放时已经在后台合成，所以首包只等一句。
 *
 * 一条回复只用一个表演指令（[TtsScript.performance]），避免逐句换语气。
 */
class TtsEngine(private val api: VoiceApi = VoiceApi()) {

    /**
     * 逐句合成 [text] 并把每句的 mp3 交给 [onClip]（从 0 开始编号）。[onClip] 返回后播放下一句，
     * 生产端始终把后续句子提前送进缓冲。返回实际合成的句数。
     *
     * 抛出的异常是接口/网络错误；调用方负责降级成文字。
     */
    suspend fun stream(
        config: VoiceSettings,
        text: String,
        instruction: String = TtsScript.performance(text),
        fallback: VoiceSettings? = null,
        onClip: suspend (index: Int, bytes: ByteArray) -> Unit,
    ): Int = coroutineScope {
        val sentences = TtsScript.sentences(TtsScript.clean(text)).toMutableList()
        if (sentences.isEmpty()) return@coroutineScope 0
        // 明显在笑/自嘲时，第一句前加一次富文本标签；其余情况完全不写。
        if (TtsScript.laughter(text)) sentences[0] = "[laughter]" + sentences[0]
        TtsScript.directionLeak(instruction)?.let {
            Log.w(TAG, "tts: instruction still has direction wording <$it>")
        }
        val startedAt = System.nanoTime()
        var firstClipAt = 0L
        var failure: Throwable? = null
        val clips = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = launch(Dispatchers.IO) {
            try {
                for (raw in sentences) {
                    // 出口校验：送去合成的正文只保留能念的话，指令措辞一句都不许混进来。
                    val sentence = TtsScript.withoutDirection(raw)
                    if (sentence != raw) {
                        Log.w(TAG, "tts: stripped direction wording <${TtsScript.directionLeak(raw)}> before synthesis")
                    }
                    if (!TtsScript.readable(sentence)) continue
                    // 豆包单句失败时用硅基兜底，避免整通电话静音；两句都失败才向上抛。
                    val bytes = api.speak(config, sentence, instruction, fallback)
                    checkDuration(sentence, bytes, config)
                    clips.send(bytes)
                }
            } catch (e: CancellationException) {
                clips.close(e)
                throw e
            } catch (e: Exception) {
                // 记下来交给消费端重抛：只 close 通道会把失败吞掉，通话会变成无声但不报错。
                failure = e
                clips.close(e)
            } finally {
                clips.close()
            }
        }
        var count = 0
        try {
            for (bytes in clips) {
                if (count == 0) firstClipAt = System.nanoTime()
                onClip(count++, bytes)
            }
        } finally {
            clips.cancel()
            producer.cancel()
            val total = (System.nanoTime() - startedAt) / 1_000_000
            val first = if (firstClipAt == 0L) total else (firstClipAt - startedAt) / 1_000_000
            Log.i(TAG, "tts: sentences=${sentences.size} first=${first}ms played=${total}ms chars=${TtsScript.clean(text).length}")
        }
        producer.join()
        failure?.let { throw it }
        count
    }

    /**
     * 出口异常检测：一段音频的时长明显长于这句正文的预估，通常意味着模型把指令或额外内容也念了。
     * 只记日志并打上 `tts anomaly` 标记，不打断播放，方便从日志里定位漏念的指令。
     */
    private fun checkDuration(sentence: String, bytes: ByteArray, config: VoiceSettings) {
        val chars = sentence.count { it.isLetterOrDigit() }
        val expected = TtsScript.expectedSpeechMs(chars, config.speed)
        val actual = AudioStore.mp3DurationMs(bytes)
        if (actual > 0 && actual > expected * 2 + 1_500) {
            Log.w(TAG, "tts anomaly: clip=${actual}ms expected=${expected}ms chars=$chars text=<${sentence.take(30)}>")
        }
    }

    private companion object {
        const val TAG = "Lily"
    }
}
