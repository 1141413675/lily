package dev.lily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.chat.GroupChat
import dev.lily.app.persona.AgentDirectory
import dev.lily.app.session.ChatItem
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 群聊的三个界面：列表、群内、信息。
 *
 * 出去的路只有一条 —— 从哪进的就退回哪，不做深层的返回栈。
 */
private sealed interface GroupRoute {
    object Index : GroupRoute
    object New : GroupRoute
    data class Room(val id: String) : GroupRoute
    data class Info(val id: String) : GroupRoute
}

@Composable
internal fun GroupHub(graph: AppGraph, onBack: () -> Unit) {
    var route by remember { mutableStateOf<GroupRoute>(GroupRoute.Index) }
    when (val current = route) {
        GroupRoute.Index -> GroupIndexPage(
            graph = graph,
            onBack = onBack,
            onCreate = { route = GroupRoute.New },
            onOpen = { route = GroupRoute.Room(it) },
        )
        GroupRoute.New -> NewGroupPage(
            graph = graph,
            onBack = { route = GroupRoute.Index },
            onCreated = { route = GroupRoute.Room(it) },
        )
        is GroupRoute.Room -> GroupRoomPage(
            graph = graph,
            groupId = current.id,
            onBack = { route = GroupRoute.Index },
            onOpenInfo = { route = GroupRoute.Info(current.id) },
        )
        is GroupRoute.Info -> GroupInfoPage(
            graph = graph,
            groupId = current.id,
            onBack = { route = GroupRoute.Room(current.id) },
            onDissolved = { route = GroupRoute.Index },
        )
    }
}

@Composable
private fun GroupIndexPage(
    graph: AppGraph,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val palette = LocalLilyPalette.current
    val groups by graph.groupChats.groups.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(palette.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.page, top = Space.xl, end = Space.lg, bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = palette.ink,
                modifier = Modifier.size(IconSize.xl).clickable(onClick = onBack),
            )
            Spacer(Modifier.width(Space.lg))
            Text("群聊", style = MaterialTheme.typography.titleLarge, color = palette.ink, modifier = Modifier.weight(1f))
            Text(
                "新建",
                color = palette.accent,
                fontSize = TypeScale.bodySmall.size,
                lineHeight = TypeScale.bodySmall.line,
                modifier = Modifier.clickable(onClick = onCreate).padding(horizontal = Space.sm, vertical = Space.sm),
            )
        }
        if (groups.isEmpty()) {
            Text(
                "还没有群。建一个，把她们拉进来一起聊。",
                color = palette.ink2,
                fontSize = TypeScale.body.size,
                lineHeight = TypeScale.body.line,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.block),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(groups, key = { it.id }) { group ->
                    GroupRow(graph, group, onClick = { onOpen(group.id) })
                }
            }
        }
    }
}

@Composable
private fun GroupRow(graph: AppGraph, group: GroupChat, onClick: () -> Unit) {
    val palette = LocalLilyPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatars(graph, group.memberIds, 44.dp)
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text(
                group.displayName(graph::agentName),
                color = palette.ink,
                fontSize = TypeScale.body.size,
                lineHeight = TypeScale.body.line,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${group.memberIds.size} 人")
                    if (group.autoNamed) append(" · 默认群名")
                },
                color = palette.ink3,
                fontSize = TypeScale.bodySmall.size,
                lineHeight = TypeScale.bodySmall.line,
            )
        }
        Text("›", color = palette.ink3, fontSize = 18.sp)
    }
}

