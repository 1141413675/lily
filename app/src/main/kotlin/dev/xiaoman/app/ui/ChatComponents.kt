package dev.xiaoman.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaoman.app.R
import dev.xiaoman.app.session.ChatItem
import dev.xiaoman.app.voice.AudioPlayer
import java.io.File

@Composable
internal fun ChatHeader(
    onCall: () -> Unit,
    name: String,
    /** 在场只说「在 / 有点远 / 还没连上」，不把处理过程写成状态读数。 */
    presence: String = "在",
    /** 长按通话键：让她打过来，用来展示来电界面。 */
    onIncoming: () -> Unit = {},
    /** 聊天是独立全屏页：左上角返回上一次的内容页。 */
    onBack: (() -> Unit)? = null,
    /** 头像统一走相册头像；没选图时退回原来的浅色圆形占位。 */
    avatar: @Composable () -> Unit = {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(SizeTokens.avatarHeader)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp)) }
        }
    },
) {
    val palette = LocalXiaomanPalette.current
    Surface(color = palette.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = palette.ink2,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
            }
            avatar()
            Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                Text(
                    name,
                    fontSize = TypeScale.headline.size,
                    lineHeight = TypeScale.headline.line,
                    fontWeight = FontWeight.Medium,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                    // 在线点只表示她在，不用于「正在输入」；状态必须有文字。
                    if (presence == "在") {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(palette.online))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(presence, style = MaterialTheme.typography.labelMedium, color = palette.ink3, maxLines = 1)
                }
            }
            HeaderCircleButton(Icons.Outlined.Call, stringResource(R.string.call_start), onCall, palette.accent, palette.accentSoft, onLongPress = onIncoming)
        }
    }
}

/** 顶栏圆形操作按钮：淡紫底 + 品牌紫图标，描边退场。 */
@Composable
private fun HeaderCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color,
    container: Color,
    onLongPress: (() -> Unit)? = null,
) {
    Surface(shape = CircleShape, color = container) {
        Box(
            Modifier.size(SizeTokens.touchTarget).pointerInput(onClick, onLongPress) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress?.invoke() })
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ChatWelcome(lastSaid: String? = null, onImage: Boolean = false, onExample: (String) -> Unit) {
    // 背景图模式下标题与提示直接压在图上会失真，垫一层近乎不透明的表面保证正文对比度。
    if (onImage) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(14.dp)) { WelcomeBody(0.dp, lastSaid, onExample) }
        }
    } else {
        WelcomeBody(26.dp, lastSaid, onExample)
    }
}

