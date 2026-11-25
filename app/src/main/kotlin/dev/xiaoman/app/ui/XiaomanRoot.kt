package dev.xiaoman.app.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.persona.AgentDirectory
import dev.xiaoman.app.persona.PersonaSettings

/** 内部路由。底栏只展示动态 / 聊天 / 我的，聊天固定在正中。 */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    FEED(R.string.nav_feed, Icons.Outlined.DynamicFeed),
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Outlined.Chat),
    ME(R.string.nav_me, Icons.Outlined.Person),
}

private enum class AgentEditorPage { PERSONA, VOICE_CLONE, COMPANIONSHIP, RELATION, MEMORY, DREAM, CALL_RECORDS }

@Composable
fun XiaomanRoot(graph: AppGraph) {
    // 聊天列表是主页面，单独保存会话详情的导航和草稿。
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var conversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorPage by rememberSaveable { mutableStateOf(AgentEditorPage.PERSONA) }
    var dreamBackPage by rememberSaveable { mutableStateOf(AgentEditorPage.COMPANIONSHIP) }
    var callId by rememberSaveable { mutableStateOf<String?>(null) }
    var startOutgoing by rememberSaveable { mutableStateOf(false) }
    // 动态 ↔ 记忆互相跳转时，把要打开的那一条带到设置里的记忆页。
    var feedFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // 发布动态时铺满内容区并收起底栏，避免写到一半切走。
    var feedEditing by remember { mutableStateOf(false) }
    // 二级页（记忆/模型/人设等）打开时收起底栏。
    var settingsSubpage by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<AppGraph.Conversation, ViewingImage>?>(null) }
    val conversation = conversationId?.let(graph::conversation)
    val editor = editorId?.let(graph::conversation)
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 切页时保留各屏的滚动位置与输入草稿。
    val stateHolder = rememberSaveableStateHolder()
    // 页面转场统一节奏：淡入 + 轻微上移，和二级页、底栏共用同一组 token。
    val pageIn = remember { Animatable(0f) }
    val density = LocalDensity.current
    val pageSpec = enterTween()
    LaunchedEffect(tab, conversationId, editorId) {
        pageIn.snapTo(0f)
        pageIn.animateTo(1f, pageSpec)
    }

    fun openContent(next: Tab) {
        conversationId = null
        editorId = null
        memoryFocus = null
        tab = next
    }

    fun openConversation(id: String) {
        tab = Tab.CHAT
        conversationId = id
        editorId = null
        memoryFocus = null
    }

    fun openEditor(id: String, page: AgentEditorPage = AgentEditorPage.PERSONA) {
        editorPage = page
        editorId = id
    }

    fun openDream(from: AgentEditorPage) {
        dreamBackPage = from
        editorPage = AgentEditorPage.DREAM
    }

    fun openVoiceCall(id: String) {
        val active = graph.activeCallAgentId()
        startOutgoing = active == null
        callId = active ?: id
    }

    // 来电或通知只打开已有的会话，避免从旧通知重新拨号。
    LaunchedEffect(Unit) {
        graph.openCall.collect { id ->
            if (id != null) {
                if (graph.conversation(id)?.call?.state?.value?.active == true) {
                    startOutgoing = false
                    callId = id
                }
                graph.openCall.compareAndSet(id, null)
            }
        }
    }
    LaunchedEffect(Unit) {
        graph.openChat.collect { id ->
            if (id != null) {
                if (graph.conversation(id) != null) openConversation(id)
                graph.openChat.compareAndSet(id, null)
            }
        }
    }
    // 通话结束或最小化后回到对应会话。
    val calling = callId?.let(graph::conversation)
    if (calling != null) {
        CallScreen(
            graph = graph,
            conversation = calling,
            startOutgoing = startOutgoing,
            onOutgoingHandled = { startOutgoing = false },
            onMinimize = {
                callId = null
                openConversation(calling.id)
            },
            onOpenSettings = {
                calling.call.endCall()
                startOutgoing = false
                callId = null
                openContent(Tab.ME)
            },
            onOpenChat = {
                calling.call.endCall()
                startOutgoing = false
                callId = null
                openConversation(calling.id)
            },
            onEnd = {
                startOutgoing = false
                callId = null
                openConversation(calling.id)
            },
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                // 单独对话和二级页收起底栏。
                // 底栏用 180/140ms 淡入淡出 + 8dp 滑落，键盘弹起时内容不再瞬间跳一格。
                AnimatedVisibility(
                    visible = conversation == null && editor == null && !keyboardOpen &&
                        !(tab == Tab.FEED && feedEditing) && !(tab == Tab.ME && settingsSubpage),
                    enter = fadeIn(enterTween()) + slideInVertically(slideTween()) { it / 3 },
                    exit = fadeOut(exitTween()) + slideOutVertically(exitSlideTween()) { it / 3 },
                ) {
                    BottomBar(current = tab, onSelect = ::openContent)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                val lift = with(density) { (1f - pageIn.value) * 14.dp.toPx() }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = pageIn.value
                        translationY = lift
                    },
                ) {
                    val screenKey = when {
                        editor != null && editorPage == AgentEditorPage.PERSONA -> "agent-editor-${editor.id}"
                        editor != null -> "agent-${editor.id}-${editorPage.name}"
                        conversation?.isPrimary == true -> Tab.CHAT.name
                        conversation != null -> "chat-${conversation.id}"
                        tab == Tab.CHAT -> "chat-list"
                        else -> tab.name
                    }
                    stateHolder.SaveableStateProvider(screenKey) {
                        if (editor != null) {
                            val settings by editor.effectiveSettings.collectAsStateWithLifecycle()
                            when (editorPage) {
                                AgentEditorPage.PERSONA -> PersonaScreen(
                                    graph = graph,
                                    settings = settings,
                                    portraitStore = editor.portrait,
                                    sceneryStore = editor.scenery,
                                    onSaveSettings = editor::saveSettings,
                                    onBack = { editorId = null },
                                    isPrimary = editor.isPrimary,
                                    onOpenVoiceClone = { editorPage = AgentEditorPage.VOICE_CLONE },
                                )
                                AgentEditorPage.VOICE_CLONE -> VoiceCloneScreen(
                                    graph = graph,
                                    settings = settings,
                                    onSaveSettings = editor::saveSettings,
                                    isVoiceUsedElsewhere = { graph.isVoiceUsedByAnotherAgent(it, editor.id) },
                                    onBack = { editorPage = AgentEditorPage.PERSONA },
                                )
                                AgentEditorPage.COMPANIONSHIP -> CompanionshipScreen(
                                    graph = graph,
                                    conversation = editor,
                                    onBack = { editorId = null },
                                    onOpenRelationship = { editorPage = AgentEditorPage.RELATION },
                                    onOpenMemory = { editorPage = AgentEditorPage.MEMORY },
                                    onOpenCallRecords = { editorPage = AgentEditorPage.CALL_RECORDS },
                                    onOpenDream = { openDream(AgentEditorPage.COMPANIONSHIP) },
                                )
                                AgentEditorPage.RELATION -> RelationshipScreen(
                                    conversation = editor,
                                    onBack = { editorPage = AgentEditorPage.COMPANIONSHIP },
                                )
                                AgentEditorPage.MEMORY -> MemoryScreen(
                                    conversation = editor,
                                    focusPermalink = memoryFocus.takeIf { tab == Tab.FEED && editor.isPrimary },
                                    onFocusConsumed = { memoryFocus = null },
                                    onBack = {
                                        memoryFocus = null
                                        if (tab == Tab.FEED) editorId = null
                                        else editorPage = AgentEditorPage.COMPANIONSHIP
                                    },
                                    onOpenDream = { openDream(AgentEditorPage.MEMORY) },
                                )
                                AgentEditorPage.DREAM -> DreamScreen(
                                    conversation = editor,
                                    onBack = { editorPage = dreamBackPage },
                                )
                                AgentEditorPage.CALL_RECORDS -> CallRecordsScreen(
                                    conversation = editor,
                                    onBack = { editorPage = AgentEditorPage.COMPANIONSHIP },
                                    onCallBack = { openVoiceCall(editor.id) },
                                )
                            }
                        } else when (tab) {
                            Tab.CHAT -> if (conversation != null) {
                                ChatScreen(
                                    graph,
                                    conversation = conversation,
                                    onBack = { openContent(Tab.CHAT) },
                                    onOpenSettings = { openContent(Tab.ME) },
                                    onOpenAgentSettings = { openEditor(conversation.id) },
                                    onOpenCompanionship = { openEditor(conversation.id, AgentEditorPage.COMPANIONSHIP) },
                                    onCall = { openVoiceCall(conversation.id) },
                                    onViewImage = { image -> viewing = image?.let { conversation to it } },
                                )
                            } else {
                                val primarySettings by graph.settings.settings.collectAsStateWithLifecycle()
                                val addedAgents by graph.agentDirectory.agents.collectAsStateWithLifecycle()
                                val appName = stringResource(R.string.app_name)
                                val roster = buildList {
                                    val primary = checkNotNull(graph.conversation(AgentDirectory.PRIMARY_ID))
                                    add(ConversationListEntry(
                                        primary.id, primarySettings.persona.nameOr(appName), primary.portrait, primary.session,
                                    ))
                                    addedAgents.forEach { profile ->
                                        val role = checkNotNull(graph.conversation(profile.id))
                                        add(ConversationListEntry(role.id, profile.persona.nameOr(appName), role.portrait, role.session))
                                    }
                                }
                                ConversationListScreen(
                                    graph,
                                    conversations = roster,
                                    onCreateAgent = { name ->
                                        val created = graph.agentDirectory.create(PersonaSettings(nickname = name))
                                        if (created != null) {
                                            openConversation(created.id)
                                            openEditor(created.id)
                                        }
                                        created != null
                                    },
                                    onOpenConversation = ::openConversation,
                                )
                            }
                            Tab.FEED -> FeedScreen(
                                graph,
                                focusPostId = feedFocus,
                                onFocusConsumed = { feedFocus = null },
                                onEditingChange = { feedEditing = it },
                                onOpenMemory = { permalink ->
                                    memoryFocus = permalink
                                    openEditor(AgentDirectory.PRIMARY_ID, AgentEditorPage.MEMORY)
                                },
                                onOpenChat = { openConversation(AgentDirectory.PRIMARY_ID) },
                            )
                            Tab.ME -> SettingsScreen(
                                graph,
                                onSubpageChange = { settingsSubpage = it },
                            )
                        }
                    }
                }
            }
        }
        viewing?.let { (owner, img) ->
            ImageViewer(
                path = img.file,
                onClose = { viewing = null },
                onSave = {
                    val ok = saveToGallery(context, img.file)
                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    owner.session.removeItem(img.id)
                    // 她分享的图来自图库，删消息不删图库里的原图。
                    if (img.mine) owner.photos.delete(img.file)
                    viewing = null
                },
            )
        }
    }
}

