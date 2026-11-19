package com.interviewassistant.core.turn

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.diagnostics.IClock
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.FollowUpIntent
import com.interviewassistant.core.intelligence.FollowUpKind
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.TermAlias
import kotlin.math.max
import kotlin.math.min

/**
 * Mirror of `InterviewAssistant.Core/Turn/TurnDetector.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

enum class TurnState { Idle, Listening, SpeechStarted, PossibleEnd, TurnSettling, QuestionFinalized }

/**
 * A finalized interviewer turn. [revisesTurnId] is set when the interviewer continued the previous
 * question after a pause (multi-part question) or restated it: the consumer must replace that turn's answer
 * instead of creating a new one.
 */
data class FinalizedTurn(
    val text: String,
    val speechEndMs: Long,
    val finalizedMs: Long,
    val segmentCount: Int,
    val turnId: Int = 0,
    val revisesTurnId: Int? = null,
    val evidence: SpeechEvidence = SpeechEvidence(0, 0, 0, 0, 0),
    val speechStartMs: Long = -1,
)

/**
 * Adaptive end-of-turn detector. Server VAD splits speech into segments at short pauses; this class decides
 * when the interviewer's whole TURN is over, so multi-part questions separated by pauses become one question.
 *
 * Two layers keep latency low without splitting multi-part questions:
 *  1. Adaptive settle, measured from the moment speech stopped (so transcription latency and settle overlap):
 *     a complete-sounding question settles quickly, an open ending ("…and", "…which") or an announced multi-part
 *     question ("I have two questions…") waits longer. New speech during settling cancels finalization.
 *  2. Continuation window: the server's speech_stopped event already lags real silence by the VAD window, and
 *     a resumed speech_started arrives a few hundred ms after the interviewer resumes, so for pauses of ~0.6–1.5 s
 *     the first part may already be finalized. In a real interview the CANDIDATE speaks after a question, so
 *     interviewer speech that resumes within [continuationWindowMs] is a continuation (or a restatement)
 *     of the same question: it is merged into that turn and emitted with [FinalizedTurn.revisesTurnId].
 *     Pleasantries ("okay", "great") never reopen a turn.
 */
class TurnDetector(private val clock: IClock) {
    private data class LastFinal(val id: Int, val text: String, val speechStoppedAt: Long, val evidence: SpeechEvidence)
    private data class Reopened(val id: Int, val text: String, val evidence: SpeechEvidence)

    private val segments = mutableListOf<Pair<String, String>>()
    private val partials = LinkedHashMap<String, StringBuilder>()
    private val partialTrusted = mutableMapOf<String, Boolean>()
    private val partialCheckedAt = mutableMapOf<String, Int>() // partial length at the last trust check
    private val pendingEvidence = ArrayDeque<SpeechEvidence>()

    // Evidence keyed by the transcription item it belongs to (exact); the queue is only a fallback when ids are unknown.
    private val evidenceByItem = LinkedHashMap<String, SpeechEvidence>()
    private var turnEvidenceAcc: SpeechEvidence = SpeechEvidence(0, 0, 0, 0, 0)
    private var turnStartMs: Long = -1
    private var segmentsText: String? = null // cached join of completed segments (long monologues: no O(n²) rebuilding)
    private var droppedSegmentsCount: Int = 0
    private var speechStoppedAt: Long = -1
    private var lastSegmentAt: Long = -1
    private var settleDeadline: Long = -1
    private var awaitingSegments: Int = 0
    private var nextTurnId: Int = 0
    private var lastFinal: LastFinal? = null
    private var reopened: Reopened? = null
    private var reopenGapMs: Long = -1 // speech_stopped of the reopened turn → speech_started of the new speech

    var state: TurnState = TurnState.Idle
        private set
    var aliases: List<TermAlias>? = null

    /**
     * Segment filter: returns false for a segment that must not join the turn (recognizer artefact, prompt echo, no
     * speech in its audio). Applied when the segment's transcript completes, with that segment's acoustic evidence.
     */
    var segmentFilter: ((String, SpeechEvidence) -> Boolean)? = null

    /**
     * Trust boundary for in-progress (partial) transcripts: only partials this accepts are shown as live text. Raw
     * partials of a beep ("Job interview") must never reach the screen; completed segments are judged by
     * [segmentFilter]. Null = show every partial.
     */
    var partialFilter: ((String) -> Boolean)? = null

