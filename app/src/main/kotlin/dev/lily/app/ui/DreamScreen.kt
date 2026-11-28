package dev.lily.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.dream.DreamConfig
import dev.lily.app.dream.DreamDay
import dev.lily.app.dream.DreamItem
import dev.lily.app.dream.DreamTypes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DreamState(
    val config: DreamConfig = DreamConfig(),
    val days: List<DreamDay> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

private class DreamViewModel(private val conversation: AppGraph.Conversation) : ViewModel() {
    private val _state = MutableStateFlow(DreamState())
    val state: StateFlow<DreamState> = _state.asStateFlow()

    init {
        viewModelScope.launch { conversation.dream.revision.collect { load() } }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.days.isEmpty(), failed = false) }
            val config = conversation.dreamStore.config()
            runCatching { conversation.dreamStore.days() }
                .onSuccess { days ->
                    _state.update { it.copy(config = config, days = days, loading = false) }
                }
                .onFailure {
                    _state.update { it.copy(config = config, loading = false, failed = true) }
                }
        }
    }

    fun setEnabled(enabled: Boolean) = save(conversation.dreamStore.config().copy(enabled = enabled))

    fun setAuto(auto: Boolean) = save(conversation.dreamStore.config().copy(autoLowRisk = auto))

    fun setTime(time: String) = save(conversation.dreamStore.config().copy(time = time))

    fun reject(day: DreamDay, item: DreamItem) = conversation.dream.rejectItem(day.date, item.id)

    fun restore(day: DreamDay, item: DreamItem) = conversation.dream.restoreItem(day.date, item.id)

    private fun save(config: DreamConfig) {
        conversation.dreamStore.saveConfig(config)
        conversation.dream.schedule()
        _state.update { it.copy(config = config) }
    }
}

/** 夜里：不是任务队列，是「她昨晚想了什么」的清晨一页。 */
@Composable
internal fun DreamScreen(conversation: AppGraph.Conversation, onBack: () -> Unit) {
    val vm = viewModel(key = "dream-${conversation.id}") { DreamViewModel(conversation) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    val latest = remember(state.days) {
        state.days
            .filter { day -> day.items.any(::isResult) }
            .maxByOrNull { it.appliedAt ?: it.createdAt }
    }
    var removing by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Pair<DreamDay, DreamItem>?>(null) }

    // 「这条不对」：卡片先收起 140ms，再把这一条从记忆里拿掉。
    val settle = motionMillis(Motion.fast)
    LaunchedEffect(removing) {
        val id = removing ?: return@LaunchedEffect
        val day = latest ?: return@LaunchedEffect
        val item = day.items.firstOrNull { it.id == id } ?: return@LaunchedEffect
        delay(settle)
        vm.reject(day, item)
        undo = day to item
        removing = null
    }
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        delay(5_000)
        undo = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DreamTopBar(onBack)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.page)
                        .padding(bottom = if (undo != null) 64.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    when {
                        state.loading -> DreamSkeleton()
                        state.failed -> DreamPlaceholder(stringResource(R.string.dream_night_failed), onRetry = vm::load)
                        latest == null -> DreamPlaceholder(stringResource(R.string.dream_night_empty))
                        else -> {
                            val results = latest.items.filter(::isResult)
                            Text(
                                stringResource(R.string.dream_night_date, nightDate(latest.date)),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalLilyPalette.current.accent,
                                modifier = Modifier.padding(top = Space.md),
                            )
                            Text(
                                stringResource(R.string.dream_night_intro, settings.persona.pronoun, results.size),
                                style = MaterialTheme.typography.titleMedium,
                                color = LocalLilyPalette.current.ink,
                            )
                            results.forEach { item ->
                                AnimatedVisibility(
                                    visible = removing != item.id,
                                    exit = fadeOut(exitTween()) + shrinkVertically(exitSizeTween()),
                                ) {
                                    DreamResultCard(item) { removing = item.id }
                                }
                            }
                            Text(
                                stringResource(R.string.dream_night_note),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalLilyPalette.current.ink3,
                                modifier = Modifier.padding(top = Space.md),
                            )
                        }
                    }
                    NightSettings(state.config, vm = vm)
                }
            }
        }
        AnimatedVisibility(
            visible = undo != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.page, vertical = Space.xl),
            enter = fadeIn(enterTween()),
            exit = fadeOut(exitTween()),
        ) {
            UndoBar(onUndo = {
                undo?.let { (day, item) -> vm.restore(day, item) }
                undo = null
            })
        }
    }
}

