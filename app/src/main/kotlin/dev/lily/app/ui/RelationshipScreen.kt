package dev.lily.app.ui

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.feed.FeedStore
import dev.lily.app.life.FeedTexts
import dev.lily.app.life.LifeSettings
import dev.lily.app.relationship.Habit
import dev.lily.app.relationship.OpenTopic
import dev.lily.app.relationship.ProactiveDecision
import dev.lily.app.relationship.Relationship
import dev.lily.app.relationship.RelationshipTiers
import dev.lily.app.settings.RelationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class RelationshipViewModel(private val conversation: AppGraph.Conversation) : ViewModel() {
    private val store = conversation.relationship
    val saveFailed = MutableStateFlow(false)

    init {
        viewModelScope.launch { runCatching { store.load() } }
    }

    fun setAddress(text: String) = viewModelScope.launch { runCatching { store.setAddress(text) } }

    fun addMoment(text: String) = viewModelScope.launch { runCatching { store.addMoment(text) } }

    fun removeMoment(text: String) = viewModelScope.launch { runCatching { store.removeMoment(text) } }

    fun addTopic(text: String) = viewModelScope.launch { runCatching { store.addTopic(text) } }

    fun removeTopic(topic: OpenTopic) = viewModelScope.launch { runCatching { store.removeTopic(topic) } }

    fun addHabit(name: String) = viewModelScope.launch { runCatching { store.addHabit(name) } }

    fun checkIn(name: String) = viewModelScope.launch { runCatching { store.checkIn(name) } }

    fun removeHabit(name: String) = viewModelScope.launch { runCatching { store.removeHabit(name) } }

    fun upgrade() = viewModelScope.launch { runCatching { store.upgrade() } }

    fun rollback() = viewModelScope.launch { runCatching { store.rollback() } }

    fun saveRelation(relation: RelationSettings) {
        saveFailed.value = !conversation.saveRelation(relation)
    }

    fun checkNow() = conversation.proactive.check()
}

@Composable
internal fun RelationshipScreen(conversation: AppGraph.Conversation, onBack: () -> Unit, showBack: Boolean = true) {
    val vm = viewModel(key = "relationship-${conversation.id}") { RelationshipViewModel(conversation) }
    val relationship by conversation.relationship.state.collectAsStateWithLifecycle()
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    if (showBack) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.page, top = Space.xs, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
            Column(
                Modifier.weight(1f).padding(start = if (showBack) Space.sm else Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    if (showBack) stringResource(R.string.relation_title) else settings.persona.pronoun,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.us_subtitle, settings.persona.nameOr(stringResource(R.string.app_name)), settings.persona.pronoun),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLilyPalette.current.ink2,
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.block),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            ProfileCard(relationship, vm)
            SharedCard(relationship, vm)
            TopicCard(relationship, vm)
            HabitCard(relationship, vm)
            ProactiveCard(settings.relation, vm, saveFailed)
            BoundaryCard(relationship, settings.relation, vm)
        }
    }
}

