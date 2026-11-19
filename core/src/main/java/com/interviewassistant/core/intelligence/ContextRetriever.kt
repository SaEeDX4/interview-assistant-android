package com.interviewassistant.core.intelligence

import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.BankQuestion
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.knowledge.KnowledgeSnippet

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/ContextRetriever.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class RetrievedContext(
    val stories: List<CandidateStory>,
    val snippets: List<KnowledgeSnippet>,
    val reference: BankQuestion?,
)

/** Selects a compact, relevant slice of knowledge for one question (never the whole CV/whitepaper). */
class ContextRetriever(private val kb: KnowledgeBase) {
    private val snippetIndex: List<Pair<KnowledgeSnippet, Set<String>>> =
        kb.snippets.map { s -> s to TextNormalizer.tokenize(s.heading + " " + s.text).toSet() }
    private val storyById: Map<String, CandidateStory> = kb.stories.associateBy { it.id }

    fun retrieve(
        question: String,
        cls: Classification,
        match: MatchResult,
        maxStories: Int = 3,
        maxSnippets: Int = 6,
        storyPenalty: ((String) -> Double)? = null,
    ): RetrievedContext {
        val qTokens = TextNormalizer.tokenize(question).toSet()

        // Stories: evidence attached to the matched bank question first, then by category, then lexical overlap.
        val stories = mutableListOf<CandidateStory>()
        if (match.question != null && match.confidence >= MatchConfidence.Medium) {
            for (id in match.question.candidateEvidence) {
                val s = storyById[id]
                if (s != null && !stories.contains(s) && (storyPenalty?.invoke(id) ?: 0.0) < 2) stories.add(s)
            }
        }

        val ranked = kb.stories
            .map { s ->
                s to ((if (s.questionTypes.contains(cls.category)) 2.0 else 0.0) +
                    qTokens.count { t -> TextNormalizer.tokenize(s.company + " " + s.action + " " + s.result + " " + s.skills.joinToString(" ")).contains(t) } -
                    (storyPenalty?.invoke(s.id) ?: 0.0) * 0.8)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        for (s in ranked) {
            if (stories.size >= maxStories) break
            if (!stories.contains(s)) stories.add(s)
        }
        if (stories.isEmpty() && cls.mode != AnswerMode.Hypothetical) {
            // relevance order, least-used first
            stories.addAll(kb.stories.sortedBy { storyPenalty?.invoke(it.id) ?: 0.0 }.take(2))
        }

        val snippets = snippetIndex
            .map { (snippet, tokens) -> snippet to (TextNormalizer.jaccard(qTokens, tokens) + qTokens.count { tokens.contains(it) } * 0.05) }
            .filter { it.second > 0.04 }
            .sortedByDescending { it.second }
            .take(maxSnippets)
            .map { it.first }

        val reference = if (match.confidence >= MatchConfidence.Medium) match.question else null
        return RetrievedContext(stories, snippets, reference)
    }
}
