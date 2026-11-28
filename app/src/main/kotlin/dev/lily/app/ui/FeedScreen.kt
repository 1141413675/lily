package dev.lily.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.feed.FeedStore
import dev.lily.app.life.FeedTexts
import dev.lily.app.session.AgentSession
import dev.lily.app.session.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 动态页：两个人各自发，按时间倒序。
 * 用户动态发布后自动抽记忆、她自动点赞评论；卡片上显示「已记入记忆 · N 条」，可跳去记忆。
 */
class FeedViewModel(private val graph: AppGraph, private val context: Context) : ViewModel() {

    enum class PublishResult { SAVED, ATTACHMENT_FAILED, STORE_FAILED }

    fun ensureHerPost() = graph.feed.refresh()

    fun extractPending() = graph.feedMemory.extractPending()

    fun retryLoad(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            onComplete(withContext(Dispatchers.IO) { graph.feedStore.retryLoad() })
        }
    }

    fun toggleLike(id: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            onComplete(withContext(Dispatchers.IO) { graph.feedStore.toggleLike(id) })
        }
    }

    fun delete(id: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            onComplete(withContext(Dispatchers.IO) { graph.feedStore.delete(id) })
        }
    }

    /** 图片先复制进 `feed/images`，再落动态；随后异步抽取记忆、让她来点赞回应。 */
    fun publish(text: String, uris: List<Uri>, onComplete: (PublishResult) -> Unit) {
        viewModelScope.launch {
            onComplete(withContext(Dispatchers.IO) { savePost(text, uris) })
        }
    }

    private fun savePost(text: String, uris: List<Uri>): PublishResult {
        val images = mutableListOf<String>()
        var committed = false
        try {
            for (uri in uris.take(FeedStore.MAX_IMAGES)) {
                val image = copyAttachment(uri) ?: return PublishResult.ATTACHMENT_FAILED
                images.add(image.absolutePath)
            }
            val post = graph.feedStore.addMine(text, images, System.currentTimeMillis())
                ?: return PublishResult.STORE_FAILED
            committed = true
            graph.feedReactions.react(post.id)
            graph.feedMemory.extract(post.id)
            return PublishResult.SAVED
        } finally {
            if (!committed) images.forEach { path -> runCatching { graph.feedPhotos.delete(path) } }
        }
    }

    /** 评论落库；[parentId] 是回复的父评论，回复她的动态时她在聊天里接一句。 */
    fun comment(post: FeedStore.Post, text: String, parentId: String?, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                graph.feedStore.addComment(post.id, "你", text, System.currentTimeMillis(), parentId) != null
            }
            onComplete(saved)
            if (saved && post.author == FeedStore.AUTHOR_HER) {
                graph.session.postAssistant(FeedTexts.commentReply(text, graph.feed.partAt(System.currentTimeMillis())))
            }
        }
    }

    /** 确认收件人后，把动态以帖子卡片发进聊天；模型只收到对应的文字上下文。 */
    fun shareToHer(post: FeedStore.Post, authorName: String): AgentSession.Submit {
        val mine = post.author == FeedStore.AUTHOR_ME
        val body = post.text.trim().ifBlank {
            if (post.images.isNotEmpty()) "一组照片" else "一条动态"
        }
        val imageContext = if (post.images.isEmpty()) "" else "（动态里有 ${post.images.size} 张图片）"
        return graph.session.submit(
            "我分享了${authorName}的一条动态给你：$body$imageContext",
            display = { id ->
                ChatItem.UserFeedShare(
                    id = id,
                    postId = post.id,
                    authorName = authorName,
                    authorIsMe = mine,
                    text = post.text.trim(),
                    images = post.images,
                    at = post.at,
                )
            },
        )
    }

    /** 「收藏」：只落本机收藏文件，不通知她、不进记忆。 */
    fun toggleSaved(post: FeedStore.Post, herName: String) = graph.savedPosts.toggle(post, herName)

    private fun copyAttachment(uri: Uri): File? {
        val dir = graph.feedStore.imagesDir()
        val file = runCatching {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@runCatching null
            File.createTempFile("img-", ".jpg", dir)
        }.getOrNull() ?: return null
        val copied = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { source ->
                FileOutputStream(file).use { output ->
                    val bytes = source.copyTo(output)
                    output.fd.sync()
                    bytes > 0
                }
            }
        }.getOrDefault(false)
        if (copied) return file
        runCatching { graph.feedPhotos.delete(file.absolutePath) }
        return null
    }
}

