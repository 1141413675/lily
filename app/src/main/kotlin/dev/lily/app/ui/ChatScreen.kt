package dev.lily.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.appearance.AppearanceSettings
import dev.lily.app.appearance.ChatBackdrop
import dev.lily.app.image.ImageRequestFeedback
import dev.lily.app.media.StickerStore
import dev.lily.app.session.AgentSession
import dev.lily.app.session.ChatItem
import dev.lily.app.voice.VoiceSend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

@Composable
internal fun ChatScreen(
    graph: AppGraph,
    conversation: AppGraph.Conversation,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenCompanionship: () -> Unit,
    onCall: () -> Unit,
    onOpenChatInfo: () -> Unit,
    onViewImage: (ViewingImage?) -> Unit,
) {
    val session = conversation.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val imageFeedback by conversation.chatImages.feedback.collectAsStateWithLifecycle()
    val voice = conversation.voice
    val voiceSend by voice.send.collectAsStateWithLifecycle()
    val stickers by graph.stickers.stickers.collectAsStateWithLifecycle()
    val mic = rememberMicPermission()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 仍保留通知权限的自动申请；聊天页不再用横幅提示任何配置或引导。
    rememberNotificationPermission()
    var draft by rememberSaveable { mutableStateOf("") }
    var tray by rememberSaveable { mutableStateOf(ComposerTray.NONE) }
    var library by rememberSaveable { mutableStateOf(false) }
    // 聊天背景移到设置 → 外观，聊天页只保留对话。
    var replyTo by remember { mutableStateOf<ChatItem.Quote?>(null) }
    // 放一次全屏效果；放完由效果层自己清空。
    var effect by remember { mutableStateOf<ScreenEffect?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    // 等待 10 秒后，显示角色仍在写和停止按钮。
    var waitedLong by remember { mutableStateOf(false) }
    // 按住说话时浮层要用的状态（录着没、取消区、计时）。
    var hold by remember { mutableStateOf(HoldToTalkState()) }
    val rows = remember(items) { chatTimeline(items) }
    // 首次进入时的最后一条：之后新到的消息才逐条入场，历史消息交给页面转场。
    val enterFromId = remember { items.maxOfOrNull { it.id } ?: 0L }
    val listState = rememberLazyListState()
    val showJumpToBottom by remember { derivedStateOf { listState.canScrollForward } }
    DisposableEffect(voice) {
        onDispose {
            voice.cancelRecording()
            voice.player.stop()
        }
    }
    LaunchedEffect(busy) {
        waitedLong = false
        if (busy) {
            kotlinx.coroutines.delay(10_000)
            waitedLong = true
        }
    }
    LaunchedEffect(notConnected) {
        if (notConnected) {
            kotlinx.coroutines.delay(8_000)
            notConnected = false
        }
    }
    LaunchedEffect(rows.size, busy) {
        if (rows.isEmpty()) return@LaunchedEffect
        // 只有停在底部附近才自动跟随；翻旧消息时不把人拽回去。
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= rows.lastIndex) {
            // 有日期分隔时整体下移一位；打字气泡再多一位。
            listState.animateScrollToItem(rows.size + if (busy) 1 else 0)
        }
    }
    LaunchedEffect(approval?.id) {
        if (approval != null) { focus.clearFocus(); keyboard?.hide() }
    }
    // 键盘弹出时收起面板，面板与键盘不同时占位。
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen) tray = ComposerTray.NONE }

    fun copyToCache(uri: Uri): File? = try {
        val temp = File(context.cacheDir, "import-${System.currentTimeMillis()}-${(0..999).random()}")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
        temp.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        null
    }

    fun nameOf(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return (fromProvider ?: "表情").substringBeforeLast('.')
    }

    fun sendPhoto(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val temp = copyToCache(uri) ?: return@launch
            val saved = conversation.photos.save(temp)
            temp.delete()
            if (saved != null) withContext(Dispatchers.Main) {
                session.postImage(saved.absolutePath)
                tray = ComposerTray.NONE
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach(::sendPhoto)
    }
    val stickerImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val temp = copyToCache(uri) ?: return@forEach
                val animated = context.contentResolver.getType(uri) == "image/gif"
                graph.stickers.add(temp, nameOf(uri), animated)
                temp.delete()
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val shot = cameraFile
        cameraFile = null
        if (shot == null) return@rememberLauncherForActivityResult
        if (!ok) { shot.delete(); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.IO) {
            val saved = conversation.photos.save(shot)
            shot.delete()
            if (saved != null) withContext(Dispatchers.Main) {
                session.postImage(saved.absolutePath)
                tray = ComposerTray.NONE
            }
        }
    }

    fun startCamera() {
        val shot = File(context.cacheDir, "camera-${System.currentTimeMillis()}.jpg")
        cameraFile = shot
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shot) }
            .onSuccess { camera.launch(it) }
            .onFailure { Toast.makeText(context, "相机不可用", Toast.LENGTH_SHORT).show() }
    }

    if (library) {
        StickerLibraryScreen(
            store = graph.stickers,
            stickers = stickers,
            onBack = { library = false },
            onImport = { stickerImporter.launch("image/*") },
        )
        return
    }

    // 聊天是独立全屏页：系统返回回到上一次的内容页。
    BackHandler(onBack = onBack)

    // 昵称为空时统一回退到当前应用名，标题、气泡与来电都用同一个名字。
    val herName = settings.persona.nameOr(stringResource(R.string.app_name))
    Box(Modifier.fillMaxSize()) {
        ScreenEffectLayer(effect) { effect = null }
        Column(Modifier.fillMaxSize()) {
            // 顶栏只说她在不在；「正在回你」交给三点气泡，不做状态读数。
            val presence = if (!settings.configured) "还没连上" else "在"
            ChatHeader(name = herName, presence = presence, onCall = onCall,
                onIncoming = conversation::ringIncoming,
                onBack = onBack,
                onOpenAgentSettings = onOpenAgentSettings,
                onOpenCompanionship = onOpenCompanionship,
                onOpenInfo = onOpenChatInfo,
                avatar = { PortraitAvatar(graph, SizeTokens.avatarHeader, store = conversation.portrait) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatBackgroundLayer(settings.appearance)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 浮动「回到底部」出现时给列表垫高，避免盖住最后一条消息。
                    contentPadding = PaddingValues(
                        start = Space.page,
                        end = Space.page,
                        top = 12.dp,
                        bottom = if (showJumpToBottom) 64.dp else Space.md,
                    ),
                ) {
                    if (rows.isNotEmpty()) item(key = "day") { DayDivider() }
                    val lastAssistant = rows.indexOfLast { it is TimelineRow.Message && it.item is ChatItem.Assistant }
                    val lastUser = rows.indexOfLast { it is TimelineRow.Message && it.item is ChatItem.User }
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        val prevItem = (rows.getOrNull(index - 1) as? TimelineRow.Message)?.item
                        val nextItem = (rows.getOrNull(index + 1) as? TimelineRow.Message)?.item
                        val item = (row as? TimelineRow.Message)?.item
                        val samePrev = item != null && prevItem != null && senderKey(prevItem) == senderKey(item)
                        val sameNext = item != null && nextItem != null && senderKey(nextItem) == senderKey(item)
                        Box(
                            Modifier.fillMaxWidth()
                                // 新进来的那条淡入、整列平滑让位，不做弹跳。
                                .animateItem(
                                    fadeInSpec = tween(200),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )
                                // 同一人连着说 4dp，换人 12dp。
                                .padding(top = if (samePrev) Space.xs else Space.lg),
                        ) {
                            when (row) {
                                is TimelineRow.Message ->                             ChatMessage(
                                    item = row.item,
                                    player = voice.player,
                                    onOpenImage = { path ->
                                        onViewImage(when (val item = row.item) {
                                            is ChatItem.UserImage -> ViewingImage(item.id, path, mine = true)
                                            is ChatItem.AssistantImage -> ViewingImage(item.id, path, mine = false)
                                            // 分享卡删消息时不连带删掉动态里的原图。
                                            is ChatItem.UserFeedShare -> ViewingImage(item.id, path, mine = false)
                                            else -> null
                                        })
                                    },
                                    // 私聊里她的话没有头像，跟语音气泡一样左对齐；群里靠名字分辨谁在说。
                                    shareAvatar = (row.item as? ChatItem.UserFeedShare)?.let { share ->
                                        { PortraitAvatar(graph, 24.dp, store = if (share.authorIsMe) graph.myAvatar else graph.portrait) }
                                    },
                                    seen = index == lastUser && index < lastAssistant,
                                    peerName = herName,
                                    onReply = { replyTo = it },
                                    onReact = { session.react(row.item.id, it) },
                                    onRecall = (row.item as? ChatItem.User)?.let { user ->
                                        { session.recall(user.id) }
                                    },
                                    groupEnd = !sameNext,
                                    animateEnter = row.item.id > enterFromId,
                                )
                                is TimelineRow.Process -> ProcessCard(row.entries)
                            }
                        }
                    }
                    // 微信式的「正在输入」：她开口之前一直显示，像真人一样不会秒回。
                    // 她一开口就让位——语音回复时那条气泡自己会显示「正在准备声音」的三个点，
                    // 两套点同时出现会让人以为有两件事在发生。
                    if (busy && awaitingReply(rows)) item(key = "typing") {
                        val lastItem = (rows.lastOrNull() as? TimelineRow.Message)?.item
                        Box(
                            Modifier.fillMaxWidth().padding(
                                top = if (lastItem != null && senderKey(lastItem) == 1) Space.xs else Space.lg,
                            ),
                        ) {
                            TypingBubble(
                                // 等超过十秒给一个「停」：久等不候时能反悔。
                                onStop = if (waitedLong) session::stop else null,
                            )
                        }
                    }
                }
                if (showJumpToBottom) {
                    Surface(
                        onClick = { scope.launch { listState.animateScrollToItem(rows.size) } },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = Space.page, bottom = Space.md)
                            .size(SizeTokens.touchTarget),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "回到底部",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(IconSize.lg),
                            )
                        }
                    }
                }
                // 微信式的按住说话浮层：盖住聊天区，波纹和计时都在浮层里。
                if (hold.recording) {
                    HoldToTalkOverlay(
                        cancelZone = hold.cancelZone,
                        seconds = hold.seconds,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            if (approval != null) {
                ApprovalCard(approval!!, session)
            } else {
                if (notConnected) {
                    // 只有真正要发却发不出去时才说；不在聊天里常驻一条配置横幅。
                    Row(
                        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "还没连上",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onOpenSettings, modifier = Modifier.tapTarget()) { Text("去设置") }
                    }
                }
                imageFeedback?.let { feedback ->
                    ChatImageFeedbackRow(feedback, conversation.chatImages::retry, conversation.chatImages::dismissFeedback)
                }
                ChatInput(
                    draft, { draft = it }, session, busy, question != null,
                    voice = voiceSend,
                    tray = tray,
                    replyTo = replyTo,
                    peerName = herName,
                    configured = settings.configured,
                    onNotConnected = { notConnected = true },
                    onClearReply = { replyTo = null },
                    onSent = { graph.chatSounds.sent() },
                    onEffect = { effect = it },
                    onToggleStickers = {
                        focus.clearFocus(); keyboard?.hide()
                        tray = if (tray == ComposerTray.STICKERS) ComposerTray.NONE else ComposerTray.STICKERS
                    },
                    onToggleImages = {
                        focus.clearFocus(); keyboard?.hide()
                        tray = if (tray == ComposerTray.IMAGES) ComposerTray.NONE else ComposerTray.IMAGES
                    },
                    onGallery = {
                        focus.clearFocus(); keyboard?.hide(); tray = ComposerTray.NONE
                        galleryPicker.launch("image/*")
                    },
                    onCamera = {
                        focus.clearFocus(); keyboard?.hide(); tray = ComposerTray.NONE
                        startCamera()
                    },
                    onStartVoice = voice::startRecording,
                    onStopVoice = voice::stopAndSend,
                    onCancelVoice = voice::cancelRecording,
                    micGranted = mic.granted,
                    onRequestMic = mic.request,
                    onDismissVoice = voice::dismiss,
                    onHold = { hold = it },
                )
                // 面板挂在输入框下方（微信式），输入框留在面板上沿。
                AnimatedVisibility(
                    visible = tray == ComposerTray.STICKERS,
                    enter = expandVertically(enterSizeTween()) + fadeIn(enterTween()),
                    exit = shrinkVertically(exitSizeTween()) + fadeOut(exitTween()),
                ) {
                    StickerTray(
                        stickers = stickers,
                        onSend = { s -> session.postSticker(s.file); graph.stickers.bumpUse(s.id) },
                        onAdd = { stickerImporter.launch("image/*") },
                        onManage = { tray = ComposerTray.NONE; library = true },
                    )
                }
                AnimatedVisibility(
                    visible = tray == ComposerTray.IMAGES,
                    enter = expandVertically(enterSizeTween()) + fadeIn(enterTween()),
                    exit = shrinkVertically(exitSizeTween()) + fadeOut(exitTween()),
                ) {
                    ImageTray(
                        onGallery = { tray = ComposerTray.NONE; galleryPicker.launch("image/*") },
                        onCamera = { tray = ComposerTray.NONE; startCamera() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatImageFeedbackRow(
    feedback: ImageRequestFeedback,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 还在生成是过程，用转圈说；文字只留「在弄什么」这个主语。
        if (feedback is ImageRequestFeedback.Generating) {
            SpinningArc(
                color = palette.ink3,
                size = 14.dp,
                strokeWidth = 2.dp,
                description = "正在生成图片",
            )
            Spacer(Modifier.size(Space.md))
            Text(
                "图片",
                style = MaterialTheme.typography.labelMedium,
                color = palette.ink2,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val message = when (feedback) {
                ImageRequestFeedback.Generating -> ""
                ImageRequestFeedback.LimitReached -> "今天的图片生成额度已用完"
                ImageRequestFeedback.Disabled -> "角色发图已关闭，可在「我的」中开启"
                is ImageRequestFeedback.Failed -> "图片没能生成，请重试"
            }
            Text(
                message,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = palette.ink2,
            )
        }
        if (feedback is ImageRequestFeedback.Failed) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
        if (feedback != ImageRequestFeedback.Generating) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭图片提示")
            }
        }
    }
}

/** 看图浮层要记的消息 id 与归属：用户照片删除时连文件一起删，她分享的图只删这条消息。 */
internal data class ViewingImage(val id: Long, val file: String, val mine: Boolean)

/** 气泡分组只看谁在说：文字、语音、图片、表情都算同一方的连续发言。 */
internal fun senderKey(item: ChatItem): Int = when (item) {
    is ChatItem.User, is ChatItem.UserImage, is ChatItem.UserSticker, is ChatItem.UserVoice, is ChatItem.UserFeedShare -> 0
    is ChatItem.Assistant, is ChatItem.AssistantImage, is ChatItem.AssistantVoice -> 1
    else -> 2
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatInput(
    text: String,
    onChange: (String) -> Unit,
    session: AgentSession,
    busy: Boolean,
    answering: Boolean,
    voice: VoiceSend,
    tray: ComposerTray,
    replyTo: ChatItem.Quote?,
    peerName: String,
    configured: Boolean,
    onNotConnected: () -> Unit,
    onClearReply: () -> Unit,
    onSent: (String) -> Unit,
    onEffect: (ScreenEffect) -> Unit,
    onToggleStickers: () -> Unit,
    onToggleImages: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onDismissVoice: () -> Unit,
    onHold: (HoldToTalkState) -> Unit,
) {
    val palette = LocalLilyPalette.current
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = text.isNotBlank() && (!busy || answering)
    val voiceEnabled = !busy || answering
    // 两种输入模式：文字（表情 / 输入框 / 拍摄）与语音（整条按住说话）。
    // 右外按钮只在没有文字时承担切换：文字态显示麦克风，语音态显示键盘；有文字时永远是发送。
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    val startVoice by rememberUpdatedState(onStartVoice)
    val stopVoice by rememberUpdatedState(onStopVoice)
    val cancelVoice by rememberUpdatedState(onCancelVoice)
    // 按住说话的短提示（太短/已取消/没权限），1.6 秒后自己收起。
    var holdHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(holdHint) {
        if (holdHint != null) {
            kotlinx.coroutines.delay(1600)
            holdHint = null
        }
    }
    // 长按发送键：先挑一个效果，再连消息一起放出去。
    var sendPicker by remember { mutableStateOf(false) }
    fun send(effect: ScreenEffect? = null) {
        if (!canSend) return
        // 没配置时不伪造一条消息，只在输入区上方说明并给「去设置」。
        if (!configured) { onNotConnected(); return }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (session.submit(text, quote = replyTo)) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            AgentSession.Submit.NotConfigured -> onNotConnected()
            else -> {
                onSent(text)
                onChange(""); onClearReply(); focus.clearFocus(); keyboard?.hide()
                effect?.let(onEffect)
            }
        }
    }
    // 聊天页没有底栏，输入区贴着屏幕底；补上导航栏 inset，既避让手势条，
    // 也让「按住说话」上滑取消不会触发系统上滑回桌面。键盘弹出时 ime 已含这条 inset，不再叠加。
    // 键盘收起时再留 10dp 呼吸位，输入条和「按住说话」一起抬高，而不是只把背景色顶到手势区。
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 这台设备的导航栏 inset 只有 2px，靠它避不开手势区；改用系统手势区高度兜底，
    // 再加 8dp 间距，保证「按住说话」的上滑取消不会从手势区里起手。键盘弹起时不再叠加。
    val gestureBottom = WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding()
    // 键盘起落时这段间距跟着 180ms 过渡，避免输入条在最后几帧突然平移。
    val bottomPad by animateDpAsState(
        targetValue = if (imeOpen) Space.xs else maxOf(gestureBottom, Space.lg) + Space.sm,
        animationSpec = tween(if (LocalMotionEnabled.current) Motion.base else 0, easing = Motion.easing),
        label = "inputBottom",
    )
    Surface(
        color = palette.surface,
        tonalElevation = 0.dp,
        // 面板开着时，底部安全区由面板自己让，输入框不再重复留白。
        modifier = if (imeOpen || tray != ComposerTray.NONE) Modifier else Modifier.navigationBarsPadding(),
    ) {
        Column {
            // 引用条：告诉用户现在回的是哪一句，发送后自动消失。
            // 用 240ms 高度展开/收起，输入区跟着平滑上移，不再瞬间跳一格。
            var shownQuote by remember { mutableStateOf(replyTo) }
            if (replyTo != null) shownQuote = replyTo
            AnimatedVisibility(
                visible = replyTo != null,
                enter = expandVertically(sizeTween()) + fadeIn(enterTween()),
                exit = shrinkVertically(sizeTween()) + fadeOut(exitTween()),
            ) {
                shownQuote?.let { q ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = Space.md, end = Space.sm, top = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(2.dp).height(32.dp).background(palette.line2))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                "回复 ${if (q.fromMe) "你" else peerName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.ink2,
                            )
                            Text(
                                q.text.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onClearReply, modifier = Modifier.size(SizeTokens.touchTarget)) {
                            Icon(Icons.Outlined.Close, contentDescription = "取消回复", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            VoiceStatus(voice, onDismissVoice)
            holdHint?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // WhatsApp 骨架：框内左表情、右拍摄；框外一个按钮在键盘与麦克风之间切换，有文字时变发送。
            Row(
                Modifier.fillMaxWidth().padding(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.xs,
                    // 键盘弹起时只吃 ime inset，不再额外叠加这段间距。
                    bottom = bottomPad,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (voiceMode && text.isBlank()) {
                    HoldToTalkBar(
                        recording = voice == VoiceSend.Recording,
                        enabled = voiceEnabled,
                        modifier = Modifier.weight(1f),
                        onHold = onHold,
                        onPressStart = {
                            if (!micGranted) {
                                onRequestMic()
                                holdHint = "需要麦克风权限才能录音"
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                startVoice()
                            }
                        },
                        onRelease = { cancelled, elapsedMs ->
                            if (voice == VoiceSend.Recording) {
                                when {
                                    cancelled -> { cancelVoice(); holdHint = "已取消" }
                                    elapsedMs < 1_000 -> { cancelVoice(); holdHint = "太短了" }
                                    else -> stopVoice()
                                }
                            }
                        },
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = palette.surface2,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onToggleStickers, modifier = Modifier.size(SizeTokens.touchTarget)) {
                                Icon(Icons.Outlined.Mood, contentDescription = "表情包", tint = palette.ink2, modifier = Modifier.size(22.dp))
                            }
                            TextField(
                                value = text, onValueChange = onChange,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                placeholder = {
                                    Text("说点什么", style = MaterialTheme.typography.bodyMedium, color = palette.ink3)
                                },
                                minLines = 1, maxLines = 4,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    cursorColor = palette.accent,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                            )
                            IconButton(onClick = onToggleImages, modifier = Modifier.size(SizeTokens.touchTarget)) {
                                Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍摄与相册", tint = palette.ink2, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.sm))
                if (sendPicker) {
                    SendEffectDialog(
                        onPick = { picked ->
                            sendPicker = false
                            send(picked)
                        },
                        onDismiss = { sendPicker = false },
                    )
                }
                // 独立圆钮与输入框同高（48dp），图标统一 22dp 线框。
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    when {
                        busy && !answering -> Box(
                            Modifier.size(48.dp).clip(CircleShape).background(palette.surface2)
                                .clickable(onClick = session::stop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.chat_stop), tint = palette.ink2, modifier = Modifier.size(22.dp))
                        }
                        canSend -> Box(
                            Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                                onClick = { send() },
                                onLongClick = { sendPicker = true },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(palette.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Send,
                                    contentDescription = stringResource(R.string.chat_send),
                                    tint = palette.accentInk,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        else -> Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(enabled = voiceEnabled) {
                                    voiceMode = !voiceMode
                                    focus.clearFocus()
                                    keyboard?.hide()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(palette.surface2)
                                    .alpha(if (voiceEnabled) 1f else 0.38f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (voiceMode) Icons.Outlined.Keyboard else Icons.Outlined.Mic,
                                    contentDescription = stringResource(
                                        if (voiceMode) R.string.chat_input_keyboard else R.string.chat_voice_record,
                                    ),
                                    tint = palette.ink2,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按住说话时浮层要用的状态：录着没、有没有滑进取消区、录了多久。 */
internal data class HoldToTalkState(
    val recording: Boolean = false,
    val cancelZone: Boolean = false,
    val seconds: Int = 0,
)

/**
 * 微信式的按住说话浮层：盖住聊天区，顶端一个上滑箭头，中间是跳动的波纹和计时。
 *
 * 计时和「上滑取消」都是过程，交给图形和节奏；
 * 只有「松开取消」需要你改一个动作，才留一句话。
 */
@Composable
private fun HoldToTalkOverlay(
    cancelZone: Boolean,
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "hold-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.loop, easing = LinearEasing)),
        label = "hold-wave-phase",
    )
    val danger = LocalLilyPalette.current.danger
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f)),
    ) {
        // 顶部：上滑箭头。滑进取消区时箭头和提示一起变红。
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (cancelZone) danger else Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                if (cancelZone) "松开取消" else "手指上滑，取消发送",
                style = MaterialTheme.typography.bodyMedium,
                color = if (cancelZone) danger else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = Space.sm),
            )
        }
        // 中间：波纹自己动，屏幕不用写字也知道在录。
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(20.dp, 36.dp, 54.dp, 36.dp, 20.dp).forEachIndexed { i, base ->
                    val u = ((phase * 5f - i) % 5f + 5f) % 5f / 5f
                    val boost = if (motion) (1f - 2f * u).coerceAtLeast(0f) else 0.5f
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(base * (0.42f + 0.58f * boost))
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.5f + 0.5f * boost)),
                    )
                }
            }
            Text(
                "%d:%02d".format(seconds / 60, seconds % 60),
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                modifier = Modifier.padding(top = Space.lg),
            )
        }
    }
}

/**
 * 语音模式的整条「按住说话」：按住即录、松开发；上滑超过 56dp 进入取消区。
 *
 * 录音中的时长和「松开发送」交给上面的浮层说，这条只负责显示操作方式
 * 和取消态——那是需要你改动作的事实。
 */
@Composable
private fun HoldToTalkBar(
    recording: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onHold: (HoldToTalkState) -> Unit = {},
    onPressStart: () -> Unit,
    onRelease: (cancelled: Boolean, elapsedMs: Long) -> Unit,
) {
    val palette = LocalLilyPalette.current
    var cancelZone by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    // 录制时长：从开始录音算起，每 200ms 刷新一次。
    LaunchedEffect(recording) {
        if (!recording) {
            elapsed = 0
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            kotlinx.coroutines.delay(200)
        }
    }
    // 把状态报给聊天页，浮层要用。
    val report by rememberUpdatedState(onHold)
    LaunchedEffect(recording, cancelZone, elapsed) {
        report(HoldToTalkState(recording, cancelZone, elapsed))
    }
    val pressStart by rememberUpdatedState(onPressStart)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                when {
                    cancelZone -> palette.errorContainer
                    recording -> palette.accentSoft
                    else -> palette.surface2
                },
            )
            .alpha(if (enabled) 1f else 0.38f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    cancelZone = false
                    pressStart()
                    var change = down
                    while (change.pressed) {
                        val event = awaitPointerEvent()
                        change = event.changes.firstOrNull { it.id == down.id } ?: break
                        cancelZone = down.position.y - change.position.y > 56.dp.toPx()
                    }
                    release(cancelZone, System.currentTimeMillis() - startedAt)
                    cancelZone = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (cancelZone) "松开取消" else "按住说话",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                cancelZone -> palette.onErrorContainer
                recording -> palette.ink
                else -> palette.ink2
            },
        )
    }
}

/** 录音/识别状态条：只在录音、识别或出错时出现。 */
@Composable
private fun VoiceStatus(state: VoiceSend, onDismiss: () -> Unit) {
    when (state) {
        VoiceSend.Idle -> Unit
        // 录音中的状态由「按住说话」长条自己显示（时长 + 松开取消），这里不再重复一行。
        VoiceSend.Recording -> Unit
        VoiceSend.Transcribing -> Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.chat_voice_transcribing), style = CaptionTextStyle)
        }
        is VoiceSend.Failed -> {
            // 只留一行、几秒后自动收起；配置好之后不再出现，也不再长期占输入区。
            LaunchedEffect(state) {
                kotlinx.coroutines.delay(6000)
                onDismiss()
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.tapTarget()) { Text(stringResource(R.string.chat_voice_dismiss)) }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Space.xl, end = Space.sm, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+: asked once automatically, then via the banner. */
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

/**
 * 聊天背景只铺在内容层：顶栏与输入区保持实色，文字与语音气泡本身不透明，
 * 所以任何背景图下正文都按 ≥4.5:1 处理。未上传或读取失败时使用主题默认底色；
 * 模糊在 Android 12 以下自动降级为不模糊。
 */
@Composable
internal fun ChatBackgroundLayer(a: AppearanceSettings) {
    val bitmap = remember(a.imagePath) {
        if (a.imagePath.isNotBlank()) {
            runCatching { android.graphics.BitmapFactory.decodeFile(a.imagePath) }.getOrNull()
        } else {
            null
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (a.blur > 0) Modifier.blur(a.blur.dp) else Modifier),
            )
            if (a.dim > 0) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (a.dim / 100f) * 0.7f)))
            }
        } else when (a.backdrop) {
            ChatBackdrop.XIAOMAN -> LilyBackdrop()
            ChatBackdrop.GLOW -> GlowBackdrop()
        }
        // 顶部一层极淡的主色雾，头像与名字更“浮”起来，不压正文。
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                    420f to Color.Transparent,
                ),
            ),
        )
    }
}

