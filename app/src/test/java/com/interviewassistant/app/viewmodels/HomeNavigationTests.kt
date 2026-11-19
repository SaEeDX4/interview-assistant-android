package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.LeaveDecision
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
 * Home navigation, the signed-out gate and the one NavigationGuard's decisions (the C#'s
 * HomeWindow navigation + guard wiring; the SelfTest's clean-at-start check included).
 */
class HomeNavigationTests {
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

    private fun signedIn(initial: HomeRoute? = null): HomeFixture =
        signedInHomeFixture(initial).also { fixture = it }

    private fun signedOut(initial: HomeRoute? = null): HomeFixture =
        signedOutHomeFixture(initial).also { fixture = it }

    @Test
    fun opensOnStart_CleanAtStart() {
        val f = signedOut()
        assertEquals("start", f.home.currentPage)
        // SelfTest: Home opens with no unsaved edits anywhere.
        assertFalse(f.home.hasUnsavedEdits)
    }

    @Test
    fun signedOutGate_DataPagesGoToAccount() = runBlocking {
        val f = signedOut()
        assertTrue(f.home.navigateAsync(HomeRoute("profiles")))
        assertEquals("account", f.home.currentPage)
        assertTrue(f.home.navigateAsync(HomeRoute("reports")))
        assertEquals("account", f.home.currentPage)
        assertFalse(f.home.navDataPagesVisible)
    }

    @Test
    fun signedIn_DataPagesOpen() = runBlocking {
        val f = signedIn()
        assertTrue(f.home.navigateAsync(HomeRoute("profiles")))
        assertEquals("profiles", f.home.currentPage)
        assertTrue(f.home.navDataPagesVisible)
    }

    @Test
    fun navigate_NoDirtyGuards_NeverAsks() = runBlocking {
        val f = signedIn()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.navigateAsync(HomeRoute("interviews"))
        f.home.navigateAsync(HomeRoute("help"))
        f.home.navigateAsync(HomeRoute("start"))
        assertTrue(f.dialogs.unsavedCalls.isEmpty())
    }

    @Test
    fun guard_Stay_BlocksNavigation() = runBlocking {
        val f = signedIn()
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "Edited Name" // a pending edit
        assertTrue(f.home.profileGuardForTest.isDirty)
        f.dialogs.unsavedAnswer = LeaveDecision.Stay
        assertFalse(f.home.navigateAsync(HomeRoute("interviews")))
        assertEquals("profiles", f.home.currentPage)
        assertTrue(f.home.profileGuardForTest.isDirty)
    }

    @Test
    fun guard_Discard_AllowsAndReloadsTheDraft() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Discard
        assertTrue(f.home.navigateAsync(HomeRoute("interviews")))
        assertEquals("interviews", f.home.currentPage)
        assertFalse(f.home.profileGuardForTest.isDirty)
        // The discard reloaded the saved profile: the draft is gone.
        assertEquals(p.name, f.home.profileName)
    }

    @Test
    fun guard_Save_AllowsAndPersists() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Save
        assertTrue(f.home.navigateAsync(HomeRoute("interviews")))
        assertFalse(f.home.profileGuardForTest.isDirty)
        assertEquals("Edited Name", f.store().getProfile(p.id)!!.name)
    }

    @Test
    fun guard_AskTitleNamesTheEditor() = runBlocking {
        val f = signedIn()
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Stay
        f.home.navigateAsync(HomeRoute("interviews"))
        assertEquals(listOf(Loc.t("dlg_unsaved_profile")), f.dialogs.unsavedCalls)
    }

    @Test
    fun settingsGuard_SaveFailure_KeepsTheUserOnSettings() = runBlocking {
        val f = signedIn()
        f.home.navigateAsync(HomeRoute("settings"))
        assertEquals("settings", f.home.currentPage)
        f.settingsPage.guard.markDirty()
        f.settingsPage.saveResult = false
        f.dialogs.unsavedAnswer = LeaveDecision.Save
        assertFalse(f.home.navigateAsync(HomeRoute("start")))
        assertEquals("settings", f.home.currentPage)
        // The failed save shows the section with the problem (hotkeys, as in the C#).
        assertEquals("hotkeys", f.settingsPage.shownSections.last())
    }

    @Test
    fun settingsGuard_SaveSuccess_Allows() = runBlocking {
        val f = signedIn()
        f.home.navigateAsync(HomeRoute("settings"))
        f.settingsPage.guard.markDirty()
        f.settingsPage.saveResult = true
        f.dialogs.unsavedAnswer = LeaveDecision.Save
        assertTrue(f.home.navigateAsync(HomeRoute("start")))
        assertEquals(1, f.settingsPage.saveCalls)
    }

    @Test
    fun closing_Clean_SavesPlacementAndCloses() {
        val f = signedIn()
        assertTrue(f.home.onClosing())
        assertTrue(f.vm.settings.homeMaximized == false)
        assertEquals(10.0, f.vm.settings.homeLeft!!, 0.001)
        assertEquals(900.0, f.vm.settings.homeWidth, 0.001)
    }

    @Test
    fun closing_Dirty_Stay_DoesNotClose() {
        val f = signedIn()
        f.addProfile()
        runBlocking { f.home.navigateAsync(HomeRoute("profiles")) }
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Stay
        assertFalse(f.home.onClosing())
        assertEquals(0, f.shell.closeCalls)
    }

    @Test
    fun closing_Dirty_Discard_Closes() {
        val f = signedIn()
        f.addProfile()
        runBlocking { f.home.navigateAsync(HomeRoute("profiles")) }
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Discard
        assertFalse(f.home.onClosing()) // the close is deferred to the guard flow, as in the C#
        assertEquals(1, f.shell.closeCalls)
    }

    @Test
    fun closing_DialogOpen_CancelsTheClose() {
        val f = signedIn()
        f.dialogs.isOpen = true
        assertFalse(f.home.onClosing())
    }

    @Test
    fun accountMenuButton_SignedOut_OpensAccount() {
        val f = signedOut()
        f.home.onAccountMenuButtonClick()
        assertEquals("account", f.home.currentPage)
    }

    @Test
    fun accountMenu_SignedIn_OpensAndMoves() {
        val f = signedIn()
        f.home.onAccountMenuButtonClick()
        assertTrue(f.home.accountMenuOpen)
        assertEquals(0, f.home.accountMenuFocusIndex)
        assertTrue(f.home.onAccountMenuKey(AccountMenuKey.Down, 0))
        assertEquals(1, f.home.accountMenuFocusIndex)
        assertTrue(f.home.onAccountMenuKey(AccountMenuKey.End, 1))
        assertEquals(3, f.home.accountMenuFocusIndex)
        assertTrue(f.home.onAccountMenuKey(AccountMenuKey.Escape, 3))
        assertFalse(f.home.accountMenuOpen)
    }
}
