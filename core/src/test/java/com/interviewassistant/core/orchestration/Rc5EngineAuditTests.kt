package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.AudioFixtures
import com.interviewassistant.core.audio.RecordedStyleAudioTests
import com.interviewassistant.core.intelligence.DuplicateGuard
import com.interviewassistant.core.intelligence.FollowUpIntent
import com.interviewassistant.core.intelligence.FollowUpKind
import com.interviewassistant.core.intelligence.PromptEchoDetector
import com.interviewassistant.core.intelligence.QuestionClassifier
import com.interviewassistant.core.intelligence.UtteranceGate
import com.interviewassistant.core.intelligence.UtteranceKind
import com.interviewassistant.core.turn.TurnDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/Rc5EngineAuditTests.cs`.
 *
 * Regression tests for the independent engine audit of the rc.5 follow-up and non-speech changes (findings 2–9; finding 1,
 * short held tones, is covered in [Rc5FollowUpEngineTests]). Each test reproduces an auditor's case.
 */
class Rc5EngineAuditTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private fun gate(): UtteranceGate = UtteranceGate(
            PromptEchoDetector(Fixtures.testKnowledge().context.buildTranscriptionPrompt()),
            listOf("Shervin", "Fallahdoust"),
        )

        private val OneWordFollowUps = listOf(
            "Continue.", "More.", "Expand.", "Clarify.", "Details.", "Illustrate.", "Unpack.", "Go on.", "Keep going.", "Be specific.",
            "Say more.", "Elaborate.", "Explain.", "Example.", "Go deeper.",
        )
    }

    // ---------------- finding 2: one- and two-word follow-ups over the voice look-alikes ----------------

    @Test
    fun lookalikeFixtures_OneWordFollowUps_CreateNothing() = runBlocking {
        for (file in RecordedStyleAudioTests.KnownVoiceLookalikes.keys) {
            for (text in OneWordFollowUps) {
                val s = Rc5FollowUpEngineTests.playPcm(AudioFixtures.pcm(AudioFixtures.get(file)), text)
                val what = "$file heard as '$text'"
                assertTrue("$what: a question was recorded", s.e.memory.questions.isEmpty())
                assertTrue("$what: a history item was created", s.e.history.isEmpty())
                assertTrue("$what: an AI request was made", s.p.requests.get() == 0)
                // A follow-up's words are never shown over these sounds. ("More." is no follow-up any more: like any other
                // non-question over a voice-like sound it may show as live text and is then dropped — rc.4 behaviour.)
                if (FollowUpIntent.classify(text) != FollowUpKind.None) {
                    for (t in s.liveTexts) assertTrue("$what: live text '$t'", t.isEmpty())
                }
            }
        }
    }

    @Test
    fun bareMore_IsNotAFollowUp() {
        assertEquals(FollowUpKind.None, FollowUpIntent.classify("More."))
        assertTrue(TurnDetector.isNonQuestion("More."))
        assertFalse(TurnDetector.isNonQuestion("More?")) // asked, it stays a question (rc.4 behaviour)
        assertEquals(FollowUpKind.Imperative, FollowUpIntent.classify("More detail."))
    }

    @Test
    fun realOneWordFollowUps_AreClearlySpeech() {
        // The very-short rule (Strong support, a sustained voiced stretch or judged pitch, ≤ 350 ms voiced per syllable)
        // passes every real one- or two-word follow-up fixture; the look-alikes above fail it.
        val cases = listOf(
            "speech_fu_goon__calln.wav" to "Go on.",
            "speech_fu_continue__callq.wav" to "Continue.",
            "speech_fu_elaborate__calln.wav" to "Elaborate.",
        )
        for ((file, text) in cases) {
            val e = RecordedStyleAudioTests.evidence(AudioFixtures.pcm(AudioFixtures.get(file))).copy(windowMs = 0)
            assertTrue(text, FollowUpIntent.isVeryShortImperative(text))
            assertTrue(e.toString(), e.sustainedVoicing || e.pitchJudged)
            assertEquals(UtteranceKind.FollowUp, gate().evaluate(text, e).kind)
        }
    }

    @Test
    fun knockAndSungMelody_FailTheVeryShortRule() {
        val knock = RecordedStyleAudioTests.evidence(AudioFixtures.pcm(AudioFixtures.get("knock_door__callq.wav")))
        val sung = RecordedStyleAudioTests.evidence(AudioFixtures.pcm(AudioFixtures.get("music_sung__callq.wav")))
        assertTrue(knock.voiceLike && sung.voiceLike) // still voice-like for longer transcripts: the documented residual
        assertFalse(knock.toString(), knock.sustainedVoicing || knock.pitchJudged)
        assertTrue(sung.toString(), sung.voicedMs > 4 * UtteranceGate.maxVoicedMsPerShortSyllable)
        assertFalse(gate().evaluate("Continue.", knock).answerable)
        assertFalse(gate().evaluate("Elaborate.", sung).answerable)
    }

    // ---------------- finding 3: a repeated real follow-up after another answer ----------------

    @Test
    fun repeatedFollowUp_AfterItsAnswer_IsAnsweredAgain() = runBlocking {
        for (followUp in listOf("Tell me more.", "Give me an example.", "Why?")) {
            val s = Rc2EngineAcceptanceTests.startAsync()
            s.say("What did you build at Teroxx?")
            s.run(8000)
            s.settle()
            s.say(followUp)
            s.run(10_000)
            s.settle()
            s.say(followUp)
            s.run(8000)
            s.settle()
            assertEquals(followUp, 3, s.e.memory.questions.size)
            assertEquals(followUp, 3, s.e.history.size)
            assertEquals(followUp, 0, s.e.metrics.duplicatesSuppressed)
        }
    }

    @Test
    fun duplicateGuard_ScopesFollowUpsByTheAnsweredQuestion() {
        val g = DuplicateGuard()
        g.register("Tell me more.", 1000, scope = 1)
        assertTrue(g.isDuplicate("Tell me more.", 4000, scope = 1)) // the same transcript echoed before anything new was answered
        assertFalse(g.isDuplicate("Tell me more.", 12_000, scope = 2)) // asked again after the first follow-up was answered
        g.register("How would you increase XAB adoption?", 20_000)
        assertTrue(g.isDuplicate("So how would you increase XAB adoption?", 21_000)) // unscoped questions: unchanged
    }

    // ---------------- finding 4: hand-off to a colleague, then a bare follow-up ----------------

    @Test
    fun handOff_ThenBareFollowUp_IsOtherDirected() {
        for (text in listOf("Over to you, Maria. Go on.", "John, do you want to take this? Go on.", "I want to hear from Sarah. Sarah, go on.")) {
            assertEquals(text, UtteranceKind.OtherDirected, gate().evaluate(text).kind)
        }
    }

    @Test
    fun handOff_ThenFollowUpNamingTheCandidate_IsAnswered() =
        assertTrue(gate().evaluate("Over to you, Maria. Shervin, go on.").answerable)

    @Test
    fun handOff_ThenBareFollowUp_CreatesNothing() = runBlocking {
        val s = Rc2EngineAcceptanceTests.startAsync()
        s.say("Over to you, Maria. Go on.")
        s.run(8000)
        s.settle()
        assertTrue(s.e.history.isEmpty())
        assertEquals(0, s.p.requests.get())
        assertEquals(1, s.e.metrics.otherDirectedSuppressed)
    }

    // ---------------- finding 5: instructions that are not asks to speak ----------------

    @Test
    fun instruction_IsNotAnswered() {
        val cases = listOf(
            "Go on mute please.", "I want you to go on mute.", "Continue to the next slide.", "Go on to the next slide.",
            "Okay, go on to the next question.", "And go on to the next one.", "Keep going with the exercise.",
            "Carry on with your code.", "Continue coding.", "Now continue with the coding exercise.",
            "Clarify that in the follow-up email.", "Show me your screen.", "Show us the dashboard.",
            "Feel free to share any questions you have for us at the end.", "Tell me when you can see my screen.",
            "Tell us when you're done.", "Give us a second.",
        )
        for (text in cases) assertFalse("$text: ${gate().evaluate(text).kind}", gate().evaluate(text).answerable)
    }

    @Test
    fun askToKeepTalking_IsAFollowUp() {
        val cases = listOf(
            "Go on.", "Please continue.", "Continue, please.", "Go on, I'm listening.",
            "Carry on with your answer.", "Keep going.", "Go on then.", "Show me how you would do it.",
            "Continue where you left off.",
        )
        for (text in cases) {
            assertEquals(text, UtteranceKind.FollowUp, gate().evaluate(text).kind)
            assertTrue(text, FollowUpIntent.isStandaloneFollowUp(text))
        }
    }

    @Test
    fun instruction_CreatesNothing() = runBlocking {
        for (text in listOf("Continue to the next slide.", "Show me your screen.", "Feel free to share any questions you have for us at the end.", "Keep going with the exercise.")) {
            val s = Rc2EngineAcceptanceTests.startAsync()
            s.say(text)
            s.run(8000)
            s.settle()
            assertTrue(text, s.e.history.isEmpty())
            assertEquals(text, 0, s.p.requests.get())
        }
    }

    @Test
    fun continueFollowedByATopic_IsNotStandalone() {
        // The verbs are no longer "anaphoric" by themselves: "continue …" with a topic of its own is not a bare follow-up.
        assertFalse(FollowUpIntent.isStandaloneFollowUp("Go on about the reconciliation."))
        assertTrue(gate().evaluate("Go on about the reconciliation.").answerable)
    }

    // ---------------- finding 6: a trailing name without a comma ----------------

    @Test
    fun trailingOtherNameWithoutComma_IsOtherDirected() {
        for (text in listOf("Go on John.", "Continue Sarah.", "Tell me more Maria.", "Go on Maria", "Elaborate Maria.")) {
            assertEquals(text, UtteranceKind.OtherDirected, gate().evaluate(text).kind)
        }
    }

    @Test
    fun trailingCandidateNameWithoutComma_IsAnswered() {
        for (text in listOf("Go on Shervin.", "Tell me more Shervin.")) {
            assertTrue(text, gate().evaluate(text).answerable)
        }
    }

    @Test
    fun capitalisedTopicAfterATransitiveAsk_IsNotAVocative() {
        for (text in listOf("Explain Snowflake.", "Describe Terraform.")) {
            assertTrue(text, gate().evaluate(text).answerable)
        }
    }

    // ---------------- findings 7 and 8: split only after a candidate-reply allowance ----------------

    @Test
    fun quickMultiPartAsk_StillMerges() = runBlocking {
        val cases = listOf(
            Triple("Tell me about a time you led a team.", 1500, "Walk me through it."),
            Triple("How did you scale the ledger?", 1200, "Give me an example."),
            Triple("Tell me about the ledger migration.", 1500, "Go deeper on the reconciliation part."),
            Triple("What was your biggest failure at Teroxx?", 1200, "Why?"),
            Triple("What was your biggest failure at Teroxx?", 1200, "Why."),
            Triple("Describe a conflict with a stakeholder.", 1200, "What happened?"),
            Triple("What did you build at Teroxx?", 1500, "Tell me more."),
        )
        for ((first, pauseMs, second) in cases) {
            val s = Rc2EngineAcceptanceTests.startAsync()
            s.say(first, pauseMs, second)
            s.run(14_000)
            s.settle()
            assertEquals(second, 1, s.e.history.size)
            assertEquals(second, 1, s.e.memory.questions.size)
            assertTrue(second, s.e.memory.questions[0].original.contains(second.trimEnd('.', '?')))
            assertEquals(second, 0, s.e.metrics.followUpTurnsSplit)
            assertTrue("a merged turn replaces its provisional answer at most once", s.p.requests.get() + s.e.metrics.cacheAnswers <= 2)
        }
    }

    @Test
    fun followUpAfterTheReplyAllowance_IsItsOwnTurn() = runBlocking {
        val cases = listOf(
            "What was your biggest failure at Teroxx?" to "Why?",
            "What was your biggest failure at Teroxx?" to "Why.",
            "Describe a conflict with a stakeholder." to "What happened?",
            "What did you build at Teroxx?" to "How so?",
            "What did you build at Teroxx?" to "Really?",
            "What did you build at Teroxx?" to "Give me an example.",
        )
        for ((first, second) in cases) {
            val s = Rc2EngineAcceptanceTests.startAsync()
            s.say(first, 2200, second) // inside the 2.5 s continuation window, past the reply allowance
            s.run(14_000)
            s.settle()
            assertEquals(second, 2, s.e.history.size)
            assertEquals(second, 2, s.e.memory.questions.size)
            assertEquals(second, 1, s.e.metrics.followUpTurnsSplit)
        }
    }

    @Test
    fun ellipticalAsk_IsStandaloneHoweverPunctuated() {
        for (text in listOf("Why?", "Why.", "How so?", "How so.", "What happened?", "Really?", "And then?")) {
            assertTrue(text, FollowUpIntent.isStandaloneFollowUp(text))
        }
    }

    @Test
    fun reallyWithAFallingPeriod_StaysUnanswered() = assertFalse(gate().evaluate("Really.").answerable)

    // ---------------- finding 9: one notion of "follow-up" ----------------

    @Test
    fun followUpIntent_AndClassifier_Agree() {
        val cases = listOf(
            "Walk me through the process.", "Take me through your thinking.", "Explain how you did that.",
            "Help me understand that better.", "Show me how you would do it.", "Okay, so tell me more.",
            "Why?", "How so?",
        )
        for (text in cases) {
            assertTrue(text, FollowUpIntent.isStandaloneFollowUp(text))
            assertTrue(text, QuestionClassifier.classify(text).isFollowUp)
        }
    }

    @Test
    fun processAndThinkingFollowUps_AreFollowUpKind() {
        for (text in listOf("Walk me through the process.", "Take me through your thinking.")) {
            assertEquals(text, UtteranceKind.FollowUp, gate().evaluate(text).kind)
        }
    }

    @Test
    fun followUp_GetsTheFollowUpPrompt_AndNoUnrelatedPreparedAnswer() = runBlocking {
        val cases = listOf(
            "Walk me through the process.", "Take me through your thinking.", "Explain how you did that.",
            "Help me understand that better.", "Show me how you would do it.",
        )
        for (followUp in cases) {
            val s = Rc2EngineAcceptanceTests.startAsync()
            s.say("What did you build at Teroxx?")
            s.run(8000)
            s.settle()
            s.say(followUp)
            s.run(8000)
            s.settle()
            assertEquals(followUp, 2, s.e.memory.questions.size)
            val record = s.e.memory.questions[1]
            assertEquals(followUp, s.e.memory.questions[0].index, record.followUpOf)
            val matched = if (record.matchedQuestionId == null) {
                null
            } else {
                Fixtures.testKnowledge().questions.first { q -> q.questionId == record.matchedQuestionId }
            }
            assertTrue("grounded on ${matched?.questionId} (${matched?.category})", matched == null || matched.category == "FOLLOW_UP")
            val last = synchronized(s.p.calls) { s.p.calls.last() }
            assertTrue(followUp, last.any { m -> m.content.contains("This is a FOLLOW-UP") })
        }
    }
}
