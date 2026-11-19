package com.interviewassistant.core.preparation

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.domain.DocumentKind
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparationState
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.domain.RequirementMatch
import com.interviewassistant.core.ingestion.JobDescriptionParser
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.knowledge.KnowledgeSnippet
import com.interviewassistant.core.knowledge.TargetContext
import com.interviewassistant.core.providers.ProviderException
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirror of `InterviewAssistant.Core/Preparation/PreparationPipeline.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class PreparationProgress(val stage: Int, val totalStages: Int, val stageName: String, val detail: String)

/** Optional AI enrichment (improves low-confidence prepared answers). Must keep the truth rules. */
interface IPreparationEnricher {
    suspend fun enrichAsync(pack: PreparedPack, progress: ((PreparationProgress) -> Unit)?)
}

@Serializable
class GenericLibraryItem(
    @SerialName("canonical_question") val canonicalQuestion: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("semantic_variants") val semanticVariants: List<String> = listOf(),
    @SerialName("short_bullets") val shortBullets: List<String> = listOf(),
    @SerialName("optional_full_answer") val optionalFullAnswer: String = "",
    @SerialName("follow_up_questions") val followUpQuestions: List<String> = listOf(),
    @SerialName("domains") val domains: List<String> = listOf(),
)

/** Shared, company-neutral inputs shipped with the app (playbooks + generic expert answers). */
class PreparationAssets(
    val playbooks: List<KnowledgeSnippet> = listOf(),
    val library: List<GenericLibraryItem> = listOf(),
) {
    @Serializable
    private class LibFile(
        @SerialName("questions") val questions: List<GenericLibraryItem> = listOf(),
    )

    companion object {
        const val LibraryFileName = "generic_question_library.json"

        /** Finds the shipped generic assets next to (or above) [baseDirectory]; empty assets if absent. */
        fun loadShipped(baseDirectory: String): PreparationAssets =
            KnowledgeBase.findDirectory(baseDirectory, "knowledge", LibraryFileName)?.let { load(it) } ?: PreparationAssets()

        fun load(knowledgeDir: String): PreparationAssets {
            val playbooks = mutableListOf<KnowledgeSnippet>()
            val pbDir = File(knowledgeDir, "playbooks")
            if (pbDir.isDirectory) {
                for (f in pbDir.listFiles { file -> file.isFile && file.name.endsWith(".md") } ?: emptyArray()) {
                    playbooks.addAll(KnowledgeBase.splitMarkdown(f.name, f.readText()))
                }
            }
            val libPath = File(knowledgeDir, LibraryFileName)
            val lib = if (libPath.isFile) {
                CoreJson.instance.decodeFromString(LibFile.serializer(), libPath.readText()).questions
            } else {
                listOf()
            }
            return PreparationAssets(playbooks = playbooks, library = lib)
        }
    }
}

/**
 * Builds a per-target interview pack from CONFIRMED profile facts + the job package. Deterministic and offline
 * (fast, free, testable); an optional [IPreparationEnricher] upgrades answers with the AI backend.
 * Never sends whole documents to the live path: the output is compact structured context.
 */
class PreparationPipeline(private val assets: PreparationAssets) {

    suspend fun runAsync(
        profile: CandidateProfileRecord,
        target: InterviewTarget,
        enricher: IPreparationEnricher? = null,
        progress: ((PreparationProgress) -> Unit)? = null,
    ): PreparedPack {
        var stage = 0
        val log = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        suspend fun step(detail: String) {
            currentCoroutineContext().ensureActive()
            log.add("${stages[stage]}: $detail")
            progress?.invoke(PreparationProgress(stage + 1, stages.size, stages[stage], detail))
            stage++
        }

        // 1. profile verification — only confirmed facts may become historical claims
        val confirmed = profile.confirmedFacts.toList()
        if (confirmed.isEmpty()) throw IllegalStateException("Confirm at least some extracted profile facts before preparing an interview.")
        if (profile.pendingReviewCount > 0) warnings.add("${profile.pendingReviewCount} extracted facts are not confirmed and will not be used as claims.")
        ProfileService.rebuildStories(profile)
        step("${confirmed.size} confirmed facts, ${profile.stories.size} stories")

        // 2. JD extraction (untrusted input; injection lines removed)
        val jdText = (listOf(target.jobDescriptionText) + target.documents.filter { it.kind == DocumentKind.JobDescription }.map { it.text }).joinToString("\n")
        val jd = JobDescriptionParser.parse(jdText, target.jobTitle.ifEmpty { null }, target.company.ifEmpty { null })
        if (target.jobTitle.isEmpty()) target.jobTitle = jd.title
        if (target.company.isEmpty()) target.company = jd.company
        warnings.addAll(jd.securityFlags)
        val requirements = (jd.requirements + jd.niceToHave).distinct().take(30)
        step("${jd.responsibilities.size} responsibilities, ${jd.requirements.size} requirements, ${jd.niceToHave.size} nice-to-have (language ${jd.language})")

        // 3–4. match map + gaps
        val factIndex = confirmed.filter {
            it.kind == FactKind.Achievement || it.kind == FactKind.Role || it.kind == FactKind.Skill || it.kind == FactKind.Project ||
                it.kind == FactKind.Certification || it.kind == FactKind.Education || it.kind == FactKind.Summary
        }.map { f -> f to TextNormalizer.tokenize(f.text + " " + f.title + " " + f.company).toHashSet() }
        val matchMap = requirements.map { r ->
            val rt = TextNormalizer.tokenize(r).filter { it !in GenericReqWords }.toHashSet()
            val scored = factIndex.map { (fact, tokens) ->
                fact to if (rt.isEmpty()) 0.0 else rt.count { it in tokens }.toDouble() / rt.size
            }.filter { it.second > 0 }.sortedByDescending { it.second }
            val best = scored.firstOrNull()?.second ?: 0.0
            RequirementMatch(requirement = r, factIds = scored.take(3).map { it.first.id }, score = best, isGap = best < 0.25)
        }
        val gaps = matchMap.filter { it.isGap }.map { keyPhrase(it.requirement) }.filter { it.length > 2 }.distinct()
        step("${matchMap.count { !it.isGap }} matched requirements")
        step("${gaps.size} likely gaps")

        // 5. terminology
        val terminology = (jd.terminology + target.documents.filter { it.kind == DocumentKind.CompanyMaterial }.flatMap { JobDescriptionParser.extractTerminology(it.text, target.company) })
            .distinctBy { it.lowercase() }.take(60)
        step("${terminology.size} terms")

        // 6. company/product context from USER-PROVIDED sources only (labelled; nothing fabricated)
        val snippets = mutableListOf<KnowledgeSnippet>()
        fun addSection(src: String, heading: String, items: Iterable<String>) {
            for (item in items) snippets.add(KnowledgeSnippet(src, heading, item))
        }
        addSection("job_description", "Responsibilities", jd.responsibilities)
        addSection("job_description", "Requirements", jd.requirements)
        addSection("job_description", "Nice to have", jd.niceToHave)
        addSection("job_description", "About the company (from JD, user-provided)", jd.about)
        for (d in target.documents.filter { it.kind == DocumentKind.CompanyMaterial || it.kind == DocumentKind.Notes }) {
            snippets.addAll(KnowledgeBase.splitMarkdown("user_material:" + d.fileName, d.text).take(200))
        }
        if (target.roleNotes.isNotEmpty()) addSection("role_notes", "Role notes (user)", target.roleNotes.split('\n').filter { it.trim().length > 20 })
        val domains = detectDomains(jdText + " " + terminology.joinToString(" "))
        snippets.addAll(assets.playbooks.filter { s -> domains.contains("fintech") || !s.source.contains("crypto") })
        val products = terminology.filter { t -> !AcronymPattern.containsMatchIn(t) && isUpperChar(t[0]) && CommonTech.none { it.equals(t, ignoreCase = true) } }.take(8)
        step("${snippets.size} context notes · domains: ${domains.joinToString(", ")}")

        // 7. stories, ordered by relevance to this role (used as default evidence order)
        val reqTokens = (requirements + jd.responsibilities).flatMap { TextNormalizer.tokenize(it) }.toHashSet()
        val stories = profile.stories
            .map { s -> s to (TextNormalizer.tokenize(s.action).count { it in reqTokens } + s.metrics.size * 0.5) }
            .sortedByDescending { it.second }.map { it.first }
        step("${stories.size} stories ranked")

        // 8–10. questions + fast answers + coach keywords
        val engineProfile = ProfileService.toEngineProfile(profile, gaps)
        val builder = QuestionFactory(profile, target, jd, stories, matchMap, gaps, confirmed)
        val questions = builder.build(assets.library.filter { l -> l.domains.any { it in domains } })
        step("${questions.size} questions")
        step("${questions.count { it.confidence >= 0.7 }} instant answers, ${questions.count { it.confidence < 0.7 }} AI-reference answers")
        step("3 keywords + structure per question")
        step("${snippets.count { s -> !s.source.startsWith("job") && !s.source.startsWith("user") }} playbook notes")

        // 12. truth policy
        val truth = "Historical claims only from user-confirmed facts. Hypothetical questions: strongest approach in future tense. " +
            "Direct questions about experience the candidate lacks (" + (if (gaps.isNotEmpty()) gaps.take(5).joinToString("; ") else "none detected") + "): one honest bridge sentence, then approach."
        step("policy set")
        val difficult = gaps.take(6).map { "Have you personally worked with $it?" } +
            listOf("Why are you looking for a new opportunity?", "What is your biggest weakness?", "Why should we choose you over other candidates?")
        step("${difficult.size} difficult questions")
        val toAsk = QuestionFactory.questionsToAsk(target, jd)
        step("${toAsk.size} questions to ask")

        val answerLang = if (target.answerLanguage != "same") target.answerLanguage else profile.answerLanguage
        val pack = PreparedPack(
            generatorVersion = GeneratorVersion,
            profileId = profile.id,
            targetId = target.id,
            context = TargetContext(
                candidateName = profile.preferredName.ifEmpty { profile.name },
                roleTitle = target.jobTitle.ifEmpty { "the role" },
                companyName = target.company,
                products = products,
                vocabulary = (terminology + confirmed.filter { it.kind == FactKind.Skill }.map { it.text }).distinct().take(80),
                domainNumbers = DomainNumbersPattern.findAll(jdText).map { it.value }.distinct().take(30).toList(),
                extraEntities = terminology.take(30),
                positioning = builder.positioning(),
                domainGuidance = "Key responsibilities: " + jd.responsibilities.take(6).joinToString("; ") { keyPhrase(it, 12) } + ".",
                interviewLanguage = if (target.expectedLanguage != "auto") target.expectedLanguage else profile.interviewLanguage,
                answerLanguage = answerLang.ifEmpty { "same" },
                profileId = profile.id,
                targetId = target.id,
            ),
            profile = engineProfile,
            stories = stories.map { ProfileService.toEngineStory(it) },
            questions = questions,
            snippets = snippets,
            matchMap = matchMap,
            gaps = gaps,
            terminology = terminology,
            difficultQuestions = difficult,
            questionsToAsk = toAsk,
            truthPolicy = truth,
            stageLog = log,
            warnings = warnings,
        )
        if (enricher != null) {
            try {
                enricher.enrichAsync(pack, progress)
            } catch (e: Exception) {
                if (e is ProviderException || e is IOException) {
                    warnings.add("AI enrichment unavailable: " + e.message + " (local answers kept).")
                } else {
                    throw e
                }
            }
        }
        step("ready")
        target.preparation = PreparationState.Prepared
        target.preparedUtc = OffsetDateTime.now(ZoneOffset.UTC)
        target.preparationError = null
        return pack
    }

    companion object {
        const val GeneratorVersion = "local-2.0"
        val stages: List<String> = listOf(
            "Verify candidate profile", "Extract role requirements", "Map candidate to role", "Analyse gaps", "Extract terminology",
            "Company & product context", "Select stories", "Generate likely questions", "Prepare fast answers", "Coach Mode keywords",
            "Domain playbook", "Answer truth policy", "Difficult questions", "Questions to ask", "Build runtime cache",
        )

        private val GenericReqWords = setOf(
            "experience", "year", "strong", "proven", "ability", "knowledge", "skill", "excellent", "good", "work", "working", "understand", "understanding", "able", "plus", "ideally", "team", "within", "least", "minimum",
        )
        private val CommonTech = listOf("Jira", "Agile", "Scrum", "Kanban", "Figma", "Product Owner", "Engineering", "Design", "Legal", "Compliance")
        private val AcronymPattern = dotNetRegex("^[A-Z]{2,5}s?$")
        private val DomainNumbersPattern = dotNetRegex("\\b\\d[\\d,.]*\\b")
        private val DomainProductPattern = dotNetRegex("product|backlog|roadmap|user stor|agile|scrum|sprint|stakeholder|okr|kpi", ignoreCase = true)
        private val DomainFintechPattern = dotNetRegex("crypto|blockchain|token|ledger|fintech|payment|bank|wallet|mica|kyc|aml|reconcil|digital asset", ignoreCase = true)
        private val DomainRegulatedPattern = dotNetRegex("regulat|complian|gdpr|audit", ignoreCase = true)
        private val DomainTechnicalPattern = dotNetRegex("api|architecture|engineer|software|cloud|system design|technical", ignoreCase = true)
        private val KeyPhraseLeadPattern = dotNetRegex(
            "^\\s*(\\d+\\+?\\s*(-\\s*\\d+\\s*)?years?( of)?|(proven|strong|solid|deep|hands-on|demonstrated|excellent|good)?\\s*(experience|background|knowledge|understanding|expertise|familiarity|proficiency)\\s*(in|with|of|building|owning|working with)?|ability to|you have|you are|must have|we expect)\\s*",
            ignoreCase = true,
        )
        private val KeyPhraseParensPattern = dotNetRegex("\\s*\\(.*?\\)")
        private val KeyPhraseArticlePattern = dotNetRegex("^(in|with|of|a|an|the)\\s+", ignoreCase = true)

        fun detectDomains(text: String): List<String> {
            val d = mutableListOf("general")
            if (DomainProductPattern.containsMatchIn(text)) d.add("product")
            if (DomainFintechPattern.containsMatchIn(text)) d.add("fintech")
            if (DomainRegulatedPattern.containsMatchIn(text)) d.add("regulated")
            if (DomainTechnicalPattern.containsMatchIn(text)) d.add("technical")
            return d
        }

        /** Compact noun phrase from a requirement sentence ("Proven experience building financial ledgers" → "building financial ledgers"). */
        fun keyPhrase(requirement: String, maxWords: Int = 8): String {
            var s = KeyPhraseLeadPattern.replace(requirement, "")
            s = KeyPhraseParensPattern.replace(s, "").trim().trimEnd('.', ';', ',')
            s = KeyPhraseArticlePattern.replace(s, "")
            val words = s.split(' ').filter { it.isNotEmpty() }
            val phrase = words.take(maxWords).joinToString(" ").trim().trimEnd(',', ';', ':')
            return if (phrase.isNotEmpty() && isUpperChar(phrase[0]) && !(phrase.length > 1 && isUpperChar(phrase[1]))) {
                phrase[0].lowercaseChar() + phrase.substring(1)
            } else {
                phrase
            }
        }

        private fun isUpperChar(c: Char): Boolean = Character.getType(c) == Character.UPPERCASE_LETTER.toInt()
    }
}