@Composable
private fun WelcomeBody(topPadding: Dp, lastSaid: String?, onExample: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text("我在。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("想说什么就说，不想说也没关系。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!lastSaid.isNullOrBlank()) {
            Surface(
                onClick = { onExample(lastSaid) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = Elevation.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Text("上次你说", style = MaterialTheme.typography.labelSmall, color = LocalXiaomanPalette.current.ink3)
                    Text(
                        "「$lastSaid」",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "接着往下说",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        Suggestion("今天有点累", "今天有点累，不太想说话。", onExample)
        Suggestion("在干嘛呢", "在干嘛呢？", onExample)
        Text("不想打字，就按住说话。", modifier = Modifier.padding(top = Space.sm),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 空状态只给两行可点的小字，不做卡片、不加箭头。 */
@Composable
private fun Suggestion(text: String, prompt: String, onExample: (String) -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onExample(prompt) }
            .padding(vertical = Space.xs, horizontal = 2.dp),
    )
}

@Composable
internal fun ProcessCard(entries: List<ChatItem.Progress>) {
    var expanded by rememberSaveable(entries.first().id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
        shadowElevation = Elevation.card) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ListAlt, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("刚才做的事 · ${entries.size} 条", Modifier.weight(1f).padding(horizontal = Space.sm), style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起" else "展开", modifier = Modifier.size(20.dp))
            }
            if (expanded) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                entries.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
internal fun ChatMessage(
    item: ChatItem,
    player: AudioPlayer,
    onOpenImage: (String) -> Unit = {},
    /** 参考图里左侧的小头像，只在对方文字消息旁出现。 */
    avatar: (@Composable () -> Unit)? = null,
    /** 转发动态卡上的原作者头像。 */
    shareAvatar: (@Composable () -> Unit)? = null,
    /** 我已经回过这条，气泡下方显示已读双勾。 */
    seen: Boolean = false,
    /** 对方的名字，用在引用块里。 */
    peerName: String = "TA",
    onReply: ((ChatItem.Quote) -> Unit)? = null,
    onReact: ((String?) -> Unit)? = null,
    /** 仅自己发的文字消息可撤回。 */
    onRecall: (() -> Unit)? = null,
    /** 一组里的最后一条才带 6dp 尾角；同组中间四条角一致。 */
    groupEnd: Boolean = true,
    /** 只有真正新到的那条才做入场；历史消息跟随页面转场，不逐条再动一次。 */
    animateEnter: Boolean = true,
) {
    val palette = LocalXiaomanPalette.current
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    // 撤回、表情、引用变化时高度平滑过渡，不突然跳一格。
    val body: @Composable () -> Unit = {
    Box(Modifier.fillMaxWidth().animateContentSize(sizeTween())) {
    if (item is ChatItem.User && item.recalled) {
        RecalledNotice()
    } else when (item) {
        is ChatItem.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            CopyableBubble(
                mine = true,
                text = item.text,
                reaction = item.reaction,
                onReply = onReply?.let { cb -> { cb(ChatItem.Quote(true, item.text)) } },
                onReact = onReact,
                onRecall = onRecall,
            ) { mod ->
                Surface(color = palette.bubbleUser, contentColor = palette.bubbleUserInk,
                    shape = RoundedCornerShape(
                        topStart = Radius.bubble,
                        topEnd = Radius.bubble,
                        bottomEnd = if (groupEnd) Radius.bubbleTail else Radius.bubble,
                        bottomStart = Radius.bubble,
                    ),
                    modifier = mod) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        item.quote?.let { QuoteBlock(it, peerName, palette.bubbleUserInk.copy(alpha = 0.12f)) }
                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (item.reaction != null) ReactionPill(item.reaction, Alignment.End)
            if (seen) {
                Row(Modifier.padding(top = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.DoneAll,
                        contentDescription = "已读",
                        tint = palette.ink3,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        is ChatItem.UserSticker -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StickerBubble(item.file)
        }
        is ChatItem.UserImage -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ImageBubble(item.file) { onOpenImage(item.file) }
        }
        is ChatItem.UserFeedShare -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FeedShareBubble(item, shareAvatar, onOpenImage)
        }
        is ChatItem.AssistantImage -> Row(Modifier.fillMaxWidth()) {
            ImageBubble(item.file) { onOpenImage(item.file) }
        }
        is ChatItem.Assistant -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            avatar?.let {
                Box(Modifier.padding(end = 6.dp, bottom = 2.dp).size(26.dp)) { it() }
            }
            Column(Modifier.weight(1f)) {
                CopyableBubble(
                    mine = false,
                    text = item.text,
                    reaction = item.reaction,
                    onReply = onReply?.let { cb -> { cb(ChatItem.Quote(false, item.text)) } },
                    onReact = onReact,
                ) { mod ->
                    Surface(color = palette.bubbleAgent, contentColor = palette.bubbleAgentInk,
                        shape = RoundedCornerShape(
                            topStart = Radius.bubble,
                            topEnd = Radius.bubble,
                            bottomEnd = Radius.bubble,
                            bottomStart = if (groupEnd) Radius.bubbleTail else Radius.bubble,
                        ),
                        modifier = mod) {
                        Text(chatRichText(item.text), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (item.reaction != null) ReactionPill(item.reaction, Alignment.Start)
            }
        }
        is ChatItem.UserVoice -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            VoiceBubble(item.id, item.file, item.durationMs, mine = true, player)
        }
        is ChatItem.AssistantVoice -> Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            item.songLyrics?.let { lyrics ->
                Surface(color = palette.bubbleAgent, contentColor = palette.bubbleAgentInk,
                    shape = RoundedCornerShape(Radius.bubble),
                    modifier = Modifier.fillMaxWidth(SizeTokens.bubbleMaxWidth).wrapContentWidth(Alignment.Start)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("唱两句给你听", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(chatRichText(lyrics), Modifier.padding(top = Space.xs), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            VoiceBubble(item.id, item.file, item.durationMs, mine = false, player)
        }
        is ChatItem.Question -> NoticeCard(if (item.takeover) "你来弄一下" else "再多说一点", item.text, false)
        // 失败只说一句人话，不给失败伪造她的气泡；重试在输入区重新发一次。
        is ChatItem.Error -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        )
        is ChatItem.CallRecord -> Unit // 旧条目不在聊天流显示；通话记录统一在通话记录页。
        is ChatItem.Progress -> Unit // handled by ProcessCard
    }
    }
    }
    if (animateEnter) {
        AnimatedVisibility(
            visibleState = appear,
            enter = fadeIn(enterTween()) + slideInVertically(slideTween()) { it / 6 },
            modifier = Modifier.fillMaxWidth(),
        ) { body() }
    } else {
        Box(Modifier.fillMaxWidth()) { body() }
    }
}

/** 居中日期：只有日期变化或长间隔才出现，不画通栏分隔线。 */
@Composable
internal fun DayDivider(label: String = "今天") {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = LocalXiaomanPalette.current.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

/** 对方正在打字：头像＋三个呼吸的点，代替生硬的「处理中」。 */
@Composable
internal fun TypingBubble(avatar: (@Composable () -> Unit)? = null) {
    val palette = LocalXiaomanPalette.current
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(Motion.loop, easing = LinearEasing)),
        label = "dots",
    )
    // 系统关闭动画时三个点停在中间帧，不再闪。
    val shownPhase = if (LocalMotionEnabled.current) phase else 1f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        avatar?.let { Box(Modifier.padding(end = 6.dp, bottom = 2.dp).size(26.dp)) { it() } }
        Surface(
            color = palette.bubbleAgent,
            shape = RoundedCornerShape(
                topStart = Radius.bubble, topEnd = Radius.bubble,
                bottomEnd = Radius.bubble, bottomStart = Radius.bubbleTail,
            ),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { i ->
                    val active = shownPhase.toInt() == i
                    Box(
                        Modifier.size(6.dp).clip(CircleShape).background(
                            if (active) palette.ink2 else palette.ink3.copy(alpha = 0.35f),
                        ),
                    )
                }
            }
        }
    }
}

/** 超过 10 秒还没回：只加一行小字和「停」，不显示处理步骤。 */
@Composable
internal fun LongWaitRow(onStop: () -> Unit) {
    val palette = LocalXiaomanPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("她还在写", style = MaterialTheme.typography.labelMedium, color = palette.ink3, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onStop,
            modifier = Modifier.size(SizeTokens.touchTarget),
            contentPadding = PaddingValues(0.dp),
        ) { Text("停", style = MaterialTheme.typography.labelMedium, color = palette.ink2) }
    }
}

/** 撤回后的原位提示：只留一行灰字，不显示气泡、反应和已读。 */
@Composable
private fun RecalledNotice() {
    Text(
        stringResource(R.string.chat_recalled),
        style = MaterialTheme.typography.labelSmall,
        color = LocalXiaomanPalette.current.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** 长按消息弹出的统一菜单；气泡本身按内容收缩，最多占可用宽度的 84%。 */
@Composable
private fun CopyableBubble(
    mine: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    reaction: String? = null,
    onReply: (() -> Unit)? = null,
    onReact: ((String?) -> Unit)? = null,
    onRecall: (() -> Unit)? = null,
    bubble: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth(SizeTokens.bubbleMaxWidth),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            bubble(Modifier.pointerInput(text) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                })
            })
            MessageMenu(
                expanded = menu,
                onDismiss = { menu = false },
                text = text,
                hasReaction = reaction != null,
                onReply = onReply,
                onReact = onReact,
                onRecall = if (mine) onRecall else null,
            )
        }
    }
}

/** 菜单统一成一张圆角卡：表情一行、分隔线、下面图标+文字的等距操作行。 */
@Composable
private fun MessageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    text: String,
    hasReaction: Boolean,
    onReply: (() -> Unit)?,
    onReact: ((String?) -> Unit)?,
    onRecall: (() -> Unit)?,
) {
    val context = LocalContext.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.md),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        if (onReact != null) {
            Row(
                Modifier.padding(horizontal = Space.lg, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                listOf("❤️", "😂", "🥺", "😮", "😢", "👍").forEach { emoji ->
                    Text(
                        emoji,
                        fontSize = 20.sp,
                        modifier = Modifier.clip(CircleShape)
                            .clickable { onReact(emoji); onDismiss() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                if (hasReaction) {
                    Text(
                        "取消",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalXiaomanPalette.current.ink2,
                        modifier = Modifier.clip(RoundedCornerShape(Radius.sm))
                            .clickable { onReact(null); onDismiss() }
                            .padding(horizontal = Space.sm, vertical = 4.dp),
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(vertical = Space.xs),
                color = LocalXiaomanPalette.current.line.copy(alpha = 0.6f),
            )
        }
        MenuAction(Icons.Outlined.ContentCopy, "复制") {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("消息", text))
            onDismiss()
        }
        onReply?.let { reply ->
            MenuAction(Icons.AutoMirrored.Outlined.Reply, "回复") {
                reply()
                onDismiss()
            }
        }
        onRecall?.let { recall ->
            MenuAction(Icons.AutoMirrored.Outlined.Undo, "撤回", danger = true) {
                recall()
                onDismiss()
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.widthIn(min = 168.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.md))
        Text(label, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** 气泡里的引用块：左边一条竖线，上面是说话人，下面是原话摘要。 */
@Composable
private fun QuoteBlock(quote: ChatItem.Quote, peerName: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 5.dp)
            .clip(RoundedCornerShape(8.dp)).background(tint).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(2.dp).height(30.dp).background(LocalXiaomanPalette.current.ink3.copy(alpha = 0.6f)))
        Column(Modifier.padding(start = 7.dp)) {
            Text(
                if (quote.fromMe) "你" else peerName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                quote.text.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 贴在气泡下面的小反应，点一下不会误触。 */
@Composable
private fun ReactionPill(emoji: String, alignment: Alignment.Horizontal) {
    val scale = remember { Animatable(0.8f) }
    val spec = enterTween()
    LaunchedEffect(emoji) {
        scale.animateTo(1f, spec)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, LocalXiaomanPalette.current.line),
            modifier = Modifier.padding(top = 2.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        ) {
            Text(emoji, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** 微信式语音气泡：只显示秒数与播放状态，点一下播放/停止；气泡里不带文字。 */
@Composable
private fun VoiceBubble(id: Long, path: String, durationMs: Int, mine: Boolean, player: AudioPlayer) {
    val palette = LocalXiaomanPalette.current
    val playingId by player.playingId.collectAsState()
    val playing = playingId == id
    val ready = path.isNotBlank()
    val seconds = maxOf(1, durationMs / 1000)
    val width = (112f + minOf(seconds, 60) * 2.2f).coerceAtMost(250f).dp
    Surface(
        color = if (mine) palette.bubbleUser else palette.bubbleAgent,
        contentColor = if (mine) palette.bubbleUserInk else palette.bubbleAgentInk,
        // 语音气泡整体更圆：22dp 主圆角，只保留一个 8dp 的尾角。
        shape = if (mine) RoundedCornerShape(Radius.voiceBubble, Radius.voiceBubble, Radius.voiceTail, Radius.voiceBubble)
        else RoundedCornerShape(Radius.voiceTail, Radius.voiceBubble, Radius.voiceBubble, Radius.voiceBubble),
        onClick = { if (ready) player.toggle(id, File(path)) },
        modifier = Modifier.width(width),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mine) {
                Text(if (ready) stringResource(R.string.voice_seconds, seconds) else stringResource(R.string.voice_generating),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Outlined.VolumeUp,
                contentDescription = stringResource(if (playing) R.string.voice_stop else R.string.voice_play),
                tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            if (!mine) {
                Spacer(Modifier.weight(1f))
                Text(if (ready) stringResource(R.string.voice_seconds, seconds) else stringResource(R.string.voice_generating),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 表情：96–112dp 方形、无底色、不套文字气泡（设计稿规定）。 */
/** 聊天里的转发动态：像社交应用私信中的帖子卡，而不是把正文再装进普通文字气泡。 */
@Composable
private fun FeedShareBubble(
    item: ChatItem.UserFeedShare,
    avatar: (@Composable () -> Unit)?,
    onOpenImage: (String) -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier.width(252.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                avatar?.let { Box(Modifier.size(24.dp)) { it() } }
                Column(Modifier.padding(start = if (avatar == null) 0.dp else 8.dp)) {
                    Text(
                        item.authorName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("动态", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
                }
            }
            item.images.firstOrNull()?.let { path ->
                val bitmap = remember(path) {
                    runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                        .background(palette.surface2)
                        .clickable(enabled = bitmap != null) { onOpenImage(path) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(bitmap, "动态图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Outlined.Image, null, tint = palette.ink3)
                    }
                    if (item.images.size > 1) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Text(
                                "+${item.images.size - 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun StickerBubble(path: String) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "表情", contentScale = ContentScale.Fit, modifier = Modifier.size(104.dp))
    } else {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Text("表情已删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 照片：最长边 240dp、最高 300dp、等比裁切；点开看大图。 */
@Composable
private fun ImageBubble(path: String, onOpen: () -> Unit) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    if (bitmap == null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
            Text("图片已删除", Modifier.padding(horizontal = Space.lg, vertical = 9.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    val maxW = 240f
    val maxH = 300f
    val width: Dp
    val height: Dp
    if (ratio >= maxW / maxH) {
        width = maxW.dp
        height = (maxW / ratio).dp
    } else {
        height = maxH.dp
        width = (maxH * ratio).dp
    }
    Image(
        bitmap,
        contentDescription = "照片",
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
    )
}

@Composable
private fun NoticeCard(title: String, body: String, error: Boolean) {
    val palette = LocalXiaomanPalette.current
    // 错误卡的颜色成对取自 token：暗色下是深红底 + 浅粉字，正文始终可读。
    val container = if (error) palette.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) palette.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            Text(chatRichText(body), style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

/** Basic emphasis and lists from model replies, rendered as text rather than raw Markdown markers. */
internal fun chatRichText(raw: String) = buildAnnotatedString {
    val text = raw.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("(?m)^[-*]\\s+"), "• ")
    var position = 0
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach { match ->
        append(text.substring(position, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        position = match.range.last + 1
    }
    append(text.substring(position))
}
