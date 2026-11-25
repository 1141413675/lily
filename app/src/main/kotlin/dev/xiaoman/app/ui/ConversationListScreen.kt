package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.persona.PersonaSettings
import dev.xiaoman.app.portrait.PortraitStore
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.ChatItem

internal data class ConversationListEntry(
    val id: String,
    val name: String,
    val portrait: PortraitStore,
    val session: AgentSession,
)

@Composable
internal fun ConversationListScreen(
    graph: AppGraph,
    conversations: List<ConversationListEntry>,
    onCreateAgent: (String) -> Boolean,
    onOpenConversation: (String) -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var agentNameDraft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(palette.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.page, top = Space.xl, end = Space.lg, bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_chat),
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = {
                agentNameDraft = ""
                createDialog = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建 Agent", tint = palette.ink, modifier = Modifier.size(IconSize.xl))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(conversations, key = { it.id }) { entry ->
                ConversationRow(graph, entry) { onOpenConversation(entry.id) }
            }
        }
    }
    if (createDialog) {
        CreateAgentDialog(
            name = agentNameDraft,
            onNameChange = { agentNameDraft = it },
            onDismiss = { createDialog = false },
            onCreate = { name ->
                createDialog && onCreateAgent(name).also { created -> if (created) createDialog = false }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    graph: AppGraph,
    entry: ConversationListEntry,
    onOpen: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    val context = LocalContext.current
    val items by entry.session.items.collectAsStateWithLifecycle()
    val preview = remember(items, context) {
        items.asReversed().firstNotNullOfOrNull { item ->
            when (item) {
                is ChatItem.User -> if (item.recalled) {
                    context.getString(R.string.chat_list_recalled_preview)
                } else {
                    item.text.takeIf { it.isNotBlank() }
                }
                is ChatItem.Assistant -> item.text.takeIf { it.isNotBlank() }
                is ChatItem.UserImage, is ChatItem.AssistantImage -> context.getString(R.string.chat_list_image_preview)
                is ChatItem.UserSticker -> context.getString(R.string.chat_list_sticker_preview)
                is ChatItem.UserVoice, is ChatItem.AssistantVoice -> context.getString(R.string.chat_list_voice_preview)
                is ChatItem.UserFeedShare -> context.getString(R.string.chat_list_shared_post_preview)
                is ChatItem.CallRecord, is ChatItem.Progress, is ChatItem.Error, is ChatItem.Question -> null
            }
        } ?: context.getString(R.string.chat_list_empty_preview)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .semantics { role = Role.Button }.pressClick(pressedScale = 0.98f, onClick = onOpen)
                .padding(horizontal = Space.page, vertical = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PortraitAvatar(graph, size = 52.dp, store = entry.portrait)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                contentDescription = null,
                tint = palette.ink3,
                modifier = Modifier.size(IconSize.sm),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = Space.page + 52.dp + Space.lg, end = Space.page),
            color = palette.line,
        )
    }
}

@Composable
private fun CreateAgentDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Boolean,
) {
    var saveFailed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val trimmed = name.trim()
    val tooLong = trimmed.length > PersonaSettings.MAX_NAME
    val canCreate = trimmed.isNotEmpty() && !tooLong
    val error = when {
        tooLong -> "名称最多 ${PersonaSettings.MAX_NAME} 个字符"
        saveFailed -> "创建失败，请重试"
        else -> null
    }
    val submit = { if (canCreate) saveFailed = !onCreate(trimmed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Agent") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    onNameChange(it.replace('\n', ' ').replace('\r', ' '))
                    saveFailed = false
                },
                label = { Text("角色名称") },
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) ({ Text(error) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canCreate) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