/** 两三个头像错开摞在一起，一眼看出这是个群而不是一个人。 */
@Composable
private fun GroupAvatars(graph: AppGraph, memberIds: List<String>, size: Dp) {
    val palette = LocalLilyPalette.current
    Box(Modifier.size(size)) {
        memberIds.take(2).reversed().forEachIndexed { index, id ->
            val store = graph.conversation(id)?.portrait
            val inner = if (memberIds.size > 1) size * 0.68f else size
            Box(Modifier.offset(x = if (memberIds.size > 1) size * 0.32f * index else 0.dp)) {
                if (store != null) {
                    PortraitAvatar(graph, inner, Modifier.clip(CircleShape), store = store)
                } else {
                    Box(Modifier.size(inner).clip(CircleShape).background(palette.surface2))
                }
            }
        }
    }
}

@Composable
private fun NewGroupPage(graph: AppGraph, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val palette = LocalLilyPalette.current
    val agents by graph.agentDirectory.agents.collectAsStateWithLifecycle()
    val primaryState by graph.agentDirectory.primary.collectAsStateWithLifecycle()
    val appName = androidx.compose.ui.res.stringResource(dev.lily.app.R.string.app_name)
    val people = buildList {
        if (primaryState.deletedAt == null) add(AgentDirectory.PRIMARY_ID to graph.agentName(AgentDirectory.PRIMARY_ID))
        agents.filter { it.deletedAt == null }.forEach { add(it.id to it.persona.nameOr(appName)) }
    }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var failed by remember { mutableStateOf(false) }

    SettingsSubpage(title = "新建群聊", onBack = onBack) {
        Text(
            "至少选两个人。群名先按成员名字拼，之后你和管理员都能改。",
            color = palette.ink2,
            fontSize = TypeScale.bodySmall.size,
            lineHeight = TypeScale.bodySmall.line,
            modifier = Modifier.padding(horizontal = Space.page),
        )
        if (people.size < GroupChat.MIN_MEMBERS) {
            Text(
                "现在就一个人，凑不成群。先去「添加好友」再回来。",
                color = palette.ink2,
                fontSize = TypeScale.body.size,
                lineHeight = TypeScale.body.line,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.block),
            )
            return@SettingsSubpage
        }
        people.forEach { (id, name) ->
            val selected = id in chosen
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { chosen = if (selected) chosen - id else chosen + id }
                    .padding(horizontal = Space.page, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                graph.conversation(id)?.portrait?.let { store ->
                    PortraitAvatar(graph, 36.dp, Modifier.clip(CircleShape), store = store)
                }
                Spacer(Modifier.width(Space.lg))
                Text(name, color = palette.ink, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line, modifier = Modifier.weight(1f))
                LilySwitch(checked = selected, onCheckedChange = { on ->
                    chosen = if (on) chosen + id else chosen - id
                })
            }
        }
        if (failed) {
            Text(
                "建群没成功，再试一次。",
                color = palette.danger,
                fontSize = TypeScale.bodySmall.size,
                lineHeight = TypeScale.bodySmall.line,
                modifier = Modifier.padding(horizontal = Space.page),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${chosen.size} 人",
                color = palette.ink2,
                fontSize = TypeScale.bodySmall.size,
                lineHeight = TypeScale.bodySmall.line,
                modifier = Modifier.weight(1f),
            )
            val enabled = chosen.size >= GroupChat.MIN_MEMBERS
            Box(
                Modifier.clip(RoundedCornerShape(Radius.md))
                    .background(if (enabled) palette.accent else palette.surface2)
                    .clickable(enabled = enabled) {
                        val created = graph.groupChats.create(chosen.toList())
                        if (created != null) onCreated(created.id) else failed = true
                    }
                    .padding(horizontal = Space.xl, vertical = Space.md),
            ) {
                Text(
                    "创建群聊",
                    color = if (enabled) palette.accentInk else palette.ink3,
                    fontSize = TypeScale.body.size,
                    lineHeight = TypeScale.body.line,
                )
            }
        }
    }
}

