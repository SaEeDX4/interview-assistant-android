package com.interviewassistant.app.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-G: the Live answer auto-fit (MainWindow's `ScheduleFit` / `FitAnswer`). This suite closes the
 * last deferred Rc5 item, `AutoFit_NeverRefitsTheSameInputs` (Rc5ReadinessTests): the C# test is a
 * source scan (WPF measuring is not unit-testable there); here the fit decisions are a pure class,
 * so the same guarantee is pinned behaviorally — stronger than the scan — plus the full pass
 * sequence the C# runs (preferred → compact spacing → proportional size → −0.5 steps, ≤ 10).
 */
class AnswerFitterTests {

    private fun key(
        viewport: Double = 300.0,
        outerWidth: Double = 800.0,
        question: String = "Tell me about yourself.",
        note: String? = null,
        isCoachView: Boolean = false,
        isProfileGap: Boolean = false,
        isThinking: Boolean = false,
        pending: String = "",
        coachKeywords: String = "",
        coachStructure: String = "",
        coachReminder: String? = null,
        fontPreset: String = "Large",
        bullets: List<String> = listOf("One", "Two"),
    ): String = AnswerFitter.key(
        viewport, outerWidth, question, note, isCoachView, isProfileGap, isThinking, pending,
        coachKeywords, coachStructure, coachReminder, fontPreset, bullets,
    )

    @Test
    fun autoFit_NeverRefitsTheSameInputs() {
        val fitter = AnswerFitter()
        val k = key()

        assertTrue(fitter.begin(k)) // the first fit for these inputs runs
        assertEquals(k, fitter.lastFitKey) // the key is committed before any pass
        assertEquals(FitAction.SetCompact(true), fitter.measured(k, false, 300.0, 500.0, 19.0, 13.0))
        assertTrue(!fitter.begin(k)) // the same inputs never fit again — even mid-fit

        assertNull(fitter.measured(k, true, 300.0, 250.0, 19.0, 13.0)) // fits: done
        assertTrue(!fitter.begin(k)) // ... and not after the fit completed either

        assertTrue(fitter.begin(key(question = "A different question"))) // new inputs fit again
    }

    @Test
    fun fit_PassesThroughCompactThenProportionalThenHalfPointSteps() {
        val fitter = AnswerFitter()
        val k = key()
        assertTrue(fitter.begin(k))

        // Preferred size/spacing does not fit → compact spacing.
        assertEquals(FitAction.SetCompact(true), fitter.measured(k, false, 100.0, 400.0, 20.0, 13.0))
        // Compact does not fit either → one proportional step: 20 × √(100/400) × 0.98 = 9.8.
        val step = fitter.measured(k, false, 100.0, 400.0, 20.0, 13.0)
        assertTrue(step is FitAction.SetFontSize)
        assertEquals(9.8, (step as FitAction.SetFontSize).size!!, 1e-9)
        // Then −0.5 steps while it still does not fit.
        assertEquals(FitAction.SetFontSize(19.5), fitter.measured(k, false, 100.0, 400.0, 20.0, 13.0))
        assertEquals(FitAction.SetFontSize(19.0), fitter.measured(k, false, 100.0, 400.0, 19.5, 13.0))
    }

    @Test
    fun fit_StopsAtTheReadableMinimum_AndAfterTenSteps() {
        val fitter = AnswerFitter()
        val k = key()
        fitter.begin(k)
        fitter.measured(k, false, 100.0, 400.0, 20.0, 13.0) // → compact
        fitter.measured(k, false, 100.0, 400.0, 20.0, 13.0) // → proportional
        // At the minimum already: no further shrink, the fit ends (scrolling takes over).
        assertNull(fitter.measured(k, false, 100.0, 400.0, 13.0, 13.0))
        assertNull(fitter.measured(k, false, 100.0, 400.0, 13.0, 13.0))

        val capped = AnswerFitter()
        capped.begin(k)
        capped.measured(k, false, 100.0, 400.0, 30.0, 13.0)
        capped.measured(k, false, 100.0, 400.0, 30.0, 13.0)
        var size = 30.0
        var actions = 0
        while (true) {
            val action = capped.measured(k, false, 100.0, 400.0, size, 13.0) ?: break
            assertTrue(action is FitAction.SetFontSize)
            size = (action as FitAction.SetFontSize).size!!
            actions++
        }
        assertEquals(10, actions) // the C#'s loop bound
    }

    @Test
    fun fit_FitsAtAnyPass_EndsTheFit() {
        val fitter = AnswerFitter()
        val k = key()
        fitter.begin(k)
        assertNull(fitter.measured(k, true, 300.0, 250.0, 19.0, 13.0)) // preferred fits at once
        assertNull(fitter.measured(k, false, 300.0, 500.0, 19.0, 13.0)) // no fit is running anymore

        val other = AnswerFitter()
        other.begin(k)
        assertNull(other.measured("another-key", false, 300.0, 500.0, 19.0, 13.0)) // foreign key: ignored
    }

    @Test
    fun key_ChangesWithEveryInput_AndRoundsTheSizes() {
        val base = key()
        assertEquals(base, key()) // deterministic
        assertNotEquals(base, key(viewport = 301.0))
        assertNotEquals(base, key(outerWidth = 801.0))
        assertNotEquals(base, key(question = "Another?"))
        assertNotEquals(base, key(note = "note"))
        assertNotEquals(base, key(isCoachView = true))
        assertNotEquals(base, key(isProfileGap = true))
        assertNotEquals(base, key(isThinking = true))
        assertNotEquals(base, key(pending = "…"))
        assertNotEquals(base, key(coachKeywords = "K"))
        assertNotEquals(base, key(coachStructure = "S"))
        assertNotEquals(base, key(coachReminder = "R"))
        assertNotEquals(base, key(fontPreset = "Normal"))
        assertNotEquals(base, key(bullets = listOf("One", "Changed")))
        // Sub-pixel viewport jitter is the same input (the C# rounds both sizes).
        assertEquals(key(viewport = 100.0), key(viewport = 100.4))
        assertNotEquals(key(viewport = 100.0), key(viewport = 100.5))
    }
}
