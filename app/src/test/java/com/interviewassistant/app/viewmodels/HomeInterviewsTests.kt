package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactSource
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.PreparationState
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Interviews editor: load/save with the title validation, dirty tracking, the prepare flow
 * (cloud job → pipeline → pack saved → readiness touched), Use and Delete (the C#'s HomeWindow
 * interview flows).
 */
class HomeInterviewsTests {
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

    private fun signedIn(): HomeFixture = signedInHomeFixture().also { fixture = it }

    /** Routes the cloud preparation-job grant the prepare flow asks for. */
    private fun grantPrepJobs(f: HomeFixture) {
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "preparation/jobs") && req.method == "POST") {
                200 to """{"jobId":"33333333-3333-3333-3333-333333333333","prepJobsRemaining":2}"""
            } else {
                base(req, body)
            }
        }
    }

    @Test
    fun stateLabel_Table() {
        assertEquals(Loc.t("state_prepared"), HomeViewModel.stateLabel(PreparationState.Prepared))
        assertEquals(Loc.t("state_preparing"), HomeViewModel.stateLabel(PreparationState.Preparing))
        assertEquals(Loc.t("state_stale"), HomeViewModel.stateLabel(PreparationState.Stale))
        assertEquals(Loc.t("state_failed"), HomeViewModel.stateLabel(PreparationState.Failed))
        assertEquals(Loc.t("state_not_prepared"), HomeViewModel.stateLabel(PreparationState.NotPrepared))
    }

    @Test
    fun load_FillsFields_GuardClean() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        assertEquals(tg.id, f.home.selectedTargetId)
        assertEquals("Senior Engineer", f.home.targetTitle)
        assertEquals("Acme", f.home.targetCompany)
        assertFalse(f.home.targetGuardForTest.isDirty)
        assertEquals(Loc.t("state_not_prepared"), f.home.prepStatusText)
    }

    @Test
    fun edit_MarksDirty() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.targetCompany = "Globex"
        assertTrue(f.home.targetGuardForTest.isDirty)
        assertEquals(Loc.t("profile_unsaved"), f.home.targetDirtyText)
    }

    @Test
    fun save_BlankTitle_ValidationError() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.targetTitle = "   "
        assertNull(f.home.saveTargetFields())
        assertTrue(f.home.targetTitleHasError)
        assertEquals(Loc.t("warn_job_title"), f.home.targetMessage.text)
    }

    @Test
    fun save_PersistsFields_AndToasts() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.targetTitle = "Staff Engineer"
        f.home.targetJd = "Build distributed systems."
        val saved = f.home.saveTargetFields()
        assertEquals(tg.id, saved!!.id)
        assertEquals("Staff Engineer", f.store().getTarget(tg.id)!!.jobTitle)
        assertFalse(f.home.targetGuardForTest.isDirty)
        f.home.onSaveTargetClick()
        assertTrue(f.toasts.any { it.first == Loc.t("toast_interview_saved") })
    }

    @Test
    fun save_ChangedPreparedInterview_BecomesStale() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        val stored = f.store().getTarget(tg.id)!!
        stored.preparation = PreparationState.Prepared
        f.store().saveTarget(stored)
        f.home.refreshTargets(tg.id, reload = true)
        f.home.targetCompany = "Globex"
        f.home.saveTargetFields()
        assertEquals(PreparationState.Stale, f.store().getTarget(tg.id)!!.preparation)
    }

    @Test
    fun newTarget_WithoutProfile_AlertsFirst() = runBlocking {
        val f = signedIn()
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.newTargetAsync()
        assertTrue(f.dialogs.alerts.any { it.first == Loc.t("warn_create_profile_first") })
    }

    @Test
    fun prepare_RunsPipeline_SavesPack_TouchesReadiness() = runBlocking {
        val f = signedIn()
        grantPrepJobs(f)
        val confirmed = ProfileFact(
            kind = FactKind.Skill, text = "Designed distributed systems", status = FactStatus.UserConfirmed,
            source = FactSource.Manual,
        )
        val role = ProfileFact(
            kind = FactKind.Role, text = "", title = "Senior Engineer", company = "Acme",
            period = "2020 - 2024", status = FactStatus.UserConfirmed, source = FactSource.Manual,
        )
        val p = f.addProfile(facts = listOf(confirmed, role))
        val tg = f.addTarget(p.id)
        val stored = f.store().getTarget(tg.id)!!
        stored.jobDescriptionText = "Senior Engineer. Build distributed systems in Kotlin."
        f.store().saveTarget(stored)
        f.home.navigateAsync(HomeRoute("interviews"))
        val readinessContextBefore = f.vm.readinessContext
        f.home.prepareAsync()
        val saved = f.store().getTarget(tg.id)!!
        assertEquals(PreparationState.Prepared, saved.preparation)
        assertNotNull(f.store().getPack(tg.id))
        assertEquals(Loc.t("state_prepared"), f.home.prepStatusText)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_prep_complete") })
        // The completed preparation invalidated readiness: the revision bumped, so the
        // readiness context (owner + target + device + revision) no longer matches —
        // the observable effect of InvalidateReadiness (Touch republishes the same
        // snapshot instance, exactly as in C#; with no checks run, the state stays
        // NotChecked, so the context is the faithful signal).
        assertTrue(f.vm.readinessContext != readinessContextBefore)
    }

    @Test
    fun prepare_NoConfirmedFacts_DeclinedConfirm_Aborts() = runBlocking {
        val f = signedIn()
        grantPrepJobs(f)
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.dialogs.confirmAnswer = false
        f.home.prepareAsync()
        assertEquals(PreparationState.NotPrepared, f.store().getTarget(tg.id)!!.preparation)
        assertNull(f.store().getPack(tg.id))
    }

    @Test
    fun prepare_PrepLimit_AlertsWithTheLimitCopy() = runBlocking {
        val f = signedIn()
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "preparation/jobs") && req.method == "POST") {
                403 to """{"code":"prep_limit","message":"limit"}"""
            } else {
                base(req, body)
            }
        }
        val confirmed = ProfileFact(
            kind = FactKind.Skill, text = "Designed distributed systems", status = FactStatus.UserConfirmed,
            source = FactSource.Manual,
        )
        val p = f.addProfile(facts = listOf(confirmed))
        f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.prepareAsync()
        assertTrue(f.dialogs.alerts.any { it.first == Loc.t("warn_prep_limit") })
    }

    @Test
    fun useTarget_MakesItActive_AndOpensLive() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.useTargetAsync()
        assertEquals(tg.id, f.vm.activeTargetId)
        assertTrue(f.shell.liveShown >= 1 || f.shell.liveToasts.any { it.first == Loc.f("toast_live_opened", tg.displayName) })
    }

    @Test
    fun deleteTarget_Confirm_Deletes() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        f.dialogs.confirmAnswer = true
        f.home.deleteTargetAsync()
        assertNull(f.store().getTarget(tg.id))
        assertTrue(f.toasts.any { it.first == Loc.t("toast_interview_deleted") })
    }

    @Test
    fun importJd_FillsThePendingDescription() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "preparation/jobs")) 200 to """{"jobId":"33333333-3333-3333-3333-333333333333","prepJobsRemaining":2}"""
            else base(req, body)
        }
        f.files.picked = FakePickedFile("jd.txt", "Senior Engineer\nWe are hiring a Kotlin engineer.".toByteArray())
        f.home.importJdAsync()
        assertTrue(f.home.targetJd.contains("Kotlin"))
        assertTrue(f.home.targetGuardForTest.isDirty) // a pending edit, not yet saved
    }
}