/** 动态、聊天列表和我的共用主导航的选中态。 */
@Composable
internal fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(
        Modifier.fillMaxWidth().background(palette.surface).navigationBarsPadding(),
    ) {
        HorizontalDivider(color = palette.line)
        Row(
            Modifier.fillMaxWidth().height(SizeTokens.navHeight).padding(horizontal = Space.lg).selectableGroup(),
        ) {
            val itemSize = Modifier.weight(1f).fillMaxHeight()
            val feedSelected = current == Tab.FEED
            val chatSelected = current == Tab.CHAT
            val meSelected = current == Tab.ME
            BarItem(
                icon = if (feedSelected) Icons.Filled.DynamicFeed else Tab.FEED.icon,
                label = stringResource(Tab.FEED.label),
                modifier = itemSize,
                isSelected = feedSelected,
                onClick = { onSelect(Tab.FEED) },
            )
            BarItem(
                icon = if (chatSelected) Icons.AutoMirrored.Filled.Chat else Tab.CHAT.icon,
                label = stringResource(Tab.CHAT.label),
                modifier = itemSize,
                isSelected = chatSelected,
                onClick = { onSelect(Tab.CHAT) },
            )
            BarItem(
                icon = if (meSelected) Icons.Filled.Person else Tab.ME.icon,
                label = stringResource(Tab.ME.label),
                modifier = itemSize,
                isSelected = meSelected,
                onClick = { onSelect(Tab.ME) },
            )
        }
    }
}

@Composable
private fun BarItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Box(
        modifier.semantics {
            contentDescription = label
            role = Role.Tab
            selected = isSelected
        }.pressClick(pressedScale = 0.96f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isSelected) palette.ink else palette.ink2,
            modifier = Modifier.size(IconSize.nav),
        )
        if (isSelected) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp)
                    .size(width = 14.dp, height = 2.dp).background(palette.accent, CircleShape),
            )
        }
    }
}
