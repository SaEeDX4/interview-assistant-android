package com.interviewassistant.core.orchestration

import com.interviewassistant.core.audio.AcousticAnalyzer
import com.interviewassistant.core.audio.PitchRunTracker
import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.audio.SpeechEvidenceTimeline
import com.interviewassistant.core.diagnostics.IClock
import com.interviewassistant.core.diagnostics.LatencySample
import com.interviewassistant.core.diagnostics.SessionMetrics
import com.interviewassistant.core.diagnostics.SystemClock
import com.interviewassistant.core.ingestion.ResumeParser
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.AcousticSupport
import com.interviewassistant.core.intelligence.AnswerPlanner
import com.interviewassistant.core.intelligence.AnswerQualityGate
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.AnswerStyleSpec
import com.interviewassistant.core.intelligence.BulletStreamParser
import com.interviewassistant.core.intelligence.Classification
import com.interviewassistant.core.intelligence.ContextRetriever
import com.interviewassistant.core.intelligence.ConversationTurn
import com.interviewassistant.core.turn.DuplicateGuard
import com.interviewassistant.core.intelligence.EvidenceIndex
import com.interviewassistant.core.intelligence.ExtractedQuestion
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.FollowUpIntent
import com.interviewassistant.core.languages.LanguageDetector
import com.interviewassistant.core.intelligence.MatchConfidence
import com.interviewassistant.core.intelligence.MatchResult
import com.interviewassistant.core.intelligence.PendingContext
import com.interviewassistant.core.intelligence.PersonalGrounding
import com.interviewassistant.core.intelligence.PersonalTopic
import com.interviewassistant.core.intelligence.PromptBuilder
import com.interviewassistant.core.intelligence.PromptRequest
import com.interviewassistant.core.intelligence.QuestionClassifier
import com.interviewassistant.core.intelligence.QuestionExtractor
import com.interviewassistant.core.intelligence.QuestionMatcher
import com.interviewassistant.core.intelligence.RetrievedContext
import com.interviewassistant.core.intelligence.SpeechPlausibility
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.intelligence.UtteranceGate
import com.interviewassistant.core.intelligence.UtteranceKind
import com.interviewassistant.core.intelligence.UtteranceVerdict
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.BankQuestion
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.live.AdaptiveLevel
import com.interviewassistant.core.live.CoachOutput
import com.interviewassistant.core.live.CoachPrompt
import com.interviewassistant.core.live.HeardEvidence
import com.interviewassistant.core.live.InterruptionEvent
import com.interviewassistant.core.live.InterruptionTracker
import com.interviewassistant.core.live.Presentation
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.live.SessionMemory
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.IAnswerProvider
import com.interviewassistant.core.providers.ITranscriber
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import com.interviewassistant.core.turn.FinalizedTurn
import com.interviewassistant.core.turn.TurnDetector
import com.interviewassistant.core.turn.TurnState
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirror of `InterviewAssistant.Core/Orchestration/InterviewEngine.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * Orchestrates the live pipeline:
 *   audio (+ acoustic speech evidence) → transcriber → TurnDetector → UtteranceGate (speech? echo? question? for whom?)
 *   → QuestionExtractor (core ask + context) → classify + match → AnswerPlanner (local) → prepared answer OR one LLM
 *   stream → FactValidator + AnswerQualityGate → stable bullets.
 * All events may fire on background threads; UI must marshal to its dispatcher.
 * One finalized question causes at most one LLM request (none on a high-confidence cache hit). Non-speech sounds,
 * prompt echoes, logistics and remarks addressed to someone else never reach the answer path. A continued question
 * replaces the SAME history entry; a variant reworks the SAME history entry it was requested for.
 *
 * C#→Kotlin adaptations (DC-42): C# events become listener lists (the P2 contract pattern); each answer's
 * `CancellationTokenSource` becomes a [Job] child of the caller's job — cancelling it aborts the hold and
 * the provider stream exactly where the token did, and the body's own catch/finally structure is preserved,
 * so a superseded answer completes normally with the "superseded" note, never as a cancelled coroutine.
 * `HandleFinalizedAsync` is launched UNDISPATCHED so its synchronous prefix (gate, extraction, history
 * insertion) runs on the raising thread like the C# fire-and-forget task.
 */
class InterviewEngine(
    private val kb: KnowledgeBase,
    private val transcriber: ITranscriber?,
    private val provider: IAnswerProvider?,
    options: EngineOptions? = null,
    clock: IClock? = null,
) {
    val options: EngineOptions = options ?: EngineOptions()
    private val clock: IClock = clock ?: SystemClock()
    val metrics: SessionMetrics = SessionMetrics()

    var status: EngineStatus = EngineStatus.Ready
        private set
    var statusDetail: String? = null
        private set

    private val matcher: QuestionMatcher = QuestionMatcher(kb.questions)
    private val retriever: ContextRetriever = ContextRetriever(kb)
    private val prompts: PromptBuilder = PromptBuilder(kb)
    private val evidence: EvidenceIndex = EvidenceIndex.`for`(kb)
    private val validator: FactValidator = FactValidator(kb.profile, kb.context, evidence)
    val gate: UtteranceGate = UtteranceGate.`for`(kb)
    private val pending: PendingContext = PendingContext()
    private val timeline: SpeechEvidenceTimeline = SpeechEvidenceTimeline()
    private val pitchRuns: PitchRunTracker = PitchRunTracker() // fed on the capture thread; OpenRun read by the partial trust boundary (thread-safe)
    private var segmentStartedAt: Long = -1

    /** Start of the most recent segment's evidence window (kept after speech stops: partials arrive late). */
    private var partialWindowFrom: Long = -1
    private var inSegment: Boolean = false
    private val turnAnswers: MutableMap<Int, AnswerView> = mutableMapOf()
    private var hold: CompletableDeferred<Boolean>? = null
    private var holdUntil: Long = -1
    private var holdExtended: Boolean = false
    private val dupes: DuplicateGuard = DuplicateGuard()
    private val session: SessionMemory = SessionMemory()
    private val interrupt: InterruptionTracker = InterruptionTracker()
    private val historyList: MutableList<AnswerView> = mutableListOf()
    private val gateLock = Any()
    private val answerJob = AtomicReference<Job?>(null)
    private val variantJob = AtomicReference<Job?>(null)
    private var runJob: Job? = null
    private var tickJob: Job? = null
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val nextAnswerId = AtomicInteger(0)
    @Volatile
    private var lastAnsweredIndex: Int = -1 // record index of the last question whose (non-variant) answer completed
    private var lastAudioActivityMs: Long = 0
    @Volatile
    private var listening: Boolean = false
    @Volatile
    private var paused: Boolean = false
    private var heardAudio: Boolean = false
    private var noAudioWarned: Boolean = false
    private var liveText: String? = null
    private var liveDirty: Boolean = false
    private var liveEmittedAt: Long = Long.MIN_VALUE / 2

    val turn: TurnDetector
    val knowledge: KnowledgeBase get() = kb
    val memory: SessionMemory get() = session

    /** The answer for the most recent QUESTION (a variant of an older item never changes it). */
    var current: AnswerView? = null
        private set
    val adaptive: AdaptiveLevel get() = interrupt.level

    /** Answer (3 bullets) or Coach (3 keywords + structure). Switchable live via hotkey. */
    var presentation: Presentation = Presentation.Answer

    /** null = use target setting ("same" follows the interviewer); otherwise a language code. */
    var answerLanguageOverride: String? = null

    val adaptiveChangedListeners: MutableList<(AdaptiveLevel) -> Unit> = mutableListOf()
    val interruptionDetectedListeners: MutableList<(InterruptionEvent) -> Unit> = mutableListOf()

    /** A finalized turn that was not answered (kind + reason, never the transcript) — diagnostics only. */
    val turnSuppressedListeners: MutableList<(UtteranceVerdict) -> Unit> = mutableListOf()
    val statusChangedListeners: MutableList<(EngineStatus, String?) -> Unit> = mutableListOf()

    /** The live transcription connection itself (connected / reconnecting / failed), independent of the status line, which answers and other states overwrite. */
    val transcriberConnectionChangedListeners: MutableList<(ConnectionStatus) -> Unit> = mutableListOf()
    val liveTranscriptChangedListeners: MutableList<(String) -> Unit> = mutableListOf()
    val answerStartedListeners: MutableList<(AnswerView) -> Unit> = mutableListOf() // new answer card (question visible immediately)
    val bulletAddedListeners: MutableList<(AnswerView, String) -> Unit> = mutableListOf() // append-only
    val pendingTextChangedListeners: MutableList<(AnswerView, String) -> Unit> = mutableListOf() // preview of the bullet being written
    val answerCompletedListeners: MutableList<(AnswerView) -> Unit> = mutableListOf()

    /** A variant was cancelled or produced nothing: (failed variant, original answer now back in its history slot). */
    val answerRestoredListeners: MutableList<(AnswerView, AnswerView) -> Unit> = mutableListOf()
    val latencyMeasuredListeners: MutableList<(LatencySample) -> Unit> = mutableListOf()
    val logListeners: MutableList<(String) -> Unit> = mutableListOf()

    init {
        val detector = TurnDetector(this.clock)
        detector.aliases = kb.context.aliases
        detector.termNormalizer = kb.context::normalizeTerms
        detector.segmentFilter = ::keepSegment
        detector.partialFilter = ::trustPartial
        detector.displayTailChars = maxOf(1000, this.options.liveTextMaxChars * 2)
        turn = detector
        detector.addLiveTextChangedListener { t ->
            liveText = t
            liveDirty = true
            // Words are only "being transcribed" once they are trusted; until then the status stays neutral.
            if (status == EngineStatus.HearingAudio && detector.hasTrustedText) setStatus(EngineStatus.SpeechDetected)
        }
        detector.addStateChangedListener(::onTurnState)
        detector.addTurnFinalizedListener { t ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { handleFinalized(t) }
        }
        detector.addFollowUpSplitListener { k ->
            metrics.incFollowUpTurn()
            fireLog("Turn accepted: ${k.name.lowercase()} follow-up after a complete question - new turn, not merged")
        }
        interrupt.addLevelChangedListener { l -> for (listener in adaptiveChangedListeners.toList()) listener(l) }

        if (transcriber != null) {
            transcriber.speechStartedListeners.add(::onInterviewerSpeechStarted)
            transcriber.speechStoppedListeners.add { onInterviewerSpeechStopped() }
            transcriber.partialTranscriptListeners.add { id, d -> synchronized(gateLock) { detector.onPartial(id, d) } }
            transcriber.segmentCompletedListeners.add { id, t -> synchronized(gateLock) { detector.onSegmentCompleted(id, t) } }
            transcriber.statusChangedListeners.add(::onTranscriberStatus)
        }
    }

    fun togglePresentation(): Presentation {
        presentation = if (presentation == Presentation.Answer) Presentation.Coach else Presentation.Answer
        return presentation
    }

    fun resetAdaptive() = interrupt.reset()

    /** Restores memory after a crash/disconnect (same session). */
    fun restoreMemory(m: SessionMemory) {
        session.questions.addAll(m.questions)
        session.interruptions.addAll(m.interruptions)
        for ((key, value) in m.storyUse) session.storyUse[key] = value
        for ((key, value) in m.storyLastUsedAt) session.storyLastUsedAt[key] = value
        for ((key, value) in m.metricUse) session.metricUse[key] = value
        for ((key, value) in m.topicCounts) session.topicCounts[key] = value
        session.rollingSummary = m.rollingSummary
    }

    /** Interviewer speech started (from transcriber, or tests): feeds the turn detector and the hold. */
    fun onInterviewerSpeechStarted() {
        synchronized(gateLock) {
            if (!inSegment) {
                segmentStartedAt = clock.nowMs
                inSegment = true
                partialWindowFrom = segmentStartedAt - options.evidenceLeadMs
            }
            val continuation = turn.onSpeechStarted()
            // The interviewer is finishing the same question: keep a pending AI request on hold until the turn settles.
            if (continuation && hold != null) holdExtended = true
        }
        // Interruption/pace is evaluated when the turn is accepted as a real question (a beep or a remark to a colleague
        // must never shorten the candidate's answers) — see handleFinalized.
    }

    private fun onInterviewerSpeechStopped() {
        synchronized(gateLock) {
            val now = clock.nowMs
            // Without the segment's start (lost in a reconnect) its audio window is unknown: abstain, never judge the words
            // by a fragment of the audio.
            var evidence = if (segmentStartedAt >= 0) timeline.sum(segmentStartedAt - options.evidenceLeadMs, now) else SpeechEvidence.Unheard
            // A pitch run still open at the stop (a tone that outlasts the speech VAD) counts as the live trust boundary saw
            // it: the final decision must not see less of a held note than the partial check did.
            if (!evidence.unknown) {
                val open = pitchRuns.openRun()
                evidence = evidence.copy(
                    runHops = evidence.runHops + open.runs,
                    flatRunHops = evidence.flatRunHops + open.flat,
                    stretchHops = evidence.stretchHops + open.stretch,
                )
            }
            inSegment = false
            segmentStartedAt = -1
            turn.onSpeechStopped(evidence, transcriber?.lastStoppedItemId)
        }
    }

    // ---------------- lifecycle ----------------

    /** Starts or resumes listening. The interview session (memory, history, numbering) continues across pauses. */
    suspend fun startListening() {
        if (listening && !paused) return
        paused = false
        listening = true
        runJob?.cancel()
        val rj = Job(scopeJob)
        runJob = rj
        synchronized(gateLock) {
            turn.start()
            inSegment = false
            segmentStartedAt = -1
        }
        timeline.clear()
        pitchRuns.reset()
        lastAudioActivityMs = clock.nowMs
        heardAudio = false
        noAudioWarned = false
        transcriber?.startAsync()
        if (options.autoTick) {
            tickJob = scope.launch(rj) {
                while (isActive) {
                    delay(options.tickMs.toLong())
                    tick()
                }
            }
        }
        setStatus(EngineStatus.Listening)
    }

    /** Pause: stop sending audio and stop detecting turns. History and memory are kept; typed questions still work. */
    fun pause() {
        synchronized(gateLock) { releaseHold(true) } // a typed or pending question still gets its answer while paused
        paused = true
        synchronized(gateLock) { turn.stop() }
        pending.clear()
        liveText = ""
        liveDirty = true
        flushLiveText(force = true)
        setStatus(EngineStatus.Paused)
    }

    /**
     * Closes the transcription connection while paused (a cloud pause ends the billable lease, and with it the relay);
     * [startListening] reconnects. History, memory and typed questions are unaffected.
     */
    suspend fun suspendTranscription() {
        if (transcriber != null && paused) transcriber.stopAsync()
    }

    /** Reconnects transcription if its connection loop ended while listening (e.g. a replaced live lease). */
    suspend fun ensureTranscription() {
        if (transcriber != null && isListening && runJob != null) transcriber.startAsync()
    }

    suspend fun stopAsync() {
        synchronized(gateLock) { releaseHold(true) }
        listening = false
        paused = false
        runJob?.cancel()
        cancelAnswer(includeVariants = true)
        synchronized(gateLock) { turn.stop() }
        pending.clear()
        transcriber?.stopAsync()
        val tj = tickJob
        if (tj != null) {
            try {
                tj.join()
            } catch (e: CancellationException) {
            }
        }
        interrupt.reset() // pace adaptation belongs to one interview
        setStatus(EngineStatus.Stopped)
    }

    val isListening: Boolean get() = listening && !paused
    val isPaused: Boolean get() = listening && paused

    /** Feed converted 24 kHz mono PCM16 frames from the capture layer (capture thread). */
    fun onAudioFrame(pcm16: ByteArray, levelDb: Double) {
        if (!isListening) return
        val now = clock.nowMs
        if (levelDb > -55) {
            lastAudioActivityMs = now
            heardAudio = true
        }
        timeline.add(now, AcousticAnalyzer.analyze(pcm16, pitchRuns))
        metrics.audioMsSent += pcm16.size / 48
        transcriber?.sendAudio(pcm16)
    }

    /** Advances time-based logic (turn settling, live text, no-audio warning). Public for deterministic tests. */
    fun tick() {
        synchronized(gateLock) {
            turn.tick()
            if (hold != null && !holdExtended && clock.nowMs >= holdUntil) releaseHold(true)
        }
        flushLiveText(force = false)
        val silentMs = clock.nowMs - lastAudioActivityMs
        if (!isListening) return
        // Silence after the interviewer finishes is normal (the candidate is talking). Warn only when NOTHING has been
        // heard since listening started — that usually means the meeting plays on a different device — and only once.
        if (!heardAudio && !noAudioWarned && (status == EngineStatus.Listening || status == EngineStatus.Quiet) && silentMs > options.noAudioWarningSeconds * 1000L) {
            noAudioWarned = true
            setStatus(EngineStatus.NoAudio, Loc.t("detail_no_audio"))
        } else if (status == EngineStatus.Listening && silentMs > options.quietAfterSeconds * 1000L) {
            setStatus(EngineStatus.Quiet)
        } else if ((status == EngineStatus.NoAudio || status == EngineStatus.Quiet) && silentMs < 1000) {
            setStatus(EngineStatus.Listening)
        }
    }

    /** Throttled live transcript: at most one update per [EngineOptions.liveTextIntervalMs], tail only. */
    private fun flushLiveText(force: Boolean) {
        if (!liveDirty) return
        val now = clock.nowMs
        if (!force && now - liveEmittedAt < options.liveTextIntervalMs) return
        liveDirty = false
        liveEmittedAt = now
        var t = liveText ?: ""
        if (t.length > options.liveTextMaxChars) t = "…" + t.substring(t.length - options.liveTextMaxChars).trimStart()
        for (listener in liveTranscriptChangedListeners.toList()) listener(t)
    }

    private fun onTurnState(s: TurnState) {
        // A continuation that turned out to be "okay"/"mm-hmm" (or never transcribed) releases the held request.
        if (s == TurnState.Listening && hold != null && holdExtended) releaseHold(true)
        if (!isListening || status == EngineStatus.Reconnecting || status == EngineStatus.ApiError) return
        if (status == EngineStatus.Answering && s != TurnState.SpeechStarted) return
        when (s) {
            // Sound started: neutral until trusted words arrive (a beep is never shown as "transcribing").
            TurnState.SpeechStarted -> setStatus(if (turn.hasTrustedText) EngineStatus.SpeechDetected else EngineStatus.HearingAudio)
            // "Question detected" only for text the gate would answer with the evidence heard so far.
            TurnState.TurnSettling -> {
                if (gate.evaluate(turn.currentText(), turn.turnEvidence).answerable) {
                    setStatus(EngineStatus.Finalizing)
                } else if (turn.hasTrustedText) {
                    setStatus(EngineStatus.SpeechDetected)
                }
            }
            TurnState.Listening -> if (status == EngineStatus.HearingAudio || status == EngineStatus.SpeechDetected || status == EngineStatus.Finalizing) {
                setStatus(EngineStatus.Listening)
            }
            else -> {}
        }
    }

    private fun onTranscriberStatus(s: ConnectionStatus, detail: String?) {
        when (s) {
            ConnectionStatus.Connected -> if (isListening) setStatus(EngineStatus.Listening, detail)
            ConnectionStatus.Reconnecting -> {
                metrics.incReconnect()
                setStatus(EngineStatus.Reconnecting, detail)
                synchronized(gateLock) {
                    turn.onConnectionInterrupted()
                    inSegment = false
                    segmentStartedAt = -1
                } // keep completed segments
            }
            ConnectionStatus.Failed -> {
                metrics.incApiError()
                setStatus(EngineStatus.ApiError, detail)
            }
            else -> {}
        }
        fireLog("Transcriber: $s $detail")
        for (listener in transcriberConnectionChangedListeners.toList()) listener(s)
    }

    private fun fireLog(message: String) {
        for (listener in logListeners.toList()) listener(message)
    }

    // ---------------- question handling ----------------

    private suspend fun handleFinalized(turn: FinalizedTurn) {
        val now = clock.nowMs
        var previous: AnswerView? = null
        val revisesId = turn.revisesTurnId
        if (revisesId != null) synchronized(turnAnswers) { previous = turnAnswers[revisesId] }

        val verdict = gate.evaluate(turn.text, turn.evidence)
        if (!verdict.answerable) {
            count(verdict, turn)
            // A continuation that is not itself answerable keeps the earlier answer; context waits for the actual ask.
            if (previous == null && verdict.kind == UtteranceKind.Context) {
                pending.add(turn.turnId, turn.text, now)
            } else if (previous == null) {
                // Anything that is not interviewer context must never be merged into the next question.
                synchronized(gateLock) { this.turn.forget(turn.turnId) }
            }
            return
        }
        if (verdict.kind == UtteranceKind.FollowUp) {
            metrics.incFollowUp()
            fireLog("Turn accepted: candidate-directed follow-up (${verdict.support} acoustic support)")
        }
        if (verdict.support == AcousticSupport.Weak) {
            metrics.incWeakSupport()
            fireLog("Question accepted with weak acoustic support (" + turn.evidence + ")")
        }

        // Context statements merged into this (continued) turn are part of its text now.
        if (revisesId != null && previous == null) pending.remove(revisesId)
        val question = QuestionExtractor.extract(turn.text, pending.take(now), kb.context, gate)

        if (previous != null) {
            // Multi-part continuation / restatement: replace the provisional answer of the same question.
            metrics.incMerged()
            fireLog("Question continued — answering the whole question (${TextNormalizer.wordCount(question.coreQuestion)} words)")
            dupes.register(question.coreQuestion, now)
        } else {
            // A follow-up is a duplicate only of the same follow-up after the same answered question: "Tell me more." after the
            // answer to "Tell me more." is a new ask; the same transcript echoed before anything new was answered is not.
            val scope: Int? = if (verdict.kind == UtteranceKind.FollowUp || FollowUpIntent.isStandaloneFollowUp(question.coreQuestion)) lastAnsweredIndex else null
            if (dupes.isDuplicate(question.coreQuestion, now, scope)) {
                metrics.incDuplicate()
                fireLog("Duplicate question suppressed")
                return
            }
            dupes.register(question.coreQuestion, now, scope)
            metrics.incQuestions()
            // Pace: evaluated only for real, accepted interviewer questions (from when the interviewer started speaking).
            val ev = interrupt.interviewerSpeechStarted(if (turn.speechStartMs >= 0) turn.speechStartMs else now)
            if (ev != null) {
                session.interruptions.add(ev)
                for (listener in interruptionDetectedListeners.toList()) listener(ev)
            }
        }
        answerAsync(
            question, options.defaultStyle, allowCache = true, speechEndMs = turn.speechEndMs, finalizedMs = turn.finalizedMs,
            kind = AnswerKind.Live, turnId = turn.turnId, replaces = previous, heard = HeardEvidence.from(turn.evidence, verdict.support),
        )
    }

    /**
     * Trust boundary for live partial transcripts (called on every partial delta): shown only when the sound heard for the
     * current segment so far behaves like a voice that could have said the words, and the words are not the prompt or a
     * prompt-flavoured sentence over little speech. Without analysed audio nothing can be trusted: the words appear when
     * the turn is final.
     */
    private fun trustPartial(partial: String): Boolean {
        val now = clock.nowMs
        val from = if (partialWindowFrom >= 0) partialWindowFrom else now - options.evidenceLeadMs
        var evidence = timeline.sum(from, now)
        val openRun = pitchRuns.openRun()
        evidence = evidence.copy(
            runHops = evidence.runHops + openRun.runs,
            flatRunHops = evidence.flatRunHops + openRun.flat,
            stretchHops = evidence.stretchHops + openRun.stretch,
        )
        val text = partial.trim()
        if (text.isEmpty()) return false
        // Live words are shown only once the audio clearly behaves like a voice: a pitch track long enough to rule out a
        // held tone (buzz, note, instrument) and enough voiced sound for every syllable. Anything less waits for the end of
        // the turn, where the full evidence decides — a short real "Why?" simply appears when it is final.
        if (!evidence.pitchJudged) return false
        if (SpeechPlausibility.assess(text, evidence) != AcousticSupport.Strong) return false
        // A one- or two-word follow-up ("Go on.", "Be specific.") is judged on its whole segment (too much voicing for its
        // syllables is only known at the end): its words appear when the segment is final, never mid-sound.
        if (FollowUpIntent.isVeryShortImperative(text)) return false
        // Prompt echoes are short reproductions of the prompt; long voiced partials are not worth the comparison cost.
        return TextNormalizer.wordCount(text) > 80 || !gate.echo.check(text, evidence).isEcho
    }

    /** Drops artefact segments (beep echo, no speech) before they can merge with real speech in the same turn. */
    private fun keepSegment(text: String, evidence: SpeechEvidence): Boolean {
        val v = gate.evaluateSegment(TextNormalizer.cleanTranscript(text, kb.context.aliases), evidence) ?: return true
        count(v, TextNormalizer.wordCount(text))
        return false
    }

    private fun count(v: UtteranceVerdict, turn: FinalizedTurn) = count(v, TextNormalizer.wordCount(turn.text))

    private fun count(v: UtteranceVerdict, words: Int) {
        when (v.kind) {
            UtteranceKind.NonSpeech -> metrics.incNonSpeech()
            UtteranceKind.ShortAcousticEvent -> metrics.incShortEvent()
            UtteranceKind.PromptEcho -> metrics.incPromptEcho()
            UtteranceKind.Noise -> metrics.incInvalid()
            UtteranceKind.OtherDirected -> metrics.incOtherDirected()
            UtteranceKind.Logistics -> metrics.incLogistics()
            UtteranceKind.Context -> metrics.incContextHeld()
            UtteranceKind.ImplausibleTranscript -> metrics.incImplausible()
            UtteranceKind.ContextHallucination -> metrics.incContextHallucination()
            else -> {}
        }
        // Kind, reason and size only — the transcript itself is never logged.
        fireLog("Turn not answered: ${v.kind} (${v.reason}; $words words)")
        for (listener in turnSuppressedListeners.toList()) listener(v)
    }

    /** Manual fallback: typed/pasted question. Works without audio or transcription, also while paused. */
    suspend fun submitManualQuestion(text: String) {
        val q = kb.context.normalizeTerms(TextNormalizer.cleanTranscript(text, kb.context.aliases))
        if (q.isEmpty()) return
        dupes.register(q, clock.nowMs)
        metrics.incQuestions()
        // A typed question is exactly what the user wants answered: no gate, but the core ask is still extracted.
        val question = if (TextNormalizer.wordCount(q) > 40) QuestionExtractor.extract(q, "", kb.context, gate) else ExtractedQuestion.plain(q)
        answerAsync(question, options.defaultStyle, allowCache = true, speechEndMs = -1, finalizedMs = clock.nowMs, kind = AnswerKind.Typed)
    }

    /**
     * Reworks the answer of ONE specific history item (Shorter / Technical / Example / Full / Regenerate). The history
     * entry, its numbering and the interview memory stay the same — a variant is not a new interviewer question.
     */
    suspend fun requestVariant(answerId: Int, style: AnswerStyle) {
        val target: AnswerView?
        synchronized(historyList) { target = historyList.firstOrNull { a -> a.id == answerId } }
        if (target == null) return
        val question = target.extracted ?: ExtractedQuestion.plain(target.question)
        metrics.incVariant()
        answerAsync(question, style, allowCache = false, speechEndMs = -1, finalizedMs = clock.nowMs, kind = AnswerKind.Variant, turnId = target.turnId, replaces = target)
    }

    /** Variant of the most recent question (hotkeys/tests). The UI passes the displayed item explicitly. */
    suspend fun requestVariant(style: AnswerStyle) {
        val c = current ?: return
        requestVariant(c.id, style)
    }

    val history: List<AnswerView>
        get() = synchronized(historyList) { historyList.toList() }

    private fun cancelAnswer(includeVariants: Boolean) {
        answerJob.getAndSet(null)?.cancel()
        if (!includeVariants) return
        variantJob.getAndSet(null)?.cancel()
    }

    private suspend fun answerAsync(
        xq: ExtractedQuestion,
        style: AnswerStyle,
        allowCache: Boolean,
        speechEndMs: Long,
        finalizedMs: Long,
        kind: AnswerKind,
        turnId: Int = 0,
        replaces: AnswerView? = null,
        heard: HeardEvidence? = null,
    ) {
        // DC-54: the per-answer Job is a CHILD of the caller's job; a bare Job that is never
        // completed keeps the parent (e.g. a test's runBlocking) waiting forever after the
        // answer body has finished. C#'s CancellationTokenSource needs no completion; the
        // Kotlin Job does — so the wrapper completes it on every exit path.
        val job = Job(currentCoroutineContext()[Job])
        try {
            answerAsyncImpl(job, xq, style, allowCache, speechEndMs, finalizedMs, kind, turnId, replaces, heard)
        } finally {
            job.complete()
        }
    }

    private suspend fun answerAsyncImpl(
        job: Job,
        xq: ExtractedQuestion,
        style: AnswerStyle,
        allowCache: Boolean,
        speechEndMs: Long,
        finalizedMs: Long,
        kind: AnswerKind,
        turnId: Int = 0,
        replaces: AnswerView? = null,
        heard: HeardEvidence? = null,
    ) {
        val isVariant = kind == AnswerKind.Variant
        val question = xq.coreQuestion
        // A variant of an answer that was still streaming must still complete that question's memory (stories, metrics).
        val replacesUnfinished = isVariant && replaces != null && !replaces.isComplete
        if (replaces != null) replaces.superseded = true
        if (isVariant) {
            // One variant at a time; a variant of the item that is still streaming replaces that stream.
            variantJob.getAndSet(job)?.cancel()
            if (replaces != null && replaces === current && !replaces.isComplete) cancelAnswer(includeVariants = false)
        } else {
            // A new question supersedes any in-flight generation, including variants of older items.
            cancelAnswer(includeVariants = true)
            answerJob.set(job)
        }

        val t0 = clock.nowMs
        val cls = QuestionClassifier.classify(question, kb.context, evidence)
        var match = matcher.match(question)
        // A follow-up with no topic of its own ("Walk me through the process.") is about the previous answer: a prepared
        // answer to some other question ("Tell me about yourself.") must not ground it. Prepared follow-up answers stay.
        val matched = match.question
        if (cls.isFollowUp && matched != null && !matched.category.equals("FOLLOW_UP", ignoreCase = true) &&
            (FollowUpIntent.isStandaloneFollowUp(question) || UtteranceGate.isEllipticalAsk(question))
        ) {
            match = MatchResult(null, 0.0, MatchConfidence.None, null, 0.0)
        }
        val matchMs = clock.nowMs - t0

        // Language: detect per question (falls back to the locked interview language); memory is NOT reset on switches.
        val det = LanguageDetector.detect(question)
        val detected = if (det.confidence >= 0.5) det.code else "und"
        val answerLang = LanguageRegistry.resolveAnswerLanguage(answerLanguageOverride ?: kb.context.answerLanguage, kb.context.interviewLanguage, detected)

        // Adaptive pace: shorten automatically after likely interruptions (default style, live questions only).
        var effective = style
        if (style == AnswerStyle.Balanced && kind == AnswerKind.Live) {
            effective = when (interrupt.level) {
                AdaptiveLevel.Concise -> AnswerStyle.Concise
                AdaptiveLevel.Rapid -> AnswerStyle.Rapid
                else -> style
            }
        }
        val presentation = if (isVariant) Presentation.Answer else presentation

        var record: QuestionRecord?
        var prevStyle: String? = null
        if (isVariant) {
            record = replaces?.record // same question: never a new record
            if (record != null) {
                prevStyle = record.style
                record.style = style.name
                record.variantCount++
            }
        } else if (replaces?.record != null) {
            val reused = replaces.record!!
            reused.original = question
            reused.context = xq.contextSummary
            reused.detectedLanguage = detected
            reused.answerLanguage = answerLang
            reused.category = cls.category
            reused.mode = cls.mode.name
            reused.matchedQuestionId = match.question?.questionId
            reused.answerBullets.clear()
            reused.storyIds.clear()
            reused.qualityFlags.clear()
            if (heard != null) reused.heard = heard
            record = reused
        } else {
            val followUpOf = if (cls.isFollowUp) session.last?.index else null
            record = session.add(
                QuestionRecord(
                    original = question, context = xq.contextSummary, typed = kind == AnswerKind.Typed, heard = heard,
                    detectedLanguage = detected, answerLanguage = answerLang,
                    category = cls.category, mode = cls.mode.name,
                    intentKey = SessionMemory.intentKeyFor(question, cls.category, if (match.confidence >= MatchConfidence.Medium) match.question else null),
                    matchedQuestionId = match.question?.questionId, followUpOf = followUpOf,
                    referencesEarlierAnswer = SessionMemory.referencesEarlier(question), presentation = presentation.name,
                    entities = (if (xq.entities.isNotEmpty()) {
                        xq.entities.toMutableList()
                    } else {
                        (listOf(kb.context.companyName) + kb.context.products)
                            .filter { e -> e.length > 1 && question.contains(e, ignoreCase = true) }.toMutableList()
                    }),
                ),
            )
        }
        if (record != null && !isVariant) for (e in record.entities) session.companyTopics.add(e)
        if (record?.referencesEarlierAnswer == true && !isVariant) {
            // "you already mentioned 60,000…" → that metric counts as used
            for (m in ResumeParser.extractMetrics(question)) session.metricUse[m] = (session.metricUse[m] ?: 0) + 1
        }

        val ctx = retriever.retrieve(question, cls, match, storyPenalty = session::storyPenalty)
        val plan = AnswerPlanner.plan(xq, cls, match, ctx, kb, evidence)

        val view = AnswerView(
            id = nextAnswerId.incrementAndGet(), question = question, style = effective,
            replacesAnswerId = replaces?.id, turnId = turnId, kind = kind,
            contextSummary = xq.contextSummary, originalText = xq.original, subQuestions = xq.subQuestions, extracted = xq,
        )
        view.mode = cls.mode
        view.category = cls.category
        view.matchedQuestionId = match.question?.questionId
        view.matchScore = match.score
        view.questionIndex = record?.index ?: replaces?.questionIndex ?: session.last?.index ?: 0
        view.detectedLanguage = detected
        view.answerLanguage = answerLang
        view.presentation = presentation
        view.adaptive = interrupt.level
        view.record = record
        view.plan = plan
        view.confidence = plan.confidence
        if (!isVariant || replaces === current) current = view
        synchronized(historyList) {
            val at = if (replaces == null) -1 else historyList.indexOf(replaces)
            if (at >= 0) {
                historyList[at] = view // same question → same history entry (continuation or variant)
            } else {
                historyList.add(view)
                if (historyList.size > options.historyCapacity) historyList.removeAt(0)
            }
        }
        if (turnId > 0) {
            synchronized(turnAnswers) {
                turnAnswers[turnId] = view
                if (turnAnswers.size > 64) turnAnswers.remove(turnAnswers.keys.minOrNull()!!)
            }
        }
        for (listener in answerStartedListeners.toList()) listener(view)
        setStatus(EngineStatus.Answering)

        var firstToken = -1L
        var firstBullet = -1L
        val bank = match.question
        val languageOk = bank != null && LanguageRegistry.find(bank.language)?.code == answerLang
        val recentStories = session.recentlyUsedStories(2)
        val repeatsStory = bank != null && bank.effectiveStoryIds.any { it in recentStories }
        val referencesEarlier = record?.referencesEarlierAnswer == true
        val personal = plan.personal != PersonalTopic.None
        var useCache = allowCache && options.useFastCache && bank != null && bank.confidence >= options.minCacheConfidence && languageOk && !personal &&
            (match.confidence == MatchConfidence.High && (!cls.isFollowUp || match.score >= 0.75)) &&
            !(provider != null && (repeatsStory || referencesEarlier)) // prefer a fresh answer instead of repeating a story
        // Without any LLM available, a Medium match is still far better than nothing.
        if (!useCache && allowCache && provider == null && bank != null && match.confidence >= MatchConfidence.Low && !personal) useCache = true
        val request = PromptRequest(
            languageRule = LanguageRegistry.answerLanguageRule(answerLang),
            memoryContext = session.buildPromptContext(),
            extraInstruction = if (referencesEarlier) {
                "The interviewer refers to something already mentioned: do NOT repeat it — use a different verified story/angle or go deeper as asked."
            } else if (repeatsStory) {
                "Avoid re-using the stories already used unless no other evidence fits."
            } else {
                null
            },
            interviewerContext = xq.promptContext, subQuestions = xq.subQuestions, constraints = xq.constraints, plan = plan, boundaries = kb.profile.boundaries,
        )

        try {
            if (plan.personalWithoutData) {
                // Never invent hobbies, motivations or preferences: show honest guidance instantly (no model call).
                view.source = AnswerSource.Guidance
                for (b in AnswerPlanner.personalGuidance(plan.personal, answerLang)) {
                    view.appendBullet(b)
                    fireBulletAdded(view, b)
                }
                firstBullet = clock.nowMs
                firstToken = firstBullet
                metrics.incGuidance()
            } else if (presentation == Presentation.Coach) {
                firstBullet = coachAsync(view, question, cls, match, useCache, answerLang, ctx, job)
                firstToken = firstBullet
            } else if (useCache) {
                emitCached(view, bank!!, if (provider == null && match.confidence < MatchConfidence.High) AnswerSource.CacheFallback else AnswerSource.Cache)
                firstBullet = clock.nowMs
                metrics.incCache()
            } else if (provider != null) {
                try {
                    if (kind == AnswerKind.Live && replaces == null && options.continuationHoldMs > 0 && isListening &&
                        !holdForContinuation(job)
                    ) {
                        throw CancellationException("superseded while held")
                    }
                    if (personal && plan.personalFacts.isNotEmpty()) view.groundingFacts = plan.personalFacts
                    val streamed = streamLlm(view, question, cls, ctx, effective, request, job)
                    firstToken = streamed.first
                    firstBullet = streamed.second
                    metrics.incLlm()
                    if (view.groundingFacts != null && view.bullets.isEmpty() && !job.isCancelled) {
                        // Every bullet added something the candidate never said: show their own confirmed words instead.
                        view.source = AnswerSource.CacheFallback
                        for (b in plan.personalFacts.take(3)) {
                            view.appendBullet(b)
                            fireBulletAdded(view, b)
                        }
                        if (firstBullet < 0) firstBullet = clock.nowMs
                    }
                } catch (ex: ProviderException) {
                    if (job.isCancelled) throw ex
                    metrics.incApiError()
                    fireLog("Answer provider error: ${ex.kind} ${ex.code ?: ""} ${ex.message}")
                    view.error = ex.kind
                    val message = ProviderException.userMessage(ex.kind, options.developerKeyMode)
                    // Fall back to a prepared answer only when it is a SAFE match (never a confidently wrong one).
                    if (view.bullets.isEmpty() && bank != null && match.confidence >= MatchConfidence.Medium && languageOk && !personal) {
                        emitCached(view, bank, AnswerSource.CacheFallback)
                        view.note = Loc.t("note_prepared_fallback")
                        firstBullet = clock.nowMs
                        metrics.incFallback()
                    } else {
                        view.source = if (view.bullets.isNotEmpty()) AnswerSource.Llm else AnswerSource.Error
                        view.note = message
                    }
                    // Transient service errors don't turn the whole live status red; session/account problems do.
                    if (ex.kind != ProviderErrorKind.RateLimited && ex.kind != ProviderErrorKind.Timeout &&
                        ex.kind != ProviderErrorKind.QuotaExceeded && ex.kind != ProviderErrorKind.BadRequest
                    ) {
                        setStatus(EngineStatus.ApiError, message)
                    }
                }
            } else if (personal && plan.personalFacts.isNotEmpty()) {
                // No AI available: the user's own confirmed answer, verbatim.
                view.source = AnswerSource.CacheFallback
                for (b in plan.personalFacts.take(3)) {
                    view.appendBullet(b)
                    fireBulletAdded(view, b)
                }
                firstBullet = clock.nowMs
            } else {
                view.source = AnswerSource.Error
                view.note = Loc.t(if (options.developerKeyMode) "err_no_provider_dev" else "err_no_provider")
            }
        } catch (e: CancellationException) {
            if (view.note == null) view.note = Loc.t("note_superseded")
        } catch (e: ProviderException) {
            if (e.kind == ProviderErrorKind.Cancelled || job.isCancelled) {
                if (view.note == null) view.note = Loc.t("note_superseded")
            } else {
                throw e
            }
        } finally {
            view.isComplete = true
            val bullets = view.snapshotBullets()
            // A variant that was cancelled (next question, Stop, another variant) or produced nothing must not wipe the
            // answer it was reworking: the original goes back into its history slot and its question record is untouched.
            if (isVariant && replaces != null && (job.isCancelled || bullets.isEmpty())) {
                synchronized(historyList) {
                    val at = historyList.indexOf(view)
                    if (at >= 0) historyList[at] = replaces
                }
                if (turnId > 0) {
                    synchronized(turnAnswers) {
                        if (turnAnswers[turnId] === view) turnAnswers[turnId] = replaces
                    }
                }
                if (current === view) current = replaces
                replaces.superseded = false
                view.superseded = true
                if (record != null) {
                    record.style = prevStyle ?: record.style
                    record.variantCount = maxOf(0, record.variantCount - 1)
                }
                for (listener in answerRestoredListeners.toList()) listener(view, replaces)
            }
            if (!view.superseded && bullets.isNotEmpty() && view.source == AnswerSource.Llm) {
                val quality = AnswerQualityGate.assess(xq, plan, bullets, recentAnswers(view), view.style)
                view.validationFlags.addAll(quality.flags)
            }
            if (record != null && !view.superseded) {
                val answerMetrics = bullets.flatMap { ResumeParser.extractMetrics(it) }
                    .filter { m -> kb.profile.verifiedNumbers.contains(m) }.distinct()
                if (isVariant && !replacesUnfinished) {
                    record.answerBullets = bullets.toMutableList()
                } else {
                    session.completeAnswer(record, bullets, view.storyIds, answerMetrics)
                    record.firstBulletLatencyMs = if (firstBullet >= 0) firstBullet - finalizedMs else -1
                }
                record.source = view.source.name
                record.coachKeywords = view.coach?.keywords?.toMutableList() ?: mutableListOf()
                record.confidence = view.confidence.name
                record.qualityFlags = view.validationFlags
                    .filter { f -> f.startsWith("QUALITY_") || f.startsWith("DROPPED_") || f.startsWith("BRIDGE_") }
                    .map { f -> f.split(':')[0] }.distinct().toMutableList()
                if (!isVariant && bullets.isNotEmpty()) lastAnsweredIndex = record.index
            }
            // Only answers to heard questions inform the pace heuristic (typed questions and variants never do).
            if (firstBullet >= 0 && kind == AnswerKind.Live && !view.superseded) {
                val words = if (view.coach != null) 25 else bullets.sumOf { TextNormalizer.wordCount(it) }
                interrupt.answerShown(firstBullet, words, view.questionIndex)
            }
            for (listener in answerCompletedListeners.toList()) listener(view)
            // Atomic hand-back: only the owner of the slot clears it (a newer answer/variant may already hold it).
            if (isVariant) {
                variantJob.compareAndSet(job, null)
            } else {
                answerJob.compareAndSet(job, null)
            }
            if (status == EngineStatus.Answering && !view.superseded) {
                setStatus(
                    if (!isListening) {
                        if (paused) EngineStatus.Paused else EngineStatus.Ready
                    } else if (view.bullets.isNotEmpty()) {
                        EngineStatus.AnswerReady
                    } else {
                        EngineStatus.Listening
                    },
                )
            }

            val done = clock.nowMs
            if (!view.superseded && !isVariant) {
                val sample = LatencySample(
                    question, view.source.name,
                    if (speechEndMs >= 0) finalizedMs - speechEndMs else -1, matchMs,
                    if (firstToken >= 0) firstToken - finalizedMs else -1, if (firstBullet >= 0) firstBullet - finalizedMs else -1,
                    done - finalizedMs, if (speechEndMs >= 0 && firstBullet >= 0) firstBullet - speechEndMs else -1,
                    OffsetDateTime.now(ZoneOffset.UTC),
                )
                metrics.add(sample)
                for (listener in latencyMeasuredListeners.toList()) listener(sample)
            }
        }
    }

    private fun fireBulletAdded(view: AnswerView, bullet: String) {
        for (listener in bulletAddedListeners.toList()) listener(view, bullet)
    }

    private fun recentAnswers(exclude: AnswerView): List<List<String>> {
        val recent: List<AnswerView>
        synchronized(historyList) {
            recent = historyList.filter { a -> a.id != exclude.id && a.isComplete && !a.superseded }.takeLast(4)
        }
        return recent.map { a -> a.snapshotBullets() }
    }

    /** Waits [EngineOptions.continuationHoldMs] (engine time, released by Tick). False when superseded. */
    private suspend fun holdForContinuation(job: Job): Boolean {
        val tcs: CompletableDeferred<Boolean>
        synchronized(gateLock) {
            releaseHold(false)
            tcs = CompletableDeferred()
            hold = tcs
            holdUntil = clock.nowMs + options.continuationHoldMs
            holdExtended = false
        }
        job.invokeOnCompletion { tcs.complete(false) }
        return tcs.await()
    }

    private fun releaseHold(proceed: Boolean) {
        val h: CompletableDeferred<Boolean>?
        synchronized(gateLock) {
            h = hold
            hold = null
            holdExtended = false
            holdUntil = -1
        }
        h?.complete(proceed)
    }

    private fun emitCached(view: AnswerView, q: BankQuestion, source: AnswerSource) {
        view.source = source
        view.mode = q.mode
        view.storyIds.addAll(q.effectiveStoryIds)
        val bullets = if (view.style == AnswerStyle.Full && q.optionalFullAnswer.isNotEmpty()) {
            listOf(q.optionalFullAnswer)
        } else {
            q.shortBullets.take(AnswerStyleSpec.maxBullets(view.style))
        }
        for (b in bullets) {
            view.appendBullet(b)
            fireBulletAdded(view, b)
        }
    }

    /** Coach Mode: prepared keywords instantly (same language), otherwise a tiny AI request; deterministic fallback. */
    private suspend fun coachAsync(
        view: AnswerView,
        question: String,
        cls: Classification,
        match: MatchResult,
        useCache: Boolean,
        lang: String,
        retrieved: RetrievedContext,
        job: Job,
    ): Long {
        val bank = match.question
        if (useCache && bank != null) {
            view.source = AnswerSource.Cache
            view.storyIds.addAll(bank.effectiveStoryIds)
            metrics.incCache()
            val kw = if (bank.coachKeywords.size == 3) bank.coachKeywords else CoachPrompt.keywords(bank.canonicalQuestion + " " + bank.shortBullets.take(1).joinToString(" "))
            val st = if (bank.answerStructure.isNotEmpty()) bank.answerStructure else defaultStructure(bank.mode)
            return emitCoach(view, CoachOutput.fromPrepared(kw, st))
        }
        if (provider != null) {
            try {
                val ctx = RetrievedContext(retrieved.stories.take(2), retrieved.snippets.take(3), retrieved.reference)
                view.storyIds.addAll(ctx.stories.map { s -> s.id })
                val messages = CoachPrompt.build(kb, question, cls, ctx, lang, session.buildPromptContext(3))
                val sb = StringBuilder()
                metrics.incLlmRequest()
                withContext(job) {
                    provider.streamAsync(messages, 80).collect { d -> sb.append(d) }
                }
                val parsed = CoachOutput.parse(sb.toString())
                if (parsed != null) {
                    view.source = AnswerSource.Llm
                    metrics.incLlm()
                    return emitCoach(view, parsed)
                }
                view.validationFlags.add("COACH_FORMAT: model output not in contract, used local fallback")
            } catch (ex: ProviderException) {
                if (job.isCancelled) throw ex
                metrics.incApiError()
                fireLog("Coach provider error: " + ex.kind)
            }
        }
        view.source = if (bank != null) AnswerSource.CacheFallback else AnswerSource.Error
        val structure = defaultStructure(cls.mode)
        return emitCoach(
            view,
            if (bank != null && bank.coachKeywords.size == 3) {
                CoachOutput.fromPrepared(bank.coachKeywords, bank.answerStructure)
            } else {
                CoachOutput.fromPrepared(CoachPrompt.keywords(question), structure)
            },
        )
    }

    private fun emitCoach(view: AnswerView, coach: CoachOutput): Long {
        view.coach = coach
        view.appendBullet(coach.keywordLine)
        fireBulletAdded(view, coach.keywordLine)
        view.appendBullet(coach.structure)
        fireBulletAdded(view, coach.structure)
        return clock.nowMs
    }

    private suspend fun streamLlm(
        view: AnswerView,
        question: String,
        cls: Classification,
        ctx: RetrievedContext,
        style: AnswerStyle,
        request: PromptRequest,
        job: Job,
    ): Pair<Long, Long> {
        view.source = AnswerSource.Llm
        view.storyIds.addAll(ctx.stories.map { s -> s.id })
        val messages = prompts.build(question, cls, ctx, style, listOf<ConversationTurn>(), request)
        val maxTokens = AnswerStyleSpec.`for`(style).maxTokens
        val parser = BulletStreamParser()
        var firstToken = -1L
        var firstBullet = -1L
        view.bridgePending = cls.mode == AnswerMode.Bridge
        metrics.incLlmRequest()
        metrics.approxLlmInputChars += messages.sumOf { m -> m.content.length }

        withContext(job) {
            provider!!.streamAsync(messages, maxTokens).collect { delta ->
                if (firstToken < 0) firstToken = clock.nowMs
                metrics.approxLlmOutputChars += delta.length
                for (b in parser.push(delta)) {
                    if (acceptBullet(view, parser, b, style) && firstBullet < 0) firstBullet = clock.nowMs
                }
                if (parser.pending.isNotEmpty()) {
                    for (listener in pendingTextChangedListeners.toList()) listener(view, parser.pending)
                }
            }
        }
        for (b in parser.complete()) {
            if (acceptBullet(view, parser, b, style) && firstBullet < 0) firstBullet = clock.nowMs
        }
        for (listener in pendingTextChangedListeners.toList()) listener(view, "")
        return Pair(firstToken, firstBullet)
    }

    /** Word-by-word grounding needs facts in the answer's language (German facts, English answer: prompt rule only). */
    private fun factsAreEnglish(facts: List<String>): Boolean {
        val code = LanguageDetector.detect(facts.joinToString(". ")).code
        return code == "en" || code == "und"
    }

    /** Validates one streamed bullet before display. Returns true when something was shown. */
    private fun acceptBullet(view: AnswerView, parser: BulletStreamParser, bullet: String, style: AnswerStyle): Boolean {
        // The classifier's BRIDGE (direct experience question without confirmed evidence) wins over the model's MODE line.
        val parserMode = parser.mode
        if (parserMode != null && view.mode != AnswerMode.Bridge) view.mode = parserMode
        val max = AnswerStyleSpec.maxBullets(style)
        if (view.bullets.size >= max) return false // hard cap: the default answer must fit without scrolling
        // Last line of defence against prompt injection: never display prompt internals or secret-looking strings.
        if (leakGuard.containsMatchIn(bullet)) {
            view.validationFlags.add("DROPPED_LEAK: bullet looked like prompt internals or a secret")
            return false
        }
        // Personal answers say only what the candidate confirmed: no added traits, feelings, benefits or links.
        val grounding = view.groundingFacts
        if (grounding != null && view.answerLanguage == "en" && factsAreEnglish(grounding)) {
            val extra = PersonalGrounding.unsupported(bullet, grounding, view.question)
            if (extra.isNotEmpty()) {
                view.validationFlags.add("DROPPED_UNGROUNDED_PERSONAL: ${extra.size} word(s) not in the confirmed profile facts")
                return false
            }
        }
        // Fact validation before display: unsupported first-person past claims become "I'd …" in every mode;
        // claims backed by confirmed evidence stay in the past tense (they are true).
        val report = validator.validate(listOf(bullet), view.mode, AnswerStyle.Technical)
        var text = bullet
        for (issue in report.issues) {
            view.validationFlags.add("${issue.kind}: ${issue.detail}")
            if (issue.kind == "UNSUPPORTED_CLAIM") text = FactValidator.softenClaim(text)
        }
        // A number nobody confirmed, stated as a past result or inside an unsupported claim, is a fabricated metric:
        // the bullet is dropped rather than shown (softening "I built X in 2021" would still be nonsense).
        val unverifiedNumber = report.issues.any { i -> i.kind == "UNVERIFIED_NUMBER" }
        if (unverifiedNumber && (report.issues.any { i -> i.kind == "UNSUPPORTED_CLAIM" } || pastResult.containsMatchIn(bullet))) {
            view.validationFlags.add("DROPPED_UNVERIFIED_METRIC: bullet removed")
            return false
        }
        if (style != AnswerStyle.Full && AnswerQualityGate.isRedundant(text, view.snapshotBullets())) {
            view.validationFlags.add("DROPPED_REDUNDANT: repeated an earlier bullet")
            metrics.incRedundantDropped()
            return false
        }
        // Direct "have you personally…" without evidence: the first thing on screen is always an honest bridge.
        if (view.bridgePending) {
            view.bridgePending = false
            val (bridge, cue) = bridgeFor(view.answerLanguage)
            if (!cue.containsMatchIn(text) && !bridgeCue.containsMatchIn(text) && style != AnswerStyle.Full) {
                view.validationFlags.add("BRIDGE_INSERTED: model answer did not start with an honest bridge")
                view.appendBullet(bridge)
                fireBulletAdded(view, bridge)
                if (view.bullets.size >= max) return true
            }
        }
        view.appendBullet(text)
        fireBulletAdded(view, text)
        return true
    }

    private fun setStatus(s: EngineStatus, detail: String? = null) {
        if (status == s && statusDetail == detail) return
        status = s
        statusDetail = detail
        for (listener in statusChangedListeners.toList()) listener(s, detail)
    }

    suspend fun disposeAsync() {
        stopAsync()
        transcriber?.disposeAsync()
    }

    companion object {
        private val bridgeCue: Regex = dotNetRegex(
            "\\b(haven't|have not|hasn't|not (personally|directly|yet)|never (owned|built|done|implemented|led)|didn't|did not|no direct|closest|not .{0,30}end-to-end)\\b",
            ignoreCase = true,
        )
        private val pastResult: Regex = dotNetRegex(
            "\\b(reduced|increased|grew|improved|saved|raised|boosted|achieved|generated|doubled|tripled|lowered|cut|delivered|drove)\\b(?!.*\\b(I'd|I would|would|could|aim|target|goal|expect)\\b)",
            ignoreCase = true,
        )
        const val HonestBridge: String = "I haven't owned that exact work end-to-end, but here is how I'd approach it."

        /** The honest bridge and the cues that show the model already wrote one, per answer language. */
        private val bridges: Map<String, Pair<String, Regex>> = mapOf(
            "es" to ("No he hecho exactamente ese trabajo de principio a fin, pero así es como lo abordaría." to cue("\\b(no he|nunca|no directamente|no personalmente|lo más cercano)\\b")),
            "fr" to ("Je n'ai pas mené ce travail exact de bout en bout, mais voici comment je l'aborderais." to cue("(je n'ai pas|jamais|pas directement|pas personnellement|le plus proche)")),
            "de" to ("Genau diese Arbeit habe ich noch nicht vollständig verantwortet, aber so würde ich vorgehen." to cue("\\b(nicht|noch nie|keine direkte|am nächsten)\\b")),
            "pt" to ("Não fui responsável exatamente por esse trabalho de ponta a ponta, mas é assim que eu o abordaria." to cue("\\b(não|nunca|mais próximo)\\b")),
            "it" to ("Non ho gestito esattamente questo lavoro dall'inizio alla fine, ma ecco come lo affronterei." to cue("\\b(non ho|mai|non direttamente|più vicino)\\b")),
            "ar" to ("لم أتولَّ هذا العمل بعينه من البداية إلى النهاية، لكن هكذا سأتعامل معه." to cue("(لم |لا |ليس)")),
            "fa" to ("این کار را دقیقاً از ابتدا تا انتها انجام نداده‌ام، اما این‌طور به آن می‌پردازم." to cue("(نکرده|نداشته|نبوده|هرگز|نداده)")),
            "zh" to ("我没有完整负责过这项具体工作，但我会这样着手。" to cue("(没有|未曾|从未|不是直接)")),
            "hi" to ("मैंने ठीक यही काम शुरू से अंत तक नहीं संभाला है, लेकिन इसे करने का मेरा तरीका यह होगा।" to cue("(नहीं|कभी)")),
        )

        private fun cue(pattern: String): Regex = dotNetRegex(pattern, ignoreCase = true)

        /** Bridge for the answer language (English for languages without a table). */
        internal fun bridgeFor(language: String?): Pair<String, Regex> =
            if (language != null) bridges[language] ?: (HonestBridge to bridgeCue) else (HonestBridge to bridgeCue)

        private val leakGuard: Regex = dotNetRegex(
            "\\b(sk-[A-Za-z0-9_-]{12,}|pdl_[A-Za-z0-9_]{8,}|eyJ[A-Za-z0-9_-]{20,}\\.|system prompt|my instructions (are|say)|TRUTH RULES|CANDIDATE EVIDENCE|INTERVIEWER CONTEXT|ANSWER PLAN|OUTPUT FORMAT|api[_ ]?key\\s*[:=])",
            ignoreCase = true,
        )

        private fun defaultStructure(m: AnswerMode): String = when (m) {
            AnswerMode.Verified -> Loc.t("coach_structure_verified")
            AnswerMode.Bridge -> Loc.t("coach_structure_bridge")
            else -> Loc.t("coach_structure_default")
        }
    }
}
