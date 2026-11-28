package dev.lily.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lily.app.R

/** 当前生效的语义色；字号与颜色都以 DesignTokens 为唯一来源。 */
val LocalLilyPalette = staticCompositionLocalOf { DesignTokens.light }

/**
 * 系统「移除动画」（动画缩放为 0）时为 false。
 * 所有时长压到 0，循环动画停在静止帧；无障碍开关和系统设置都走这一条。
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

@Composable
fun enterTween(): TweenSpec<Float> =
    if (LocalMotionEnabled.current) Motion.enter() else tween(0, easing = Motion.easing)

@Composable
fun exitTween(): TweenSpec<Float> =
    if (LocalMotionEnabled.current) Motion.exit() else tween(0, easing = Motion.easing)

@Composable
fun popTween(): TweenSpec<Float> =
    if (LocalMotionEnabled.current) Motion.pop() else tween(0, easing = Motion.easing)

/** 按压态只走 90ms；关闭动画时直接跳到终值。 */
@Composable
fun pressTween(): TweenSpec<Float> =
    if (LocalMotionEnabled.current) tween(Motion.press, easing = Motion.easing) else tween(0)

/** 高度类变化（撤回、展开引用条）用 240ms，避免内容跳变。 */
@Composable
fun sizeTween(): TweenSpec<IntSize> =
    if (LocalMotionEnabled.current) tween(Motion.slow, easing = Motion.easing) else tween(0)

/** expandVertically / shrinkVertically 需要 IntSize 规格。 */
@Composable
fun enterSizeTween(): TweenSpec<IntSize> =
    if (LocalMotionEnabled.current) tween(Motion.base, easing = Motion.easing) else tween(0)

@Composable
fun exitSizeTween(): TweenSpec<IntSize> =
    if (LocalMotionEnabled.current) tween(Motion.fast, easing = Motion.easing) else tween(0)

/** slideIn/OutVertically 需要 IntOffset 规格。 */
@Composable
fun slideTween(): TweenSpec<IntOffset> =
    if (LocalMotionEnabled.current) tween(Motion.base, easing = Motion.easing) else tween(0)

@Composable
fun exitSlideTween(): TweenSpec<IntOffset> =
    if (LocalMotionEnabled.current) tween(Motion.fast, easing = Motion.easing) else tween(0)

/** 需要等动画结束再做事时，用这个把等待也一起静音。 */
@Composable
fun motionMillis(ms: Int): Long = if (LocalMotionEnabled.current) ms.toLong() else 0L

/** 读系统动画缩放；用户在开发者选项或无障碍里关掉动画时立即生效。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): Boolean {
        val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        return animator == 0f || transition == 0f
    }
    var reduce by remember { mutableStateOf(read()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer,
        )
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduce = read()
        }
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return reduce
}

/**
 * 亮色是紫雾白：`#F7F4FA` 底 + 近白卡 + 加深柔淡紫主色 `#6E4D8F`，圆角 20/14/10。
 * 暗色是紫夜：冷紫黑底 + 浅丁香主色 `#C7A6E3`，文字浅紫白。
 * 颜色与字号全部取自 DesignTokens（唯一来源），这里只做 Material 映射，不改业务逻辑。
 */