    /** Acoustic evidence of the segments accepted into the current turn so far. */
    val turnEvidence: SpeechEvidence get() = turnEvidenceAcc

    /** True when the live text holds trusted words (accepted segments or trusted partials). */
    val hasTrustedText: Boolean get() = segments.isNotEmpty() || partialTrusted.values.any { it }

    /** Target-scoped term normalization applied to finalized text (e.g. MiCA → MiCAR when the target uses MiCAR). */
    var termNormalizer: ((String) -> String)? = null
    var sensitivity: Double = 1.0 // >1 waits longer, <1 answers faster
    var questionCompleteMs: Int = 600
    var defaultMs: Int = 800
    var incompleteMs: Int = 1700
    var missingTranscriptTimeoutMs: Int = 4000
    var maxSegmentsPerTurn: Int = 400

    /** Bound on the text kept for one turn (a 5-minute monologue is ~5,000 characters). The beginning and the
     * most recent speech are kept; the middle of an extremely long turn is elided. */
    var maxTurnChars: Int = 8000

    /** Bound on one in-progress partial transcript (tail kept). */
    var maxPartialChars: Int = 4000

    /** Interviewer speech resuming within this time after the previous turn's speech stopped continues that turn. */
    var continuationWindowMs: Int = 2500

    /**
     * A standalone follow-up ("Tell me more.", "Why?") inside the continuation window is its own turn only when it starts at
     * least this long after the previous turn's speech_stopped event (≈ 2 s of real silence once the VAD tail and the
     * speech_started lag are added): time for the candidate to have started answering. Sooner, the interviewer is still
     * asking — "What is your biggest weakness? … Explain." is one multi-part question, merged as in rc.4.
     */
    var followUpReplyAllowanceMs: Int = 1800

    private val stateChangedListeners = mutableListOf<(TurnState) -> Unit>()
    private val liveTextChangedListeners = mutableListOf<(String) -> Unit>()
    private val turnFinalizedListeners = mutableListOf<(FinalizedTurn) -> Unit>()

    /** A standalone follow-up arrived within the continuation window and became its own turn (diagnostics). */
    private val followUpSplitListeners = mutableListOf<(FollowUpKind) -> Unit>()

    fun addStateChangedListener(listener: (TurnState) -> Unit) {
        stateChangedListeners.add(listener)
    }

    fun addLiveTextChangedListener(listener: (String) -> Unit) {
        liveTextChangedListeners.add(listener)
    }

    fun addTurnFinalizedListener(listener: (FinalizedTurn) -> Unit) {
        turnFinalizedListeners.add(listener)
    }

    fun addFollowUpSplitListener(listener: (FollowUpKind) -> Unit) {
        followUpSplitListeners.add(listener)
    }

    fun start() {
        reset()
        lastFinal = null
        setState(TurnState.Listening)
    }

    fun stop() {
        reset()
        lastFinal = null
        setState(TurnState.Idle)
    }

    fun reset() {
        segments.clear()
        partials.clear()
        partialTrusted.clear()
        partialCheckedAt.clear()
        segmentsText = null
        droppedSegmentsCount = 0
        speechStoppedAt = -1
        lastSegmentAt = -1
        settleDeadline = -1
        awaitingSegments = 0
        reopened = null
        reopenGapMs = -1
        turnEvidenceAcc = SpeechEvidence(0, 0, 0, 0, 0)
        turnStartMs = -1
        pendingEvidence.clear()
        evidenceByItem.clear()
    }

    /** Interviewer speech started. Returns true when it continues the just-finalized question (not an interruption). */
    fun onSpeechStarted(): Boolean {
        if (state == TurnState.Idle) return false
        settleDeadline = -1 // interviewer resumed: cancel pending finalization
        awaitingSegments++
        if (turnStartMs < 0) turnStartMs = clock.nowMs
        var continuation = false
        val last = lastFinal
        if (segments.isEmpty() && partials.isEmpty() && reopened == null && last != null && clock.nowMs - last.speechStoppedAt <= continuationWindowMs) {
            reopened = Reopened(last.id, last.text, last.evidence)
            reopenGapMs = clock.nowMs - last.speechStoppedAt
            continuation = true
        }
        setState(TurnState.SpeechStarted)
        return continuation
    }

