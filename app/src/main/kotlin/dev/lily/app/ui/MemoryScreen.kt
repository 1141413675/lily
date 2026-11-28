package dev.lily.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lily.api.MemoryStore
import dev.lily.api.Note
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.dream.DreamDay
import dev.lily.app.dream.DreamTypes
import dev.lily.app.feed.FeedMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class MemoryState(
    val query: String = "",
    /** 全部记忆，时间线按 updatedAtMillis 倒序排列。 */
    val notes: List<Note> = emptyList(),
    /** 已落地的做梦结果，接在同一条时间线里。 */
    val dreams: List<DreamDay> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** 打开的那一条。 */
    val open: Note? = null,
    /** 正在 140ms 收起的条目，用来让「忘掉」不是硬删除。 */
    val removing: String? = null,
)

/** 时间线的一行：一条记忆，或一次做梦整理出的结果。 */
private sealed class TimelineEntry {
    abstract val key: String
    abstract val at: Long

    data class Memory(val note: Note, val inferred: Boolean) : TimelineEntry() {
        override val key get() = "m-${note.permalink}"
        override val at get() = note.updatedAtMillis
    }

    data class Dream(val day: DreamDay, val text: String) : TimelineEntry() {
        override val key get() = "d-${day.date}"
        override val at get() = day.appliedAt ?: day.createdAt
    }
}

class MemoryViewModel(
    private val memory: MemoryStore,
    private val dreamDays: () -> List<DreamDay>,
) : ViewModel() {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    fun setQuery(text: String) = _state.update { it.copy(query = text) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val dreams = runCatching { dreamDays() }
                .getOrDefault(emptyList())
                .filter { day -> day.status == DreamTypes.APPLIED || day.items.any { it.applied } }
                .sortedByDescending { it.appliedAt ?: it.createdAt }
            runCatching { memory.list().sortedByDescending { it.updatedAtMillis } }
                .onSuccess { notes ->
                    _state.update { it.copy(notes = notes, dreams = dreams, loading = false) }
                }
                .onFailure {
                    _state.update { it.copy(dreams = dreams, loading = false, failed = true) }
                }
        }
    }

    fun open(permalink: String) {
        viewModelScope.launch {
            val note = runCatching { memory.read(permalink) }.getOrNull()
            if (note != null) _state.update { it.copy(open = note) }
        }
    }

    fun close() = _state.update { it.copy(open = null) }

    /** 先让条目收起 140ms，再真正删掉；不弹确认框。 */
    fun forget(note: Note) {
        viewModelScope.launch {
            _state.update { it.copy(open = null, removing = note.permalink) }
            delay(Motion.fast.toLong())
            runCatching { memory.delete(note.permalink) }
            _state.update {
                it.copy(
                    notes = it.notes.filterNot { item -> item.permalink == note.permalink },
                    removing = null,
                )
            }
        }
    }
}

