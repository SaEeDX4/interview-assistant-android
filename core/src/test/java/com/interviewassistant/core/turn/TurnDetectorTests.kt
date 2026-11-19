package com.interviewassistant.core.turn

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.diagnostics.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/TurnDetectorTests.cs`. */
class TurnDetectorTests {

    private class Rig(val td: TurnDetector, val clock: ManualClock, val out: MutableList<FinalizedTurn>)

    private fun create(): Rig {
        val clock = ManualClock()
        val td = TurnDetector(clock)
        val list = mutableListOf<FinalizedTurn>()
        td.addTurnFinalizedListener { list.add(it) }
        td.start()
        return Rig(td, clock, list)
    }

    private fun run(td: TurnDetector, c: ManualClock, ms: Int) {
        var i = 0
        while (i < ms) {
            c.advance(20)
            td.tick()
            i += 20
        }
    }

    private fun segment(td: TurnDetector, c: ManualClock, id: String, text: String, speakMs: Int = 1500, transcriptLatencyMs: Int = 300) {
        td.onSpeechStarted()
        run(td, c, speakMs)
        td.onSpeechStopped()
        run(td, c, transcriptLatencyMs)
        td.onSegmentCompleted(id, text)
    }

    @Test
    fun clearQuestionFinalizesQuicklyAfterSpeechEnds() {
        val (td, c, outp) = create().let { Triple(it.td, it.clock, it.out) }
        segment(td, c, "a", "How would you increase XAB adoption?")
        val stoppedAt = c.nowMs - 300
        run(td, c, 1500)
        assertEquals(1, outp.size)
        val latency = outp[0].finalizedMs - stoppedAt
        assertTrue("latency $latency", latency in 300..800)
    }

    @Test
    fun multiPartQuestionWithPauseIsOneQuestion() {
        val rig = create()
        segment(rig.td, rig.clock, "a", "How would you improve XAB utility and")
        run(rig.td, rig.clock, 600) // short thinking pause by interviewer (< incomplete threshold)
        segment(rig.td, rig.clock, "b", "which metrics would you use to measure success?")
        run(rig.td, rig.clock, 2000)
        assertEquals(1, rig.out.size)
        assertEquals("How would you improve XAB utility and which metrics would you use to measure success?", rig.out[0].text)
    }

    @Test
    fun resumedSpeechDuringSettlingCancelsFinalization() {
        val rig = create()
        segment(rig.td, rig.clock, "a", "Tell me about your crypto background.")
        run(rig.td, rig.clock, 200) // within settle window
        segment(rig.td, rig.clock, "b", "And specifically your experience at Arzif.")
        run(rig.td, rig.clock, 2000)
        assertEquals(1, rig.out.size)
        assertTrue(rig.out[0].text.contains("Arzif"))
        assertTrue(rig.out[0].text.contains("crypto background"))
    }

    @Test
    fun revisedTranscriptForSameItemDoesNotDuplicate() {
        val rig = create()
        segment(rig.td, rig.clock, "a", "How would you prioritize the backlog")
        rig.td.onSegmentCompleted("a", "How would you prioritize the backlog?")
        run(rig.td, rig.clock, 2000)
        assertEquals(1, rig.out.size)
        assertEquals("How would you prioritize the backlog?", rig.out[0].text)
    }

    @Test
    fun pleasantriesNeverTriggerAnswers() {
        val rig = create()
        segment(rig.td, rig.clock, "a", "Okay, great, thank you.")
        run(rig.td, rig.clock, 2000)
        assertTrue(rig.out.isEmpty())
        assertEquals(TurnState.Listening, rig.td.state)
    }

    @Test
    fun missingTranscriptDoesNotHangForever() {
        val rig = create()
        segment(rig.td, rig.clock, "a", "What would you do in your first ninety days")
        rig.td.onSpeechStarted()
        run(rig.td, rig.clock, 500)
        rig.td.onSpeechStopped() // segment b transcript never arrives
        run(rig.td, rig.clock, 5000)
        assertEquals(1, rig.out.size)
        assertEquals(TurnState.Listening, rig.td.state)
    }

    @Test
    fun adaptiveDelay() {
        for ((text, expected) in listOf(
            "How would you prioritize?" to 600,
            "How would you prioritize the backlog and" to 1700,
            "I'd like to understand your background," to 1700,
            "Let's talk about the ledger" to 800,
        )) {
            assertEquals(text, expected, TurnDetector(ManualClock()).adaptiveDelayMs(text))
        }
    }

    @Test
    fun duplicateGuardSuppressesRevisionsButNotNewQuestions() {
        val g = DuplicateGuard()
        g.register("How would you increase XAB adoption?", 0)
        assertTrue(g.isDuplicate("how would you increase xab adoption", 1000))
        assertTrue(g.isDuplicate("So how would you increase XAB adoption?", 2000))
        assertFalse(g.isDuplicate("How would you measure XAB retention by cohort?", 3000))
        assertFalse(g.isDuplicate("How would you increase XAB adoption?", 60_000)) // outside window
    }

    @Test
    fun speechEvidence_FollowsItsOwnItem_EvenWhenTranscriptsArriveOutOfOrderOrAreDropped() {
        val rig = create()
        val td = rig.td
        val c = rig.clock
        val seen = mutableMapOf<String, SpeechEvidence>()
        td.segmentFilter = { text, ev -> seen[text] = ev; true }
        val beep = SpeechEvidence(5, 500, 500, 0, 480) // tonal, no voice
        val voice = SpeechEvidence(15, 1500, 1500, 1200, 0) // voiced speech

        td.onSpeechStarted()
        run(td, c, 500)
        td.onSpeechStopped(beep, "item-beep")
        td.onSpeechStarted()
        run(td, c, 1500)
        td.onSpeechStopped(voice, "item-voice")
        td.onSegmentCompleted("item-voice", "And why?") // arrives first
        td.onSegmentCompleted("item-beep", "Thank you.")
        assertEquals(voice, seen["And why?"]) // FIFO would have given it the beep's evidence
        assertEquals(beep, seen["Thank you."])

        // A reconnect drops a transcript: its evidence must not shift onto the next segment.
        td.onSpeechStarted()
        run(td, c, 500)
        td.onSpeechStopped(beep) // no item id (fallback queue)
        td.onConnectionInterrupted()
        td.onSpeechStarted()
        run(td, c, 1500)
        td.onSpeechStopped(voice)
        td.onSegmentCompleted("item-next", "How so?")
        assertEquals(voice, seen["How so?"])
    }

    /**
     * The TurnDetector half of `NonLatinQuestionTests.QuestionMarkOfAnyScript_IsAQuestion`, deferred by name to
     * this package by Package B (which ported the UtteranceGate/TextNormalizer halves): recognizers write "？"
     * (Chinese) and "؟" (Arabic, Persian), and word counts only see Latin letters.
     */
    @Test
    fun questionMarkOfAnyScript_IsAQuestion() {
        for (text in listOf(
            "你为什么离开上一家公司？",
            "为什么？",
            "لماذا تركت وظيفتك السابقة؟",
            "چرا شغل قبلی خود را ترک کردید؟",
            "Warum haben Sie sich bei uns beworben?",
        )) {
            assertFalse(text, TurnDetector.isNonQuestion(text))
        }
    }
}
