package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.LanguageRegistry
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
 * In-place relocalization (the §7 exit-gate behavior): a language switch re-renders every text
 * but never touches the page, the selection, a draft being typed or its dirty flag — Persian
 * (RTL) included.
 */
class HomeRelocalizationTests {
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
    fun dirtyProfileDraft_SurvivesLanguageSwitch_IncludingPersianRtl() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        // A draft being typed, in Persian, with the guard dirty.
        f.home.profileName = "نام ویرایش‌شده"
        f.home.profileHeadline = "عنوان آزمایشی"
        assertTrue(f.home.profileGuardForTest.isDirty)

        Loc.language = "fa" // the switch relocalizes Home in place (Loc listener)

        assertTrue(LanguageRegistry.isRtl("fa"))
        assertTrue(Loc.isRtl)
        assertEquals("profiles", f.home.currentPage) // same page
        assertEquals("نام ویرایش‌شده", f.home.profileName) // the draft is untouched
        assertEquals("عنوان آزمایشی", f.home.profileHeadline)
        assertTrue(f.home.profileGuardForTest.isDirty) // and still unsaved
        // Nothing asked about the unsaved draft: a language change never prompts.
        assertTrue(f.dialogs.unsavedCalls.isEmpty())
        // The dirty indicator re-rendered in the new language.
        assertEquals(Loc.t("profile_unsaved"), f.home.profileDirtyText)
    }

    @Test
    fun languageSwitch_RebuildsPickers_KeepsInterviewLanguageSelections() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.home.navigateAsync(HomeRoute("interviews"))
        assertEquals(tg.id, f.home.selectedTargetId)
        f.home.targetLanguageCode = "fa"
        f.home.targetAnswerLanguageCode = "fa"
        assertTrue(f.home.targetGuardForTest.isDirty)

        Loc.language = "de"

        // The pickers were rebuilt (auto/same first, then every language) and the selections kept.
        assertEquals("auto", f.home.interviewLanguageItems.first().code)
        assertEquals("same", f.home.answerLanguageItems.first().code)
        assertEquals(LanguageRegistry.all.size + 1, f.home.interviewLanguageItems.size)
        assertEquals("fa", f.home.targetLanguageCode)
        assertEquals("fa", f.home.targetAnswerLanguageCode)
        assertTrue(f.home.targetGuardForTest.isDirty)
    }

    @Test
    fun languageSwitch_RerendersListsAndDashboard() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.addProfile()
        f.home.navigateAsync(HomeRoute("start"))
        val englishStep = f.home.dashboardSteps.first().stateText
        val englishLabel = f.home.profileItems.first().label
        Loc.language = "fa"
        assertEquals("start", f.home.currentPage)
        assertEquals(Loc.t("gs_status_done"), f.home.dashboardSteps.first().stateText)
        assertTrue(englishStep.isNotEmpty())
        // The profile list label was rebuilt in the new language (the name itself is data, unchanged).
        val persianLabel = f.home.profileItems.first().label
        assertTrue(persianLabel.contains("Ada Lovelace"))
        assertFalse(englishLabel == persianLabel)
    }

    @Test
    fun uiLanguageChange_PersistsAndRefreshes() {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onUiLanguageChanged("fa")
        assertEquals("fa", f.vm.settings.uiLanguage)
        assertEquals("fa", Loc.language)
        assertEquals("fa", f.home.uiLanguageCode)
    }
}
