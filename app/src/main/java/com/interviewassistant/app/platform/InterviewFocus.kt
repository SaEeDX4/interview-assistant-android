package com.interviewassistant.app.platform

import android.util.Rational
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.uistate.FocusPlacement
import com.interviewassistant.app.uistate.FocusPreset

/**
 * Interview Focus on Android (TRANSFER-MAP §8 D9; P4-PORT-NOTES DC-161 deferred the placement
 * to this phase): the C# shrinks the Live window to a top-edge preset by the camera and saves
 * the prior placement for "Restore previous size". The Android substitute is Picture-in-Picture:
 * applying a preset enters PiP with the live aspect ratio; the system owns PiP's size and
 * position, so the preset's *geometry* cannot be honoured — the preset is still chosen,
 * persisted and restored exactly as the C# persists it, and the PiP request carries the
 * [FocusPlacement] recommended size as its aspect ratio. That is the whole D9 residual gap,
 * recorded as DC-174 rather than papered over.
 *
 * The wiring shape mirrors the C#'s split: the Live screen's dialog (P4-G) owns the choice and
 * the persistence; the platform (MainActivity) owns the window act. [FocusPlatform] is the seam
 * between them — with no handler installed, applying a preset is persistence-only, exactly the
 * P4-G behaviour.
 */
object FocusPlatform {
    /** Enters the Focus presentation for [preset]; installed by MainActivity. */
    var applyHandler: ((FocusPreset) -> Unit)? = null

    /** Restores from the Focus presentation; installed by MainActivity. */
    var restoreHandler: (() -> Unit)? = null

    fun applyFocus(preset: FocusPreset) {
        applyHandler?.invoke(preset)
    }

    fun restoreFocus() {
        restoreHandler?.invoke()
    }

    /** The PiP aspect ratio: the C#'s recommended Focus size (560×360), as D9 prescribes. */
    fun pipAspectRatio(): Rational =
        Rational(FocusPlacement.recommendedWidth.toInt(), FocusPlacement.recommendedHeight.toInt())
}

/**
 * The Focus restore record (the C#'s `FocusRestore*` settings): written from the activity's
 * actual pre-PiP state when Focus is applied, read by the Live screen's restore row under the
 * exact rule the C# uses (a record exists when both dimensions are present and positive), and
 * cleared when the presentation is restored. On Android, leaving PiP *is* the restore — the
 * full Live screen comes back at its prior size — so MainActivity clears the record when PiP
 * exits; the record's lifetime is "while in the Focus presentation", as on Windows it is
 * "while the window sits at the Focus placement".
 */
object FocusRestoreRecord {
    /** Whether a restore record exists (the Live screen's `hasFocusRestore` rule, verbatim). */
    fun has(settings: AppSettings): Boolean =
        (settings.focusRestoreWidth ?: 0.0) > 0 && (settings.focusRestoreHeight ?: 0.0) > 0

    /** Records the pre-Focus placement (logical pixels, as the C# stores them) and saves. */
    fun write(settings: AppSettings, left: Double, top: Double, width: Double, height: Double) {
        settings.focusRestoreLeft = left
        settings.focusRestoreTop = top
        settings.focusRestoreWidth = width
        settings.focusRestoreHeight = height
        settings.save()
    }

    /** Clears the record (all four fields back to unset) and saves. No-op when already clear. */
    fun clear(settings: AppSettings) {
        if (settings.focusRestoreLeft == null && settings.focusRestoreTop == null &&
            settings.focusRestoreWidth == null && settings.focusRestoreHeight == null
        ) {
            return
        }
        settings.focusRestoreLeft = null
        settings.focusRestoreTop = null
        settings.focusRestoreWidth = null
        settings.focusRestoreHeight = null
        settings.save()
    }
}

/**
 * Focus requests from outside the Live destination (the D2 ManualInput shortcut): the Live
 * screen's manual bar registers a focus action while it is composed; a request with no bar
 * composed is dropped — the destination switch that precedes it composes the bar, and the C#'s
 * equivalent (focusing a hidden window's field) is likewise a no-op until the window shows.
 */
object LiveFocusRequests {
    val manualInputListeners: MutableList<() -> Unit> = mutableListOf()

    fun requestManualInput() {
        val snapshot = synchronized(manualInputListeners) { manualInputListeners.toList() }
        for (listener in snapshot) listener()
    }
}
