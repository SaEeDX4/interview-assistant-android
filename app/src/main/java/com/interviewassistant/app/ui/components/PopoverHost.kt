package com.interviewassistant.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Placement of a popover under its anchor (Popover.cs's CustomPopupPlacementCallback): end edges aligned —
 * the card grows into the window from the button (x = anchor right − popup width + 8, y = anchor bottom + 2);
 * when that would leave the window, the fallback aligns the start edges (x = anchor left, same y). Mirrored
 * by the platform in right-to-left layouts, as WPF mirrors it.
 */
class IaPopoverPositionProvider(private val density: Float) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val dx = (8 * density).toInt()
        val dy = (2 * density).toInt()
        val y = anchorBounds.bottom + dy
        val endAligned = anchorBounds.right - popupContentSize.width + dx
        val startAligned = anchorBounds.left
        val x = if (endAligned >= 0 && endAligned + popupContentSize.width <= windowSize.width) endAligned else startAligned
        return IntOffset(x, y)
    }
}

/**
 * An anchored popover (Quick help, More, account menu): renders [anchor], and while [controller] is open a
 * Popup with [content] under it. Focus moves inside on open (to [contentFocusRequester]'s target, or the
 * first focusable — the content's own first focusable takes it via its modifier), Escape closes and returns
 * focus to the anchor, an outside tap closes without restoring focus — all decided by [controller]
 * (Popover.cs's rules). The card chrome is the content's own (the C#'s popup children are styled cards).
 */
@Composable
fun IaPopover(
    controller: PopoverController,
    modifier: Modifier = Modifier,
    anchorFocusRequester: FocusRequester? = null,
    contentFocusRequester: FocusRequester? = null,
    anchor: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    // focus returns to the anchor whenever a close asked for it
    LaunchedEffect(controller.focusRestoreRequests) {
        if (controller.focusRestoreRequests > 0) anchorFocusRequester?.requestFocus()
    }
    LaunchedEffect(controller.isOpen) {
        if (controller.isOpen) contentFocusRequester?.requestFocus()
    }
    Box(modifier) {
        anchor()
        if (controller.isOpen) {
            Popup(
                popupPositionProvider = IaPopoverPositionProvider(density),
                onDismissRequest = { controller.onWindowTap(PopoverTapTarget.Elsewhere) },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier
                        .then(if (contentFocusRequester != null) Modifier.focusRequester(contentFocusRequester) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                controller.onEscape()
                            } else false
                        },
                ) {
                    content()
                }
            }
        }
    }
}
