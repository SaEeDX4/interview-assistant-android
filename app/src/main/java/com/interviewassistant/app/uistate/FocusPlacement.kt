package com.interviewassistant.app.uistate

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Mirror of `InterviewAssistant.App/UiState/FocusPlacement.cs`. */

/** Where Interview Focus puts the Live window: close to a webcam at the top of the screen. */
enum class FocusPreset { TopCentre, TopLeft, TopRight }

/** A rectangle in logical pixels of one monitor (left/top may be negative on monitors left of or above the primary). */
data class PlacementRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
    fun contains(r: PlacementRect, tolerance: Double = 0.5): Boolean =
        r.left >= left - tolerance && r.top >= top - tolerance && r.right <= right + tolerance && r.bottom <= bottom + tolerance
}

/**
 * Interview Focus geometry (no WPF types: unit-tested). The window gets a recommended size (~560×360) that is never
 * larger than 40 % of the monitor's work area in either direction — except that it never goes below the window's own
 * minimum size — and is placed at the top edge (centre, left or right), fully inside the work area. Works for any
 * monitor origin (negative coordinates) and any DPI: callers pass the work area in the monitor's logical pixels.
 */
object FocusPlacement {
    const val recommendedWidth: Double = 560.0
    const val recommendedHeight: Double = 360.0
    const val maxShare: Double = 0.40
    /** Gap to the left/right screen edge for the corner presets (the window's own 8 px shadow margin adds to it). */
    const val edgeGap: Double = 8.0

    fun compute(work: PlacementRect, preset: FocusPreset, minWidth: Double = LiveChrome.minWidth, minHeight: Double = 260.0): PlacementRect {
        var width = min(recommendedWidth, work.width * maxShare)
        var height = min(recommendedHeight, work.height * maxShare)
        // the window cannot be smaller than its minimum size, and never larger than the work area itself
        width = min(max(width, minWidth), work.width)
        height = min(max(height, minHeight), work.height)
        var left = when (preset) {
            FocusPreset.TopLeft -> work.left + edgeGap
            FocusPreset.TopRight -> work.right - width - edgeGap
            else -> work.left + (work.width - width) / 2
        }
        left = left.coerceIn(work.left, max(work.left, work.right - width))
        val top = work.top // right under the camera: the top edge of the work area
        // .NET Math.Round(double) rounds midpoints to even — java.lang.Math.rint is the same rule.
        return PlacementRect(Math.rint(left), Math.rint(top), floor(width), floor(height))
    }

    /** Localization key of the preset's label. */
    fun key(p: FocusPreset): String = when (p) {
        FocusPreset.TopLeft -> "focus_top_left"
        FocusPreset.TopRight -> "focus_top_right"
        else -> "focus_top_centre"
    }

    /** The stored preset (settings), Top centre when none or unknown. */
    fun parse(stored: String?): FocusPreset {
        // C# Enum.TryParse(ignoreCase) also accepts a defined numeric value ("1" → TopLeft); undefined numbers fall back.
        if (stored != null) {
            val s = stored.trim()
            FocusPreset.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { return it }
            s.toIntOrNull()?.let { n -> if (n in FocusPreset.entries.indices) return FocusPreset.entries[n] }
        }
        return FocusPreset.TopCentre
    }
}
