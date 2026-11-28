package dev.lily.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个对话的「聊天信息」页，结构照微信：她在最上面，往下是她的资料与入口，
 * 再往下是这一处对话的设置，最下面是删除。
 *
 * 这一页只放真的能跑的东西 —— 置顶、免打扰、只对本对话生效的背景，
 * 数据层还没有这些字段，所以先不做成点不动的开关。
 * 「删除」是真的删人：索引、会话、照片、记忆、关系、通话记录一起清空。
 */
@Composable
internal fun ChatInfoScreen(
    graph: AppGraph,
    conversation: AppGraph.Conversation,
    onBack: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenCompanionship: () -> Unit,
    onOpenAppearance: () -> Unit,
    onDeleted: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val name = settings.persona.nameOr(stringResource(R.string.app_name))
    // 称呼跟角色的性别走：她是「她」，他是「他」，没指定就是「TA」。
    val ta = settings.persona.gender.pronoun
    val agents by graph.agentDirectory.agents.collectAsStateWithLifecycle()
    val primaryState by graph.agentDirectory.primary.collectAsStateWithLifecycle()
    val blocked = agents.firstOrNull { it.id == conversation.id }?.blocked
        ?: (conversation.id == dev.lily.app.persona.AgentDirectory.PRIMARY_ID && primaryState.blocked)
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    SettingsSubpage(title = "聊天信息", onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PortraitAvatar(graph, 64.dp, store = conversation.portrait)
            Spacer(Modifier.height(Space.md))
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                "你们之间的对话",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink3,
            )
            TextButton(onClick = onOpenPersona) {
                Text("查看主页", color = palette.accent, style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(color = palette.line)
        Spacer(Modifier.height(Space.xl))

        SettingsGroup("关于${ta}") {
            SettingsRow(
                title = "角色设置",
                description = "名字、说话方式、声音",
                onClick = onOpenPersona,
                trailing = { ForwardChevron(palette.ink3) },
            )
            SettingsRow(
                title = "陪伴与回忆",
                description = "关系、记忆、做梦、通话记录",
                onClick = onOpenCompanionship,
                trailing = { ForwardChevron(palette.ink3) },
            )
        }

        Spacer(Modifier.height(Radius.lg * 2))

        SettingsGroup("聊天背景") {
            SettingsRow(
                title = "跟随全局背景",
                description = "在「我的 → 聊天背景」更换，对所有对话都生效",
                onClick = onOpenAppearance,
                trailing = { ForwardChevron(palette.ink3) },
            )
        }

        Spacer(Modifier.height(Radius.lg * 2))

        SettingsGroup("${ta}与你的边界") {
            SettingsRow(
                title = "拉黑${ta}",
                description = "${ta}的主动消息、来电、做梦推送都会停下；历史和你说过的话都还在",
                trailing = {
                    LilySwitch(
                        checked = blocked,
                        onCheckedChange = { on -> graph.setAgentBlocked(conversation.id, on) },
                    )
                },
            )
        }

        Spacer(Modifier.height(Radius.lg * 2))

        OutlinedButton(
            onClick = { if (!deleting) confirming = true },
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(Radius.md),
            border = BorderStroke(1.dp, palette.line),
        ) {
            Text("删除${ta}", color = palette.danger, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(Space.lg))
        Text(
            "删除之后，${ta}会先进「最近删除」，15 天内还能找回来。过了这 15 天，${ta}的对话、照片、记忆、关系档案和通话记录才会真正清空。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink3,
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("删除$name？") },
            text = {
                Text("${ta}会先被放进「最近删除」，15 天内随时可以恢复。这 15 天里${ta}的对话、照片、记忆都还留着；到期之后才会真正清空，那之后就找不回来了。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        deleting = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { graph.trashAgent(conversation.id) }
                            deleting = false
                            if (ok) onDeleted()
                        }
                    },
                ) {
                    Text("删除", color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("再想想", color = palette.ink2) }
            },
            containerColor = palette.surface,
        )
    }
}

/** 行尾的右箭头，与对话列表同一颗。 */
@Composable
private fun ForwardChevron(tint: androidx.compose.ui.graphics.Color) {
    Icon(
        Icons.AutoMirrored.Outlined.ArrowForwardIos,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(IconSize.sm),
    )
}
