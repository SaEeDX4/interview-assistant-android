package com.interviewassistant.app.uistate

import com.interviewassistant.client.BackendException
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.providers.ConnectionStatus
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `ErrorStateTests.cs` (RC4 error-state matrix for the Account page): slow, offline, reconnect,
 * one endpoint timing out, 500, cancelled and retry-success. Exception-type analogs (DC-64): HttpRequestException →
 * [IOException]; TaskCanceledException → [TimeoutCancellationException]; OperationCanceledException + token →
 * [CancellationException] + [Job].
 */
class ErrorStateTests {
    companion object {
        private val t0: OffsetDateTime = OffsetDateTime.of(2026, 10, 16, 14, 5, 0, 0, ZoneOffset.UTC)
        private fun en(key: String): String = Loc.format("en", key)

        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    @Test
    fun offline_SaysCheckYourConnection() {
        assertEquals(en("svc_unreachable"), ErrorCopy.describe(BackendException(0, "network", "", null), includeReference = false))
        assertEquals(en("svc_unreachable"), ErrorCopy.describe(IOException("No such host"), includeReference = false))
    }

    @Test
    fun oneEndpointTimingOut_IsTheTimeoutCopy_WithoutTheCorrelationIdInline() {
        val ex = BackendException(504, "timeout", "timeout", "c0ffee12-3456-7890-abcd-ef0123456789")
        val inline = ErrorCopy.describe(ex, includeReference = false)
        assertEquals(en("svc_timeout"), inline)
        assertFalse(inline.contains("c0ffee12"))
        assertTrue(ErrorCopy.describe(ex, includeReference = true).contains("c0ffee12")) // dialogs/toasts keep a short reference
        assertEquals(en("svc_timeout"), ErrorCopy.describe(clientSideTimeout(), includeReference = false)) // client-side timeout
    }

    @Test
    fun serverError500_IsTemporarilyUnavailable_NeverARawMessage() {
        val text = ErrorCopy.describe(BackendException(500, "", "System.NullReferenceException at …", "abcdef1234567890"), includeReference = false)
        assertEquals(en("svc_unavailable"), text)
        assertFalse(text.contains("Exception"))
    }

    @Test
    fun userCancellation_IsNotAFailure() {
        val job = Job()
        job.cancel()
        assertTrue(ErrorCopy.isUserCancellation(CancellationException("cancelled"), job))
        assertFalse(ErrorCopy.isUserCancellation(clientSideTimeout(), Job())) // an HTTP timeout is not a user cancellation
    }

    @Test
    fun slow_ShowsLoadingOnlyUntilDataExists_ThenKeepsTheData() {
        val devices = SectionState("devices")
        val t = devices.begin()
        assertTrue(devices.isLoading)
        assertFalse(devices.hasData)
        devices.succeed(t, t0)
        val t2 = devices.begin() // a slow refresh: the list on screen stays
        assertTrue(devices.isLoading && devices.hasData)
        devices.succeed(t2, t0.plusMinutes(1))
        assertEquals(SectionStatus.Ready, devices.status)
    }

    @Test
    fun offlineThenReconnect_StaleNoteThenCleared() {
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val s = AccountSections()
        val a = s.summary.begin(); s.summary.succeed(a, t0)
        val d = s.devices.begin(); s.devices.succeed(d, t0)
        // connection drops: both refreshes fail, the data stays with a "saved data from" note
        val a2 = s.summary.begin(); val d2 = s.devices.begin()
        s.summary.fail(a2, en("svc_unreachable")); s.devices.fail(d2, en("svc_unreachable"))
        assertTrue(s.summary.isStale && s.devices.isStale)
        assertTrue(s.devices.staleNote { t -> t.format(hhmm) }!!.contains("Showing saved data from"))
        // reconnect: the next loads clear errors and notes by themselves
        val a3 = s.summary.begin(); val d3 = s.devices.begin()
        s.summary.succeed(a3, t0.plusMinutes(5)); s.devices.succeed(d3, t0.plusMinutes(5))
        for (x in listOf(s.summary, s.devices)) {
            assertNull(x.error); assertFalse(x.isStale); assertNull(x.staleNote { "" })
        }
    }

    @Test
    fun retrySuccess_AfterA500_ClearsOnlyThatSection() {
        val s = AccountSections()
        val b = s.billing.begin(); s.billing.fail(b, en("svc_unavailable"))
        val d = s.devices.begin(); s.devices.fail(d, en("svc_timeout"))
        val retry = s.billing.begin(); s.billing.succeed(retry, t0)
        assertEquals(SectionStatus.Ready, s.billing.status)
        assertEquals(SectionStatus.Failed, s.devices.status) // independent: still offers its own "Try again"
    }

    @Test
    fun sectionFailureTitles_AreSectionSpecific_InEveryLanguage() {
        for (l in LanguageRegistry.all) {
            val titles = listOf("acct_summary_failed", "acct_devices_failed", "acct_billing_failed").map { k -> Loc.format(l.code, k) }
            assertEquals(3, titles.distinct().size)
            assertFalse(titles.contains(Loc.format(l.code, "svc_timeout")))
        }
    }

    /** A real client-side timeout exception, produced the way production code produces it. */
    private fun clientSideTimeout(): TimeoutCancellationException {
        var captured: TimeoutCancellationException? = null
        try {
            runBlocking { withTimeout(1) { delay(10_000) } }
        } catch (e: TimeoutCancellationException) {
            captured = e
        }
        return captured!!
    }
}

/** Port of the Windows `NavigationGuardOrderTests`. */
class NavigationGuardOrderTests {
    @Test
    fun discardForTheFirstEditor_ThenStayForTheSecond_DiscardsNothing() {
        runBlocking {
            val answers = ArrayDeque(listOf(LeaveDecision.Discard, LeaveDecision.Stay))
            val nav = NavigationGuard { _, _ -> answers.removeFirst() }
            val profile = nav.register(DirtyStateGuard("profile"))
            val interview = nav.register(DirtyStateGuard("interview"))
            var discarded = 0
            profile.discard = { discarded++ }
            interview.discard = { discarded++ }
            profile.markDirty(); interview.markDirty()
            assertFalse(nav.canProceedAsync(ChangeReason.SignOut))
            assertEquals(0, discarded)
            assertTrue(profile.isDirty && interview.isDirty)
        }
    }
}

/** Port of the Windows `RelayConnectionToastTests`. */
class RelayConnectionToastTests {
    companion object {
        private fun run(vararg events: ConnectionStatus): ConnectionToast {
            var now = OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC)
            val gate = ConnectionToastGate({ now })
            var up = true
            var last = ConnectionToast.None
            for (e in events) {
                val t = RelayConnection.transition(up, e)
                if (t != null) {
                    up = t.up
                    val r = gate.report(up, t.silent)
                    if (r != ConnectionToast.None) last = r
                }
                now = now.plusSeconds(5)
                val p = gate.poll()
                if (p != ConnectionToast.None) last = p
            }
            return last
        }
    }

    @Test
    fun reconnectThenConnected_SaysRestored() =
        assertEquals(ConnectionToast.Restored, run(ConnectionStatus.Reconnecting, ConnectionStatus.Connected))

    @Test
    fun reconnectThenFailed_NeverSaysRestored() =
        assertEquals(ConnectionToast.Lost, run(ConnectionStatus.Reconnecting, ConnectionStatus.Failed))

    @Test
    fun answerOrErrorStatusDuringTheOutage_DoesNotCountAsRestored() {
        // Only transcriber events move the state; the status line (Answering, ApiError) is not an input at all.
        assertNull(RelayConnection.transition(false, ConnectionStatus.Reconnecting))
        assertNull(RelayConnection.transition(true, ConnectionStatus.Connected))
    }
}
