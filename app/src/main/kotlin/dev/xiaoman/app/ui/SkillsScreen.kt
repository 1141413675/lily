package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsState(val skills: List<Skill> = emptyList(), val problems: List<SkillProblem> = emptyList(), val loading: Boolean = true)

class SkillsViewModel(
    private val repository: SkillRepository,
    private val ready: suspend () -> Unit,
    private val onReloaded: (Int, List<SkillProblem>) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsState())
    val state: StateFlow<SkillsState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            ready()
            val result = repository.reload()
            onReloaded(result.skills.size, result.problems)
            _state.value = SkillsState(result.skills, result.problems, loading = false)
        }
    }
}

@Composable
fun SkillsScreen(graph: AppGraph, onBack: (() -> Unit)? = null) {
    val vm = viewModel { SkillsViewModel(graph.skills, { graph.startup.await() }, graph::logSkillProblems) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.page, top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
            Text(stringResource(R.string.skills_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = vm::reload,
                enabled = !state.loading,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalXiaomanPalette.current.ink2),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                Text(stringResource(R.string.skills_reload), modifier = Modifier.padding(start = Space.xs))
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            if (!state.loading && state.skills.isEmpty()) {
                item { SettingsNote(stringResource(R.string.skills_empty, graph.skillsDir.path)) }
            }
            items(state.skills, key = { it.name }) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(skill.name, style = MaterialTheme.typography.bodyLarge, color = LocalXiaomanPalette.current.ink)
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                            color = LocalXiaomanPalette.current.ink3,
                        )
                        if (skill.apps.isNotEmpty()) {
                            Text(
                                stringResource(R.string.skills_apps, skill.apps.joinToString()),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalXiaomanPalette.current.ink3,
                            )
                        }
                        Text(skill.source, style = MaterialTheme.typography.labelSmall, color = LocalXiaomanPalette.current.ink3)
                    }
                }
            }
            if (state.problems.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.skills_problems),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalXiaomanPalette.current.ink2,
                        modifier = Modifier.padding(top = Space.block),
                    )
                }
                items(state.problems) { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Space.lg)) {
                            Text(p.source + (p.line?.let { ":$it" } ?: ""), style = MaterialTheme.typography.labelMedium)
                            Text(p.message, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
