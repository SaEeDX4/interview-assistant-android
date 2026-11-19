package com.interviewassistant.app.uistate

/** Mirror of `InterviewAssistant.App/UiState/LiveChrome.cs`. */

/** How the pre-interview readiness indicator appears in the Live Assistant header. */
enum class ReadinessIndicator {
    /** A pill with the coloured dot and the state label ("Ready", "Not checked") — setup mode, room to spare. */
    Label,
    /** Only the coloured dot (details in the tooltip) — during an interview, in compact view or when narrow. */
    Dot,
}

/** Which parts of the Live Assistant header and body show (no WPF types: unit-tested). */
data class LiveChromeLayout(
    val readiness: ReadinessIndicator,
    val startLabel: Boolean,
    val adaptiveBadge: Boolean,
    val presentationChip: Boolean,
    val minutesLabel: Boolean,
    val setupEmptyState: Boolean,
    val homeLink: Boolean,
) {
    /** The Coach/Answer choice moves into the More menu when the header has no room for its chip. */
    val presentationInMore: Boolean get() = !presentationChip
    /** The minutes-left figure moves into the More menu when the header has no room for it. */
    val minutesInMore: Boolean get() = !minutesLabel
}

/**
 * The visibility rule of the Live Assistant chrome. Two modes: **setup** (no session) shows setup affordances —
 * the readiness pill with its label, a calm empty-state line and a link to Home; **interview** (listening or
 * paused) keeps only the interview controls — the readiness indicator collapses to a dot. Independently, the header
 * sheds optional items as the window gets narrower so it never clips at the minimum width: the primary items (status,
 * Start/Pause, Stop, readiness, help, More, window buttons) always stay; the overflow (More) takes what does not fit.
 */
object LiveChrome {
    /** The Live window's minimum width (logical px): everything the rule keeps must fit here. */
    const val minWidth: Double = 440.0
    /** From this width the Start button shows its text next to the icon. */
    const val startLabelWidth: Double = 580.0
    /** From this width the "answers are shorter" badge shows (it only appears during an interview). */
    const val adaptiveBadgeWidth: Double = 640.0
    /** From this width (setup mode) the readiness indicator shows its label. */
    const val readinessLabelWidth: Double = 700.0
    /** From this width the Coach/Answer chip shows in the header (otherwise in More). */
    const val presentationChipWidth: Double = 780.0
    /** From this width the minutes-left label shows in the header (otherwise in More). */
    const val minutesLabelWidth: Double = 880.0

    fun `for`(sessionActive: Boolean, compact: Boolean, width: Double): LiveChromeLayout {
        val setup = !sessionActive
        return LiveChromeLayout(
            readiness = if (setup && !compact && width >= readinessLabelWidth) ReadinessIndicator.Label else ReadinessIndicator.Dot,
            startLabel = width >= startLabelWidth,
            adaptiveBadge = !compact && width >= adaptiveBadgeWidth,
            presentationChip = !compact && width >= presentationChipWidth,
            minutesLabel = !compact && width >= minutesLabelWidth,
            setupEmptyState = setup,
            homeLink = setup && !compact,
        )
    }
}
