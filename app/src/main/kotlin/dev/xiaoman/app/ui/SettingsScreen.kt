package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.appearance.ThemeMode
import dev.xiaoman.app.settings.ImageSettings
import dev.xiaoman.app.settings.ReplyMode
import dev.xiaoman.app.settings.SettingsForm

/** 「我的」汇集个人资料和全局设置。 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    onBack: (() -> Unit)? = null,
    /** 二级页打开时外层收起底栏，回到设置首页再出现。 */
    onSubpageChange: (Boolean) -> Unit = {},
) {
    var safety by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    var appearancePage by remember { mutableStateOf(false) }
    var backgroundPage by remember { mutableStateOf(false) }
    var imagePage by remember { mutableStateOf(false) }
    var dialoguePage by remember { mutableStateOf(false) }
    var storagePage by remember { mutableStateOf(false) }
    var profilePage by remember { mutableStateOf(false) }
    val subpage = models || skills || services || safety ||
        appearancePage || backgroundPage || imagePage || dialoguePage || storagePage || profilePage
    LaunchedEffect(subpage) { onSubpageChange(subpage) }
    DisposableEffect(Unit) { onDispose { onSubpageChange(false) } }
    if (models) {
        SubpageEnter { ModelsScreen(graph, onBack = { models = false }) }
        return
    }
    if (skills) {
        SubpageEnter { SkillsScreen(graph, onBack = { skills = false }) }
        return
    }
    if (services) {
        SubpageEnter { McpScreen(graph, onBack = { services = false }) }
        return
    }
    if (safety) {
        SubpageEnter { SafetyScreen(graph, onBack = { safety = false }) }
        return
    }
    if (appearancePage) {
        SubpageEnter { AppearanceScreen(graph, onBack = { appearancePage = false }) }
        return
    }
    if (backgroundPage) {
        SubpageEnter { ChatBackgroundScreen(graph, onBack = { backgroundPage = false }) }
        return
    }
    if (imagePage) {
        SubpageEnter { ImageSettingsScreen(graph, onBack = { imagePage = false }) }
        return
    }
    if (dialoguePage) {
        SubpageEnter { DialogueScreen(graph, onBack = { dialoguePage = false }) }
        return
    }
    if (storagePage) {
        SubpageEnter { StorageScreen(graph, onBack = { storagePage = false }) }
        return
    }
    if (profilePage) {
        SubpageEnter { MyProfileScreen(graph, onBack = { profilePage = false }) }
        return
    }
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    Column(Modifier.fillMaxSize()) {
        // 返回与标题固定在顶部，长列表滚到底也还能返回。
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = LocalXiaomanPalette.current.ink2,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
            }
            Text(stringResource(R.string.nav_me), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            LilyMark()
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.xl, top = Space.sm, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            MyProfileSection(graph, onOpen = { profilePage = true })

            SettingsHomeGroup("聊天与外观") {
                SettingsHomeRow(
                    stringResource(R.string.settings_group_dialog), Icons.AutoMirrored.Outlined.Chat,
                    stringResource(stored.replyMode.label()),
                ) { dialoguePage = true }
                SettingsHomeRow(
                    "角色发图", Icons.Outlined.Image,
                    if (stored.image.enabled) "每天最多 ${stored.image.dailyLimit} 张" else "已关闭",
                ) { imagePage = true }
                SettingsHomeRow("主题", Icons.Outlined.Palette, stored.appearance.mode.label) {
                    appearancePage = true
                }
                SettingsHomeRow(
                    "聊天背景", Icons.Outlined.PhotoLibrary,
                    if (stored.appearance.hasImage) "自定义背景" else stored.appearance.backdrop.label,
                ) { backgroundPage = true }
            }
            SettingsHomeGroup("高级设置") {
                SettingsHomeRow(
                    stringResource(R.string.settings_models_entry), Icons.Outlined.Tune,
                    if (stored.chat.configured) "已配置" else "未配置",
                ) { models = true }
                SettingsHomeRow(stringResource(R.string.settings_skills_entry), Icons.Outlined.Extension) {
                    skills = true
                }
                SettingsHomeRow(stringResource(R.string.settings_services_entry), Icons.Outlined.Hub) {
                    services = true
                }
                SettingsHomeRow(stringResource(R.string.settings_safety_entry), Icons.Outlined.Shield) {
                    safety = true
                }
                SettingsHomeRow(stringResource(R.string.settings_group_storage), Icons.Outlined.Storage) {
                    storagePage = true
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            title,
            Modifier.padding(start = Space.sm),
            style = MaterialTheme.typography.labelMedium,
            color = palette.ink2,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surface,
        ) {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.xs), content = content)
        }
    }
}

