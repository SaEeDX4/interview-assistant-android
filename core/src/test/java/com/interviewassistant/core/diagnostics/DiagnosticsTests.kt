package com.interviewassistant.core.diagnostics

import java.time.Duration
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests for the `InterviewAssistant.Core/Diagnostics` types (P7 coverage closure — the
 * package had no dedicated suite): [SessionMetrics] counters, its bounded sample history, the
 * suppression derivation, the cost estimate and the percentile rule from `Metrics.cs`;
 * [ExponentialBackoff]'s doubling/cap/reset from `Backoff.cs`; the clocks from `Clock.cs`; and
 * the `InstallLayout.cs` path rules beyond the cases the client suite pins.
 */
class DiagnosticsTests {

    private fun sample(question: String): LatencySample = LatencySample(
        question = question, source = "test",
        speechEndToFinalizedMs = 10, matchMs = 1, finalizedToFirstTokenMs = 20,
        finalizedToFirstBulletMs = 30, finalizedToCompleteMs = 40, speechEndToFirstBulletMs = 50,
        at = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    // ---- SessionMetrics ----

    @Test
    fun metrics_CountersStartAtZero_AndEachIncrementMovesOnlyItsOwnCounter() {
        val m = SessionMetrics()
        assertEquals(0, m.questions)
        assertEquals(0, m.totalSuppressed)
        assertTrue(m.samples.isEmpty())
        m.incQuestions(); m.incQuestions(); m.incCache(); m.incLlm(); m.incFallback(); m.incApiError()
        m.incDuplicate(); m.incReconnect(); m.incLlmRequest(); m.incMerged()
        assertEquals(2, m.questions)
        assertEquals(1, m.cacheAnswers)
        assertEquals(1, m.llmAnswers)
        assertEquals(1, m.fallbackAnswers)
        assertEquals(1, m.apiErrors)
        assertEquals(1, m.duplicatesSuppressed)
        assertEquals(1, m.reconnects)
        assertEquals(1, m.llmRequests)
        assertEquals(1, m.turnsMerged)
    }

    @Test
    fun metrics_TotalSuppressed_SumsExactlyTheEightSuppressionCounters() {
        val m = SessionMetrics()
        m.incNonSpeech(); m.incPromptEcho(); m.incPromptEcho(); m.incShortEvent(); m.incInvalid()
        m.incOtherDirected(); m.incLogistics(); m.incImplausible(); m.incContextHallucination()
        // These are tracked but are NOT part of TotalSuppressed in the C#.
        m.incContextHeld(); m.incWeakSupport(); m.incFollowUp(); m.incFollowUpTurn(); m.incVariant()
        m.incGuidance(); m.incRedundantDropped()
        assertEquals(9, m.totalSuppressed)
        assertEquals(1, m.contextHeld)
        assertEquals(1, m.weakSupportAccepted)
        assertEquals(1, m.followUpsAccepted)
        assertEquals(1, m.followUpTurnsSplit)
        assertEquals(1, m.variants)
        assertEquals(1, m.guidanceAnswers)
        assertEquals(1, m.redundantBulletsDropped)
    }

    @Test
    fun metrics_SuppressionMap_CarriesTheDiagnosticNamesInOrder() {
        val m = SessionMetrics()
        m.incNonSpeech(); m.incContextHeld(); m.incFollowUp()
        val s = m.suppression
        assertEquals(
            listOf(
                "non_speech_suppressed", "prompt_echo_suppressed", "short_acoustic_event_suppressed",
                "invalid_turn_suppressed", "other_directed_suppressed", "logistics_suppressed",
                "implausible_transcript_suppressed", "context_hallucination_suppressed",
                "context_held", "weak_acoustic_support_accepted", "follow_up_accepted", "follow_up_new_turn",
            ),
            s.keys.toList(),
        )
        assertEquals(1, s["non_speech_suppressed"])
        assertEquals(0, s["prompt_echo_suppressed"])
        assertEquals(1, s["context_held"])
        assertEquals(1, s["follow_up_accepted"])
    }

    @Test
    fun metrics_SampleHistory_IsBoundedByCapacity_AndKeepsTheNewest() {
        val m = SessionMetrics(capacity = 3)
        for (i in 1..5) m.add(sample("q$i"))
        assertEquals(listOf("q3", "q4", "q5"), m.samples.map { it.question })
        val big = SessionMetrics()
        assertEquals(200, big.capacity)
        for (i in 1..201) big.add(sample("q$i"))
        assertEquals(200, big.samples.size)
        assertEquals("q2", big.samples.first().question)
    }

    @Test
    fun metrics_EstimatedCost_CombinesAudioAndTokenEstimates() {
        val m = SessionMetrics()
        m.audioMsSent = 120_000 // 2 minutes of audio
        m.approxLlmInputChars = 4_000_000 // ~1M tokens at 4 chars/token
        m.approxLlmOutputChars = 2_000_000 // ~0.5M tokens
        // 2 * 0.10 + 1 * 2.00 + 0.5 * 4.00 = 4.20
        assertEquals(4.20, m.estimatedCostUsd(0.10, 2.00, 4.00), 1e-9)
        assertEquals(0.0, SessionMetrics().estimatedCostUsd(0.10, 2.00, 4.00), 1e-12)
    }

    @Test
    fun metrics_Percentile_FiltersNegatives_Sorts_AndClampsTheIndex() {
        assertEquals(-1L, SessionMetrics.percentile(emptyList(), 95.0))
        assertEquals(-1L, SessionMetrics.percentile(listOf(-5L, -1L), 95.0))
        assertEquals(7L, SessionMetrics.percentile(listOf(7L), 99.0))
        val values = listOf(40L, 10L, 30L, 20L)
        assertEquals(10L, SessionMetrics.percentile(values, 0.0))
        assertEquals(20L, SessionMetrics.percentile(values, 50.0))
        assertEquals(40L, SessionMetrics.percentile(values, 95.0))
        assertEquals(40L, SessionMetrics.percentile(values, 100.0))
        // Negatives are dropped before ranking: [-5, 10, 20] ranks over [10, 20].
        assertEquals(10L, SessionMetrics.percentile(listOf(-5L, 10L, 20L), 50.0))
        assertEquals(20L, SessionMetrics.percentile(listOf(-5L, 10L, 20L), 100.0))
    }

    @Test
    fun latencySample_CarriesItsFields() {
        val s = sample("q")
        assertEquals("q", s.question)
        assertEquals(10L, s.speechEndToFinalizedMs)
        assertEquals(50L, s.speechEndToFirstBulletMs)
        assertEquals(s, sample("q"))
    }

    // ---- ExponentialBackoff ----

    @Test
    fun backoff_DoublesFromOneSecond_WithinTheJitterBand() {
        val b = ExponentialBackoff()
        assertEquals(0, b.attempt)
        val first = b.next().toMillis()
        assertTrue("first=$first", first in 850..1150) // 1s ± 15%
        assertEquals(1, b.attempt)
        val second = b.next().toMillis()
        assertTrue("second=$second", second in 1700..2300) // 2s ± 15%
        val third = b.next().toMillis()
        assertTrue("third=$third", third in 3400..4600) // 4s ± 15%
    }

    @Test
    fun backoff_NeverExceedsTheCap_AndResetStartsOver() {
        val b = ExponentialBackoff()
        var last = 0L
        repeat(12) { last = b.next().toMillis() }
        assertEquals(12, b.attempt)
        assertTrue("capped=$last", last in 25_500..30_000) // base capped at 30s, jitter applied, capped again
        b.reset()
        assertEquals(0, b.attempt)
        val again = b.next().toMillis()
        assertTrue("after reset=$again", again in 850..1150)
    }

    @Test
    fun backoff_HonoursCustomInitialAndMax() {
        val b = ExponentialBackoff(Duration.ofSeconds(2), Duration.ofSeconds(5))
        assertTrue(b.next().toMillis() in 1700..2300)
        assertTrue(b.next().toMillis() in 3400..4600)
        val third = b.next().toMillis()
        assertTrue("third=$third", third in 4250..5000) // base min(5s, 8s) = 5s
    }

    // ---- clocks ----

    @Test
    fun clocks_ManualAdvances_AndSystemIsMonotonic() {
        val manual = ManualClock()
        assertEquals(0L, manual.nowMs)
        manual.advance(150)
        manual.advance(50)
        assertEquals(200L, manual.nowMs)
        val system = SystemClock()
        val a = system.nowMs
        val b = system.nowMs
        assertTrue(a >= 0)
        assertTrue(b >= a)
    }

    // ---- InstallLayout (beyond the client suite's cases) ----

    @Test
    fun installLayout_ForwardSlashesCaseAndTrailingSeparators() {
        val update = "C:/Users/u/AppData/Local/App/Update.exe"
        // Path.Combine joins with '\' even when the existing path uses '/', exactly as on Windows.
        assertTrue(InstallLayout.isInstalled("C:/Users/u/AppData/Local/App/current/App.exe") { p -> p == "C:/Users/u/AppData/Local/App\\Update.exe" })
        assertTrue(InstallLayout.isInstalled("C:\\Users\\u\\AppData\\Local\\App\\CURRENT\\App.exe") { p -> p.replace('/', '\\') == update.replace('/', '\\') })
        assertFalse(InstallLayout.isInstalled("C:\\Users\\u\\AppData\\Local\\App\\App.exe") { true }) // no "current" folder
        assertFalse(InstallLayout.isInstalled("C:\\Users\\u\\AppData\\Local\\App\\current\\App.exe") { false }) // Update.exe missing
        assertFalse(InstallLayout.isInstalled("App.exe") { true }) // no directory at all
        assertFalse(InstallLayout.isInstalled("   ") { true })
        assertFalse(InstallLayout.isInstalled("") { true })
    }

    @Test
    fun installLayout_DriveRootKeepsItsSeparator_WhenCheckingForUpdateExe() {
        val seen = mutableListOf<String>()
        val installed = InstallLayout.isInstalled("C:\\current\\App.exe") { p -> seen += p; true }
        assertTrue(installed)
        assertEquals(listOf("C:\\Update.exe"), seen)
    }
}
