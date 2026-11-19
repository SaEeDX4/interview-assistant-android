package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.ingestion.regexEscape
import com.interviewassistant.core.knowledge.TargetContext

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/QuestionExtractor.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port — hosts `ExtractedQuestion`/`QuestionExtractor` and `PendingContext`,
 * exactly the types the C# file hosts.
 */

/**
 * The actual ask hidden inside an interviewer turn. Long turns often start with background and end with the question
 * ("Our reward program grew quickly… Engineering has limited capacity. So if you owned this product, what would you
 * change first and how would you measure whether it worked?"). Classification, matching and the answer focus on
 * [coreQuestion]; the context keeps the constraints the answer must respect. No chain-of-thought, only fields.
 */
data class ExtractedQuestion(
    val original: String,
    val coreQuestion: String,
    val subQuestions: List<String>,
    val contextSummary: String,
    val promptContext: String,
    val constraints: List<String>,
    val entities: List<String>,
    val confidence: Double,
    val directedToCandidate: Boolean = true,
    val speakerLabel: String? = null,
) {
    /** Typed question or single-sentence turn: the text is the question. */
    companion object {
        fun plain(q: String): ExtractedQuestion = ExtractedQuestion(q, q, listOf(), "", "", listOf(), listOf(), 1.0)
    }

    val hasContext: Boolean get() = contextSummary.isNotEmpty()
}

object QuestionExtractor {
    const val maxContextChars = 900
    const val maxSummaryChars = 220
    const val maxCoreWords = 80

    private val sentenceSplit: Regex = dotNetRegex("(?<=[.?!…])\\s+(?=[\\p{Lu}\\p{Lo}\"“'(\\d])|(?<=[?؟])\\s+|(?<=[。！？])\\s*(?=\\S)|\\s+[—–]\\s+(?=[\\p{L}])|;\\s+")
    private val restartCue: Regex = dotNetRegex("\\b(sorry,? let me (rephrase|put (it|that) (differently|another way)|ask (it|that) differently)|let me rephrase|let me put (it|that) (differently|another way)|what i('m| am) (really )?asking is|in other words|to put it (differently|another way)|actually,? let me ask)\\b[\\s,:—-]*", ignoreCase = true)
    private val discourse: Regex = dotNetRegex("^((so|okay|ok|well|right|alright|now|then|anyway)[,.]?\\s+)+", ignoreCase = true)
    private val constraintCue: Regex = dotNetRegex("\\b(limited|limit|constraint|capacity|budget|deadline|quarter|weeks?|months?|only|can't|cannot|won't|must|regulat|complian|legal|risk|worried|concern|pressure|blocked|dependency|headcount|cost|small team|no (budget|team|data))\\b", ignoreCase = true)
    private val askStart: Regex = dotNetRegex("\\b(so,? )?(if you|what|how|why|which|would you|could you|can you|do you|tell me|walk me|talk me|describe|explain|give me)\\b", ignoreCase = true)
    private val subSplit: Regex = dotNetRegex("(?:,\\s*|\\s+)(?:and|also|plus)\\s+(?=(?:how|what|which|why|when|who|would|could|can|do|did|where)\\b)|(?<=[?؟])\\s+|(?<=？)\\s*(?=\\S)", ignoreCase = true)
    private val letterDigit: Regex = dotNetRegex("[\\p{L}\\p{N}]")

