package dev.xiaoman.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 品牌 Lily 字标：淡紫圆角底 + 白色手写感「lily」。
 * 四个字母全部用 Path 曲线画出来，不依赖任何系统手写字体，
 * 因此在所有设备上字形一致；只作为小标识点缀，不进入正文层级。
 */
@Composable
fun LilyMark(modifier: Modifier = Modifier) {
    val palette = LocalXiaomanPalette.current
    // 亮色用品牌紫做底；暗色的紫本身很浅，改用深紫底，保证白色笔画始终清晰。
    val light = palette.bg.luminance() >= 0.4f
    val base = if (light) {
        Brush.linearGradient(listOf(palette.accent, palette.accentOnSoft))
    } else {
        Brush.linearGradient(listOf(palette.accentSoft, palette.line2))
    }
    Canvas(modifier.size(width = 36.dp, height = 18.dp)) {
        val w = size.width
        val h = size.height
        val radius = h * 0.34f
        drawRoundRect(brush = base, cornerRadius = CornerRadius(radius, radius))

        val ink = Color.White.copy(alpha = 0.96f)
        val pen = Stroke(width = h * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // l
        drawPath(
            Path().apply {
                moveTo(w * 0.18f, h * 0.22f)
                cubicTo(w * 0.13f, h * 0.44f, w * 0.13f, h * 0.68f, w * 0.18f, h * 0.76f)
                cubicTo(w * 0.23f, h * 0.82f, w * 0.27f, h * 0.66f, w * 0.24f, h * 0.50f)
            },
            color = ink,
            style = pen,
        )
        // i：短竖 + 上方一点
        drawPath(
            Path().apply {
                moveTo(w * 0.31f, h * 0.52f)
                lineTo(w * 0.31f, h * 0.76f)
            },
            color = ink,
            style = pen,
        )
        drawCircle(ink, radius = h * 0.048f, center = Offset(w * 0.31f, h * 0.34f))
        // l
        drawPath(
            Path().apply {
                moveTo(w * 0.44f, h * 0.22f)
                cubicTo(w * 0.39f, h * 0.44f, w * 0.39f, h * 0.68f, w * 0.44f, h * 0.76f)
                cubicTo(w * 0.49f, h * 0.82f, w * 0.53f, h * 0.66f, w * 0.50f, h * 0.50f)
            },
            color = ink,
            style = pen,
        )
        // y：两笔交汇后向下带一个尾
        drawPath(
            Path().apply {
                moveTo(w * 0.60f, h * 0.54f)
                cubicTo(w * 0.60f, h * 0.68f, w * 0.63f, h * 0.74f, w * 0.67f, h * 0.75f)
            },
            color = ink,
            style = pen,
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.80f, h * 0.50f)
                cubicTo(w * 0.77f, h * 0.66f, w * 0.71f, h * 0.84f, w * 0.62f, h * 0.88f)
            },
            color = ink,
            style = pen,
        )
    }
}