@Composable
internal fun MemoryScreen(
    conversation: AppGraph.Conversation,
    focusPermalink: String? = null,
    onFocusConsumed: () -> Unit = {},
    onDetailChange: (Boolean) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenDream: (() -> Unit)? = null,
) {
    val vm = viewModel(key = "memory-${conversation.id}") {
        MemoryViewModel(conversation.memory) { conversation.dreamStore.days() }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val embedding = settings.memoryEmbedding
    val memorySearch = conversation.memorySearch
    val progress by memorySearch.progress.collectAsStateWithLifecycle()
    var searchHits by remember(conversation.id, state.query, embedding) { mutableStateOf(emptySet<String>()) }
    val personaName = settings.persona.nameOr(stringResource(R.string.app_name))
    val pronoun = settings.persona.pronoun
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(embedding) { memorySearch.prepare() }
    LaunchedEffect(state.query, embedding, progress.indexed) {
        val query = state.query.trim()
        if (embedding.active && query.length >= 2) {
            delay(350)
            searchHits = try {
                memorySearch.search(query, limit = 30).mapTo(mutableSetOf()) { it.permalink }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
        }
    }
    LaunchedEffect(focusPermalink) {
        if (focusPermalink != null) {
            vm.open(focusPermalink)
            onFocusConsumed()
        }
    }
    val note = state.open
    BackHandler(enabled = onBack != null || note != null) {
        if (note != null) vm.close() else onBack?.invoke()
    }
    // 详情是浮层：告诉外层收起底栏；离开时复位。
    LaunchedEffect(note != null) { onDetailChange(note != null) }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    // 「正在构建」是过程，交给动效说；其余是事实，仍然写字。
    val indexing = embedding.active && progress.running && progress.indexed == 0
    val indexStatus = if (embedding.active) {
        when {
            indexing -> null
            progress.unavailable -> "向量服务暂时不可用，仍可用关键词查找"
            progress.total == 0 -> "有新记忆后会建立语义索引"
            else -> "语义索引 · ${progress.indexed}/${progress.total} 段"
        }
    } else null
    MemoryTimeline(state, vm, personaName, pronoun, onBack, onOpenDream, searchHits, indexStatus, indexing)
    if (note != null) {
        NoteDetailSheet(
            note = note,
            inferred = isInferred(note),
            pronoun = pronoun,
            onDismiss = vm::close,
            onForget = { vm.forget(note) },
        )
    }
}

/** 顶栏显示角色昵称和记忆数量，下面按月份分组。 */
@Composable
private fun MemoryTimeline(
    state: MemoryState,
    vm: MemoryViewModel,
    personaName: String,
    pronoun: String,
    onBack: (() -> Unit)?,
    onOpenDream: (() -> Unit)?,
    searchHits: Set<String>,
    indexStatus: String?,
    indexing: Boolean,
) {
    val palette = LocalLilyPalette.current
    val entries = remember(state.notes, state.dreams, state.query, searchHits) {
        val q = state.query.trim()
        val notes = state.notes
            .filter {
                q.isEmpty() || it.permalink in searchHits ||
                    it.title.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true)
            }
            .map { TimelineEntry.Memory(it, isInferred(it)) }
        val dreams = state.dreams.map { TimelineEntry.Dream(it, dreamLine(it)) }
        (notes + dreams).sortedByDescending { it.at }
    }
    val groups = remember(entries) { entries.groupBy { monthLabel(it.at) } }
    val lastNight = remember(state.dreams) { state.dreams.firstOrNull()?.let { rememberedCount(it) } ?: 0 }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Space.xs, end = Space.xl),
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
                Column(Modifier.weight(1f).padding(start = Space.sm, top = Space.xs, bottom = Space.xs)) {
                    Text(
                        stringResource(R.string.memory_title, personaName),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    SettingsNote(stringResource(R.string.memory_count, state.notes.size))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.md)) {
                Text(
                    stringResource(R.string.memory_title, personaName),
                    style = MaterialTheme.typography.titleLarge,
                )
                SettingsNote(stringResource(R.string.memory_count, state.notes.size))
            }
        }
        MemorySearch(
            query = state.query,
            onQueryChange = vm::setQuery,
            modifier = Modifier.padding(horizontal = Space.page).padding(top = Space.md, bottom = Space.sm),
        )
        if (indexStatus != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xs)) {
                SettingsNote(indexStatus)
            }
        }
        // 索引刚起步时：过程交给转圈，文字只留「在弄什么」这个主语。
        if (indexing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpinningArc(
                    color = LocalLilyPalette.current.ink3,
                    size = 14.dp,
                    strokeWidth = 2.dp,
                    description = "正在构建语义索引",
                )
                Spacer(Modifier.size(Space.md))
                Text(
                    "语义索引",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLilyPalette.current.ink2,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> MemorySkeleton()
                state.failed -> CenterHint(
                    text = stringResource(R.string.memory_load_failed),
                    onClick = vm::refresh,
                )
                entries.isEmpty() -> CenterHint(
                    text = if (state.query.isBlank()) {
                        stringResource(R.string.memory_empty_timeline, pronoun)
                    } else {
                        stringResource(R.string.memory_no_hits)
                    },
                )
                else -> TimelineList(state, groups, vm, pronoun)
            }
        }
        if (onOpenDream != null && lastNight > 0 && !state.loading && !state.failed) {
            NightEntry(count = lastNight, pronoun = pronoun, onClick = onOpenDream)
        }
    }
}

