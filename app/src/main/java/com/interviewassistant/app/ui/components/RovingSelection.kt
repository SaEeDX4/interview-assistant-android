package com.interviewassistant.app.ui.components

/** The arrow keys a roving group handles (the C#'s `RovingRadios` PreviewKeyDown switch). */
enum class RovingKey { Up, Down, Left, Right }

/**
 * Mirror of `InterviewAssistant.App/Views/RovingRadios.cs`: one Tab stop for a group of radio choices; the
 * arrow keys move the selection (and focus) like a native radio group, mirrored in right-to-left layouts
 * (Left/Right swap in RTL; Up/Down do not). Used by the export choices and the Interview Focus presets.
 */
object RovingSelection {
    /** +1 / −1 for an arrow key, 0 for none. Left/Right are mirrored when [rtl]. */
    fun step(key: RovingKey, rtl: Boolean): Int {
        var step = when (key) {
            RovingKey.Down, RovingKey.Right -> 1
            RovingKey.Up, RovingKey.Left -> -1
        }
        if (rtl && (key == RovingKey.Left || key == RovingKey.Right)) step = -step
        return step
    }

    /** The index an arrow key moves to from [current] in a group of [count] (wraps; empty group → −1). */
    fun next(current: Int, count: Int, key: RovingKey, rtl: Boolean): Int {
        if (count == 0) return -1
        val i = maxOf(0, current)
        return (i + step(key, rtl) + count) % count
    }
}
