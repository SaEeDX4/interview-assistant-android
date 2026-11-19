package com.interviewassistant.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.interviewassistant.app.ui.theme.IaTokens

/**
 * The Windows focus ring (Themes/Theme.xaml): a 2 dp accent border drawn OUTSIDE the control, so it never
 * covers content and never changes layout (negative margins in WPF). Buttons draw it 2 dp outside with the
 * control radius (8); inputs and combos draw it 3 dp outside with the focus-ring radius (11) in AccentSoft
 * (ErrorSoft in the error state). [visible] is the keyboard-focus state of the control.
 */
fun Modifier.iaFocusRing(visible: Boolean, color: Color, radius: Dp, outset: Dp): Modifier =
    if (!visible) this
    else drawWithContent {
        drawContent()
        val o = outset.toPx()
        val w = IaTokens.FocusRingWidth.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(-o, -o),
            size = Size(size.width + 2 * o, size.height + 2 * o),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = w),
        )
    }

/** The button ring: radius = control radius, outset 2 (the `Focus` border of the Btn.Base template). */
fun Modifier.iaButtonFocusRing(visible: Boolean, color: Color): Modifier =
    iaFocusRing(visible, color, IaTokens.RadiusControl, IaTokens.FocusRingOutsetButton)

/** The input/combo ring: radius = focus-ring radius, outset 3 (the `Ring` border of the Input template). */
fun Modifier.iaInputFocusRing(visible: Boolean, color: Color): Modifier =
    iaFocusRing(visible, color, IaTokens.RadiusFocusRing, IaTokens.FocusRingOutsetInput)
