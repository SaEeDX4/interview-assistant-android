package com.interviewassistant.core.orchestration

import com.interviewassistant.core.intelligence.AnswerConfidence
import com.interviewassistant.core.intelligence.AnswerPlan
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.ExtractedQuestion
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.live.AdaptiveLevel
import com.interviewassistant.core.live.CoachOutput
import com.interviewassistant.core.live.Presentation
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.providers.ProviderErrorKind

/**
 * Mirror of `InterviewAssistant.Core/Orchestration/EngineModels.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations: the `required`/`init` properties of [AnswerView] become constructor
 * parameters; the bullet list keeps the C# lock discipline (appends and snapshots synchronize on the
 * list's own lock) because the engine appends on background threads while the UI reads.
 */

/**
 * Live states. [Quiet] is normal silence (neutral); [NoAudio] is a one-time warning when nothing
 * has been heard since listening started (likely the wrong playback device).
 *
 * [HearingAudio]: sound is being segmented but no trustworthy words exist yet (shown neutrally: a beep never
 * appears as "transcribing" or "question detected"); [SpeechDetected]: trusted words are arriving;
 * [Finalizing]: an answerable question is settling.
 */
enum class EngineStatus { Ready, Listening, SpeechDetected, Finalizing, Answering, Paused, Reconnecting, NoAudio, ApiError, Stopped, Quiet, AnswerReady, HearingAudio }

/** [Guidance]: deterministic coaching shown instead of an invented personal answer. */
enum class AnswerSource { Cache, Llm, CacheFallback, Error, Guidance }

/** Why an answer exists: a heard question, a typed question, or a rework of an existing answer. */
enum class AnswerKind { Live, Typed, Variant }

/** An answer being displayed. Bullets are append-only (stable under the reader's eyes). */
class AnswerView(
    val id: Int,
    val question: String,
    val style: AnswerStyle,
    /** Set when this answer replaces an earlier one for the same question (multi-part continuation/restatement). */
    val replacesAnswerId: Int? = null,
    /** Interviewer turn this answer belongs to (0 = typed question or variant). */
    val turnId: Int = 0,
    /** Live question, typed question, or a variant (rework) of an existing answer. */
    val kind: AnswerKind,
    /** Short interviewer context before the actual ask (empty for a direct question). */
    val contextSummary: String = "",
    /** The full interviewer turn the question was extracted from. */
    val originalText: String = "",
    val subQuestions: List<String> = listOf(),
    internal val extracted: ExtractedQuestion? = null,
) {
    var source: AnswerSource = AnswerSource.Cache
    var mode: AnswerMode = AnswerMode.Verified
    var category: String = ""
    var matchedQuestionId: String? = null
    var matchScore: Double = 0.0

    private val bulletsLock = Any()
    val bullets: MutableList<String> = mutableListOf()

    /** Thread-safe copy (engine appends on a background thread while the UI reads). */
    fun snapshotBullets(): List<String> = synchronized(bulletsLock) { bullets.toList() }

    internal fun appendBullet(b: String) {
        synchronized(bulletsLock) { bullets.add(b) }
    }

    var isComplete: Boolean = false
    var note: String? = null
    val validationFlags: MutableList<String> = mutableListOf()
    var questionIndex: Int = 0
    var detectedLanguage: String = "und"
    var answerLanguage: String = "en"
    var presentation: Presentation = Presentation.Answer
    var coach: CoachOutput? = null
    var adaptive: AdaptiveLevel = AdaptiveLevel.Normal
    val storyIds: MutableList<String> = mutableListOf()

    /** Superseded by a continuation of the same question: its output is discarded. */
    var superseded: Boolean = false
        internal set

    /** Error category when the answer failed (null on success). */
    var error: ProviderErrorKind? = null

    internal var record: QuestionRecord? = null

    /** Confirmed profile facts a personal answer must stay within (null = not a grounded personal answer). */
    internal var groundingFacts: List<String>? = null
    internal var bridgePending: Boolean = false
    var confidence: AnswerConfidence = AnswerConfidence.Medium
    var plan: AnswerPlan? = null

    /** Clears bullets (a variant re-generates the content of the same history item). */
    internal fun clearBullets() {
        synchronized(bulletsLock) { bullets.clear() }
    }
}

class EngineOptions {
    var autoTick: Boolean = true
    var tickMs: Int = 40

    /** Use the prepared answer instantly when the match is High confidence. */
    var useFastCache: Boolean = true

    /** Prepared answers below this confidence are used as AI reference context, not shown instantly. */
    var minCacheConfidence: Double = 0.7
    var defaultStyle: AnswerStyle = AnswerStyle.Balanced

    /** Seconds without audio level above the floor while listening before "NO AUDIO" is shown. */
    var noAudioWarningSeconds: Int = 20

    /**
     * After a live question is finalized, an AI request (not a prepared answer) waits this long for the interviewer
     * to continue the question; if they do, the request is never sent and the merged question is answered instead.
     * Hides most of the cost of multi-part questions with ~1 s pauses at a small latency cost.
     */
    var continuationHoldMs: Int = 300

    /** Seconds of silence before the neutral "Listening · quiet" state. */
    var quietAfterSeconds: Int = 6

    /** Questions kept in the navigable history (the whole interview; old answers are small). */
    var historyCapacity: Int = 500

    /** Acoustic evidence window opens this long before the server's speech_started (onset lag + VAD prefix). */
    var evidenceLeadMs: Int = 1000

    /** Minimum interval between live-transcript UI updates (long monologues never flood the UI thread). */
    var liveTextIntervalMs: Int = 120

    /** Characters of the live transcript shown (tail). */
    var liveTextMaxChars: Int = 360

    /** Developer own-key mode: credential errors point to Settings instead of sign-in. */
    var developerKeyMode: Boolean = true
}
