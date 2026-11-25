package dev.xiaoman.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.R
import dev.xiaoman.app.appearance.AppearanceSettings
import dev.xiaoman.app.appearance.ChatBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 聊天背景：上传本地图片、暗化 0–70%、模糊 0–14。
 * 图片只复制一份到应用私有目录，换图后旧文件删除。
 */
@Composable
fun ChatBackgroundSection(appearance: AppearanceSettings, onChange: (AppearanceSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "chat-bg").apply { mkdirs() }
                    val target = File(dir, "bg-${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    appearance.imagePath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                    target.absolutePath
                }.getOrNull()
            }
            if (path != null) {
                onChange(appearance.copy(imagePath = path, dim = maxOf(appearance.dim, 35), blur = maxOf(appearance.blur, 6)))
                msg = context.getString(R.string.appearance_image_ready)
            } else {
                msg = context.getString(R.string.appearance_image_failed)
            }
        }
    }

    val palette = LocalXiaomanPalette.current
    SettingsGroup("聊天预览") {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg))
                .border(1.dp, palette.line, RoundedCornerShape(Radius.lg)),
        ) {
            Box(Modifier.matchParentSize()) { ChatBackgroundLayer(appearance) }
            Column(
                Modifier.fillMaxWidth().padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(Radius.md),
                    color = palette.bubbleUser,
                ) {
                    Text(
                        "今天过得怎么样？",
                        Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.bubbleUserInk,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(Radius.md),
                    color = palette.surface,
                ) {
                    Text(
                        "挺好的，刚好想和你说说。",
                        Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink,
                    )
                }
            }
        }
    }
    SettingsGroup("内置背景") {
        SettingsNote(stringResource(R.string.appearance_builtin_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ChatBackdrop.entries.forEach { item ->
                val selected = appearance.backdrop == item
                // 未选中也给一档表面色 + 极细边，白底上不塌；选中才用实心主色。
                Surface(
                    onClick = { onChange(appearance.copy(backdrop = item)) },
                    shape = RoundedCornerShape(Radius.pill),
                    color = if (selected) palette.accent else palette.surface2,
                    border = if (selected) null else BorderStroke(1.dp, palette.line2),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Box(Modifier.padding(horizontal = Space.sm, vertical = Space.xs), contentAlignment = Alignment.Center) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) palette.accentInk else palette.ink,
                        )
                    }
                }
            }
        }
    }
    SettingsGroup("自定义背景") {
        SettingsNote(stringResource(R.string.appearance_bg_hint))
        // 上传背景是次级动作，走 tonal，不再和上面的选中胶囊抢主按钮。
        Button(
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accentSoft,
                contentColor = palette.accentOnSoft,
            ),
        ) {
            Text(stringResource(R.string.appearance_image_choose))
        }
        SettingsNote(
            if (appearance.hasImage) stringResource(R.string.appearance_current_image) else "现在：${appearance.backdrop.label}",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                stringResource(R.string.appearance_dim),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalXiaomanPalette.current.ink,
                modifier = Modifier.weight(1f),
            )
            SettingsValue("${appearance.dim}%")
        }
        AppearanceSlider(
            value = appearance.dim.toFloat(),
            valueRange = 0f..70f,
            onValueChange = { onChange(appearance.copy(dim = it.toInt().coerceIn(0, 70))) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                stringResource(R.string.appearance_blur),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalXiaomanPalette.current.ink,
                modifier = Modifier.weight(1f),
            )
            SettingsValue("${appearance.blur}px")
        }
        AppearanceSlider(
            value = appearance.blur.toFloat(),
            valueRange = 0f..14f,
            onValueChange = { onChange(appearance.copy(blur = it.toInt().coerceIn(0, 14))) },
        )
        if (appearance.hasImage) {
            TextButton(
                onClick = {
                    File(appearance.imagePath).delete()
                    onChange(appearance.copy(imagePath = "", dim = 0, blur = 0))
                },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalXiaomanPalette.current.ink2),
            ) { Text(stringResource(R.string.appearance_image_remove)) }
        }
        msg?.let { SettingsNote(it) }
        SettingsNote(stringResource(R.string.appearance_contrast_note))
    }
}

/**
 * 背景调节滑块：圆点 thumb（原来的竖条 thumb 像刻度），轨道只留一档柔紫填充，
 * 未填充段用 line2，亮暗两套都不出现多余刻线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = palette.accent,
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.line2,
            activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
            inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        // 圆点外留一圈表面色，压住轨道边缘，看起来像按键而不是刻度。
        thumb = {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(palette.accent)
                    .border(2.dp, palette.surface, CircleShape),
            )
        },
    )
}
