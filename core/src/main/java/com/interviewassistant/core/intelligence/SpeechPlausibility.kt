package com.interviewassistant.core.intelligence

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.ingestion.dotNetRegex
import kotlin.math.max

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/SpeechPlausibility.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** How well the audio supports a transcript (structured, logged as metadata only — never the text). */
enum class AcousticSupport {
    /** No audio was analysed for this turn (tests, capture not running): acoustic layers abstain. */
    Unknown,

    /** The audio is not a human voice (beep, chime, alert tone, click, knock, instrumental music). */
    None,

    /** A voice was heard, but far too little of it for the words in the transcript. */
    Implausible,

    /** A voice was heard and could have said the transcript, with little margin. */
    Weak,

    /** Clearly enough voiced speech for the transcript. */
    Strong,
}

/**
 * Consistency between a transcript and the audio it came from. A recognizer given a short non-speech sound can return a
 * perfectly formed, context-flavoured interview question; what it cannot do is make a 0.6 s chime contain the ~12
 * syllables of "What is your experience with FinTech products?". Every syllable a person says has a voiced vowel nucleus
 * (typically 80–200 ms; eSpeak fixtures ≈ 140 ms), so the voiced time bounds the syllables a transcript may contain:
 *  - at least [minVoicedMsPerSyllable] ms voiced per syllable (≈ 3× faster than fast speech), and no more than
 *    [maxSyllablesPerActiveSecond] syllables per second of sound — otherwise the transcript is impossible
 *    for the audio ([AcousticSupport.Implausible]);
 *  - below [strongVoicedMsPerSyllable] ms per syllable (after the same slack) the support is only
 *    [AcousticSupport.Weak].
 * The syllable count is a deliberate under-estimate (vowel groups for Latin scripts; one per word or per CJK character
 * otherwise), so real speech is never judged impossible because of counting.
 */
object SpeechPlausibility {
    const val minVoicedMsPerSyllable = 35
    const val strongVoicedMsPerSyllable = 70
    const val maxSyllablesPerActiveSecond = 9.0

    /** Slack for the start/end of an utterance clipped by the recognizer's segmentation. */
    const val slackSyllables = 2

    fun assess(transcript: String, evidence: SpeechEvidence): AcousticSupport {
        if (evidence.unknown) return AcousticSupport.Unknown
        if (!evidence.voiceLike) return AcousticSupport.None
        if (evidence.incomplete) return AcousticSupport.Unknown // a voice was heard, but not the whole window
        val syllables = syllables(transcript)
        val budget = max(0, syllables - slackSyllables)
        if (evidence.voicedMs < budget * minVoicedMsPerSyllable) return AcousticSupport.Implausible
        if (budget > maxSyllablesPerActiveSecond * evidence.activeMs / 1000.0) return AcousticSupport.Implausible
        // The same slack as above: a fast talker, or an utterance whose edge fell outside the window, is still "Strong".
        return if (evidence.voicedMs >= budget * strongVoicedMsPerSyllable) AcousticSupport.Strong else AcousticSupport.Weak
    }

    private val word: Regex = dotNetRegex("[\\p{L}\\p{N}]+")
    private val vowelGroup: Regex = dotNetRegex("[aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüýÿœ]+", ignoreCase = true)

    /** Lower-bound syllable estimate (never over-counts real speech). */
    fun syllables(text: String): Int {
        var total = 0
        for (m in word.findAll(text)) {
            val w = m.value
            val cjk = w.count { c -> c in '぀'..'鿿' || c in '가'..'힯' }
            if (cjk > 0) {
                total += cjk
                continue
            }
            if (w.all { c -> c.code < 0x250 }) { // Latin script: vowel groups, silent final "e" not counted
                var groups = vowelGroup.findAll(w).count()
                val lw = w.lowercase()
                if (groups > 1 && lw.endsWith('e') && !lw.endsWith("le")) groups-- // make, use
                else if (groups > 1 && lw.length > 3 && lw.endsWith("es") && !"sxzcg".contains(lw[lw.length - 3]) && !lw.endsWith("hes")) groups-- // makes, times (not boxes, wishes)
                else if (groups > 1 && lw.length > 3 && lw.endsWith("ed") && !"td".contains(lw[lw.length - 3])) groups-- // used, asked (not wanted, ended)
                total += max(1, groups)
            } else total += 1 // other scripts: one per word (an under-estimate)
        }
        return total
    }
}
