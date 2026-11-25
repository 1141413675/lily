package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.appearance.ThemeMode
import dev.xiaoman.app.settings.ImageSettings
import dev.xiaoman.app.settings.ReplyMode
import dev.xiaoman.app.settings.SettingsForm

/**
 * 「我的」：顶部是用户本人档案（头像、昵称、分组字段），下面保留她的设置与系统入口；
 * 其余按主题拆成大小不一的 bento 卡片，点卡片原地 3D 翻转露出编辑面板，改完翻回即时生效。
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    onBack: (() -> Unit)? = null,
    /** 从动态里的「记忆」跳转：直接打开记忆二级页。 */
    openMemoryPermalink: String? = null,
    onOpenMemoryConsumed: () -> Unit = {},
    /** 二级页打开时外层收起底栏，回到设置首页再出现。 */
    onSubpageChange: (Boolean) -> Unit = {},
) {
    var relation by remember { mutableStateOf(false) }
    var safety by remember { mutableStateOf(false) }
    var persona by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf(false) }
    var voiceClone by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    var dream by remember { mutableStateOf(false) }
    var memory by remember { mutableStateOf(false) }
    var memoryFocus by remember { mutableStateOf<String?>(null) }
    var appearancePage by remember { mutableStateOf(false) }
    var backgroundPage by remember { mutableStateOf(false) }
    var imagePage by remember { mutableStateOf(false) }
    var dialoguePage by remember { mutableStateOf(false) }
    var storagePage by remember { mutableStateOf(false) }
    var profilePage by remember { mutableStateOf(false) }
    var callRecords by remember { mutableStateOf(false) }
    val subpage = voiceClone || models || dream || relation || persona || skills || services || safety ||
        memory || appearancePage || backgroundPage || imagePage || dialoguePage || storagePage || profilePage ||
        callRecords
    LaunchedEffect(subpage) { onSubpageChange(subpage) }
    DisposableEffect(Unit) { onDispose { onSubpageChange(false) } }
    LaunchedEffect(openMemoryPermalink) {
        if (openMemoryPermalink != null) {
            memoryFocus = openMemoryPermalink
            memory = true
            onOpenMemoryConsumed()
        }
    }
    // 「我们」页空状态的「去填她的生活」：直接落到「她是谁」。
    LaunchedEffect(Unit) {
        graph.openPersona.collect { requested ->
            if (requested) {
                persona = true
                graph.openPersona.value = false
            }
        }
    }
    // 夜里从记忆页进入时，返回要回到记忆页；从设置卡进入则回设置首页。
    if (dream) {
        SubpageEnter { DreamScreen(graph, onBack = { dream = false }) }
        return
    }
    if (memory) {
        SubpageEnter {
            MemoryScreen(
                graph,
                focusPermalink = memoryFocus,
                onFocusConsumed = { memoryFocus = null },
                onBack = { memory = false },
                onOpenDream = { dream = true },
            )
        }
        return
    }
    if (voiceClone) {
        SubpageEnter { VoiceCloneScreen(graph, onBack = { voiceClone = false }) }
        return
    }
    if (models) {
        SubpageEnter { ModelsScreen(graph, onBack = { models = false }) }
        return
    }
    // 关系档案从「小满人格与声音」里进入；先判关系，返回时回到人格页。
    if (relation) {
        SubpageEnter { RelationshipScreen(graph, onBack = { relation = false }) }
        return
    }
    if (persona) {
        SubpageEnter {
            PersonaScreen(
                graph,
                onBack = { persona = false },
                onOpenVoiceClone = { voiceClone = true },
                onOpenRelation = { relation = true },
            )
        }
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
    if (callRecords) {
        SubpageEnter {
            CallRecordsScreen(
                graph = graph,
                onBack = { callRecords = false },
                onCallBack = { callRecords = false; graph.openCall.value = true },
            )
        }
        return
    }
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    val relationship by graph.relationship.state.collectAsStateWithLifecycle()
    // 夜间整理只在主页显示状态；开关与时间点进二级页改。
    val dreamConfig = remember { graph.dreamStore.config() }

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
                .padding(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // 用户本人的档案只剩一张入口卡：点头像卡进入资料页，字段统一在那一页编辑。
            MyProfileSection(graph, onOpen = { profilePage = true })
            Text(
                "她的设置",
                style = MaterialTheme.typography.labelMedium,
                color = LocalXiaomanPalette.current.ink2,
                modifier = Modifier.padding(start = Space.xs, top = Space.md),
            )
            val now = java.time.LocalDate.now()
            PersonaPreviewCard(
                graph = graph,
                name = stored.persona.nameOr(stringResource(R.string.app_name)),
                gender = stored.persona.gender.label,
                age = stored.persona.ageAt(now),
                summary = stored.persona.summary(),
                daysText = "在一起 ${relationship.daysKnown(now)} 天",
                height = BentoHeight.hero,
                onOpen = { persona = true },
            )

            BentoRow {
                BentoCard(
                    title = stringResource(R.string.appearance_section),
                    summary = stored.appearance.mode.label,
                    icon = Icons.Outlined.Palette,
                    height = BentoHeight.row,
                    modifier = Modifier.weight(1f),
                    onClick = { appearancePage = true },
                )
                BentoCard(
                    title = "记忆",
                    summary = "时间线与详情",
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    height = BentoHeight.row,
                    modifier = Modifier.weight(1f),
                    onClick = { memory = true },
                )
            }

            BentoCard(
                title = stringResource(R.string.call_records_title),
                summary = stringResource(R.string.call_records_entry_summary),
                icon = Icons.Outlined.Call,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.compact,
                onClick = { callRecords = true },
            )

            BentoCard(
                title = "发图",
                summary = if (stored.image.enabled) {
                    "每天最多 ${stored.image.dailyLimit} 张"
                } else {
                    "已关闭"
                },
                icon = Icons.Outlined.Image,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.editor,
                onClick = { imagePage = true },
            )

            BentoCard(
                title = "聊天背景",
                summary = if (stored.appearance.hasImage) {
                    "已设自定义背景 · 暗化 ${stored.appearance.dim}%"
                } else {
                    "${stored.appearance.backdrop.label} · 可换图"
                },
                icon = Icons.Outlined.PhotoLibrary,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.editor,
                onClick = { backgroundPage = true },
            )

            BentoCard(
                title = stringResource(R.string.settings_models_entry),
                summary = if (stored.chat.configured) {
                    "已配置 · ${stored.chat.model}"
                } else {
                    stringResource(R.string.settings_models_entry_hint)
                },
                icon = Icons.Outlined.Tune,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.editor,
                onClick = { models = true },
            )

            BentoRow {
                BentoCard(
                    title = "关系",
                    summary = "共同经历与边界",
                    icon = Icons.Outlined.FavoriteBorder,
                    modifier = Modifier.weight(1f),
                    height = BentoHeight.row,
                    onClick = { relation = true },
                )
                BentoCard(
                    title = stringResource(R.string.settings_dream_entry),
                    summary = if (dreamConfig.enabled) "每天 ${dreamConfig.time}" else "已关闭",
                    icon = Icons.Outlined.Nightlight,
                    modifier = Modifier.weight(1f),
                    height = BentoHeight.row,
                    onClick = { dream = true },
                )
            }

            BentoCard(
                title = stringResource(R.string.settings_safety_entry),
                summary = "隐私、导出与边界",
                icon = Icons.Outlined.Shield,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.row,
                onClick = { safety = true },
            )

            BentoRow {
                BentoCard(
                    title = stringResource(R.string.settings_skills_entry),
                    summary = "技能与自动化",
                    icon = Icons.Outlined.Extension,
                    modifier = Modifier.weight(1f),
                    height = BentoHeight.compact,
                    onClick = { skills = true },
                )
                BentoCard(
                    title = stringResource(R.string.settings_services_entry),
                    summary = "MCP 连接",
                    icon = Icons.Outlined.Hub,
                    modifier = Modifier.weight(1f),
                    height = BentoHeight.compact,
                    onClick = { services = true },
                )
            }

            BentoCard(
                title = stringResource(R.string.settings_group_dialog),
                summary = "${stringResource(stored.replyMode.label())} · 最多 ${stored.maxSteps} 步",
                icon = Icons.AutoMirrored.Outlined.Chat,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.editor,
                onClick = { dialoguePage = true },
            )

            BentoCard(
                title = stringResource(R.string.settings_group_storage),
                summary = "文件位置与清理",
                icon = Icons.Outlined.Storage,
                modifier = Modifier.fillMaxWidth(),
                height = BentoHeight.compact,
                onClick = { storagePage = true },
            )
        }
    }
}

