package com.interviewassistant.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Mirror target of `InterviewAssistant.App/App.xaml` + `Themes/Theme.xaml`
 * (TRANSFER-MAP §2 row 1 and §3.1, layer: Platform). Dark-only, exactly like Windows.
 *
 * Shape policy: every corner uses the Windows radii — Card 12 dp, Control 8 dp.
 * Pill/capsule shapes (50% radius) are forbidden by the owner's standing design rule.
 */
private val IaDarkColorScheme = darkColorScheme(
    primary = IaTokens.Accent,
    onPrimary = IaTokens.Text,
    primaryContainer = IaTokens.AccentStrong,
    background = IaTokens.Bg,
    onBackground = IaTokens.Text,
    surface = IaTokens.Surface,
    onSurface = IaTokens.Text,
    surfaceVariant = IaTokens.Surface2,
    onSurfaceVariant = IaTokens.TextDim,
    outline = IaTokens.Border,
    outlineVariant = IaTokens.InputBorder,
    error = IaTokens.Error,
    onError = IaTokens.Text,
    errorContainer = IaTokens.ErrorStrong,
    scrim = IaTokens.Backdrop,
)

private val IaShapes = Shapes(
    extraSmall = RoundedCornerShape(IaTokens.RadiusControl),
    small = RoundedCornerShape(IaTokens.RadiusControl),
    medium = RoundedCornerShape(IaTokens.RadiusCard),
    large = RoundedCornerShape(IaTokens.RadiusCard),
    extraLarge = RoundedCornerShape(IaTokens.RadiusCard),
)

@Composable
fun InterviewAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IaDarkColorScheme,
        shapes = IaShapes,
        content = content,
    )
}
