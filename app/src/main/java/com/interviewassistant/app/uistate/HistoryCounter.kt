package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.Loc
import kotlin.math.max
import kotlin.math.min

/** Mirror of `InterviewAssistant.App/UiState/HistoryCounter.cs`. */

/** The 1-based position and the total to show. */
data class HistoryNumbers(val shown: Int, val total: Int)

/**
 * The Live window's "n / total" history counter. Hidden (empty) whenever no history item is on screen: no history
 * at all, nothing selected yet, or a position that no longer exists after the engine was rebuilt (sign-out, account
 * switch, settings change). It can therefore never read "0 / 1" or "0 / 0".
 */
object HistoryCounter {
    /** The 1-based position and the total to show, or null when the counter is hidden. */
    fun numbers(position: Int, count: Int): HistoryNumbers? {
        if (count <= 0 || position < 0 || position >= count) return null
        return HistoryNumbers(position + 1, count)
    }

    /** Localized counter text ("3 / 8"), or "" when hidden. */
    fun text(position: Int, count: Int): String =
        numbers(position, count)?.let { n -> Loc.f("history_label", n.shown, n.total) } ?: ""
}

/**
 * Arrow-key movement in a horizontal segmented control: Left/Right move to the visually adjacent segment, so in a
 * right-to-left layout (segments mirrored) Left moves forward. Stops at the ends (no wrap-around).
 */
object SegmentedNavigation {
    fun move(current: Int, count: Int, leftKey: Boolean, rightToLeft: Boolean): Int {
        if (count <= 0) return -1
        val forward = leftKey == rightToLeft // LTR: Right = forward; RTL: Left = forward
        return min(max(current + if (forward) 1 else -1, 0), count - 1)
    }
}
