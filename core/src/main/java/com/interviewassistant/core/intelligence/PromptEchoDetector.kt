package com.interviewassistant.core.intelligence

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.ingestion.dotNetRegex

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/PromptEchoDetector.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class EchoResult(val isEcho: Boolean, val reason: String, val coverage: Double, val longestRun: Int)

/**
 * Speech-recognition models can "transcribe" their own bias prompt when they receive a non-speech sound (a beep, a
 * click): the result reads like "Job interview for a Product Owner role at Teroxx. Terms: XAB, MiCA, Jira…". No
 * interviewer speaks the prompt, so a transcript that reproduces its structure or a long run of its words is an echo.
 * Mentioning one or two of the terms ("What do you know about Teroxx?") is never an echo. Stronger acoustic speech
 * evidence raises the bar.
 */
class PromptEchoDetector(transcriptionPrompt: String, terms: Iterable<String>? = null) {
    private val prompt: List<String> = tokens(transcriptionPrompt).take(400)
    private val promptSet: MutableSet<String> = HashSet(prompt)
    private val termsSet: Set<String> =
        (terms ?: listOf()).map { norm(it) }.filter { it.isNotEmpty() }.toSet()

    init {
        for (t in tokens("job interview professional business technology vocabulary")) promptSet.add(t)
    }

    fun check(transcript: String, evidence: SpeechEvidence = SpeechEvidence(0, 0, 0, 0, 0)): EchoResult {
        val words = tokens(transcript).take(240)
        if (words.isEmpty()) return EchoResult(false, "", 0.0, 0)
        val coverage = words.count { promptSet.contains(it) } / words.size.toDouble()
        val run = longestCommonRun(words, prompt)
        val items = transcript.split(',', ';').map { norm(it) }.filter { it.isNotEmpty() }
        val termItems = items.count { i -> termsSet.contains(i) || termsSet.contains(norm(termsPrefix.replace(i, ""))) }
        val marker = termsMarker.containsMatchIn(transcript)
        val startsLikePrompt = words.size >= 2 && words[0] == "job" && words[1] == "interview"
        val listLike = items.size >= 4 && termItems >= 3
        // Long, clearly voiced speech: only an unmistakable reproduction of the prompt counts.
        val strongSpeech = evidence.hasSpeech && evidence.voicedMs >= 1500

        var reason = ""
        if (marker && (termItems >= 2 || coverage >= 0.6)) reason = "terms list"
        else if (listLike && coverage >= 0.7) reason = "vocabulary list"
        else if (startsLikePrompt && coverage >= 0.8) reason = "prompt opening"
        else if (run >= 5 && run >= words.size * 0.7 && coverage >= 0.8) reason = "prompt wording"
        if (reason.isNotEmpty() && strongSpeech && !(marker && termItems >= 3) && !(run >= 8 && run >= words.size * 0.9))
            reason = ""
        return EchoResult(reason.isNotEmpty(), reason, dotNetRound2(coverage), run)
    }

    /**
     * Distinct words of the transcript that come from the interview's own vocabulary prompt (title, company, domain
     * terms), ignoring the prompt's generic frame. A recognizer that is given non-speech tends to produce a sentence
     * built from exactly this vocabulary ("What is your experience with FinTech products?").
     */
    fun contextOverlap(transcript: String): Int {
        val words = tokens(transcript).take(240).filter { w -> w.length > 1 && !frameWords.contains(w) }.distinct()
        var hits = words.count { promptSet.contains(it) }
        val norm = " " + norm(transcript) + " "
        for (t in termsSet) if (t.contains(' ') && norm.contains(" " + t + " ")) hits++ // multi-word terms
        return hits
    }

    companion object {
        val generic: PromptEchoDetector get() = PromptEchoDetector("Job interview. Professional business and technology vocabulary.")

        private val word: Regex = dotNetRegex("[\\p{L}\\p{N}]+")
        private val termsMarker: Regex = dotNetRegex("\\b(terms|vocabulary|keywords)\\s*:", ignoreCase = true)
        private val termsPrefix: Regex = dotNetRegex("^(terms|vocabulary|keywords)\\s*", ignoreCase = true)

        /** Words of the prompt's frame that say nothing about the interview's own subject. */
        private val frameWords: Set<String> = setOf(
            "job", "interview", "interviews", "role", "for", "a", "an", "at", "the", "of", "and", "or", "in", "on", "to", "with", "terms",
            "professional", "business", "technology", "vocabulary", "keywords", "is", "are", "you", "your",
        )

        private fun tokens(s: String): List<String> = word.findAll(s.lowercase()).map { it.value }.toList()
        private fun norm(s: String): String = tokens(s).joinToString(" ")

        private fun longestCommonRun(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            var prev = IntArray(b.size + 1)
            var cur = IntArray(b.size + 1)
            var best = 0
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                    if (cur[j] > best) best = cur[j]
                }
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return best
        }
    }
}
