package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.diagnostics.ManualClock
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/EngineTests.cs`. */
class EngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    private class Rig(
        val engine: InterviewEngine,
        val t: FakeTranscriber,
        val p: FakeProvider,
        val clock: ManualClock,
    ) {
        val completed: MutableList<AnswerView> = mutableListOf()
        val bullets: MutableList<Pair<Int, String>> = mutableListOf()

        fun run(ms: Int) {
            var i = 0
            while (i < ms) {
                clock.advance(20)
                engine.tick()
                i += 20
            }
        }

        suspend fun ask(text: String, vararg parts: String): AnswerView {
            val before = synchronized(completed) { completed.size }
            val segs = if (parts.isNotEmpty()) parts else arrayOf(text)
            for (s in segs) {
                t.start()
                run(1200)
                t.stop()
                run(250)
                t.complete(s)
                run(300)
            }
            run(2000)
            var i = 0
            while (i < 200) {
                synchronized(completed) { if (completed.size > before) return completed.last() }
                delay(10)
                i++
            }
            throw AssertionError("no answer for: $text")
        }
    }

    private suspend fun create(withProvider: Boolean = true): Rig {
        val clock = ManualClock()
        val t = FakeTranscriber()
        val p = FakeProvider()
        val e = InterviewEngine(Fixtures.testKnowledge(), t, if (withProvider) p else null, EngineOptions().apply { autoTick = false }, clock)
        val rig = Rig(e, t, p, clock)
        e.answerCompletedListeners.add { a -> synchronized(rig.completed) { rig.completed.add(a) } }
        e.bulletAddedListeners.add { a, b -> synchronized(rig.bullets) { rig.bullets.add(a.id to b) } }
        e.startListening()
        return rig
    }

    @Test
    fun knownQuestionIsServedFromCacheWithoutLlmCall() = runBlocking {
        val r = create()
        val a = r.ask("How would you prioritize improvements to the XAB rewards ecosystem?")
        assertEquals(AnswerSource.Cache, a.source)
        assertEquals(3, a.bullets.size)
        assertTrue(a.bullets[0].startsWith("I'd start with the customer and business outcome"))
        assertEquals(0, r.p.requests.get())
        val s = r.engine.metrics.samples.last()
        assertTrue("speechEnd->finalized ${s.speechEndToFinalizedMs} ms", s.speechEndToFinalizedMs in 200..1200)
    }

    @Test
    fun unexpectedQuestionStreamsFromLlmAsStableBullets() = runBlocking {
        val r = create()
        val a = r.ask("How would you price a premium subscription bundled with XAB benefits?")
        assertEquals(AnswerSource.Llm, a.source)
        assertEquals(1, r.p.requests.get())
        assertEquals(3, a.bullets.size)
        assertEquals("I'd start by defining the goal and the user problem clearly.", a.bullets[0])
        // Bullets were appended exactly once each, in order (no rewriting).
        assertEquals(a.bullets, synchronized(r.bullets) { r.bullets.filter { b -> b.first == a.id }.map { b -> b.second } })
        val prompt = r.p.calls[0][1].content
        assertTrue(prompt.contains("INTERVIEWER QUESTION"))
        assertTrue("prompt should stay compact", r.p.calls[0].sumOf { m -> m.content.length } < 14000)
    }

    @Test
    fun multiPartQuestionGetsOneAnswer() = runBlocking {
        val r = create()
        r.ask("", "How would you improve XAB utility and", "which metrics would you use to measure success?")
        assertEquals(1, synchronized(r.completed) { r.completed.size })
        assertTrue(r.completed[0].question.contains("which metrics"))
    }

    @Test
    fun repeatedFinalTranscriptDoesNotCreateSecondAnswer() = runBlocking {
        val r = create()
        r.ask("How would you increase XAB adoption?")
        r.t.start()
        r.run(800)
        r.t.stop()
        r.run(200)
        r.t.complete("How would you increase XAB adoption")
        r.run(2500)
        delay(100)
        assertEquals(1, synchronized(r.completed) { r.completed.size })
        assertEquals(1, r.engine.metrics.duplicatesSuppressed)
    }

    @Test
    fun llmFailureFallsBackToPreparedAnswer() = runBlocking {
        val r = create()
        r.p.throwEx = ProviderException(ProviderErrorKind.Network, "offline")
        // Medium-similarity phrasing (not an exact cache hit) -> would go to LLM -> fails -> prepared fallback.
        val a = r.ask("Could you explain how you think about rewards cost sustainability and the ROI side?")
        assertTrue("${a.source} ${a.matchedQuestionId} ${a.matchScore} ${a.note}", a.source == AnswerSource.CacheFallback || a.source == AnswerSource.Cache)
        assertTrue(a.bullets.isNotEmpty())
    }

    @Test
    fun invalidKeyWithNoMatchShowsActionableError() = runBlocking {
        val r = create()
        r.p.throwEx = ProviderException(ProviderErrorKind.InvalidApiKey, "bad key")
        val a = r.ask("Zebra quantum basketball orchestra?")
        assertEquals(AnswerSource.Error, a.source)
        assertTrue(a.note!!.contains("Settings"))
        assertEquals(EngineStatus.ApiError, r.engine.status)
    }

    @Test
    fun serviceBuildShowsSignInNoteInsteadOfApiKeyAdvice() = runBlocking {
        val r = create()
        r.engine.options.developerKeyMode = false
        r.p.throwEx = ProviderException(ProviderErrorKind.InvalidApiKey, "401")
        val a = r.ask("Zebra quantum basketball orchestra?")
        assertEquals("Please sign in again (Home → Account).", a.note)
        assertFalse(a.note!!.contains("API key"))
    }

    @Test
    fun manualQuestionWorksWithoutAudioOrProvider() = runBlocking {
        val e = InterviewEngine(Fixtures.testKnowledge(), null, null, EngineOptions().apply { autoTick = false })
        var done: AnswerView? = null
        e.answerCompletedListeners.add { a -> done = a }
        e.submitManualQuestion("tell me about yourself")
        assertNotNull(done)
        assertEquals(AnswerSource.Cache, done!!.source)
        // Default view shows exactly three bullets (the prepared answer has four): it must fit without scrolling.
        assertEquals(3, done!!.bullets.size)
        val bank = Fixtures.testKnowledge().questions.first { q -> q.questionId == done!!.matchedQuestionId }
        assertEquals(bank.shortBullets.take(3), done!!.bullets)
    }

    @Test
    fun variantRequestUsesLlmWithStyleAndFollowUpContext() = runBlocking {
        val r = create()
        r.ask("Tell me about your crypto experience.")
        r.engine.requestVariant(AnswerStyle.Technical)
        assertEquals(1, r.p.requests.get())
        assertTrue(r.p.calls[0][0].content.contains("more technical"))
        assertTrue(r.p.calls[0][1].content.contains("INTERVIEW MEMORY"))
        assertTrue(r.p.calls[0][1].content.contains("Arzif"))
    }

    @Test
    fun fabricatedClaimFromLlmIsSoftenedAndFlagged() = runBlocking {
        val r = create()
        r.p.responder = {
            arrayOf(
                "MODE: HYPOTHETICAL\n",
                "• I built a reward ledger handling 43% more volume.\n",
                "• I built a cashback partner program for card spending.\n",
                "• I'd measure activation and repeat spend.\n",
            )
        }
        val a = r.ask("How would you design a cashback program in XAB for card spending?")
        // Fabricated history + fabricated metric: never shown at all.
        assertFalse(a.bullets.any { b -> b.contains("43%") })
        assertTrue(a.validationFlags.any { f -> f.startsWith("DROPPED_UNVERIFIED_METRIC") })
        assertTrue(a.validationFlags.any { f -> f.startsWith("UNVERIFIED_NUMBER") })
        // Fabricated history without a number: rewritten as a plan.
        assertTrue(a.bullets[0].startsWith("I'd build a cashback partner program"))
        assertFalse(a.bullets.any { b -> b.startsWith("I built") })
    }

    @Test
    fun newQuestionCancelsInFlightGeneration() = runBlocking {
        val r = create()
        r.p.delayMsPerDelta = 150
        val first = async { r.engine.submitManualQuestion("How would you design a quantum-safe custody roadmap?") }
        delay(200)
        val second = async { r.engine.submitManualQuestion("How would you design an onboarding quest for new traders?") }
        first.await()
        second.await()
        val answers = r.engine.history
        assertEquals("Superseded", answers[0].note)
        assertEquals(3, answers[1].bullets.size)
    }

    @Test
    fun reconnectStatusIsSurfacedAndRecovers() = runBlocking {
        val r = create()
        r.t.raise(ConnectionStatus.Reconnecting, "drop")
        assertEquals(EngineStatus.Reconnecting, r.engine.status)
        r.t.raise(ConnectionStatus.Connected, null)
        assertEquals(EngineStatus.Listening, r.engine.status)
        assertEquals(1, r.engine.metrics.reconnects)
    }

    @Test
    fun pauseStopsAudioAndAnswers() = runBlocking {
        val r = create()
        r.engine.pause()
        r.engine.onAudioFrame(ByteArray(4800), -20.0)
        assertEquals(0L, r.t.framesReceived.get())
        r.t.start()
        r.run(500)
        r.t.stop()
        r.t.complete("How would you increase XAB adoption?")
        r.run(2000)
        delay(50)
        assertTrue(synchronized(r.completed) { r.completed.isEmpty() })
        r.engine.startListening()
        r.engine.onAudioFrame(ByteArray(4800), -20.0)
        assertEquals(1L, r.t.framesReceived.get())
        r.engine.stopAsync()
        assertEquals(EngineStatus.Stopped, r.engine.status)
    }

    @Test
    fun noAudioWarningAppearsAndClears() = runBlocking {
        val r = create()
        r.run(21_000)
        assertEquals(EngineStatus.NoAudio, r.engine.status)
        r.engine.onAudioFrame(ByteArray(4800), -20.0)
        r.run(40)
        assertEquals(EngineStatus.Listening, r.engine.status)
    }
}