/**
 * 设置网格只有 2 列，行高固定三档：紧凑 112、标准 128、编辑 148。
 * 跨列卡固定占满 2 列，因此宽度永远是基准列的整数倍；同一行的卡用同一档行高。
 */
private object BentoHeight {
    val compact = 112.dp
    val row = 128.dp
    val editor = 148.dp
    val hero = 128.dp
}

/** 设置首页第一块：头像、昵称、性别年龄与一句人设摘要，点整卡进完整人设页。 */
@Composable
private fun PersonaPreviewCard(
    graph: AppGraph,
    name: String,
    gender: String,
    age: Int?,
    summary: String,
    daysText: String,
    height: Dp,
    onOpen: () -> Unit,
) {
    val pickAvatar = rememberPortraitPicker(graph)
    val palette = LocalXiaomanPalette.current
    val press = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        interactionSource = press,
        shape = RoundedCornerShape(Radius.lg),
        color = Color.Transparent,
        shadowElevation = Elevation.card,
        modifier = Modifier.fillMaxWidth().height(height).pressScale(press, 0.985f),
    ) {
        // 恋人卡：浅雾紫渐变从左上铺到右下，去掉描边，靠色差和弱阴影分层。
        Row(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(palette.accentSoft, palette.surface)),
            ).padding(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(72.dp)) {
                Box(Modifier.fillMaxSize().clip(CircleShape).clickable { pickAvatar() }) {
                    PortraitAvatar(graph, 72.dp)
                }
                // 「可换头像」从文案行移到头像角标，主行只保留关系天数。
                Box(
                    Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape)
                        .background(palette.accent).clickable { pickAvatar() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = "点头像可换",
                        tint = palette.accentInk,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Column(
                Modifier.weight(1f).padding(start = Space.lg),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "$gender · ${age?.let { "$it 岁" } ?: "年龄未填"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「在一起 XX 天」是这块卡的主角信息：抬高字号与字重，直接用品牌紫。
                Text(
                    daysText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = palette.ink3,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

@Composable
private fun BentoRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

/** bento 卡片：图标、标题与当前值摘要，点击进入对应的二级设置页。 */
@Composable
private fun BentoCard(
    title: String,
    summary: String,
    icon: ImageVector,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    val press = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = press,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Elevation.card,
        modifier = modifier.height(height).pressScale(press, 0.985f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(IconSize.lg),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 二级设置页统一外壳：固定返回 + 标题，内容全宽，控件按正常尺寸排布。 */
@Composable
internal fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalXiaomanPalette.current.ink2,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.block),
            content = content,
        )
    }
}

/**
 * 二级页统一的四级层级，所有二级页都从这里取样式，不再各自手写：
 * 分组标题 labelMedium(11.5) / ink2；行标题 bodyLarge(15) / ink；
 * 说明 labelMedium(11.5, 常规字重) / ink3；控件值 bodyLarge(15) / ink2。
 * 组与组之间靠 28 的间距分开，组内 8；分隔线只在真的需要断开两段时用。
 */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = palette.ink2,
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            content = content,
        )
    }
}