@Composable
fun FeedScreen(
    graph: AppGraph,
    focusPostId: String? = null,
    onFocusConsumed: () -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
    onOpenMemory: (String) -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val vm = viewModel { FeedViewModel(graph, context.applicationContext) }
    val posts by graph.feedStore.posts.collectAsStateWithLifecycle()
    val loadFailed by graph.feedStore.loadFailed.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val profile by graph.myProfile.state.collectAsStateWithLifecycle()
    val writing by graph.feed.writing.collectAsStateWithLifecycle()
    val saved by graph.savedPosts.items.collectAsStateWithLifecycle()
    val palette = LocalLilyPalette.current
    val herName = settings.persona.nameOr(stringResource(R.string.app_name))
    val myName = profile.nickname.trim().ifBlank { "你" }
    var composing by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FeedStore.Post?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<FeedStore.Post?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var retryingLoad by remember { mutableStateOf(false) }
    // 评论输入由页面统一管理，点输入区以外（包括另一条动态）就取消这次未发送的评论。
    var commentingPostId by remember { mutableStateOf<String?>(null) }
    var commentComposerBounds by remember { mutableStateOf<Rect?>(null) }
    var feedOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()

    LaunchedEffect(loadFailed) {
        if (!loadFailed) {
            vm.ensureHerPost()
            vm.extractPending()
        }
    }
    // 编辑态与收藏页都铺满内容区并把底栏收起；离开页面时恢复。
    LaunchedEffect(composing, savedOpen) { onEditingChange(composing || savedOpen) }
    DisposableEffect(Unit) { onDispose { onEditingChange(false) } }
    LaunchedEffect(focusPostId, posts.size) {
        if (focusPostId == null) return@LaunchedEffect
        val index = posts.indexOfFirst { it.id == focusPostId }
        if (index >= 0) listState.animateScrollToItem(index)
        onFocusConsumed()
    }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { feedOriginInRoot = it.boundsInRoot().topLeft }
            .pointerInput(commentingPostId, commentComposerBounds, feedOriginInRoot) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val active = commentingPostId
                    if (active != null) {
                        val point = feedOriginInRoot + down.position
                        if (commentComposerBounds?.contains(point) != true) {
                            commentingPostId = null
                            commentComposerBounds = null
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 页名在左，发布与收藏入口在右上角（朋友圈相机位），列表为空时也在。
            Row(
                Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.sm, top = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("动态", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { composing = true },
                    modifier = Modifier.size(SizeTokens.touchTarget),
                    enabled = !loadFailed,
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = "发布动态",
                        tint = palette.ink2,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
                Row(
                    Modifier.heightIn(min = SizeTokens.touchTarget)
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { savedOpen = true }
                        .padding(horizontal = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (saved.isEmpty()) Icons.Outlined.BookmarkBorder else Icons.Filled.Bookmark,
                        contentDescription = "收藏",
                        tint = if (saved.isEmpty()) palette.ink2 else palette.accent,
                        modifier = Modifier.size(IconSize.lg),
                    )
                    if (saved.isNotEmpty()) {
                        Text(
                            " ${saved.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.ink2,
                        )
                    }
                }
            }
            feedError?.let { FeedError(it, Modifier.padding(horizontal = Space.xl, vertical = Space.sm)) }
            if (loadFailed) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
                    EmptyBox {
                        FeedError("暂时读不到动态记录。")
                        TextButton(
                            onClick = {
                                retryingLoad = true
                                vm.retryLoad { retryingLoad = false }
                            },
                            enabled = !retryingLoad,
                        ) {
                            // 重试中：转圈说，别让按钮文字一会儿一变。
                            if (retryingLoad) {
                                SpinningArc(
                                    color = palette.ink3,
                                    size = 14.dp,
                                    strokeWidth = 2.dp,
                                    description = "正在读取动态",
                                )
                            } else {
                                Text("重试")
                            }
                        }
                    }
                }
            } else if (posts.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
                    EmptyBox {
                        if (writing) {
                            // 她正在写：三个点就够了，不必写成一句话。
                            PulsingDots(
                                color = palette.ink2,
                                dotSize = 7.dp,
                                description = "${settings.persona.pronoun}正在写今天的事",
                            )
                        } else {
                            Text(
                                "${settings.persona.pronoun}还没发过。等${settings.persona.pronoun}想说的时候，会在这里。",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "发第一条，或者等${settings.persona.pronoun}先开口。",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.ink3,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = Space.sm, bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    itemsIndexed(posts, key = { _, post -> post.id }) { index, post ->
                        FeedAppear {
                            FeedPostCard(
                                graph = graph,
                                post = post,
                                herName = herName,
                                myName = myName,
                                saved = saved.any { it.id == post.id },
                                onLike = { id ->
                                    vm.toggleLike(id) { ok ->
                                        feedError = if (ok) null else "没能保存点赞，请重试。"
                                    }
                                },
                                commentsOpen = commentingPostId == post.id,
                                onCommentsOpenChange = { open ->
                                    commentingPostId = if (open) post.id else null
                                    if (!open) {
                                        commentComposerBounds = null
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                    }
                                },
                                onComposerBounds = { commentComposerBounds = it },
                                onComment = { text, parentId, complete -> vm.comment(post, text, parentId, complete) },
                                onShare = { shareError = null; sharing = post },
                                onToggleSave = { vm.toggleSaved(post, herName) },
                                onDelete = { deleteError = false; pendingDelete = post },
                                onOpenMemory = onOpenMemory,
                                onViewImage = { viewing = it },
                            )
                        }
                        // 帖子是长内容流，边界是功能性的：块间距 21 + 一条 1dp 细线，
                        // 只在两条动态之间出现；卡片内部不再画线，也不加卡片外框。
                        if (index < posts.lastIndex) {
                            Column(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.height(Space.md))
                                HorizontalDivider(thickness = 1.dp, color = palette.line)
                                Spacer(Modifier.height(Space.md))
                            }
                        }
                    }
                }
            }
        }
        if (composing) {
            FeedEditor(
                onCancel = { composing = false },
                onPublish = vm::publish,
                onSaved = { composing = false },
            )
        }
        if (savedOpen) {
            SavedPostsScreen(
                items = saved,
                onBack = { savedOpen = false },
                onRemove = graph.savedPosts::remove,
                onOpenImage = { viewing = it },
            )
        }
        sharing?.let { post ->
            val authorName = if (post.author == FeedStore.AUTHOR_ME) myName else herName
            FeedShareSheet(
                graph = graph,
                post = post,
                authorName = authorName,
                herName = herName,
                error = shareError,
                onDismiss = { sharing = null },
                onSend = {
                    when (vm.shareToHer(post, authorName)) {
                        AgentSession.Submit.Busy -> shareError = "聊天正在处理上一条消息，请稍后再试。"
                        AgentSession.Submit.Empty -> shareError = "没有可分享的内容。"
                        else -> {
                            sharing = null
                            onOpenChat()
                        }
                    }
                },
            )
        }
        viewing?.let { path ->
            FeedImageViewer(path) { viewing = null }
        }
        pendingDelete?.let { post ->
            AlertDialog(
                onDismissRequest = { if (!deleting) pendingDelete = null },
                title = { Text("删掉这条？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Text("删掉以后就看不到了。已经记下的事还在。")
                        if (deleteError) FeedError("没能删除这条动态，请重试。")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            deleting = true
                            deleteError = false
                            vm.delete(post.id) { ok ->
                                deleting = false
                                if (ok) pendingDelete = null else deleteError = true
                            }
                        },
                    ) { Text(if (deleting) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun FeedError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 分享前先选收件人；目前只有她，选中后才允许发送。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedShareSheet(
    graph: AppGraph,
    post: FeedStore.Post,
    authorName: String,
    herName: String,
    error: String?,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    var selected by remember(post.id) { mutableStateOf(false) }
    val preview = remember(post.images) { post.images.firstOrNull()?.let(::decodeThumb) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
        contentColor = palette.ink,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = Space.xl, end = Space.xl, top = Space.lg, bottom = Space.md),
        ) {
            Text(
                "分享动态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(Radius.lg),
                border = BorderStroke(1.dp, palette.line),
                modifier = Modifier.fillMaxWidth().padding(top = Space.lg),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PortraitAvatar(
                            graph,
                            26.dp,
                            store = if (post.author == FeedStore.AUTHOR_ME) graph.myAvatar else graph.portrait,
                        )
                        Column(Modifier.padding(start = Space.sm)) {
                            Text(authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("动态", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
                        }
                    }
                    if (preview != null) {
                        Image(
                            preview,
                            "动态图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(156.dp),
                        )
                    }
                    if (post.text.isNotBlank()) {
                        Text(
                            post.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                        )
                    }
                }
            }
            Text(
                "发送给",
                style = MaterialTheme.typography.labelMedium,
                color = palette.ink3,
                modifier = Modifier.padding(top = Space.lg, bottom = Space.xs),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md))
                    .clickable { selected = !selected }
                    .padding(vertical = Space.sm, horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PortraitAvatar(graph, 42.dp)
                Text(
                    herName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = Space.md),
                )
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选择$herName" else "选择$herName",
                    tint = if (selected) palette.accent else palette.ink3,
                    modifier = Modifier.size(IconSize.lg),
                )
            }
            error?.let { FeedError(it, Modifier.padding(top = Space.sm)) }
            Button(
                onClick = onSend,
                enabled = selected,
                modifier = Modifier.fillMaxWidth().padding(top = Space.md).heightIn(min = 48.dp),
            ) {
                Text(if (selected) "发送给 $herName" else "先选择好友")
            }
        }
    }
}

/**
 * 列表项首次出现时轻轻淡入。
 * 只做 180ms 透明度，不再叠位移：页面转场已经有一次上移，两者相加会变成双重动画。
 */
@Composable
private fun FeedAppear(content: @Composable () -> Unit) {
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(enterTween()),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun FeedPostCard(
    graph: AppGraph,
    post: FeedStore.Post,
    herName: String,
    myName: String,
    saved: Boolean,
    onLike: (String) -> Unit,
    commentsOpen: Boolean,
    onCommentsOpenChange: (Boolean) -> Unit,
    onComposerBounds: (Rect) -> Unit,
    onComment: (String, String?, (Boolean) -> Unit) -> Unit,
    onShare: () -> Unit,
    onToggleSave: () -> Unit,
    onDelete: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onViewImage: (String) -> Unit,
) {
    val palette = LocalLilyPalette.current
    val mine = post.author == FeedStore.AUTHOR_ME
    var draft by remember(post.id) { mutableStateOf("") }
    var sendingComment by remember(post.id) { mutableStateOf(false) }
    var commentError by remember(post.id) { mutableStateOf(false) }
    // 点某条评论后进入回复态：输入框显示 @对方，发送时把这条评论的顶层 id 作为父级。
    var replyTo by remember(post.id) { mutableStateOf<FeedStore.Comment?>(null) }
    LaunchedEffect(commentsOpen) {
        if (!commentsOpen) {
            draft = ""
            replyTo = null
            commentError = false
        }
    }
    var menu by remember(post.id) { mutableStateOf(false) }
    // 正文超过三行才出现「更多」；展开后不再折叠。
    var textExpanded by remember(post.id) { mutableStateOf(false) }
    var textOverflows by remember(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // 账号头：头像、名字、时间小字，自己的帖子右侧留更多操作。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 谁发的就用谁的头像和昵称：我用自己的（「我的」页设置），她用她的。
            PortraitAvatar(graph, 32.dp, store = if (mine) graph.myAvatar else graph.portrait)
            Column(Modifier.weight(1f).padding(start = Space.sm)) {
                Text(
                    if (mine) myName else herName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (mine) "仅${herName}可见" else "${herName}的日常",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.ink3,
                )
            }
            if (mine) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(SizeTokens.touchTarget)) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多", modifier = Modifier.size(IconSize.lg), tint = palette.ink3)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("删掉这条", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
        // 正文在图片上方；正文带左右留白，图片整行铺满。
        // 三行折叠只作用于正文，评论区不受影响；超过三行给一个「更多」。
        if (post.text.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = Space.md, bottom = Space.sm),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    // 正文不再重复昵称：昵称只在卡片头部出现一次。
                    post.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink,
                    maxLines = if (textExpanded) Int.MAX_VALUE else 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    onTextLayout = { if (!textExpanded) textOverflows = it.hasVisualOverflow },
                )
                if (textOverflows && !textExpanded) {
                    Text(
                        "更多",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink2,
                        modifier = Modifier.clickable { textExpanded = true }.padding(top = 2.dp),
                    )
                }
            }
        }
        // 图片铺满整行：单图/多图统一 4:3 居中裁切。
        if (post.images.isNotEmpty()) {
            PostImages(post.images, onViewImage)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // 正文已移到图片上方；这里只剩操作行 → 评论区。
            // 操作行：点赞、评论、分享靠左；收藏单独靠右。收藏只在已收藏时显示实心紫。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 自己的动态和她的动态都可以点赞，再点一次取消；她的点赞单独保留提示。
                    FeedIconAction(
                        if (post.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (post.liked) "取消点赞" else "点赞",
                        if (post.liked) palette.danger else palette.ink,
                    ) { onLike(post.id) }
                    if (mine && post.likedByHer) {
                        Text(
                            "${herName}赞过",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.ink3,
                            modifier = Modifier.padding(end = Space.xs),
                        )
                    }
                    FeedIconAction(Icons.Outlined.ChatBubbleOutline, "评论", palette.ink) {
                        onCommentsOpenChange(!commentsOpen)
                    }
                    FeedIconAction(Icons.Outlined.Share, "发给$herName", palette.ink, onShare)
                    if (post.memoryIds.isNotEmpty()) {
                        Row(
                            Modifier.heightIn(min = SizeTokens.touchTarget)
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { onOpenMemory(post.memoryIds.first()) }
                                .padding(horizontal = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.AutoStories,
                                contentDescription = "记在记忆里",
                                modifier = Modifier.size(IconSize.lg),
                                tint = palette.ink2,
                            )
                            Text(" ${post.memoryIds.size}", style = MaterialTheme.typography.labelSmall, color = palette.ink2)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                FeedIconAction(
                    if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (saved) "取消收藏" else "收藏",
                    if (saved) palette.accent else palette.ink,
                    onToggleSave,
                )
            }
            // 评论区：默认只露最近两条顶层评论，点评论图标看全部并输入；回复缩进挂在父评论下。
            val roots = post.comments.filter { root ->
                root.parentId == null || post.comments.none { it.id == root.parentId }
            }
            val shownRoots = if (commentsOpen) roots else roots.takeLast(2)
            // 评论区和正文用一层浅色块分开；输入框也在块里，左侧挂我的头像。
            if (shownRoots.isNotEmpty() || commentsOpen) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(palette.surface2)
                        .padding(horizontal = Space.sm, vertical = Space.xs),
                ) {
                    shownRoots.forEach { root ->
                        FeedCommentRow(
                            comment = root,
                            parent = null,
                            replying = replyTo?.id == root.id,
                            onClick = { replyTo = root; onCommentsOpenChange(true) },
                        )
                        post.comments.filter { it.parentId == root.id }.forEach { reply ->
                            Box(Modifier.padding(start = Space.xl)) {
                                FeedCommentRow(
                                    comment = reply,
                                    parent = root,
                                    replying = replyTo?.id == reply.id,
                                    onClick = { replyTo = reply; onCommentsOpenChange(true) },
                                )
                            }
                        }
                    }
                    if (commentsOpen) {
                        val target = replyTo
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            modifier = Modifier.padding(top = Space.xs)
                                .onGloballyPositioned { onComposerBounds(it.boundsInRoot()) },
                        ) {
                            PortraitAvatar(graph, 30.dp, store = graph.myAvatar)
                            OutlinedTextField(
                                value = draft,
                                onValueChange = {
                                    if (it.length <= 120) {
                                        draft = it
                                        commentError = false
                                    }
                                },
                                enabled = !sendingComment,
                                placeholder = { Text("说点什么…", style = MaterialTheme.typography.labelSmall) },
                                // 回复态把 @对方 放进输入框，发送时用被点评论的顶层 id 作为父级。
                                prefix = if (target != null) {
                                    { Text("@${target.who} ", style = MaterialTheme.typography.labelSmall, color = palette.accent) }
                                } else {
                                    null
                                },
                                trailingIcon = if (target != null) {
                                    {
                                        IconButton(
                                            onClick = { replyTo = null },
                                            modifier = Modifier.size(SizeTokens.touchTarget),
                                            enabled = !sendingComment,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "取消回复",
                                                tint = palette.ink3,
                                                modifier = Modifier.size(IconSize.md),
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                singleLine = true,
                                // 输入框做成圆形药丸，和头像并排时不显得是表单。
                                shape = CircleShape,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = draft.isNotBlank() && !sendingComment,
                                onClick = {
                                    sendingComment = true
                                    commentError = false
                                    onComment(draft.trim(), target?.let { it.parentId ?: it.id }) { saved ->
                                        sendingComment = false
                                        if (saved) {
                                            draft = ""
                                            replyTo = null
                                        } else {
                                            commentError = true
                                        }
                                    }
                                },
                            ) { Text(if (sendingComment) "发送中…" else "发送") }
                        }
                        if (commentError) {
                            FeedError("没能保存评论，请重试。", Modifier.padding(top = Space.xs))
                        }
                    }
                }
            }
            if (mine && !post.memoryChecked) {
                Text("我在记着…", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
            }
            Text(feedTime(post.at), style = MaterialTheme.typography.labelSmall, color = palette.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * 一条评论或一级回复：整行 44dp 热区，点它就回复这条。
 * 回复挂在父评论下面、缩进一级，并写明「回复 谁」。
 */
@Composable
private fun FeedCommentRow(
    comment: FeedStore.Comment,
    parent: FeedStore.Comment?,
    replying: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = SizeTokens.touchTarget)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (replying) palette.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.ink)) { append(comment.who) }
                if (parent != null) {
                    withStyle(SpanStyle(color = palette.ink2)) { append(" 回复 ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.ink)) { append(parent.who) }
                }
                append("：")
                append(comment.text)
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink,
        )
    }
}

/** 动态卡片上的小动作：图标 + 文案，最小热区 44。 */
@Composable
private fun FeedAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.heightIn(min = SizeTokens.touchTarget)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** 动态操作行里的单图标按钮：固定 44dp 热区，无文字，靠 contentDescription 保留语义。 */
@Composable
private fun FeedIconAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(SizeTokens.touchTarget).clip(CircleShape).pressClick(pressedScale = 0.90f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * 动态图片统一 4:3 居中裁切（cover）：单图和多图共用同一个比例与缝隙，
 * 长图、竖图不会把动态撑得很高，动态流行高保持稳定。
 */
@Composable
private fun PostImages(images: List<String>, onOpen: (String) -> Unit) {
    val ratio = Modifier.aspectRatio(SizeTokens.feedImageAspect)
    when {
        images.size == 1 -> FeedThumb(images.first(), Modifier.fillMaxWidth().then(ratio), onOpen, radius = 0.dp)
        // 两张各占一半，不再固定三列留一格空白。
        images.size == 2 -> Row(horizontalArrangement = Arrangement.spacedBy(Space.imageGap)) {
            images.forEach { path ->
                FeedThumb(path, Modifier.weight(1f).then(ratio), onOpen, radius = 0.dp)
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.imageGap)) {
            images.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.imageGap)) {
                    row.forEach { path ->
                        FeedThumb(path, Modifier.weight(1f).then(ratio), onOpen, radius = 0.dp)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FeedThumb(path: String, modifier: Modifier, onOpen: (String) -> Unit, radius: androidx.compose.ui.unit.Dp = Radius.sm) {
    val bitmap = remember(path) { decodeThumb(path) }
    val palette = LocalLilyPalette.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(palette.surface2)
            // 大图只压 0.985，按下去有回应但不晃眼。
            .pressClick(pressedScale = 0.985f) { onOpen(path) },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "动态图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = palette.ink3)
        }
    }
}

/** 发布态：文字 + 最多 9 图；相册选择后先留在内存，发布时才落盘。 */
@Composable
private fun FeedEditor(
    onCancel: () -> Unit,
    onPublish: (String, List<Uri>, (FeedViewModel.PublishResult) -> Unit) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalLilyPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val uris = remember { mutableStateListOf<Uri>() }
    var publishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { picked ->
        picked.take(FeedStore.MAX_IMAGES - uris.size).forEach { uris += it }
    }
    BackHandler { if (!publishing) onCancel() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !publishing) {
                    Text(stringResource(R.string.cancel), color = palette.ink2)
                }
                Text(
                    "发点什么",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = (text.isNotBlank() || uris.isNotEmpty()) && !publishing,
                    onClick = {
                        publishing = true
                        error = null
                        onPublish(text, uris.toList()) { result ->
                            publishing = false
                            when (result) {
                                FeedViewModel.PublishResult.SAVED -> onSaved()
                                FeedViewModel.PublishResult.ATTACHMENT_FAILED -> {
                                    error = "有照片无法读取，请移除或重新选择后再发布。"
                                }
                                FeedViewModel.PublishResult.STORE_FAILED -> {
                                    error = "没能保存动态，内容仍在编辑器里。请重试。"
                                }
                            }
                        }
                    },
                ) { Text(if (publishing) "发布中…" else "发") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    enabled = !publishing,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text("今天发生了什么…", style = MaterialTheme.typography.bodyLarge, color = palette.ink3)
                            }
                            inner()
                        }
                    },
                )
                error?.let { FeedError(it) }
                Text("照片 ${uris.size}/${FeedStore.MAX_IMAGES}", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 图片和「添加」共用三列格子，最多 9 张。
                    val cells: List<Uri?> = uris.toList() +
                        if (uris.size < FeedStore.MAX_IMAGES) listOf(null) else emptyList()
                    cells.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { uri ->
                                if (uri == null) {
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f)
                                            .clip(RoundedCornerShape(Radius.sm))
                                            .dashedBorder(palette.line, radius = Radius.sm)
                                            .clickable(enabled = !publishing) { picker.launch("image/*") },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.AddPhotoAlternate,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = palette.ink3,
                                            )
                                            Text("加张照片", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
                                        }
                                    }
                                } else {
                                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(Radius.sm))) {
                                        UriThumb(context, uri, Modifier.fillMaxSize())
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                                                .clickable(enabled = !publishing) { uris.remove(uri) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "移除图片",
                                                modifier = Modifier.size(13.dp),
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
            }
        }
    }
}

