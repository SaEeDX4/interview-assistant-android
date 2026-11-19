package com.interviewassistant.app.uistate

import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Windows `Rc5ReadinessTests.cs`: one authoritative snapshot drives every readiness surface; Get
 * started offers exactly one next action; deep links resolve before a page is shown.
 * Deferred by name (WPF/view-model source scan; the Live-window counterpart lands in P4-G):
 * AutoFit_NeverRefitsTheSameInputs. AuthorizedUse_IsAskedOnlyWhenListeningCanStart was deferred
 * to P4-F and is ported below.
 */
class Rc5ReadinessTests {
    companion object {
        private const val CTX = "user|target|device"

        private fun run(vararg results: Pair<ReadinessCheckId, CheckOutcome>): ReadinessStore {
            val store = ReadinessStore { OffsetDateTime.of(2026, 10, 17, 9, 0, 0, 0, ZoneOffset.UTC) }
            store.begin(CTX)
            val given = results.toMap()
            for (id in ReadinessCheckId.entries)
                store.report(ReadinessCheckResult(id, given[id] ?: CheckOutcome.Pass))
            return store
        }

        private fun facts(
            account: Boolean = true, profile: Boolean = true, facts: Boolean = true,
            interview: Boolean = true, prepared: Boolean = true, readiness: ReadinessState = ReadinessState.Ready,
        ) = SetupFacts(account, profile, facts, interview, prepared, readiness)
    }

    @Test
    fun allPass_IsReady_EightOfEight() {
        val s = run().snapshot
        assertEquals(ReadinessState.Ready, s.stateFor(CTX))
        assertEquals(8, s.passed)
        assertEquals(8, s.total)
        assertNotNull(s.checkedAt)
    }

    @Test
    fun oneWarning_IsReadyWithWarnings_SevenOfEight() {
        val s = run(ReadinessCheckId.WindowOnTop to CheckOutcome.Warning).snapshot
        assertEquals(ReadinessState.ReadyWithWarnings, s.stateFor(CTX))
        assertEquals(7, s.passed)
        assertEquals(1, s.warnings)
    }

    @Test
    fun oneFailure_IsNotReady() {
        val s = run(ReadinessCheckId.AccountPlan to CheckOutcome.Fail, ReadinessCheckId.WindowOnTop to CheckOutcome.Warning).snapshot
        assertEquals(ReadinessState.NotReady, s.stateFor(CTX))
        assertEquals(6, s.passed)
    }

    @Test
    fun running_IsChecking_AndHasNoTimestamp() {
        val store = ReadinessStore()
        store.begin(CTX)
        store.report(ReadinessCheckResult(ReadinessCheckId.PlaybackDevice, CheckOutcome.Pass))
        assertEquals(ReadinessState.Checking, store.snapshot.stateFor(CTX))
        assertNull(store.snapshot.checkedAt)
    }

    @Test
    fun anotherInterviewOrDevice_MakesTheResultOutdated() {
        val s = run().snapshot
        assertEquals(ReadinessState.Outdated, s.stateFor("user|other-target|device"))
        assertEquals(ReadinessState.Outdated, s.stateFor(ReadinessStore.contextOf("user", "target", "headset")))
    }

    @Test
    fun nothingRun_IsNotChecked_AndClearForgetsTheResult() {
        assertEquals(ReadinessState.NotChecked, ReadinessSnapshot.empty.stateFor(CTX))
        val store = run()
        store.clear()
        assertEquals(ReadinessState.NotChecked, store.snapshot.stateFor(CTX))
    }

    @Test
    fun aLaterResult_ReplacesTheEarlierOne_AndEverySubscriberSeesTheSameSnapshot() {
        val store = run(ReadinessCheckId.AudioSignal to CheckOutcome.Warning)
        var seenByPage: ReadinessSnapshot? = null
        var seenByIndicator: ReadinessSnapshot? = null
        store.addChangedListener { s -> seenByPage = s }
        store.addChangedListener { s -> seenByIndicator = s }
        store.report(ReadinessCheckResult(ReadinessCheckId.AudioSignal, CheckOutcome.Pass, "heard -18 dB"))
        assertSame(seenByPage, seenByIndicator)
        assertEquals(ReadinessState.Ready, seenByPage!!.stateFor(CTX))
        assertEquals(1, seenByPage!!.checks.count { c -> c.id == ReadinessCheckId.AudioSignal })
    }

    @Test
    fun cloudTranscriptionNotTestedYet_IsStillReady_AndTheSummarySaysWhatIsOptional() {
        // rc.5 UX audit H1: transcription can only be proven with real meeting audio, so it is an optional test —
        // a fully working account setup reaches "Ready", never a permanent "Ready with warnings".
        val s = run(ReadinessCheckId.Transcription to CheckOutcome.NotTested).snapshot
        assertEquals(ReadinessState.Ready, s.stateFor(CTX))
        assertEquals(7, s.passed)
        assertEquals(7, s.required)
        val summary = ReadinessPresentation.summary(s, CTX)
        assertEquals("rd_summary_passed_untested", summary.key)
        assertEquals(listOf(7, 7, 1), summary.args)
        assertEquals(1, ReadinessPresentation.notTestedYet(s).size)
        assertTrue(ReadinessPresentation.warnings(s).isEmpty())
        assertEquals("rd_status_not_tested", ReadinessPresentation.statusKey(CheckOutcome.NotTested))
    }

