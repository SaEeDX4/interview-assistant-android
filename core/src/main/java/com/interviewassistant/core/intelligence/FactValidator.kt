package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.TargetContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.regex.Pattern

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/FactValidator.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class ValidationIssue(val kind: String, val detail: String, val bulletIndex: Int)

data class ValidationReport(val issues: List<ValidationIssue>, val wordCount: Int, val bulletCount: Int) {
    val hasFactualIssues: Boolean
        get() = issues.any { i -> i.kind == "UNVERIFIED_NUMBER" || i.kind == "UNSUPPORTED_CLAIM" || i.kind == "UNKNOWN_EMPLOYER" }
}

/**
 * Guards against fabricated history: flags numbers not on the résumé, first-person past claims ("I built…",
 * "I've implemented…", "I led…") that no confirmed evidence item supports — in every mode, including VERIFIED —
 * and unknown employers. Also checks format (bullet count/length) for automated quality evaluation.
 */
class FactValidator(profile: CandidateProfile, context: TargetContext? = null, evidence: EvidenceIndex? = null) {
    private val numbers: Set<String>
    private val entities: List<String>
    private val domainNumbers: Set<String>
    private val evidence: EvidenceIndex = evidence ?: EvidenceIndex(profile, listOf<CandidateStory>())
    private val profileEntities: List<String>

    init {
        profileEntities = (profile.verifiedEntities + profile.experience.map { it.company }).filter { it.length > 1 }.distinct()
        numbers = profile.verifiedNumbers.map { normalizeNumber(it) }.toSet()
        val year = OffsetDateTime.now(ZoneOffset.UTC).year
        domainNumbers = (genericNumbers + listOf((year - 2).toString(), (year - 1).toString(), year.toString()))
            .toSet() + (context?.domainNumbers ?: listOf()).map { normalizeNumber(it) }
        entities = (profile.verifiedEntities +
            (if (context == null) listOf() else listOf(context.companyName) + context.products + context.extraEntities) +
            genericEntities).filter { it.isNotBlank() }
    }

