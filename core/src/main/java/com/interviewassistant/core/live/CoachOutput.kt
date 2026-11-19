package com.interviewassistant.core.live

import com.interviewassistant.core.ingestion.dotNetRegex

/**
 * Mirror of `InterviewAssistant.Core/Live/CoachOutput.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

enum class Presentation { Answer, Coach }

/** Coach Mode contract: exactly 3 keywords, one short structure (with arrows), optional one-line reminder. */
data class CoachOutput(val keywords: List<String>, val structure: String, val reminder: String?) {
    val keywordLine: String get() = keywords.joinToString(" · ")

    val isValid: Boolean
        get() = keywords.size == 3 && keywords.all { it.length in 1..32 } && structure.contains('→') && structure.length <= 90

    companion object {
        private val LabelLine = dotNetRegex("^(KEYWORDS|STRUCTURE|REMINDER)\\s*:\\s*(.+)$", ignoreCase = true)
        private val KeywordSplit = dotNetRegex("\\s*[·•|,،、]\\s*")
        private val ArrowForms = dotNetRegex("\\s*(->|=>|⇒|›)\\s*")
        private val HasLetter = dotNetRegex("\\p{Lu}|\\p{Ll}")

        /** Parses model output "KEYWORDS: A · B · C / STRUCTURE: x → y → z / REMINDER: …" (labels may be in any case). */
        fun parse(text: String): CoachOutput? {
            var kw: String? = null
            var st: String? = null
            var rem: String? = null
            for (raw in text.split('\n')) {
                val line = raw.trim().trimStart('-', '*', '•').trim()
                val m = LabelLine.find(line) ?: continue
                when (m.groupValues[1].uppercase()) {
                    "KEYWORDS" -> kw = m.groupValues[2]
                    "STRUCTURE" -> st = m.groupValues[2]
                    "REMINDER" -> rem = m.groupValues[2].trim()
                }
            }
            if (kw == null || st == null) return null
            val keys = KeywordSplit.split(kw).map { it.trim().trim('"') }.filter { it.isNotEmpty() }.take(3)
            if (keys.size < 3) return null
            val structure = ArrowForms.replace(st.trim(), " → ")
            val o = CoachOutput(
                keys.map { k -> if (HasLetter.containsMatchIn(k)) k.uppercase() else k },
                structure,
                if (rem.isNullOrBlank()) null else rem,
            )
            return if (o.isValid) o else null
        }

        fun fromPrepared(keywords: List<String>, structure: String): CoachOutput =
            CoachOutput(
                keywords.take(3) + List(maxOf(0, 3 - keywords.size)) { "RESULT" },
                if (structure.contains('→')) structure else "Direct answer → approach → result",
                null,
            )
    }
}
