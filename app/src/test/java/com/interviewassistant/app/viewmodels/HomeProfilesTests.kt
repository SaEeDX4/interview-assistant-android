package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactSource
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.PersonalCategories
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
 * The Profiles editor: load/select under the suspended guard, edit → dirty, save, the fact
 * filters and ConfirmAll, personal fields generated from the real schema, delete with confirm,
 * the plan's profile limit (the C#'s HomeWindow profile flows).
 */
class HomeProfilesTests {
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

    private fun fact(text: String, status: FactStatus) = ProfileFact(
        kind = FactKind.Skill, text = text, status = status, source = FactSource.Manual,
        createdUtc = fixture!!.nowUtc, updatedUtc = fixture!!.nowUtc,
    )

    @Test
    fun load_SelectsProfile_FillsFields_GuardClean() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        assertEquals(p.id, f.home.editedProfileId)
        assertEquals("Ada Lovelace", f.home.profileName)
        assertFalse(f.home.profileGuardForTest.isDirty) // loading never dirties (suspended guard)
        assertEquals("", f.home.profileDirtyText)
    }

    @Test
    fun edit_MarksDirty_AndShowsTheUnsavedIndicator() = runBlocking {
        val f = signedIn()
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileHeadline = "Staff Engineer"
        assertTrue(f.home.profileGuardForTest.isDirty)
        assertEquals(Loc.t("profile_unsaved"), f.home.profileDirtyText)
    }

    @Test
    fun save_Persists_Trims_AndConfirmsFacts() = runBlocking {
        val f = signedIn()
        val pending = fact("Led a team of five", FactStatus.Source)
        val p = f.addProfile(facts = listOf(pending))
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "  Ada L.  "
        val row = f.home.facts.single()
        row.confirmed = true // tick the fact
        assertTrue(f.home.saveProfile())
        val saved = f.store().getProfile(p.id)!!
        assertEquals("Ada L.", saved.name) // trimmed
        assertEquals(FactStatus.UserConfirmed, saved.facts.single().status)
        assertFalse(f.home.profileGuardForTest.isDirty)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_profile_saved") })
    }

    @Test
    fun factFilters_AllReviewConfirmed() = runBlocking {
        val f = signedIn()
        f.addProfile(
            facts = listOf(
                fact("Confirmed skill", FactStatus.UserConfirmed),
                fact("Pending skill", FactStatus.Source),
            ),
        )
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.onFactFilterClick("all")
        assertEquals(2, f.home.visibleFacts.size)
        f.home.onFactFilterClick("review")
        assertEquals(listOf("Pending skill"), f.home.visibleFacts.map { it.fact.text })
        f.home.onFactFilterClick("confirmed")
        assertEquals(listOf("Confirmed skill"), f.home.visibleFacts.map { it.fact.text })
        // Unconfirmed first in every view (the C# orders the list by Confirmed ascending).
        f.home.onFactFilterClick("all")
        assertEquals("Pending skill", f.home.visibleFacts.first().fact.text)
    }

    @Test
    fun confirmAll_TicksEveryPendingFact() = runBlocking {
        val f = signedIn()
        f.addProfile(
            facts = listOf(
                fact("One", FactStatus.Source),
                fact("Two", FactStatus.AiInference),
            ),
        )
        f.home.navigateAsync(HomeRoute("profiles"))
        f.dialogs.confirmAnswer = true
        f.home.confirmAllAsync()
        assertTrue(f.home.facts.all { it.confirmed })
        assertTrue(f.toasts.any { it.first == Loc.f("toast_facts_ticked", 2) })
    }

    @Test
    fun confirmAll_NothingPending_ReportsAllConfirmed() = runBlocking {
        val f = signedIn()
        f.addProfile(facts = listOf(fact("Done", FactStatus.UserConfirmed)))
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.confirmAllAsync()
        assertTrue(f.toasts.any { it.first == Loc.t("toast_all_confirmed") })
    }

    @Test
    fun personalFields_AreGeneratedFromTheRealSchema() = runBlocking {
        val f = signedIn()
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        val fields = f.home.personalSections.flatMap { it.fields }
        assertEquals(PersonalCategories.all.size, fields.size)
        assertEquals(PersonalCategories.all.map { it.key }, fields.map { it.key })
        assertTrue(fields.all { it.maxLength == 600 && it.multiline })
        // Section headings and labels are the schema's localization keys.
        val first = PersonalCategories.all.first()
        assertEquals("pcs_${first.section}", f.home.personalSections.first().headingKey)
        assertEquals("pc_${first.key}_label", fields.first().labelKey)
    }

    @Test
    fun personalText_EditMarksDirty_SavePersists() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        val key = PersonalCategories.all.first().key
        f.home.setPersonalText(key, "I love distributed systems")
        assertTrue(f.home.profileGuardForTest.isDirty)
        assertTrue(f.home.saveProfile())
        val saved = f.store().getProfile(p.id)!!
        assertTrue(saved.facts.any { it.category == key && it.text == "I love distributed systems" })
    }

    @Test
    fun addFact_ViaEditor_AddsManualFactAndDirties() = runBlocking {
        val f = signedIn()
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.dialogs.factResult = FactEditResult(FactKind.Skill, "Built a compiler", "", "", "", true)
        f.home.addFactAsync()
        assertTrue(f.home.facts.any { it.fact.text == "Built a compiler" })
        assertTrue(f.home.profileGuardForTest.isDirty)
    }

    @Test
    fun deleteFact_Confirm_Removes() = runBlocking {
        val f = signedIn()
        f.addProfile(facts = listOf(fact("Delete me", FactStatus.Source)))
        f.home.navigateAsync(HomeRoute("profiles"))
        f.dialogs.confirmAnswer = true
        f.home.deleteFactAsync(f.home.facts.single())
        assertTrue(f.home.facts.isEmpty())
    }

    @Test
    fun deleteProfile_Confirm_DeletesAndToasts() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.dialogs.confirmAnswer = true
        f.home.deleteProfileAsync()
        assertTrue(f.store().profiles().none { it.id == p.id })
        assertTrue(f.toasts.any { it.first == Loc.t("toast_profile_deleted") })
    }

    @Test
    fun deleteProfile_Cancel_KeepsEverything() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.dialogs.confirmAnswer = false
        f.home.deleteProfileAsync()
        assertTrue(f.store().profiles().any { it.id == p.id })
    }

    @Test
    fun newEmptyProfile_CreatesWithTheDefaultName() = runBlocking {
        val f = signedIn()
        f.home.newEmptyProfileAsync()
        val created = f.store().profiles().single()
        assertEquals(Loc.t("profile_new_name"), created.name)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_profile_created") })
    }

    @Test
    fun profileLimit_SecondProfile_RefusedWithThePlanLimit() = runBlocking {
        val f = signedIn() // the fixture account allows maxProfiles = 1
        f.addProfile()
        f.home.newEmptyProfileAsync()
        assertEquals(1, f.store().profiles().size)
        assertTrue(f.dialogs.alerts.any { it.first == Loc.f("warn_profile_limit", 1) })
    }

    @Test
    fun importResume_TextFile_CreatesTheProfile() = runBlocking {
        val f = signedIn()
        f.files.picked = FakePickedFile(
            "cv.txt",
            "Ada Lovelace\nSenior Engineer at Acme Corp 2020 - 2024\nSkills: Kotlin, distributed systems\n".toByteArray(),
        )
        f.home.importResumeAsync(newProfile = true)
        assertEquals(1, f.store().profiles().size)
        assertEquals("profiles", f.home.currentPage)
    }

    @Test
    fun importResume_OversizedFile_RefusedBeforeReading() = runBlocking {
        val f = signedIn()
        f.files.picked = FakePickedFile("big.pdf", ByteArray(0), sizeBytes = 11L * 1024 * 1024)
        f.home.importResumeAsync(newProfile = true)
        assertTrue(f.store().profiles().isEmpty())
        assertTrue(f.dialogs.alerts.any { it.first == Loc.t("warn_file_too_large") })
    }
}
