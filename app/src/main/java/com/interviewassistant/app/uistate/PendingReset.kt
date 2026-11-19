package com.interviewassistant.app.uistate

import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.Job

/** Mirror of `InterviewAssistant.App/UiState/PendingReset.cs`. */

/**
 * The one password reset whose e-mailed link this PC is waiting for (up to 15 minutes). Each request gets a
 * [Ticket]; a newer request, leaving the reset screen, a sign-in, a sign-out, a switch or closing the window
 * cancels the current one. Only the CURRENT ticket's task may change the form (clear Busy, reset the state, show its
 * result), so a stale reset can never interfere with a later sign-in. Plain class (no WPF) so it is unit-tested.
 */
class PendingReset {
    /** One reset request: its cancellation and the generation it belongs to. */
    class Ticket internal constructor(val generation: Int, val email: String) : AutoCloseable {
        private val job: Job = Job()
        private var disposed: Boolean = false

        /** Cancelled when the ticket is replaced or the reset screen is left (the CancellationToken counterpart: a Job). */
        val token: Job get() = job

        internal fun cancel() {
            if (!disposed) job.cancel()
        }

        /** Called by the task that owns the ticket once it has finished. (C# disposes the token source; disposal does not cancel it, and neither does this.) */
        override fun close() {
            if (disposed) return
            disposed = true
        }
    }

    private var current: Ticket? = null
    private var generationField: Int = 0
    private val requestedAt = mutableMapOf<String, OffsetDateTime>()

    /** A reset link is being awaited. */
    val isPending: Boolean get() = current != null

    /** The generation of the latest request (increases with every request and every cancellation). */
    val generation: Int get() = generationField

    /** A link for this address is being awaited (asking again sends nothing new). */
    fun isPendingFor(email: String): Boolean = current?.let { t -> t.email.equals(email, ignoreCase = true) } ?: false

    /** Starts a new request; any earlier one is cancelled. */
    fun begin(email: String): Ticket {
        cancel()
        val t = Ticket(++generationField, email)
        current = t
        return t
    }

    /** True while [ticket] is the current, uncancelled request: only then may its task touch the form. */
    fun isCurrent(ticket: Ticket): Boolean =
        current === ticket && ticket.generation == generationField && !ticket.token.isCancelled

    /**
     * The ticket's task has finished: it stops being current WITHOUT being cancelled. Returns true when it was still
     * current (the task may then clear Busy); false for a stale ticket, which must leave the form alone.
     */
    fun release(ticket: Ticket): Boolean {
        if (!isCurrent(ticket)) return false
        current = null
        return true
    }

    /** Cancels the awaited reset (if any). Returns true when one was cancelled. */
    fun cancel(): Boolean {
        val t = current ?: return false
        current = null
        generationField++
        t.cancel()
        return true
    }

    /** Records that the auth server accepted a request for this address (its per-address interval starts). */
    fun markRequested(email: String, now: OffsetDateTime) {
        // The C# dictionary is OrdinalIgnoreCase: re-recording an address under a different casing replaces the entry.
        val key = email.trim()
        requestedAt.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let { requestedAt.remove(it) }
        requestedAt[key] = now
    }

    /**
     * How long a new request for [email] must wait so the auth server sends a new e-mail rather than
     * silently rate-limiting it (which is reported as accepted): the earlier link stops working once its wait is
     * cancelled, so sending again too early would leave no working link.
     *
     * Note: the C# per-address dictionary is case-insensitive (OrdinalIgnoreCase); the lookup here trims the
     * address, and [markRequested] stores the trimmed address — see P4-PORT-NOTES DC-65 for the casing rule.
     */
    fun waitBeforeSending(email: String, now: OffsetDateTime, interval: Duration): Duration {
        val at = requestedAt.entries.firstOrNull { (k, _) -> k.equals(email.trim(), ignoreCase = true) }?.value
        return if (at != null && Duration.between(at, now) < interval) interval - Duration.between(at, now) else Duration.ZERO
    }
}
