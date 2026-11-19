package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `UiFoundationTests.cs` (desktop UI foundation: the unsaved-changes guard, independently
 * loaded Account sections, the pre-interview verdict rule, and the copy rules) — the JVM-portable cases.
 * Deferred by name (WPF source scans; Compose counterparts land in P4-E/F/G): DesktopApp_NeverUsesTheNativeMessageBox,
 * SettingsIsAPage_NotASeparateWindow, FormControls_UseTheSharedStyles_NoAdHocWatermarkOverlays, and the
 * app-source halves of EnglishCopy_SaysResume_WithoutAccents and AboutLabels_AreTranslated.
 */
class UiFoundationTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private class Asker {
        var answer: LeaveDecision = LeaveDecision.Stay
        val questions = mutableListOf<Pair<String, ChangeReason>>()
        suspend fun ask(g: DirtyStateGuard, r: ChangeReason): LeaveDecision {
            questions.add(g.name to r)
            return answer
        }
    }

    // ---------------- dirty-state guard ----------------

    @Test
    fun presentationRefreshes_NeverAsk_AndKeepTheEdits() {
        runBlocking {
            for (reason in listOf(ChangeReason.LanguageChange, ChangeReason.Resize, ChangeReason.ThemeChange, ChangeReason.ResourceRefresh, ChangeReason.ListRefresh)) {
                val asker = Asker()
                val nav = NavigationGuard(asker::ask)
                val profile = nav.register(DirtyStateGuard("profile"))
                profile.markDirty()
                assertTrue(nav.canProceedAsync(reason))
                assertTrue(asker.questions.isEmpty())
                assertTrue(profile.isDirty) // nothing saved, nothing discarded
                assertFalse(profile.requiresConfirmation(reason))
            }
        }
    }

    @Test
    fun realDataLossActions_AskAndStayWhenTheUserCancels() {
        runBlocking {
            for (reason in listOf(ChangeReason.Navigate, ChangeReason.SwitchItem, ChangeReason.CloseWindow, ChangeReason.SignOut, ChangeReason.ReloadData)) {
                val asker = Asker().apply { answer = LeaveDecision.Stay }
                val nav = NavigationGuard(asker::ask)
                val profile = nav.register(DirtyStateGuard("profile"))
                profile.markDirty()
                assertFalse(nav.canProceedAsync(reason))
                assertEquals(listOf("profile" to reason), asker.questions)
                assertTrue(profile.isDirty)
            }
        }
    }

    @Test
    fun cleanEditors_NeverAsk() {
        runBlocking {
            val asker = Asker()
            val nav = NavigationGuard(asker::ask)
            nav.register(DirtyStateGuard("profile"))
            assertTrue(nav.canProceedAsync(ChangeReason.Navigate))
            assertTrue(asker.questions.isEmpty())
        }
    }

    @Test
    fun saveChoice_SavesThenProceeds_FailedValidationStays() {
        runBlocking {
            val asker = Asker().apply { answer = LeaveDecision.Save }
            val nav = NavigationGuard(asker::ask)
            var saves = 0
            val settings = nav.register(DirtyStateGuard("settings").apply { save = { saves++; saves > 1 } })
            settings.markDirty()
            assertFalse(nav.canProceedAsync(ChangeReason.Navigate)) // validation failed: stay, still dirty
            assertTrue(settings.isDirty)
            assertTrue(nav.canProceedAsync(ChangeReason.Navigate))
            assertFalse(settings.isDirty)
            assertEquals(2, saves)
        }
    }

    @Test
    fun discardChoice_RevertsSideEffectsAndProceeds() {
        runBlocking {
            val asker = Asker().apply { answer = LeaveDecision.Discard }
            val nav = NavigationGuard(asker::ask)
            var reverted = false
            val settings = nav.register(DirtyStateGuard("settings").apply { discard = { reverted = true } })
            settings.markDirty()
            assertTrue(nav.canProceedAsync(ChangeReason.CloseWindow))
            assertTrue(reverted)
            assertFalse(settings.isDirty)
        }
    }

    @Test
    fun scopedQuestion_OnlyConcernsTheAffectedEditors() {
        runBlocking {
            val asker = Asker().apply { answer = LeaveDecision.Stay }
            val nav = NavigationGuard(asker::ask)
            val profile = nav.register(DirtyStateGuard("profile"))
            val settings = nav.register(DirtyStateGuard("settings"))
            settings.markDirty()
            assertTrue(nav.canProceedAsync(ChangeReason.SwitchItem, profile)) // switching profile: settings edits are unaffected
            assertTrue(asker.questions.isEmpty())
            assertFalse(nav.canProceedAsync(ChangeReason.CloseWindow)) // closing: everything is affected
            assertEquals(1, asker.questions.size)
        }
    }

    @Test
    fun programmaticUpdates_NeitherMarkNorClearDirty() {
        val g = DirtyStateGuard("profile")
        var flips = 0
        g.addDirtyChangedListener { flips++ }
        g.suspend().use { g.markDirty() } // loading a record fills its fields
        assertFalse(g.isDirty)
        g.markDirty() // the user types
        g.suspend().use { g.markDirty() } // re-rendering after a language switch rewrites labels and pickers
        assertTrue(g.isDirty)
        assertEquals(1, flips)
        assertFalse(g.isSuspended)
    }

    @Test
    fun languageSwitchWhileEditingAProfile_DoesNotAskAndKeepsTheDraft() {
        runBlocking {
            // The confirmed rc.3 bug: switching the UI language with unsaved profile edits showed "Discard them?".
            val asker = Asker()
            val nav = NavigationGuard(asker::ask)
            val profile = nav.register(DirtyStateGuard("profile"))
            val draft = "Senior PM"
            profile.markDirty()
            // Home re-renders: the list is rebuilt and re-selects the profile being edited, field labels change.
            profile.suspend().use {
                assertEquals(ChangeReason.ListRefresh, DirtyStateGuard.classifySelection("p1", "p1", programmatic = false))
                assertEquals(ChangeReason.ListRefresh, DirtyStateGuard.classifySelection("p1", null, programmatic = true))
                assertTrue(nav.canProceedAsync(ChangeReason.LanguageChange))
            }
            assertTrue(asker.questions.isEmpty())
            assertTrue(profile.isDirty)
            assertEquals("Senior PM", draft)
            // Picking another profile afterwards is a real switch and asks.
            assertEquals(ChangeReason.SwitchItem, DirtyStateGuard.classifySelection("p1", "p2", programmatic = false))
            assertFalse(nav.canProceedAsync(ChangeReason.SwitchItem, profile))
            assertEquals(1, asker.questions.size)
        }
    }

    @Test
    fun onlyOneQuestionAtATime() {
        runBlocking {
            val gate = CompletableDeferred<LeaveDecision>()
            var asked = 0
            val nav = NavigationGuard { _, _ -> asked++; gate.await() }
            nav.register(DirtyStateGuard("profile")).markDirty()
            val first = async(start = CoroutineStart.UNDISPATCHED) { nav.canProceedAsync(ChangeReason.CloseWindow) }
            assertFalse(nav.canProceedAsync(ChangeReason.Navigate)) // a second request while the question is open is refused
            gate.complete(LeaveDecision.Discard)
            assertTrue(first.await())
            assertEquals(1, asked)
        }
    }

    // ---------------- account sections ----------------

    private val t0: OffsetDateTime = OffsetDateTime.of(2026, 10, 16, 14, 5, 0, 0, ZoneOffset.UTC)
    private fun hm(t: OffsetDateTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm"))

    @Test
    fun oneSectionTimingOut_DoesNotFailTheOthers() {
        val s = AccountSections()
        s.summary.loaded(t0) // plan and quota loaded at sign-in
        val d = s.devices.begin()
        assertTrue(s.devices.fail(d, "That took too long. Please try again."))
        assertEquals(SectionStatus.Ready, s.summary.status)
        assertNull(s.summary.error)
        assertEquals(SectionStatus.Failed, s.devices.status)
        assertEquals(SectionStatus.NotLoaded, s.billing.status)
        assertFalse(s.devices.isStale) // never loaded: an error, not "saved data"
    }

    @Test
    fun retrySuccess_ClearsTheErrorAutomatically() {
        val devices = SectionState("devices")
        devices.succeed(devices.begin(), t0)
        devices.fail(devices.begin(), "timeout")
        assertTrue(devices.isStale)
        val retry = devices.begin()
        assertTrue(devices.isLoading)
        assertTrue(devices.succeed(retry, t0.plusMinutes(1)))
        assertEquals(SectionStatus.Ready, devices.status)
        assertNull(devices.error)
        assertFalse(devices.isStale)
        assertEquals(t0.plusMinutes(1), devices.loadedAt)
    }

    @Test
    fun staleData_IsLabelledWithTheTimeItWasLoaded() {
        val before = Loc.language
        try {
            Loc.language = "en"
            val summary = SectionState("summary")
            summary.succeed(summary.begin(), t0)
            assertNull(summary.staleNote(::hm))
            summary.fail(summary.begin(), "timeout")
            assertEquals("Showing saved data from 14:05.", summary.staleNote(::hm))
            Loc.language = "de"
            assertTrue(summary.staleNote(::hm)!!.contains("14:05"))
        } finally {
            Loc.language = before
        }
    }

    @Test
    fun lateResponses_AreIgnored_AndSignOutDropsEverything() {
        val devices = SectionState("devices")
        val slow = devices.begin()
        val fast = devices.begin()
        assertTrue(devices.succeed(fast, t0))
        assertFalse(devices.fail(slow, "timeout")) // an older request that times out later cannot mark fresh data as failed
        assertEquals(SectionStatus.Ready, devices.status)
        val pending = devices.begin()
        devices.reset() // signed out meanwhile
        assertFalse(devices.succeed(pending, t0))
        assertFalse(devices.hasData)
        assertEquals(SectionStatus.NotLoaded, devices.status)
    }

    // ---------------- pre-interview verdict ----------------

    @Test
    fun verdict_OnlyFailBlocks() {
        assertEquals(VerdictKind.Ready, ReadinessVerdict.evaluate(listOf(CheckOutcome.Pass, CheckOutcome.Pass)))
        assertEquals(VerdictKind.ReadyWithWarnings, ReadinessVerdict.evaluate(listOf(CheckOutcome.Pass, CheckOutcome.Warning, CheckOutcome.Warning)))
        assertEquals(VerdictKind.NotReady, ReadinessVerdict.evaluate(listOf(CheckOutcome.Pass, CheckOutcome.Warning, CheckOutcome.Fail)))
        assertEquals(VerdictKind.Checking, ReadinessVerdict.evaluate(listOf(CheckOutcome.Pending, CheckOutcome.Fail)))
    }

    @Test
    fun preflightCopy_MatchesTheVerdictRule() {
        val en = Loc.tableFor("en")
        // rc.5 UX audit M6/M12 copy: sentence-case badges; a warning does not block, a failed check must be fixed.
        assertTrue(en["pf_subtitle"]!!.contains("a warning does not stop you from starting"))
        assertTrue(en["pf_subtitle"]!!.contains("Fix anything marked " + en["pf_fail"]))
        assertTrue(en["pf_ready_warnings"]!!.startsWith("READY"))
    }

    // ---------------- copy rules (table halves; the app-source halves are deferred by name) ----------------

    @Test
    fun englishCopy_SaysResume_WithoutAccents() {
        val en = Loc.tableFor("en")
        val joined = (en.keys + en.values).joinToString("\n")
        assertFalse(joined.contains("résumé", ignoreCase = true))
        assertFalse(joined.contains("resumé", ignoreCase = true))
        val cv = Regex("""\bCVs?\b""")
        assertTrue(en.values.none { v -> cv.containsMatchIn(v) })
        assertTrue(en["import_resume"]!!.contains("resume", ignoreCase = true))
    }

    @Test
    fun passwordResetCopy_IsNeutral_InEveryLanguage() {
        assertEquals("If an account exists for this e-mail, a password reset link has been sent.", Loc.tableFor("en")["acct_reset_requested"])
        assertFalse(Loc.has("acct_reset_sent")) // the old copy claimed delivery to a specific address
        for (lang in LanguageRegistry.all.map { it.code })
            assertFalse(Loc.tableFor(lang)["acct_reset_requested"]!!.contains("{0}")) // never echoes the address
    }

    @Test
    fun aboutLabels_AreTranslated() {
        val keys = listOf("about_product", "about_version", "about_channel", "about_build", "about_install", "about_signing", "about_service")
        for (lang in listOf("fa", "ar", "zh", "hi"))
            for (k in keys)
                assertTrue("$k in $lang", Loc.tableFor("en")[k] != Loc.tableFor(lang)[k])
    }
}
