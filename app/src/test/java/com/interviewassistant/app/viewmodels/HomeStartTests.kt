package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.SetupAction
import com.interviewassistant.app.uistate.StepStatus
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactSource
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.ProfileFact
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
 * The Get started dashboard: SetupPlanner states over the real facts, the acknowledgement
 * card, and the one primary action's routing (the C#'s HomeWindow.Start.cs + the SelfTest's
 * "primary action is the planner's" check).
 */
class HomeStartTests {
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

    @Test
    fun signedOut_PrimaryIsSignIn_AckCardShown_PrimaryDisabled() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("start"))
        assertEquals(SetupAction.SignIn, f.home.primaryActionForTest)
        assertEquals(Loc.t("gs_action_sign_in"), f.home.primaryActionLabel)
        assertTrue(f.home.ackCardVisible)
        assertFalse(f.home.ackNoteVisible)
        assertFalse(f.home.primaryActionEnabled) // step 1 (the acknowledgement) comes first
        assertEquals(Loc.t("start_step1_hint"), f.home.primaryActionTooltip)
        assertEquals(StepStatus.Current, f.home.dashboardSteps.first().status)
    }

    @Test
    fun ack_Persists_HidesCard_EnablesPrimary() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("start"))
        f.home.ackChecked = true
        f.home.onAckChanged(true)
        assertTrue(f.vm.settings.authorizedUseAcknowledged)
        assertFalse(f.home.ackCardVisible)
        assertTrue(f.home.ackNoteVisible)
        assertTrue(f.home.primaryActionEnabled)
        assertEquals(null, f.home.primaryActionTooltip)
    }

    @Test
    fun ackOff_PrimaryActionDoesNothing() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("start"))
        f.home.primaryActionClickAsync()
        assertEquals("start", f.home.currentPage) // still here: the click is gated by the ack
    }

    @Test
    fun signedIn_NoProfile_PrimaryIsCompleteProfile() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onAckChanged(true)
        f.home.navigateAsync(HomeRoute("start"))
        assertEquals(SetupAction.CompleteProfile, f.home.primaryActionForTest)
        assertEquals(StepStatus.Done, f.home.dashboardSteps[0].status) // account
        assertEquals(StepStatus.Current, f.home.dashboardSteps[1].status) // profile
        assertEquals(Loc.t("gs_status_done"), f.home.dashboardSteps[0].stateText)
        f.home.primaryActionClickAsync()
        assertEquals("profiles", f.home.currentPage)
        assertEquals("facts", f.home.currentProfileTab)
    }

    @Test
    fun profileWithoutConfirmedFacts_StillCompleteProfile() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.addProfile(
            facts = listOf(
                ProfileFact(kind = FactKind.Skill, text = "Pending", status = FactStatus.Source, source = FactSource.Manual),
            ),
        )
        f.home.onAckChanged(true)
        f.home.navigateAsync(HomeRoute("start"))
        assertEquals(SetupAction.CompleteProfile, f.home.primaryActionForTest)
        assertTrue(f.home.dashboardSteps[1].detail.contains("1"))
    }

    @Test
    fun confirmedFacts_NoInterview_PrimaryIsCreateInterview() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.addProfile(
            facts = listOf(
                ProfileFact(kind = FactKind.Skill, text = "Confirmed", status = FactStatus.UserConfirmed, source = FactSource.Manual),
            ),
        )
        f.home.onAckChanged(true)
        f.home.navigateAsync(HomeRoute("start"))
        assertEquals(SetupAction.CreateInterview, f.home.primaryActionForTest)
        assertEquals(StepStatus.Done, f.home.dashboardSteps[1].status)
        assertEquals(StepStatus.Current, f.home.dashboardSteps[2].status)
        f.home.primaryActionClickAsync()
        assertEquals("interviews", f.home.currentPage)
    }

    @Test
    fun interviewNotPrepared_PrimaryIsPrepare() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val p = f.addProfile(
            facts = listOf(
                ProfileFact(kind = FactKind.Skill, text = "Confirmed", status = FactStatus.UserConfirmed, source = FactSource.Manual),
            ),
        )
        f.addTarget(p.id)
        f.home.onAckChanged(true)
        f.home.navigateAsync(HomeRoute("start"))
        assertEquals(SetupAction.PrepareInterview, f.home.primaryActionForTest)
    }

    @Test
    fun developerModeEntry_HiddenInReleaseBuilds() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("start"))
        assertFalse(f.home.developerModeVisible) // BuildFlavor.Developer is false in this build
        assertFalse(f.home.developerKeyMode)
    }
}
