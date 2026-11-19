package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.DotNetRandom
import com.interviewassistant.core.diagnostics.ManualClock
import com.interviewassistant.core.diagnostics.SessionMetrics
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.IAnswerProvider
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import com.interviewassistant.core.reports.ReportBuilder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/SoakTests.cs` (including its `VirtualLatencyProvider`).
 * JVM adaptations (DC-43): GC.GetTotalMemory → used-heap around System.gc(); System.Random →
 * the bit-exact DotNetRandom from the audio tests, with Next(max) = (int)(NextDouble() * max)
 * exactly as the .NET legacy generator computes it.
 *
 * Long simulated interviews on a virtual clock with realistic VAD timing (see [InterviewSimulator]):
 * 100+ questions mixing prepared and novel questions, multi-part questions with natural pauses, fragmented and
 * revised transcripts, acknowledgements, a reconnect, pause/resume with a typed question, AI provider failures and
 * alias terminology. Reports latency percentiles and checks one-question → one-answer invariants and bounded memory.
 */
class VirtualLatencyProvider(private val clock: ManualClock) : IAnswerProvider {
    override val name: String = "virtual-latency"
    var firstTokenMs: Int = 700
    var perBulletMs: Int = 250
    val requests = AtomicInteger(0)
    var throwEx: ProviderException? = null

    private suspend fun waitUntil(t: Long) {
        while (clock.nowMs < t) delay(1)
    }

    override fun streamAsync(messages: List<ChatMessage>, maxTokens: Int): Flow<String> = flow {
        requests.incrementAndGet()
        val failure = throwEx
        if (failure != null) throw failure
        val start = clock.nowMs
        waitUntil(start + firstTokenMs)
        emit("MODE: HYPOTHETICAL\n• I'd start with the user problem and the business goal, ")
        emit("then agree success measures.\n")
        waitUntil(clock.nowMs + perBulletMs)
        emit("• I'd prioritise by value, risk and effort together with Engineering and Compliance.\n")
        waitUntil(clock.nowMs + perBulletMs)
        emit("• I'd ship in small steps and track activation, retention and support tickets.\n")
    }
}

class SoakTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    private fun usedHeap(): Long {
        System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** .NET Random.Next(maxValue) on the legacy subtractive generator. */
    private fun DotNetRandom.next(max: Int): Int = (nextDouble() * max).toInt()

    @Test
    fun longSimulatedInterviewKeepsOneAnswerPerQuestionAndReportsLatency() = runBlocking {
        val kb = Fixtures.testKnowledge()
        var providerRef: VirtualLatencyProvider? = null
        val s = InterviewSimulator(kb, provider = { c -> VirtualLatencyProvider(c).also { providerRef = it } })
        val provider = providerRef!!
        val e = s.e
        e.startListening()

        val rnd = DotNetRandom(7)
        val novel = listOf(
            "How would you price a premium tier bundle for frequent traders?", "What's your view on gamification in financial products?",
            "How would you explain the ledger to a non-technical CEO?", "How would you onboard a new market partner in Dubai?",
            "How would you reduce support tickets about delayed rewards?", "How would you decide between building and buying a KYC vendor?",
        )
        var asked = 0
        var typedWhilePaused = 0
        val memStart = usedHeap()

        for (i in 1..105) {
            asked++
            if (i == 60) {
                s.t.raise(ConnectionStatus.Reconnecting, "simulated drop")
                s.run(1500)
                s.t.raise(ConnectionStatus.Connected, null)
            }
            if (i == 80 || i == 81) provider.throwEx = ProviderException(ProviderErrorKind.ServiceUnavailable, "503").apply { code = "ai_unavailable" }
            if (i == 82) provider.throwEx = null
            if (i == 90) {
                e.pause()
                s.run(4 * 60_000 / 10) // paused (compressed): no listening
                val typed = async { e.submitManualQuestion("How would you design a referral bonus that does not attract fraud?") }
                while (!typed.isCompleted) s.run(20) // virtual time runs while the AI answers
                typed.await()
                typedWhilePaused++
                asked++
                e.startListening()
            }

            val roll = rnd.nextDouble()
            if (i % 15 == 0) {
                s.say("How would you increase adoption of the ab locks token on terox?")
            } else if (roll < 0.25) {
                val pause = listOf(300, 500, 800, 1000)[rnd.next(4)]
                s.say(novel[rnd.next(novel.size)].trimEnd('?') + " for VIP clients?", pause, "And which metrics would you track to prove it is working?")
            } else if (roll < 0.55) {
                s.say(novel[rnd.next(novel.size)])
            } else {
                s.say(kb.questions[rnd.next(kb.questions.size)].semanticVariants[0] + "?")
            }

            s.run(9000)
            if (rnd.nextDouble() < 0.2) {
                s.say("Okay, great.")
                s.run(2500)
            }
            s.run(15_000) // candidate answers; interviewer silent
            s.settle()
        }
        e.stopAsync()
        val memEnd = usedHeap()

        val m = e.metrics
        val samples = m.samples
        val cache = samples.filter { x -> x.source == "Cache" }
        val llm = samples.filter { x -> x.source == "Llm" }
        assertTrue("the soak must cover a long interview: ${s.clock.nowMs}", s.clock.nowMs >= 40 * 60_000)

        assertTrue(asked >= 100)
        // One question → one record (multi-part merged). The only exception is the same question repeated within the
        // duplicate window (the random script can re-ask a bank question), which is suppressed on purpose.
        assertTrue("duplicates ${m.duplicatesSuppressed}", m.duplicatesSuppressed <= 3)
        assertEquals(asked - m.duplicatesSuppressed, e.memory.questions.size)
        assertEquals(1, m.reconnects)
        assertTrue("${provider.requests.get()} AI requests for $asked questions", provider.requests.get() <= asked)
        assertTrue(m.fallbackAnswers + m.apiErrors >= 2) // provider failures were handled, not crashed
        for (q in e.memory.questions.filter { x -> x.original.contains("token on") }) {
            assertTrue(q.original, q.original.contains("Abloxx"))
        }
        assertTrue(e.history.size <= e.options.historyCapacity)
        assertTrue(samples.size <= m.capacity)
        assertTrue("memory grew ${(memEnd - memStart) / 1024} KB", memEnd - memStart < 8 * 1024 * 1024)
        // Latency budgets on the virtual clock (network/model time is simulated, not guaranteed in production).
        assertTrue(
            SessionMetrics.percentile(samples.filter { x -> x.speechEndToFinalizedMs >= 0 }.map { x -> x.speechEndToFinalizedMs }, 95.0) <= 1700,
        )
        assertTrue(SessionMetrics.percentile(cache.map { x -> x.finalizedToFirstBulletMs }, 95.0) <= 60)
        assertTrue(SessionMetrics.percentile(llm.map { x -> x.finalizedToFirstBulletMs }, 95.0) <= 700 + 300 + 200)
    }

    /**
     * RC2 production-shaped soak: 110+ heard questions on a virtual clock interleaved with the noise that broke RC1 —
     * Windows beeps and screenshot shutters "transcribed" as the recognizer's own prompt, remarks addressed to another
     * panel member, variants on earlier history items, AI outages, a transcription reconnect and three pause/resume
     * cycles with typed questions. Invariants: no suppressed turn reaches memory, history or the report; one heard
     * question → one record; variants never add entries; the session stays one continuous session; bounded memory.
     */
    @Test
    fun rc2SoakWithBeepsPanelVariantsOutagesAndPausesKeepsOnlyRealQuestions() = runBlocking {
        val kb = Fixtures.testKnowledge()
        val echo = kb.context.buildTranscriptionPrompt()
        var providerRef: VirtualLatencyProvider? = null
        val s = InterviewSimulator(kb, provider = { c -> VirtualLatencyProvider(c).also { providerRef = it } })
        val provider = providerRef!!
        val e = s.e
        e.startListening()
        val rnd = DotNetRandom(11)
        val subjects = listOf(
            "the rewards ledger", "VIP onboarding", "the referral programme", "card cashback", "the Dubai launch", "the KYC vendor",
            "the support backlog", "token utility", "the loyalty tiers", "the partner API", "fraud monitoring",
            "the mobile wallet", "merchant payouts", "the savings product", "chargebacks", "the pricing page",
        ) // 160 distinct questions
        val asks = listOf(
            "How would you prioritise work on {0}?", "What metrics would you use to judge {0}?", "How would you explain {0} to the CEO?",
            "What risks do you see with {0}?", "How would you test a change to {0}?", "Who would you involve first for {0}?", "How would you cut the scope of {0}?",
            "What would you do in your first month on {0}?", "How would you handle pushback from Compliance on {0}?", "How would you measure adoption of {0}?",
        )
        var heard = 0
        var typed = 0
        var beeps = 0
        var shutters = 0
        var panel = 0
        var variants = 0
        var pauses = 0
        val usedQuestions = HashSet<String>()
        fun nextQuestion(): String {
            while (true) {
                val q = asks[rnd.next(asks.size)].replace("{0}", subjects[rnd.next(subjects.size)])
                if (usedQuestions.add(q)) return q
            }
        }
        val memStart = usedHeap()

        for (i in 1..112) {
            if (i == 70) {
                s.t.raise(ConnectionStatus.Reconnecting, "simulated drop")
                s.run(1500)
                s.t.raise(ConnectionStatus.Connected, null)
            }
            if (i == 40 || i == 41) provider.throwEx = ProviderException(ProviderErrorKind.ServiceUnavailable, "503").apply { code = "ai_unavailable" }
            if (i == 42) provider.throwEx = null
            if (i == 30 || i == 65 || i == 95) {
                pauses++
                e.pause()
                s.sound(InterviewSimulator.SoundKind.Beep, echo) // a beep while paused is never even heard
                s.run(20_000)
                val t = async { e.submitManualQuestion(nextQuestion()) }
                while (!t.isCompleted) s.run(20)
                t.await()
                typed++
                e.startListening()
            }
            if (i % 6 == 0) {
                beeps++
                s.sound(InterviewSimulator.SoundKind.Beep, echo)
                s.run(3000)
            }
            if (i % 9 == 0) {
                shutters++
                s.sound(InterviewSimulator.SoundKind.Shutter, "Thank you.")
                s.run(3000)
            }
            if (i % 11 == 0) {
                panel++
                s.say(listOf("John, what do you think about that?", "What's your view on the rollout, Sarah?")[panel % 2])
                s.run(6000)
                s.settle()
            }

            heard++
            if (rnd.nextDouble() < 0.2) {
                s.say(nextQuestion().trimEnd('?') + " for VIP clients?", 600, "And which metrics would you track to prove it is working?")
            } else {
                s.say(nextQuestion())
            }
            s.run(9000)
            s.run(22_000) // candidate answers (~20 s); interviewer silent
            s.settle()

            if (i % 10 == 0 && e.history.size > 3) {
                val before = e.history.size
                val target = e.history[rnd.next(e.history.size - 1)]
                val vt = async { e.requestVariant(target.id, listOf(AnswerStyle.VeryShort, AnswerStyle.Technical, AnswerStyle.Example)[variants % 3]) }
                while (!vt.isCompleted) s.run(20)
                vt.await()
                variants++
                assertEquals(before, e.history.size)
            }
        }
        e.stopAsync()
        val memEnd = usedHeap()

        val m = e.metrics
        val questions = e.memory.questions
        assertTrue("the RC2 soak must cover at least a 60-minute interview: ${s.clock.nowMs}", s.clock.nowMs >= 60 * 60_000)

        assertTrue(heard + typed >= 100)
        assertEquals(heard + typed - m.duplicatesSuppressed, questions.size) // one real question → one record
        assertEquals(questions.size, e.metrics.questions)
        assertEquals(minOf(questions.size, e.options.historyCapacity), e.history.size) // variants add nothing
        assertEquals(variants, m.variants)
        assertEquals(panel, m.otherDirectedSuppressed)
        assertTrue(m.suppression.toString(), m.totalSuppressed >= beeps + shutters)
        assertFalse(
            questions.any { q ->
                q.original.contains("Terms:") || q.original.startsWith("Thank you") ||
                    q.original.contains("John,") || q.original.contains("Sarah")
            },
        )
        assertEquals(typed, questions.count { q -> q.typed })
        assertEquals(1, m.reconnects)
        assertTrue("${provider.requests.get()} AI requests", provider.requests.get() <= heard + typed + variants)
        assertTrue(m.fallbackAnswers + m.apiErrors >= 2)

        val report = ReportBuilder.build(e.memory, kb, m, "soak", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), OffsetDateTime.now(ZoneOffset.UTC))
        assertEquals(questions.size, report.questions.size)
        assertEquals(m.totalSuppressed, report.excludedTurns) // disclosed as "not counted", never listed as questions
        assertTrue("memory grew ${(memEnd - memStart) / 1024} KB", memEnd - memStart < 8 * 1024 * 1024)
    }
}
