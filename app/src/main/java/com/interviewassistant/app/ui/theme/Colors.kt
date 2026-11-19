package com.interviewassistant.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The active colour set of the theme: the §3.1 palette ([Normal], values verbatim in [IaTokens]) or the
 * high-contrast set ([HighContrast]). Mirrors the Windows behaviour where setting `HighContrast.IsOn` swaps
 * every themed brush for the system colours in the same states (Themes/Theme.xaml DataTriggers): surfaces and
 * text become the system window colours, selection/focus become the system highlight. On Android there is no
 * single system palette to bind to, so the platform high-contrast palette is approximated (P4-D DC-131):
 * black surfaces, white text/borders, yellow highlight.
 */
data class IaColorSet(
    val Bg: Color,
    val Surface: Color,
    val Surface2: Color,
    val Surface3: Color,
    val Border: Color,
    val Text: Color,
    val TextDim: Color,
    val TextFaint: Color,
    val Accent: Color,
    val AccentSoft: Color,
    val AccentStrong: Color,
    val OnAccentStrong: Color,
    val InputBorder: Color,
    val Placeholder: Color,
    val Listening: Color,
    val Thinking: Color,
    val ThinkingSoft: Color,
    val Error: Color,
    val ErrorSoft: Color,
    val ErrorStrong: Color,
    val OnErrorStrong: Color,
    val Backdrop: Color,
) {
    companion object {
        /** The §3.1 palette, value for value (white text on the two filled strong surfaces, as in the XAML). */
        val Normal: IaColorSet = IaColorSet(
            Bg = IaTokens.Bg,
            Surface = IaTokens.Surface,
            Surface2 = IaTokens.Surface2,
            Surface3 = IaTokens.Surface3,
            Border = IaTokens.Border,
            Text = IaTokens.Text,
            TextDim = IaTokens.TextDim,
            TextFaint = IaTokens.TextFaint,
            Accent = IaTokens.Accent,
            AccentSoft = IaTokens.AccentSoft,
            AccentStrong = IaTokens.AccentStrong,
            OnAccentStrong = Color.White,
            InputBorder = IaTokens.InputBorder,
            Placeholder = IaTokens.Placeholder,
            Listening = IaTokens.Listening,
            Thinking = IaTokens.Thinking,
            ThinkingSoft = IaTokens.ThinkingSoft,
            Error = IaTokens.Error,
            ErrorSoft = IaTokens.ErrorSoft,
            ErrorStrong = IaTokens.ErrorStrong,
            OnErrorStrong = Color.White,
            Backdrop = IaTokens.Backdrop,
        )

        /** Platform high-contrast approximation of the Windows system-colour swap (P4-D DC-131). */
        val HighContrast: IaColorSet = IaColorSet(
            Bg = Color(0xFF000000),
            Surface = Color(0xFF000000),
            Surface2 = Color(0xFF000000),
            Surface3 = Color(0xFF000000),
            Border = Color(0xFFFFFFFF),
            Text = Color(0xFFFFFFFF),
            TextDim = Color(0xFFFFFFFF),
            TextFaint = Color(0xFFC8C8C8),
            Accent = Color(0xFFFFFF00),
            AccentSoft = Color(0x40FFFF00),
            AccentStrong = Color(0xFF0078D7),
            OnAccentStrong = Color(0xFFFFFFFF),
            InputBorder = Color(0xFFFFFFFF),
            Placeholder = Color(0xFFC8C8C8),
            Listening = IaTokens.Listening,
            Thinking = IaTokens.Thinking,
            ThinkingSoft = IaTokens.ThinkingSoft,
            Error = IaTokens.Error,
            ErrorSoft = IaTokens.ErrorSoft,
            ErrorStrong = IaTokens.ErrorStrong,
            OnErrorStrong = Color.White,
            Backdrop = IaTokens.Backdrop,
        )
    }
}

/** The colour set in effect for the current composition (set by [InterviewAssistantTheme]). */
val LocalIaColors = compositionLocalOf { IaColorSet.Normal }