/** 内置背景一：淡紫柔光，顶部一团柔光向紫雾白过渡，不做纹理。 */
@Composable
private fun GlowBackdrop() {
    val palette = LocalLilyPalette.current
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to palette.accentSoft,
                0.42f to palette.bg,
                1f to palette.accentSoft.copy(alpha = 0.55f),
            ),
        )
        // 右上柔光：径向渐变提亮一档，靠明度而不是装饰制造氛围。
        val center = Offset(size.width * 0.78f, size.height * 0.13f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accent.copy(alpha = 0.20f), Color.Transparent),
                center = center,
                radius = size.width,
            ),
            radius = size.width,
            center = center,
        )
        // 左侧再补一团更淡的，避免只有右上角发亮。
        val left = Offset(size.width * 0.12f, size.height * 0.40f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accent.copy(alpha = 0.08f), Color.Transparent),
                center = left,
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = left,
        )
    }
}

/** 内置背景二：极低透明度隐百合暗纹，按网格铺开，近看有花，远看只像一层雾。 */
@Composable
private fun LilyBackdrop() {
    val palette = LocalLilyPalette.current
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to palette.bg,
                0.55f to palette.accentSoft.copy(alpha = 0.50f),
                1f to palette.bg,
            ),
        )
        // 花纹只做氛围：间距大、线细，透明度仍压在 5% 上下，不挡正文。
        val unit = 124.dp.toPx()
        val pen = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        // 暗色底吸光，同样透明度会更难看见，单独提一档。
        val ink = palette.accent.copy(alpha = if (palette.bg.luminance() >= 0.4f) 0.045f else 0.075f)
        var row = 0
        var y = -unit
        while (y < size.height + unit) {
            var x = if (row % 2 == 0) -unit * 0.5f else 0f
            while (x < size.width + unit) {
                drawLily(Offset(x, y), unit, ink, pen)
                x += unit
            }
            y += unit * 1.12f
            row++
        }
    }
}

