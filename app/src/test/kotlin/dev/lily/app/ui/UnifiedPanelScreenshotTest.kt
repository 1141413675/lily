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
import dev.lily.app.settings.ModelVendor
import dev.lily.app.settings.SettingsForm
import org.junit.Rule
import org.junit.Test

/**
 * 模型接法面板的截图回归。
 *
 * 一体化面板信息密度高：一个服务方、一个 Key、几个模型，每个模型旁边写着价格。
 * 截图回归能抓住「价格不见了」「开关挤在一起」这类改动——单测看不出来。
 *
 * 基线：`app/src/test/snapshots/images/`；重新生成：`./scripts/gradle.sh --online :app:recordPaparazziDebug`。
 */
class UnifiedPanelScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            // 面板比一屏长，用更高的取景框把「模型 + 价格」那部分一起拍进来。
            screenHeight = 1400,
            density = com.android.resources.Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    /** 还没填 Key：只该看到服务方与输入框。 */
    @Test fun empty() = shot {
        UnifiedSection(SettingsForm.UnifiedForm(), onChange = {})
    }

    /** 填了 Key、挑了模型：价格与估算都在。 */
    @Test fun withModels() = shot {
        UnifiedSection(
            SettingsForm.UnifiedForm(
                vendor = ModelVendor.ARK,
                apiKey = "ark-demo-key",
                chatModel = "doubao-seed-2-1-pro-260915",
                operatorModel = "doubao-seed-2-1-lite-260915",
                imageModel = "doubao-seedream-5-0-flash-260915",
            ),
            onChange = {},
        )
    }

    /** 换了别家：没有生图模型，语音也不归它。 */
    @Test fun anotherVendor() = shot {
        UnifiedSection(
            SettingsForm.UnifiedForm(
                vendor = ModelVendor.DEEPSEEK,
                apiKey = "sk-demo",
                chatModel = "deepseek-chat",
                operatorModel = "deepseek-reasoner",
            ),
            onChange = {},
        )
    }

    /** 两种接法的选择。 */
    @Test fun provisioningModes() = shot {
        ProvisioningSection(dev.lily.app.settings.ProvisioningMode.UNIFIED) {}
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
