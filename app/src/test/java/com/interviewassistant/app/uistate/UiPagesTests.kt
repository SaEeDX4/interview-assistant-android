package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `UiPagesTests.cs` — the JVM-portable UiState cases (segmented navigation, the history
 * counter, translated page keys, the debounced connection notifications).
 * Deferred by name (WPF source scans; Compose counterparts land in P4-E/F/G): ReportsPage_HasTwoResponsivePanes_FourActions_EmptyAndSelectStates,
 * ReportsPage_Keyboard_EnterOpens_DeleteAsksDestructively, ProfileDelete_IsDestructive_SetApart_AndConfirmedWithCancelAsDefault,
 * ProfileSections_AreASegmentedControl_WithAutomationNames_AndKeyboard, ProfileAndInterviewForms_UseTheSameGridTokens,
 * LiveWindow_CounterHiddenWhenEmpty_HistoryResetOnRebuildAndSignOut, ManualQuestionBar_FieldAndSendButton_ShareTheHeightTokenOnOneRow,
 * ProfileGapGuidance_IsADistinctCard_NeverTheCandidatesAnswer, LiveWindow_UsesTheGateForEverySource.
 */
class UiPagesTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    @Test
    fun segmentedNavigation_FollowsTheVisualDirection() {
        // (current, leftKey, rightToLeft, expected) with count = 2
        val cases = listOf(
            arrayOf(0, false, false, 1), // LTR: Right → next
            arrayOf(1, true, false, 0), // LTR: Left → previous
            arrayOf(0, true, true, 1), // RTL: segments mirrored, Left → next
            arrayOf(1, false, true, 0), // RTL: Right → previous
            arrayOf(1, false, false, 1), // stops at the end
            arrayOf(0, true, false, 0), // stops at the start
        )
        for (c in cases) {
            val current = c[0] as Int; val left = c[1] as Boolean; val rtl = c[2] as Boolean; val expected = c[3] as Int
            assertEquals("Move($current, left=$left, rtl=$rtl)", expected, SegmentedNavigation.move(current, 2, left, rtl))
        }
    }

    @Test
    fun historyCounter_NeverShowsZeroOfN() {
        val before = Loc.language
        try {
            Loc.language = "en"
            for (count in 0..12) {
                for (position in -3..14) {
                    val text = HistoryCounter.text(position, count)
                    assertFalse(Regex("""^0\s*/""").containsMatchIn(text))
                    assertFalse(text.contains("0 / 0"))
                    val n = HistoryCounter.numbers(position, count)
                    if (n != null) {
                        assertTrue(n.shown in 1..count)
                        assertEquals(count, n.total)
                        assertEquals("${n.shown} / ${n.total}", text)
                    } else {
                        assertEquals("", text)
                    }
                }
            }
            assertEquals("", HistoryCounter.text(-1, 1)) // the reported "0 / 1": nothing on screen yet → hidden
            assertEquals("", HistoryCounter.text(-1, 0))
            assertEquals("1 / 1", HistoryCounter.text(0, 1))
            assertEquals("", HistoryCounter.text(3, 2)) // stale position after an engine rebuild
        } finally {
            Loc.language = before
        }
    }

    @Test
    fun profileGapCard_ReportsStates_AndContextualErrors_AreTranslated() {
        val keys = listOf(
            "live_gap_header", "live_gap_title", "live_gap_subtitle", "live_gap_add", "tt_live_gap_add",
            "reports_empty_title", "reports_empty_hint", "reports_select_title", "reports_select_hint", "report_row_questions",
            "profile_sections", "delete_profile", "delete_interview", "toast_connection_lost_reconnecting",
            "err_profile_save", "err_profile_export", "err_profile_delete", "err_file_read", "err_interview_save", "err_interview_delete",
            "err_report_open", "err_report_copy", "err_report_save", "err_report_delete",
        )
        val en = Loc.tableFor("en")
        for (lang in LanguageRegistry.all.map { it.code }) {
            val t = Loc.tableFor(lang)
            for (k in keys) {
                val v = t[k]
                assertTrue("$lang: $k missing", v != null && v.trim().isNotEmpty())
                if (lang != "en") assertTrue("$lang: $k not translated", en[k] != v)
            }
        }
        assertEquals("Not in your profile yet", en["live_gap_title"])
        assertEquals("Speak from your own experience — here is a structure:", en["live_gap_subtitle"])
        assertEquals("Connection lost — reconnecting…", en["toast_connection_lost_reconnecting"])
        assertTrue(en["err_profile_save"]!!.startsWith("Couldn't save the profile."))
        assertFalse(keys.joinToString(" ") { k -> en[k]!! }.contains("résumé"))
    }

    // ---------------- connection notifications ----------------

    private class FakeClock {
        var now: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)
            private set
        fun advance(seconds: Double) {
            now = now.plusNanos((seconds * 1_000_000_000).toLong())
        }
    }

    /** Polls every 500 ms for [seconds] and collects what would be shown. */
    private fun run(gate: ConnectionToastGate, clock: FakeClock, seconds: Double): List<ConnectionToast> {
        val shown = mutableListOf<ConnectionToast>()
        var t = 0.0
        while (t < seconds) {
            clock.advance(0.5)
            val r = gate.poll()
            if (r != ConnectionToast.None) shown.add(r)
            t += 0.5
        }
        return shown
    }

    @Test
    fun connectionToasts_ShortBlip_ShowsNothing() {
        val clock = FakeClock()
        val gate = ConnectionToastGate({ clock.now })
        assertEquals(ConnectionToast.None, gate.report(false))
        assertTrue(run(gate, clock, 2.5).isEmpty())
        assertEquals(ConnectionToast.None, gate.report(true)) // no "restored" without a shown "lost"
        assertTrue(run(gate, clock, 20.0).isEmpty())
    }

    @Test
    fun connectionToasts_RealOutage_LostAfterGrace_ThenRestored() {
        val clock = FakeClock()
        val gate = ConnectionToastGate({ clock.now })
        gate.report(false)
        assertTrue(run(gate, clock, 2.5).isEmpty())
        assertEquals(listOf(ConnectionToast.Lost), run(gate, clock, 10.0)) // exactly once, however long it lasts
        assertFalse(gate.needsPolling)
        assertEquals(ConnectionToast.Restored, gate.report(true))
        assertEquals(ConnectionToast.None, gate.report(true)) // re-confirmations are silent
    }

    @Test
    fun connectionToasts_AtMostOnePairPerTenSeconds() {
        val clock = FakeClock()
        val gate = ConnectionToastGate({ clock.now })
        gate.report(false)
        assertEquals(listOf(ConnectionToast.Lost), run(gate, clock, 3.5)) // lost shown at ~3 s
        clock.advance(1.0)
        assertEquals(ConnectionToast.Restored, gate.report(true))
        // flapping again right away: the next "lost" waits until 10 s after the previous one…
        clock.advance(0.5)
        gate.report(false)
        val during = run(gate, clock, 4.0) // down 4 s, but only ~9 s since the last "lost"
        assertTrue(during.isEmpty())
        // …and an outage that ends inside that window is never announced (nor its recovery)
        assertEquals(ConnectionToast.None, gate.report(true))
        // a later, real outage is announced again
        clock.advance(10.0)
        gate.report(false)
        assertEquals(listOf(ConnectionToast.Lost), run(gate, clock, 4.0))
        assertEquals(ConnectionToast.Restored, gate.report(true))
    }

    @Test
    fun connectionToasts_DelayedLost_WhenTheOutageOutlastsTheRateLimit() {
        val clock = FakeClock()
        val gate = ConnectionToastGate({ clock.now })
        gate.report(false)
        run(gate, clock, 3.5)
        gate.report(true)
        gate.report(false)
        val shown = run(gate, clock, 12.0)
        assertEquals(listOf(ConnectionToast.Lost), shown) // shown once the 10 s interval has passed
    }

    @Test
    fun connectionToasts_SilentReset_NeverAnnouncesARecoveryThatDidNotHappen() {
        val clock = FakeClock()
        val gate = ConnectionToastGate({ clock.now })
        gate.report(false)
        assertEquals(listOf(ConnectionToast.Lost), run(gate, clock, 4.0))
        assertEquals(ConnectionToast.None, gate.report(true, silent = true)) // interview stopped / signed out
        assertTrue(gate.isConnected)
        assertTrue(run(gate, clock, 15.0).isEmpty())
    }
}