/** 暗纹里的一朵：中央花瓣 + 两片侧瓣 + 一小段花茎，全部是曲线路径。 */
private fun DrawScope.drawLily(center: Offset, unit: Float, color: Color, pen: Stroke) {
    val cx = center.x
    val cy = center.y
    val flower = Path().apply {
        // 中央花瓣：窄长、收尖
        moveTo(cx, cy + unit * 0.28f)
        cubicTo(cx - unit * 0.10f, cy + unit * 0.04f, cx - unit * 0.05f, cy - unit * 0.28f, cx, cy - unit * 0.44f)
        cubicTo(cx + unit * 0.05f, cy - unit * 0.28f, cx + unit * 0.10f, cy + unit * 0.04f, cx, cy + unit * 0.28f)
        // 左瓣：向外张、尖头
        moveTo(cx, cy + unit * 0.24f)
        cubicTo(cx - unit * 0.22f, cy + unit * 0.12f, cx - unit * 0.38f, cy - unit * 0.10f, cx - unit * 0.36f, cy - unit * 0.30f)
        cubicTo(cx - unit * 0.20f, cy - unit * 0.24f, cx - unit * 0.06f, cy - unit * 0.02f, cx, cy + unit * 0.24f)
        // 右瓣
        moveTo(cx, cy + unit * 0.24f)
        cubicTo(cx + unit * 0.22f, cy + unit * 0.12f, cx + unit * 0.38f, cy - unit * 0.10f, cx + unit * 0.36f, cy - unit * 0.30f)
        cubicTo(cx + unit * 0.20f, cy - unit * 0.24f, cx + unit * 0.06f, cy - unit * 0.02f, cx, cy + unit * 0.24f)
    }
    drawPath(flower, color, style = pen)
    drawLine(
        color = color,
        start = Offset(cx, cy + unit * 0.30f),
        end = Offset(cx + unit * 0.05f, cy + unit * 0.52f),
        strokeWidth = pen.width,
        cap = StrokeCap.Round,
    )
}

/** 长按发送时的小选择器：四个效果，一次只放一个，不放就点取消。 */
@Composable
private fun SendEffectDialog(onPick: (ScreenEffect) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalLilyPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("带个效果发出去") },
        text = {
            Column {
                ScreenEffect.values().forEach { effect ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(effect) }.padding(vertical = Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            effect.label,
                            color = palette.ink,
                            fontSize = TypeScale.body.size,
                            lineHeight = TypeScale.body.line,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            effect.hint,
                            color = palette.ink3,
                            fontSize = TypeScale.bodySmall.size,
                            lineHeight = TypeScale.bodySmall.line,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
