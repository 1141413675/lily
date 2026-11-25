package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import dev.xiaoman.app.appearance.ThemeMode
import org.junit.Rule
import org.junit.Test

class BottomBarScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 88,
            density = Density.MEDIUM,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun feedSelectedLight() = shot(Tab.FEED, ThemeMode.LIGHT)

    @Test fun chatSelectedLight() = shot(Tab.CHAT, ThemeMode.LIGHT)

    @Test fun meSelectedDark() = shot(Tab.ME, ThemeMode.DARK)

    private fun shot(selected: Tab, mode: ThemeMode) {
        paparazzi.snapshot {
            XiaomanTheme(mode = mode) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        BottomBar(current = selected, onSelect = {})
                    }
                }
            }
        }
    }
}
