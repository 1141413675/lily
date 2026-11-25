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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

/** 内部路由。底栏只展示动态 / 聊天 / 我的，聊天固定在正中。 */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    FEED(R.string.nav_feed, Icons.Outlined.Explore),
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Outlined.Chat),
    ME(R.string.nav_me, Icons.Outlined.Person),
}

@Composable
fun XiaomanRoot(graph: AppGraph) {
    // 启动仍落在聊天；返回时回到上一次的内容页。
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var lastContentTab by rememberSaveable { mutableStateOf(Tab.FEED) }
    var callOverlay by rememberSaveable { mutableStateOf(false) }
    // 动态 ↔ 记忆互相跳转时，把要打开的那一条带到设置里的记忆页。
    var feedFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsMemoryFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // 发布动态时铺满内容区并收起底栏，避免写到一半切走。
    var feedEditing by remember { mutableStateOf(false) }
    // 二级页（记忆/模型/人设等）打开时收起底栏。
    var settingsSubpage by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ViewingImage?>(null) }
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 切页时保留各屏的滚动位置与输入草稿。
    val stateHolder = rememberSaveableStateHolder()
    // 页面转场统一节奏：淡入 + 轻微上移，和二级页、底栏共用同一组 token。
    val pageIn = remember { Animatable(0f) }
    val density = LocalDensity.current
    val pageSpec = enterTween()
    LaunchedEffect(tab) {
        pageIn.snapTo(0f)
        pageIn.animateTo(1f, pageSpec)
    }

    fun openContent(next: Tab) {
        if (next != Tab.CHAT) lastContentTab = next
        tab = next
    }

    // 「发起通话」的回复：打开全屏通话浮层（回复已由 CallController 排队朗读）。
    LaunchedEffect(Unit) {
        graph.openCall.collect { requested ->
            if (requested) {
                callOverlay = true
                graph.openCall.value = false
            }
        }
    }
    // 通话是聊天内的全屏浮层：没有独立入口，结束后回到聊天页。
    if (callOverlay) {
        CallScreen(
            graph = graph,
            onOpenSettings = {
                graph.call.endCall()
                callOverlay = false
                tab = Tab.ME
            },
            onOpenChat = {
                graph.call.endCall()
                callOverlay = false
                tab = Tab.CHAT
            },
            onEnd = {
                callOverlay = false
                tab = Tab.CHAT
            },
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                // 聊天是独立全屏页；二级页也收起底栏。
                // 底栏用 180/140ms 淡入淡出 + 8dp 滑落，键盘弹起时内容不再瞬间跳一格。
                AnimatedVisibility(
                    visible = (tab == Tab.FEED || tab == Tab.ME) && !keyboardOpen &&
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
                    stateHolder.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.CHAT -> ChatScreen(
                                graph,
                                onBack = { tab = lastContentTab },
                                onOpenSettings = { tab = Tab.ME },
                                onCall = { callOverlay = true },
                                onViewImage = { viewing = it },
                            )
                            Tab.FEED -> FeedScreen(
                                graph,
                                focusPostId = feedFocus,
                                onFocusConsumed = { feedFocus = null },
                                onEditingChange = { feedEditing = it },
                                onOpenMemory = { permalink ->
                                    settingsMemoryFocus = permalink
                                    openContent(Tab.ME)
                                },
                                onOpenChat = { openContent(Tab.CHAT) },
                            )
                            Tab.ME -> SettingsScreen(
                                graph,
                                openMemoryPermalink = settingsMemoryFocus,
                                onOpenMemoryConsumed = { settingsMemoryFocus = null },
                                onSubpageChange = { settingsSubpage = it },
                            )
                        }
                    }
                }
            }
        }
        viewing?.let { img ->
            ImageViewer(
                path = img.file,
                onClose = { viewing = null },
                onSave = {
                    val ok = saveToGallery(context, img.file)
                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    graph.session.removeItem(img.id)
                    // 她分享的图来自图库，删消息不删图库里的原图。
                    if (img.mine) graph.photos.delete(img.file)
                    viewing = null
                },
            )
        }
    }
}

/**
 * 悬浮底栏：动态 / 聊天 / 我的。
 * 62dp 高、24dp 圆角、1dp 描边；两侧是低饱和图标，聊天固定居中，是底栏里唯一的实心主色圆。
 */
@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalXiaomanPalette.current
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = Space.lg, vertical = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.surface.copy(alpha = 0.96f),
            shadowElevation = Elevation.floating,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = SizeTokens.navHeight).padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarItem(Tab.FEED, current, onSelect, Modifier.weight(1f))
                ChatCenterButton(current == Tab.CHAT, onSelect)
                BarItem(Tab.ME, current, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BarItem(tab: Tab, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val palette = LocalXiaomanPalette.current
    val selected = current == tab
    Column(
        modifier.heightIn(min = SizeTokens.touchTarget).clip(RoundedCornerShape(Radius.md))
            .pressClick(pressedScale = 0.94f) { onSelect(tab) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            tab.icon,
            contentDescription = null,
            tint = if (selected) palette.accent else palette.ink3,
            modifier = Modifier.size(IconSize.nav),
        )
        // 选中态：图标、文字、圆点统一用品牌紫，不铺整块底色。
        Box(
            Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape)
                .background(if (selected) palette.accent else Color.Transparent),
        )
        Text(
            stringResource(tab.label),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) palette.accent else palette.ink3,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ChatCenterButton(selected: Boolean, onSelect: (Tab) -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(
        Modifier.width(88.dp).heightIn(min = SizeTokens.touchTarget)
            .clip(RoundedCornerShape(Radius.md))
            .pressClick(pressedScale = 0.96f) { onSelect(Tab.CHAT) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 与两侧同族线框图标；选中时才铺品牌紫，未选中退成表面色，避免和线框图标割裂。
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(if (selected) palette.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) palette.accent else palette.surface2,
                shadowElevation = 0.dp,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = stringResource(Tab.CHAT.label),
                        tint = if (selected) palette.accentInk else palette.ink2,
                        modifier = Modifier.size(IconSize.xl),
                    )
                }
            }
        }
    }
}
