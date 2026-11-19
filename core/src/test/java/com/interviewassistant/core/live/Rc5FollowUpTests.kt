package com.interviewassistant.core.live

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.AudioFixtures
import com.interviewassistant.core.audio.RecordedStyleAudioTests
import com.interviewassistant.core.intelligence.FollowUpIntent
import com.interviewassistant.core.intelligence.PromptEchoDetector
import com.interviewassistant.core.intelligence.UtteranceGate
import com.interviewassistant.core.intelligence.UtteranceKind
import com.interviewassistant.core.turn.TurnDetector
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/Rc5FollowUpTests.cs` — the text-classification and acoustic cases.
 * The InterviewSimulator cases (FollowUpFixture_CreatesExactlyOneTurnHistoryItemAndRequest,
 * SamePhrasesOverNonSpeech_CreateNothing, SamePhrasesOverShortNonSpeech_CreateNothing,
 * QuickFollowUp_AfterACompleteQuestion_IsItsOwnTurn, UnfinishedQuestion_ThenGoOn_StillMergesIntoOneTurn,
 * FollowUp_AfterTheAnswer_IsAnsweredOnce, Panel_JohnTellMeMore_IsNotAnswered_ShervinTellMeMore_Is) are
 * deferred by name to Package D (Orchestration) in P3-PORT-NOTES.md.
 *
 * RC5 P0: candidate-directed imperative follow-ups ("Tell me more.", "Go on.", "Walk me through that.") are real turns —
 * recognized by grammar (an elicitation verb in imperative position), heard over real, often flat, speech — while the
 * same words over a sound that is not a voice still create nothing.
 */
class Rc5FollowUpTests {

    companion object {
        val Positives: List<String> = listOf(
            "Tell me more.", "Walk me through that.", "Explain further.", "Go on.", "Give me an example.", "Elaborate.",
            "Continue.", "Talk me through it.", "Can you expand on that?", "Give me more detail.", "Take me through your thinking.",
            "Walk me through the process.", "Explain how you did that.", "Give me another example.",
            "Why?", "Why.", "How so?", "Really?",
        )

        /** Short clips of in-range held tones and pulsing tones (see the C# MemberData): (file, shape, ms). */
        val ShortNonSpeech: List<Triple<String, String, Int>> = listOf(
            Triple("error_low_buzz__direct.wav", "first", 300), Triple("error_low_buzz__direct.wav", "first", 400),
            Triple("error_low_buzz__callq.wav", "first", 300), Triple("error_low_buzz__callq.wav", "first", 400),
            Triple("error_low_buzz__calln.wav", "first", 360), Triple("error_low_buzz__calln.wav", "first", 400),
            Triple("music_piano__callq.wav", "first", 360), Triple("music_piano__callq.wav", "first", 400),
            Triple("error_low_buzz__direct.wav", "pulsed", 70), Triple("error_low_buzz__callq.wav", "pulsed", 70),
            Triple("error_low_buzz__calln.wav", "pulsed", 70), Triple("music_band__calln.wav", "pulsed", 70),
        )

        /** The first [ms] ms of a fixture's sound ("first"), or the sound pulsed [ms] ms
         * on / (100 − ms) ms off ("pulsed"), measured from its onset. */
        fun shape(file: String, shape: String, ms: Int): ByteArray {
            val pcm = AudioFixtures.pcm(AudioFixtures.get(file))
            val s = ShortArray(pcm.size / 2) { i -> ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() }
            val on = maxOf(0, s.indexOfFirst { abs(it.toInt()) > 300 })
            val o: ShortArray = if (shape == "first") {
                val cut = ShortArray(minOf(s.size, on + ms * 24))
                s.copyInto(cut, 0, 0, cut.size)
                cut
            } else {
                val clone = s.copyOf()
                for (i in on until clone.size) if ((i - on) / 24 % 100 >= ms) clone[i] = 0
                clone
            }
            val b = ByteArray(o.size * 2)
            for (i in o.indices) {
                val v = o[i].toInt()
                b[i * 2] = (v and 0xFF).toByte()
                b[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
            }
            return b
        }
    }

    private fun gate(): UtteranceGate = UtteranceGate(
        PromptEchoDetector(Fixtures.testKnowledge().context.buildTranscriptionPrompt()),
        listOf("Shervin", "Fallahdoust"),
    )

    // ---------------- text: intent classification ----------------

    @Test
    fun positive_IsACandidateDirectedAsk() {
        for (text in Positives) {
            assertFalse(text, TurnDetector.isNonQuestion(text))
            val v = gate().evaluate(text)
            assertTrue("$text: ${v.kind} (${v.reason})", v.answerable)
        }
    }

    @Test
    fun imperativeFollowUp_IsItsOwnKind() {
        for (text in listOf(
            "Tell me more.", "Go on.", "Continue.", "Elaborate.", "Walk me through that.", "Give me another example.",
            "Okay, so tell me more.", "Please go on.", "Could you elaborate?", "Keep going.",
        )) {
            assertEquals(text, UtteranceKind.FollowUp, gate().evaluate(text).kind)
            assertTrue(text, FollowUpIntent.isStandaloneFollowUp(text))
        }
    }

    @Test
    fun fullImperativeQuestion_IsAQuestion() {
        // A full imperative question is a question, not a follow-up (it brings its own topic).
        for (text in listOf(
            "Describe a time you disagreed with your manager.",
            "Give me an example of a metric you moved.",
            "Tell me about yourself.",
        )) {
            assertEquals(text, UtteranceKind.Question, gate().evaluate(text).kind)
            assertFalse(text, FollowUpIntent.isStandaloneFollowUp(text))
        }
    }

    @Test
    fun sameVerbs_NotAnAsk() {
        // The same verbs outside imperative position, logistics and hand-overs are not asks to the candidate.
        for (text in listOf(
            "I can explain that later.",
            "We will continue after the break.",
            "Let's continue with the next topic.",
            "Give me a second.",
            "Tell me when you're ready.",
            "Continue sharing your screen.",
            "It was hard to explain in the app.",
        )) {
            assertFalse(text, gate().evaluate(text).answerable)
        }
    }

    @Test
    fun panel_FollowUpToAnotherInterviewer_IsOtherDirected() {
        for (text in listOf("John, tell me more.", "Maria, go on.", "Go on, Maria.", "Sarah, walk me through that.")) {
            assertEquals(text, UtteranceKind.OtherDirected, gate().evaluate(text).kind)
        }
    }

    @Test
    fun panel_FollowUpToTheCandidate_IsAnswerable() {
        for (text in listOf("Shervin, tell me more.", "Shervin, go on.", "Go on, Shervin.")) {
            assertTrue(text, gate().evaluate(text).answerable)
        }
    }

    @Test
    fun oneWordFollowUp_IsNotDroppedByTheTurnDetector() {
        for (text in listOf("Continue.", "Elaborate.", "Example.", "Details.")) {
            assertFalse(text, TurnDetector.isNonQuestion(text))
        }
    }

    // ---------------- audio: real (synthesized) flat speech ----------------

    @Test
    fun flatFollowUp_WasRejectedByTheRc4Rule_AndIsAcceptedByTheRunRule() {
        // The root cause, measured: "Go on." spoken level and falling has ≥ 65 % adjacent-hop steadiness, which the
        // rc.4 rule (no pitch tracking) called a held tone; its runs drift, so the rc.5 rule hears a voice.
        val e = RecordedStyleAudioTests.evidence(AudioFixtures.pcm(AudioFixtures.get("speech_fu_goon__calln.wav")))
        println(e.toString())
        assertTrue(e.notVoiceReason, e.voiceLike)
        val rc4 = e.copy(trackedHops = 0, runHops = 0, flatRunHops = 0)
        assertFalse(rc4.voiceLike)
        assertTrue(rc4.notVoiceReason, rc4.notVoiceReason.startsWith("steady pitch"))
    }

    @Test
    fun shortAndPulsingHeldTones_AreNotVoiceLike() {
        // Short clips of in-range held tones (the first 300–400 ms of a buzz or a piano note) and pulsing tones (70 ms on /
        // 30 ms off): too few pitch-run hops for the run rule, so the rc.4 steady-pitch rule decides (rc.5 engine audit,
        // finding 1: before the fix rc.5 answered 12 of 18 phrases over these, rc.4 none).
        for ((file, shape, ms) in ShortNonSpeech) {
            val e = RecordedStyleAudioTests.evidence(shape(file, shape, ms))
            println(e.toString())
            assertFalse("$file $shape $ms: $e", e.voiceLike)
        }
    }
}
