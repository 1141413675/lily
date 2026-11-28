package dev.lily.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lily.api.Skill
import dev.lily.api.SkillProblem
import dev.lily.api.SkillRepository
import dev.lily.app.AppGraph
import dev.lily.app.R
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
    val palette = LocalLilyPalette.current
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.page, top = Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = palette.ink2,
                    )
                }
            }
            Text(stringResource(R.string.skills_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = vm::reload,
                enabled = !state.loading,
                colors = ButtonDefaults.textButtonColors(contentColor = palette.accent),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                Text(stringResource(R.string.skills_reload), modifier = Modifier.padding(start = Space.xs))
            }
        }
        if (state.loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.sm),
                trackColor = palette.accentSoft,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            if (!state.loading && state.skills.isEmpty()) {
                item {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.lg),
                        color = palette.surface,
                    ) {
                        SettingsNote(
                            stringResource(R.string.skills_empty, graph.skillsDir.path),
                            Modifier.padding(Space.xl),
                        )
                    }
                }
            }
            items(state.skills, key = { it.name }) { skill ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.lg),
                    color = palette.surface,
                ) {
                    Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall, color = palette.ink)
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.ink2,
                        )
                        if (skill.apps.isNotEmpty()) {
                            Surface(shape = RoundedCornerShape(Radius.sm), color = palette.accentSoft) {
                                Text(
                                    stringResource(R.string.skills_apps, skill.apps.joinToString()),
                                    modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.accentOnSoft,
                                )
                            }
                        }
                        Text(skill.source, style = MaterialTheme.typography.labelSmall, color = palette.ink3)
                    }
                }
            }
            if (state.problems.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.skills_problems),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink2,
                        modifier = Modifier.padding(start = Space.sm, top = Space.md),
                    )
                }
                items(state.problems) { p ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.md),
                        color = palette.errorContainer,
                        contentColor = palette.onErrorContainer,
                    ) {
                        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(p.message, style = MaterialTheme.typography.bodySmall)
                            Text(p.source + (p.line?.let { ":$it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
