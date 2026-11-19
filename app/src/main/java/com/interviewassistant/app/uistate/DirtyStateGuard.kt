package com.interviewassistant.app.uistate

/** Mirror of `InterviewAssistant.App/UiState/DirtyStateGuard.cs`. */

/** Why the UI is about to replace what is on screen. */
enum class ChangeReason {
    // ---- real data-loss actions: unsaved edits would be thrown away ----
    /** Leaving the page (another sidebar item, "Start interview", a deep link). */
    Navigate,
    /** Selecting another item in the same page (another profile, another interview). */
    SwitchItem,
    /** Closing the window. */
    CloseWindow,
    /** Signing out or switching account (the workspace is closed). */
    SignOut,
    /** Reloading the edited item from storage (e.g. after importing another document into it). */
    ReloadData,

    // ---- presentation refreshes: never data loss, never a prompt ----
    /** The UI language changed (texts re-render; drafts, selection and dirty state stay). */
    LanguageChange,
    /** The window was resized, maximized or moved to another monitor/DPI. */
    Resize,
    /** Theme or high-contrast change. */
    ThemeChange,
    /** Resources or account data refreshed in the background. */
    ResourceRefresh,
    /** A list was rebuilt and re-selected the item that is already being edited. */
    ListRefresh,
}

/** What the user chose when asked about unsaved changes. */
enum class LeaveDecision { Save, Discard, Stay }

/**
 * Dirty state of one editor (a profile, an interview, the Settings page). It only tracks edits made by the user:
 * programmatic field updates run inside [suspend], so loading a record or re-rendering texts after a
 * language switch never marks the editor dirty and never clears it either. Plain class (no WPF) so it is unit-tested.
 */
class DirtyStateGuard(val name: String) {
    private var suspended: Int = 0

    var isDirty: Boolean = false
        private set
    val isSuspended: Boolean get() = suspended > 0

    private val dirtyChangedListeners = mutableListOf<() -> Unit>()
    /** Raised when [isDirty] flips (the page shows or hides its "Unsaved changes" indicator). */
    fun addDirtyChangedListener(listener: () -> Unit) { dirtyChangedListeners.add(listener) }
    fun removeDirtyChangedListener(listener: () -> Unit) { dirtyChangedListeners.remove(listener) }
    private fun fireDirtyChanged() = dirtyChangedListeners.toList().forEach { it() }

    /** Saves the pending edits. Returns false when validation failed (the user stays on the page). */
    var save: (() -> Boolean)? = null
    /** Reverts side effects of the pending edits (e.g. a live audio-device preview) when the user discards them. */
    var discard: (() -> Unit)? = null

    /** Called from the editor's change handlers. Ignored while a programmatic update is running. */
    fun markDirty() {
        if (suspended > 0 || isDirty) return
        isDirty = true
        fireDirtyChanged()
    }

    /** After a successful save, a load of another record, or an explicit discard. */
    fun markClean() {
        if (!isDirty) return
        isDirty = false
        fireDirtyChanged()
    }

    /** Programmatic updates inside the returned scope never change the dirty state. */
    fun suspend(): Scope {
        suspended++
        return Scope(this)
    }

    /** The user must be asked only when there are unsaved edits AND the action would lose them. */
    fun requiresConfirmation(reason: ChangeReason): Boolean = isDirty && isDataLoss(reason)

    /** The scope of a [suspend] call; close it (or use `use {}`) to end the suspension. */
    class Scope internal constructor(private val owner: DirtyStateGuard) : AutoCloseable {
        private var done: Boolean = false
        override fun close() {
            if (done) return
            done = true
            owner.suspended--
        }
    }

    companion object {
        /** True for actions that would throw away unsaved edits. */
        fun isDataLoss(reason: ChangeReason): Boolean =
            reason == ChangeReason.Navigate || reason == ChangeReason.SwitchItem || reason == ChangeReason.CloseWindow ||
                reason == ChangeReason.SignOut || reason == ChangeReason.ReloadData

        /**
         * A list selection change is a real switch only when the user picked a different item. Rebuilding the list (for
         * example to re-render its labels in another language) and re-selecting the edited item is not.
         */
        fun classifySelection(editingId: String?, selectedId: String?, programmatic: Boolean): ChangeReason =
            if (programmatic || editingId == selectedId) ChangeReason.ListRefresh else ChangeReason.SwitchItem
    }
}

/**
 * The window-wide guard: before a data-loss action it asks about every affected editor with unsaved edits (Save /
 * Discard / Stay) through `ask` (the in-app dialog in the app, a stub in tests). Presentation refreshes pass
 * straight through without asking.
 */
class NavigationGuard(private val ask: suspend (DirtyStateGuard, ChangeReason) -> LeaveDecision) {
    private val guards = mutableListOf<DirtyStateGuard>()
    private var asking: Boolean = false

    val allGuards: List<DirtyStateGuard> get() = guards
    val anyDirty: Boolean get() = guards.any { it.isDirty }

    fun register(guard: DirtyStateGuard): DirtyStateGuard {
        guards.add(guard)
        return guard
    }

    /**
     * True when the action may proceed. [scope] limits the question to the editors the action affects
     * (switching profile only concerns the profile editor); empty = every registered editor.
     */
    suspend fun canProceedAsync(reason: ChangeReason, vararg scope: DirtyStateGuard): Boolean {
        if (!DirtyStateGuard.isDataLoss(reason)) return true
        if (asking) return false // a question is already on screen: never stack a second one
        val affected = (if (scope.isNotEmpty()) scope.toList() else guards.toList()).filter { it.requiresConfirmation(reason) }
        if (affected.isEmpty()) return true
        asking = true
        try {
            // Ask about every affected editor first; nothing is saved or discarded unless the user lets the action go
            // ahead for all of them ("Stay" on the second must not leave the first already discarded).
            val decisions = mutableListOf<Pair<DirtyStateGuard, LeaveDecision>>()
            for (g in affected) {
                val decision = ask(g, reason)
                if (decision == LeaveDecision.Stay) return false
                decisions.add(g to decision)
            }
            for ((g, decision) in decisions) {
                if (decision == LeaveDecision.Save) {
                    val s = g.save
                    if (s == null || !s()) return false
                    g.markClean()
                } else {
                    g.discard?.invoke()
                    g.markClean()
                }
            }
            return true
        } finally {
            asking = false
        }
    }
}
