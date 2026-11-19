package com.interviewassistant.app.ui.preinterview

import com.interviewassistant.app.uistate.CheckOutcome
import com.interviewassistant.app.uistate.ReadinessCheckId
import com.interviewassistant.app.uistate.ReadinessCheckResult
import com.interviewassistant.app.uistate.ReadinessFix
import com.interviewassistant.app.uistate.ReadinessPresentation
import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.app.viewmodels.VmCloudFixture
import com.interviewassistant.app.viewmodels.VmFixture
import com.interviewassistant.core.languages.Loc
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * P4-F: the pre-interview page controller's snapshot → render mapping and its actions, over the
 * real MainViewModel fixture (fake capture, fake HTTP). The C#'s page renders FROM the store and
 * writes it through the production probes; these tests pin the render mapping (rows, badges,
 * blockers, summary), the Start gate, the fix routing, and one full signed-out cloud run.
 */
class PreInterviewControllerTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private lateinit var cloud: VmCloudFixture
    private lateinit var fixture: VmFixture
    private lateinit var controller: PreInterviewPageControllerImpl

    @Before
    fun setUp() {
        cloud = VmCloudFixture()
        fixture = VmFixture(cloud)
        controller = PreInterviewPageControllerImpl(fixture.vm, fixture.scope, postToUi = { it() })
    }

    @After
    fun tearDown() {
        controller.detach()
        fixture.close()
    }

    /** Writes a complete result for the current context straight into the store (all 8 checks). */
    private fun writeResult(vararg outcomes: Pair<ReadinessCheckId, CheckOutcome>) {
        val vm = fixture.vm
        val given = outcomes.toMap()
        vm.readiness.begin(vm.readinessContext)
        for (id in ReadinessCheckId.entries) {
            val outcome = given[id] ?: CheckOutcome.Pass
            val fix = when {
                outcome == CheckOutcome.Pass || outcome == CheckOutcome.Pending -> ReadinessFix.None
                id == ReadinessCheckId.AccountPlan -> ReadinessFix.OpenAccount
                id == ReadinessCheckId.WindowOnTop -> ReadinessFix.OpenWindowSettings
                else -> ReadinessFix.None
            }
            vm.readiness.report(ReadinessCheckResult(id, outcome, "detail-$id", fix))
        }
    }

    private fun row(id: ReadinessCheckId): ReadinessRowState = controller.rows.first { it.id == id }

    @Test
    fun render_MapsTheSnapshotToRowsBadgesAndSummary() {
        writeResult(ReadinessCheckId.AccountPlan to CheckOutcome.Fail)
        controller.refresh()

        // The failed row carries its fix; a passed row offers none.
        val account = row(ReadinessCheckId.AccountPlan)
        assertEquals(Loc.t(ReadinessPresentation.titleKey(ReadinessCheckId.AccountPlan, usingCloud = true)), account.title)
        assertEquals(Loc.t(ReadinessPresentation.statusKey(CheckOutcome.Fail)), account.status)
        assertEquals("detail-AccountPlan", account.detail)
        assertTrue(account.fixVisible)
        assertEquals(ReadinessFix.OpenAccount, account.fix)
        assertEquals(
            Loc.t(ReadinessPresentation.fixLabelKey(ReadinessFix.OpenAccount)!!),
            account.fixLabel,
        )
        val device = row(ReadinessCheckId.PlaybackDevice)
        assertEquals(Loc.t(ReadinessPresentation.statusKey(CheckOutcome.Pass)), device.status)
        assertFalse(device.fixVisible)

        // The summary card: Not ready, one blocker line, a checked-at stamp.
        assertEquals(ReadinessState.NotReady, controller.state)
        assertEquals(Loc.t(ReadinessSnapshot.stateKey(ReadinessState.NotReady)), controller.summaryStateText)
        assertEquals(controller.summaryText(), controller.summaryLine)
        assertTrue(controller.summaryCheckedAt.isNotEmpty())
        assertTrue(controller.blockersVisible)
        assertEquals(Loc.t("rd_blockers_title"), controller.blockersTitle)
        assertEquals(1, controller.blockerLines.size)
        assertFalse(controller.canStart)
    }

    @Test
    fun render_NothingRun_RowsAreUntestedAndNothingCanStart() {
        controller.refresh()

        assertEquals(ReadinessState.NotChecked, controller.state)
        for (r in controller.rows) {
            assertEquals(Loc.t(ReadinessPresentation.statusKey(null)), r.status)
            assertEquals("", r.detail)
            assertFalse(r.fixVisible)
        }
        assertEquals("", controller.summaryCheckedAt)
        assertFalse(controller.blockersVisible)
        assertFalse(controller.canStart)
        assertFalse(controller.isRunning)
    }

    @Test
    fun requestStart_FiresOnlyWhenNothingBlocks() {
        var started = 0
        controller.startRequestedListeners += { started++ }

        writeResult(ReadinessCheckId.AccountPlan to CheckOutcome.Fail)
        controller.refresh()
        controller.requestStart()
        assertEquals(0, started)

        writeResult()
        controller.refresh()
        assertEquals(ReadinessState.Ready, controller.state)
        assertTrue(controller.canStart)
        controller.requestStart()
        assertEquals(1, started)
    }

    @Test
    fun fixClick_PageFixesNavigate_TestFixesRunTheToolInPage() {
        writeResult(
            ReadinessCheckId.AccountPlan to CheckOutcome.Fail,
            ReadinessCheckId.AudioSignal to CheckOutcome.Warning,
        )
        // Give the audio row the TestAudio fix the checks assign it.
        fixture.vm.readiness.report(
            ReadinessCheckResult(ReadinessCheckId.AudioSignal, CheckOutcome.Warning, "quiet", ReadinessFix.TestAudio),
        )
        controller.refresh()

        val routes = mutableListOf<String>()
        controller.navigateRequestedListeners += { routes += it }

        assertFalse(controller.onFixClick(row(ReadinessCheckId.AccountPlan)))
        assertEquals(listOf(ReadinessPresentation.fixRoute(ReadinessFix.OpenAccount)), routes)

        assertTrue(controller.onFixClick(row(ReadinessCheckId.AudioSignal)))
        assertEquals(Loc.t("pf_audio_title"), controller.toolTitle)
    }

    @Test
    fun runAll_SignedOutCloud_RunsTheLocalChecksAndFailsTheAccountCheck() {
        // The meeting audio "plays" while the run listens: signal frames keep arriving.
        val bumper = thread {
            var i = 0
            while (i < 40) {
                fixture.capture.framesWithSignal += 10
                Thread.sleep(100)
                i++
            }
        }
        runBlocking { controller.runAllAsync() }
        bumper.join()

        val snap = fixture.vm.readiness.snapshot
        fun outcome(id: ReadinessCheckId): CheckOutcome? = snap.get(id)?.outcome
        assertEquals(CheckOutcome.Pass, outcome(ReadinessCheckId.PlaybackDevice))
        assertEquals(CheckOutcome.Pass, outcome(ReadinessCheckId.AudioSignal))
        assertEquals(CheckOutcome.Fail, outcome(ReadinessCheckId.AccountPlan))
        assertEquals(ReadinessFix.SignIn, snap.get(ReadinessCheckId.AccountPlan)?.fix)
        assertEquals(CheckOutcome.Warning, outcome(ReadinessCheckId.AnswerEngine))
        assertEquals(CheckOutcome.Warning, outcome(ReadinessCheckId.Transcription))
        assertEquals(CheckOutcome.Fail, outcome(ReadinessCheckId.InterviewPrepared))
        assertEquals(CheckOutcome.Fail, outcome(ReadinessCheckId.PreparedAnswers))
        assertEquals(CheckOutcome.Pass, outcome(ReadinessCheckId.WindowOnTop))
        assertFalse(controller.isRunning)
        assertEquals(ReadinessState.NotReady, controller.state)
    }

    @Test
    fun onHidden_StopsABegunTool() {
        controller.onTestAudioClick()
        assertEquals(Loc.t("pf_audio_title"), controller.toolTitle)

        controller.onHidden()
        assertTrue(controller.toolResultVisible)
        assertEquals("✗ " + Loc.t("rd_tool_stopped"), controller.toolResultText)
    }
}
