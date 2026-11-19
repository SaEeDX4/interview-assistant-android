package com.interviewassistant.core.turn

import com.interviewassistant.core.intelligence.TextNormalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Mirror of `InterviewAssistant.Core/Turn/DuplicateGuard.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/**
 * Prevents a second answer for the same question caused by transcript revisions, repeated finals or
 * punctuation/word corrections. Compares token-set fingerprints within a time window. Bounded memory.
 * A follow-up ("Tell me more.", "Why?") has no topic of its own: it asks about the answer before it. Its entry is therefore
 * scoped by the last question that had been ANSWERED when it was asked: the same words after another completed answer
 * (including the answer to the first "Tell me more.") are a new ask, while the same transcript echoed for the same turn —
 * before anything new was answered — still has the same scope and is suppressed.
 */
class DuplicateGuard(
    val capacity: Int = 12,
    val windowMs: Long = 30_000, // revisions arrive within seconds; a re-ask minutes later deserves an answer
    val similarityThreshold: Double = 0.8,
) {
    private data class Entry(val tokens: Set<String>, val atMs: Long, val text: String, val scope: Int?)

    private val recent = ArrayDeque<Entry>()

    var suppressedCount: Int = 0
        private set

    /**
     * @param scope For a follow-up: the last answered question it follows (e.g. that record's index); null otherwise.
     * A scoped query only matches entries with the same scope; an unscoped one matches every entry, as before.
     */
    fun isDuplicate(question: String, nowMs: Long, scope: Int? = null): Boolean {
        val tokens = TextNormalizer.fingerprint(question).split(' ').filter { it.isNotEmpty() }.toHashSet()
        for (r in recent) {
            if (nowMs - r.atMs > windowMs) continue
            if (scope != null && r.scope != scope) continue
            val sim = TextNormalizer.jaccard(tokens, r.tokens)
            // Also treat "same question + one extra word" as duplicate (containment).
            val containment = if (tokens.isEmpty()) 0.0 else tokens.count { it in r.tokens }.toDouble() / max(tokens.size, r.tokens.size)
            if (sim >= similarityThreshold || (containment >= 0.85 && abs(tokens.size - r.tokens.size) <= 1)) {
                suppressedCount++
                return true
            }
        }
        return false
    }

    fun register(question: String, nowMs: Long, scope: Int? = null) {
        val tokens = TextNormalizer.fingerprint(question).split(' ').filter { it.isNotEmpty() }.toHashSet()
        recent.addFirst(Entry(tokens, nowMs, question, scope))
        while (recent.size > capacity) recent.removeLast()
    }

    fun clear() = recent.clear()
}
