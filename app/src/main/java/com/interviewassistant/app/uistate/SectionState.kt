package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.Loc
import java.time.OffsetDateTime

/** Mirror of `InterviewAssistant.App/UiState/SectionState.cs`. */

enum class SectionStatus { NotLoaded, Loading, Ready, Failed }

/**
 * Load state of one independently loaded part of a page (Account: plan and usage, devices, billing actions). A failed
 * or timed-out request only marks its own section: other sections keep their data and status, data already shown
 * stays visible as "saved data from <time>", and the next successful load clears the error by itself.
 * Responses that arrive after a newer request started (or after a sign-out) are ignored. Plain class (no WPF).
 */
class SectionState(val name: String) {
    private var generation: Int = 0

    var status: SectionStatus = SectionStatus.NotLoaded
        private set
    /** User-facing message of the last failure (no correlation id: that goes to the log). */
    var error: String? = null
        private set
    /** When the data on screen was loaded successfully; null = no data yet. */
    var loadedAt: OffsetDateTime? = null
        private set
    val hasData: Boolean get() = loadedAt != null
    val isLoading: Boolean get() = status == SectionStatus.Loading
    /** Data is on screen but the latest refresh failed: show it with a "saved data from …" note. */
    val isStale: Boolean get() = status == SectionStatus.Failed && hasData

    private val changedListeners = mutableListOf<() -> Unit>()
    fun addChangedListener(listener: () -> Unit) { changedListeners.add(listener) }
    fun removeChangedListener(listener: () -> Unit) { changedListeners.remove(listener) }
    private fun fireChanged() = changedListeners.toList().forEach { it() }

    /** Starts a load and returns its ticket; pass it back to [succeed] or [fail]. */
    fun begin(): Int {
        status = SectionStatus.Loading
        fireChanged()
        return ++generation
    }

    /** Returns false (and changes nothing) when a newer load started or the section was reset meanwhile. */
    fun succeed(ticket: Int, now: OffsetDateTime): Boolean {
        if (ticket != generation) return false
        status = SectionStatus.Ready
        error = null
        loadedAt = now
        fireChanged()
        return true
    }

    fun fail(ticket: Int, error: String): Boolean {
        if (ticket != generation) return false
        status = SectionStatus.Failed
        this.error = error
        fireChanged()
        return true
    }

    /** Data that arrived through another path (e.g. the account summary loaded at sign-in). */
    fun loaded(now: OffsetDateTime) {
        generation++
        status = SectionStatus.Ready
        error = null
        loadedAt = now
        fireChanged()
    }

    /** A failure reported through another path (e.g. a background account refresh that failed). */
    fun failed(error: String) {
        generation++
        status = SectionStatus.Failed
        this.error = error
        fireChanged()
    }

    /** Sign-out or account switch: nothing of the previous account remains, late responses are dropped. */
    fun reset() {
        generation++
        status = SectionStatus.NotLoaded
        error = null
        loadedAt = null
        fireChanged()
    }

    /** "Showing saved data from 14:05" when stale, else null. [formatTime] renders the local time. */
    fun staleNote(formatTime: (OffsetDateTime) -> String): String? =
        if (isStale) Loc.f("acct_section_stale", formatTime(loadedAt!!)) else null
}

/** The Account page's independently loaded sections. */
class AccountSections {
    val summary: SectionState = SectionState("summary")
    val devices: SectionState = SectionState("devices")
    val billing: SectionState = SectionState("billing")
    val all: List<SectionState> get() = listOf(summary, devices, billing)

    fun resetAll() {
        for (s in all) s.reset()
    }
}
