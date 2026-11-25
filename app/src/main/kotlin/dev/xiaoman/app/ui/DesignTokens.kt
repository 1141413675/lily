package dev.xiaoman.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token 的唯一来源，命名与设计规范一一对应：
 * `--bg / --surface / --surface2 / --ink / --ink2 / --ink3 / --line / --line2 /
 *  --accent / --accent-ink / --accent-soft / --bubble-user / --bubble-agent /
 *  --online / --danger / --mood`。
 *
 * 主题「淡紫柔雾百合」：亮色是紫雾白，暗色是紫夜；两套色板各自取值，不做反相。
 * 正文统一避开纯黑，卡片不再靠描边分层，改用柔光与弱阴影。
 */
data class XiaomanPalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surfaceLow: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val accentOnSoft: Color,
    val neutralSoft: Color,
    val neutralOnSoft: Color,
    val bubbleUser: Color,
    val bubbleUserInk: Color,
    /** 透明表示不要气泡底色（A 稿的她的消息直接落在页面上）。 */
    val bubbleAgent: Color,
    val bubbleAgentInk: Color,
    val online: Color,
    val danger: Color,
    /** 错误提示卡专用：底色与正文成对给出，暗色下也不会出现深底深字。 */
    val errorContainer: Color,
    val onErrorContainer: Color,
    val mood: Color,
    /** 通话页专用：接听/挂断都比 accent、danger 更钝，深底上不刺眼。 */
    val callAccept: Color,
    val callAcceptInk: Color,
    val callHangup: Color,
    val callHangupInk: Color,
)

data class TypeToken(val size: TextUnit, val line: TextUnit)

object DesignTokens {
    val light = XiaomanPalette(
        // 极浅紫雾白：比纯白低一档亮度，带一点冷紫，不再用暖纸色。
        bg = Color(0xFFF7F4FA),
        surface = Color(0xFFFEFCFF),
        surface2 = Color(0xFFEFE9F6),
        surfaceLow = Color(0xFFF3EEF9),
        // 深灰微紫，避免纯黑在紫底上发死。
        ink = Color(0xFF2B2733),
        ink2 = Color(0xFF57515F),
        // 功能性小字（撤回提示、时间、来源标签）至少 4.5:1。
        ink3 = Color(0xFF736C7C),
        line = Color(0xFFE7E1F0),
        line2 = Color(0xFFD5CCE2),
        // 加深版柔淡紫：白字 6.7:1，对紫雾白底 6.0:1。
        accent = Color(0xFF6E4D8F),
        accentInk = Color(0xFFFFFFFF),
        accentSoft = Color(0xFFEFE6F8),
        accentOnSoft = Color(0xFF553976),
        neutralSoft = Color(0xFFEFE9F6),
        neutralOnSoft = Color(0xFF57515F),
        // 我的气泡偏紫，她的气泡近白，靠明度与色相柔和区分，不再描边。
        bubbleUser = Color(0xFFE3D8F3),
        bubbleUserInk = Color(0xFF2B2733),
        bubbleAgent = Color(0xFFFBF8FF),
        bubbleAgentInk = Color(0xFF2B2733),
        // 在线点用柔紫粉，跟主色同族，不再用绿色破坏紫调。
        online = Color(0xFF5F8C6E),
        danger = Color(0xFFA64B58),
        errorContainer = Color(0xFFF8E9EE),
        onErrorContainer = Color(0xFF7A2735),
        mood = Color(0xFF8A6A3C),
        callAccept = Color(0xFF7A5A93),
        callAcceptInk = Color(0xFFFFFFFF),
        callHangup = Color(0xFF9C5A66),
        callHangupInk = Color(0xFFFFFFFF),
    )