@Composable
private fun MemorySearch(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalLilyPalette.current
    val focus = LocalFocusManager.current
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = palette.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Space.lg, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.ink3, modifier = Modifier.size(16.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(start = Space.sm)) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.memory_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.ink3,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(SizeTokens.touchTarget)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.clear),
                        tint = palette.ink3,
                        modifier = Modifier.size(IconSize.sm),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineList(
    state: MemoryState,
    groups: Map<String, List<TimelineEntry>>,
    vm: MemoryViewModel,
    pronoun: String,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        groups.forEach { (month, rows) ->
            stickyHeader(key = "month-$month") { MonthHeader(month) }
            items(rows, key = { it.key }) { entry ->
                AnimatedVisibility(
                    visible = state.removing != entry.key,
                    exit = fadeOut(exitTween()) + shrinkVertically(exitSizeTween()),
                ) {
                    MemoryAppear {
                        when (entry) {
                            is TimelineEntry.Memory -> NoteRow(
                                note = entry.note,
                                inferred = entry.inferred,
                                pronoun = pronoun,
                                onClick = { vm.open(entry.note.permalink) },
                            )
                            is TimelineEntry.Dream -> DreamRow(entry.text, pronoun)
                        }
                    }
                }
            }
        }
    }
}

/** 时间线进入动画：只做 180ms 淡入，位移交给二级页统一转场，避免双重动画。 */
@Composable
private fun MemoryAppear(content: @Composable () -> Unit) {
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(enterTween()),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

/** 粘性月份标题，滚动时保留时间分组。 */
@Composable
private fun MonthHeader(month: String) {
    val palette = LocalLilyPalette.current
    Column(Modifier.fillMaxWidth().background(palette.bg).padding(start = Space.page, end = Space.page, top = Space.xl, bottom = Space.sm)) {
        Text(month, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = palette.ink2)
    }
}

/** 左侧 20 处一条 1dp 竖线，节点 6dp；内容从 36 开始。 */
@Composable
private fun TimelineRail(accent: Boolean) {
    val palette = LocalLilyPalette.current
    Box(Modifier.width(36.dp).fillMaxHeight()) {
        Box(
            Modifier.padding(start = 19.dp).width(1.dp).fillMaxHeight()
                .background(palette.line),
        )
        Box(
            Modifier.padding(start = 17.dp, top = 23.dp).size(6.dp).clip(CircleShape)
                .background(if (accent) palette.accent else palette.ink3.copy(alpha = 0.75f)),
        )
    }
}

/** 一条记忆，来源与正文左对齐，便于连续浏览。 */
@Composable
private fun NoteRow(note: Note, inferred: Boolean, pronoun: String, onClick: () -> Unit) {
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        TimelineRail(accent = false)
        Column(Modifier.weight(1f).padding(end = Space.page, top = 14.dp, bottom = 14.dp)) {
            Text(
                shortLine(note),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (inferred) R.string.memory_source_her else R.string.memory_source_user, pronoun),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = TypeScale.micro.size,
                    lineHeight = TypeScale.micro.line,
                ),
                color = palette.ink3,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** 做梦结果：浅紫底的小卡，和正文拉开一层，不抢句子的位置。 */
@Composable
private fun DreamRow(text: String, pronoun: String) {
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        TimelineRail(accent = true)
        Surface(
            Modifier.weight(1f).padding(end = Space.page, top = 14.dp, bottom = 14.dp),
            shape = RoundedCornerShape(Radius.md),
            color = palette.accentSoft,
        ) {
            Column(Modifier.fillMaxWidth().padding(Space.lg)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.DarkMode,
                        contentDescription = null,
                        tint = palette.accentOnSoft,
                        modifier = Modifier.padding(top = 3.dp).size(14.dp),
                    )
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.accentOnSoft,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Space.sm),
                    )
                }
                Text(
                    stringResource(R.string.memory_dream_source, pronoun),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = TypeScale.micro.size,
                        lineHeight = TypeScale.micro.line,
                    ),
                    color = palette.accentOnSoft.copy(alpha = 0.78f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
                )
            }
        }
    }
}

/** 只在有整理结果时显示入口。 */
@Composable
private fun NightEntry(count: Int, pronoun: String, onClick: () -> Unit) {
    val palette = LocalLilyPalette.current
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = palette.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.md),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DarkMode, contentDescription = null, tint = palette.accent, modifier = Modifier.size(IconSize.md))
            Text(
                stringResource(R.string.memory_last_night, pronoun, count),
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
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
private fun CenterHint(text: String, onClick: (() -> Unit)? = null) {
    val palette = LocalLilyPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = Space.page),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(Modifier.size(48.dp).background(palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = palette.accentOnSoft)
            }
            if (onClick != null) {
                TextButton(onClick = onClick, modifier = Modifier.tapTarget()) {
                    Text(text, textAlign = TextAlign.Center)
                }
            } else {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.ink2, textAlign = TextAlign.Center)
            }
        }
    }
}

