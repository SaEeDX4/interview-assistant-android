package com.interviewassistant.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The closing rules of `Views/Popover.cs`, as a pure controller: a popover anchored under a header button
 * (Quick help, More). Focus moves inside when it opens; Escape closes and returns focus to the button; a
 * click anywhere else in the window, moving or resizing the window, or switching to another app closes it
 * (deactivation only when keyboard focus is NOT inside the popover). [closedListeners] fire after every
 * close, whatever closed it. Focus itself is the host's job: it observes [focusRestoreRequests].
 */
class PopoverController {
    var isOpen: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** Incremented whenever a close asks for focus to return to the anchor (Escape, a close button). */
    var focusRestoreRequests: Int by androidx.compose.runtime.mutableStateOf(0)
        private set

    private val closedListeners = mutableListOf<() -> Unit>()
    fun addClosedListener(listener: () -> Unit) { closedListeners.add(listener) }
    fun removeClosedListener(listener: () -> Unit) { closedListeners.remove(listener) }

    fun open() {
        if (isOpen) return
        isOpen = true
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    /** Closes; [restoreFocus] returns keyboard focus to the anchor button (Escape, a close button). */
    fun close(restoreFocus: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        if (restoreFocus) focusRestoreRequests++
        closedListeners.toList().forEach { it() }
    }

    /** Escape inside the popover or its window: handled, closes with focus restored. */
    fun onEscape(): Boolean {
        if (!isOpen) return false
        close(restoreFocus = true)
        return true
    }

    /** A tap on the anchor toggles by itself; a tap inside the content stays; anything else closes (no focus restore). */
    fun onWindowTap(target: PopoverTapTarget) {
        if (!isOpen) return
        when (target) {
            PopoverTapTarget.Anchor, PopoverTapTarget.Content -> Unit
            PopoverTapTarget.Elsewhere -> close(restoreFocus = false)
        }
    }

    /** The window lost activation: closes only while keyboard focus is outside the popover. */
    fun onWindowDeactivated(keyboardFocusWithin: Boolean) {
        if (isOpen && !keyboardFocusWithin) close(restoreFocus = false)
    }

    /** The window moved, resized or changed state: closes (no focus restore). */
    fun onWindowGeometryChanged() {
        if (isOpen) close(restoreFocus = false)
    }
}

/** Where a window tap landed, relative to a popover (Popover.cs's PreviewMouseDown hit test). */
enum class PopoverTapTarget { Anchor, Content, Elsewhere }
