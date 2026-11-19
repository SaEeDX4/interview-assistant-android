package com.interviewassistant.app.ui.live

import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test

/**
 * P4-G: the Live chrome's pure decisions that are NOT already covered by the P4-A LiveChrome
 * suite (Rc5LiveWindowTests): the readiness button's tooltip rule from MainWindow.Chrome.cs —
 * before a first (or a fresh) result the tooltip invites running the checks; afterwards it
 * carries the pre-interview summary.
 */
class LiveScreenLogicTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    @Test
    fun readinessTooltip_BeforeAResult_InvitesRunningTheChecks() {
        for (state in listOf(ReadinessState.NotChecked, ReadinessState.Outdated)) {
            val tip = liveReadinessTooltip(state, "the-summary")
            assertEquals(Loc.f("live_rd_tt_run", Loc.t(ReadinessSnapshot.stateKey(state))), tip)
            assertFalse(tip.contains("the-summary"))
        }
    }

    @Test
    fun readinessTooltip_WithAResult_CarriesTheSummary() {
        for (state in listOf(
            ReadinessState.Checking,
            ReadinessState.Ready,
            ReadinessState.ReadyWithWarnings,
            ReadinessState.NotReady,
        )) {
            val tip = liveReadinessTooltip(state, "the-summary")
            assertEquals(Loc.f("live_rd_tt", Loc.t(ReadinessSnapshot.stateKey(state)), "the-summary"), tip)
        }
    }
}