/** 加载态只给三行句子骨架，不转圈。 */
@Composable
private fun MemorySkeleton() {
    val palette = LocalLilyPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.md)) {
        repeat(3) {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(Modifier.fillMaxWidth(0.72f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(palette.line))
                Box(Modifier.fillMaxWidth(0.42f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(palette.line))
            }
        }
    }
}

/** 详情浮层：70% 高，完整内容在上，「忘掉这条」在下，二次确认就在原位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailSheet(
    note: Note,
    inferred: Boolean,
    pronoun: String,
    onDismiss: () -> Unit,
    onForget: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    var confirm by remember(note.permalink) { mutableStateOf(false) }
    val content = fullContent(note)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
        contentColor = palette.ink,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.7f)
                .padding(start = Space.page, end = Space.page, top = Space.sm)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        stringResource(if (inferred) R.string.memory_source_her else R.string.memory_source_user, pronoun),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                    Text(dateLabel(note.updatedAtMillis), style = MaterialTheme.typography.labelMedium, color = palette.ink3)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel), tint = palette.ink2)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(top = Space.md, bottom = Space.lg),
            ) {
                SelectionContainer {
                    Text(
                        chatRichText(content),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = TypeScale.headline.size,
                            lineHeight = TypeScale.headline.line,
                        ),
                        color = palette.ink,
                    )
                }
            }
            HorizontalDivider(color = palette.line)
            if (confirm) {
                Text(
                    stringResource(R.string.memory_forget_confirm_line),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                    modifier = Modifier.padding(top = Space.sm),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirm = false }, modifier = Modifier.tapTarget()) {
                        Text(stringResource(R.string.memory_forget_cancel), color = palette.ink2)
                    }
                    TextButton(onClick = onForget, modifier = Modifier.tapTarget()) {
                        Text(stringResource(R.string.memory_forget_action), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                TextButton(
                    onClick = { confirm = true },
                    modifier = Modifier.fillMaxWidth().tapTarget(),
                ) {
                    Text(stringResource(R.string.memory_forget_this), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 来自动态、做梦归纳或长期规则的记忆属于角色推断。 */
private fun isInferred(note: Note): Boolean =
    note.extraFrontmatter[FeedMemory.SOURCE_KEY] != null ||
        note.extraFrontmatter["dream"] != null ||
        note.type == "rule"

private fun shortLine(note: Note): String =
    note.title.trim().ifBlank { note.observations.firstOrNull()?.content?.trim().orEmpty() }
        .ifBlank { "一件还说不清的小事" }

/** 详情正文：去掉与标题重复的行、自动生成的标题行与小标题，只留内容。 */
private fun fullContent(note: Note): String {
    val title = note.title.trim()
    fun isTitleLike(text: String) =
        text == title || text == "# $title" || text == "标题：$title" || text == "**$title**"
    val body = note.body.lineSequence()
        .map { it.trim() }
        .filterNot { line -> line.isBlank() || isTitleLike(line) || Regex("^#{1,6}\\s+").containsMatchIn(line) }
        .joinToString("\n")
    val observations = note.observations
        .map { it.content.trim() }
        .filterNot { it.isBlank() || isTitleLike(it) }
    return body.ifBlank { observations.joinToString("\n") }
        .ifBlank { title }
}

private fun dreamLine(day: DreamDay): String =
    day.summary.trim().ifBlank {
        day.items.firstOrNull { it.applied }?.title?.trim().orEmpty()
    }.ifBlank { "夜里整理了几件事" }

/** 昨晚真正想起来的事：只数她写下的新增与合并。 */
private fun rememberedCount(day: DreamDay): Int =
    day.items.count { item ->
        item.applied && (item.type == DreamTypes.REMEMBER || item.type == DreamTypes.MERGE)
    }

private fun monthLabel(millis: Long): String {
    if (millis <= 0) return "更早"
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.year}年${date.monthValue}月"
}

private fun dateLabel(millis: Long): String {
    if (millis <= 0) return ""
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.monthValue}月${date.dayOfMonth}日"
}
