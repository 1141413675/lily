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
 * 语音气泡的两种状态：还在合成 vs 已经好了。
 *
 * 「在准备声音…」这类句子被换成三个依次呼吸的点——这里用截图把它钉住，
 * 免得以后有人「好心」改回文字。
 */
class VoiceBubbleScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 160,
            density = Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun preparingShowsDots() = shot(ready = false)

    @Test fun readyShowsSeconds() = shot(ready = true)

    private fun shot(ready: Boolean) {
        paparazzi.snapshot {
            LilyTheme(mode = ThemeMode.LIGHT) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        VoiceStatus(ready = ready, seconds = 4, playing = false)
                        VoiceStatus(ready = ready, seconds = 4, playing = true)
                    }
                }
            }
        }
    }
}
