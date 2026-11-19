package com.interviewassistant.core.intelligence

import com.interviewassistant.core.knowledge.BankQuestion
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/QuestionMatcher.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

enum class MatchConfidence { None, Low, Medium, High }

data class MatchResult(
    val question: BankQuestion?,
    val score: Double,
    val confidence: MatchConfidence,
    val runnerUp: BankQuestion?,
    val runnerUpScore: Double,
)

/**
 * Local hybrid semantic matcher: BM25 over canonical question + variants + keywords, blended with
 * character-trigram similarity against the best individual phrasing. No external infrastructure.
 */
class QuestionMatcher(questions: Iterable<BankQuestion>) {
    private class Doc(
        val question: BankQuestion,
        val termFreq: Map<String, Int>,
        val length: Int,
        val phrasings: List<Pair<Set<String>, Set<String>>>,
    )

    private val docs = mutableListOf<Doc>()
    private val idf = mutableMapOf<String, Double>()
    private val avgLen: Double

    var highThreshold: Double = 0.62
    var mediumThreshold: Double = 0.42
    var lowThreshold: Double = 0.25

    init {
        for (q in questions) {
            val phrasings = mutableListOf(q.canonicalQuestion)
            phrasings.addAll(q.semanticVariants)
            val allTokens = phrasings.flatMap { TextNormalizer.tokenize(it) } + q.keywords.map { TextNormalizer.stem(it) }
            val tf = allTokens.groupingBy { it }.eachCount()
            docs.add(
                Doc(
                    question = q,
                    termFreq = tf,
                    length = allTokens.size,
                    phrasings = phrasings.map { p -> TextNormalizer.tokenize(p).toSet() to TextNormalizer.charTrigrams(p) },
                ),
            )
        }
        avgLen = if (docs.isEmpty()) 1.0 else docs.sumOf { it.length }.toDouble() / docs.size
        val df = mutableMapOf<String, Int>()
        for (d in docs) for (t in d.termFreq.keys) df[t] = (df[t] ?: 0) + 1
        for ((t, n) in df) idf[t] = ln(1.0 + (docs.size - n + 0.5) / (n + 0.5))
    }

    fun match(question: String): MatchResult {
        val qTokens = TextNormalizer.tokenize(question)
        if (qTokens.isEmpty() || docs.isEmpty()) return MatchResult(null, 0.0, MatchConfidence.None, null, 0.0)
        val qSet = qTokens.toSet()
        val qTri = TextNormalizer.charTrigrams(question)

        // Max achievable BM25 for the query (self-match) to normalise into [0,1].
        val maxBm25 = qSet.sumOf { t -> (idf[t] ?: ln(1.0 + docs.size)) * (k1 + 1) / (1 + k1) }

        val scored = mutableListOf<Pair<Doc, Double>>()
        for (d in docs) {
            var bm25 = 0.0
            for (t in qSet) {
                val f = d.termFreq[t] ?: continue
                val idfT = idf.getValue(t)
                bm25 += idfT * f * (k1 + 1) / (f + k1 * (1 - b + b * d.length / avgLen))
            }
            val bm25Norm = min(1.0, bm25 / max(maxBm25, 1e-6))

            var bestPhr = 0.0
            for ((tok, tri) in d.phrasings) {
                val tokSim = TextNormalizer.jaccard(qSet, tok)
                val triSim = TextNormalizer.jaccard(qTri, tri)
                bestPhr = max(bestPhr, 0.55 * tokSim + 0.45 * triSim)
            }
            scored.add(d to 0.5 * bm25Norm + 0.5 * min(1.0, bestPhr * 1.35))
        }
        scored.sortByDescending { it.second }
        val best = scored[0]
        val second = if (scored.size > 1) scored[1] else (best.first to 0.0)

        var conf = when {
            best.second >= highThreshold -> MatchConfidence.High
            best.second >= mediumThreshold -> MatchConfidence.Medium
            best.second >= lowThreshold -> MatchConfidence.Low
            else -> MatchConfidence.None
        }
        // Ambiguity: two different intents score almost equally -> don't trust the cache blindly.
        if (conf == MatchConfidence.High && second.second > 0 && best.second - second.second < 0.03) conf = MatchConfidence.Medium
        if (conf == MatchConfidence.High && !topicConsistent(question, best.first.question)) conf = MatchConfidence.Medium

        return MatchResult(best.first.question, best.second, conf, if (scored.size > 1) second.first.question else null, second.second)
    }

    companion object {
        private const val k1 = 1.2
        private const val b = 0.6

        // Categories that say little about the topic (any specific topic may legitimately match them).
        private val broadCategories: Set<String> = setOf("GENERAL_BUSINESS", "FOLLOW_UP", "RESUME_EXPERIENCE", "BEHAVIOURAL", "GAP_EXPERIENCE", "COMPANY_SPECIFIC")

        /**
         * Topic guard for instant cache answers: when the spoken question and the matched prepared question classify into
         * two different SPECIFIC topics (e.g. CRYPTO vs INTRODUCTION), the match is not trusted as High.
         */
        fun topicConsistent(spoken: String, matched: BankQuestion): Boolean {
            val a = QuestionClassifier.classify(spoken).category
            val bCat = QuestionClassifier.classify(matched.canonicalQuestion).category
            return a == bCat || broadCategories.contains(a) || broadCategories.contains(bCat)
        }
    }
}
