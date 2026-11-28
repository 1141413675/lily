package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import dev.lily.app.appearance.ThemeMode
import dev.lily.app.session.ChatItem
import dev.lily.app.voice.AudioPlayer
import org.junit.Rule
import org.junit.Test

/**
 * 私聊里她的话没有头像：文字气泡与语音气泡贴着同一条左边线。
 *
 * 两气泡上下排在一起，看左边是否齐平；这样「文字比语音多缩进一截」的毛病不会回来。
 */
class HerBubbleAlignmentScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 200,
            density = Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun textAndVoiceShareTheSameLeftEdge() {
        paparazzi.snapshot {
            LilyTheme(mode = ThemeMode.LIGHT) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val player = remember { AudioPlayer() }
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChatMessage(
                            item = ChatItem.Assistant(1, "在呀，我刚醒。窗外在下雨，你那边呢？"),
                            player = player,
                        )
                        ChatMessage(
                            item = ChatItem.AssistantVoice(2, "", 0),
                            player = player,
                        )
                    }
                }
            }
        }
    }
}
