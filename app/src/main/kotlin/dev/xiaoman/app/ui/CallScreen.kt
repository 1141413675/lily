package dev.xiaoman.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.voice.CallController
import dev.xiaoman.app.voice.CallState
import kotlinx.coroutines.delay

/** 语音通话浮层。系统返回或收起通话不会结束正在进行的语音。 */
@Composable
internal fun CallScreen(
    graph: AppGraph,
    conversation: AppGraph.Conversation,
    startOutgoing: Boolean,
    onOutgoingHandled: () -> Unit,
    onMinimize: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onEnd: () -> Unit,
) {
    val controller = conversation.call
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val question by conversation.session.question.collectAsStateWithLifecycle()
    val approval by conversation.session.approval.collectAsStateWithLifecycle()
    val mic = rememberMicPermission()
    var wasActive by remember { mutableStateOf(false) }
    var outgoingPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var retryOutgoing by rememberSaveable { mutableStateOf(false) }
    var acceptRequested by rememberSaveable { mutableStateOf(false) }
    var captionsOpen by rememberSaveable { mutableStateOf(false) }

    // 默认轮次通话需要识别和朗读，实时通道单独配置。
    val ready = (settings.asr.configured && settings.tts.configured) ||
        (settings.realtimeEnabled && settings.realtime.configured)
    val ringing = state.phase == CallState.Phase.RINGING
    val inVoiceMode = state.active && !state.missed && !ringing
    val incoming = ringing && state.incoming

    LaunchedEffect(state.active, state.missed) {
        if (state.active) {
            wasActive = true
        } else if (wasActive && !state.missed && state.message == null) {
            delay(250)
            onEnd()
        }
    }
    LaunchedEffect(startOutgoing, retryOutgoing, mic.granted, ready, state.active) {
        if ((!startOutgoing && !retryOutgoing) || state.active || !ready) return@LaunchedEffect
        if (mic.granted) {
            controller.startCall()
            retryOutgoing = false
            if (startOutgoing) onOutgoingHandled()
        } else if (!outgoingPermissionRequested) {
            outgoingPermissionRequested = true
            mic.request()
        }
    }
    LaunchedEffect(acceptRequested, mic.granted, incoming) {
        if (acceptRequested && incoming && mic.granted) {
            acceptRequested = false
            controller.connectNow()
        }
    }
    fun hangUp() {
        controller.endCall()
        onEnd()
    }
    BackHandler {
        if (inVoiceMode) {
            onMinimize()
        } else if (ringing) {
            hangUp()
        } else {
            onEnd()
        }
    }

    val name = settings.persona.nameOr(stringResource(R.string.app_name))
    val palette = LocalXiaomanPalette.current
    val light = palette.bg.luminance() >= 0.4f
    val background = if (light) Color(0xFFFAFAFB) else Color(0xFF221F26)
    val controlColor = if (light) Color(0xFFF0EDF3) else Color(0xFF3B3441)
    val hangupColor = if (light) Color(0xFFA63C52) else Color(0xFFAC4358)

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CallHeader(
            graph = graph,
            portrait = conversation.portrait,
            incoming = incoming,
            inVoiceMode = inVoiceMode,
            connected = inVoiceMode && state.phase != CallState.Phase.CONNECTING,
            captionsOpen = captionsOpen,
            onMinimize = onMinimize,
            onToggleCaptions = { captionsOpen = !captionsOpen },
        )
        if (inVoiceMode) {
            VoiceStage(state, controller, Modifier.weight(1f))
            if (captionsOpen) CallCaptions(state, name)
        } else {
            WaitingStage(graph, conversation.portrait, state, name, Modifier.weight(1f))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
            if (!settings.configured) {
                BannerText(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
            } else if (!ready) {
                BannerText(stringResource(R.string.call_voice_unconfigured), stringResource(R.string.banner_models_action), onOpenSettings)
            }
            if (!mic.granted && (!incoming || acceptRequested)) {
                BannerText(stringResource(R.string.call_mic_permission), stringResource(R.string.call_mic_action), mic.request)
            }
            if (question != null || approval != null) {
                BannerText(stringResource(R.string.call_need_confirm), stringResource(R.string.call_go_chat), onOpenChat)
            }

            when {
                ringing -> Row(
                    Modifier.fillMaxWidth().padding(top = Space.lg, bottom = Space.block),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallCircle(
                        color = hangupColor,
                        icon = Icons.Outlined.CallEnd,
                        label = if (incoming) "拒接" else stringResource(R.string.cancel),
                        iconTint = Color.White,
                        onClick = ::hangUp,
                    )
                    if (incoming) {
                        CallCircle(
                            color = palette.callAccept,
                            icon = Icons.Outlined.Call,
                            label = stringResource(R.string.call_answer),
                            iconTint = palette.callAcceptInk,
                            onClick = {
                                if (mic.granted) controller.connectNow()
                                else {
                                    acceptRequested = true
                                    mic.request()
                                }
                            },
                        )
                    }
                }
                state.active -> Row(
                    Modifier.fillMaxWidth().padding(top = Space.lg, bottom = Space.block),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallCircle(
                        color = if (state.muted) palette.accentSoft else controlColor,
                        icon = if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        iconTint = if (state.muted) palette.accentOnSoft else palette.ink,
                        label = stringResource(if (state.muted) R.string.call_unmute else R.string.call_mute),
                        onClick = { controller.setMuted(!state.muted) },
                    )
                    CallCircle(
                        color = if (state.speakerOn) palette.accentSoft else controlColor,
                        icon = if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.Call,
                        iconTint = if (state.speakerOn) palette.accentOnSoft else palette.ink,
                        label = stringResource(if (state.speakerOn) R.string.call_speaker else R.string.call_earpiece),
                        onClick = { controller.setSpeaker(!state.speakerOn) },
                    )
                    CallCircle(
                        color = hangupColor,
                        icon = Icons.Outlined.CallEnd,
                        label = stringResource(R.string.call_hangup),
                        iconTint = Color.White,
                        onClick = ::hangUp,
                    )
                }
                else -> Row(
                    Modifier.fillMaxWidth().padding(bottom = Space.xl, top = Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Button(
                        onClick = onEnd,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(Radius.pill),
                    ) { Text(stringResource(R.string.call_close)) }
                    OutlinedButton(
                        enabled = ready,
                        onClick = {
                            retryOutgoing = true
                            if (!mic.granted) {
                                outgoingPermissionRequested = true
                                mic.request()
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(Radius.pill),
                    ) { Text(stringResource(R.string.call_retry)) }
                }
            }
        }
    }
}

@Composable
private fun CallHeader(
    graph: AppGraph,
    portrait: dev.xiaoman.app.portrait.PortraitStore,
    incoming: Boolean,
    inVoiceMode: Boolean,
    connected: Boolean,
    captionsOpen: Boolean,
    onMinimize: () -> Unit,
    onToggleCaptions: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 70.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connected) PortraitAvatar(graph, 30.dp, store = portrait)
            Text(
                when {
                    connected -> "通话中"
                    incoming -> "来电"
                    else -> "语音通话"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                textAlign = TextAlign.Center,
            )
        }
        if (inVoiceMode) {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "收起通话",
                    tint = palette.accent,
                )
            }
            TextButton(
                onClick = onToggleCaptions,
                modifier = Modifier.align(Alignment.CenterEnd).semantics {
                    stateDescription = if (captionsOpen) "已打开" else "已关闭"
                },
            ) {
                Text("字幕", color = if (captionsOpen) palette.accent else palette.ink2)
            }
        }
    }
}