    /**
     * The consumer rejected this turn (no speech, prompt echo, logistics, remark to someone else): later speech must
     * not be merged into it — a real question right after a beep starts a fresh turn.
     */
    fun forget(turnId: Int) {
        if (lastFinal?.id == turnId) lastFinal = null
        if (reopened?.id == turnId) reopened = null
    }

    /** True while a just-finalized question can still be continued by the interviewer. */
    val inContinuationWindow: Boolean
        get() {
            val l = lastFinal ?: return false
            return clock.nowMs - l.speechStoppedAt <= continuationWindowMs
        }

    /**
     * @param evidence Acoustic evidence for the segment that just ended (attached to its transcript).
     * @param itemId Transcription item the segment belongs to, when the provider reports it.
     */
    fun onSpeechStopped(evidence: SpeechEvidence = SpeechEvidence(0, 0, 0, 0, 0), itemId: String? = null) {
        if (state == TurnState.Idle) return
        if (!itemId.isNullOrEmpty()) {
            evidenceByItem[itemId] = evidence
            if (evidenceByItem.size > 16) evidenceByItem.remove(evidenceByItem.keys.first())
        } else {
            pendingEvidence.addLast(evidence)
            while (pendingEvidence.size > 8) pendingEvidence.removeFirst()
        }
        speechStoppedAt = clock.nowMs
        setState(TurnState.PossibleEnd)
        scheduleSettle()
    }

    fun onPartial(itemId: String, delta: String) {
        if (state == TurnState.Idle) return
        val sb = partials.getOrPut(itemId) { StringBuilder() }
        sb.append(delta)
        if (sb.length > maxPartialChars) sb.delete(0, sb.length - maxPartialChars)
        // An untrusted or short partial is re-checked on every delta (a sentence that outgrows its audio loses its trust
        // at once); a long trusted one after every ~40 new characters (cheap for minute-long monologues).
        var trusted = partialTrusted[itemId] ?: false
        val filter = partialFilter
        if (filter == null) trusted = true
        else if (!trusted || sb.length <= 240 || sb.length - (partialCheckedAt[itemId] ?: 0) >= 40) {
            trusted = filter(sb.toString())
            partialCheckedAt[itemId] = sb.length
        }
        partialTrusted[itemId] = trusted
        fireLiveTextChanged(displayText())
    }

    fun onSegmentCompleted(itemId: String, transcript: String) {
        if (state == TurnState.Idle) return
        partials.remove(itemId)
        partialTrusted.remove(itemId)
        partialCheckedAt.remove(itemId)
        if (awaitingSegments > 0) awaitingSegments--
        val text = transcript.trim()
        val idx = segments.indexOfFirst { it.first == itemId }
        if (idx >= 0) {
            segments[idx] = itemId to text
            segmentsText = null
        } // revised transcript replaces, never duplicates
        else {
            val byItem = evidenceByItem.remove(itemId)
            val segEvidence: SpeechEvidence = byItem ?: if (pendingEvidence.isNotEmpty()) pendingEvidence.removeFirst() else SpeechEvidence.Unheard
            val filter = segmentFilter
            if (text.isNotEmpty() && filter != null && !filter(text, segEvidence)) {
                // Dropped: the turn continues (or ends) as if this sound had not been transcribed.
                lastSegmentAt = clock.nowMs
                fireLiveTextChanged(displayText())
                if (segments.isEmpty() && partials.isEmpty() && awaitingSegments == 0 && (state == TurnState.PossibleEnd || state == TurnState.TurnSettling)) {
                    speechStoppedAt = -1
                    settleDeadline = -1
                    turnStartMs = -1
                    turnEvidenceAcc = SpeechEvidence(0, 0, 0, 0, 0)
                    reopened = null
                    setState(TurnState.Listening)
                }
                return
            }
            turnEvidenceAcc += segEvidence
            if (text.isNotEmpty() && !(segments.isNotEmpty() && sameText(segments.last().second, text))) // a re-delivered segment is not new speech
            {
                segments.add(itemId to text)
                segmentsText = null
                bound()
            }
        }
        lastSegmentAt = clock.nowMs
        fireLiveTextChanged(displayText())
        if (state == TurnState.PossibleEnd || state == TurnState.TurnSettling) scheduleSettle()
    }

