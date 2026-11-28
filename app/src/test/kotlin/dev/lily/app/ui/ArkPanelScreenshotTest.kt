package dev.lily.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.lily.app.appearance.ThemeMode
import dev.lily.app.settings.SettingsForm
import org.junit.Rule
import org.junit.Test

/**
 * 「火山方舟一体」面板的截图回归。
 *
 * 这个面板的信息密度高：一个 Key、四个开关、每个开关后面跟着一个带价格的模型选择。
 * 截图回归能抓住「价格不见了」「开关挤在一起」这类改动——单测看不出来。
 *
 * 基线：`app/src/test/snapshots/images/`；重新生成：`./scripts/gradle.sh --online :app:recordPaparazziDebug`。
 */
class ArkPanelScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            // 面板比一屏长，用更高的取景框把「模型 + 价格」那部分一起拍进来。
            screenHeight = 1200,
            density = com.android.resources.Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    /** 还没填 Key：只该看到说明与输入框，不该冒出开关。 */
    @Test fun empty() = shot {
        ArkSection(SettingsForm.ArkForm(), onChange = {})
    }

    /** 填了 Key 但一项都没开：开关都露出来，模型选择还没出现。 */
    @Test fun keyOnly() = shot {
        ArkSection(SettingsForm.ArkForm(apiKey = "ark-demo-key"), onChange = {})
    }

    /** 开了聊天与发图：模型选择与价格都在。 */
    @Test fun withModels() = shot {
        ArkSection(
            SettingsForm.ArkForm(
                apiKey = "ark-demo-key",
                useChat = true,
                useImage = true,
                chatModel = "doubao-seed-2-1-pro-260915",
                imageModel = "doubao-seedream-5-0-flash-260915",
            ),
            onChange = {},
        )
    }

    /** 开了语音又填了方舟的 Key：应当提醒它调不了豆包语音。 */
    @Test fun arkKeyWithVoice() = shot {
        ArkSection(
            SettingsForm.ArkForm(
                apiKey = "ark-demo-key",
                useChat = true,
                useVoice = true,
            ),
            onChange = {},
        )
    }

    private fun shot(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            LilyTheme(mode = ThemeMode.LIGHT) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) { content() }
                }
            }
        }
    }
}