    fun validate(bullets: List<String>, mode: AnswerMode, style: AnswerStyle = AnswerStyle.Balanced): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        var words = 0
        for (i in bullets.indices) {
            val b = bullets[i]
            val wc = TextNormalizer.wordCount(b)
            words += wc
            if (style == AnswerStyle.Balanced && (wc < 6 || wc > 30)) issues.add(ValidationIssue("BULLET_LENGTH", "$wc words", i))

            for (m in numberRegex.findAll(b)) {
                val n = normalizeNumber(m.value)
                if (n.isEmpty()) continue
                if (numbers.contains(n) || domainNumbers.contains(n)) continue
                // Accept a number if any verified number matches ignoring formatting (e.g., "60000" vs "60,000").
                if (numbers.any { v -> v.replace(",", "") == n }) continue
                issues.add(ValidationIssue("UNVERIFIED_NUMBER", m.value.trim(), i))
            }

            for (m in employerClaim.findAll(b)) {
                val name = m.groupValues[1]
                if (!entities.any { e -> name.startsWith(e, ignoreCase = true) || e.startsWith(name, ignoreCase = true) } && pastClaim.containsMatchIn(b))
                    issues.add(ValidationIssue("UNKNOWN_EMPLOYER", name, i))
            }

            for (claim in pastClaim.findAll(b)) {
                if (!isSupported(b, claim)) {
                    issues.add(ValidationIssue("UNSUPPORTED_CLAIM", claim.value, i))
                    break
                }
            }
        }
        if (style == AnswerStyle.Balanced && (bullets.size < 2 || bullets.size > 4)) issues.add(ValidationIssue("BULLET_COUNT", bullets.size.toString(), -1))
        if (style == AnswerStyle.Balanced && words > 85) issues.add(ValidationIssue("TOTAL_LENGTH", "$words words", -1))
        return ValidationReport(issues, words, bullets.size)
    }

    /**
     * A past claim is supported when a confirmed evidence item covers most of its clause ("I led the Arzif growth
     * team to 60,000 traders" vs. the confirmed Arzif achievement). Generic words like "Engineering" never count.
     */
    private fun isSupported(bullet: String, claim: MatchResult): Boolean {
        val rest = bullet.substring(claim.range.first + claim.value.length)
        val end = clauseEnd.find(rest)
        val clause = if (end != null) rest.substring(0, end.range.first) else rest
        // The clause may name the employer before the verb ("At Arzif, I led…"). Only the SAME clause counts: an
        // earlier clause such as the honest bridge ("I haven't owned that ledger, but I led…") must not dilute the claim.
        val lead = leadIn(bullet.substring(0, claim.range.first))
        if (EvidenceIndex.topic(clause).isEmpty()) return profileEntities.any { e -> lead.contains(e, ignoreCase = true) }
        return evidence.supports(clause, 0.5) || (EvidenceIndex.topic(lead).isNotEmpty() && evidence.supports(lead + " " + clause, 0.5))
    }

    /** True when the bullet contains a first-person past claim no confirmed evidence supports. */
    fun hasUnsupportedClaim(bullet: String): Boolean = pastClaim.findAll(bullet).any { m -> !isSupported(bullet, m) }

    companion object {
        private val numberRegex: Regex = dotNetRegex("\\b\\d[\\d,\\.]*\\s*(%|percent)?|\\b(twenty-two|twenty-five|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|eighteen|twenty|thirty|fifty|sixty|hundred)(\\s|-)?(thousand|percent)?\\b", ignoreCase = true)

        // Past-tense ownership claims. Present habits ("I run a weekly review") are not history; "I've run…" is.
        private val pastClaim: Regex = dotNetRegex(
            "\\bI(?:(?:'ve|’ve|\\s+have)\\s+(built|implemented|led|managed|delivered|launched|designed|owned|created|developed|run|been responsible for|been in charge of|set up|introduced|driven|scaled|overseen|headed|spearheaded|architected|shipped|rolled out|established)" +
                "|\\s+(built|implemented|led|managed|delivered|launched|designed|owned|created|developed|ran|was responsible for|was in charge of|set up|introduced|drove|scaled|oversaw|headed|spearheaded|architected|shipped|rolled out|established)" +
                "|\\s+did\\s+(build|implement|lead|manage|deliver|launch|design|own|create|develop|run|set up|introduce|drive|scale|oversee|head|architect|ship|roll out|establish))\\b",
            ignoreCase = true,
        )
        private val clauseEnd: Regex = dotNetRegex("[.;!?]|,\\s+(and|but|which|so)\\b|\\s+—\\s+")
        private val employerClaim: Regex = dotNetRegex("\\b(?:at|with|while at|when I was at)\\s+([A-Z][A-Za-z]+(?:\\s[A-Z][A-Za-z]+)?)")

        private val wordNumbers: Map<String, String> = mapOf(
            "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10", "twelve" to "12", "fifteen" to "15", "eighteen" to "18",
            "twenty" to "20", "twenty-two" to "22", "twenty-five" to "25", "thirty" to "30", "fifty" to "50", "sixty" to "60", "hundred" to "100",
        )

        // Generic numbers that are not candidate claims (30/60/90-day plans, small counts, recent years).
        private val genericNumbers: List<String> = listOf("30", "60", "90", "1", "2", "3", "4", "5", "6", "20", "100")
        private val genericEntities: List<String> = listOf("Engineering", "Compliance", "Legal", "Finance", "Marketing", "CRM", "Design", "Product", "Security", "Sales", "Operations", "Support", "Data", "Jira", "Scrum", "Agile", "QA", "MiCA")

        // The C#'s ClauseBoundary is the one Singleline regex in the file; the JVM needs DOTALL for the same
        // meaning, so this pattern is compiled directly instead of through the shared helper.
        private val clauseBoundary: Regex = Regex(
            Pattern.compile(
                "(.*)(\\bbut\\b|\\band\\b|\\bhowever\\b|\\balthough\\b|\\bwhile\\b|\\bthough\\b|[.;!?]|\\s[—–-]\\s)",
                Pattern.UNICODE_CHARACTER_CLASS or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.DOTALL,
            ),
        )

        private fun leadIn(before: String): String {
            val m = clauseBoundary.find(before) ?: return before
            return before.substring(m.groupValues[1].length + m.groupValues[2].length)
        }

        fun normalizeNumber(raw: String): String {
            var s = raw.trim().trim('-').lowercase().replace("percent", "").replace("%", "").replace("+", "").trim()
            val parts = s.split(' ', '-').filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && wordNumbers.containsKey(s)) return wordNumbers.getValue(s)
            if (parts.size == 2 && wordNumbers.containsKey(parts[0]) && parts[1] == "thousand") return wordNumbers.getValue(parts[0]) + "000"
            if (parts.isNotEmpty() && parts.last() == "years") s = parts[0]
            wordNumbers[s]?.let { return it }
            s = s.replace(",", "").replace(" ", "")
            if (s.endsWith("thousand")) s = s.replace("thousand", "000")
            return s.trimEnd('.')
        }

        /** Rewrites or drops bullets with factual issues (used on LLM output before it is shown as final). */
        fun softenClaim(bullet: String): String {
            // "I built X" / "I've built X" -> "I'd build X": the same idea as a plan, never as history.
            return pastClaim.replace(bullet) { m ->
                val verb = (m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]!!.value).lowercase()
                val baseVerb = when (verb) {
                    "built" -> "build"
                    "implemented" -> "implement"
                    "led" -> "lead"
                    "managed" -> "manage"
                    "delivered" -> "deliver"
                    "launched" -> "launch"
                    "designed" -> "design"
                    "owned" -> "own"
                    "created" -> "create"
                    "developed" -> "develop"
                    "ran", "run" -> "run"
                    "was responsible for", "been responsible for" -> "be responsible for"
                    "was in charge of", "been in charge of" -> "be in charge of"
                    "set up" -> "set up"
                    "introduced" -> "introduce"
                    "drove", "driven" -> "drive"
                    "scaled" -> "scale"
                    "oversaw", "overseen" -> "oversee"
                    "headed" -> "head"
                    "spearheaded" -> "spearhead"
                    "architected" -> "architect"
                    "shipped" -> "ship"
                    "rolled out" -> "roll out"
                    "established" -> "establish"
                    else -> verb
                }
                "I'd " + baseVerb
            }
        }
    }
}