    /**
     * Transcription connection dropped: in-flight partials are lost, but completed segments are kept and finalized
     * normally (a question spoken just before a reconnect is still answered).
     */
    fun onConnectionInterrupted() {
        partials.clear()
        partialTrusted.clear()
        partialCheckedAt.clear()
        awaitingSegments = 0
        // Transcripts of the dropped connection never arrive: their evidence must not shift onto later segments.
        pendingEvidence.clear()
        evidenceByItem.clear()
        if (segments.isEmpty()) {
            reopened = null
            if (state != TurnState.Idle) setState(TurnState.Listening)
            return
        }
        if (speechStoppedAt < 0) speechStoppedAt = clock.nowMs
        scheduleSettle()
    }

    /** Called periodically (~50 ms). Finalizes when the settle deadline passes. */
    fun tick() {
        val now = clock.nowMs
        if (state == TurnState.TurnSettling && settleDeadline >= 0 && now >= settleDeadline && awaitingSegments == 0) {
            finalizeTurn(now)
            return
        }
        // Speech stopped but the transcript for the last segment never arrived: don't hang forever.
        if ((state == TurnState.PossibleEnd || state == TurnState.TurnSettling) && speechStoppedAt >= 0 && now - speechStoppedAt > missingTranscriptTimeoutMs) {
            awaitingSegments = 0
            if (segments.isNotEmpty() || partials.isNotEmpty()) finalizeTurn(now)
            else {
                reset()
                setState(TurnState.Listening)
            }
        }
    }

    private fun scheduleSettle() {
        if (segments.isEmpty()) return // wait for text
        val text = currentText()
        val delay = (adaptiveDelayMs(text) * sensitivity).toInt()
        val anchor = if (speechStoppedAt >= 0) speechStoppedAt else clock.nowMs
        settleDeadline = max(anchor + delay, lastSegmentAt)
        setState(TurnState.TurnSettling)
    }

    fun adaptiveDelayMs(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return defaultMs
        if (TrailingIncomplete.containsMatchIn(t)) return incompleteMs
        if (AnnouncedMultiPart.containsMatchIn(t) && TextNormalizer.questionMarkCount(t) <= 1) return incompleteMs
        val lastSentence = SentenceSplit.split(t).lastOrNull { it.isNotEmpty() } ?: t
        if (TextNormalizer.endsWithQuestionMark(t)) return questionCompleteMs
        if (QuestionStart.containsMatchIn(lastSentence) && t.endsWith('.')) return (questionCompleteMs + defaultMs) / 2
        return defaultMs
    }

    private fun finalizeTurn(now: Long) {
        var text = TextNormalizer.cleanTranscript(currentText(), aliases)
        termNormalizer?.let { text = it(text) }
        val speechEnd = speechStoppedAt
        val count = segments.size
        var reopenedLocal = reopened
        val reopenGap = reopenGapMs
        // Evidence for segments whose transcript never arrived still belongs to this turn.
        var evidence = turnEvidenceAcc
        while (pendingEvidence.isNotEmpty()) evidence += pendingEvidence.removeFirst()
        for (e in evidenceByItem.values) evidence += e
        val startMs = turnStartMs
        reset()
        if (isNonQuestion(text)) {
            setState(TurnState.Listening)
            return
        } // an acknowledgement never reopens or creates a turn

        var revises: Int? = null
        // A short follow-up that points back at the answer ("Tell me more.", "Why?", "Why.", "Give me another example.") after
        // a complete question is a NEW ask once the candidate could have started answering — it starts at least
        // FollowUpReplyAllowanceMs after the previous speech stopped: merging it would replace the previous question's answer
        // instead of answering the follow-up. Said straight after the question ("What is your biggest weakness? … Explain."),
        // it is part of one multi-part question and merges, as in rc.4, as does a follow-up after an unfinished question
        // ("…and" + "go on"). Whether the previous answer was already on screen is deliberately not used: a prepared answer
        // appears at once, long before the candidate can have said anything.
        val follow = reopenedLocal
        if (follow != null && FollowUpIntent.isStandaloneFollowUp(text) && isCompleteAsk(follow.text) && reopenGap >= followUpReplyAllowanceMs) {
            reopenedLocal = null
            fireFollowUpSplit(FollowUpIntent.classify(text))
        }
        val prev = reopenedLocal
        if (prev != null) {
            val restated = stripRestartCues(text)
            if (restated.length < text.length && restated.isNotEmpty()) {
                text = restated[0].uppercaseChar() + restated.substring(1) // restated: the new wording replaces the old
            } else if (isRestatement(prev.text, text)) {
                text = if (TextNormalizer.wordCount(text) >= TextNormalizer.wordCount(prev.text)) text else prev.text
            } else {
                text = prev.text.trimEnd() + " " + text // continued: one multi-part question
            }
            // The continued turn's words were spoken across both turns' audio: judge them against both.
            evidence = prev.evidence + evidence
            if (text == prev.text) {
                rememberFinal(prev.id, text, speechEnd, evidence)
                setState(TurnState.Listening)
                return
            }
            revises = prev.id
        }

        val id = revises ?: ++nextTurnId
        rememberFinal(id, text, speechEnd, evidence)
        setState(TurnState.QuestionFinalized)
        fireTurnFinalized(FinalizedTurn(text, speechEnd, now, count, id, revises, evidence, startMs))
        setState(TurnState.Listening)
    }

