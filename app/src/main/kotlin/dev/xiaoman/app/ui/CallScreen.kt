package dev.xiaoman.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.xiaoman.app.relationship.Relationship
import dev.xiaoman.app.voice.CallState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全屏通话浮层：拨出后先振铃（呼吸波纹 + 等待计时），按心情决定接通延迟与是否接听；
 * 接通后保留实时全双工 / 免提降级两套通道，结束回到聊天页；明细只进通话记录，不进聊天流。
 */
@Composable
fun CallScreen(graph: AppGraph, onOpenSettings: () -> Unit, onOpenChat: () -> Unit, onEnd: () -> Unit) {
    val controller = graph.call
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val question by graph.session.question.collectAsStateWithLifecycle()
    val approval by graph.session.approval.collectAsStateWithLifecycle()
    val mic = rememberMicPermission()
    val granted by rememberUpdatedState(mic.granted)

    var wasActive by remember { mutableStateOf(false) }

    // 通话结束后自动回聊天；未接是情绪事件，停在这一页等用户自己关。
    LaunchedEffect(state.active, state.missed) {
        if (state.active) {
            wasActive = true
        } else if (wasActive && !state.missed) {
            delay(250)
            onEnd()
        }
    }

    LaunchedEffect(Unit) {
        if (!mic.granted) mic.request()
    }
    // 默认轮次通话只需要识别与朗读；实时通道要用户显式打开且已配置才算就绪。
    val ready = (settings.asr.configured && settings.tts.configured) ||
        (settings.realtimeEnabled && settings.realtime.configured)
    LaunchedEffect(granted, ready) {
        if (granted && ready && !controller.state.value.active && controller.state.value.phase == CallState.Phase.IDLE) {
            controller.startCall()
        }
    }
    DisposableEffect(Unit) {
        onDispose { controller.endCall() }
    }

    val herName = settings.persona.nameOr(stringResource(R.string.app_name))
    // 通话页是全屏浮层，自己铺一层页面底色，暗色下才不会露出系统窗底。
    val palette = LocalXiaomanPalette.current
    // 振铃态头像距顶约屏高 21%；接通后交给屏幕正中那个会起伏的大圆。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val inVoiceMode = state.active && !state.missed && state.phase != CallState.Phase.RINGING
    val level by controller.level.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (inVoiceMode) {
            VoiceStage(state, level, controller, Modifier.weight(1f))
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = screenHeight * 0.15f)
                    .padding(horizontal = Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    state.phase == CallState.Phase.RINGING -> RingingAvatar(graph)
                    state.missed -> MissedAvatar(graph)
                    else -> Unit
                }
                if (state.phase == CallState.Phase.RINGING || state.missed) {
                    Text(
                        herName,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.ink,
                        modifier = Modifier.padding(top = Space.sm),
                    )
                }
                if (state.phase == CallState.Phase.RINGING) {
                    // 呼出与来电用两套状态词：一个是「等对方接」，一个是「等你接」。
                    Text(
                        if (state.incoming) "响铃中 · 她在等你接" else "呼出中 · 等${herName}接",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.missed) {
                    Text(
                        stringResource(R.string.call_missed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.missed) palette.ink2 else palette.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .clickable { if (!state.missed) controller.dismissMessage() },
                    )
                }
            }
        }

        // 配置提示合并成一张，并贴着底部操作区，和「回去 / 再打一次」在同一视线里。
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
            if (!settings.configured) {
                BannerText(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
            } else if (!ready) {
                BannerText(stringResource(R.string.call_voice_unconfigured), stringResource(R.string.banner_models_action), onOpenSettings)
            }
            if (!mic.granted) BannerText(stringResource(R.string.call_mic_permission), stringResource(R.string.call_mic_action), mic.request)
            if (question != null || approval != null) {
                BannerText(stringResource(R.string.call_need_confirm), stringResource(R.string.call_go_chat), onOpenChat)
            }

            when {
                // 振铃中：只有她打来（来电）才给接听 + 挂断；我拨出去只留一颗取消。
                state.phase == CallState.Phase.RINGING -> {
                    val palette = LocalXiaomanPalette.current
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(top = Space.lg, bottom = Space.xl + screenHeight * 0.08f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        // 接听/挂断都用更钝的通话语义色，各自带一圈同色柔光。
                        if (state.incoming) {
                            CallCircle(
                                color = palette.callAccept,
                                icon = Icons.Outlined.Call,
                                label = stringResource(R.string.call_answer),
                                iconTint = palette.callAcceptInk,
                                glow = true,
                                onClick = controller::connectNow,
                            )
                        }
                        CallCircle(
                            color = palette.callHangup,
                            icon = Icons.Outlined.CallEnd,
                            label = stringResource(if (state.incoming) R.string.call_hangup else R.string.cancel),
                            iconTint = palette.callHangupInk,
                            glow = true,
                            onClick = controller::endCall,
                        )
                    }
                }
                // 通话中：静音 / 挂断 / 免提三颗，挂断居中；免提按当前真实路由高亮。
                state.active -> Row(
                    Modifier.fillMaxWidth()
                        .padding(top = Space.lg, bottom = Space.xl + screenHeight * 0.08f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val palette = LocalXiaomanPalette.current
                    CallCircle(
                        color = if (state.muted) palette.accentSoft else palette.surface2,
                        icon = if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        iconTint = if (state.muted) palette.accentOnSoft else palette.ink2,
                        label = stringResource(if (state.muted) R.string.call_unmute else R.string.call_mute),
                        onClick = { controller.setMuted(!state.muted) },
                    )
                    CallCircle(
                        color = palette.callHangup,
                        icon = Icons.Outlined.CallEnd,
                        label = stringResource(R.string.call_hangup),
                        iconTint = palette.callHangupInk,
                        glow = true,
                        onClick = controller::endCall,
                    )
                    // 免提 / 听筒：label 写当前状态，切完一眼能看出现在是哪种。
                    CallCircle(
                        color = if (state.speakerOn) palette.accentSoft else palette.surface2,
                        icon = if (state.speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.PhoneInTalk,
                        iconTint = if (state.speakerOn) palette.accentOnSoft else palette.ink2,
                        label = stringResource(if (state.speakerOn) R.string.call_speaker else R.string.call_earpiece),
                        onClick = { controller.setSpeaker(!state.speakerOn) },
                    )
                }
                else -> Row(
                    Modifier.fillMaxWidth().padding(bottom = Space.xl, top = Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Button(
                        onClick = onEnd,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(Radius.pill),
                    ) { Text(stringResource(R.string.call_close)) }
                    OutlinedButton(
                        onClick = { controller.startCall() },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(Radius.pill),
                    ) { Text(stringResource(R.string.call_retry)) }
                }
            }
        }
    }
}

/**
 * 接通后的语音模式：上方一行状态词，中间一个大圆用波纹/光环/波形分辨三态，
 * 底下是静音、挂断、免提三颗圆钮；不显示逐字转写，也不显示内部状态占位。
 */
@Composable
private fun VoiceStage(
    state: CallState,
    level: Float,
    controller: dev.xiaoman.app.voice.CallController,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXiaomanPalette.current
    Column(modifier.fillMaxWidth()) {
        // 状态词只在这套里用「通话中」；振铃阶段另有「呼出中 / 响铃中」，三态不共用一套文案。
        Crossfade(
            targetState = "通话中 · " + voiceStatus(state.phase),
            animationSpec = enterTween(),
            label = "voiceStatus",
        ) { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Space.block),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            VoiceOrb(state.phase, level) {
                if (controller.state.value.phase == CallState.Phase.SPEAKING) controller.interrupt()
            }
        }
        // 说完一句只落一行她的话，不实时滚动。
        state.reply?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = Space.sm),
            )
        }
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl)
                    .clickable { controller.dismissMessage() },
            )
        }
    }
}

