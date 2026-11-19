package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.KnowledgeBase

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/AnswerPlanner.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port — hosts `AnswerPlan`/`AnswerPlanner` and `QualityReport`/`AnswerQualityGate`,
 * exactly the types the C# file hosts.
 */

/** Internal confidence in the answer we are about to show. LOW is never presented as certainty. */
enum class AnswerConfidence { Low, Medium, High }

/** Personal / professional-identity topics answered only from the confirmed profile. */
enum class PersonalTopic { None, Hobbies, Motivation, WorkStyle, Team, Strength, Weakness, WhyLooking, CareerGoals, Location, Remote, Availability, Leadership, Communication }

/**
 * Structured decision metadata for one answer (no chain-of-thought): what is being asked, what evidence may be used,
 * what structure fits, which risks apply and how confident we are. Built locally in microseconds — the normal
 * answer path never adds a model round-trip.
 */
data class AnswerPlan(
    val questionType: String,
    val coreIntent: String,
    val hypothetical: Boolean,
    val directExperience: Boolean,
    val knownGap: Boolean,
    val evidenceStoryIds: List<String>,
    val evidenceCompanies: List<String>,
    val metrics: List<String>,
    val structure: String,
    val riskFlags: List<String>,
    val confidence: AnswerConfidence,
    val personal: PersonalTopic,
    val personalFacts: List<String>,
) {
    val personalWithoutData: Boolean get() = personal != PersonalTopic.None && personalFacts.isEmpty()

    /** Compact plan section for the prompt (instructions, not reasoning). */
    fun toPromptSection(): String {
        val sb = StringBuilder("ANSWER PLAN: ")
        sb.append("intent=$coreIntent; structure=$structure; confidence=${confidence.name.uppercase()}")
        if (evidenceCompanies.isNotEmpty()) sb.append("; relevant confirmed experience at: ${evidenceCompanies.joinToString(", ")}")
        if (metrics.isNotEmpty()) sb.append("; usable verified numbers: ${metrics.take(6).joinToString(", ")}")
        sb.appendLine(".")
        if (knownGap)
            sb.appendLine("The exact experience is NOT confirmed: start with a short honest bridge, then cite the closest CONFIRMED experience as past fact (it is true — keep it in past tense), then the approach as \"I'd…\".")
        else if (hypothetical && !directExperience)
            sb.appendLine("Hypothetical/approach question: answer confidently as \"I'd…\" / \"My approach would be…\" — no disclaimer, no invented history.")
        if (confidence == AnswerConfidence.Low)
            sb.appendLine("Confidence is LOW: use measured wording, stay general where evidence is thin, never invent specifics.")
        if (personal != PersonalTopic.None)
            sb.appendLine(
                if (personalFacts.isNotEmpty())
                    "PERSONAL ANSWER — use ONLY these confirmed facts (do not add others): " + personalFacts.joinToString(" | ") +
                        ". Rephrase them naturally in first person and stop. Do NOT add reasons, feelings, benefits, personality traits, skills it " +
                        "built, how often, where, since when, or a link to the role — none of that was confirmed. Short is correct here."
                else
                    "PERSONAL ANSWER — the profile has NO confirmed facts on this: do not invent any (no hobbies, preferences or reasons).",
            )
        return sb.toString().trimEnd()
    }
}

object AnswerPlanner {
    private class PersonalRule(val topic: PersonalTopic, val categories: Array<String>, val pattern: Regex)

