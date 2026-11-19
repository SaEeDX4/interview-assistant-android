package com.interviewassistant.core.diagnostics

import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Mirror of `InterviewAssistant.Core/Diagnostics/Metrics.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class LatencySample(
    val question: String,
    val source: String,
    val speechEndToFinalizedMs: Long,
    val matchMs: Long,
    val finalizedToFirstTokenMs: Long,
    val finalizedToFirstBulletMs: Long,
    val finalizedToCompleteMs: Long,
    val speechEndToFirstBulletMs: Long,
    val at: OffsetDateTime,
)

/** Thread-safe counters + bounded latency history for the diagnostics panel and soak tests. */
class SessionMetrics(val capacity: Int = 200) {
    private val samplesQueue = ConcurrentLinkedQueue<LatencySample>()
    private val questionsCounter = AtomicInteger()
    private val cacheAnswersCounter = AtomicInteger()
    private val llmAnswersCounter = AtomicInteger()
    private val fallbackAnswersCounter = AtomicInteger()
    private val apiErrorsCounter = AtomicInteger()
    private val duplicatesCounter = AtomicInteger()
    private val reconnectsCounter = AtomicInteger()
    private val llmRequestsCounter = AtomicInteger()
    private val mergedCounter = AtomicInteger()
    private val nonSpeechCounter = AtomicInteger()
    private val promptEchoCounter = AtomicInteger()
    private val shortEventCounter = AtomicInteger()
    private val invalidCounter = AtomicInteger()
    private val otherDirectedCounter = AtomicInteger()
    private val logisticsCounter = AtomicInteger()
    private val contextHeldCounter = AtomicInteger()
    private val weakSupportCounter = AtomicInteger()
    private val implausibleCounter = AtomicInteger()
    private val contextHallucinationCounter = AtomicInteger()
    private val variantsCounter = AtomicInteger()
    private val guidanceCounter = AtomicInteger()
    private val redundantDroppedCounter = AtomicInteger()
    private val followUpsCounter = AtomicInteger()
    private val followUpTurnsCounter = AtomicInteger()

    var audioMsSent: Long = 0

    val questions: Int get() = questionsCounter.get()
    val cacheAnswers: Int get() = cacheAnswersCounter.get()
    val llmAnswers: Int get() = llmAnswersCounter.get()
    val fallbackAnswers: Int get() = fallbackAnswersCounter.get()
    val apiErrors: Int get() = apiErrorsCounter.get()
    val duplicatesSuppressed: Int get() = duplicatesCounter.get()
    val reconnects: Int get() = reconnectsCounter.get()
    val llmRequests: Int get() = llmRequestsCounter.get()

    /** Multi-part questions joined after a pause (one answer instead of two). */
    val turnsMerged: Int get() = mergedCounter.get()
    var approxLlmInputChars: Long = 0
    var approxLlmOutputChars: Long = 0

    /** Turns whose audio contained no human speech (non_speech_suppressed). */
    val nonSpeechSuppressed: Int get() = nonSpeechCounter.get()

    /** Recognizer reproduced its vocabulary prompt (prompt_echo_suppressed). */
    val promptEchoSuppressed: Int get() = promptEchoCounter.get()

    /** Beeps, chimes, clicks, screenshot sounds (short_acoustic_event_suppressed). */
    val shortAcousticEventSuppressed: Int get() = shortEventCounter.get()

    /** Recognizer artefacts and other invalid turns (invalid_turn_suppressed). */
    val invalidTurnSuppressed: Int get() = invalidCounter.get()
    val otherDirectedSuppressed: Int get() = otherDirectedCounter.get()
    val logisticsSuppressed: Int get() = logisticsCounter.get()

    /** Context statements held for the next question (not answered on their own). */
    val contextHeld: Int get() = contextHeldCounter.get()

    /** Questions accepted with only weak acoustic support for their words (diagnostics; they had a real voice). */
    val weakSupportAccepted: Int get() = weakSupportCounter.get()

    /** Transcripts with far more words than the voiced audio could hold (implausible_transcript_suppressed). */
    val implausibleTranscriptSuppressed: Int get() = implausibleCounter.get()

    /** Interview-flavoured sentences built from the prompt vocabulary over too little speech (context_hallucination_suppressed). */
    val contextHallucinationSuppressed: Int get() = contextHallucinationCounter.get()

    /** Candidate-directed follow-ups answered ("Tell me more.", "Go on.", "Why?"). */
    val followUpsAccepted: Int get() = followUpsCounter.get()

    /** Follow-ups that came quickly after a complete question and became their own turn instead of merging. */
    val followUpTurnsSplit: Int get() = followUpTurnsCounter.get()
    val variants: Int get() = variantsCounter.get()
    val guidanceAnswers: Int get() = guidanceCounter.get()
    val redundantBulletsDropped: Int get() = redundantDroppedCounter.get()
    val totalSuppressed: Int
        get() = nonSpeechCounter.get() + promptEchoCounter.get() + shortEventCounter.get() + invalidCounter.get() +
            otherDirectedCounter.get() + logisticsCounter.get() + implausibleCounter.get() + contextHallucinationCounter.get()

    fun add(s: LatencySample) {
        samplesQueue.offer(s)
        while (samplesQueue.size > capacity && samplesQueue.poll() != null) {
        }
    }

    val samples: List<LatencySample> get() = samplesQueue.toList()

    fun incQuestions() {
        questionsCounter.incrementAndGet()
    }

    fun incCache() {
        cacheAnswersCounter.incrementAndGet()
    }

    fun incLlm() {
        llmAnswersCounter.incrementAndGet()
    }

    fun incFallback() {
        fallbackAnswersCounter.incrementAndGet()
    }

    fun incApiError() {
        apiErrorsCounter.incrementAndGet()
    }

    fun incDuplicate() {
        duplicatesCounter.incrementAndGet()
    }

    fun incReconnect() {
        reconnectsCounter.incrementAndGet()
    }

    fun incLlmRequest() {
        llmRequestsCounter.incrementAndGet()
    }

    fun incMerged() {
        mergedCounter.incrementAndGet()
    }

    fun incNonSpeech() {
        nonSpeechCounter.incrementAndGet()
    }

    fun incPromptEcho() {
        promptEchoCounter.incrementAndGet()
    }

    fun incShortEvent() {
        shortEventCounter.incrementAndGet()
    }

    fun incInvalid() {
        invalidCounter.incrementAndGet()
    }

    fun incOtherDirected() {
        otherDirectedCounter.incrementAndGet()
    }

    fun incLogistics() {
        logisticsCounter.incrementAndGet()
    }

    fun incContextHeld() {
        contextHeldCounter.incrementAndGet()
    }

    fun incWeakSupport() {
        weakSupportCounter.incrementAndGet()
    }

    fun incImplausible() {
        implausibleCounter.incrementAndGet()
    }

    fun incContextHallucination() {
        contextHallucinationCounter.incrementAndGet()
    }

    fun incFollowUp() {
        followUpsCounter.incrementAndGet()
    }

    fun incFollowUpTurn() {
        followUpTurnsCounter.incrementAndGet()
    }

    fun incVariant() {
        variantsCounter.incrementAndGet()
    }

    fun incGuidance() {
        guidanceCounter.incrementAndGet()
    }

    fun incRedundantDropped() {
        redundantDroppedCounter.incrementAndGet()
    }

    /** Suppression counters with their diagnostic names (no transcript content). */
    val suppression: Map<String, Int>
        get() = linkedMapOf(
            "non_speech_suppressed" to nonSpeechCounter.get(),
            "prompt_echo_suppressed" to promptEchoCounter.get(),
            "short_acoustic_event_suppressed" to shortEventCounter.get(),
            "invalid_turn_suppressed" to invalidCounter.get(),
            "other_directed_suppressed" to otherDirectedCounter.get(),
            "logistics_suppressed" to logisticsCounter.get(),
            "implausible_transcript_suppressed" to implausibleCounter.get(),
            "context_hallucination_suppressed" to contextHallucinationCounter.get(),
            "context_held" to contextHeldCounter.get(),
            "weak_acoustic_support_accepted" to weakSupportCounter.get(),
            "follow_up_accepted" to followUpsCounter.get(),
            "follow_up_new_turn" to followUpTurnsCounter.get(),
        )

    /** Approximate session cost in USD from configurable unit prices (estimate only). */
    fun estimatedCostUsd(transcribePerMinute: Double, inputPerMTok: Double, outputPerMTok: Double): Double =
        audioMsSent / 60000.0 * transcribePerMinute + approxLlmInputChars / 4.0 / 1e6 * inputPerMTok + approxLlmOutputChars / 4.0 / 1e6 * outputPerMTok

    companion object {
        fun percentile(values: Iterable<Long>, p: Double): Long {
            val arr = values.filter { it >= 0 }.sorted()
            if (arr.isEmpty()) return -1
            val idx = ceil(p / 100.0 * arr.size).toInt() - 1
            return arr[idx.coerceIn(0, arr.size - 1)]
        }
    }
}