    /** Splits a transcript into sentences (also on dashes/semicolons used as pauses by the recognizer). */
    fun sentences(text: String): List<String> {
        val parts = sentenceSplit.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() && letterDigit.containsMatchIn(it) }
        return if (parts.isEmpty()) listOf(text.trim()) else parts
    }

    /**
     * @param text The finalized turn (cleaned).
     * @param earlierContext Context statements from the previous turns (already bounded), oldest first.
     */
    fun extract(text: String, earlierContext: String, ctx: TargetContext?, gate: UtteranceGate): ExtractedQuestion {
        val original = text.trim()
        var working = original
        var restartPrefix = ""
        // "…prioritise the backlog — sorry, let me put it differently: what would you cut first?" → the restated ask wins.
        val restarts = restartCue.findAll(working).toList()
        if (restarts.isNotEmpty()) {
            val last = restarts.last()
            val after = working.substring(last.range.last + 1).trim()
            if (TextNormalizer.wordCount(after) >= 3) {
                restartPrefix = working.substring(0, last.range.first).trim()
                working = capitalize(after)
            }
        }

        val sentences = sentences(working)
        var lastQ = -1
        for (i in sentences.indices.reversed()) {
            if (UtteranceGate.isQuestionSentence(sentences[i]) && gate.addressedTo(sentences[i]) == UtteranceGate.Addressee.Candidate) {
                lastQ = i
                break
            }
        }

        var first = lastQ
        var endIdx = lastQ
        var confidence: Double
        if (lastQ >= 0) {
            confidence = if (TextNormalizer.endsWithQuestionMark(sentences[lastQ])) 1.0 else 0.85
            // Adjacent question sentences belong to the same ask ("What would you change first? And how would you measure it?").
            for (i in lastQ - 1 downTo 0) {
                if (UtteranceGate.isQuestionSentence(sentences[i]) && gate.addressedTo(sentences[i]) == UtteranceGate.Addressee.Candidate) {
                    first = i
                    continue
                }
                if (TextNormalizer.wordCount(sentences[i]) <= 4 && i - 1 >= 0 && UtteranceGate.isQuestionSentence(sentences[i - 1])) continue // "Right." between parts
                break
            }
        } else {
            // No explicit question: an implicit prompt is the last sentence.
            first = sentences.size - 1
            endIdx = sentences.size - 1
            confidence = 0.6
        }

        val coreSentences = sentences.drop(first).take(endIdx - first + 1)
        var core = coreSentences.joinToString(" ")
        // A single very long sentence without punctuation: start at the first ask cue in its final part.
        if (TextNormalizer.wordCount(core) > maxCoreWords) {
            val words = core.split(' ').filter { it.isNotEmpty() }
            val tailStart = words.take(words.size - maxCoreWords).joinToString(" ").length
            val m = askStart.findAll(core).firstOrNull { it.range.first >= tailStart } ?: askStart.findAll(core).lastOrNull()
            if (m != null && m.range.first > 0) {
                core = capitalize(core.substring(m.range.first))
                confidence = minOf(confidence, 0.75)
            } else {
                core = words.takeLast(maxCoreWords).joinToString(" ")
                confidence = minOf(confidence, 0.6)
            }
        }
        core = capitalize(discourse.replace(core, "").trim())

        val contextSentences = mutableListOf<String>()
        if (restartPrefix.isNotEmpty()) contextSentences.addAll(sentences(restartPrefix).filter { !UtteranceGate.isQuestionSentence(it) })
        contextSentences.addAll(sentences.take(first).filter { gate.addressedTo(it) == UtteranceGate.Addressee.Candidate })
        // A sentence that only restates the ask (the interviewer said it twice, or a revised segment repeated it) is the
        // question, not context: it must never show up again as its own context line.
        contextSentences.removeAll { restatesAsk(it, core) }
        val inTurn = contextSentences.joinToString(" ")
        val earlier = sentences(earlierContext).filter { earlierContext.trim().isNotEmpty() && !restatesAsk(it, core) }.joinToString(" ")
        val allContext = join(earlier, inTurn)

        var subs = subSplit.split(core).filter { it.isNotEmpty() }
            .map { capitalize(it.trim().trimEnd(',')) }
            .filter { TextNormalizer.wordCount(it) >= 3 }
        if (subs.size < 2) subs = listOf()

        val allSentences = sentences(allContext)
        val constraints = allSentences.filter { constraintCue.containsMatchIn(it) }.map { trim(it, 160) }.takeLast(4)
        val entities = if (ctx == null) listOf()
        else (listOf(ctx.companyName) + ctx.products + ctx.vocabulary).filter { it.length > 1 }
            .filter { e -> dotNetRegex("(^|[^\\p{L}\\p{N}])" + regexEscape(e) + "($|[^\\p{L}\\p{N}])", ignoreCase = true).containsMatchIn(original) }
            .distinctBy { it.lowercase() }.take(8)

        return ExtractedQuestion(original, core, subs, summary(allSentences, constraints), tail(allContext, maxContextChars), constraints, entities, confidence)
    }

    /** Short, readable context line for the live window: constraint sentences first, then the most recent ones. */
    private fun summary(sentences: List<String>, constraints: List<String>): String {
        if (sentences.isEmpty() || sentences.all { it.isEmpty() }) return ""
        val picked = mutableListOf<String>()
        // Most recent constraints first (closest to the ask), then the most recent other sentences.
        for (s in constraints.reversed() + sentences.reversed()) {
            if (picked.contains(s)) continue
            if (picked.sumOf { it.length } + s.length > maxSummaryChars && picked.isNotEmpty()) break
            picked.add(s)
        }
        val ordered = sentences.filter { picked.contains(it) }.distinct()
        val text = (if (ordered.isNotEmpty()) ordered else picked).joinToString(" ")
        return trim(text, maxSummaryChars)
    }

    /** The sentence says (nearly) the same as the core question: same content words, or a subset of them. */
    internal fun restatesAsk(sentence: String, core: String): Boolean {
        val a = TextNormalizer.tokenize(sentence).toSet()
        val b = TextNormalizer.tokenize(core).toSet()
        if (a.isEmpty() || b.isEmpty())
            return sentence.trim().trimEnd('?', '.', '!').equals(core.trim().trimEnd('?', '.', '!'), ignoreCase = true)
        if (a.size >= 2 && b.containsAll(a)) return true
        return TextNormalizer.jaccard(a, b) >= 0.75
    }

    private fun join(a: String, b: String): String =
        if (a.isEmpty()) b else if (b.isEmpty()) a else a.trimEnd() + " " + b.trimStart()

    private fun tail(s: String, max: Int): String =
        if (s.length <= max) s else "…" + s.substring(s.length - max).trimStart()

    private fun trim(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max).trimEnd() + "…"

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
}

/**
 * Bounded buffer of interviewer context statements awaiting a question (by turn id, so a merged/continued turn
 * replaces its own entry). Older than [maxAgeMs] or beyond [maxChars] is dropped.
 */
class PendingContext {
    private data class Item(val turnId: Int, val text: String, val atMs: Long)

    private val lock = Any()
    private val items = mutableListOf<Item>()

    var maxChars: Int = 1500
    var maxAgeMs: Long = 120_000

    fun add(turnId: Int, text: String, nowMs: Long) {
        synchronized(lock) {
            if (turnId > 0) items.removeAll { it.turnId == turnId }
            items.add(Item(turnId, text, nowMs))
            while (items.sumOf { it.text.length } > maxChars && items.size > 1) items.removeAt(0)
        }
    }

    fun remove(turnId: Int) {
        synchronized(lock) { items.removeAll { it.turnId == turnId } }
    }

    /** Returns and clears the fresh context (oldest first). */
    fun take(nowMs: Long): String {
        synchronized(lock) {
            val text = items.filter { nowMs - it.atMs <= maxAgeMs }.joinToString(" ") { it.text }
            items.clear()
            return if (text.length > maxChars) text.substring(text.length - maxChars) else text
        }
    }

    val count: Int get() = synchronized(lock) { items.size }

    fun clear() {
        synchronized(lock) { items.clear() }
    }
}