@Composable
private fun ProfileCard(relationship: Relationship, vm: RelationshipViewModel) {
    var draft by remember(relationship.address) { mutableStateOf(relationship.address) }
    val today = LocalDate.now()
    val days = relationship.daysKnown(today)
    val next = relationship.tier + 1
    val canUpgrade = RelationshipTiers.canUpgradeTo(relationship.tier, next, relationship.interactions, days)
    SectionCard(stringResource(R.string.relation_profile), emphasized = true) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Stat(days.toString(), "天", Modifier.weight(1f))
            Stat(relationship.interactions.toString(), "次说话", Modifier.weight(1f))
            Stat(RelationshipTiers.name(relationship.tier), "现在", Modifier.weight(1f))
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.relation_call_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { vm.setAddress(draft) },
            modifier = Modifier.align(Alignment.End).tapTarget(),
        ) {
            Text(stringResource(R.string.relation_save))
        }
        if (relationship.mood.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Space.xs),
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        Relationship.moodLabel(relationship.mood),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (relationship.moodBasis.isNotBlank()) {
                    Text(
                        " · ${relationship.moodBasis}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        if (relationship.tier < RelationshipTiers.NAMES.lastIndex) {
            if (canUpgrade) {
                Button(onClick = vm::upgrade) {
                    Text(stringResource(R.string.relation_upgrade, RelationshipTiers.name(next)))
                }
            } else {
                Text(
                    stringResource(R.string.relation_next, RelationshipTiers.requirementFor(next)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (relationship.tier > 0) {
            TextButton(onClick = vm::rollback) { Text(stringResource(R.string.relation_rollback)) }
        }
        if (relationship.tier < RelationshipTiers.NAMES.lastIndex) {
            Text(
                "走近会让称呼更亲近，${RelationshipTiers.name(next)}之后主动找你的次数也会多一点。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A big number with a small label, used for the profile stats row. */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalLilyPalette.current.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SharedCard(relationship: Relationship, vm: RelationshipViewModel) {
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    SectionCard(stringResource(R.string.relation_shared)) {
        Text(
            stringResource(R.string.relation_shared_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddRow(draft, { draft = it }, stringResource(R.string.relation_shared_hint)) {
            vm.addMoment(draft)
            draft = ""
        }
        relationship.sharedMoments.forEach { moment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(moment, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { pending = moment }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.relation_remove))
                }
            }
        }
        pending?.let { moment ->
            ConfirmDangerDialog(
                title = "删掉这条共享？",
                text = "「$moment」会从你们的共享里移走，删掉就找不回来了。",
                onConfirm = { vm.removeMoment(moment) },
                onDismiss = { pending = null },
            )
        }
    }
}

@Composable
private fun TopicCard(relationship: Relationship, vm: RelationshipViewModel) {
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<OpenTopic?>(null) }
    SectionCard(stringResource(R.string.relation_topics)) {
        AddRow(draft, { draft = it }, stringResource(R.string.relation_topic_hint)) {
            vm.addTopic(draft)
            draft = ""
        }
        relationship.openTopics.forEach { topic ->
            val followed = stringResource(R.string.relation_followed)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(topic.text, style = MaterialTheme.typography.bodyMedium)
                    val meta = buildString {
                        if (topic.notedOn.isNotBlank()) append(topic.notedOn)
                        if (topic.followedOn != null) {
                            if (isNotEmpty()) append(" · ")
                            append(followed)
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { pending = topic }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.relation_remove))
                }
            }
        }
        pending?.let { topic ->
            ConfirmDangerDialog(
                title = "删掉这个话题？",
                text = "「${topic.text}」会从待聊话题里移走，删掉就找不回来了。",
                onConfirm = { vm.removeTopic(topic) },
                onDismiss = { pending = null },
            )
        }
    }
}

@Composable
private fun HabitCard(relationship: Relationship, vm: RelationshipViewModel) {
    var draft by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.relation_habits)) {
        AddRow(draft, { draft = it }, stringResource(R.string.relation_habit_hint)) {
            vm.addHabit(draft)
            draft = ""
        }
        relationship.habits.forEach { habit ->
            HabitRow(habit, vm)
        }
    }
}

