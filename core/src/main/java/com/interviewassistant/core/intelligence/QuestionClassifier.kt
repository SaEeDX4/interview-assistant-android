package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.TargetContext
import com.interviewassistant.core.knowledge.regexEscape

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/QuestionClassifier.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class Classification(val category: String, val mode: AnswerMode, val isFollowUp: Boolean)

/** Fast rule-based classifier (microseconds). Mode decides truthfulness policy; category steers retrieval. */
object QuestionClassifier {
    private val categoryRules: List<Pair<String, Regex>> = listOf(
        "LEDGER" to "\\bledger|double[- ]entry|idempoten|duplicate (transaction|payment|payout)|transaction state|reversal|debit|credit\\b",
        "RECONCILIATION" to "reconcil",
        "MICAR" to "\\bmica|micar|markets in crypto",
        "COMPLIANCE" to "complian|regulat|legal|\\bkyc\\b|\\baml\\b|cysec|white ?paper",
        "VIP" to "\\bvip\\b|tier|status level|platinum|gold|silver",
        "REWARDS" to "reward|booster|cashback|loyalty|payout",
        "TOKENOMICS" to "tokenomic|token supply|supply|emission|inflation|distribution",
        "TOKENOMICS" to "token utility|utility token",
        "CRYPTO" to "crypto|blockchain|exchange|wallet|staking|on-?chain|erc-?20|defi|stablecoin",
        "AGILE_JIRA" to "\\bjira\\b|agile|scrum|sprint|kanban|definition of (done|ready)|refinement",
        "PRIORITIZATION" to "priorit|trade-?off|backlog|roadmap|technical debt|tech debt",
        "METRICS" to "metric|kpi|okr|north star|measure|a/b|experiment|retention|churn|activation|conversion|ltv",
        "STAKEHOLDER" to "stakeholder|disagree|conflict|c-?level|ceo|executive|leadership team|engineering (team|disagree)|marketing wants|push ?back",
        "LEADERSHIP" to "lead(ership)? style|manage (a )?team|mentor|motivat|hire|hiring",
        "PRODUCT_DISCOVERY" to "discovery|user research|customer research|interview users|validate",
        "PRODUCT_EXECUTION" to "user stor|acceptance criteria|spec|requirement|release|launch|uat",
        "PRODUCT_STRATEGY" to "strategy|vision|competitor|compet|market",
        "TECHNICAL_CONCEPT" to "\\bapi\\b|security|architecture|database|custod",
        "INTRODUCTION" to "about yourself|introduce|walk (me|us) through your|your background",
        "MOTIVATION" to "why (do you want|us|this|product owner|should we)|motivat",
        "BEHAVIOURAL" to "tell (me|us) about a time|give (me|us) an example|describe a situation|have you ever|biggest (achievement|failure)",
        "COMPANY_SPECIFIC" to "our company|our product",
    ).map { (cat, pattern) -> cat to dotNetRegex(pattern) }

    // Direct questions about personal history (Mode C candidates).
    private val personalHistory: Regex = dotNetRegex(
        "\\b(have you (ever |personally |actually |previously )?(built|owned|worked|designed|implemented|managed|led|run|done|launched|created|used)|did you (ever |personally )?(build|own|work|design|implement|manage|lead)|do you have (any |direct |hands-on |practical )?experience|what('s| is) your experience|your experience (with|in)|in your (previous|past|last) (role|job))",
        ignoreCase = true,
    )

    private val hypothetical: Regex = dotNetRegex(
        "\\b(how would you|what would you|how will you|how do you|how should|what is|what's|what are|explain|design|imagine|suppose|if you|walk me through how)\\b",
        ignoreCase = true,
    )

    private val verifiedCue: Regex = dotNetRegex(
        "\\b(tell (me|us) about (yourself|your|a time)|your (background|career|cv|resume|role at|time at|current)|give (me|us) an example from|why (do you want|should we hire)|your (strength|weakness|achievement))",
        ignoreCase = true,
    )