/** 只有「在听 / 在想 / 在说」三个词，不再堆别的文案。 */
private fun voiceStatus(phase: CallState.Phase): String = when (phase) {
    CallState.Phase.THINKING, CallState.Phase.TRANSCRIBING -> "在想"
    CallState.Phase.SPEAKING -> "在说"
    else -> "在听"
}

/**
 * 语音大圆：三态用三种图像语言，不叠状态小字。
 * - 在听：两圈波纹随麦克风音量涨落；没声音时几乎静止。
 * - 在想：头像外一圈缓慢呼吸的光环，不跟音量走。
 * - 在说：环绕的波形柱随语流跳动。
 * 颜色全部走 token；系统关动画时只留静止的大圆。
 */
@Composable
private fun VoiceOrb(phase: CallState.Phase, level: Float, onTap: () -> Unit) {
    val palette = LocalXiaomanPalette.current
    val transition = rememberInfiniteTransition(label = "voiceOrb")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    // 两段不同周期的缓慢起伏，叠出接近说话节奏的语流。
    val sway by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(820, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val swaySlow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_370, easing = LinearEasing), RepeatMode.Reverse),
        label = "swaySlow",
    )
    // 聆听波纹与播报波形的循环相位。
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    val calm = !LocalMotionEnabled.current
    // 状态严格跟 phase 走；识别中算「在想」，不提前回到「在听」。
    val listening = phase == CallState.Phase.LISTENING || phase == CallState.Phase.CONNECTING
    val thinking = phase == CallState.Phase.THINKING || phase == CallState.Phase.TRANSCRIBING
    val speaking = phase == CallState.Phase.SPEAKING
    val strength = level.coerceIn(0f, 1f)
    val voice = maxOf(strength, sway * 0.7f + swaySlow * 0.3f)
    val target = if (calm) 1f else when {
        thinking -> breathe
        speaking -> 1f + voice * 0.10f
        listening -> 1f + strength * 0.12f
        else -> 1f
    }
    // 三态切换用 240ms 过渡：听→想→说之间不硬切；系统关动画时停在静止帧。
    val scale by animateFloatAsState(
        target,
        animationSpec = if (calm) tween(0) else tween(Motion.slow, easing = Motion.easing),
        label = "orbScale",
    )
    val brush = Brush.radialGradient(
        listOf(palette.accent.copy(alpha = 0.55f), palette.accentSoft, palette.surface),
    )
    Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
        // 聆听：两圈同心波纹，半径与亮度都跟麦克风音量走。
        if (listening && !calm) repeat(2) { index ->
            val p = (ripple + index * 0.5f) % 1f
            val reach = 1f + p * (0.08f + strength * 0.50f)
            Box(
                Modifier
                    .size(176.dp)
                    .graphicsLayer {
                        scaleX = reach
                        scaleY = reach
                        alpha = (1f - p) * (0.10f + strength * 0.45f)
                    }
                    .border(1.5.dp, palette.accent, CircleShape),
            )
        }
        // 在想：一圈缓慢呼吸的光环，不看音量，避免和聆听混在一起。
        if (thinking && !calm) {
            Box(
                Modifier
                    .size(196.dp)
                    .graphicsLayer { scaleX = breathe; scaleY = breathe }
                    .border(1.dp, palette.accent.copy(alpha = 0.22f), CircleShape),
            )
        }
        Box(Modifier.size(220.dp).clip(CircleShape).background(palette.accentSoft.copy(alpha = 0.35f)))
        Box(
            Modifier
                .size(172.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(brush)
                .border(1.dp, palette.accent.copy(alpha = 0.28f), CircleShape)
                .clickable(onClick = onTap),
        )
        // 在说：一圈波形柱，按下时的语流节奏跳动；点大圆仍可打断。
        if (speaking) {
            val amplitude = if (calm) 0.45f else voice
            Canvas(Modifier.size(236.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val base = size.minDimension / 2f - 14.dp.toPx()
                val bars = 44
                for (i in 0 until bars) {
                    val angle = i.toFloat() / bars * (2 * PI).toFloat() + (if (calm) 0f else wave)
                    val wobble = 0.35f + 0.65f * abs(sin(angle * 3.3f + i * 0.8f))
                    val len = (2f + amplitude * 20f * wobble).dp.toPx()
                    val dx = cos(angle)
                    val dy = sin(angle)
                    drawLine(
                        color = palette.accent.copy(alpha = 0.30f + amplitude * 0.55f),
                        start = Offset(center.x + dx * base, center.y + dy * base),
                        end = Offset(center.x + dx * (base + len), center.y + dy * (base + len)),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** 通话页的大圆钮：72dp 圆 + 图标 + 下方标签。 */
@Composable
private fun CallCircle(
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
    glow: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // 柔光不是描边：按钮自己的颜色降透明度垫一圈，接听/挂断都适用。
            if (glow) {
                Box(Modifier.size(90.dp).background(color.copy(alpha = 0.16f), CircleShape))
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(color)
                    .pressClick(pressedScale = 0.94f, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.ink,
            modifier = Modifier.padding(top = Space.sm),
        )
    }
}

/** 呼叫中：呼吸波纹 + 「正在呼叫」 + 等待秒数，不立刻出声。 */
@Composable
private fun RingingAvatar(graph: AppGraph) {
    val palette = LocalXiaomanPalette.current
    val transition = rememberInfiniteTransition(label = "ring")
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        // 头像外一层静态淡紫氛围光，波纹之外先把人“托”起来。
        Box(
            Modifier.size(150.dp).background(
                Brush.radialGradient(
                    listOf(palette.accent.copy(alpha = 0.20f), Color.Transparent),
                ),
                CircleShape,
            ),
        )
        // 系统关动画时只留头像，不画扩散波纹。
        if (LocalMotionEnabled.current) repeat(3) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1.55f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_800, delayMillis = index * 600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "ripple$index",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_800, delayMillis = index * 600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "alpha$index",
            )
            Box(
                Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .background(palette.accent.copy(alpha = 0.22f), CircleShape),
            )
        }
        PortraitAvatar(graph, 120.dp)
    }
}

@Composable
private fun MissedAvatar(graph: AppGraph) {
    val palette = LocalXiaomanPalette.current
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(150.dp).background(
                Brush.radialGradient(
                    listOf(palette.accent.copy(alpha = 0.14f), Color.Transparent),
                ),
                CircleShape,
            ),
        )
        PortraitAvatar(graph, 120.dp)
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

private fun duration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

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
