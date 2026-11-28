package dev.lily.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动效词汇表：能用图形与节奏表达的状态，就不写成「正在…」这样的字。
 *
 * 规则很简单：
 * - **瞬时状态**（等一等、在生成、在上传）→ 用这里的动效，屏幕自己会说话。
 * - **持续事实**（额度用完了、没填 Key、需要你确认）→ 仍然写字，因为它们是信息，不是过程。
 *
 * 每个动效都带 `contentDescription`，读屏软件照样知道发生了什么；视觉上不出现文字。
 */

/** 三个点里最暗的那个也要看得见——不然动画起点会像坏掉了。 */
private const val DOT_MIN_ALPHA = 0.32f

/**
 * 三个依次呼吸的点：最通用的一种「还在等」。
 *
 * 点按顺序变亮再变暗（不是同时闪），像一句话在慢慢说。
 *
 * 关键：三个点**始终都看得见**——亮点只是走到谁头上谁更亮，
 * 底下的点仍留一个最低亮度。早期版本让非亮点淡到几乎透明，
 * 结果动画起点只剩一个点，整排看着像坏掉了。
 */
@Composable
internal fun PulsingDots(
    modifier: Modifier = Modifier,
    color: Color = LocalLilyPalette.current.ink2,
    dotSize: Dp = 6.dp,
    gap: Dp = 4.dp,
    /** 无障碍朗读用的说明；调用方应给出「正在……」的完整句子。 */
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(Motion.loop, easing = LinearEasing)),
        label = "dots-phase",
    )
    // 系统关闭动画时三个点停在中间帧，不再闪。
    val shown = if (motion) phase else 0f
    Row(
        modifier = modifier.semantics { if (description != null) contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            // u 是「亮点走到这一格走了多远」：0 表示正好轮到自己，2/3 表示快轮到下一格。
            val u = ((shown - i) % 3f + 3f) % 3f / 3f
            // 亮点在 u=0 时最亮，到 u=0.5 时熄下去。
            val highlight = (1f - 2f * u).coerceAtLeast(0f)
            val alpha = DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * highlight
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 一圈缓慢旋转的弧：表示「在忙，但不知道要多久」。
 *
 * 比进度条诚实——进度条会假装知道进度。
 */
@Composable
internal fun SpinningArc(
    modifier: Modifier = Modifier,
    color: Color = LocalLilyPalette.current.accent,
    size: Dp = 16.dp,
    strokeWidth: Dp = 2.dp,
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "arc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.slow * 4, easing = LinearEasing)),
        label = "arc-angle",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        if (motion) {
            drawArc(
                color = color,
                startAngle = angle,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else {
            // 动画关掉时画一个静止的圆环，仍然表示「在忙」。
            drawArc(
                color = color.copy(alpha = 0.35f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 一条来回滑动的短横：表示「在上传 / 在同步」这类有方向的等待。
 *
 * 比转圈更贴合直觉——东西在动，不是在空转。
 */
@Composable
internal fun SlidingBar(
    modifier: Modifier = Modifier,
    color: Color = LocalLilyPalette.current.accent,
    trackColor: Color = LocalLilyPalette.current.surface2,
    width: Dp = 120.dp,
    height: Dp = 4.dp,
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "slide")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.slow * 2, easing = LinearEasing)),
        label = "slide-progress",
    )
    Canvas(
        modifier
            .size(width = width, height = height)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        // 一段滑块在轨道里来回走；动画关掉时停在中间。
        val travel = size.width - size.height
        val offset = if (motion) {
            (if (progress < 0.5f) progress * 2f else (1f - progress) * 2f) * travel
        } else {
            travel / 2f
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(offset, 0f),
            size = Size(size.height, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}

/**
 * 语音气泡里的波纹：微信式的三道同心弧，**永远朝右**。
 *
 * 早先两侧的图标是镜像的（她的朝左、我的朝右），于是两个气泡长得完全不一样：
 * 我的那条图标被甩到屏幕最右端、声波朝屏幕外指，像坏了。
 * 现在**两侧完全一样**——图标都在秒数左边、声波都朝右；只靠气泡对齐和尾角区分谁说的。
 */
@Composable
internal fun VoiceWaveform(
    modifier: Modifier = Modifier,
    /** 播放中：波纹依次亮起并向外推一点。 */
    playing: Boolean = false,
    color: Color = LocalLilyPalette.current.ink3,
    size: Dp = 18.dp,
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.loop, easing = LinearEasing)),
        label = "voice-wave-phase",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val w = this.size.width
        val h = this.size.height
        // 圆心贴着左侧，声纹从那里一层层往右长。
        val cx = w * 0.16f
        val cy = h / 2f
        val stroke = 1.7.dp.toPx()
        listOf(0.26f, 0.46f, 0.66f).forEachIndexed { i, factor ->
            // 播放时三道弧依次亮起，亮的那一道还往外推一点。
            val u = if (playing) ((phase * 3f - i) % 3f + 3f) % 3f / 3f else 0f
            val highlight = if (playing) (1f - 2f * u).coerceAtLeast(0f) else 0f
            val alpha = if (playing) 0.34f + 0.66f * highlight else 0.82f
            val radius = w * factor * (1f + 0.05f * highlight)
            drawArc(
                color = color.copy(alpha = alpha),
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 通话页中间的那颗球：ChatGPT 语音通话的逻辑——**一屏只留一个主角**。
 *
 * 球自己表达状态：思考是极慢的呼吸，她在听是慢一点的呼吸，她在说是明显的跳动。
 * 所以屏幕上不需要再写「正在说话」「正在听你说」——节奏已经说了。
 */
internal enum class OrbState { Waiting, Listening, Speaking }

@Composable
internal fun VoiceOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    /** 麦克风音量（0..1），让她说话时球跟着你的声音起伏。 */
    level: Float = 0f,
    muted: Boolean = false,
    color: Color = LocalLilyPalette.current.accent,
    size: Dp = 216.dp,
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                durationMillis = when (state) {
                    OrbState.Waiting -> 3600
                    OrbState.Listening -> 2400
                    OrbState.Speaking -> 1150
                },
                easing = LinearEasing,
            ),
        ),
        label = "orb-phase",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val c = this.size.center
        val base = size.toPx() / 2f
        val p = if (motion) phase else 0.5f
        // 呼吸：正弦走一圈，0.5 时最饱满。
        val breath = 0.5f - 0.5f * kotlin.math.cos(p * 2f * Math.PI.toFloat())
        val speaking = state == OrbState.Speaking
        val energy = when (state) {
            OrbState.Speaking -> breath
            OrbState.Listening -> 0.35f + 0.35f * breath + level.coerceIn(0f, 1f) * 0.5f
            OrbState.Waiting -> 0.18f + 0.22f * breath
        }
        // 外圈柔光：说话时扩得更开。
        val haloRadius = base * (0.62f + 0.10f * energy + if (speaking) 0.12f * breath else 0f)
        drawCircle(color = color.copy(alpha = 0.10f + 0.06f * energy), radius = haloRadius, center = c)
        drawCircle(
            color = color.copy(alpha = 0.16f + 0.10f * energy),
            radius = haloRadius * 0.80f,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
        )
        // 核心：她的声音。静音时整颗球压暗。
        val coreAlpha = if (muted) 0.32f else 0.88f + 0.12f * energy
        val coreRadius = base * (0.40f + 0.05f * energy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = coreAlpha),
                    color.copy(alpha = coreAlpha * 0.72f),
                ),
                center = c,
                radius = coreRadius,
            ),
            radius = coreRadius,
            center = c,
        )
    }
}

/**
 * 一圈随呼吸明暗变化的柔光环：表示「她在，但不说话」。
 *
 * 比在线点更明确——在线点只回答「在不在线」，光环回答「醒着没在忙」。
 */
@Composable
internal fun BreathingHalo(
    modifier: Modifier = Modifier,
    color: Color = LocalLilyPalette.current.accent,
    size: Dp = 44.dp,
    description: String? = null,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "halo")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.ambient, easing = LinearEasing)),
        label = "halo-pulse",
    )
    val p = if (motion) pulse else 0.5f
    Canvas(
        modifier
            .size(size)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val maxRadius = minOf(this.size.width, this.size.height) / 2f
        val radius = maxRadius * (0.42f + 0.16f * p)
        drawCircle(
            color = color.copy(alpha = 0.10f + 0.10f * (1f - p)),
            radius = radius,
        )
        drawCircle(
            color = color.copy(alpha = 0.55f + 0.25f * (1f - p)),
            radius = radius * 0.34f,
        )
    }
}