    private fun rememberFinal(id: Int, text: String, speechEnd: Long, evidence: SpeechEvidence) {
        lastFinal = LastFinal(id, text, if (speechEnd >= 0) speechEnd else clock.nowMs, evidence)
    }

    private fun bound() {
        var total = segments.sumOf { it.second.length + 1 }
        while ((total > maxTurnChars || segments.size > maxSegmentsPerTurn) && segments.size > 2) {
            total -= segments[1].second.length + 1
            segments.removeAt(1)
            droppedSegmentsCount++
        }
    }

    fun currentText(): String {
        val segmentsJoined = segmentsText()
        if (partials.isEmpty()) return segmentsJoined
        val sb = StringBuilder(segmentsJoined)
        for (p in partials.values) {
            if (p.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p.toString().trim())
        }
        return sb.toString()
    }

    private fun segmentsText(): String {
        var cached = segmentsText
        if (cached == null) {
            val b = StringBuilder()
            for (i in segments.indices) {
                if (b.isNotEmpty()) b.append(' ')
                if (i == 1 && droppedSegmentsCount > 0) b.append("… ")
                b.append(segments[i].second)
            }
            cached = b.toString()
            segmentsText = cached
        }
        return cached
    }

    /**
     * Longest live text a consumer shows (only its tail is ever displayed). [displayText] builds at most this
     * much, so a minute-long partial does not allocate the whole transcript on every delta.
     */
    var displayTailChars: Int = Int.MAX_VALUE

    /**
     * What may be shown as live text: accepted segments plus the partials that passed [partialFilter]
     * (the last [displayTailChars] characters of it).
     */
    fun displayText(): String {
        val segmentsJoined = segmentsText()
        val parts = mutableListOf<String>()
        if (segmentsJoined.isNotEmpty()) parts.add(segmentsJoined)
        for ((id, p) in partials) {
            if (p.isEmpty() || !(partialTrusted[id] ?: (partialFilter == null))) continue
            val take = min(p.length, displayTailChars)
            parts.add((if (take < p.length) p.substring(p.length - take, p.length) else p.toString()).trim())
        }
        if (parts.size == 1 && parts[0].length <= displayTailChars) return parts[0]
        // Walk back from the newest part until the tail is full.
        val tail = mutableListOf<String>()
        var len = 0
        for (i in parts.size - 1 downTo 0) {
            if (len >= displayTailChars) break
            val part = parts[i]
            val room = displayTailChars - len
            tail.add(if (part.length > room) part.substring(part.length - room) else part)
            len += min(part.length, room) + 1
        }
        tail.reverse()
        return tail.joinToString(" ")
    }

    /** Number of segments elided from the middle of the current turn (diagnostics). */
    val droppedSegments: Int get() = droppedSegmentsCount

    private fun setState(s: TurnState) {
        if (state == s) return
        state = s
        for (listener in stateChangedListeners) listener(s)
    }

    private fun fireLiveTextChanged(text: String) {
        for (listener in liveTextChangedListeners) listener(text)
    }

    private fun fireTurnFinalized(turn: FinalizedTurn) {
        for (listener in turnFinalizedListeners) listener(turn)
    }

    private fun fireFollowUpSplit(kind: FollowUpKind) {
        for (listener in followUpSplitListeners) listener(kind)
    }

