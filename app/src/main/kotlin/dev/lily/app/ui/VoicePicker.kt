package dev.lily.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lily.app.persona.VoiceOption

/**
 * 音色选择器：搜索 + 分段 + 三列网格。
 *
 * 原来 90 个音色逐行铺开，每行还带原始 voice id、一个一模一样的「豆包 2.0」，
 * 再加一个「听一下」文字按钮 —— 要滚八屏才能看完，而且只能硬翻。
 *
 * 现在：
 * - 名字只留人看得懂的部分（去掉原始 id、去掉整组共有的「 2.0」后缀）；
 * - 整组都一样的说明文字直接不显示（豆包 90 个全是「豆包 2.0」，等于没说）；
 * - 三列网格铺开，配搜索框和「女声 / 男声 / 克隆」分段；
 * - 试听在每格下面的小喇叭，正在播放的那格用三个跳动的点表示，不写「试听中」。
 */
internal data class PickerVoice(val id: String, val name: String, val desc: String?)

private enum class VoiceTab { FEMALE, MALE, CLONE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoicePicker(
    female: List<VoiceOption>,
    male: List<VoiceOption>,
    clones: List<PickerVoice>,
    current: String,
    currentName: String,
    currentSub: String,
    previewing: String?,
    configured: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onNeedTts: () -> Unit,
    avatar: (@Composable () -> Unit)? = null,
) {
    val palette = LocalLilyPalette.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(VoiceTab.FEMALE) }

    val femaleVoices = remember(female) { female.toPickerVoices() }
    val maleVoices = remember(male) { male.toPickerVoices() }
    val all = remember(femaleVoices, maleVoices, clones) { femaleVoices + maleVoices + clones }

    val q = query.trim()
    // 搜索时跨性别找：想要「御姐」就打两个字，不用先想它在哪一段。
    val searching = q.isNotEmpty()
    val shown = if (searching) {
        all.searchVoices(q)
    } else {
        when (tab) {
            VoiceTab.FEMALE -> femaleVoices
            VoiceTab.MALE -> maleVoices
            VoiceTab.CLONE -> clones
        }
    }
    // 说明文字整组都一样就没有信息量（豆包 90 个全是「豆包 2.0」）。
    val showDesc = shown.hasDistinctDescriptions()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        // 当前音色：抬成一张大卡，一眼看到现在用的是什么。
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surfaceLow,
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                avatar?.let { Box(Modifier.size(52.dp)) { it() } }
                Column(Modifier.weight(1f).padding(start = if (avatar == null) 0.dp else Space.md)) {
                    Text(
                        currentName,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        currentSub,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(palette.accent)
                        .clickable {
                            if (!configured) onNeedTts() else onPreview(current)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewing == current) {
                        PulsingDots(
                            color = palette.accentInk,
                            dotSize = 5.dp,
                            gap = 3.dp,
                            description = "正在试听",
                        )
                    } else {
                        Icon(
                            Icons.Outlined.VolumeUp,
                            contentDescription = "试听当前音色",
                            tint = palette.accentInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索音色，比如「御姐」", style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            shape = RoundedCornerShape(Radius.sm),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.surface,
                unfocusedContainerColor = palette.surface,
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.line,
                cursorColor = palette.accent,
                focusedTextColor = palette.ink,
                unfocusedTextColor = palette.ink,
            ),
        )

        // 分段：不用先滚完 49 行才看得到另外 41 行。
        if (!searching) {
            VoiceTabs(tab, clones.isNotEmpty()) { tab = it }
        }

        if (shown.isEmpty()) {
            Text(
                "没有叫「$q」的音色",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink2,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                shown.forEach { voice ->
                    VoiceTile(
                        voice = voice,
                        selected = voice.id == current,
                        previewing = previewing == voice.id,
                        showDesc = showDesc,
                        onSelect = { onSelect(voice.id) },
                        onPreview = {
                            if (!configured) onNeedTts() else onPreview(voice.id)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 女声 / 男声 / 克隆 三段。 */
@Composable
private fun VoiceTabs(current: VoiceTab, hasClones: Boolean, onChange: (VoiceTab) -> Unit) {
    val palette = LocalLilyPalette.current
    val tabs = buildList {
        add(VoiceTab.FEMALE to "女声")
        add(VoiceTab.MALE to "男声")
        if (hasClones) add(VoiceTab.CLONE to "克隆")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.pill)).background(palette.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (value, label) ->
            val on = value == current
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.pill))
                    .background(if (on) palette.surface else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) palette.accent else palette.ink2,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 一格音色：只显示名字，选中态用 accentSoft 底 + accent 描边。 */
@Composable
private fun VoiceTile(
    voice: PickerVoice,
    selected: Boolean,
    previewing: Boolean,
    showDesc: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLilyPalette.current
    Surface(
        modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(Radius.md),
        color = if (selected) palette.accentSoft else palette.surfaceLow,
        border = if (selected) BorderStroke(1.5.dp, palette.accent) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().clickable { onSelect() }
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                voice.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) palette.accentOnSoft else palette.ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDesc && !voice.desc.isNullOrBlank()) {
                Text(
                    voice.desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(2.dp))
            // 试听：下面的小喇叭；正在播放就换成三个跳动的点，不写「试听中」。
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable { onPreview() },
                contentAlignment = Alignment.Center,
            ) {
                if (previewing) {
                    PulsingDots(
                        color = palette.accent,
                        dotSize = 4.dp,
                        gap = 2.5.dp,
                        description = "正在试听",
                    )
                } else {
                    Icon(
                        Icons.Outlined.VolumeUp,
                        contentDescription = "试听 ${voice.name}",
                        tint = palette.ink3,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * 原始 voice id 留着给引擎用，界面上不给人看。
 * 名字尾部整组共有的「 2.0」也去掉 —— 豆包下 90 个全带，等于没有区分度。
 */
internal fun List<VoiceOption>.toPickerVoices(): List<PickerVoice> {
    val tail = firstOrNull()?.label?.substringAfterLast(' ', "").orEmpty()
    val suffix = if (tail.isNotEmpty() && all { it.label.endsWith(" $tail") }) " $tail" else ""
    return map { PickerVoice(it.id, it.label.removeSuffix(suffix), it.desc) }
}

/** 搜索：按名字模糊匹配，忽略大小写；空查询返回全部。 */
internal fun List<PickerVoice>.searchVoices(query: String): List<PickerVoice> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.name.contains(q, ignoreCase = true) }
}

/** 说明文字整组都一样就没有信息量（豆包 90 个全是「豆包 2.0」），这种就不显示。 */
internal fun List<PickerVoice>.hasDistinctDescriptions(): Boolean =
    mapNotNull { it.desc }.distinct().size > 1