    @Test
    fun notTestedNextToAFailure_IsStillNotReady() {
        val s = run(ReadinessCheckId.Transcription to CheckOutcome.NotTested, ReadinessCheckId.AccountPlan to CheckOutcome.Fail).snapshot
        assertEquals(ReadinessState.NotReady, s.stateFor(CTX))
    }

    @Test
    fun allPassed_SummaryIsNOfEight() {
        val summary = ReadinessPresentation.summary(run().snapshot, CTX)
        assertEquals("rd_summary_passed", summary.key)
        assertEquals(listOf(8, 8), summary.args)
        // a result for something else never shows its numbers
        assertEquals(listOf(0, 8), ReadinessPresentation.summary(run().snapshot, "other").args)
    }

    @Test
    fun anOldResult_IsOutOfDate() {
        // rc.5 UX audit H3: "Ready" from yesterday morning says nothing about this meeting.
        val s = run().snapshot
        val checkedAt = s.checkedAt!!
        assertEquals(ReadinessState.Ready, s.stateFor(CTX, checkedAt.plusMinutes(29)))
        assertEquals(ReadinessState.Outdated, s.stateFor(CTX, checkedAt + ReadinessSnapshot.maxAge + java.time.Duration.ofSeconds(1)))
        assertTrue(ReadinessPresentation.shouldAutoRun(ReadinessState.Outdated))
    }

    @Test
    fun savedSettingsOrProfile_MakeTheResultOutOfDate_ThroughTheRevision() {
        val before = ReadinessStore.contextOf("user", "target", "device", revision = 3)
        val after = ReadinessStore.contextOf("user", "target", "device", revision = 4)
        assertNotEquals(before, after)
        val store = ReadinessStore()
        store.begin(before)
        for (id in ReadinessCheckId.entries) store.report(ReadinessCheckResult(id, CheckOutcome.Pass))
        assertEquals(ReadinessState.Ready, store.snapshot.stateFor(before))
        assertEquals(ReadinessState.Outdated, store.snapshot.stateFor(after))
        var seen: ReadinessSnapshot? = null
        store.addChangedListener { x -> seen = x }
        store.touch() // surfaces re-render with the new context
        assertSame(store.snapshot, seen)
    }

    @Test
    fun concurrentReports_AreNeverLost() {
        // error-state audit L1: probes report from thread-pool continuations
        val store = ReadinessStore()
        store.begin(CTX)
        val threads = ReadinessCheckId.entries.map { id ->
            thread { store.report(ReadinessCheckResult(id, CheckOutcome.Pass)) }
        }
        threads.forEach { it.join() }
        assertEquals(8, store.snapshot.passed)
    }

    @Test
    fun getStarted_CheckStepIsNeverDone_BeforeTheSetupItChecks() {
        // a "Ready" result from earlier must not tick step 4 while step 3 (prepare) is still open
        val f = facts(prepared = false, readiness = ReadinessState.Ready)
        assertFalse(SetupPlanner.isDone(SetupStep.PreInterviewCheck, f))
        assertEquals(StepStatus.Upcoming, SetupPlanner.steps(f).single { s -> s.step == SetupStep.PreInterviewCheck }.status)
    }

    @Test
    fun everyStateHasALabel() {
        val cases = listOf(
            ReadinessState.Ready to "rd_state_ready",
            ReadinessState.ReadyWithWarnings to "rd_state_warnings",
            ReadinessState.NotReady to "rd_state_not_ready",
            ReadinessState.NotChecked to "rd_state_not_checked",
            ReadinessState.Outdated to "rd_state_outdated",
            ReadinessState.Checking to "rd_state_checking",
        )
        for ((state, key) in cases) assertEquals(key, ReadinessSnapshot.stateKey(state))
    }

    // ---------------- Get started ----------------

