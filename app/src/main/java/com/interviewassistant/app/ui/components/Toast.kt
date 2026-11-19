package com.interviewassistant.app.ui.components

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.uistate.ConnectionToast

/**
 * The queue logic of `Views/ToastHost.cs`, as a pure controller the Compose host renders (times are
 * milliseconds from the caller's clock, so every rule is unit-testable):
 * - stacked, at most [maxVisible] (3): a new toast while full removes the OLDEST immediately;
 * - de-duplicated: the same text within 4 s of its last show is shown once (the recent table is pruned of
 *   entries older than 4 s on every show);
 * - auto-dismiss after a duration: explicit milliseconds win, else Error 7000, Warning 5500, with-detail
 *   4500, plain 3200;
 * - dismissing fades for 160 ms before removal; Escape dismisses the newest non-dismissing toast;
 * - the announced text is the title, plus ". " + detail when there is one; errors announce assertively.
 */
class ToastController(private val maxVisible: Int = 3) {

    enum class Phase { Shown, Dismissing }

    data class ToastItem(
        val id: Int,
        val text: String,
        val detail: String?,
        val kind: ToastKind,
        val shownAtMs: Long,
        val durationMs: Long,
        val phase: Phase = Phase.Shown,
        val dismissStartedAtMs: Long = 0,
    ) {
        /** The text a screen reader announces (the card's automation name in the C#). */
        val announcement: String get() = if (detail.isNullOrEmpty()) text else "$text. $detail"
        val assertive: Boolean get() = kind == ToastKind.Error
    }

    private val recent = mutableMapOf<String, Long>()
    private val items = mutableListOf<ToastItem>()
    private var nextId = 1

    /** Toasts currently on screen (including ones fading out), oldest first. Expired ones start dismissing. */
    fun visible(nowMs: Long): List<ToastItem> {
        settle(nowMs)
        return items.toList()
    }

    /** Currently visible toast announcement texts (the C#'s self-test `VisibleTexts`). */
    fun visibleTexts(nowMs: Long): List<String> = visible(nowMs).map { it.announcement }

    fun show(text: String, kind: ToastKind = ToastKind.Info, detail: String? = null, milliseconds: Long? = null, nowMs: Long) {
        if (text.isBlank()) return
        recent.entries.removeAll { nowMs - it.value > DEDUP_WINDOW_MS }
        if (recent.containsKey(text)) return // no spam: identical message already on screen
        recent[text] = nowMs
        val duration = milliseconds ?: defaultDurationMs(kind, detail)
        items.add(ToastItem(nextId++, text, detail, kind, nowMs, duration))
        while (items.size > maxVisible) items.removeAt(0)
    }

    /** Click on a card dismisses it (fade 160 ms, then removal). */
    fun dismiss(id: Int, nowMs: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || items[i].phase == Phase.Dismissing) return
        items[i] = items[i].copy(phase = Phase.Dismissing, dismissStartedAtMs = nowMs)
    }

    /** Escape dismisses the newest toast that is not already dismissing. True when one was dismissed. */
    fun dismissNewest(nowMs: Long): Boolean {
        val item = items.lastOrNull { it.phase != Phase.Dismissing } ?: return false
        dismiss(item.id, nowMs)
        return true
    }

    /** A language switch re-renders the visible toasts in place (ToastHost.RelocalizeVisible). */
    fun relocalize(relocalize: (String) -> String?) {
        for (i in items.indices) {
            val it = items[i]
            items[i] = it.copy(
                text = relocalize(it.text) ?: it.text,
                detail = it.detail?.let { d -> relocalize(d) ?: d },
            )
        }
    }

    /** The connection pair, worded exactly as MainViewModel.ShowConnectionToast words it. */
    fun showConnection(toast: ConnectionToast, nowMs: Long) {
        when (toast) {
            ConnectionToast.Lost -> show(
                Loc.t("toast_connection_lost_reconnecting"), ToastKind.Warning,
                Loc.t("toast_connection_lost_detail"), nowMs = nowMs,
            )
            ConnectionToast.Restored -> show(
                Loc.t("toast_connection_restored"), ToastKind.Success,
                Loc.t("toast_connection_restored_detail"), nowMs = nowMs,
            )
            ConnectionToast.None -> Unit
        }
    }

    private fun settle(nowMs: Long) {
        for (i in items.indices) {
            val it = items[i]
            if (it.phase == Phase.Shown && nowMs - it.shownAtMs >= it.durationMs) {
                items[i] = it.copy(phase = Phase.Dismissing, dismissStartedAtMs = it.shownAtMs + it.durationMs)
            }
        }
        items.removeAll { it.phase == Phase.Dismissing && nowMs - it.dismissStartedAtMs >= FADE_OUT_MS }
    }

    companion object {
        const val DEDUP_WINDOW_MS: Long = 4000
        const val FADE_IN_MS: Long = 180
        const val SLIDE_IN_MS: Long = 220
        const val FADE_OUT_MS: Long = 160

        /** The duration table of ToastHost.Show. */
        fun defaultDurationMs(kind: ToastKind, detail: String?): Long = when (kind) {
            ToastKind.Error -> 7000
            ToastKind.Warning -> 5500
            else -> if (detail == null) 3200 else 4500
        }

        /** Icon + accent brush key of a kind (ToastHost.Show's switch). */
        fun glyph(kind: ToastKind): IaGlyph = when (kind) {
            ToastKind.Success -> IaGlyph.Check
            ToastKind.Warning -> IaGlyph.Warning
            ToastKind.Error -> IaGlyph.Error
            ToastKind.Info -> IaGlyph.Info
        }
    }
}
