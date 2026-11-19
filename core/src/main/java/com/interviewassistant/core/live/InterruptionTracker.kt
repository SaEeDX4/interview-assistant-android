package com.interviewassistant.core.live

import com.interviewassistant.core.intelligence.dotNetRound2
import kotlin.math.min

/**
 * Mirror of `InterviewAssistant.Core/Live/InterruptionTracker.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

enum class AdaptiveLevel { Normal, Concise, Rapid }

/**
 * Heuristic, NOT certainty: without the candidate microphone we cannot know whether the interviewer interrupted
 * the candidate or simply asked the next question quickly. We estimate an interruption confidence from
 * how soon interviewer speech resumed relative to how long the shown answer takes to say, plus repeated short turns.
 * First likely interruption → Concise; repeated within 5 minutes → Rapid; three calm answers step back down.
 */
class InterruptionTracker {
    var level: AdaptiveLevel = AdaptiveLevel.Normal
        private set
    var manuallyLocked: Boolean = false
        private set
    private var answerShownMs: Long = -1
    private var answerWords: Int = 0
    private var answerIndex: Int = 0
    private var calmAnswers: Int = 0
    private val recentEventsMs = mutableListOf<Long>()

    private val levelChangedListeners = mutableListOf<(AdaptiveLevel) -> Unit>()

    fun addLevelChangedListener(listener: (AdaptiveLevel) -> Unit) {
        levelChangedListeners.add(listener)
    }

    fun answerShown(nowMs: Long, words: Int, questionIndex: Int) {
        answerShownMs = nowMs
        answerWords = words
        answerIndex = questionIndex
    }

    /** Called when interviewer speech starts. Returns an event when it looks like an interruption/fast follow. */
    fun interviewerSpeechStarted(nowMs: Long): InterruptionEvent? {
        if (answerShownMs < 0 || answerWords == 0) return null
        val elapsed = nowMs - answerShownMs
        val expected = (answerWords / SpeakingWordsPerSecond * 1000).toLong() + 1500 // + time to start speaking
        answerShownMs = -1 // one evaluation per answer
        if (elapsed >= expected * 0.85) {
            calm()
            return null
        }
        var confidence = (1.0 - elapsed.toDouble() / expected).coerceIn(0.0, 1.0)
        recentEventsMs.removeAll { nowMs - it > 5 * 60_000 }
        if (recentEventsMs.isNotEmpty()) confidence = min(1.0, confidence + 0.15) // repeated short-turn pattern
        if (confidence < 0.35) {
            calm()
            return null
        }
        recentEventsMs.add(nowMs)
        calmAnswers = 0
        if (!manuallyLocked) {
            val next = if (recentEventsMs.size >= 2) AdaptiveLevel.Rapid else AdaptiveLevel.Concise
            if (next.ordinal > level.ordinal) setLevel(next)
        }
        return InterruptionEvent(
            afterQuestionIndex = answerIndex,
            confidence = dotNetRound2(confidence),
            elapsedMs = elapsed,
            expectedMs = expected,
            note = if (confidence >= 0.7) "likely interruption" else "fast follow-up (uncertain)",
        )
    }

    private fun calm() {
        if (manuallyLocked || level == AdaptiveLevel.Normal) return
        if (++calmAnswers >= 3) {
            calmAnswers = 0
            setLevel(AdaptiveLevel.entries[level.ordinal - 1])
        }
    }

    fun reset() {
        manuallyLocked = false
        recentEventsMs.clear()
        calmAnswers = 0
        setLevel(AdaptiveLevel.Normal)
    }

    fun lock(level: AdaptiveLevel) {
        manuallyLocked = true
        setLevel(level)
    }

    private fun setLevel(l: AdaptiveLevel) {
        if (l == level) return
        level = l
        for (listener in levelChangedListeners) listener(l)
    }

    companion object {
        const val SpeakingWordsPerSecond = 2.4 // ~145 wpm professional speech
    }
}
