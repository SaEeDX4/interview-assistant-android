package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.SpeechPlausibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/LongContextRegressionTests.cs` — deferred BY NAME from Package B.
 *
 * RC4 regression guard for long-context extraction while the acoustic gate got stricter: 30 s, 60 s and 2 min of
 * interviewer background (one segment per sentence group, natural pauses), then a multi-part question, a rephrase, an
 * interruption or a continuation. Exactly one answer, the right core ask, and the constraints kept as context.
 */
class LongContextRegressionTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private val Background = listOf(
            "Our reward program grew quickly over the last two years across three markets.",
            "Most of the growth came from the cashback tiers and the referral campaign.",
            "Engineering capacity is limited to one squad for the next two quarters.",
            "Compliance is worried about how the token rewards are presented to retail users.",
            "Retention after the first month dropped from forty to around thirty percent.",
            "The VIP tiers are popular but expensive and hard to explain in the app.",
            "Marketing wants a new campaign before the end of the year.",
            "Support tickets about missing rewards doubled since the summer release.",
        )
    }

    /** Schedules ~[seconds] of background speech: sentences in segments of three, 1.2 s apart. Returns spoken ms. */
    private fun sayBackground(s: InterviewSimulator, seconds: Int): Int {
        val parts = mutableListOf<Any>()
        var ms = 0
        var i = 0
        while (ms < seconds * 1000) {
            val sentence = Background[i++ % Background.size]
            parts.add(sentence)
            val dur = maxOf(600, maxOf(sentence.split(' ').size * s.msPerWord, SpeechPlausibility.syllables(sentence) * s.msPerSyllable))
            val pause = if (i % 3 == 0) 1200 else 250 // a breath inside a segment, a real pause between groups
            parts.add(pause)
            ms += dur + pause
        }
        s.say(*parts.toTypedArray())
        return ms
    }

    private suspend fun runScenario(seconds: Int, vararg question: Any): InterviewSimulator {
        val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false; useFastCache = false })
        s.start()
        val ms = sayBackground(s, seconds)
        s.run(ms)
        s.say(*question)
        s.run(question.filterIsInstance<String>().sumOf { p -> p.split(' ').size } * 400 + question.filterIsInstance<Int>().sum() + 6000)
        s.settle()
        return s
    }

    @Test
    fun background_ThenTwoPartQuestion_OneAnswerWithBothPartsAndTheConstraints() = runBlocking {
        for (seconds in listOf(30, 60, 120)) {
            val s = runScenario(seconds, "So if you owned this product, what would you change first, and how would you measure whether it worked?")
            val a = s.e.history.single()
            assertTrue("$seconds s", a.question.contains("what would you change first", ignoreCase = true))
            assertTrue("$seconds s", a.question.contains("how would you measure", ignoreCase = true))
            assertEquals("$seconds s", 2, a.subQuestions.size)
            assertEquals("$seconds s", 1, s.p.requests.get())
            val prompt = s.p.calls.single()[1].content
            assertTrue(prompt.contains("Engineering capacity is limited"))
            assertFalse(a.contextSummary.contains("what would you change first", ignoreCase = true))
        }
    }

    @Test
    fun background_ThenRephrasedAsk_TheRestatedAskWins() = runBlocking {
        for (seconds in listOf(30, 120)) {
            val s = runScenario(seconds, "How would you rebuild the whole rewards roadmap for next year across every market — sorry, let me put it differently: what would you cut first if capacity halved?")
            assertEquals("$seconds s", "What would you cut first if capacity halved?", s.e.history.single().question)
        }
    }

    @Test
    fun background_InterruptedByAnAside_StillOneAnswerForTheRealQuestion() = runBlocking {
        for (seconds in listOf(60, 120)) {
            val s = runScenario(seconds, "Sorry, one second, my colleague just joined.", 2000, "Okay. Given all that, how would you win back the users who stopped after the first month?")
            val a = s.e.history.single()
            assertTrue("$seconds s", a.question.contains("how would you win back the users", ignoreCase = true))
            assertFalse(a.question.contains("colleague"))
            assertEquals("$seconds s", 1, s.p.requests.get())
        }
    }

    @Test
    fun background_QuestionThenQuickContinuation_IsOneQuestion() = runBlocking {
        for (seconds in listOf(30, 60)) {
            val s = runScenario(seconds, "How would you increase retention", 700, "and which metrics would you track to prove it?")
            val a = s.e.history.single()
            assertTrue("$seconds s", a.question.contains("increase retention"))
            assertTrue("$seconds s", a.question.contains("which metrics"))
        }
    }
}
