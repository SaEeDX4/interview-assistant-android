package com.interviewassistant.core.preparation

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.domain.RequirementMatch
import com.interviewassistant.core.domain.Story
import com.interviewassistant.core.ingestion.JobAnalysis
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.BankQuestion

/**
 * Mirror of `InterviewAssistant.Core/Preparation/QuestionFactory.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/**
 * Generates the per-target question bank with prepared answers, Coach keywords and structures.
 * Verified answers are assembled ONLY from confirmed facts (verbatim content, first-person phrasing);
 * hypothetical answers come from the company-neutral expert library or approach templates.
 * Confidence < 0.7 marks template answers that the live engine uses as AI reference instead of showing instantly.
 */
internal class QuestionFactory(
    private val p: CandidateProfileRecord,
    private val t: InterviewTarget,
    private val jd: JobAnalysis,
    private val stories: List<Story>,
    private val matches: List<RequirementMatch>,
    private val gaps: List<String>,
    confirmed: List<ProfileFact>,
) {
    private val facts: Map<String, ProfileFact> = confirmed.associateBy { it.id }
    private val out = mutableListOf<BankQuestion>()
    private val storyUse = mutableMapOf<String, Int>()

    private val role: String get() = t.jobTitle.ifEmpty { "this role" }
    private val company: String get() = t.company.ifEmpty { "your company" }
    private val latestRole: ProfileFact?
        get() = facts.values.filter { it.kind == FactKind.Role }.maxByOrNull { startYear(it.period) }

    fun build(library: List<GenericLibraryItem>): List<BankQuestion> {
        core()
        behavioural()
        perRole()
        requirements()
        responsibilities()
        for (l in library) {
            add(l.category, AnswerMode.Hypothetical, l.canonicalQuestion, l.semanticVariants, l.shortBullets, 0.85, full = l.optionalFullAnswer, follow = l.followUpQuestions)
        }

        // Adaptive size: 50–150 depending on role complexity; drop lowest-confidence template items first.
        var ordered = out.groupBy { TextNormalizer.fingerprint(it.canonicalQuestion) }.values.map { it.first() }
        if (ordered.size > 150) ordered = ordered.sortedByDescending { it.confidence }.take(150)
        return ordered.mapIndexed { i, q -> renumber(q, i + 1) }
    }

    // ---------------- sections ----------------

    private fun core() {
        val yrs = ProfileService.yearsOfExperience(p)
        val latest = latestRole
        val top = topMatchedAchievements(3)
        val intro = mutableListOf<String>()
        intro.add(
            if (p.headline.isNotEmpty()) {
                "I'm ${article(p.headline)} ${lower(p.headline)}${if (yrs > 0) " with over $yrs years of experience" else ""}."
            } else if (yrs > 0) {
                "I have over $yrs years of professional experience."
            } else {
                "I bring a strong mix of practical experience and fast learning."
            },
        )
        if (latest != null) {
            intro.add("Most recently I've been ${article(latest.title)} ${latest.title} at ${latest.company}${if (top.isNotEmpty() && top[0].parentId == latest.id) ", where " + clause(top[0]) else ""}.")
        }
        if (top.isNotEmpty() && (latest == null || top[0].parentId != latest.id)) intro.add(speak(top[0]))
        intro.add("${cap(role)} is a natural next step because it builds directly on ${strengthPhrase()}.")
        add(
            "INTRODUCTION", AnswerMode.Verified, "Tell me about yourself.",
            listOf("Walk me through your background", "Introduce yourself", "Tell us a bit about your career", "Walk me through your CV"),
            intro.take(4), 0.8,
            coach = listOf("PRESENT", "PROOF", "WHY THIS ROLE"), structure = "Who I am now → strongest proof → why this role",
            storyIds = top.map { "s" + it.id },
        )

        add(
            "MOTIVATION", AnswerMode.Hypothetical, "Why do you want to work at $company?",
            listOf("Why $company", "Why this company", "What do you know about us", "Why do you want this job"),
            listOf(
                if (jd.about.isNotEmpty()) "What attracts me is ${lower(keyPhraseShort(jd.about[0], 14))}." else "What attracts me is the chance to contribute to $company's growth in this specific area.",
                "The $role role connects directly with what I do best: ${strengthPhrase()}.",
                "I'd like to bring that experience into a team where I can have clear, measurable impact.",
            ),
            0.6, coach = listOf(upper(company), "FIT", "IMPACT"), structure = "What attracts me → why I fit → impact I'd make",
        )

        add(
            "MOTIVATION", AnswerMode.Verified, "Why should we hire you?",
            listOf("What makes you the right candidate", "Why you", "What would you bring to this role"),
            top.take(2).map { speak(it) } + "I'd bring that combination of results and ${lower(strengthPhrase())} straight into the $role role.",
            if (top.size >= 2) 0.8 else 0.6,
            coach = listOf("RESULTS", "FIT", "VALUE"), structure = "Proof 1 → proof 2 → value for you",
            storyIds = top.take(2).map { "s" + it.id },
        )

        val skills = facts.values.filter { it.kind == FactKind.Skill }.map { it.text }.take(3)
        if (skills.isNotEmpty() && top.isNotEmpty()) {
            add(
                "GENERAL_BUSINESS", AnswerMode.Verified, "What are your strengths?",
                listOf("Your main strengths", "What are you good at"),
                listOf(
                    "My core strengths are ${join(skills.map { lower(it) })}.",
                    speak(top[0]),
                    "I combine that with a strong focus on clear priorities and measurable outcomes.",
                ),
                0.75,
                coach = listOf(upper(skills[0]), "PROOF", "OUTCOMES"), structure = "Strength → proof → how I work",
            )
        }

        add(
            "GENERAL_BUSINESS", AnswerMode.Bridge, "What is your biggest weakness?",
            listOf("Your weaknesses", "What do you need to improve"),
            listOf(
                if (gaps.isNotEmpty()) "An area I'm actively strengthening is ${gaps[0]}." else "I sometimes take on too much myself instead of delegating early.",
                if (gaps.isNotEmpty()) "I'm closing that gap through focused learning and by working closely with experts on real problems." else "I've learned to agree priorities up front and hand over ownership sooner.",
                "I'm open about it, because it keeps expectations realistic and helps me improve faster.",
            ),
            0.7, coach = listOf("HONEST", "ACTION", "PROGRESS"), structure = "Real area → what I'm doing → progress",
        )

        add(
            "GENERAL_BUSINESS", AnswerMode.Hypothetical, "What would you do in your first 90 days?",
            listOf("30 60 90 day plan", "First three months", "How would you start in the role"),
            listOf(
                "In the first thirty days I'd learn the product, the customers and the team's priorities${if (jd.responsibilities.isNotEmpty()) ", especially " + lower(PreparationPipeline.keyPhrase(jd.responsibilities[0], 8)) else ""}.",
                "By sixty days I'd have clarified goals and success metrics with stakeholders and prioritised the biggest opportunities.",
                "By ninety days I'd deliver first measurable improvements and set a steady rhythm of planning, review and measurement.",
            ),
            0.8, coach = listOf("LEARN", "PRIORITISE", "DELIVER"), structure = "30: learn → 60: prioritise → 90: deliver",
        )

        val ask = questionsToAsk(t, jd)
        add(
            "GENERAL_BUSINESS", AnswerMode.Hypothetical, "Do you have any questions for us?",
            listOf("Any questions for me", "What would you like to ask us"),
            ask.take(3), 0.85, full = ask.joinToString(" "),
            coach = listOf("SUCCESS", "CHALLENGES", "TEAM"), structure = "Success metric → biggest challenge → how the team works",
        )

        add(
            "GENERAL_BUSINESS", AnswerMode.Hypothetical, "Where do you see yourself in five years?",
            listOf("Five year plan", "Career goals"),
            listOf(
                "I see myself growing into a leading expert in ${lower(strengthPhraseShort())}, with real ownership of outcomes.",
                "Ideally I'd have helped $company achieve clear, measurable results in this area.",
                "And I'd like to be developing others as well as my own skills.",
            ),
            0.65,
            coach = listOf("GROWTH", "IMPACT", "PEOPLE"), structure = "Expertise → impact here → developing others",
        )
    }

    private data class BehaviouralTopic(val topic: String, val question: String, val variants: List<String>, val lesson: String)

    private fun behavioural() {
        for ((topic, q, variants, lesson) in BehaviouralSet) {
            val story = pickStory(topic) ?: continue
            val fact = facts[story.factIds[0]]
            val bullets = listOf(
                if (story.company.isNotEmpty()) "At ${story.company}, as ${story.role}, this was one of my main priorities." else "This was one of my main priorities in a previous role.",
                if (fact != null) speak(fact) else "I ${lower(story.action)}",
                "What I took from it is that ${lower(lesson)}",
            )
            add(
                "BEHAVIOURAL", AnswerMode.Verified, q, variants, bullets, 0.75,
                coach = listOf(upper(firstWord(story.company)), upper(topic), story.metrics.firstOrNull()?.uppercase() ?: "RESULT"),
                structure = "Situation → what I did → measurable result", storyIds = listOf(story.id),
            )
        }
        // Failure/conflict questions: approach framing (no invented incidents).
        add(
            "BEHAVIOURAL", AnswerMode.Hypothetical, "Tell me about a time you failed.",
            listOf("Describe a mistake you made", "What's a setback you learned from"),
            listOf(
                "I'd pick a real example where I underestimated something early and had to correct course.",
                "The key is owning it quickly, fixing the impact and telling the people affected.",
                "And then changing how I work, so the same mistake doesn't happen again.",
            ),
            0.5,
            coach = listOf("OWN IT", "FIX", "LESSON"), structure = "Real setback → how I fixed it → what changed",
            gap = "Use a real personal example; the résumé does not describe failures.",
        )
    }

    private fun perRole() {
        for (roleFact in facts.values.filter { it.kind == FactKind.Role }.sortedByDescending { startYear(it.period) }.take(6)) {
            val ach = facts.values.filter { it.parentId == roleFact.id && it.kind == FactKind.Achievement }.sortedByDescending { it.metrics.size }.take(2)
            if (ach.isEmpty()) continue
            val bullets = mutableListOf("At ${roleFact.company} I was ${roleFact.title}${if (roleFact.period.isNotEmpty()) ", " + roleFact.period.replace(" – ", " to ") else ""}.")
            bullets.addAll(ach.map { speak(it) })
            add(
                "RESUME_EXPERIENCE", AnswerMode.Verified, "Tell me about your experience at ${roleFact.company}.",
                listOf("What did you do at ${roleFact.company}", "Tell me about your role at ${firstWord(roleFact.company)}", "Your time at ${firstWord(roleFact.company)}"),
                bullets, 0.85,
                coach = listOf(upper(firstWord(roleFact.company)), upper(firstWord(roleFact.title)), ach[0].metrics.firstOrNull()?.uppercase() ?: "RESULT"),
                structure = "Role → key result → second result", storyIds = ach.map { "s" + it.id },
            )
        }
    }

    private fun requirements() {
        for (m in matches.take(30)) {
            val phrase = PreparationPipeline.keyPhrase(m.requirement)
            if (phrase.length < 3) continue
            val keywords = contentKeywords(phrase)
            if (!m.isGap) {
                val matchedFacts = m.factIds.mapNotNull { facts[it] }.filter { it.kind == FactKind.Achievement || it.kind == FactKind.Project }.take(2)
                if (matchedFacts.isNotEmpty()) {
                    add(
                        "RESUME_EXPERIENCE", AnswerMode.Verified, "What experience do you have with $phrase?",
                        listOf("Tell me about your experience with $phrase", "Have you worked with $phrase"),
                        matchedFacts.map { speak(it) } + "In this role I'd apply the same approach to $phrase, starting with clear goals and measurable results.",
                        0.75,
                        coach = keywords, structure = "Direct answer → proof → how I'd apply it here",
                        storyIds = matchedFacts.map { "s" + it.id },
                    )
                }
            } else {
                val closest = topMatchedAchievements(1).firstOrNull()
                add(
                    "GAP_EXPERIENCE", AnswerMode.Bridge, "Have you personally worked with $phrase?",
                    listOf("Do you have experience with $phrase", "Have you done $phrase before"),
                    listOf(
                        "I haven't owned $phrase directly, but I understand what it requires and how to approach it.",
                        "I'd start by clarifying the goals, rules and risks with the experts, then turn them into clear, testable requirements.",
                        if (closest != null) "My closest experience: " + lower(speak(closest)) else "I learn new domains quickly by working closely with specialists on real problems.",
                    ),
                    0.7, coach = listOf("HONEST", upper(keywords[0]), "CLOSEST PROOF"), structure = "Honest bridge → approach → closest evidence",
                    gap = "Résumé shows no direct experience with: $phrase.",
                )
            }
            add(
                "CASE_STUDY", AnswerMode.Hypothetical, "How would you approach $phrase in this role?",
                listOf("How would you handle $phrase", "What is your approach to $phrase"),
                listOf(
                    "I'd start by clarifying the goal and the constraints around $phrase, with the people who own it today.",
                    "Then I'd prioritise the highest-impact steps, agree success criteria and deliver in small, measurable increments.",
                    "I'd track the results closely and adjust based on what the data and the users tell us.",
                ),
                0.55, coach = keywords, structure = "Goal → approach → measure",
            )
        }
    }

    private fun responsibilities() {
        for (r in jd.responsibilities.take(20)) {
            val phrase = PreparationPipeline.keyPhrase(r, 10)
            if (phrase.length < 4) continue
            add(
                "PRODUCT_EXECUTION", AnswerMode.Hypothetical, "How would you ${verbPhrase(phrase)}?",
                listOf("How do you approach $phrase", "Walk me through how you'd handle $phrase"),
                listOf(
                    "I'd begin by understanding who is affected and what success looks like for $phrase.",
                    "Then I'd align stakeholders on priorities and break the work into clear, testable steps.",
                    "After delivery I'd measure the impact and iterate, so we keep improving rather than just shipping.",
                ),
                0.55, coach = contentKeywords(phrase), structure = "Who & why → plan → measure",
            )
        }
    }

    fun positioning(): String {
        val yrs = ProfileService.yearsOfExperience(p)
        val top = topMatchedAchievements(3).map { "${it.company}: ${trim(it.text, 18)}" }
        return "${p.headline}${if (yrs > 0) ", $yrs+ years" else ""}. Strongest verified evidence for this role: ${top.joinToString(" | ")}. Mention evidence only when it genuinely strengthens the answer."
    }

    // ---------------- helpers ----------------

    private fun topMatchedAchievements(n: Int): List<ProfileFact> {
        val scores = mutableMapOf<String, Double>()
        for (m in matches.filter { !it.isGap }) {
            for (id in m.factIds) scores[id] = (scores[id] ?: 0.0) + m.score
        }
        return facts.values.filter { it.kind == FactKind.Achievement }
            .sortedByDescending { (scores[it.id] ?: 0.0) + it.metrics.size * 0.3 + (startYear(it.period) / 10000.0) }
            .take(n)
    }

    private fun pickStory(topic: String): Story? {
        val candidates = stories.filter { topic in it.skills }
            .sortedWith(compareBy<Story> { storyUse[it.id] ?: 0 }.thenByDescending { it.metrics.size })
        val s = candidates.firstOrNull()
        if (s != null) storyUse[s.id] = (storyUse[s.id] ?: 0) + 1
        return s
    }

    private fun strengthPhrase(): String {
        val topics = stories.flatMap { it.skills }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(3)
        val nice = topics.map { topic ->
            when (topic) {
                "growth" -> "driving growth"
                "retention" -> "improving retention"
                "leadership" -> "leading teams"
                "cost" -> "cost discipline"
                "launch" -> "launching products"
                "data" -> "data-driven decisions"
                "partnership" -> "building partnerships"
                "reliability" -> "working closely with technical teams"
                "process" -> "improving processes"
                "customer" -> "customer focus"
                "compliance" -> "managing regulatory risk"
                else -> topic
            }
        }
        return if (nice.isNotEmpty()) join(nice) else "my track record of delivering results"
    }

    private fun strengthPhraseShort(): String = strengthPhrase().split(',')[0]

    private fun add(
        category: String,
        mode: AnswerMode,
        question: String,
        variants: List<String>,
        bulletsIn: List<String>,
        confidence: Double,
        coach: List<String>? = null,
        structure: String? = null,
        storyIds: List<String>? = null,
        gap: String = "",
        full: String = "",
        follow: List<String>? = null,
    ) {
        val bullets = bulletsIn.filter { it.isNotBlank() }.map { it.trim() }
        if (bullets.size < 2) return
        val kw = (coach ?: contentKeywords(question)).filter { it.isNotBlank() }.map { it.uppercase() }.distinct().toMutableList()
        for (d in listOf("GOAL", "APPROACH", "RESULT")) if (kw.size < 3 && d !in kw) kw.add(d)
        out.add(
            BankQuestion(
                canonicalQuestion = question,
                category = category,
                intent = question.trimEnd('?', '.'),
                keywords = TextNormalizer.tokenize(question + " " + variants.joinToString(" ")).distinct().take(14),
                semanticVariants = variants,
                answerModeRaw = mode.name.uppercase(),
                shortBullets = bullets.take(4),
                optionalFullAnswer = full.ifEmpty { bullets.joinToString(" ") },
                candidateEvidence = storyIds ?: listOf(),
                gapWarning = gap,
                followUpQuestions = follow ?: listOf(),
                coachKeywords = kw.take(3),
                answerStructure = structure ?: when (mode) {
                    AnswerMode.Verified -> "Situation → what I did → result"
                    AnswerMode.Bridge -> "Honest bridge → approach → closest evidence"
                    else -> "Direct answer → approach → measure"
                },
                storyIds = storyIds ?: listOf(),
                language = "en",
                confidence = confidence,
            ),
        )
    }

    companion object {
        private val BehaviouralSet: List<BehaviouralTopic> = listOf(
            BehaviouralTopic("leadership", "Tell me about a time you led a team.", listOf("Give an example of your leadership", "How have you managed a team"), "Clear goals and real ownership get the best from a team."),
            BehaviouralTopic("growth", "Tell me about a time you drove growth.", listOf("Give an example of increasing revenue", "Tell me about a growth success"), "Growth comes from combining the right priorities with consistent execution."),
            BehaviouralTopic("retention", "Tell me about a time you improved customer retention.", listOf("How have you reduced churn", "Example of improving customer satisfaction"), "Retention improves when you remove friction customers actually feel."),
            BehaviouralTopic("cost", "Tell me about a time you reduced costs.", listOf("Example of cost optimisation", "How have you managed a budget"), "Protect what customers value and cut what doesn't change outcomes."),
            BehaviouralTopic("launch", "Tell me about a product launch or initiative you led.", listOf("Tell me about a go-to-market", "Give me a launch example"), "Strong launches start from a clear customer and a measurable goal."),
            BehaviouralTopic("data", "Tell me about a time you used data to make a decision.", listOf("Example of a data-driven decision", "How do you use analytics"), "Data is most useful when it's tied to a specific decision."),
            BehaviouralTopic("partnership", "Tell me about a successful partnership or negotiation.", listOf("Tell me about a negotiation", "Example of working with partners"), "Good partnerships start from a shared goal and clear trade-offs."),
            BehaviouralTopic("reliability", "Tell me about a time you worked with technical teams to improve something.", listOf("Example of working with engineers", "How have you collaborated with technical teams"), "Reliability is a product feature, and it deserves real priority."),
            BehaviouralTopic("process", "Tell me about a time you improved a process.", listOf("Example of process improvement", "How have you made a team more efficient"), "Small process changes compound when you measure them."),
            BehaviouralTopic("customer", "Tell me about a time you improved the customer experience.", listOf("Example of customer focus", "How have you improved user experience"), "Listening to customers is the fastest way to find what to fix."),
            BehaviouralTopic("compliance", "Tell me about a time you managed regulatory or compliance risk.", listOf("Experience with compliance", "How have you handled regulation"), "Compliance works best when it's built in from the start."),
        )

        fun questionsToAsk(t: InterviewTarget, jd: JobAnalysis): List<String> {
            val role = t.jobTitle.ifEmpty { "this role" }
            val list = mutableListOf(
                "How do you define success for the $role in the first six months?",
                "What is the biggest challenge the team is facing right now?",
                "How do product, engineering and the business work together on priorities?",
                "Which metrics does leadership review most closely for this area?",
                "What does a typical week look like in this role?",
                "How is the team structured, and who would I work with most closely?",
                "What would make someone exceptional in this role after one year?",
                "How are decisions made when stakeholders disagree?",
                "What are the next big milestones on the roadmap?",
                "What are the next steps in the interview process?",
            )
            for (r in jd.responsibilities.take(3)) {
                list.add(2, "How do you currently approach ${PreparationPipeline.keyPhrase(r, 8)}, and where would you like it to improve?")
            }
            return list.distinct()
        }

        /** Résumé bullet → speakable first-person sentence (content unchanged, ≤ ~26 words). */
        fun speak(f: ProfileFact): String {
            var text = WhitespaceRun.replace(f.text.trim(), " ").trimEnd('.', ';')
            text = shortenClauses(text, 24)
            val first = text.split(' ')[0]
            val s: String = if (first.lowercase() in PassiveVerbs) {
                "I was " + lower(text)
            } else if (first.lowercase() in PastVerbs || first.endsWith("ed", ignoreCase = true)) {
                "I " + lower(text)
            } else {
                (if (f.company.isNotEmpty()) "At ${f.company}: " else "") + text
            }
            return cap(s) + "."
        }

        private fun clause(f: ProfileFact): String {
            val s = speak(f)
            return lower(s).trimEnd('.')
        }

        private fun shortenClauses(t: String, maxWords: Int): String {
            val words = t.split(' ')
            if (words.size <= maxWords) return t
            for (sep in listOf(", resulting in", ", which", ", leading to", " through ", " via ", " by ", ", ")) {
                val i = t.indexOf(sep, ignoreCase = true)
                if (i > 20 && t.substring(0, i).split(' ').size <= maxWords && HasDigit.containsMatchIn(t.substring(0, i)) == HasDigit.containsMatchIn(t)) {
                    return t.substring(0, i)
                }
            }
            return words.take(maxWords).joinToString(" ")
        }

        fun contentKeywords(text: String): List<String> {
            val words = WordPattern.findAll(text).map { it.value }
                .filter { w -> w.length > 2 && w.lowercase() !in NotKeywords }
                .filter { w -> TextNormalizer.tokenize(w).isNotEmpty() }
                .toList()
            val ranked = words
                .sortedWith(compareByDescending<String> { w -> if (isUpperChar(w[0])) 2 else 0 }.thenByDescending { w -> w.length })
                .map { it.uppercase() }.distinct().take(3).toMutableList()
            for (d in listOf("GOAL", "APPROACH", "MEASURE")) if (ranked.size < 3) ranked.add(d)
            return ranked
        }

        private val PastVerbs = setOf(
            "led", "grew", "built", "ran", "drove", "won", "made", "set", "cut", "took", "wrote", "sold", "brought", "kept", "met", "oversaw", "spearheaded", "began", "found", "held", "taught", "lowered",
        )
        private val PassiveVerbs = setOf("recognized", "recognised", "awarded", "promoted", "selected", "named", "appointed", "ranked", "certified")
        private val NotKeywords = setOf(
            "approach", "would", "role", "experience", "work", "working", "personally", "handle", "tell", "time", "team", "worked", "strong", "proven", "ability", "build", "building", "using", "across", "within", "ensure", "drive", "manage", "own", "owning",
        )

        private val WhitespaceRun = dotNetRegex("\\s+")
        private val HasDigit = dotNetRegex("\\d")
        private val WordPattern = dotNetRegex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\-]+")
        private val VowelStart = dotNetRegex("^[aeiouAEIOU]")
        private val VerbBasePattern = dotNetRegex("^(build|manage|own|drive|define|lead|translate|implement|oversee|develop|create|work|improve|design|deliver|run|scale|support|coordinate|ensure|maintain)s?$")
        private val YearPattern = dotNetRegex("(19|20)\\d{2}")

        private val VerbMap = mapOf(
            "building" to "build", "managing" to "manage", "owning" to "own", "driving" to "drive", "defining" to "define", "leading" to "lead",
            "translating" to "translate", "implementing" to "implement", "overseeing" to "oversee", "developing" to "develop", "creating" to "create", "working" to "work",
        )

        private fun verbPhrase(phrase: String): String {
            val first = phrase.split(' ')[0].lowercase()
            val mapped = VerbMap[first]
            if (mapped != null) return mapped + phrase.substring(first.length)
            if (VerbBasePattern.containsMatchIn(first)) return first.trimEnd('s') + phrase.substring(first.length)
            return "approach " + phrase
        }

        private fun keyPhraseShort(s: String, n: Int): String =
            s.split(' ').filter { it.isNotEmpty() }.take(n).joinToString(" ").trimEnd('.', ',')

        private fun trim(s: String, n: Int): String {
            val w = s.split(' ')
            return if (w.size <= n) s.trimEnd('.') else w.take(n).joinToString(" ") + "…"
        }

        private fun firstWord(s: String): String = s.split(' ').firstOrNull { it.isNotEmpty() } ?: s
        private fun upper(s: String): String = s.uppercase()
        private fun lower(s: String): String =
            if (s.length > 1 && isUpperChar(s[0]) && !isUpperChar(s[1])) s[0].lowercaseChar() + s.substring(1) else s

        private fun cap(s: String): String = if (s.isNotEmpty()) s[0].uppercaseChar() + s.substring(1) else s
        private fun article(s: String): String = if (VowelStart.containsMatchIn(s)) "an" else "a"

        private fun join(items: Iterable<String>): String {
            val l = items.toList()
            return if (l.size <= 1) l.joinToString("") else l.take(l.size - 1).joinToString(", ") + " and " + l.last()
        }

        private fun startYear(period: String): Int {
            val m = YearPattern.find(period) ?: return 0
            return m.value.toInt()
        }

        private fun isUpperChar(c: Char): Boolean = Character.getType(c) == Character.UPPERCASE_LETTER.toInt()
    }

    private fun renumber(q: BankQuestion, n: Int): BankQuestion = BankQuestion(
        questionId = "T" + n.toString().padStart(3, '0'),
        canonicalQuestion = q.canonicalQuestion,
        category = q.category,
        intent = q.intent,
        keywords = q.keywords,
        semanticVariants = q.semanticVariants,
        answerModeRaw = q.answerModeRaw,
        shortBullets = q.shortBullets,
        optionalFullAnswer = q.optionalFullAnswer,
        candidateEvidence = q.candidateEvidence,
        gapWarning = q.gapWarning,
        technicalNotes = q.technicalNotes,
        productNotes = q.productNotes,
        followUpQuestions = q.followUpQuestions,
        coachKeywords = q.coachKeywords,
        answerStructure = q.answerStructure,
        storyIds = q.storyIds,
        language = q.language,
        confidence = q.confidence,
    )
}