@Composable
internal fun SettingsHomeRow(
    title: String,
    icon: ImageVector,
    value: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = SizeTokens.touchTarget)
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xs, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(IconSize.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = palette.ink3,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

/** 二级设置页的固定标题栏和可滚动内容。 */
@Composable
internal fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = Space.sm, end = Space.page, top = Space.sm, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = palette.accent,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
        }
        HorizontalDivider(color = palette.line)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, top = Space.lg, end = Space.page, bottom = Space.block),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

/** 分组标题与设置控件保持同一条左对齐线。 */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink2,
                modifier = Modifier.padding(bottom = Space.lg),
            )
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
            content = content,
        )
    }
}

/** 左侧文字可换行，右侧控件有独立间距。 */
@Composable
internal fun SettingsRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = LocalXiaomanPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink3,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
        trailing?.let { content ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                content = content,
            )
        }
    }
}

/** 辅助说明保持正文的换行和对比度。 */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalXiaomanPalette.current.ink2,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 控件里的当前值：15、ink2；主色只留给每屏唯一的主强调。 */
@Composable
internal fun SettingsValue(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalXiaomanPalette.current.ink2,
        modifier = modifier,
    )
}

@Composable
private fun AppearanceScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    SettingsSubpage("主题", onBack) {
        SettingsGroup("外观模式") {
            ThemeMode.entries.forEach { mode ->
                SettingsChoice(mode.label, selected = stored.appearance.mode == mode) {
                    store.save(store.current.copy(appearance = store.current.appearance.copy(mode = mode)))
                }
            }
        }
        SettingsNote(stringResource(R.string.appearance_mode_hint))
    }
}

@Composable
private fun ChatBackgroundScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    SettingsSubpage("聊天背景", onBack) {
        ChatBackgroundSection(stored.appearance) { appearance ->
            store.save(store.current.copy(appearance = appearance))
        }
    }
}

