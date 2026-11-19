package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.providers.ConnectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/TurnBoundaryAcceptanceTests.cs` — deferred BY NAME from Package C.
 *
 * Turn finalization under realistic server-VAD timing (see [InterviewSimulator]).
 * Contract: one interviewer question → one history entry → one visible answer → at most one AI request
 * (for pauses up to ~1 s); longer continuations are merged into the same entry.
 */
class TurnBoundaryAcceptanceTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private const val PartA = "How would you increase XAB utility and user retention?"
        private const val PartB = "And which metrics would you track to prove that the strategy is working?"
    }

    private suspend fun sim(): InterviewSimulator {
        val s = InterviewSimulator()
        s.start()
        return s
    }

    @Test
    fun singleQuestionIsOneTurnAndFinalizesQuickly() = runBlocking {
        val s = sim()
        s.say("Tell me about your crypto experience.")
        s.run(6000)
        s.settle()
        val a = s.visible.single()
        assertEquals(1, s.e.history.size)
        assertEquals("Tell me about your crypto experience.", a.question)
        val sample = s.e.metrics.samples.single()
        assertTrue("speechEnd->finalized ${sample.speechEndToFinalizedMs} ms", sample.speechEndToFinalizedMs in 0..700)
    }

    /** Acceptance C: the real failing case — two parts with a natural pause must be ONE question and ONE answer. */
    @Test
    fun twoPartQuestionWithNaturalPauseIsOneQuestionOneAnswer() = runBlocking {
        for (pauseMs in listOf(300, 500, 800, 1000)) {
            val s = sim()
            s.say(PartA, pauseMs, PartB)
            s.run(12_000)
            s.settle()

            val history = s.e.history
            assertEquals("pause $pauseMs", 1, history.size) // not two history entries
            val a = s.visible.single() // one answer on screen
            assertTrue(a.question.contains("XAB utility"))
            assertTrue(a.question.contains("which metrics"))
            assertTrue("${s.p.requests.get()} AI requests for one question (pause $pauseMs ms)", s.p.requests.get() <= 1)
            assertEquals("pause $pauseMs", 1, s.e.memory.questions.size) // report/memory also see one question
            assertEquals("pause $pauseMs", 1, s.e.metrics.questions)
        }
    }

    @Test
    fun longerContinuationIsStillMergedIntoTheSameEntry() = runBlocking {
        for (pauseMs in listOf(1400, 2000)) {
            val s = sim()
            s.say("How would you improve onboarding for new traders?", pauseMs, "And what would you measure in the first month?")
            s.run(14_000)
            s.settle()
            assertEquals("pause $pauseMs", 1, s.e.history.size)
            val a = s.visible.single()
            assertTrue(a.question.contains("first month"))
            assertTrue(a.question.contains("onboarding"))
            assertTrue(s.p.requests.get() <= 2) // at most one provisional request that was cancelled
            for (c in s.completed.filter { x -> x.superseded }) {
                assertFalse(s.e.history.contains(c)) // replaced, not listed
            }
        }
    }

    @Test
    fun completedQuestionFollowedByADistinctQuestionLaterIsTwoTurns() = runBlocking {
        val s = sim()
        s.say("How do you prioritize the backlog?")
        s.run(7000) // ~4 s of silence after the question: the turn really ended
        assertEquals(1, s.visible.size)
        s.say("What is a closed-loop token economy?")
        s.run(6000)
        s.settle()
        assertEquals(2, s.e.history.size)
        assertEquals(2, s.visible.size)
        assertEquals(0, s.e.metrics.turnsMerged)
    }

    @Test
    fun interviewerRestatingTheQuestionReplacesTheWording() = runBlocking {
        val s = sim()
        s.say("How would you increase retention?", 900, "Sorry, let me rephrase: how would you increase retention for VIP clients specifically?")
        s.run(14_000)
        s.settle()
        val a = s.visible.single()
        assertEquals(1, s.e.history.size)
        assertTrue(a.question.startsWith("How would you increase retention for VIP clients", ignoreCase = true))
        assertFalse(a.question.contains("Sorry"))
    }

    @Test
    fun acknowledgementDuringContinuationWindowDoesNotMergeOrBlockTheAnswer() = runBlocking {
        val s = sim()
        s.say("How would you design a fee discount feature using XAB tokens for frequent traders?", 900, "Okay.")
        s.run(12_000)
        s.settle()
        val a = s.visible.single()
        assertFalse(a.question.contains("Okay"))
        assertTrue(a.snapshotBullets().isNotEmpty()) // the held AI request was released and answered
        assertEquals(0, s.e.metrics.turnsMerged)
    }

    @Test
    fun fragmentedDeltasAndDuplicateSegmentsGiveOneAnswer() = runBlocking {
        val s = sim()
        s.voice(2000) // the interviewer is audible while the item is open
        s.t.start()
        s.run(2000)
        s.t.stop()
        s.run(200)
        for (piece in listOf("How ", "would ", "you ", "handle ", "an ", "unrealistic ", "deadline")) s.t.partial("seg1", piece)
        s.t.complete("How would you handle an unrealistic deadline", "seg1")
        s.t.complete("How would you handle an unrealistic deadline?", "seg1") // revised final for the same item
        s.t.complete("How would you handle an unrealistic deadline?", "seg1b") // same text re-delivered with a new id
        s.run(5000)
        s.settle()
        val a = s.visible.single()
        assertEquals("How would you handle an unrealistic deadline?", a.question)
    }

    @Test
    fun followUpAfterTheCandidateAnsweredIsANewLinkedQuestion() = runBlocking {
        val s = sim()
        s.say("Tell me about a time you improved retention.")
        s.run(20_000) // candidate answers (interviewer silent)
        s.say("Can you give a concrete example?")
        s.run(6000)
        s.settle()
        assertEquals(2, s.visible.size)
        assertEquals(1, s.e.memory.questions[1].followUpOf)
    }

    @Test
    fun newQuestionDuringAStreamingAnswerSupersedesItWithoutDuplicates() = runBlocking {
        // Slow AI measured on the VIRTUAL clock (deterministic on any OS timer resolution): 3 s to first token, 3 s per bullet.
        val s = InterviewSimulator(provider = { c -> VirtualLatencyProvider(c).apply { firstTokenMs = 3000; perBulletMs = 3000 } })
        s.start()
        s.say("How would you price a premium tier bundle for VIP clients?")
        s.run(8500) // finalized ~4.8 s; answer still streaming
        assertFalse(s.e.current!!.isComplete)
        s.say("What's your view on gamification in finance?")
        s.run(8000)
        s.settle(20_000)
        assertEquals(2, s.e.history.size)
        assertTrue(s.e.current!!.question.contains("gamification"))
        assertTrue(s.e.current!!.isComplete)
        assertEquals(0, s.e.metrics.duplicatesSuppressed)
    }

    @Test
    fun reconnectRightAfterTheQuestionStillAnswersItOnce() = runBlocking {
        val s = sim()
        s.say("How would you write acceptance criteria for a reward payout feature?")
        s.run(3800) // transcript arrives, settle pending
        s.t.raise(ConnectionStatus.Reconnecting, "network drop")
        s.run(1500)
        s.t.raise(ConnectionStatus.Connected, null)
        s.run(4000)
        s.settle()
        val a = s.visible.single()
        assertTrue(a.question.contains("acceptance criteria"))
        assertEquals(1, s.e.metrics.reconnects)
    }
}
