package dev.lily.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 长按发送键时可选的全屏效果。名字短，是因为要出现在一个竖着排的小列表里。 */
enum class ScreenEffect(val label: String, val hint: String) {
    BALLOONS("气球", "生日"),
    CONFETTI("彩纸", "恭喜、新年"),
    HEARTS("心形", "想你了"),
    STARS("星星", "晚安"),
}

private const val EFFECT_MS = 2400f

private data class Particle(
    val x: Float,
    val phase: Float,
    val size: Float,
    val spin: Float,
    val color: Color,
)

/** 一次放完就自己收掉：2.4 秒，不挡输入，也不等点。 */
@Composable
internal fun ScreenEffectLayer(effect: ScreenEffect?, onFinished: () -> Unit) {
    val current = effect ?: return
    // 系统关掉动画（无障碍或开发者选项）时，不放满屏粒子，直接过。
    if (!LocalMotionEnabled.current) {
        LaunchedEffect(current) { onFinished() }
        return
    }
    val palette = LocalLilyPalette.current
    val particles = remember(current) { particlesFor(current, palette) }
    var progress by remember(current) { mutableStateOf(0f) }
    LaunchedEffect(current) {
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val p = ((now - start).toFloat() / EFFECT_MS).coerceIn(0f, 1f)
            progress = p
            if (p >= 1f) break
        }
        onFinished()
    }
    Canvas(Modifier.fillMaxSize().zIndex(1f)) {
        when (current) {
            ScreenEffect.BALLOONS -> drawBalloons(particles, progress)
            ScreenEffect.CONFETTI -> drawConfetti(particles, progress)
            ScreenEffect.HEARTS -> drawHearts(particles, progress)
            ScreenEffect.STARS -> drawStars(particles, progress)
        }
    }
}

private fun particlesFor(effect: ScreenEffect, palette: LilyPalette): List<Particle> {
    val random = Random(effect.ordinal * 97 + 13)
    val colors = listOf(palette.accent, palette.mood, palette.accentSoft, palette.online)
    val count = when (effect) {
        ScreenEffect.BALLOONS -> 11
        ScreenEffect.CONFETTI -> 42
        ScreenEffect.HEARTS -> 11
        ScreenEffect.STARS -> 26
    }
    return List(count) {
        Particle(
            x = random.nextFloat(),
            phase = random.nextFloat() * 6.28f,
            size = 0.6f + random.nextFloat() * 0.8f,
            spin = (random.nextFloat() - 0.5f) * 540f,
            color = colors[random.nextInt(colors.size)],
        )
    }
}

/** 气球：从下往上飘，出了顶就淡掉。上升速度有快有慢，看起来不是一次齐射。 */
private fun DrawScope.drawBalloons(particles: List<Particle>, t: Float) {
    particles.forEachIndexed { index, p ->
        val speed = 1.05f + (index % 3) * 0.18f
        val y = size.height * (1.06f - t * speed * 1.35f) - p.phase * 8f
        val x = size.width * p.x + sin(t * 4.2f + p.phase) * size.width * 0.035f
        val radius = size.width * 0.042f * p.size
        val alpha = (1f - ((t - 0.78f) / 0.22f).coerceIn(0f, 1f))
        if (y < -radius * 3f || alpha <= 0f) return@forEachIndexed
        val color = p.color.copy(alpha = alpha * 0.92f)
        drawOval(
            color = color,
            topLeft = Offset(x - radius, y - radius * 1.22f),
            size = Size(radius * 2f, radius * 2.44f),
        )
        // 高光让气球不像一个纯色块。
        drawOval(
            color = Color.White.copy(alpha = alpha * 0.30f),
            topLeft = Offset(x - radius * 0.55f, y - radius * 0.85f),
            size = Size(radius * 0.62f, radius * 0.86f),
        )
        drawLine(
            color = Color.White.copy(alpha = alpha * 0.36f),
            start = Offset(x, y + radius * 1.22f),
            end = Offset(x + sin(t * 5f + p.phase) * radius * 0.5f, y + radius * 2.4f),
            strokeWidth = 1.6f,
        )
    }
}

/** 彩纸：从顶上落下来，边落边转。落到地面前就收干净。 */
private fun DrawScope.drawConfetti(particles: List<Particle>, t: Float) {
    particles.forEach { p ->
        val y = size.height * (-0.08f + t * 1.18f) + ((p.phase / 6.28f) * size.height) * 0.1f
        val x = size.width * p.x + sin(t * 6.4f + p.phase) * size.width * 0.045f
        val w = size.width * 0.012f * p.size
        val alpha = (1f - ((t - 0.82f) / 0.18f).coerceIn(0f, 1f))
        if (alpha <= 0f) return@forEach
        rotate(degrees = p.spin * t, pivot = Offset(x, y)) {
            drawRect(
                color = p.color.copy(alpha = alpha * 0.9f),
                topLeft = Offset(x - w, y - w * 0.6f),
                size = Size(w * 2f, w * 1.2f),
            )
        }
    }
}

/** 心形：从两侧往中间升，越往上越淡，不铺满整个屏幕。 */
private fun DrawScope.drawHearts(particles: List<Particle>, t: Float) {
    particles.forEachIndexed { index, p ->
        val fromLeft = index % 2 == 0
        val startX = if (fromLeft) -size.width * 0.1f else size.width * 1.1f
        val targetX = size.width * (0.32f + p.x * 0.36f)
        val x = startX + (targetX - startX) * (t * 1.1f)
        val y = size.height * (0.9f - t * 0.75f) - p.phase * 6f
        val s = size.width * 0.02f * p.size
        val alpha = (1f - ((t - 0.72f) / 0.28f).coerceIn(0f, 1f))
        if (alpha <= 0f) return@forEachIndexed
        val path = Path().apply {
            moveTo(x, y + s)
            cubicTo(x - s * 1.35f, y - s * 0.15f, x - s * 0.72f, y - s * 1.25f, x, y - s * 0.4f)
            cubicTo(x + s * 0.72f, y - s * 1.25f, x + s * 1.35f, y - s * 0.15f, x, y + s)
            close()
        }
        drawPath(path, color = p.color.copy(alpha = alpha * 0.85f))
    }
}

/** 星星：原地闪，不移动。最安静的一个。 */
private fun DrawScope.drawStars(particles: List<Particle>, t: Float) {
    particles.forEach { p ->
        val x = size.width * (0.08f + p.x * 0.84f)
        val y = size.height * (0.06f + (p.phase / 6.28f) * 0.7f)
        val r = size.width * 0.008f * p.size
        val twinkle = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 12f + p.phase)))
        val alpha = twinkle * (1f - ((t - 0.8f) / 0.2f).coerceIn(0f, 1f))
        if (alpha <= 0f) return@forEach
        val path = Path().apply {
            moveTo(x, y - r * 1.8f)
            lineTo(x + r * 0.55f, y - r * 0.55f)
            lineTo(x + r * 1.8f, y)
            lineTo(x + r * 0.55f, y + r * 0.55f)
            lineTo(x, y + r * 1.8f)
            lineTo(x - r * 0.55f, y + r * 0.55f)
            lineTo(x - r * 1.8f, y)
            lineTo(x - r * 0.55f, y - r * 0.55f)
            close()
        }
        drawPath(path, color = Color.White.copy(alpha = alpha * 0.9f))
        drawCircle(color = p.color.copy(alpha = alpha * 0.5f), radius = r * 2.6f, center = Offset(x, y))
    }
}