@Composable
private fun HabitRow(habit: Habit, vm: RelationshipViewModel) {
    var confirming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(habit.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                habit.lastDoneOn ?: stringResource(R.string.relation_never_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { vm.checkIn(habit.name) }) { Text(stringResource(R.string.relation_check_in)) }
        IconButton(onClick = { confirming = true }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.relation_remove))
        }
    }
    if (confirming) {
        ConfirmDangerDialog(
            title = "删掉这个习惯？",
            text = "「${habit.name}」和它的打卡记录会一起消失，删掉就找不回来了。",
            onConfirm = { vm.removeHabit(habit.name) },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun ProactiveCard(config: RelationSettings, vm: RelationshipViewModel, saveFailed: Boolean) {
    var form by remember(config) { mutableStateOf(RelationForm.of(config)) }
    var saved by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val parsed = form.toSettings(config)
    SectionCard(stringResource(R.string.relation_proactive)) {
        Text(
            stringResource(R.string.relation_proactive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.relation_enabled), Modifier.weight(1f))
            LilySwitch(checked = form.proactive) {
                form = form.copy(proactive = it)
                saved = false
            }
        }
        NumberField(stringResource(R.string.relation_max_per_day), form.maxPerDay) {
            form = form.copy(maxPerDay = it)
            saved = false
        }
        NumberField(stringResource(R.string.relation_min_gap), form.minGap) {
            form = form.copy(minGap = it)
            saved = false
        }
        NumberField(stringResource(R.string.relation_absence), form.absence) {
            form = form.copy(absence = it)
            saved = false
        }
        NumberField(stringResource(R.string.relation_habit_miss), form.habitDays) {
            form = form.copy(habitDays = it)
            saved = false
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedTextField(
                value = form.quietStart,
                onValueChange = {
                    form = form.copy(quietStart = it)
                    saved = false
                },
                label = { Text(stringResource(R.string.relation_quiet_start)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.quietEnd,
                onValueChange = {
                    form = form.copy(quietEnd = it)
                    saved = false
                },
                label = { Text(stringResource(R.string.relation_quiet_end)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.relation_quiet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!form.valid) {
            Text(stringResource(R.string.relation_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = parsed != null, onClick = {
                parsed?.let {
                    vm.saveRelation(it)
                    saved = true
                }
            }) { Text(stringResource(R.string.relation_save)) }
            TextButton(onClick = {
                vm.checkNow()
                checked = true
            }, modifier = Modifier.tapTarget()) { Text(stringResource(R.string.relation_check_now)) }
        }
        if (saveFailed) InlineError("保存失败，请重试。")
        if (saved) Text(stringResource(R.string.relation_saved), style = MaterialTheme.typography.bodySmall)
        if (checked) {
            Text(
                stringResource(R.string.relation_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoundaryCard(relationship: Relationship, config: RelationSettings, vm: RelationshipViewModel) {
    val today = LocalDate.now().toString()
    val used = if (relationship.proactiveDate == today) relationship.proactiveCount else 0
    val last = lastProactiveText(relationship.lastProactiveAt)
    SectionCard(stringResource(R.string.relation_boundary)) {
        Text(stringResource(R.string.relation_today_count, used, config.maxPerDay), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.relation_last_proactive, last),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.relation_overreach),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedButton(
                onClick = { vm.saveRelation(RelationSettings.degraded()) },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.relation_degrade))
            }
            TextButton(onClick = { vm.saveRelation(RelationSettings()) }) {
                Text(stringResource(R.string.relation_restore))
            }
        }
        Text(
            stringResource(R.string.relation_never_do),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalLilyPalette.current.ink,
            modifier = Modifier.padding(top = Space.sm),
        )
        SettingsNote(stringResource(R.string.relation_boundary_text))
    }
}

@Composable
private fun SectionCard(title: String, emphasized: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalLilyPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = if (emphasized) palette.accentSoft else palette.surface,
        border = BorderStroke(1.dp, palette.line2),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = palette.ink)
            content()
        }
    }
}

@Composable
private fun AddRow(value: String, onChange: (String) -> Unit, hint: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = value.isNotBlank(), onClick = onAdd, modifier = Modifier.tapTarget()) { Text(stringResource(R.string.relation_add)) }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 空状态：虚线框在 Compose 里用细线 + 浅底近似，不留白板。「记忆」「做梦」共用同一套。 */
@Composable
internal fun EmptyBox(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalLilyPalette.current.line2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun lastProactiveText(millis: Long): String = if (millis <= 0) {
    stringResource(R.string.relation_never)
} else {
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

/** Text fields of the proactive card; null when a number or a time is out of range. */
private data class RelationForm(
    val proactive: Boolean,
    val maxPerDay: String,
    val minGap: String,
    val absence: String,
    val habitDays: String,
    val quietStart: String,
    val quietEnd: String,
) {
    val valid: Boolean get() = toSettings(RelationSettings()) != null

    fun toSettings(base: RelationSettings): RelationSettings? {
        val max = maxPerDay.trim().toIntOrNull()?.takeIf { it in RelationSettings.MAX_PER_DAY_RANGE } ?: return null
        val gap = minGap.trim().toIntOrNull()?.takeIf { it in RelationSettings.MIN_GAP_RANGE } ?: return null
        val absence = absence.trim().toIntOrNull()?.takeIf { it in RelationSettings.ABSENCE_RANGE } ?: return null
        val habit = habitDays.trim().toIntOrNull()?.takeIf { it in RelationSettings.HABIT_MISS_RANGE } ?: return null
        if (ProactiveDecision.parseMinutes(quietStart) == null) return null
        if (ProactiveDecision.parseMinutes(quietEnd) == null) return null
        return base.copy(
            proactive = proactive,
            maxPerDay = max,
            minGapHours = gap,
            absenceHours = absence,
            habitMissDays = habit,
            quietStart = quietStart.trim(),
            quietEnd = quietEnd.trim(),
        )
    }

    companion object {
        fun of(r: RelationSettings) = RelationForm(
            proactive = r.proactive,
            maxPerDay = r.maxPerDay.toString(),
            minGap = r.minGapHours.toString(),
            absence = r.absenceHours.toString(),
            habitDays = r.habitMissDays.toString(),
            quietStart = r.quietStart,
            quietEnd = r.quietEnd,
        )
    }
}
