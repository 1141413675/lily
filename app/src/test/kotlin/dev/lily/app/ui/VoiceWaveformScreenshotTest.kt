package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import dev.lily.app.appearance.ThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * 语音气泡的波纹图标：**两侧必须长得一模一样**。
 *
 * 早先两侧各用各的方向（镜像），于是我的那条图标被甩到屏幕最右端、声波朝屏幕外指，
 * 像坏了；她的那条又朝另一边。微信的做法是两侧都用同一样式，
 * 只靠气泡靠哪边、尾角在哪边来区分谁说的。
 *
 * 这里把两条并排画出来钉住：图标都在左边、声波都朝右。
 */
class VoiceWaveformScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 220,
            density = Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun bothSidesLookTheSame() = paparazzi.snapshot {
        LilyTheme(mode = ThemeMode.LIGHT) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 她的语音：整条靠左。
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Start) {
                        VoiceWaveform(playing = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        VoiceStatus(ready = true, seconds = 2, playing = false)
                    }
                    // 我的语音：整条靠右。图标和秒数的位置关系不变。
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                        VoiceWaveform(playing = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        VoiceStatus(ready = true, seconds = 2, playing = false)
                    }
                    // 播放中：波纹依次亮起。
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
                        VoiceWaveform(playing = true, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        VoiceStatus(ready = true, seconds = 2, playing = true)
                    }
                }
            }
        }
    }
}
