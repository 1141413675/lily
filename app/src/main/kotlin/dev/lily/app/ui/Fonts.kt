package dev.lily.app.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.lily.app.R

/**
 * 界面统一字体：Noto Sans SC（SIL OFL 1.1，许可文本在 `assets/licenses/noto-sans-sc-OFL.txt`）。
 * 只打包 400 正文与 600 标题两档，行高与字距仍由 [TypeScale] / Theme 控制。
 */
val LilySans = FontFamily(
    Font(R.font.noto_sans_sc_regular, FontWeight.Normal),
    Font(R.font.noto_sans_sc_regular, FontWeight.Medium),
    Font(R.font.noto_sans_sc_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_sc_semibold, FontWeight.Bold),
)
