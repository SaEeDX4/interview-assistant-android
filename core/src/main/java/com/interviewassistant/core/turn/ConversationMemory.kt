package com.interviewassistant.core.turn

import com.interviewassistant.core.intelligence.ConversationTurn

/**
 * Mirror of `InterviewAssistant.Core/Turn/ConversationMemory.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** Small rolling context (last N Q/A summaries) for follow-up interpretation. Bounded. */
class ConversationMemory(val capacity: Int = 5) {
    private val turns = ArrayDeque<ConversationTurn>()

    fun add(question: String, answerBullets: Iterable<String>) {
        val summary = answerBullets.take(3).joinToString(" ") { b -> if (b.length > 110) b.substring(0, 110) + "…" else b }
        turns.addLast(ConversationTurn(question, summary))
        while (turns.size > capacity) turns.removeFirst()
    }

    fun snapshot(): List<ConversationTurn> = turns.toList()
    fun clear() = turns.clear()
    val count: Int get() = turns.size
}
