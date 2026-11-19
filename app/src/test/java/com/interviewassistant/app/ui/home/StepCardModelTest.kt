package com.interviewassistant.app.ui.home

import com.interviewassistant.app.uistate.SetupStep
import com.interviewassistant.app.uistate.StepStatus
import com.interviewassistant.app.viewmodels.DashboardStep
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Get started step-card model (Package P4-E): the culture-formatted step number, the
 * accessible name the Rc5 accessibility counterpart requires ("{n}. {title}: {state}. {detail}"),
 * and which marker the card shows. Pure string/state mapping — the card's rendering is Compose.
 */
class StepCardModelTest {

    @Test
    fun stepNumber_isOneBased() {
        assertEquals("1", stepNumberText(0, Locale.US))
        assertEquals("2", stepNumberText(1, Locale.US))
        assertEquals("4", stepNumberText(3, Locale.US))
    }

    @Test
    fun stepNumber_usesTheCulturesDigits() {
        // Persian (the UI language of the rc.5 RTL pass) formats integers with Persian digits
        // (Extended Arabic-Indic, U+06F0..U+06F9).
        assertEquals("\u06F1", stepNumberText(0, Locale.forLanguageTag("fa-IR")))
        assertEquals("\u06F4", stepNumberText(3, Locale.forLanguageTag("fa-IR")))
    }

    @Test
    fun accessibleName_isNumberLabelValueDetail() {
        assertEquals(
            "2. Profile: Current step. Add your résumé.",
            stepCardAccessibleName("2", "Profile: Current step", "Add your résumé."),
        )
        assertEquals(
            "1. Account: Done. Signed in.",
            stepCardAccessibleName("1", "Account: Done", "Signed in."),
        )
    }

    @Test
    fun mark_showsCheckOnlyWhenDone() {
        val done = DashboardStep(SetupStep.Account, StepStatus.Done, "Done", "d")
        val current = DashboardStep(SetupStep.Profile, StepStatus.Current, "Current", "d")
        val upcoming = DashboardStep(SetupStep.InterviewSetup, StepStatus.Upcoming, "Upcoming", "d")
        assertTrue(stepMarkShowsCheck(done))
        assertFalse(stepMarkShowsCheck(current))
        assertFalse(stepMarkShowsCheck(upcoming))
    }

    @Test
    fun titleKeys_matchTheOriginalsBindings() {
        assertEquals("account", stepTitleKey(SetupStep.Account))
        assertEquals("gs_step_profile", stepTitleKey(SetupStep.Profile))
        assertEquals("gs_step_interview", stepTitleKey(SetupStep.InterviewSetup))
        assertEquals("preflight", stepTitleKey(SetupStep.PreInterviewCheck))
    }
}