    private val personalRules: List<PersonalRule> = listOf(
        PersonalRule(PersonalTopic.Hobbies, arrayOf("hobby", "outside_work", "learning"), dotNetRegex("\\b(hobb(y|ies)|free time|spare time|outside (of )?work|weekends?|for fun|pastimes?|interests outside|do you (do|enjoy) (when|after|outside))\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Motivation, arrayOf("motivation"), dotNetRegex("\\b(what motivates you|what drives you|motivat(es|ion) you|what gets you (up|excited)|what energi[sz]es you)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Team, arrayOf("team"), dotNetRegex("\\b(what (kind|type) of team|team (do you|you) (prefer|work best)|ideal team|work best (with|in))\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.WorkStyle, arrayOf("work_style"), dotNetRegex("\\b(work(ing)? style|how do you (like|prefer) to work|preferred way of working|work environment (do you|you) prefer)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Strength, arrayOf("strength"), dotNetRegex("\\b(greatest|biggest|main|key|top) strengths?\\b|\\bwhat are your strengths\\b|\\bwhat('s| is) your (greatest |biggest )?strength\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Weakness, arrayOf("weakness"), dotNetRegex("\\bweakness(es)?\\b|\\barea(s)? (for|of) (improvement|development)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.WhyLooking, arrayOf("why_looking"), dotNetRegex("\\b(why are you (looking|leaving|changing)|why (do|did) you (want to )?leave|why (now|move on)|reason for (leaving|looking|change))\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.CareerGoals, arrayOf("career_goal", "ideal_role"), dotNetRegex("\\b(career goals?|where do you see yourself|in (five|5|three|3|ten|10) years|long-?term goals?|next step in your career)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Location, arrayOf("location"), dotNetRegex("\\b(where are you (based|located|from)|which time ?zone)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Remote, arrayOf("remote"), dotNetRegex("\\b(remote|hybrid|relocat|on-?site|in the office)\\b.*\\?|\\b(open to|willing to) relocate\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Availability, arrayOf("availability"), dotNetRegex("\\b(notice period|when (can|could) you start|availability to start|earliest start)\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Leadership, arrayOf("leadership_style"), dotNetRegex("\\b(leadership style|how (do|would) you (lead|manage) (a |your )?team|describe yourself as a (leader|manager))\\b", ignoreCase = true)),
        PersonalRule(PersonalTopic.Communication, arrayOf("communication_style"), dotNetRegex("\\b(communication style|how do you communicate)\\b", ignoreCase = true)),
    )

    fun detectPersonal(question: String): Pair<PersonalTopic, Array<String>> {
        for (rule in personalRules) if (rule.pattern.containsMatchIn(question)) return rule.topic to rule.categories
        return PersonalTopic.None to emptyArray()
    }

    fun plan(q: ExtractedQuestion, cls: Classification, match: MatchResult, ctx: RetrievedContext, kb: KnowledgeBase, evidence: EvidenceIndex): AnswerPlan {
        val core = q.coreQuestion
        val direct = QuestionClassifier.isDirectExperienceQuestion(core)
        val gap = cls.mode == AnswerMode.Bridge
        val hypothetical = cls.mode == AnswerMode.Hypothetical
        val (personal, cats) = detectPersonal(core)
        val personalFacts = if (personal == PersonalTopic.None) listOf() else kb.profile.personal.filter { p -> cats.contains(p.category) }.map { p -> p.text }

        val stories = ctx.stories.toList()
        val metrics = stories.flatMap { it.verifiedNumbers }.filter { it.isNotEmpty() }.distinct().take(8)
        val companies = stories.map { it.company }.filter { it.length > 1 }.distinctBy { it.lowercase() }.take(3)

        val structure = if (personal != PersonalTopic.None) {
            if (personalFacts.isNotEmpty()) "the confirmed facts, rephrased in first person — nothing added" else "the candidate's own words (no facts confirmed)"
        } else if (gap) "honest bridge → closest confirmed experience (past tense) → approach (I'd…)"
        else if (cls.mode == AnswerMode.Verified) {
            if (cls.category == "BEHAVIOURAL") "situation → what I did → result" else "direct answer from confirmed experience → how → result"
        } else if (q.subQuestions.size >= 2) "answer each part in order: " + q.subQuestions.joinToString(" / ") { short(it, 6) }
        else "direct answer → approach → how to measure"

        val risks = mutableListOf<String>()
        if (direct && gap) risks.add("direct-experience-without-evidence")
        if (personal != PersonalTopic.None && personalFacts.isEmpty()) risks.add("personal-question-without-profile-data")
        if (q.confidence < 0.7) risks.add("unclear-ask")
        if (q.hasContext) risks.add("long-context")
        if (cls.isFollowUp) risks.add("follow-up")
        if (kb.profile.boundaries.isNotEmpty()) risks.add("user-boundaries")

        val confidence: AnswerConfidence
        if (match.confidence == MatchConfidence.High) confidence = AnswerConfidence.High
        else if (q.confidence < 0.7 || (personal != PersonalTopic.None && personalFacts.isEmpty())) confidence = AnswerConfidence.Low
        else if (gap) confidence = if (companies.isNotEmpty()) AnswerConfidence.Medium else AnswerConfidence.Low
        else if (cls.mode == AnswerMode.Verified) confidence = if (stories.isNotEmpty() && evidence.count > 0) AnswerConfidence.High else AnswerConfidence.Low
        else confidence = if (q.confidence >= 0.85) AnswerConfidence.High else AnswerConfidence.Medium

        val intent = cls.category.lowercase().replace('_', ' ') + (if (personal != PersonalTopic.None) " / personal: " + personal.name.lowercase() else "")
        return AnswerPlan(cls.category, intent, hypothetical, direct, gap, stories.map { it.id }, companies, metrics, structure, risks,
            confidence, personal, personalFacts)
    }

    private fun short(s: String, words: Int): String {
        val w = s.split(' ').filter { it.isNotEmpty() }
        return if (w.size <= words) s.trimEnd('?', '.') else w.take(words).joinToString(" ") + "…"
    }

    /**
     * Deterministic guidance when a personal question has no confirmed data: nothing is invented, the candidate
     * answers from their own life. Shown instantly (no model call). Written in the answer language.
     */
    fun personalGuidance(topic: PersonalTopic, language: String): List<String> {
        val t = personalTexts[language] ?: personalTexts.getValue("en")
        return listOf(t[0], if (topic == PersonalTopic.Hobbies) t[1] else t[2], t[3])
    }

    private val personalTexts: Map<String, Array<String>> = mapOf(
        "en" to arrayOf("Answer this one from your own life — your profile has no confirmed answer, so nothing is suggested for you.",
            "Keep it short: one real interest, what you enjoy about it, and one quality it reflects (curiosity, discipline, teamwork).",
            "Keep it short and genuine: one honest point, a brief reason, and how it connects to this role.",
            "Tip: add this to Profiles → Interview profile so future answers can use it."),
        "es" to arrayOf("Responda desde su propia experiencia: su perfil no tiene una respuesta confirmada, así que no se sugiere nada inventado.",
            "Sea breve: un interés real, qué le gusta de él y una cualidad que refleja (curiosidad, disciplina, trabajo en equipo).",
            "Sea breve y auténtico: un punto honesto, una razón breve y su relación con este puesto.",
            "Consejo: añádalo en Perfiles → Perfil de entrevista para usarlo en futuras respuestas."),
        "fr" to arrayOf("Répondez à partir de votre propre vie : votre profil ne contient aucune réponse confirmée, rien n'est donc inventé pour vous.",
            "Restez bref : un vrai centre d'intérêt, ce qui vous plaît, et une qualité qu'il reflète (curiosité, discipline, esprit d'équipe).",
            "Restez bref et sincère : un point honnête, une courte raison et le lien avec ce poste.",
            "Astuce : ajoutez-le dans Profils → Profil d'entretien pour les prochaines réponses."),
        "de" to arrayOf("Beantworten Sie das aus Ihrem eigenen Leben – Ihr Profil enthält keine bestätigte Antwort, daher wird nichts erfunden.",
            "Kurz halten: ein echtes Interesse, was Ihnen daran gefällt, und eine Eigenschaft, die es zeigt (Neugier, Disziplin, Teamgeist).",
            "Kurz und ehrlich: ein ehrlicher Punkt, ein kurzer Grund und der Bezug zu dieser Rolle.",
            "Tipp: Ergänzen Sie dies unter Profile → Interviewprofil, damit künftige Antworten es nutzen können."),
        "pt" to arrayOf("Responda com base na sua própria vida — o seu perfil não tem uma resposta confirmada, por isso nada é inventado.",
            "Seja breve: um interesse real, o que gosta nele e uma qualidade que ele mostra (curiosidade, disciplina, trabalho em equipa).",
            "Seja breve e autêntico: um ponto honesto, uma razão curta e a ligação com esta função.",
            "Dica: adicione isto em Perfis → Perfil de entrevista para respostas futuras."),
        "it" to arrayOf("Rispondi partendo dalla tua vita — il tuo profilo non ha una risposta confermata, quindi non viene inventato nulla.",
            "Sii breve: un interesse reale, cosa ti piace e una qualità che rivela (curiosità, disciplina, lavoro di squadra).",
            "Sii breve e autentico: un punto onesto, un breve motivo e il collegamento con questo ruolo.",
            "Suggerimento: aggiungilo in Profili → Profilo colloquio per le risposte future."),
        "ar" to arrayOf("أجب عن هذا من حياتك الخاصة — لا يحتوي ملفك على إجابة مؤكدة، لذلك لا يُقترح عليك أي شيء مختلق.",
            "اجعلها قصيرة: اهتمام حقيقي واحد، وما الذي تستمتع به فيه، وصفة يعكسها (الفضول، الانضباط، العمل الجماعي).",
            "اجعلها قصيرة وصادقة: نقطة صادقة واحدة، وسبب موجز، وصلتها بهذا الدور.",
            "نصيحة: أضف هذا في الملفات الشخصية ← ملف المقابلة لتستخدمه الإجابات القادمة."),
        "fa" to arrayOf("به این سؤال از زندگی خودتان پاسخ دهید — پروفایل شما پاسخ تأییدشده‌ای ندارد، پس چیزی برایتان ساخته نمی‌شود.",
            "کوتاه بگویید: یک علاقهٔ واقعی، اینکه چه چیزی از آن لذت می‌برید، و یک ویژگی که نشان می‌دهد (کنجکاوی، نظم، کار تیمی).",
            "کوتاه و صادقانه: یک نکتهٔ صادقانه، یک دلیل کوتاه و ارتباط آن با این نقش.",
            "نکته: این را در پروفایل‌ها ← پروفایل مصاحبه اضافه کنید تا پاسخ‌های بعدی از آن استفاده کنند."),
        "zh" to arrayOf("请根据您自己的生活回答——您的资料中没有已确认的答案，因此不会为您编造内容。",
            "简短作答：一个真实的兴趣、您喜欢它的原因，以及它体现的一种品质（好奇心、自律、团队合作）。",
            "简短而真诚：一个真实的要点、一个简短的理由，以及它与该职位的联系。",
            "提示：在“个人资料 → 面试资料”中补充此项，以便今后的回答使用。"),
        "hi" to arrayOf("इसका जवाब अपने जीवन से दें — आपकी प्रोफ़ाइल में कोई पुष्टि किया गया उत्तर नहीं है, इसलिए आपके लिए कुछ भी गढ़ा नहीं जाता।",
            "संक्षेप में कहें: एक असली रुचि, आपको उसमें क्या पसंद है, और वह कौन-सा गुण दिखाती है (जिज्ञासा, अनुशासन, टीमवर्क)।",
            "संक्षिप्त और सच्चा रखें: एक ईमानदार बात, एक छोटा कारण, और इस भूमिका से उसका संबंध।",
            "सुझाव: इसे प्रोफ़ाइल → इंटरव्यू प्रोफ़ाइल में जोड़ें ताकि आगे के उत्तर इसका उपयोग कर सकें।"),
    )
}

data class QualityReport(val flags: List<String>, val relevance: Double, val words: Int)

/**
 * Fast, deterministic answer checks (no model call): redundant bullets are dropped while streaming; relevance,
 * generic filler, length, repetition of a recent answer and ignored confirmed evidence are flagged for diagnostics
 * and the report. Unsupported claims are handled by [FactValidator].
 */
object AnswerQualityGate {
    private val generic: Regex = dotNetRegex("\\b(it depends|communication is key|at the end of the day|in today's fast-paced|think outside the box|synergy|best practices in general|i'm a team player|i am a team player|hard-?working and passionate)\\b", ignoreCase = true)

    /** True when the new bullet mostly repeats an earlier bullet of the same answer. */
    fun isRedundant(bullet: String, earlier: Iterable<String>): Boolean {
        val a = TextNormalizer.tokenize(bullet).toSet()
        if (a.size < 3) return false
        return earlier.any { e -> TextNormalizer.jaccard(a, TextNormalizer.tokenize(e).toSet()) >= 0.6 }
    }

    fun assess(q: ExtractedQuestion, plan: AnswerPlan, bullets: List<String>, recentAnswers: Iterable<List<String>>, style: AnswerStyle): QualityReport {
        val flags = mutableListOf<String>()
        if (bullets.isEmpty()) return QualityReport(flags, 0.0, 0)
        val answerTokens = bullets.flatMap { TextNormalizer.tokenize(it) }.toSet()
        val questionTokens = TextNormalizer.tokenize(q.coreQuestion + " " + q.constraints.joinToString(" ")).toSet()
        val relevance = if (questionTokens.isEmpty()) 1.0 else questionTokens.count { answerTokens.contains(it) } / questionTokens.size.toDouble()
        if (questionTokens.size >= 3 && relevance == 0.0) flags.add("QUALITY_OFF_TOPIC: answer shares no content words with the question")
        if (bullets.any { b -> generic.containsMatchIn(b) }) flags.add("QUALITY_GENERIC: filler phrase")
        val words = bullets.sumOf { TextNormalizer.wordCount(it) }
        if (style == AnswerStyle.Balanced && words > 85) flags.add("QUALITY_TOO_LONG: $words words")
        for (prev in recentAnswers) {
            val p = prev.flatMap { TextNormalizer.tokenize(it) }.toSet()
            if (p.size >= 8 && TextNormalizer.jaccard(p, answerTokens) >= 0.55) {
                flags.add("QUALITY_REPEATS_RECENT: very similar to a recent answer")
                break
            }
        }
        if (plan.evidenceCompanies.isNotEmpty() && (plan.knownGap || plan.directExperience || plan.questionType == "BEHAVIOURAL") &&
            !plan.evidenceCompanies.any { c -> bullets.any { b -> b.contains(c.split(' ')[0], ignoreCase = true) } })
            flags.add("QUALITY_IGNORED_EVIDENCE: confirmed experience available but not used")
        return QualityReport(flags, dotNetRound2(relevance), words)
    }
}
