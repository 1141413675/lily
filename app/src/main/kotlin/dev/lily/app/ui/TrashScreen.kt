package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.persona.AgentDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保留期，与 LilyRoot 里的清理口径一致。 */
private const val TRASH_KEEP_MS = 15L * 24L * 60L * 60L * 1000L

private data class TrashEntry(val id: String, val name: String, val deletedAt: Long, val pronoun: String)

/**
 * 「最近删除」：删掉的人先住在这儿。
 * 这期间她的对话、照片、记忆一个字节都没动，恢复就是把时间戳抹掉；
 * 过了保留期，启动时会被真正清空 —— 清空之后不可逆。
 */
@Composable
internal fun TrashScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    val agents by graph.agentDirectory.agents.collectAsStateWithLifecycle()
    val primaryState by graph.agentDirectory.primary.collectAsStateWithLifecycle()
    val primarySettings by graph.settings.settings.collectAsStateWithLifecycle()
    val appName = stringResource(R.string.app_name)
    val trash = remember(agents, primaryState, primarySettings.persona.nickname) {
        buildList {
            primaryState.deletedAt?.let { since ->
                add(TrashEntry(AgentDirectory.PRIMARY_ID, primarySettings.persona.nameOr(appName), since, primarySettings.persona.gender.pronoun))
            }
            agents.filter { it.deletedAt != null }.forEach { profile ->
                add(TrashEntry(profile.id, profile.persona.nameOr(appName), profile.deletedAt ?: 0L, profile.persona.gender.pronoun))
            }
        }.sortedByDescending { it.deletedAt }
    }
    val scope = rememberCoroutineScope()
    var purging by remember { mutableStateOf<TrashEntry?>(null) }

    SettingsSubpage(title = "最近删除", onBack = onBack) {
        Text(
            "这些人都还在，只是先住这儿。保留 15 天，到期后真正清空。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink3,
        )
        Spacer(Modifier.height(20.dp))

        if (trash.isEmpty()) {
            Text(
                "现在没有人在这里。",
                style = bodyStyle,
                color = palette.ink2,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }

        trash.forEach { entry ->
            val portrait = graph.conversation(entry.id)?.portrait ?: graph.portrait
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PortraitAvatar(graph, 44.dp, store = portrait)
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = bodyStyle,
                        fontWeight = FontWeight.Medium,
                        color = palette.ink,
                    )
                    Text(
                        "还有 ${remainingDays(entry.deletedAt)} 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink3,
                    )
                }
                TextButton(onClick = { scope.launch { graph.restoreAgent(entry.id) } }) {
                    Text("恢复", color = palette.accent)
                }
                TextButton(onClick = { purging = entry }) {
                    Text("彻底删除", color = palette.danger)
                }
            }
            HorizontalDivider(color = palette.line)
        }
    }

    purging?.let { entry ->
        AlertDialog(
            onDismissRequest = { purging = null },
            title = { Text("彻底删除${entry.name}？") },
            text = {
                Text("${entry.pronoun}的对话记录、照片、记忆、关系档案、通话记录会立刻清空，这一次没有后悔的余地。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = entry.id
                        purging = null
                        scope.launch { withContext(Dispatchers.IO) { graph.purgeAgent(id) } }
                    },
                ) {
                    Text("彻底删除", color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { purging = null }) { Text("取消", color = palette.ink2) }
            },
            containerColor = palette.surface,
        )
    }
}

private fun remainingDays(deletedAt: Long?): Int {
    val since = deletedAt ?: return 0
    val left = TRASH_KEEP_MS - (System.currentTimeMillis() - since)
    return ((left + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
}

/** Compose 的 typography 没有 body，与工程内其它页保持同一口径，直接取 TypeScale。 */
private val bodyStyle = TextStyle(fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line)
