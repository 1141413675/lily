package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import dev.lily.app.appearance.ThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * 通话页中间那颗球的三种状态。
 *
 * ChatGPT 语音通话最值得抄的不是球本身，是「一屏只留一个主角」的逻辑：
 * 状态全靠球的节奏说，屏幕上不再出现「正在说话」「正在听你说」。
 * 这个测试保证球一直画得出来、且三种状态长得不一样。
 */
class VoiceOrbScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 760,
            density = Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun threeStates() = paparazzi.snapshot {
        LilyTheme(mode = ThemeMode.LIGHT) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    VoiceOrb(state = OrbState.Waiting, size = 140.dp, description = "正在回应")
                    VoiceOrb(state = OrbState.Listening, level = 0.5f, size = 140.dp, description = "正在听你说")
                    VoiceOrb(state = OrbState.Speaking, size = 140.dp, description = "正在说话")
                    VoiceOrb(state = OrbState.Listening, muted = true, size = 140.dp, description = "麦克风已静音")
                }
            }
        }
    }
}
