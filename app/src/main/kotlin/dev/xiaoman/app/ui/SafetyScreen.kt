package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.safety.SafetyEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SafetyState(
    val entries: List<SafetyEntry> = emptyList(),
    val loading: Boolean = true,
)

class SafetyViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val entries = runCatching { graph.safetyStore.entries() }.getOrDefault(emptyList())
            _state.update { it.copy(entries = entries, loading = false) }
        }
    }

    fun clear() {
        graph.safetyStore.clear()
        _state.update { it.copy(entries = emptyList()) }
    }
}

@Composable
fun SafetyScreen(graph: AppGraph, onBack: () -> Unit) {
    val vm = viewModel { SafetyViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.xs, top = Space.xs, end = Space.page), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalXiaomanPalette.current.ink2,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            Text(
                stringResource(R.string.safety_title),
                Modifier.weight(1f).padding(start = Space.sm),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                enabled = state.entries.isNotEmpty(),
                onClick = { confirmClear = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.safety_clear))
            }
        }
        SettingsNote(
            stringResource(R.string.safety_hint),
            Modifier.padding(horizontal = Space.page, vertical = Space.xs),
        )
        RefusalSection(graph)
        if (!state.loading && state.entries.isEmpty()) {
            SettingsNote(
                stringResource(R.string.safety_empty),
                Modifier.padding(horizontal = Space.page, vertical = Space.lg),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            items(state.entries) { entry ->
                SafetyRow(entry)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.safety_clear_title)) },
            text = { Text(stringResource(R.string.safety_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.safety_clear_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RefusalSection(graph: AppGraph) {
    var tick by remember { mutableStateOf(0) }
    val stats = remember(tick) { graph.refusals.stats() }
    val recent = remember(tick) { graph.refusals.recent(3) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_refusal_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = LocalXiaomanPalette.current.ink2,
            )
            TextButton(
                onClick = { graph.refusals.clear(); tick++ },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalXiaomanPalette.current.ink3),
            ) {
                Text(stringResource(R.string.settings_refusal_clear), style = MaterialTheme.typography.labelMedium)
            }
        }
        SettingsNote(
            stringResource(R.string.settings_refusal_stats, stats.checks, stats.refusals, stats.rate * 100, stats.retriesLastHour),
        )
        if (recent.isEmpty()) {
            SettingsNote(stringResource(R.string.settings_refusal_empty))
        } else {
            recent.forEach { e ->
                Text(
                    "${timeText(e.at)} · ${e.topic} · ${e.outcome ?: e.kind}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalXiaomanPalette.current.ink3,
                )
            }
        }
        SettingsNote(stringResource(R.string.settings_refusal_note))
    }
}

@Composable
private fun SafetyRow(entry: SafetyEntry) {
    val palette = LocalXiaomanPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stageLabel(entry.stage), style = MaterialTheme.typography.labelMedium, color = stageColor(entry.stage))
            Text(
                timeText(entry.time),
                Modifier.weight(1f).padding(start = Space.sm),
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink3,
            )
        }
        Text(entry.instruction, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
        val detail = buildString {
            append(entry.rule)
            if (entry.matched.isNotBlank()) append(" · ").append(entry.matched)
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = palette.ink3)
        }
    }
}

@Composable
private fun stageLabel(stage: String): String = when (stage) {
    "chat" -> stringResource(R.string.safety_stage_chat)
    "task" -> stringResource(R.string.safety_stage_task)
    "action" -> stringResource(R.string.safety_stage_action)
    "confirm" -> stringResource(R.string.safety_stage_confirm)
    else -> stage
}

@Composable
private fun stageColor(stage: String) = when (stage) {
    "confirm" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

private fun timeText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