@Composable
fun LilyTheme(mode: dev.lily.app.appearance.ThemeMode = dev.lily.app.appearance.ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        dev.lily.app.appearance.ThemeMode.LIGHT -> false
        dev.lily.app.appearance.ThemeMode.DARK -> true
        dev.lily.app.appearance.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val t = if (dark) DesignTokens.dark else DesignTokens.light
    val scheme = if (dark) darkColorScheme(
        primary = t.accent, onPrimary = t.accentInk,
        primaryContainer = t.accentSoft, onPrimaryContainer = t.accentOnSoft,
        secondaryContainer = t.accentSoft, onSecondaryContainer = t.accentOnSoft,
        tertiary = t.mood,
        background = t.bg, surface = t.surface,
        surfaceContainerLow = t.surfaceLow, surfaceVariant = t.surface2,
        onSurface = t.ink, onSurfaceVariant = t.ink2,
        outline = t.line, outlineVariant = t.line, error = t.danger,
        errorContainer = t.errorContainer, onErrorContainer = t.onErrorContainer,
    ) else lightColorScheme(
        primary = t.accent, onPrimary = t.accentInk,
        primaryContainer = t.accentSoft, onPrimaryContainer = t.accentOnSoft,
        secondaryContainer = t.accentSoft, onSecondaryContainer = t.accentOnSoft,
        tertiary = t.mood,
        background = t.bg, surface = t.surface,
        surfaceContainerLow = t.surfaceLow, surfaceVariant = t.surface2,
        onSurface = t.ink, onSurfaceVariant = t.ink2,
        outline = t.line, outlineVariant = t.line, error = t.danger,
        errorContainer = t.errorContainer, onErrorContainer = t.onErrorContainer,
    )
    val base = Typography()
    val typography = Typography(
            displayLarge = base.displayLarge.copy(fontFamily = LilySans),
            displayMedium = base.displayMedium.copy(fontFamily = LilySans),
            displaySmall = base.displaySmall.copy(fontFamily = LilySans),
            headlineLarge = base.headlineLarge.copy(fontFamily = LilySans),
            headlineMedium = base.headlineMedium.copy(fontFamily = LilySans),
            headlineSmall = TextStyle(fontFamily = LilySans, fontSize = TypeScale.titleLarge.size, lineHeight = TypeScale.titleLarge.line, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontFamily = LilySans, fontSize = TypeScale.titleLarge.size, lineHeight = TypeScale.titleLarge.line, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = LilySans, fontSize = TypeScale.titleMedium.size, lineHeight = TypeScale.body.line),
            bodyMedium = TextStyle(fontFamily = LilySans, fontSize = TypeScale.body.size, lineHeight = TypeScale.body.line),
            bodySmall = TextStyle(fontFamily = LilySans, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.bodySmall.line),
            titleMedium = TextStyle(fontFamily = LilySans, fontSize = TypeScale.titleMedium.size, lineHeight = TypeScale.titleMedium.line, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontFamily = LilySans, fontSize = TypeScale.titleSmall.size, lineHeight = TypeScale.titleSmall.line, fontWeight = FontWeight.SemiBold),
            labelLarge = TextStyle(fontFamily = LilySans, fontSize = TypeScale.bodySmall.size, lineHeight = TypeScale.labelMedium.line, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontFamily = LilySans, fontSize = TypeScale.labelMedium.size, lineHeight = TypeScale.labelMedium.line, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontFamily = LilySans, fontSize = TypeScale.labelSmall.size, lineHeight = TypeScale.labelSmall.line, letterSpacing = 0.2.sp),
    )
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(LocalLilyPalette provides t, LocalMotionEnabled provides !reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** 数字与计时用等宽，避免通话秒数跳动。 */
val MonoDigits = FontFamily.Monospace

/** 文字型操作补到 44dp 最小热区：只扩大可点范围，不改视觉高度。 */
fun Modifier.tapTarget(): Modifier = heightIn(min = SizeTokens.touchTarget)

/**
 * 按压缩放：只跟同一个 interactionSource 的按下态，90ms 沉下去、松手 90ms 回来。
 * 图标 0.90–0.92，整卡 0.98，避免「过重」。
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.92f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = pressTween(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 可点 + 按压缩放，替代裸 clickable：涟漪仍在，多一层轻微下沉。 */
@Composable
fun Modifier.pressClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.92f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = pressTween(),
        label = "pressClick",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 二级页统一入场：180ms 淡入 + 8dp 上移，与底栏、页面转场同一曲线。
 * 返回不做退出动画：返回要跟手，宁可即时。
 */
@Composable
fun SubpageEnter(content: @Composable () -> Unit) {
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    val lift = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(enterTween()) + slideInVertically(slideTween()) { lift },
        exit = fadeOut(exitTween()),
    ) {
        content()
    }
}

/** 全局开关：关闭态也给出可见的轨道与滑块，浅色卡片上不再「消失」。 */
@Composable
fun LilySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val palette = LocalLilyPalette.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = palette.ink2,
            uncheckedTrackColor = palette.surface2,
            uncheckedBorderColor = palette.ink3.copy(alpha = 0.65f),
        ),
    )
}

/** 信息性辅助说明（时间戳、状态句、占位文字）：比 label 大一档，行高更松。 */
val CaptionTextStyle = TextStyle(
    fontFamily = LilySans,
    fontSize = TypeScale.caption.size,
    lineHeight = TypeScale.caption.line,
)

/**
 * 统一的内联错误行：错误留在页面里，用户看得到、也能重试；Toast 只留给纯确认类提示。
 */
@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * 破坏性操作统一二次确认：标题 + 后果说明 + 危险色确认，取消固定用「取消」。
 * 不可恢复的操作要在 [text] 里写清楚「无法恢复」。
 */
@Composable
fun ConfirmDangerDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "删除",
) {
    val palette = LocalLilyPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.ink2) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel, color = palette.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = palette.ink2)
            }
        },
    )
}
