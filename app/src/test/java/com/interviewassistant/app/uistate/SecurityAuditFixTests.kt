package com.interviewassistant.app.uistate

import java.time.Duration
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the UiState half of `InterviewAssistant.Tests/SecurityAuditFixTests.cs` (P7,
 * TEST-MAP closure): the pending-reset ticket rules (H1) and the reset-flow membership of
 * the auth form. Same inputs, same expected values as the C#. The C# file's client-level
 * methods are ported in `:client` (`client/SecurityAuditFixTests.kt`); its two WPF source
 * scans (HomeWindow cancellation wiring, reset-form focus) are recorded as (c) in
 * parity/TEST-MAP.md with their Android counterparts named.
 *
 * Adaptation: the C# ticket's CancellationToken is the ticket's Job (PendingReset's own
 * documented mapping), and `Dispose()` is `close()`.
 */
class SecurityAuditFixTests {

    @Test
    fun pendingReset_LeavingOrReplacing_CancelsTheWait_AndOnlyTheCurrentTicketMayTouchTheForm() {
        val r = PendingReset()
        val first = r.begin("b@x.test")
        assertTrue(r.isPending)
        assertTrue(r.isPendingFor("B@x.test"))
        assertTrue(r.isCurrent(first))

        // A newer request replaces the first: its wait is cancelled and it may no longer clear Busy or reset the form.
        val second = r.begin("c@x.test")
        assertTrue(first.token.isCancelled)
        assertFalse(r.isCurrent(first))
        assertFalse(r.release(first))
        assertTrue(r.isCurrent(second))
        assertTrue(second.generation > first.generation)

        // Leaving the reset screen (Back to sign in, a sign-in, sign-out, switch, closing the window).
        assertTrue(r.cancel())
        assertTrue(second.token.isCancelled)
        assertFalse(r.isPending)
        assertFalse(r.isCurrent(second))
        assertFalse(r.release(second)) // the stale task's finally leaves Busy alone
        assertFalse(r.cancel()) // nothing left to cancel
        first.close()
        second.close()
        second.close() // idempotent

        // A finished request is released without being cancelled, exactly once.
        val third = r.begin("b@x.test")
        assertTrue(r.release(third))
        assertFalse(third.token.isCancelled)
        assertFalse(r.release(third))
        assertFalse(r.isPending)
        third.close()
    }

    @Test
    fun pendingReset_ANewRequestForTheSameAddress_WaitsOutTheServersPerAddressInterval() {
        val r = PendingReset()
        val t0 = OffsetDateTime.parse("2026-10-19T10:00:00Z")
        val interval = AuthFormState.resendCooldown
        assertEquals(Duration.ZERO, r.waitBeforeSending("b@x.test", t0, interval))
        r.markRequested("b@x.test", t0)
        assertEquals(Duration.ofSeconds(50), r.waitBeforeSending("B@x.test", t0.plusSeconds(10), interval))
        assertEquals(Duration.ZERO, r.waitBeforeSending("c@x.test", t0.plusSeconds(10), interval)) // another address
        assertEquals(Duration.ZERO, r.waitBeforeSending("b@x.test", t0.plus(interval), interval))
    }

    @Test
    fun authForm_InResetFlow_OnlyOnTheResetScreens() {
        val s = AuthFormState()
        assertFalse(s.inResetFlow)
        assertTrue(s.toForgotPassword())
        assertTrue(s.inResetFlow)
        assertTrue(s.resetRequested(OffsetDateTime.now()))
        assertTrue(s.inResetFlow)
        assertTrue(s.toSignIn())
        assertFalse(s.inResetFlow)
        s.toForgotPassword()
        s.startSwitch("a@x.test")
        assertFalse(s.inResetFlow)
        s.toForgotPassword()
        s.reset()
        assertFalse(s.inResetFlow)
    }
}