    companion object {
        private val TrailingIncomplete = dotNetRegex("(\\b(and|or|but|so|because|then|also|plus|the|a|an|to|of|with|about|for|in|on|like|which|that|if|how|what|your|my|our|is|are|would|could|um|uh|specifically|as well as|in addition)|,|…|-)\\s*$", ignoreCase = true)
        private val QuestionStart = dotNetRegex("^(how|what|why|when|where|which|who|can|could|would|will|do|does|did|have|has|is|are|should|tell|describe|walk|explain|give)\\b", ignoreCase = true)
        // "I have two questions", "a couple of things", "first, …" announce more than one part.
        private val AnnouncedMultiPart = dotNetRegex("\\b(two|three|couple of|a few) (questions|things|parts|points)\\b|\\btwo-part\\b|\\b(first(ly)?|to start)\\s*,", ignoreCase = true)
        private val SentenceSplit = dotNetRegex("(?<=[.?!？؟])\\s+|(?<=[。！？])")
        private val Pleasantry = dotNetRegex("^(ok(ay)?|great|thanks?|thank you|perfect|nice|good|right|sure|yes|yeah|no|mm-?hmm|alright|cool|interesting|i see|got it|wonderful|excellent|very good)[\\s,.!]*((thanks?|thank you|great|perfect|okay)[\\s,.!]*)*$", ignoreCase = true)
        private val RestartCue = dotNetRegex("^(sorry|let me (rephrase|put it|ask (it|that) (differently|another way))|i mean|rather|to rephrase|what i('m| am) asking is|actually,? let me)\\b[\\s,.:—-]*", ignoreCase = true)
        private val OneWordAsk = dotNetRegex("^(why|how|really|example|examples|which|meaning|explain|elaborate|when|where|who|what)$", ignoreCase = true)
        private val Filler = dotNetRegex("^(h+m+|m+h*m+|uh+|um+|huh|eh|ah+|oh+|sorry|pardon|hello|hi|hey|bye)$", ignoreCase = true)

        /** "Sorry, let me rephrase: how would…" → "how would…" (cues may be chained). */
        private fun stripRestartCues(text: String): String {
            var t = text.trim()
            for (i in 0 until 3) {
                val m = RestartCue.find(t) ?: break
                if (m.range.first != 0 || m.value.isEmpty() || m.value.length >= t.length) break
                t = t.substring(m.value.length).trimStart()
            }
            return t
        }

        /** True for short acknowledgements that should never trigger an answer. */
        fun isNonQuestion(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return true
            if (Pleasantry.containsMatchIn(t)) return true
            // Word counts are only meaningful for Latin script: a whole Chinese, Arabic or Persian question counts as < 2 "words".
            if (TextNormalizer.isLatinScript(t) && TextNormalizer.wordCount(t) < 2) {
                // One word is a real follow-up when it is asked ("Metrics?", "Timeline?") or is an interrogative a recognizer
                // wrote with falling punctuation ("Why.", "How", "Really."); acknowledgements and fillers are not ("Okay?",
                // "Hm?", "Sorry?" — the last asks the interviewer to repeat, not the candidate to answer).
                val word = t.trimEnd('?', '？', '؟', '.', '!', '…', ' ')
                if (word.isEmpty() || Pleasantry.containsMatchIn(word) || Filler.containsMatchIn(word)) return true
                return !(TextNormalizer.endsWithQuestionMark(t) || OneWordAsk.containsMatchIn(word) || FollowUpIntent.classify(t) != FollowUpKind.None)
            }
            return false
        }

        /** The previous turn ended as a complete ask (terminal punctuation, no dangling conjunction or article). */
        private fun isCompleteAsk(text: String): Boolean {
            val t = text.trimEnd()
            return t.isNotEmpty() && !TrailingIncomplete.containsMatchIn(t) && t.last() in ".?!？؟。！"
        }

        private fun sameText(a: String, b: String): Boolean = TextNormalizer.fingerprint(a) == TextNormalizer.fingerprint(b)

        /** The new segment repeats the previous question (mostly the same words) rather than adding a part. */
        private fun isRestatement(previous: String, next: String): Boolean {
            val p = TextNormalizer.fingerprint(previous).split(' ').filter { it.isNotEmpty() }.toHashSet()
            val n = TextNormalizer.fingerprint(next).split(' ').filter { it.isNotEmpty() }
            if (n.isEmpty() || p.isEmpty()) return false
            return n.count { it in p }.toDouble() / n.size >= 0.8
        }
    }
}