    private val followUpCue: Regex = dotNetRegex(
        "^(and |but |so |why\\??$|how so|what (do you mean|metric|kpi|exactly)|can you (elaborate|expand|give (me |us )?(a |an )?(concrete |specific |real |quick )?example|be more specific)|for example|such as|what happened|how would that|tell me more|go deeper|which one)",
        ignoreCase = true,
    )

    /**
     * Classify with target context and the candidate's confirmed evidence. A direct experience question becomes
     * VERIFIED only when confirmed evidence covers its topic, otherwise BRIDGE (honest bridge, then the approach).
     * Without evidence (no profile), direct experience questions are always BRIDGE — never assume history.
     */
    fun classify(question: String, ctx: TargetContext? = null, evidence: EvidenceIndex? = null): Classification {
        val q = question.trim()
        val lower = q.lowercase()
        var category = "GENERAL_BUSINESS"
        val mentionsEmployer = ctx != null && ctx.employers.any { e -> e.length > 1 && containsWord(lower, e.lowercase().split(' ')[0]) }
        val mentionsCompany = ctx != null && (listOf(ctx.companyName) + ctx.products).any { c -> c.length > 1 && containsWord(lower, c.lowercase()) }
        for ((cat, pattern) in categoryRules) {
            if (pattern.containsMatchIn(lower)) {
                category = cat
                break
            }
        }

        if (category == "GENERAL_BUSINESS" && mentionsCompany) category = "COMPANY_SPECIFIC"
        if (category == "GENERAL_BUSINESS" && mentionsEmployer) category = "RESUME_EXPERIENCE"
        // The same follow-up intent the gate and the turn detector use ("Walk me through the process.", "Why?") — so such an
        // ask gets the FOLLOW-UP prompt line and is not grounded on an unrelated prepared answer.
        val isFollowUp = followUpCue.containsMatchIn(lower) || TextNormalizer.wordCount(q) <= 4 && !lower.contains("yourself") ||
            FollowUpIntent.isStandaloneFollowUp(q) || UtteranceGate.isEllipticalAsk(q)
        if (isFollowUp && category == "GENERAL_BUSINESS") category = "FOLLOW_UP"

        val mode: AnswerMode
        if (personalHistory.containsMatchIn(lower) && !hasEvidence(q, evidence)) {
            mode = AnswerMode.Bridge
            category = if (category == "GENERAL_BUSINESS") "GAP_EXPERIENCE" else category
        } else if (verifiedCue.containsMatchIn(lower) || personalHistory.containsMatchIn(lower) || (mentionsEmployer && !hypothetical.containsMatchIn(lower))) mode = AnswerMode.Verified
        else if (hypothetical.containsMatchIn(lower)) mode = AnswerMode.Hypothetical
        else mode = AnswerMode.Hypothetical

        return Classification(category, mode, isFollowUp)
    }

    /** "Have you personally…", "Did you implement…", "Do you have experience with…" — asks about the candidate's own history. */
    fun isDirectExperienceQuestion(question: String): Boolean = personalHistory.containsMatchIn(question.lowercase())

    private fun hasEvidence(question: String, evidence: EvidenceIndex?): Boolean {
        if (evidence == null || evidence.count == 0) return false
        val topic = EvidenceIndex.topic(personalHistory.replace(question.lowercase(), " "))
        // "What did you do in your previous role?" has no specific topic: it is about the confirmed history itself.
        return topic.isEmpty() || evidence.supports(personalHistory.replace(question.lowercase(), " "))
    }

    private fun containsWord(haystack: String, word: String): Boolean =
        word.isNotEmpty() && dotNetRegex("(^|[^\\p{L}\\p{N}])" + regexEscape(word) + "($|[^\\p{L}\\p{N}])").containsMatchIn(haystack)
}