@Composable
private fun WaitingStage(graph: AppGraph, portrait: dev.xiaoman.app.portrait.PortraitStore, state: CallState, name: String, modifier: Modifier) {
    val ringing = state.phase == CallState.Phase.RINGING
    val palette = LocalXiaomanPalette.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (ringing || state.missed) {
            Box(
                Modifier.size(104.dp).background(palette.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) { PortraitAvatar(graph, 86.dp, store = portrait) }
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Space.lg))
            Text(
                when {
                    state.missed -> stringResource(R.string.call_missed_title)
                    state.incoming -> "${name}想和你说话"
                    else -> "等待${name}接听…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.missed) palette.danger else palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.xs).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.missed) palette.ink2 else palette.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.md),
            )
        }
    }
}

@Composable
private fun VoiceStage(state: CallState, controller: CallController, modifier: Modifier) {
    val palette = LocalXiaomanPalette.current
    val speaking = state.phase == CallState.Phase.SPEAKING
    val status = when {
        state.phase == CallState.Phase.CONNECTING -> "正在连接…"
        speaking -> "正在说话"
        state.phase == CallState.Phase.THINKING || state.phase == CallState.Phase.TRANSCRIBING -> "正在回应"
        state.muted -> "麦克风已静音"
        else -> "正在听你说"
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val interrupt = if (speaking) {
                Modifier.clickable(onClickLabel = "打断语音", role = Role.Button, onClick = controller::interrupt)
            } else {
                Modifier
            }
            Box(interrupt.size(width = 132.dp, height = 62.dp), contentAlignment = Alignment.Center) {
                VoiceSignal(state.phase, state.muted, controller)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.sm).semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Space.md).clickable { controller.dismissMessage() },
                )
            }
        }
    }
}