    @Test
    fun getStarted_OffersExactlyTheNextAction() {
        // (account, profile, facts, interview, prepared, readiness, expected)
        data class Case(val account: Boolean, val profile: Boolean, val facts: Boolean, val interview: Boolean, val prepared: Boolean, val r: ReadinessState, val expected: SetupAction)
        val cases = listOf(
            Case(false, true, true, true, true, ReadinessState.Ready, SetupAction.SignIn),
            Case(true, false, false, false, false, ReadinessState.NotChecked, SetupAction.CompleteProfile),
            Case(true, true, false, false, false, ReadinessState.NotChecked, SetupAction.CompleteProfile),
            Case(true, true, true, false, false, ReadinessState.NotChecked, SetupAction.CreateInterview),
            Case(true, true, true, true, false, ReadinessState.NotChecked, SetupAction.PrepareInterview),
            Case(true, true, true, true, true, ReadinessState.NotChecked, SetupAction.RunPreInterviewCheck),
            Case(true, true, true, true, true, ReadinessState.Outdated, SetupAction.RunPreInterviewCheck),
            Case(true, true, true, true, true, ReadinessState.NotReady, SetupAction.RunPreInterviewCheck),
            Case(true, true, true, true, true, ReadinessState.ReadyWithWarnings, SetupAction.StartInterview),
            Case(true, true, true, true, true, ReadinessState.Ready, SetupAction.StartInterview),
            // rc.5 UX audit M10: prepared knowingly without confirmed facts → the button no longer sticks on "Complete profile"
            Case(true, true, false, true, true, ReadinessState.Ready, SetupAction.StartInterview),
            Case(true, true, false, true, false, ReadinessState.NotChecked, SetupAction.CompleteProfile),
        )
        for (c in cases)
            assertEquals(c.expected, SetupPlanner.primaryAction(facts(c.account, c.profile, c.facts, c.interview, c.prepared, c.r)))
    }

    @Test
    fun getStarted_Steps_OneCurrent_DoneBeforeIt() {
        val steps = SetupPlanner.steps(facts(interview = false, prepared = false, readiness = ReadinessState.NotChecked))
        assertEquals(
            listOf(StepStatus.Done, StepStatus.Done, StepStatus.Current, StepStatus.Upcoming),
            steps.map { s -> s.status },
        )
        assertEquals(1, steps.count { s -> s.status == StepStatus.Current })
    }

    @Test
    fun getStarted_SignedOut_NothingAfterAccountIsDone() {
        // Local data of a signed-out user is not shown, so it cannot count as a finished step either.
        val steps = SetupPlanner.steps(facts(account = false))
        assertEquals(StepStatus.Current, steps[0].status)
        for (s in steps.drop(1).take(2)) assertNotEquals(StepStatus.Done, s.status)
    }

    @Test
    fun getStarted_AllDone_NoCurrentStep_StartInterview() {
        val f = facts()
        for (s in SetupPlanner.steps(f)) assertEquals(StepStatus.Done, s.status)
        assertEquals("gs_action_start", SetupPlanner.actionKey(SetupPlanner.primaryAction(f)))
    }

    // ---------------- deep links ----------------

    @Test
    fun route_Parse() {
        // (route, page, section)
        val cases = listOf(
            Triple("settings/audio", "settings", "audio"),
            Triple("Settings/Hotkeys", "settings", "hotkeys"),
            Triple("preinterview", "preinterview", null),
            Triple("account", "account", null),
            Triple("profiles/personal", "profiles", "personal"),
            Triple("nonsense/x", "start", null),
            Triple("", "start", null),
            Triple(null, "start", null),
        )
        for ((route, page, section) in cases) {
            val r = HomeRoute.parse(route)
            assertEquals("route=$route", page, r.page)
            assertEquals("route=$route", section, r.section)
        }
    }

    @Test
    fun route_SignedOutGate() {
        // (page, signedOut, expected)
        val cases = listOf(
            Triple("profiles", true, "account"),
            Triple("interviews", true, "account"),
            Triple("reports", true, "account"),
            Triple("settings", true, "settings"),
            Triple("preinterview", true, "preinterview"),
            Triple("profiles", false, "profiles"),
        )
        for ((page, signedOut, expected) in cases)
            assertEquals(expected, HomeRoute(page).gate(signedOut).page)
    }

    @Test
    fun authorizedUse_IsAskedOnlyWhenListeningCanStart() {
        // Port of the C# source scan (deferred to P4-F): inside ToggleListeningCoreAsync the
        // sign-in note comes first, the authorized-use confirmation second and the lease begins
        // last — the consent prompt can only appear once listening can actually start.
        val candidates = listOf(
            File("src/main/java/com/interviewassistant/app/viewmodels/MainViewModel.kt"),
            File("app/src/main/java/com/interviewassistant/app/viewmodels/MainViewModel.kt"),
            File("../app/src/main/java/com/interviewassistant/app/viewmodels/MainViewModel.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("MainViewModel.kt not found from the test working directory", file)
        val source = file!!.readText(Charsets.UTF_8)
        val fnStart = source.indexOf("fun toggleListeningCoreAsync")
        assertTrue("toggleListeningCoreAsync not found", fnStart >= 0)
        val body = source.substring(fnStart)
        val note = body.indexOf("note_sign_in_to_listen")
        val confirm = body.indexOf("confirmAuthorizedUseAsync")
        val lease = body.indexOf("beginLeaseAsync()")
        assertTrue("the sign-in note is missing", note >= 0)
        assertTrue("the authorized-use confirmation must come after the sign-in note", confirm > note)
        assertTrue("the lease must begin after the confirmation", lease > confirm)
    }
}
