package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.CheckOutcome
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.ReadinessCheckId
import com.interviewassistant.app.uistate.ReadinessCheckResult
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * The readiness surfaces — footer, Interviews card, sidebar pill, Get started step — all render
 * the ONE snapshot in the main view model (the C#'s HomeWindow.Readiness.cs glue).
 */
class HomeReadinessTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private var fixture: HomeFixture? = null

    @Before
    fun setUp() {
        Loc.language = "en"
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        Loc.language = "en"
    }

    private fun reportAllPassed(f: HomeFixture) {
        val store = f.vm.readiness
        store.begin(f.vm.readinessContext)
        for (id in ReadinessCheckId.entries) {
            store.report(ReadinessCheckResult(id, CheckOutcome.Pass))
        }
    }

    @Test
    fun notChecked_PillAndFooterSaySo_StartNeedsACheckFirst() {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.refreshReadinessSurfaces()
        assertEquals(Loc.t("rd_state_not_checked"), f.home.pillText)
        assertEquals("B.TextFaint", f.home.pillBrushKey)
        assertEquals(Loc.t("rd_hint_not_checked"), f.home.pillTooltip)
        assertTrue(f.home.readinessFooterText.startsWith(Loc.t("rd_state_not_checked")))
        assertEquals(Loc.t("tt_pf_start_run_first"), f.home.pfStartTooltip)
        assertFalse(f.home.pfStartEnabled)
        assertEquals(Loc.t("rd_run_check"), f.home.interviewCheckText) // auto-run state offers "Run check"
    }

    @Test
    fun allPassed_EverySurfaceShowsReadyFromTheOneSnapshot() {
        val f = signedInHomeFixture().also { fixture = it }
        reportAllPassed(f)
        f.home.refreshReadinessSurfaces()
        assertEquals(Loc.t("rd_state_ready"), f.home.pillText)
        assertEquals("B.Listening", f.home.pillBrushKey)
        assertEquals("B.Listening", f.home.readinessFooterBrushKey)
        assertEquals(Loc.t("rd_state_ready"), f.home.ivBadgeText)
        assertTrue(f.home.readinessFooterText.contains(f.home.summaryText()))
        assertEquals(Loc.f("rd_pill_name", Loc.t("rd_state_ready")), f.home.pillName)
        assertEquals(Loc.t("rd_open_check"), f.home.interviewCheckText) // checked: the button opens the page
    }

    @Test
    fun pageStateChanged_ReRendersTheSurfaces() {
        val f = signedInHomeFixture().also { fixture = it }
        reportAllPassed(f)
        f.preInterview.fireStateChanged() // the page rendered → StateChanged → surfaces refresh
        assertEquals(Loc.t("rd_state_ready"), f.home.pillText)
    }

    @Test
    fun pillClick_OpensTheCheckPage() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onReadinessPillClick()
        assertEquals("preinterview", f.home.currentPage)
        assertTrue(f.preInterview.shownCalls >= 1)
        f.home.navigateAsync(HomeRoute("start"))
        assertTrue(f.preInterview.hiddenCalls >= 1) // leaving the page hides it, as in the C#
    }

    @Test
    fun pageNavigationRequest_RoutesThroughHome() {
        val f = signedInHomeFixture().also { fixture = it }
        f.preInterview.fireNavigate("settings/audio")
        assertEquals("settings", f.home.currentPage)
        assertEquals("audio", f.settingsPage.shownSections.last())
    }

    @Test
    fun pfButtons_RunAndSettings() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onPfRunClick()
        assertEquals(1, f.preInterview.runAllCalls)
        f.home.onPfSettingsClick()
        assertEquals("settings", f.home.currentPage)
        assertEquals("audio", f.settingsPage.shownSections.last())
    }

    @Test
    fun startInterview_ClosesHome_AndStartsListening() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onAckChanged(true)
        f.home.startInterviewAsync()
        assertEquals(1, f.shell.liveShown)
        assertEquals(1, f.shell.closeCalls)
        assertTrue(f.vm.settings.firstRunCompleted) // reaching the interview completes the first run
    }

    @Test
    fun startRequestedFromPage_StartsTheInterview() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.preInterview.requestStart()
        assertEquals(1, f.shell.closeCalls)
    }
}