    /** 暗色是紫夜：底色压得更深、卡片再提一档，靠明度差把边界立住。 */
    val dark = XiaomanPalette(
        bg = Color(0xFF0E0C12),
        surface = Color(0xFF211B29),
        surface2 = Color(0xFF2C2436),
        surfaceLow = Color(0xFF272031),
        ink = Color(0xFFEFEAF5),
        ink2 = Color(0xFFB7AFC4),
        ink3 = Color(0xFF9A93A8),
        line = Color(0xFF352C41),
        line2 = Color(0xFF463A55),
        // 暗色主色是夜色里的浅丁香：对紫夜底 12:1，深字在其上 9:1。
        accent = Color(0xFFC7A6E3),
        accentInk = Color(0xFF241030),
        accentSoft = Color(0xFF33243F),
        accentOnSoft = Color(0xFFE2C6F4),
        neutralSoft = Color(0xFF2C2436),
        neutralOnSoft = Color(0xFFB7AFC4),
        bubbleUser = Color(0xFF32233F),
        bubbleUserInk = Color(0xFFEFEAF5),
        bubbleAgent = Color(0xFF241D2D),
        bubbleAgentInk = Color(0xFFF1ECF7),
        // 柔粉紫，和主色同族，暗底上仍然亮得起来。
        online = Color(0xFF7FA88C),
        danger = Color(0xFFE08F9C),
        errorContainer = Color(0xFF3A2130),
        onErrorContainer = Color(0xFFF2C6D2),
        mood = Color(0xFFD8B078),
        callAccept = Color(0xFFB49BC9),
        callAcceptInk = Color(0xFF241030),
        callHangup = Color(0xFFBC8390),
        callHangupInk = Color(0xFF2B141B),
    )

}

/** 阴影只有两档：卡片用极弱的柔光，浮层（底栏、回到底部）稍高一点。 */
object Elevation {
    val card = 2.dp
    val floating = 3.dp
}

/** 字号固定档：28 / 22 / 17 / 15 / 13 / 11.5 / 10.5，行高成对给出。 */
object TypeScale {
    val display = TypeToken(28.sp, 36.sp)
    val title = TypeToken(22.sp, 30.sp)
    val headline = TypeToken(17.sp, 26.sp)
    val body = TypeToken(15.sp, 24.sp)
    val bodySmall = TypeToken(13.sp, 20.sp)
    val label = TypeToken(11.5.sp, 16.sp)
    val micro = TypeToken(10.5.sp, 15.sp)

    // 兼容旧名：页面迁移期间先按新档位取值，后续批次再逐个换成语义名。
    val titleLarge = title
    val titleMedium = body
    val titleSmall = bodySmall
    val labelMedium = label
    val labelSmall = micro
}

/** 间距刻度：4/8/10/12/16，外加页面 20 与块间距 28。 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 10.dp
    val lg = 12.dp
    val xl = 16.dp
    val page = 20.dp
    val block = 28.dp
    /** 多图拼接的细缝；图片统一裁切后不再按原图尺寸让步。 */
    val imageGap = 2.dp
}

/** 圆角：与设计稿 --r-lg 20 / --r-md 14 / --r-sm 10 对齐；另有弹层 28、气泡 18、语音气泡 22。 */
object Radius {
    val lg = 20.dp
    val md = 14.dp
    val sm = 10.dp
    val bubble = 18.dp
    val sheet = 28.dp
    val bubbleTail = 6.dp
    /** 语音气泡比文字气泡更圆，和波形读秒的柔软感对齐。 */
    val voiceBubble = 22.dp
    val voiceTail = 8.dp
    val pill = 999.dp
}

/** 常用尺寸：头像、气泡最大宽度、导航与输入区高度。 */
object SizeTokens {
    val avatarHeader = 28.dp
    val avatarFeed = 26.dp
    val avatarEntry = 40.dp
    val navHeight = 54.dp
    /** 所有可点元素的最小热区，视觉图标仍按 IconSize 取 18–20dp。 */
    val touchTarget = 44.dp
    /** 气泡最多占内容宽度的 80%，给对侧和留白留出呼吸。 */
    const val bubbleMaxWidth = 0.80f
    /** 动态图片统一 4:3 居中裁切；长图、竖图不再把一条动态撑高。 */
    const val feedImageAspect = 4f / 3f
}

/** 动效只有三档时长和一条曲线；另有按压 90ms、环境动画与环境光呼吸的节拍。 */
object Motion {
    const val fast = 140
    const val base = 180
    const val slow = 240
    /** 按压态，比 fast 更快。 */
    const val press = 90
    /** 环境动画周期（呼吸、渐变）。 */
    const val ambient = 4_000
    /** 循环小动画（在线点、播放波形）的节拍。 */
    const val loop = 1_200

    val easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter(): TweenSpec<T> = tween(base, easing = easing)
    fun <T> exit(): TweenSpec<T> = tween(fast, easing = easing)
    fun <T> pop(): TweenSpec<T> = tween(slow, easing = easing)
}

/** 图标尺寸统一四档，新页面不再随手写 dp。 */
object IconSize {
    val sm = 16.dp
    val md = 18.dp
    val lg = 20.dp
    /** 底栏图标。 */
    val nav = 24.dp
    val xl = 24.dp
}
