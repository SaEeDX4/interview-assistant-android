package com.interviewassistant.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.ui.components.AppDialog
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.ExportChoiceState
import com.interviewassistant.app.ui.components.ExportDialogLogic
import com.interviewassistant.app.ui.components.InfoTipNaming
import com.interviewassistant.app.ui.components.InlineMessageStyle
import com.interviewassistant.app.ui.components.LevelMeterValue
import com.interviewassistant.app.ui.components.RovingKey
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.app.viewmodels.MessageKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The component-layer counterparts of the Windows `Rc5AccessibilityTests.cs` (the P4-A deferral). The
 * Windows file parses XAML for WPF-specific failure modes (peerless containers, inert live settings); on
 * Android those failure modes do not exist — Compose semantics are the announcement mechanism — so each
 * Windows test maps to the rule it protects, asserted against the component logic:
 * - LiveRegions_UseTheAnnouncingAttachedProperty → liveRegions below (live-region modes are real semantics
 *   on every announcing component, errors assertive).
 * - FocusableOrNamedContainers_HaveAnAutomationPeer → every named component carries semantics; the naming
 *   rules are asserted in infoTips below and in DialogFormsTest/ToastControllerTest.
 * - StyledFocusableBorders_AreAccessibleBorders → DEFERRED BY NAME to P4-E (Home step cards).
 * - AudioMeters_AreProgressBarsForScreenReaders → levelMeter below (the component); the page meters are
 *   DEFERRED BY NAME to P4-F (PreInterview) and P4-G (Live/Settings).
 * - LiveReadinessIndicator_IsAnIcon_NotAColourOnlyDot → DEFERRED BY NAME to P4-G (Live chrome).
 * - ToggleMenuRows_AnnounceTheirState_AndInPlaceActionsKeepFocus → DEFERRED BY NAME to P4-G (More menu).
 * - RadioGroups_AreOneTabStop_WithArrowSelection → radioGroups below (ExportChoiceList); the Interview
 *   Focus presets half is DEFERRED BY NAME to P4-G.
 * - InfoTips_NameTheFieldTheyExplain → infoTips below.
 * Compose rendering assertions (semantics trees of the rendered components) need an emulator and are NOT
 * run in CI — see the P4-D section of P4-PORT-NOTES.md.
 */
class Rc5ComponentAccessibilityTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() {
            UiStateLocFixtures.installLocTables()
            Loc.language = "en"
        }
    }

    @Test
    fun liveRegions_ErrorsAreAssertive_OnEveryAnnouncingComponent() {
        val toasts = ToastController()
        toasts.show("failed", ToastKind.Error, nowMs = 0)
        toasts.show("saved", ToastKind.Success, nowMs = 1)
        assertTrue(toasts.visible(2).first { it.kind == ToastKind.Error }.assertive)
        assertTrue(!toasts.visible(2).first { it.kind == ToastKind.Success }.assertive)
        assertEquals(LiveRegionMode.Assertive, InlineMessageStyle.liveMode(MessageKind.Error))
        assertEquals(LiveRegionMode.Polite, InlineMessageStyle.liveMode(MessageKind.Warning))
        assertEquals(LiveRegionMode.Polite, InlineMessageStyle.liveMode(MessageKind.Success))
    }

    @Test
    fun radioGroups_TheCheckedOptionIsTheOneEntry_ArrowsMoveSelectionAndFocusTogether() {
        val state = ExportChoiceState(ExportDialogLogic.profileChoices(), selectedKey = "backup")
        assertEquals(1, state.selectedIndex) // the checked option is the group's entry point (SelectedButton)
        state.onArrowKey(RovingKey.Down, rtl = false)
        assertEquals(2, state.selectedIndex)
        state.onArrowKey(RovingKey.Down, rtl = false)
        assertEquals(0, state.selectedIndex) // wraps, like a native radio group
    }

    @Test
    fun infoTips_NameTheFieldTheyExplain() {
        val more = Loc.t("info_more")
        assertEquals(more, InfoTipNaming.name(more, ""))
        assertEquals("$more: ${Loc.t("password_new")}", InfoTipNaming.name(more, Loc.t("password_new")))
    }

    @Test
    fun dialogs_InitialFocusIsTheSafeDefault() { runBlocking {
        // non-destructive confirm: focus starts on the confirm action (index 1)
        val c1 = DialogController()
        val a1 = async { AppDialog.confirm(c1, "Proceed?", "Go") }
        repeat(100) { if (c1.topLayer != null) return@repeat; delay(10) }
        assertEquals(1, c1.initialFocusIndex(c1.topLayer!!))
        c1.closeOwner(); a1.await()
        // destructive confirm: focus starts on Cancel (index 0) — Enter never destroys by accident
        val c2 = DialogController()
        val a2 = async { AppDialog.confirm(c2, "Delete?", "Delete", destructive = true) }
        repeat(100) { if (c2.topLayer != null) return@repeat; delay(10) }
        assertEquals(0, c2.initialFocusIndex(c2.topLayer!!))
        c2.closeOwner(); a2.await()
    } }

    @Test
    fun levelMeter_TheScreenReaderValueStaysAReadOnlyPercent() {
        for (level in listOf(-1.0, 0.0, 0.25, 0.5, 0.75, 1.0, 2.0)) {
            val v = LevelMeterValue.percent(level)
            assertTrue("percent $v out of range for level $level", v in 0..100)
        }
        assertEquals(100, LevelMeterValue.percent(0.995))
        assertEquals(0, LevelMeterValue.percent(0.004))
    }
}