@Composable
private fun DreamPlaceholder(text: String, onRetry: (() -> Unit)? = null) {
    val palette = LocalLilyPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = Space.md),
        shape = RoundedCornerShape(Radius.lg),
        color = palette.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Space.block),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(Modifier.size(48.dp).background(palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DarkMode, contentDescription = null, tint = palette.accentOnSoft)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.tapTarget()) { Text(text, textAlign = TextAlign.Center) }
            } else {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.ink2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DreamTopBar(onBack: () -> Unit) {
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Space.xs, end = Space.page),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = palette.ink2,
                modifier = Modifier.size(IconSize.lg),
            )
        }
        Text(
            stringResource(R.string.dream_night_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = Space.sm),
        )
    }
}

/** 一张整理结果：只有句子、来源和右下角的「这条不对」。 */
@Composable
private fun DreamResultCard(item: DreamItem, onWrong: () -> Unit) {
    val palette = LocalLilyPalette.current
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = palette.surface,
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
    ) {
        Column(Modifier.fillMaxWidth().padding(Space.xl)) {
            Text(
                item.title.trim().ifBlank { item.observations.firstOrNull()?.content?.trim().orEmpty() },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dream_night_source),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = TypeScale.micro.size,
                        lineHeight = TypeScale.micro.line,
                    ),
                    color = palette.ink3,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.tapTarget().clickable(onClick = onWrong), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        stringResource(R.string.dream_night_wrong),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoBar(onUndo: () -> Unit) {
    val palette = LocalLilyPalette.current
    Surface(shape = RoundedCornerShape(Radius.md), color = palette.ink) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.dream_night_removed),
                style = MaterialTheme.typography.bodySmall,
                color = palette.bg,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.tapTarget().clickable(onClick = onUndo), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.dream_night_undo),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.bg,
                    modifier = Modifier.padding(horizontal = Space.lg),
                )
            }
        }
    }
}

/** 加载态：3 张结果卡骨架。 */
@Composable
private fun DreamSkeleton() {
    val palette = LocalLilyPalette.current
    Column(Modifier.fillMaxWidth()) {
        repeat(3) {
            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
            ) {
                Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(Modifier.fillMaxWidth(0.78f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(palette.line))
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(palette.line))
                }
            }
        }
    }
}

/**
 * 夜间整理的开关、时间与自动处理收在同一组。
 */
@Composable
private fun NightSettings(config: DreamConfig, vm: DreamViewModel) {
    val palette = LocalLilyPalette.current
    var time by remember(config.time) { mutableStateOf(config.time) }
    val valid = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(time.trim())
    Column(Modifier.fillMaxWidth().padding(top = Space.block, bottom = Space.block)) {
        Text(
            stringResource(R.string.dream_night_settings),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink2,
            modifier = Modifier.padding(start = Space.sm, bottom = Space.sm),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surface,
        ) {
            Column(Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                SettingsRow(
                    title = stringResource(R.string.dream_enabled),
                    trailing = { LilySwitch(checked = config.enabled) { vm.setEnabled(it) } },
                )
                HorizontalDivider(color = palette.line)
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(stringResource(R.string.dream_time)) },
                    singleLine = true,
                    isError = !valid,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
                )
                TextButton(
                    onClick = { vm.setTime(time.trim()) },
                    enabled = valid && time.trim() != config.time,
                    modifier = Modifier.align(Alignment.End).tapTarget(),
                ) {
                    Text(stringResource(R.string.dream_time_save))
                }
                HorizontalDivider(color = palette.line)
                SettingsRow(
                    title = stringResource(R.string.dream_auto),
                    description = stringResource(R.string.dream_auto_desc),
                    trailing = { LilySwitch(checked = config.autoLowRisk) { vm.setAuto(it) } },
                )
            }
        }
    }
}

/** 昨晚真正想起来的事：只算她写下、并且可撤回的新增与合并。 */
private fun isResult(item: DreamItem): Boolean =
    item.applied && item.undo != null &&
        (item.type == DreamTypes.REMEMBER || item.type == DreamTypes.MERGE)

/** `2026-11-23` → `11月23日`。 */
private fun nightDate(raw: String): String = runCatching {
    val date = LocalDate.parse(raw)
    "${date.monthValue}月${date.dayOfMonth}日"
}.getOrDefault(raw)
