package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.TermAlias
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/TextNormalizer.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Also hosts [dotNetRound2]: .NET `Math.Round(x, 2)` rounds midpoints to even (banker's rounding),
 * unlike `kotlin.math.round`; the planner and the echo detector rely on the .NET rule.
 */
internal fun dotNetRound2(x: Double): Double =
    BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

/** Normalises spoken transcripts: removes fillers, fixes domain-term misrecognitions, tokenises. */
object TextNormalizer {
    private val fillerRegex: Regex = dotNetRegex(
        "\\b(um+|uh+|erm+|hmm+|ah+|you know|i mean|kind of|sort of|basically|actually|like,|okay so|ok so|so,|well,|right,)\\s*",
        ignoreCase = true,
    )

    // Generic ASR confusions for widely used business/tech terms. Target-specific names come from TargetContext.Aliases.
    private val termFixes: List<Pair<Regex, String>> = listOf(
        // "MiCA" vs "MiCAR" is NOT normalized globally (both are correct names): the active target decides
        // (TargetContext.NormalizeTerms). Only clear misrecognitions are fixed here; personal names like "Mika" are left alone.
        dotNetRegex("\\b(mica r|mi car)\\b", ignoreCase = true) to "MiCAR",
        dotNetRegex("\\b(mi ca|mica regulation)\\b", ignoreCase = true) to "MiCA",
        dotNetRegex("\\b(cysec|cy sec|sci sec|psy sec)\\b", ignoreCase = true) to "CySEC",
        dotNetRegex("\\b(jira|gira|jeera)\\b", ignoreCase = true) to "Jira",
        dotNetRegex("\\b(item potency|idem potency|ident potency)\\b", ignoreCase = true) to "idempotency",
        dotNetRegex("\\b(token omics|tokonomics)\\b", ignoreCase = true) to "tokenomics",
        dotNetRegex("\\b(v i p)\\b", ignoreCase = true) to "VIP",
    )

    fun cleanTranscript(text: String, aliases: List<TermAlias>? = null): String {
        if (text.isBlank()) return ""
        var t = fillerRegex.replace(text, " ")
        for ((pattern, replacement) in termFixes) t = pattern.replace(t, replacement)
        if (aliases != null) for (a in aliases) if (a.variants.isNotEmpty()) t = a.regex.replace(t, a.canonical)
        t = dotNetRegex("^\\s*(so|and so|okay|ok|well|right|alright)\\b[\\s,]*", ignoreCase = true).replace(t, "")
        t = dotNetRegex("\\s+").replace(t, " ")
        t = t.trim()
        t = dotNetRegex("\\s+([,.?!])").replace(t, "\$1")
        t = dotNetRegex(",{2,}").replace(t, ",")
        t = t.trimStart(',', ' ')
        if (t.isNotEmpty()) t = t[0].uppercaseChar() + t.substring(1)
        return t
    }

    private val stopWords: Set<String> = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "be", "been", "do", "does", "did",
        "you", "your", "we", "our", "i", "me", "my", "it", "this", "that", "at", "by", "as", "from", "about", "us", "so", "can", "could", "would",
        "will", "should", "there", "their", "they", "them", "some", "any", "if", "then", "than", "just", "also", "have", "has", "had", "into",
        "please", "tell", "maybe", "bit", "little", "really", "very", "what", "how", "why", "when", "which", "who", "s", "d", "ll", "ve", "re",
    )

    private val tokenRegex: Regex = dotNetRegex("[a-z0-9][a-z0-9\\-]*")

    /** Lower-cased content tokens with light stemming. Question words are kept separately by the classifier. */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        for (m in tokenRegex.findAll(text.lowercase())) {
            val w = m.value.trim('-')
            if (w.length < 2 || stopWords.contains(w)) continue
            tokens.add(stem(w))
        }
        return tokens
    }

    fun stem(w: String): String {
        if (w.length > 5 && w.endsWith("ing")) return w.dropLast(3)
        if (w.length > 4 && w.endsWith("ies")) return w.dropLast(3) + "y"
        if (w.length > 4 && w.endsWith("ed")) return w.dropLast(2)
        if (w.length > 4 && w.endsWith("ise")) return w.dropLast(3) + "ize"
        if (w.length > 3 && w.endsWith('s') && !w.endsWith("ss")) return w.dropLast(1)
        return w
    }

    /** Stable fingerprint used for duplicate detection: sorted unique content tokens. */
    fun fingerprint(text: String): String {
        val tokens = tokenize(text).distinct().sorted()
        return tokens.joinToString(" ")
    }

    fun charTrigrams(text: String): Set<String> {
        val s = " " + dotNetRegex("[^a-z0-9 ]").replace(text.lowercase(), "") + " "
        val set = HashSet<String>()
        var i = 0
        while (i + 3 <= s.length) {
            set.add(s.substring(i, i + 3))
            i++
        }
        return set
    }

    fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** "?" in any script the recognizers write: ASCII, full-width (Chinese) and Arabic/Persian "؟". */
    fun isQuestionMark(c: Char): Boolean = c == '?' || c == '？' || c == '؟'

    /** The sentence ends with a question mark of any script (closing quotes and brackets ignored). */
    fun endsWithQuestionMark(text: String): Boolean {
        val t = text.trimEnd(' ', '"', '\'', '”', '’', '»', ')', '」', '』')
        return t.isNotEmpty() && isQuestionMark(t.last())
    }

    fun questionMarkCount(text: String): Int = text.count { isQuestionMark(it) }

    /** True when every letter is Latin script (word counts and the English question rules apply). */
    fun isLatinScript(text: String): Boolean =
        text.all { c -> !c.isLetter() || c <= 'ɏ' || c in 'Ḁ'..'ỿ' }

    private val wordRegex: Regex = dotNetRegex("[A-Za-z0-9’'\\-]+")

    fun wordCount(text: String): Int = wordRegex.findAll(text).count()

    fun stripBullet(line: String): String {
        val sb = StringBuilder(line.trim())
        while (sb.isNotEmpty() && (sb[0] == '•' || sb[0] == '-' || sb[0] == '*' || sb[0] == '·' || sb[0].isWhitespace())) sb.deleteCharAt(0)
        return sb.toString().trim().trim('"', '“', '”')
    }
}
