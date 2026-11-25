package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.xiaoman.app.appearance.AppearanceSettings
import dev.xiaoman.app.appearance.ThemeMode
import dev.xiaoman.app.persona.Expression
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.voice.AudioPlayer
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi 截图回归（JVM，免真机）。
 *
 * 基线：`app/src/test/snapshots/images/`；失败 diff：`app/build/reports/paparazzi/`（gradle 输出目录在
 * `$XIAOMAN_BUILD_DIR/app` 时相应替换前缀）。
 * 重新生成基线：`./scripts/gradle.sh --online :app:recordPaparazziDebug`；只校验：`:app:verifyPaparazziDebug`。
 *
 * 说明：四个主屏目前都直连 AppGraph（含 SharedPreferences / 启动器），Paparazzi 里无法整体挂载，
 * 因此下面用「屏幕取景框 + 现有可无副作用组合的组件」拼出同构画面；它抓得到主题、字号、间距、
 * 颜色、气泡层级的变化，但看不见真机专有的系统栏、厂商字体回退与权限弹层。
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 390,
            screenHeight = 844,
            density = com.android.resources.Density.MEDIUM,
            softButtons = false,
        ),
        showSystemUi = false,
    )

    @Test fun chatLight() = shot(ThemeMode.LIGHT) { ChatFrame() }

    @Test fun chatDark() = shot(ThemeMode.DARK) { ChatFrame() }

    @Test fun chatSongLight() = shot(ThemeMode.LIGHT) { ChatFrame(showSong = true) }

    @Test fun usLight() = shot(ThemeMode.LIGHT) { UsFrame() }

    @Test fun usDark() = shot(ThemeMode.DARK) { UsFrame() }

    @Test fun settingsLight() = shot(ThemeMode.LIGHT) { SettingsFrame() }

    @Test fun settingsDark() = shot(ThemeMode.DARK) { SettingsFrame() }

    @Test fun personaLight() = shot(ThemeMode.LIGHT) { PersonaFrame() }

    @Test fun personaDark() = shot(ThemeMode.DARK) { PersonaFrame() }

    private fun shot(mode: ThemeMode, content: @Composable () -> Unit) {
        paparazzi.snapshot {
            XiaomanTheme(mode = mode) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        BottomBarFixture()
                    }
                }
            }
        }
    }

    // ---- 聊天 ----
    @Composable
    private fun ChatFrame(showSong: Boolean = false) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                onCall = {},
                name = "小满",
                presence = "在旁边",
            )
            val player = remember { AudioPlayer() }
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChatMessage(ChatItem.Assistant(1, "我刚醒。窗外在下雨，你那边呢？"), player, onOpenImage = {})
                ChatMessage(ChatItem.User(2, "刚下班，有点累。"), player, onOpenImage = {})
                ChatMessage(ChatItem.Assistant(3, "先坐会儿。雨伞在门口，别又忘了。"), player, onOpenImage = {})
                ChatMessage(ChatItem.User(4, "知道了。"), player, onOpenImage = {})
                if (showSong) {
                    ChatMessage(ChatItem.User(5, "有点累，唱两句吧。"), player, onOpenImage = {})
                    ChatMessage(ChatItem.AssistantVoice(
                        id = 6,
                        file = "song.mp3",
                        durationMs = 6000,
                        songLyrics = "今晚的月亮很安静，\n把疲惫轻轻放下去。",
                        songEmotion = "温柔",
                    ), player, onOpenImage = {})
                }
                Spacer(Modifier.weight(1f))
            }
            ComposerFixture()
        }
    }

    @Composable
    private fun ComposerFixture() {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                EntryDot(Icons.Outlined.Mood)
                Spacer(Modifier.width(6.dp))
                EntryDot(Icons.Outlined.Add)
                Spacer(Modifier.width(7.dp))
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodyMedium) },
                    shape = MaterialTheme.shapes.large,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 1,
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {}, modifier = Modifier.padding(bottom = 4.dp).size(44.dp)) { Text("↑") }
            }
        }
    }

    @Composable
    private fun EntryDot(icon: ImageVector) {
        Box(
            Modifier.padding(bottom = 3.dp).size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }

    // ---- 我们 ----
    @Composable
    private fun UsFrame() {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("她", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Section("现在") {
                Text("认识 3 天", style = MaterialTheme.typography.bodyMedium)
                Text("说过 28 次话", style = MaterialTheme.typography.bodyMedium)
                Text("现在：熟悉", style = MaterialTheme.typography.bodyMedium)
                Text("她现在：平静", style = MaterialTheme.typography.bodyMedium)
            }
            Section("一起经历过") {
                Text("你常说喜欢简单实用的东西。", style = MaterialTheme.typography.bodyMedium)
                Text("周末习惯晚起。", style = MaterialTheme.typography.bodyMedium)
            }
            Section("还没聊完的") {
                Text("换季买外套", style = MaterialTheme.typography.bodyMedium)
                Text("周末去西湖", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                content()
            }
        }
    }

    // ---- 设置 ----
    @Composable
    private fun SettingsFrame() {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("满", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("小满", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "她的档案、照片、语气和声音",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GroupLabel("她")
            SettingRow("亮暗与背景", "跟随系统")
            GroupLabel("她的生活")
            SettingRow("她会做的事", "4 项")
            SettingRow("外部服务", "3 个")
            GroupLabel("你们")
            SettingRow("她怎么回你", "文字")
            GroupLabel("系统")
            SettingRow("模型与接口", "openai-compatible")
        }
    }

    @Composable
    private fun GroupLabel(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    @Composable
    private fun SettingRow(label: String, value: String) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp))
        }
    }

    // ---- 小满 ----
    @Composable
    private fun PersonaFrame() {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("小满", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("住在你手机里的她", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Section("名字与称呼") {
                Text("她叫小满，叫你「你」", style = MaterialTheme.typography.bodyMedium)
            }
            Section("距离感") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Expression.entries.forEach { e ->
                        FilterChip(selected = e == Expression.entries.first(), onClick = {}, label = { Text(e.label) })
                    }
                }
                Text("克制、不过度亲昵；不替你做决定。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("声音") {
                Text("温软 · 女 · 默认", style = MaterialTheme.typography.bodyMedium)
                Text("朗读、语音消息和免提通话都用这个声音。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("她最近") {
                Text("住在杭州；喜欢散步和旧书店。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun BottomBarFixture() {
        NavigationBar(modifier = Modifier.height(62.dp), windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
            listOf(
                Icons.Outlined.FavoriteBorder to "聊天",
                Icons.Outlined.AutoAwesome to "记忆",
                Icons.Outlined.Person to "她",
                Icons.Outlined.Settings to "设置",
            ).forEachIndexed { i, (icon, label) ->
                NavigationBarItem(
                    selected = i == 0,
                    onClick = {},
                    icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}