@Composable
private fun ImageSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    SettingsSubpage("角色发图", onBack) {
        SettingsGroup("聊天发图") {
            SettingsRow(
                title = "允许角色发图",
                description = "按聊天内容生成图片，或在回忆时复用聊天里的旧图。",
                trailing = {
                    XiaomanSwitch(checked = stored.image.enabled) {
                        store.save(store.current.copy(image = store.current.image.copy(enabled = it)))
                    }
                },
            )
        }
        if (stored.image.enabled) {
            SettingsGroup("每天最多生成") {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(1, 3, 5).forEach { limit ->
                        FilterChip(
                            selected = stored.image.dailyLimit == limit,
                            onClick = {
                                store.save(store.current.copy(image = store.current.image.copy(dailyLimit = limit)))
                            },
                            label = { Text("$limit 张") },
                        )
                    }
                }
                SettingsNote("用聊天模型的方舟 Key 生成，一张约 ¥0.12。达到上限后可分享已有图片。")
            }
            SettingsGroup("图片模型") {
                OutlinedTextField(
                    value = stored.image.model,
                    onValueChange = {
                        store.save(store.current.copy(image = store.current.image.copy(model = it)))
                    },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsNote("默认 ${ImageSettings.DEFAULT_MODEL}；留空也会回退到这个默认值。")
            }
            SettingsGroup("自拍") {
                SettingsRow(
                    title = "生成自拍",
                    description = "用你设的头像做参考，保持长相一致；没设头像时只发生活场景。",
                    trailing = {
                        XiaomanSwitch(checked = stored.image.includeSelfie) {
                            store.save(store.current.copy(image = store.current.image.copy(includeSelfie = it)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DialogueScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf(SettingsForm.of(stored)) }
    // 和设置首页一致：改完即时落盘，数字不合法时只提示不保存。
    fun persist(next: SettingsForm) {
        form = next
        next.toSettings()?.let { p ->
            store.save(
                store.current.copy(
                    replyMode = next.replyMode,
                    replyModeChosen = next.replyModeChosen,
                    replyModeNotice = next.replyModeNotice,
                    maxSteps = p.maxSteps,
                    learnFromSuccess = next.learnFromSuccess,
                ),
            )
        }
    }
    SettingsSubpage(stringResource(R.string.settings_group_dialog), onBack) {
        if (form.replyModeNotice) {
            Surface(shape = RoundedCornerShape(Radius.md), color = LocalXiaomanPalette.current.surface2) {
                Column(Modifier.fillMaxWidth().padding(Space.lg)) {
                    Text(
                        "以前固定选的文字或语音，已经改成「看情况」；想固定的话重新选一次就好。",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                        color = LocalXiaomanPalette.current.ink2,
                    )
                    TextButton(
                        onClick = { persist(form.copy(replyModeNotice = false)) },
                        modifier = Modifier.align(Alignment.End).tapTarget(),
                    ) {
                        Text("知道了", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        SettingsGroup("回复方式") {
            ReplyModeSection(form.replyMode) {
                persist(form.copy(replyMode = it, replyModeChosen = true, replyModeNotice = false))
            }
        }
        SettingsGroup("主动操作") {
            OutlinedTextField(
                value = form.maxSteps,
                onValueChange = { persist(form.copy(maxSteps = it)) },
                label = { Text(stringResource(R.string.settings_max_steps)) },
                isError = !form.maxStepsValid,
                supportingText = if (!form.maxStepsValid) ({ Text(stringResource(R.string.settings_invalid_number)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsRow(
                title = stringResource(R.string.settings_learn),
                trailing = {
                    XiaomanSwitch(checked = form.learnFromSuccess) { persist(form.copy(learnFromSuccess = it)) }
                },
            )
        }
    }
}

@Composable
private fun StorageScreen(graph: AppGraph, onBack: () -> Unit) {
    SettingsSubpage(stringResource(R.string.settings_group_storage), onBack) {
        SettingsGroup("目录") {
            SettingsRow("技能", graph.skillsDir.path)
            SettingsRow("记忆", graph.memoryDir.path)
            SettingsRow("运行记录", graph.tracesDir.path)
        }
        SettingsNote("这些目录都在应用私有空间，覆盖安装不会清空；图片按张数、体积与天数自动回收。")
    }
}

@Composable
private fun ReplyModeSection(value: ReplyMode, onChange: (ReplyMode) -> Unit) {
    ReplyMode.entries.forEach { mode ->
        SettingsChoice(
            title = stringResource(mode.label()),
            description = stringResource(mode.description()),
            selected = value == mode,
            onSelect = { onChange(mode) },
        )
    }
}

@Composable
private fun SettingsChoice(title: String, selected: Boolean, description: String? = null, onSelect: () -> Unit) {
    val palette = LocalXiaomanPalette.current
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = if (selected) palette.accentSoft else palette.surface,
        border = BorderStroke(1.dp, if (selected) palette.accent.copy(alpha = .35f) else palette.line),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .heightIn(min = 52.dp)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) palette.accentOnSoft else palette.ink,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink2,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private fun ReplyMode.label() = when (this) {
    ReplyMode.AUTO -> R.string.settings_reply_auto
    ReplyMode.TEXT -> R.string.settings_reply_text
    ReplyMode.VOICE -> R.string.settings_reply_voice
    ReplyMode.CALL -> R.string.settings_reply_call
}

private fun ReplyMode.description() = when (this) {
    ReplyMode.AUTO -> R.string.settings_reply_auto_desc
    ReplyMode.TEXT -> R.string.settings_reply_text_desc
    ReplyMode.VOICE -> R.string.settings_reply_voice_desc
    ReplyMode.CALL -> R.string.settings_reply_call_desc
}

@Composable
internal fun VoiceSection(voice: SettingsForm.VoiceForm, showVoice: Boolean, onChange: (SettingsForm.VoiceForm) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedTextField(
        value = voice.baseUrl,
        onValueChange = { onChange(voice.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        trailingIcon = { CopyUrlButton(voice.baseUrl, context) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = voice.model,
        onValueChange = { onChange(voice.copy(model = it)) },
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = voice.apiKey,
        onValueChange = { onChange(voice.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (showVoice) {
        OutlinedTextField(
            value = voice.voice,
            onValueChange = { onChange(voice.copy(voice = it)) },
            label = { Text(stringResource(R.string.settings_voice_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ModelSection(model: SettingsForm.ModelForm, onChange: (SettingsForm.ModelForm) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedTextField(
        value = model.baseUrl,
        onValueChange = { onChange(model.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        trailingIcon = { CopyUrlButton(model.baseUrl, context) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.model,
        onValueChange = { onChange(model.copy(model = it)) },
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.apiKey,
        onValueChange = { onChange(model.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.temperature,
        onValueChange = { onChange(model.copy(temperature = it)) },
        label = { Text(stringResource(R.string.settings_temperature)) },
        isError = !model.temperatureValid,
        supportingText = if (!model.temperatureValid) ({ Text(stringResource(R.string.settings_invalid_number)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 单行 URL 显示不下时，提供一个复制完整地址的入口。 */
@Composable
private fun CopyUrlButton(url: String, context: android.content.Context) {
    IconButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("地址", url))
        Toast.makeText(context, "已复制完整地址", Toast.LENGTH_SHORT).show()
    }) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "复制完整地址",
            modifier = Modifier.size(IconSize.md),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
