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
    val ButtonHeight: Dp = 36.dp
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
    val SplitMaxWidth: Dp = 940.dp
    val FormMaxWidth: Dp = 620.dp
    val ShortFieldWidth: Dp = 320.dp
    val SidePaneWidth: Dp = 240.dp
    val PaneGap: Dp = 20.dp
    val PaneMinWidth: Dp = 260.dp
    val ColumnGap: Dp = 12.dp
    val LiveReadableColumnMax: Dp = 980.dp

    // Paddings (§3.1 / audit-app §1.3, horizontal × vertical)
    val InputPaddingH: Dp = 10.dp
    val InputPaddingV: Dp = 6.dp
    val MultilinePaddingH: Dp = 10.dp
    val MultilinePaddingV: Dp = 8.dp
    val ButtonPaddingH: Dp = 14.dp
    val ButtonPaddingV: Dp = 6.dp
    val ButtonBasePaddingH: Dp = 12.dp
    val ButtonBasePaddingV: Dp = 6.dp
    val GhostPaddingH: Dp = 8.dp
    val GhostPaddingV: Dp = 6.dp
    val LinkPaddingH: Dp = 6.dp
    val LinkPaddingV: Dp = 2.dp
    val ChipPaddingH: Dp = 11.dp
    val ChipPaddingV: Dp = 5.dp
    val FormButtonPaddingH: Dp = 16.dp
    val FormButtonPaddingV: Dp = 6.dp
    val ListItemPaddingH: Dp = 10.dp
    val ListItemPaddingV: Dp = 7.dp
    val ChoiceCardPaddingH: Dp = 12.dp
    val ChoiceCardPaddingV: Dp = 10.dp
    val SegTrackPadding: Dp = 3.dp
    val SegItemPaddingH: Dp = 12.dp
    val SegItemPaddingV: Dp = 4.dp

    // Margins / gaps (§3.1 / audit-app §1.3)
    val PageMarginStart: Dp = 8.dp
    val PageMarginTop: Dp = 0.dp
    val PageMarginEnd: Dp = 16.dp
    val PageMarginBottom: Dp = 8.dp
    val SectionMarginTop: Dp = 24.dp
    val SectionMarginBottom: Dp = 8.dp
    val FieldLabelMarginTop: Dp = 12.dp
    val FieldLabelMarginBottom: Dp = 6.dp
    val HintMarginTop: Dp = 4.dp
    val ButtonRowMarginTop: Dp = 14.dp
    val ButtonGap: Dp = 8.dp
    val StackGap: Dp = 8.dp
    val DividerMarginTop: Dp = 8.dp
    val DividerMarginBottom: Dp = 16.dp
    val FieldGap: Dp = 4.dp

    // Focus ring (§3.1): 2 dp ring; buttons draw it 2 dp outside, inputs/combos 3 dp outside.
    val FocusRingWidth: Dp = 2.dp
    val FocusRingOutsetButton: Dp = 2.dp
    val FocusRingOutsetInput: Dp = 3.dp

    // Shadow (§3.1 `E.Shadow`: blur 24, depth 4, direction 270°, opacity 0.35 black).
    // Compose has no direct equivalent; components approximate it with elevation (P4-D DC-130).
    val ShadowBlur: Dp = 24.dp
    val ShadowDepth: Dp = 4.dp
    val ShadowOpacity: Float = 0.35f
    val ShadowElevation: Dp = 8.dp

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
