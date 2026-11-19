package com.interviewassistant.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max

/** Thumb placement of the slim scrollbar, as fractions of the track (pure geometry, unit-tested). */
data class ScrollbarThumb(val topFraction: Float, val heightFraction: Float)

/**
 * The slim scrollbar of Themes/Theme.xaml (width 6, thumb Surface3, corner radius 3, no track buttons):
 * the thumb covers the visible fraction of the content and travels the remaining track as the scroll
 * position goes 0 → max. Hidden when everything fits (the WPF bar disables itself; here nothing is drawn).
 */
object ScrollbarGeometry {
    /** [viewport] and [content] in the same unit; [scroll] in 0..(content−viewport). */
    fun thumb(viewport: Float, content: Float, scroll: Float): ScrollbarThumb? {
        if (viewport <= 0f || content <= viewport) return null
        val height = (viewport / content).coerceIn(0f, 1f)
        val maxScroll = max(0f, content - viewport)
        val t = if (maxScroll == 0f) 0f else (scroll / maxScroll).coerceIn(0f, 1f)
        return ScrollbarThumb(topFraction = t * (1f - height), heightFraction = height)
    }

    /** From a Compose [ScrollState]: value/maxValue give the scroll fraction; the visible fraction is derived. */
    fun thumb(state: ScrollState, viewportPx: Float): ScrollbarThumb? {
        if (state.maxValue <= 0 || viewportPx <= 0f) return null
        val content = viewportPx + state.maxValue
        return thumb(viewportPx, content, state.value.toFloat())
    }
}

/**
 * Draws the slim scrollbar over a vertically scrolling Column (the Compose equivalent of the XAML ScrollBar
 * style). The thumb sits at the end edge (right in LTR, left in RTL, like the WPF bar in a mirrored layout).
 */
fun Modifier.slimVerticalScrollbar(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val thumb = ScrollbarGeometry.thumb(state, size.height) ?: return@drawWithContent
    val width = 6.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - width else 0f
    drawRoundRect(
        color = color,
        topLeft = Offset(x, thumb.topFraction * size.height),
        size = Size(width, thumb.heightFraction * size.height),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
}
