package com.interviewassistant.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens transcribed VERBATIM from TRANSFER-MAP §3.1
 * (source: Windows `Themes/Theme.xaml`, audit-app §1). Dark-only theme.
 *
 * WPF ARGB hex -> Compose [Color]; WPF device-independent px -> dp; font sizes -> sp
 * at the same numeric value. Do not "tune" any value here — a change is a Lockstep
 * divergence and needs a TRANSFER-MAP §8 entry.
 */
object IaTokens {
    // Colours (§3.1 table, ARGB)
    val Bg: Color = Color(0xFF0F1115)
    val Surface: Color = Color(0xFF161920)
    val Surface2: Color = Color(0xFF1D212A)
    val Surface3: Color = Color(0xFF252A35)
    val Border: Color = Color(0xFF2A303C)
    val Text: Color = Color(0xFFECEEF2)
    val TextDim: Color = Color(0xFFA3A9B5)
    val TextFaint: Color = Color(0xFF8B92A0)
    val Accent: Color = Color(0xFF5B8DEF)
    val AccentSoft: Color = Color(0x335B8DEF)
    val AccentStrong: Color = Color(0xFF3567D0)
    val InputBorder: Color = Color(0xFF5C6577)
    val Placeholder: Color = Color(0xFF8F96A4)
    val Listening: Color = Color(0xFF3DD68C)
    val Thinking: Color = Color(0xFFF2B44C)
    val ThinkingSoft: Color = Color(0x1FF2B44C)
    val Error: Color = Color(0xFFEF6B6B)
    val ErrorSoft: Color = Color(0x40EF6B6B)
    val ErrorStrong: Color = Color(0xFFB3373A)
    val Backdrop: Color = Color(0xB3080A0E)

    // Radii (§3.1) — NO pill/capsule shapes anywhere (owner rule, map §0.3 rule 6).
    val RadiusCard: Dp = 12.dp
    val RadiusControl: Dp = 8.dp
    val RadiusFocusRing: Dp = 11.dp
    val RadiusReadinessBadge: Dp = 10.dp
    val EmptyStateIconDisc: Dp = 56.dp // 28 radius disc

    // Sizes (§3.1)
    val ControlHeight: Dp = 36.dp
    val CompactButtonHeight: Dp = 30.dp
    val MultilineMinHeight: Dp = 72.dp
    val MultilineTallHeight: Dp = 170.dp
    val InputFontSize: TextUnit = 13.5.sp
    val BorderWidth: Dp = 1.dp
    val CardPaddingHorizontal: Dp = 18.dp
    val CardPaddingVertical: Dp = 14.dp
    val PageMaxWidth: Dp = 820.dp
    val ContentMaxWidth: Dp = 1120.dp
    val ReadingMaxWidth: Dp = 760.dp
    val FormMaxWidth: Dp = 620.dp
    val LiveReadableColumnMax: Dp = 980.dp

    // Answer/question typography (§3.1, from AppSettings.FontPreset; default = Large)
    val AnswerFontCompact: TextUnit = 16.sp
    val AnswerFontNormal: TextUnit = 19.sp
    val AnswerFontLarge: TextUnit = 22.sp
    val AnswerFontExtraLarge: TextUnit = 26.sp
    val QuestionFontCompact: TextUnit = 14.sp
    val QuestionFontNormal: TextUnit = 16.sp
    val QuestionFontLarge: TextUnit = 17.sp
    val QuestionFontExtraLarge: TextUnit = 19.sp
}