@Composable
private fun GroupRoomPage(graph: AppGraph, groupId: String, onBack: () -> Unit, onOpenInfo: () -> Unit) {
    val palette = LocalLilyPalette.current
    val room = remember(groupId) { graph.groupRoom(groupId) }
    val group = graph.groupChats.find(groupId)
    if (room == null || group == null) {
        onBack()
        return
    }
    val items by room.items.collectAsStateWithLifecycle()
    val busy by room.busy.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by rememberSaveable(groupId) { mutableStateOf("") }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(palette.surface).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.xl, top = Space.xl, end = Space.page, bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = palette.ink,
                modifier = Modifier.size(IconSize.xl).clickable(onClick = onBack),
            )
            Spacer(Modifier.width(Space.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    group.displayName(graph::agentName),
                    color = palette.ink,
                    fontSize = TypeScale.body.size,
                    lineHeight = TypeScale.body.line,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    group.memberIds.joinToString("、") { graph.agentName(it) },
                    color = palette.ink3,
                    fontSize = TypeScale.bodySmall.size,
                    lineHeight = TypeScale.bodySmall.line,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "…",
                color = palette.ink,
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onOpenInfo).padding(horizontal = Space.md, vertical = Space.sm),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.page, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            items(items, key = { it.id }) { item ->
                Box(
                    Modifier.animateItem(
                        fadeInSpec = tween(200),
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ),
                ) {
                    when (item) {
                        is ChatItem.User -> UserBubble(item.text)
                        is ChatItem.Assistant -> AgentBubble(graph, item)
                        else -> Unit
                    }
                }
            }
        }
        if (busy) {
            // 群里成员挨个说话，用三个点表示「有人在回」，跟私聊同一套动效。
            PulsingDots(
                modifier = Modifier.padding(start = Space.page, bottom = Space.sm),
                color = palette.ink3,
                dotSize = 5.dp,
                gap = 3.dp,
                description = "正在回应",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.bubble)).background(palette.surface2)
                    .padding(horizontal = Space.xl, vertical = Space.lg),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = palette.ink,
                        fontSize = TypeScale.body.size,
                        lineHeight = TypeScale.body.line,
                    ),
                    maxLines = 4,
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text("在群里说点什么", color = palette.ink3, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line)
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.width(Space.sm))
            val canSend = draft.isNotBlank() && !busy
            Box(
                Modifier.clip(CircleShape).background(if (canSend) palette.accent else palette.surface2)
                    .clickable(enabled = canSend) {
                        if (room.send(draft)) {
                            graph.chatSounds.sent()
                            draft = ""
                        }
                    }
                    .padding(horizontal = Space.xl, vertical = Space.md),
            ) {
                Text("发送", color = if (canSend) palette.accentInk else palette.ink3, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val palette = LocalLilyPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(Radius.bubble)).background(palette.bubbleUser)
                .padding(horizontal = Space.xl, vertical = Space.md),
        ) {
            Text(
                text,
                color = palette.bubbleUserInk,
                fontSize = TypeScale.body.size,
                lineHeight = TypeScale.body.line,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** 群里的消息各带各的头像和名字 —— 不然一屏气泡分不清谁在说。 */
@Composable
private fun AgentBubble(graph: AppGraph, item: ChatItem.Assistant) {
    val palette = LocalLilyPalette.current
    val from = item.from
    Row(Modifier.fillMaxWidth(0.86f), horizontalArrangement = Arrangement.Start) {
        val store = from?.let { graph.conversation(it)?.portrait }
        if (store != null) PortraitAvatar(graph, 30.dp, Modifier.clip(CircleShape), store = store)
        Spacer(Modifier.width(Space.sm))
        Column {
            Text(
                from?.let(graph::agentName)?.takeIf { it.isNotBlank() } ?: "某人",
                color = palette.ink3,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = Space.xs),
            )
            Box(
                Modifier.clip(RoundedCornerShape(Radius.bubble)).background(palette.bubbleAgent)
                    .padding(horizontal = Space.xl, vertical = Space.md),
            ) {
                Text(item.text, color = palette.bubbleAgentInk, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line)
            }
        }
    }
}

@Composable
private fun GroupInfoPage(graph: AppGraph, groupId: String, onBack: () -> Unit, onDissolved: () -> Unit) {
    val palette = LocalLilyPalette.current
    val groups by graph.groupChats.groups.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == groupId }
    if (group == null) {
        onBack()
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var dissolving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

    SettingsSubpage(title = "群聊信息", onBack = onBack) {
        SettingsGroup("群名") {
            SettingsRow(
                title = group.displayName(graph::agentName),
                description = if (group.autoNamed) "按成员名字拼的，群主和管理员可以改" else "群主和管理员可以改",
                onClick = {
                    nameDraft = group.name.orEmpty()
                    renaming = true
                },
            ) { Text("改", color = palette.accent, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line) }
        }
        SettingsGroup("成员（${group.memberIds.size}）") {
            group.memberIds.forEach { id ->
                val isAdmin = group.admins.contains(id)
                SettingsRow(
                    title = graph.agentName(id),
                    description = when {
                        isAdmin -> "管理员"
                        group.ownerId == id -> "群主"
                        else -> null
                    },
                    onClick = null,
                ) {
                    if (isAdmin) {
                        TextButton(onClick = { graph.groupChats.setAdmin(groupId, id, false) }) {
                            Text("取消管理员", color = palette.ink2, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line)
                        }
                    } else {
                        TextButton(onClick = { graph.groupChats.setAdmin(groupId, id, true) }) {
                            Text("设为管理员", color = palette.accent, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line)
                        }
                    }
                    if (group.memberIds.size > GroupChat.MIN_MEMBERS) {
                        // 移人不可逆：先确认，再移。
                        TextButton(onClick = { removing = id }) {
                            Text("移出", color = palette.danger, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line)
                        }
                    }
                }
            }
        }
        notice?.let {
            Text(
                it,
                color = palette.ink2,
                fontSize = TypeScale.bodySmall.size,
                lineHeight = TypeScale.bodySmall.line,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.sm),
            )
        }
        Text(
            "解散群聊",
            color = palette.danger,
            fontSize = TypeScale.body.size,
            lineHeight = TypeScale.body.line,
            modifier = Modifier.fillMaxWidth()
                .clickable { dissolving = true }
                .padding(horizontal = Space.page, vertical = Space.block),
        )
    }

    removing?.let { memberId ->
        val memberName = group.memberIds.firstOrNull { it == memberId }
            ?.let { graph.agentName(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "这个人"
        ConfirmDangerDialog(
            title = "把${memberName}移出群？",
            text = "移出后对方就看不到这个群了，也没法自己回来。",
            confirmLabel = "移出",
            onConfirm = {
                if (!graph.groupChats.removeMember(groupId, memberId)) {
                    notice = "群至少要留两个人，再移就散了。"
                }
            },
            onDismiss = { removing = null },
        )
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改群名") },
            text = {
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(GroupChat.MAX_NAME) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = palette.ink, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line),
                    decorationBox = { inner ->
                        if (nameDraft.isEmpty()) Text("最多 ${GroupChat.MAX_NAME} 个字", color = palette.ink3, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line)
                        inner()
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (graph.groupChats.rename(groupId, nameDraft)) renaming = false
                }) { Text("改好") }
            },
            dismissButton = {
                TextButton(onClick = {
                    graph.groupChats.resetName(groupId)
                    renaming = false
                }) { Text("用默认名") }
            },
        )
    }
    if (dissolving) {
        AlertDialog(
            onDismissRequest = { dissolving = false },
            title = { Text("解散这个群？") },
            text = { Text("群里的对话会一起消失，她们各自的私聊不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    graph.groupRoom(groupId)?.clear()
                    graph.groupChats.delete(groupId)
                    dissolving = false
                    onDissolved()
                }) { Text("解散", color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { dissolving = false }) { Text("再想想") } },
        )
    }
}
