package com.interviewassistant.core.turn

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.intelligence.AcousticSupport
import com.interviewassistant.core.intelligence.PromptEchoDetector
import com.interviewassistant.core.intelligence.QuestionExtractor
import com.interviewassistant.core.intelligence.SpeechPlausibility
import com.interviewassistant.core.intelligence.UtteranceGate
import com.interviewassistant.core.intelligence.UtteranceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/ShortTurnTests.cs`.
 *
 * RC4: short real follow-ups stay answerable however the recognizer punctuates them, while acknowledgements and
 * fillers do not become questions — without a word-count floor, a question-mark requirement or a list of transcripts.
 */
class ShortTurnTests {

    private fun gate(): UtteranceGate =
        UtteranceGate(PromptEchoDetector("Job interview for a Product Owner role at Teroxx. Terms: FinTech, blockchain."))

    @Test
    fun shortFollowUp_IsAQuestion_AndIsNotDroppedByTheTurnDetector() {
        for (text in listOf(
            "Why?", "Why.", "Why", "How so?", "How so.", "Really?", "And then?", "And then.", "Tell me more.",
            "What happened?", "What happened.", "Metrics?", "Anything else?", "Anything else.", "Timeline?", "Why not?", "How come.",
        )) {
            assertFalse(text, TurnDetector.isNonQuestion(text))
            val verdict = gate().evaluate(text)
            assertTrue(text, verdict.answerable)
            // rc.5: an imperative that points back at the answer is its own kind (a candidate-directed follow-up); the
            // elliptical and explicit questions stay questions.
            assertEquals(text, if (text == "Tell me more.") UtteranceKind.FollowUp else UtteranceKind.Question, verdict.kind)
        }
    }

    @Test
    fun acknowledgement_OrFiller_IsNotAQuestionTurn() {
        for (text in listOf("Okay.", "Okay?", "Thanks.", "Right?", "Hm?", "Mm-hmm.", "Sorry?", "Great", "Uh")) {
            assertTrue(text, TurnDetector.isNonQuestion(text))
        }
    }

    @Test
    fun exclamation_StartingWithAWhWord_IsNotAQuestion() {
        for (text in listOf("What a day.", "How nice.", "What a great story that was.", "How interesting that is.")) {
            assertFalse(text, gate().evaluate(text).answerable)
        }
    }

    @Test
    fun shortFollowUp_OverAShortRealVoice_HasAcousticSupport() {
        // 200 ms voiced across ~4 pitch pairs is what a real half-second "Why?" measures (speech_why fixtures).
        val why = SpeechEvidence(5, 500, 400, 200, 0, pitchPairs = 4, steadyPairs = 1, windowMs = 500)
        assertTrue(why.voiceLike)
        for (t in listOf("Why?", "Why.", "How so?", "Really?")) {
            assertTrue(t, gate().evaluate(t, why).answerable)
        }
    }

    @Test
    fun fewTonalHops_DoNotMakeShortSpeechTonal_ButAChimeIsTonal() {
        // "And then?" over a call: 240 ms voiced, 60 ms tonal (three hops) — measurement noise, still a voice.
        val andThen = SpeechEvidence(7, 700, 420, 240, 60, pitchPairs = 7, steadyPairs = 4, windowMs = 700)
        assertTrue(andThen.toString(), andThen.voiceLike)
        val chime = SpeechEvidence(11, 1100, 1080, 100, 420, pitchPairs = 18, steadyPairs = 12, windowMs = 1100)
        assertFalse(chime.voiceLike)
        assertTrue(chime.tooTonal)
    }

    @Test
    fun steadyPitch_IsJudgedOnlyWithEnoughPairs() {
        val buzz = SpeechEvidence(8, 800, 600, 400, 0, pitchPairs = 17, steadyPairs = 14, windowMs = 800)
        assertFalse(buzz.voiceLike)
        assertTrue(buzz.pitchJudged)
        val shortWhy = SpeechEvidence(5, 500, 460, 240, 0, pitchPairs = 6, steadyPairs = 5, windowMs = 500)
        assertTrue(shortWhy.voiceLike) // 83 % steady over 6 pairs is noise, not a tone
        assertFalse(shortWhy.pitchJudged)
    }
}

/** Mirror of `DuplicateContextTests` in `ShortTurnTests.cs`. */
class DuplicateContextTests {

    private fun gate(): UtteranceGate = UtteranceGate(PromptEchoDetector("Job interview"))

    @Test
    fun questionSaidTwice_IsNotItsOwnContextLine() {
        val x = QuestionExtractor.extract(
            "How would you increase adoption of the rewards program. Our adoption is flat this quarter. So how would you increase adoption of the rewards program?",
            "", null, gate(),
        )
        assertFalse(x.contextSummary, x.contextSummary.contains("increase adoption", ignoreCase = true))
        assertTrue(x.contextSummary.contains("adoption is flat"))
    }

    @Test
    fun earlierContextThatRepeatsTheQuestion_IsDropped_RealContextKept() {
        val earlier = "We launched the VIP program in March. How would you diagnose the slow adoption"
        val x = QuestionExtractor.extract("How would you diagnose the slow adoption?", earlier, null, gate())
        assertTrue(x.contextSummary.contains("VIP program"))
        assertFalse(x.contextSummary.contains("diagnose"))
    }

    @Test
    fun relatedButDifferentSentence_StaysContext() {
        val x = QuestionExtractor.extract(
            "Adoption of the rewards program has been slow since launch. How would you increase adoption?",
            "", null, gate(),
        )
        assertTrue(x.contextSummary.contains("slow since launch"))
    }
}

/** Mirror of `ImperativeAskTests` in `ShortTurnTests.cs`: imperative asks are recognised in imperative position only. */
class ImperativeAskTests {

    private fun gate(): UtteranceGate = UtteranceGate(PromptEchoDetector("Job interview"))

    @Test
    fun imperativeAsk_IsAQuestion() {
        for (text in listOf(
            "Describe a time you disagreed with your manager.",
            "So, describe your approach to discovery.",
            "Please share an example of a hard trade-off.",
            "Share an example of a feature you killed.",
            "Could you explain how you prioritised the backlog.",
            "Can you elaborate on the migration.",
            "I'd like you to describe your first ninety days.",
            "I want you to explain the trade-off.",
            "We would like you to walk us through the launch.",
            "Give me an example of a metric you moved.",
            "Imagine you joined us tomorrow and the release is late.",
            "Okay, explain the architecture to me.",
            "Tell me about your biggest failure.",
        )) {
            assertEquals(text, UtteranceKind.Question, gate().evaluate(text).kind)
        }
    }

    @Test
    fun statementContainingTheVerb_IsNotAnAsk() {
        for (text in listOf(
            "The VIP tiers are popular but expensive and hard to explain in the app.",
            "Our onboarding docs describe the process in detail.",
            "Users often share their referral links on social media.",
            "That would explain the drop in retention last quarter.",
        )) {
            assertNotEquals(text, UtteranceKind.Question, gate().evaluate(text).kind)
        }
    }
}

/** Mirror of `SyllableEstimateTests` in `ShortTurnTests.cs`. */
class SyllableEstimateTests {

    @Test
    fun syllables_AreALowerBound() {
        for ((text, max) in listOf(
            "makes" to 1, "used" to 1, "asked" to 1, "times" to 1, "wanted" to 2, "boxes" to 2, "wishes" to 2,
            "product" to 2, "What is your experience with FinTech products?" to 12,
        )) {
            assertTrue("$text: ${SpeechPlausibility.syllables(text)}", SpeechPlausibility.syllables(text) <= max)
        }
    }

    @Test
    fun fastShortRealQuestion_IsStrong() {
        for (text in listOf("Which interview stage?", "Why product?", "And the wallet?")) {
            // ~110 ms voiced per syllable incl. clipped edges: 340 ms voiced, a judged moving pitch.
            val fast = SpeechEvidence(12, 1200, 600, 340, 0, pitchPairs = 12, steadyPairs = 3, windowMs = 1200)
            assertEquals(text, AcousticSupport.Strong, SpeechPlausibility.assess(text, fast))
        }
    }

    @Test
    fun contextHallucinationOverAShortVoiceLikeSound_IsStillRejected() {
        val knock = SpeechEvidence(19, 1900, 620, 360, 40, pitchPairs = 11, steadyPairs = 2, windowMs = 1900)
        val support = SpeechPlausibility.assess("What is your experience with FinTech products?", knock)
        assertTrue(support.toString(), support == AcousticSupport.Weak || support == AcousticSupport.Implausible)
        val gate = UtteranceGate(PromptEchoDetector("Job interview for a Product Owner role. Terms: FinTech, blockchain."))
        assertFalse(gate.evaluate("What is your experience with FinTech products?", knock).answerable)
    }
}