/** 本机收藏页：只读快照，可逐条取消；不向她发消息、不进记忆。 */
@Composable
private fun SavedPostsScreen(
    items: List<dev.lily.app.feed.SavedPostsStore.Saved>,
    onBack: () -> Unit,
    onRemove: (String) -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val palette = LocalLilyPalette.current
    BackHandler(onBack = onBack)
    Surface(color = palette.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.page, top = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = palette.ink2,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
                Text(
                    "收藏",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(Space.page),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "还没有收藏。\n动态里点「收藏」，只存在这台手机上，也不会通知对方。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink3,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.page),
                    verticalArrangement = Arrangement.spacedBy(Space.block),
                ) {
                    items(items, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (item.author == FeedStore.AUTHOR_ME) "你" else item.authorName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.ink2,
                                )
                                Text(
                                    "  ·  ${feedTime(item.at)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.ink3,
                                )
                                Spacer(Modifier.weight(1f))
                                FeedAction(Icons.Filled.Bookmark, "取消收藏", palette.accent) { onRemove(item.id) }
                            }
                            if (item.text.isNotBlank()) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.ink,
                                    maxLines = 8,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (item.images.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = Space.xs),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    item.images.take(3).forEach { path ->
                                        FeedThumb(path, Modifier.size(92.dp), onOpenImage)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UriThumb(context: Context, uri: Uri, modifier: Modifier) {
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun FeedImageViewer(path: String, onClose: () -> Unit) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "大图", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("图片已删除", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        Text(
            "关闭",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(Space.xl).clickable(onClick = onClose),
        )
    }
}

/** 虚线边框（Compose 没有现成 dashed border）。 */
private fun Modifier.dashedBorder(
    color: Color,
    width: androidx.compose.ui.unit.Dp = 1.dp,
    radius: androidx.compose.ui.unit.Dp = Radius.lg,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f),
        ),
    )
}

/** 缩略图按最长边采样，避免九张原图一起进内存。 */
private fun decodeThumb(path: String, max: Int = 720): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(path, options)?.asImageBitmap() }.getOrNull()
}

/** 今天的动态只显示时分，往前的带上日期。 */
private fun feedTime(at: Long): String {
    val zoned = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val today = zoned.toLocalDate() == LocalDate.now()
    val pattern = if (today) "'今天' HH:mm" else "MM-dd HH:mm"
    return zoned.format(DateTimeFormatter.ofPattern(pattern))
}