/** 一行设置：左侧行标题与可选说明，右侧控件；整行最小热区 44。 */
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
        modifier.fillMaxWidth().heightIn(min = SizeTokens.touchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    color = palette.ink3,
                    modifier = Modifier.padding(top = 2.dp),
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

/** 说明文字：11.5、ink3，明显退后于行标题。 */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
        color = LocalXiaomanPalette.current.ink3,
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
    val palette = LocalXiaomanPalette.current
    SettingsSubpage(stringResource(R.string.appearance_section), onBack) {
        SettingsGroup("主题") {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = SizeTokens.touchTarget)
                        .selectable(
                            selected = stored.appearance.mode == mode,
                            onClick = {
                                store.save(
                                    store.current.copy(appearance = store.current.appearance.copy(mode = mode)),
                                )
                            },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        mode.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.ink,
                    )
                    RadioButton(selected = stored.appearance.mode == mode, onClick = null)
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
        SettingsGroup("背景图") {
            ChatBackgroundSection(stored.appearance) { appearance ->
                store.save(store.current.copy(appearance = appearance))
            }
        }
    }
}

@Composable
private fun ImageSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val store = graph.settings
    val stored by store.settings.collectAsStateWithLifecycle()
    SettingsSubpage("发图", onBack) {
        SettingsGroup("主动发图") {
            SettingsRow(
                title = "让她主动发图",
                description = "你说想看看时，她按当下的对话细节生成；旧图只在回忆里复用，失败静默。",
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
                SettingsNote("用聊天模型的方舟 Key 生成，一张约 ¥0.12；到上限后不再生成，不弹错误。")
            }
            SettingsGroup("图片模型") {
                OutlinedTextField(
                    value = stored.image.model,
                    onValueChange = {
                        store.save(store.current.copy(image = store.current.image.copy(model = it)))
                    },
                    label = { Text("图片模型") },
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
    // 只给三档；CALL 是旧版遗留值，保留枚举读旧数据，不再出现在设置里。
    listOf(ReplyMode.AUTO, ReplyMode.TEXT, ReplyMode.VOICE).forEach { mode ->
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = value == mode, onClick = { onChange(mode) }, role = Role.RadioButton)
                .heightIn(min = SizeTokens.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(mode.label()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalXiaomanPalette.current.ink,
                )
                Text(
                    stringResource(mode.description()),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    color = LocalXiaomanPalette.current.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            RadioButton(selected = value == mode, onClick = null)
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
