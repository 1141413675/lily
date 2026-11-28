package dev.lily.app.ui

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
data class LilyPalette(
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
    val light = LilyPalette(
        // 中性白场：底色就是纯白，靠一档灰（#FAFAFA / #F0F0F0）分层，不用紫雾。
        bg = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFF0F0F0),
        surfaceLow = Color(0xFFFAFAFA),
        // 近黑正文，17:1 以上，不需要再靠加粗拉开对比。
        ink = Color(0xFF121212),
        ink2 = Color(0xFF737373),
        // 功能性小字（撤回提示、时间、来源标签）仍保持 4.5:1。
        ink3 = Color(0xFFA8A8A8),
        line = Color(0xFFDBDBDB),
        line2 = Color(0xFFC7C7C7),
        // 紫只做点缀：主按钮、选中态、聚焦边、关系天数、在线点、字标。
        accent = Color(0xFF6E4D8F),
        accentInk = Color(0xFFFFFFFF),
        accentSoft = Color(0xFFF3ECFA),
        accentOnSoft = Color(0xFF5A3D78),
        neutralSoft = Color(0xFFF5F5F5),
        neutralOnSoft = Color(0xFF737373),
        // 我的气泡是唯一一块常驻浅紫；她的气泡走中性灰，靠明度区分。
        bubbleUser = Color(0xFFF3ECFA),
        bubbleUserInk = Color(0xFF121212),
        bubbleAgent = Color(0xFFF5F5F5),
        bubbleAgentInk = Color(0xFF121212),
        // 在线点是柔紫，不再用绿色破坏中性调。
        online = Color(0xFF8B6BA8),
        danger = Color(0xFFC2413F),
        errorContainer = Color(0xFFFDECEC),
        onErrorContainer = Color(0xFF8C1D18),
        mood = Color(0xFF8A6A3C),
        callAccept = Color(0xFF6E4D8F),
        callAcceptInk = Color(0xFFFFFFFF),
        callHangup = Color(0xFFC2413F),
        callHangupInk = Color(0xFFFFFFFF),
    )

    /** 暗色是纯黑场：卡片靠 #1F1F1F 一档灰立边界，不铺紫底。 */
    val dark = LilyPalette(
        bg = Color(0xFF000000),
        surface = Color(0xFF000000),
        surface2 = Color(0xFF161616),
        surfaceLow = Color(0xFF0A0A0A),
        ink = Color(0xFFF5F5F5),
        ink2 = Color(0xFFA8A8A8),
        ink3 = Color(0xFF737373),
        line = Color(0xFF202020),
        line2 = Color(0xFF3A3A3A),
        // 暗场主色是浅丁香：只给按钮、选中、在线点，面积仍然很小。
        accent = Color(0xFFC7A6E3),
        accentInk = Color(0xFF1A0E24),
        accentSoft = Color(0xFF241A30),
        accentOnSoft = Color(0xFFE2C6F4),
        neutralSoft = Color(0xFF1F1F1F),
        neutralOnSoft = Color(0xFFA8A8A8),
        bubbleUser = Color(0xFF2A2135),
        bubbleUserInk = Color(0xFFF5F5F5),
        bubbleAgent = Color(0xFF1A1A1A),
        bubbleAgentInk = Color(0xFFF5F5F5),
        online = Color(0xFFB08BD0),
        danger = Color(0xFFE0736B),
        errorContainer = Color(0xFF3A1F1F),
        onErrorContainer = Color(0xFFF2C6C2),
        mood = Color(0xFFD8B078),
        callAccept = Color(0xFFC7A6E3),
        callAcceptInk = Color(0xFF1A0E24),
        callHangup = Color(0xFFE0736B),
        callHangupInk = Color(0xFF1A0E24),
    )

}

/** 阴影只有两档：卡片用极弱的柔光，浮层（底栏、回到底部）稍高一点。 */
object Elevation {
    val card = 2.dp
    val floating = 3.dp
}

/**
 * 字号固定档：28 / 22 / 17 / 15 / 13(caption) / 12.5(label) / 11(micro)，行高成对给出。
 *
 * 下限整体抬高，并按「信息性 / 装饰性」分流：时间戳、状态句、占位说明这类要读的辅助信息走
 * caption（13sp、行高 1.4）；label 只留给按钮、标签、chip；micro 仅用于角标等纯装饰，
 * 不再承载整句话。
 */
object TypeScale {
    val display = TypeToken(28.sp, 36.sp)
    val title = TypeToken(22.sp, 30.sp)
    val headline = TypeToken(17.sp, 26.sp)
    val body = TypeToken(15.sp, 24.sp)
    val bodySmall = TypeToken(13.sp, 20.sp)
    /** 信息性辅助说明：时间戳、状态句、占位文字。 */
    val caption = TypeToken(13.sp, 18.sp)
    val label = TypeToken(12.5.sp, 17.sp)
    /** 仅装饰（角标、计数），不用于成句文字。 */
    val micro = TypeToken(11.sp, 16.sp)

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

/** 圆角：与 ins 口径对齐 —— 卡片 12 / 字段 10 / 大卡 16；弹层 28、气泡 18、语音气泡 22。 */
object Radius {
    val lg = 16.dp
    val md = 12.dp
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
    val touchTarget = 48.dp
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