private val VoiceDotWeights = floatArrayOf(0.36f, 0.71f, 1f, 0.71f, 0.36f)

@Composable
private fun VoiceSignal(phase: CallState.Phase, muted: Boolean, controller: CallController) {
    // 音量只在绘制时读取，通话页面不会随每个音量样本重新组合。
    val level = controller.level.collectAsStateWithLifecycle()
    val motion = LocalMotionEnabled.current
    val tint = LocalXiaomanPalette.current.accent
    val speaking = phase == CallState.Phase.SPEAKING
    val listening = phase == CallState.Phase.LISTENING && !muted
    val waiting = phase == CallState.Phase.CONNECTING || phase == CallState.Phase.THINKING ||
        phase == CallState.Phase.TRANSCRIBING
    Canvas(Modifier.size(width = 112.dp, height = 56.dp)) {
        val strength = if (motion && (speaking || listening)) level.value.coerceIn(0f, 1f) else 0f
        val width = 6.dp.toPx()
        val gap = 19.dp.toPx()
        VoiceDotWeights.forEachIndexed { index, weight ->
            val centerDot = index == 2
            val alpha = when {
                waiting && index in 1..3 -> if (centerDot) 0.86f else 0.44f
                (speaking || listening) && centerDot -> 0.88f
                speaking || listening -> ((strength - 0.07f) * 1.55f * weight).coerceIn(0f, 0.9f)
                else -> 0f
            }
            if (alpha > 0f) {
                val height = (6f + strength * weight * (if (speaking) 40f else 32f)).dp.toPx()
                drawRoundRect(
                    color = tint.copy(alpha = alpha),
                    topLeft = Offset(center.x + (index - 2) * gap - width / 2f, center.y - height / 2f),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2f),
                )
            }
        }
    }
}

@Composable
private fun CallCaptions(state: CallState, name: String) {
    val palette = LocalXiaomanPalette.current
    val reply = state.reply?.takeIf { it.isNotBlank() && it != "(语音回复)" && it != "（语音回复）" }
    val heard = state.heard?.takeIf { it.isNotBlank() }
    val showReply = reply != null && (state.phase == CallState.Phase.SPEAKING || heard == null)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.line),
        shape = RoundedCornerShape(Radius.md),
    ) {
        Column(Modifier.padding(Space.md).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(if (showReply) name else if (heard != null) "你" else "字幕", style = MaterialTheme.typography.labelMedium)
            Text(
                (if (showReply) reply else heard) ?: "开始说话后，字幕会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

@Composable
private fun CallCircle(
    color: Color,
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Column(
        Modifier.widthIn(min = 68.dp).clip(RoundedCornerShape(Radius.md))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xs, vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.size(56.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.ink, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BannerText(text: String, action: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(Radius.md),
        modifier = Modifier.fillMaxWidth().padding(top = Space.md),
    ) {
        Row(Modifier.padding(start = Space.md, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick, modifier = Modifier.tapTarget()) { Text(action) }
        }
    }
}

internal class MicPermission(val granted: Boolean, val request: () -> Unit)

/** RECORD_AUDIO 运行时权限；回到前台时重新读一次，用户可能在系统设置里改过。 */
@Composable
internal fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return MicPermission(granted) { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}
