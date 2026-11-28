package dev.lily.app.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.lily.api.ApiProtocol
import dev.lily.api.ModelConfig
import dev.lily.api.ModelRole
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.appearance.ThemeMode
import dev.lily.app.settings.ImageSettings
import dev.lily.app.settings.ModelVendor
import dev.lily.app.settings.ReplyMode
import dev.lily.app.settings.SettingsForm
import dev.lily.app.settings.VoiceVendor
import dev.lily.llm.ModelCatalog
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.compose.material.icons.outlined.Accessibility
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    var trashPage by remember { mutableStateOf(false) }
    // 无障碍服务是「她能操作手机」的前提：这里给一个能直达系统开关的入口。
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(accessibilityEnabled(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessibilityOn = accessibilityEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val subpage = models || skills || services || safety ||
        appearancePage || backgroundPage || imagePage || dialoguePage || storagePage || profilePage ||
        trashPage
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
    if (trashPage) {
        SubpageEnter { TrashScreen(graph, onBack = { trashPage = false }) }
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
                        tint = LocalLilyPalette.current.ink2,
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
                    if (stored.image.enabled) "不限次数" else "已关闭",
                ) { imagePage = true }
                SettingsHomeRow("主题", Icons.Outlined.Palette, stored.appearance.mode.label) {
                    appearancePage = true
                }
                SettingsHomeRow(
                    "聊天背景", Icons.Outlined.PhotoLibrary,
                    if (stored.appearance.hasImage) "自定义背景" else stored.appearance.backdrop.label,
                ) { backgroundPage = true }
                SettingsHomeRow("最近删除", Icons.Outlined.PhotoLibrary) { trashPage = true }
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
                SettingsHomeRow("无障碍服务", Icons.Outlined.Accessibility, if (accessibilityOn) "已开启" else "未开启") {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
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
    val palette = LocalLilyPalette.current
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
    val palette = LocalLilyPalette.current
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
    val palette = LocalLilyPalette.current
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
    val palette = LocalLilyPalette.current
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
    val palette = LocalLilyPalette.current
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
        color = LocalLilyPalette.current.ink2,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 控件里的当前值：15、ink2；主色只留给每屏唯一的主强调。 */
@Composable
internal fun SettingsValue(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalLilyPalette.current.ink2,
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
                    LilySwitch(checked = stored.image.enabled) {
                        store.save(store.current.copy(image = store.current.image.copy(enabled = it)))
                    }
                },
            )
        }
        if (stored.image.enabled) {
            // 方舟一体接管发图时，模型由那边定；这里改了也会被覆盖，干脆只读。
            val arkDrivesImage = stored.unified.enabled &&
                stored.provisioning == dev.lily.app.settings.ProvisioningMode.UNIFIED &&
                stored.unified.isVolcano && stored.unified.imageModel.isNotBlank()
            SettingsGroup("图片模型") {
                OutlinedTextField(
                    value = stored.image.model,
                    onValueChange = {
                        store.save(store.current.copy(image = store.current.image.copy(model = it)))
                    },
                    enabled = !arkDrivesImage,
                    label = { Text("模型 ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (arkDrivesImage) {
                    SettingsNote("发图模型由「模型与接口」里的「火山方舟一体」接管，这里改不了。")
                } else {
                    SettingsNote("默认 ${ImageSettings.DEFAULT_MODEL}；留空也会回退到这个默认值。")
                }
            }
            SettingsGroup("费用") {
                SettingsNote("用聊天模型的方舟 Key 生成，一张约 ¥0.12。生成次数不限，费用从你的方舟账号直接扣。")
            }
            SettingsGroup("自拍") {
                SettingsRow(
                    title = "生成自拍",
                    description = "用你设的头像做参考，保持长相一致；没设头像时只发生活场景。",
                    trailing = {
                        LilySwitch(checked = stored.image.includeSelfie) {
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
            Surface(shape = RoundedCornerShape(Radius.md), color = LocalLilyPalette.current.surface2) {
                Column(Modifier.fillMaxWidth().padding(Space.lg)) {
                    Text(
                        "以前固定选的文字或语音，已经改成「看情况」；想固定的话重新选一次就好。",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                        color = LocalLilyPalette.current.ink2,
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
                    LilySwitch(checked = form.learnFromSuccess) { persist(form.copy(learnFromSuccess = it)) }
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
    val palette = LocalLilyPalette.current
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
internal fun VoiceSection(voice: SettingsForm.VoiceForm, onChange: (SettingsForm.VoiceForm) -> Unit) {
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
}

/**
 * 语音识别：挑一家，编辑这一家的配置。
 *
 * 各家配置都留着，切回来不用重填。
 */
@Composable
internal fun AsrSection(form: SettingsForm.AsrForm, onChange: (SettingsForm.AsrForm) -> Unit) {
    VoiceVendorChips(
        vendors = VoiceVendor.entries.filter { it.supportsAsr },
        current = form.vendor,
        onPick = { onChange(form.copy(vendor = it)) },
    )
    val current = form.active
    VoiceSection(current) { onChange(form.withVendor(form.vendor, it)) }
    SettingsNote(form.vendor.keyHint)
    val missing = form.toSettings().missing
    if (missing.isNotEmpty()) {
        SettingsNote(stringResource(R.string.models_asr_incomplete, missing.joinToString("、")))
    }
}

/** 语音厂商的选择按钮。 */
@Composable
internal fun VoiceVendorChips(
    vendors: List<VoiceVendor>,
    current: VoiceVendor,
    onPick: (VoiceVendor) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        vendors.forEach { vendor ->
            FilterChip(
                selected = vendor == current,
                onClick = { onPick(vendor) },
                label = { Text(vendor.displayName) },
            )
        }
    }
}
/** 模型页字段：标签单独一行放在输入框上方并留出间距，和输入框边框脱开。 */
@Composable
internal fun FieldLabel(label: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
internal fun ModelSection(
    model: SettingsForm.ModelForm,
    onChange: (SettingsForm.ModelForm) -> Unit,
    onVendor: (ModelVendor) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    var vendorMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    // 接口拉到的模型按厂商缓存；拉不到就退回内置默认，保存不依赖它。
    var fetched by remember { mutableStateOf<Map<ModelVendor, List<String>>>(emptyMap()) }
    var failed by remember { mutableStateOf<Set<ModelVendor>>(emptySet()) }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val custom = model.vendor == ModelVendor.CUSTOM
    val choices = (model.vendor.models + fetched[model.vendor].orEmpty()).distinct()

    FieldLabel("厂商") {
        OutlinedButton(onClick = { vendorMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(model.vendor.displayName, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_vendor))
        }
    }
    if (vendorMenu) {
        VendorPickerSheet(
            current = model.vendor,
            onPick = onVendor,
            onDismiss = { vendorMenu = false },
        )
    }
    FieldLabel(stringResource(R.string.settings_base_url)) {
        OutlinedTextField(
            value = model.baseUrl,
            onValueChange = { if (custom) onChange(model.copy(baseUrl = it)) },
            readOnly = !custom,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            trailingIcon = { CopyUrlButton(model.baseUrl, context) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FieldLabel(stringResource(R.string.settings_model)) {
        Box {
            OutlinedTextField(
                value = model.model,
                onValueChange = { onChange(model.copy(model = it)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { modelMenu = true }, enabled = choices.isNotEmpty()) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_model))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                choices.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            modelMenu = false
                            onChange(model.copy(model = name))
                        },
                    )
                }
            }
        }
    }
    if (custom) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilterChip(
                selected = model.protocol == ApiProtocol.OPENAI,
                onClick = { onChange(model.copy(protocol = ApiProtocol.OPENAI)) },
                label = { Text(stringResource(R.string.models_protocol_openai)) },
            )
            FilterChip(
                selected = model.protocol == ApiProtocol.ANTHROPIC,
                onClick = { onChange(model.copy(protocol = ApiProtocol.ANTHROPIC)) },
                label = { Text(stringResource(R.string.models_protocol_anthropic)) },
            )
        }
        SettingsNote(stringResource(R.string.models_protocol_hint))
    }
    FieldLabel(
        if (model.vendor.keyOptional) {
            stringResource(R.string.settings_api_key) + "（可选）"
        } else {
            stringResource(R.string.settings_api_key)
        },
    ) {
        OutlinedTextField(
            value = model.apiKey,
            onValueChange = { onChange(model.copy(apiKey = it)) },
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
    }
    if (model.vendor.keyOptional) SettingsNote(stringResource(R.string.models_key_optional))
    FieldLabel(stringResource(R.string.settings_temperature)) {
        OutlinedTextField(
            value = model.temperature,
            onValueChange = { onChange(model.copy(temperature = it)) },
            isError = !model.temperatureValid,
            supportingText = if (!model.temperatureValid) ({ Text(stringResource(R.string.settings_invalid_number)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!custom) {
        OutlinedButton(
            enabled = !fetching && (model.apiKey.isNotBlank() || model.vendor.keyOptional),
            onClick = {
                val vendor = model.vendor
                fetching = true
                scope.launch {
                    val names = ModelCatalog.list(
                        ModelConfig(
                            role = ModelRole.CHAT,
                            baseUrl = model.baseUrl,
                            model = model.model,
                            apiKey = model.apiKey,
                            protocol = model.protocol,
                        ),
                    )
                    fetched = fetched + (vendor to names)
                    failed = if (names.isEmpty()) failed + vendor else failed - vendor
                    fetching = false
                }
            },
        ) {
            Text(stringResource(if (fetching) R.string.models_fetching else R.string.models_fetch_models))
        }
    }
    if (model.vendor in failed) SettingsNote(stringResource(R.string.models_fetch_failed))
    SettingsNote(stringResource(R.string.models_key_hint, model.vendor.keyHintText))
}

/**
 * 朗读：挑一家，编辑这一家的配置。
 *
 * 各家配置都留着，切回来不用重填。
 */
@Composable
internal fun TtsSection(form: SettingsForm.TtsForm, onChange: (SettingsForm.TtsForm) -> Unit) {
    VoiceVendorChips(
        vendors = VoiceVendor.entries.filter { it.supportsTts },
        current = form.vendor,
        onPick = { onChange(form.copy(vendor = it)) },
    )
    val current = form.active
    VoiceSection(current) { onChange(form.withVendor(form.vendor, it)) }
    SettingsNote(form.vendor.keyHint)
    SettingsNote(stringResource(R.string.models_voice_hint))
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

/** 系统里是否已经勾上我们的无障碍服务。跳出去开关再回来会重新算一次。 */
private fun accessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.endsWith("LilyAccessibilityService") }
}
