package com.interviewassistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors

/**
 * `Card` (Themes/Theme.xaml): Surface fill, Border outline, card radius 12, card padding 18×14.
 * [elevated] adds the `E.Shadow` approximation (dialogs, popovers, toasts).
 */
@Composable
fun IaCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalIaColors.current
    val shape = RoundedCornerShape(IaTokens.RadiusCard)
    Box(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(IaTokens.ShadowElevation, shape) else Modifier)
            .clip(shape)
            .background(c.Surface)
            .border(IaTokens.BorderWidth, c.Border, shape)
            .padding(horizontal = IaTokens.CardPaddingHorizontal, vertical = IaTokens.CardPaddingVertical),
        content = content,
    )
}

/** `Divider`: a 1 dp Border line with the divider margin (0/8/0/16) unless the caller overrides it. */
@Composable
fun IaDivider(modifier: Modifier = Modifier, top: Dp = IaTokens.DividerMarginTop, bottom: Dp = IaTokens.DividerMarginBottom) {
    val c = LocalIaColors.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = top, bottom = bottom)
            .height(IaTokens.BorderWidth)
            .background(c.Border),
    )
}

/**
 * `EmptyState.Icon`: the 56 dp disc (Surface2, radius 28 — a circle, as drawn in the XAML) that carries an
 * empty state's glyph, with the style's 14 dp bottom margin.
 */
@Composable
fun IaEmptyStateIcon(glyph: IaGlyph, modifier: Modifier = Modifier, glyphSize: Dp = 24.dp, description: String? = null) {
    val c = LocalIaColors.current
    Box(
        modifier = modifier
            .padding(bottom = 14.dp)
            .size(IaTokens.EmptyStateIconDisc)
            .clip(CircleShape)
            .background(c.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        IaIcon(glyph, glyphSize, c.TextDim, description)
    }
}
