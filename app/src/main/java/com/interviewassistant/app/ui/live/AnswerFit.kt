package com.interviewassistant.app.ui.live

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One step of an answer fit: what the Live screen must apply to the view model next. */
sealed interface FitAction {
    /** Set the answer's compact spacing (the C#'s `CompactSpacing`). */
    data class SetCompact(val compact: Boolean) : FitAction

    /** Set the fit font size (null = back to the preset size; the C#'s `SetFitFontSize`). */
    data class SetFontSize(val size: Double?) : FitAction
}

/**
 * The Live answer's auto-fit (MainWindow.xaml.cs `ScheduleFit` / `FitAnswer`): all bullets visible
 * without scrolling, recomputed from the preferred layout every time — 1) preferred size and
 * spacing, 2) tighter spacing, 3) smaller text (wrapped text height scales ≈ with font size², so
 * one proportional step plus a short −0.5 loop converges), never below the readable minimum.
 *
 * The rc.5 guard is the point of the class: with an auto vertical scroll bar the fit oscillated on
 * Windows (scroll bar on/off → width → SizeChanged → fit), an endless loop that starved all idle
 * work. The inputs are therefore keyed — [begin] commits the key BEFORE any pass runs, exactly
 * like the C# assigns `_lastFitKey` before fitting, and the same inputs never fit again. The key
 * uses the scroll viewer's OUTER width (not the viewport width, which the scroll bar changes).
 *
 * Pure decision logic: the screen measures (Compose size callbacks) and applies the returned
 * actions to the view model; every pass is driven by a fresh measurement of the configuration the
 * previous action selected. [begin] returning true means the caller first resets the fit
 * (`SetCompact(false)` + `SetFontSize(null)` — the C#'s first two statements of every fit).
 */
class AnswerFitter {
    /** The inputs the last begun fit was computed for; the same inputs never fit again. */
    var lastFitKey: String? = null
        private set

    private var active: Boolean = false
    private var phase: Int = 0 // 0 = preferred measured, 1 = compact measured, 2 = shrinking
    private var shrinkSteps: Int = 0

    /**
     * Starts a fit for [key]. False when these exact inputs were already fitted (the rc.5 guard) —
     * the caller then does nothing at all.
     */
    fun begin(key: String): Boolean {
        if (key == lastFitKey) return false
        lastFitKey = key
        active = true
        phase = 0
        shrinkSteps = 0
        return true
    }

    /**
     * Reports a measurement of the configuration currently applied. [fits] = the content fits the
     * (safety-reduced) viewport. Returns the next action, or null when the fit is complete (or no
     * fit is running for [key]).
     */
    fun measured(
        key: String,
        fits: Boolean,
        viewport: Double,
        contentHeight: Double,
        fontSize: Double,
        minFontSize: Double,
    ): FitAction? {
        if (!active || key != lastFitKey) return null
        if (fits) {
            active = false
            return null
        }
        return when (phase) {
            0 -> {
                phase = 1
                FitAction.SetCompact(true)
            }
            1 -> {
                phase = 2
                FitAction.SetFontSize(fontSize * sqrt(viewport / contentHeight) * 0.98)
            }
            else -> {
                if (shrinkSteps < 10 && fontSize > minFontSize) {
                    shrinkSteps++
                    FitAction.SetFontSize(fontSize - 0.5)
                } else {
                    active = false
                    null
                }
            }
        }
    }

    companion object {
        /** The C#'s safety margin: the fit targets the viewport height minus 4 px. */
        const val SAFETY_PX: Double = 4.0

        /**
         * The fit key, field for field the C#'s: rounded viewport and outer width, then every
         * input that changes the answer's height, joined by U+001F (bullets by U+001E).
         */
        fun key(
            viewport: Double,
            outerWidth: Double,
            question: String,
            note: String?,
            isCoachView: Boolean,
            isProfileGap: Boolean,
            isThinking: Boolean,
            pending: String,
            coachKeywords: String,
            coachStructure: String,
            coachReminder: String?,
            fontPreset: String,
            bullets: List<String>,
        ): String = listOf(
            viewport.roundToLong().toString(),
            outerWidth.roundToLong().toString(),
            question,
            note ?: "",
            isCoachView.toString(),
            isProfileGap.toString(),
            isThinking.toString(),
            pending,
            coachKeywords,
            coachStructure,
            coachReminder ?: "",
            fontPreset,
            bullets.joinToString("\u001E"),
        ).joinToString("\u001F")
    }
}
