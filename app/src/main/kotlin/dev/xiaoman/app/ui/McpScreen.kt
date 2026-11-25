package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.mcp.CatalogServer
import dev.xiaoman.mcp.ConfirmPolicy
import dev.xiaoman.mcp.CustomMcpServer
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.ServerStatus
import kotlinx.coroutines.launch

/** Services that work on Android, grouped by category. */
private val catalog: List<CatalogServer> by lazy { McpCatalog.builtIn.availableOn(McpPlatform.ANDROID) }

/** Ids ticked by 推荐组合: free or easy to obtain, read-only. */
private val RECOMMENDED = setOf("amap", "aihot")

/** One-tap MCP services: tick a service, paste its key, save. Changes apply from the next chat message. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun McpScreen(graph: AppGraph, onBack: (() -> Unit)? = null) {
    val store = graph.mcpSettings
    var selection by remember { mutableStateOf(store.current) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val status = remember { mutableStateMapOf<String, ServerStatus>() }
    val scope = rememberCoroutineScope()
    val palette = LocalXiaomanPalette.current

    fun update(next: McpSelection) {
        selection = next
        saved = false
    }

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
            Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.block),
        ) {
            SettingsNote(stringResource(R.string.mcp_intro))
            SettingsGroup("已启用") {
                SettingsRow(
                    title = stringResource(R.string.mcp_enabled_count, selection.enabled.size + selection.custom.size),
                    trailing = {
                        OutlinedButton(onClick = { update(selection.copy(enabled = selection.enabled + RECOMMENDED)) }) {
                            Text(stringResource(R.string.mcp_recommended))
                        }
                    },
                )
            }

            catalog.groupBy { it.category }.forEach { (category, servers) ->
                SettingsGroup(category) {
                    servers.forEach { server ->
                        ServiceCard(
                            server = server,
                            selection = selection,
                            status = status[server.id],
                            onChange = ::update,
                        )
                    }
                }
            }

            SettingsGroup(stringResource(R.string.mcp_custom_title)) {
                CustomServers(selection, status, ::update)
            }
        }
        Surface(color = palette.surface) {
            Column {
                HorizontalDivider(color = palette.line)
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Button(onClick = {
                        store.save(selection)
                        saved = true
                    }) { Text(stringResource(R.string.settings_save)) }
                    OutlinedButton(enabled = !testing, onClick = {
                        testing = true
                        scope.launch {
                            try {
                                status.clear()
                                graph.testMcp(selection).forEach { status[it.name] = it }
                            } finally {
                                testing = false
                            }
                        }
                    }) { Text(stringResource(if (testing) R.string.mcp_testing else R.string.mcp_test)) }
                    if (saved) {
                        Text(
                            stringResource(R.string.settings_saved),
                            modifier = Modifier.align(Alignment.CenterVertically)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.ink2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(server: CatalogServer, selection: McpSelection, status: ServerStatus?, onChange: (McpSelection) -> Unit) {
    val enabled = server.id in selection.enabled
    val uri = LocalUriHandler.current
    val palette = LocalXiaomanPalette.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = palette.surfaceLow,
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(server.title, style = MaterialTheme.typography.titleSmall, color = palette.ink)
                    Surface(shape = RoundedCornerShape(Radius.sm), color = palette.accentSoft) {
                        Text(
                            stringResource(if (server.official) R.string.mcp_official else R.string.mcp_community),
                            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accentOnSoft,
                        )
                    }
                }
                XiaomanSwitch(checked = enabled) { on ->
                    onChange(selection.copy(enabled = if (on) selection.enabled + server.id else selection.enabled - server.id))
                }
            }
            Text(server.description, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
            if (enabled) {
                server.keys.forEach { key ->
                    OutlinedTextField(
                        value = selection.keys[key.name].orEmpty(),
                        onValueChange = { onChange(selection.copy(keys = selection.keys + (key.name to it.trim()))) },
                        label = { Text(key.label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    key.help?.let { help -> TextButton(onClick = { uri.openUri(help) }) { Text(stringResource(R.string.mcp_apply_key)) } }
                }
                val missing = server.keys.filter { selection.keys[it.name].isNullOrBlank() }.map { it.label }
                if (missing.isNotEmpty()) {
                    Text(stringResource(R.string.mcp_missing_key, missing.joinToString("、")), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            status?.let { StatusLine(it) }
        }
    }
}

@Composable
private fun StatusLine(status: ServerStatus) {
    val palette = LocalXiaomanPalette.current
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = if (status.connected) palette.accentSoft else palette.errorContainer,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            if (status.connected) stringResource(R.string.mcp_connected, status.toolCount)
            else stringResource(R.string.mcp_failed, status.error ?: "?"),
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.connected) palette.accentOnSoft else palette.onErrorContainer,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CustomServers(selection: McpSelection, status: Map<String, ServerStatus>, onChange: (McpSelection) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(ConfirmPolicy.WRITES) }
    val problem = url.takeIf { it.isNotBlank() }?.let { McpCatalog.validateCustomUrl(it) }
    val palette = LocalXiaomanPalette.current

    SettingsNote(stringResource(R.string.mcp_custom_hint))
    selection.custom.forEachIndexed { i, c ->
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            color = palette.surfaceLow,
        ) {
            Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { c.url }, style = MaterialTheme.typography.bodyLarge)
                        Text(c.url.substringBefore('?'), style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Normal,
                        ), color = palette.ink2)
                    }
                    TextButton(onClick = { onChange(selection.copy(custom = selection.custom.filterIndexed { j, _ -> j != i })) }) {
                        Text(stringResource(R.string.mcp_remove))
                    }
                }
                status["custom-${i + 1}"]?.let { StatusLine(it) }
            }
        }
    }
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.mcp_custom_name)) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = url, onValueChange = { url = it.trim() }, label = { Text(stringResource(R.string.mcp_custom_url)) },
        isError = problem != null, supportingText = { problem?.let { Text(stringResource(R.string.mcp_custom_invalid, it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = auth, onValueChange = { auth = it }, label = { Text(stringResource(R.string.mcp_custom_auth)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.mcp_custom_confirm), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        listOf(
            ConfirmPolicy.WRITES to R.string.mcp_confirm_writes,
            ConfirmPolicy.ALL to R.string.mcp_confirm_all,
            ConfirmPolicy.NONE to R.string.mcp_confirm_none,
        ).forEach { (policy, label) ->
            FilterChip(selected = confirm == policy, onClick = { confirm = policy }, label = { Text(stringResource(label)) })
        }
    }
    AssistChip(
        enabled = url.isNotBlank() && problem == null,
        onClick = {
            val headers = auth.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to it) }.orEmpty()
            onChange(selection.copy(custom = selection.custom + CustomMcpServer(title.trim(), url, headers, confirm)))
            title = ""; url = ""; auth = ""; confirm = ConfirmPolicy.WRITES
        },
        label = { Text(stringResource(R.string.mcp_custom_add)) },
    )
}
