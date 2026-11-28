package dev.lily.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 送出一条消息时的那一下「呼」。
 *
 * 用 SoundPool 而不是 MediaPlayer：90ms 的短音，按下就要响，不能等加载。
 * 系统静音或媒体音量是 0 的时候自然听不见，不需要额外判断 —— 这是用户的选择。
 */
class ChatSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val whoosh = pool.load(context, R.raw.send_whoosh, 1)

    fun sent() {
        runCatching { pool.play(whoosh, 0.55f, 0.55f, 1, 0, 1f) }
    }
}
