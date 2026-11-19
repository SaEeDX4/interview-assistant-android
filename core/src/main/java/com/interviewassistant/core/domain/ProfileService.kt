package com.interviewassistant.core.domain

import com.interviewassistant.core.ingestion.DocumentTextExtractor
import com.interviewassistant.core.ingestion.IUploadScanner
import com.interviewassistant.core.ingestion.ParsedResume
import com.interviewassistant.core.ingestion.PromptInjectionGuard
import com.interviewassistant.core.ingestion.ResumeParser
import com.interviewassistant.core.ingestion.UploadValidator
import com.interviewassistant.core.ingestion.XmlException
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.EducationEntry
import com.interviewassistant.core.knowledge.Experience
import com.interviewassistant.core.knowledge.LanguageEntry
import com.interviewassistant.core.knowledge.PersonalEntry
import com.interviewassistant.core.languages.LanguageDetector
import com.interviewassistant.core.persistence.InvalidDataException
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Mirror of `InterviewAssistant.Core/Domain/ProfileService.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Adaptations: the C# value-tuple return of `Upload` is a Kotlin [Pair]; the exception filter of the C#
 * (`InvalidDataException | XmlException | ArgumentException | InvalidOperationException | IOException`)
 * maps to the mirrored [InvalidDataException], the ingestion [XmlException], [IllegalArgumentException],
 * [IllegalStateException] and [IOException].
 */

data class IngestResult(val ok: Boolean, val error: String?, val document: SourceDocument?, val parsed: ParsedResume?)

/** Creates profiles from source documents, builds stories from CONFIRMED facts, and converts to engine shapes. */
object ProfileService {
    fun createDocument(fileName: String, kind: DocumentKind, text: String, needsOcr: Boolean = false): SourceDocument {
        val guarded = PromptInjectionGuard.sanitize(text)
        val doc = SourceDocument(
            fileName = UploadValidator.sanitizeFileName(fileName), kind = kind, text = guarded.text, needsOcr = needsOcr,
            sha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            language = LanguageDetector.detectOr(guarded.text, "und", 0.4),
        )
        doc.securityFlags.addAll(guarded.flags)
        return doc
    }

    /** Validates + extracts an uploaded file (PDF/DOCX/TXT) into a SourceDocument. */
    fun upload(fileName: String, bytes: ByteArray, kind: DocumentKind, scanner: IUploadScanner? = null): Pair<SourceDocument?, String?> {
        val check = UploadValidator.validate(fileName, bytes, scanner)
        if (!check.ok) return Pair(null, check.error)
        val text = try {
            DocumentTextExtractor.extract(check.type, bytes)
        } catch (ex: Exception) {
            if (ex is InvalidDataException || ex is XmlException || ex is IllegalArgumentException ||
                ex is IllegalStateException || ex is IOException
            ) return Pair(null, "The document could not be read: " + ex.message)
            throw ex
        }
        if (text.needsOcr) return Pair(null, "This PDF contains only images (scanned). Please upload a text-based PDF, DOCX, or paste the text.")
        return Pair(createDocument(check.safeFileName, kind, text.text, text.needsOcr), null)
    }

    /** Adds a résumé to a profile: parses facts (status = Source, awaiting user confirmation). */
    fun addResume(profile: CandidateProfileRecord, doc: SourceDocument): ParsedResume {
        profile.documents.add(doc)
        val parsed = ResumeParser.parse(doc)
        if (profile.name.isEmpty()) profile.name = parsed.name
        if (profile.headline.isEmpty()) profile.headline = parsed.headline
        if (profile.location.isEmpty()) profile.location = parsed.location
        // De-duplicate facts across multiple CV versions (same kind + normalised text).
        val existing = profile.facts.map { key(it) }.toHashSet()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        for (f in parsed.facts) {
            if (existing.add(key(f))) {
                f.source = FactSource.ResumeImport
                f.sourceName = doc.fileName
                f.createdUtc = now
                profile.facts.add(f)
            }
        }
        profile.updatedUtc = now
        ProfileMigration.migrate(profile)
        return parsed
    }

    /** Adds a fact the user typed (profile editor or questionnaire). The user's own statement starts confirmed. */
    fun addManualFact(
        profile: CandidateProfileRecord, kind: FactKind, text: String, category: String = "", company: String = "", title: String = "",
        period: String = "", source: FactSource = FactSource.Manual, confirmed: Boolean = true,
    ): ProfileFact {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val f = ProfileFact(
            kind = kind, text = text.trim(), category = category, company = company.trim(), title = title.trim(), period = period.trim(),
            source = source, sourceName = if (source == FactSource.Questionnaire) "Interview profile questions" else "Added by you",
            createdUtc = now, updatedUtc = now, confidence = 1.0,
            metrics = ResumeParser.extractMetrics(text).toMutableList(),
            status = if (confirmed) FactStatus.UserConfirmed else FactStatus.Source, confirmedUtc = if (confirmed) now else null,
        )
        profile.facts.add(f)
        profile.updatedUtc = now
        return f
    }

    /** Edits a fact's text. An edited fact keeps its confirmation only if the user re-confirms it in the same edit. */
    fun editFact(
        profile: CandidateProfileRecord, f: ProfileFact, text: String, confirmed: Boolean,
        company: String? = null, title: String? = null, period: String? = null,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        f.text = text.trim()
        if (company != null) f.company = company.trim()
        if (title != null) f.title = title.trim()
        if (period != null) f.period = period.trim()
        f.metrics = ResumeParser.extractMetrics(f.text).toMutableList()
        f.updatedUtc = now
        f.setConfirmed(confirmed, now)
        profile.updatedUtc = now
    }

    /** Removes a fact (the user deleted it). Stories built from it disappear on the next rebuild. */
    fun deleteFact(profile: CandidateProfileRecord, factId: String): Boolean {
        val removed = profile.facts.removeAll { it.id == factId }
        if (removed) {
            profile.updatedUtc = OffsetDateTime.now(ZoneOffset.UTC)
            rebuildStories(profile)
        }
        return removed
    }

    /** Replaces the answers for one questionnaire category (empty text removes it). Answers are the user's own, so confirmed. */
    fun setPersonal(profile: CandidateProfileRecord, category: String, text: String?) {
        val cat = PersonalCategories.find(category) ?: throw IllegalArgumentException("Unknown category")
        val kind = if (cat.boundary) FactKind.Boundary else FactKind.Personal
        val current = profile.facts.firstOrNull { it.kind == kind && it.category == category && it.status != FactStatus.Rejected }
        var value = (text ?: "").trim()
        if (value.length > 600) value = value.substring(0, 600)
        if (value.isEmpty()) {
            if (current != null) profile.facts.remove(current)
            profile.updatedUtc = OffsetDateTime.now(ZoneOffset.UTC)
            return
        }
        if (current == null) addManualFact(profile, kind, value, category, source = FactSource.Questionnaire)
        else if (current.text != value) editFact(profile, current, value, confirmed = true)
    }

    private fun key(f: ProfileFact): String =
        f.kind.toString() + "|" + com.interviewassistant.core.ingestion.dotNetRegex("\\W+").replace(f.text.lowercase(), " ").trim()

    /** Rebuilds the story bank from confirmed achievements (one story per achievement; nothing invented). */
    fun rebuildStories(profile: CandidateProfileRecord) {
        profile.stories = profile.confirmedFacts
            .filter { it.kind == FactKind.Achievement || it.kind == FactKind.Project }
            .map { f ->
                Story(
                    id = "s" + f.id, company = f.company, role = f.title,
                    situation = if (f.company.isNotEmpty()) "As ${f.title} at ${f.company}" else "",
                    action = f.text, result = f.metrics.joinToString(", "),
                    metrics = f.metrics.toList(), skills = storyTopics(f.text), questionTypes = storyQuestionTypes(f.text),
                    factIds = listOf(f.id),
                )
            }.toMutableList()
    }

    private val topics: List<Pair<String, String>> = listOf(
        "growth" to "grow|grew|increas|expand|scal|revenue|sales|market share|users|customers|traders",
        "retention" to "retention|churn|loyal|satisfaction|nps",
        "leadership" to "led|lead|team|mentor|train|hired|recruit|onboard|managed",
        "cost" to "cost|budget|saving|reduc|negotiat|margin|efficien",
        "launch" to "launch|go-to-market|gtm|introduc|release|rollout|new product",
        "data" to "analy|data|spss|excel|insight|research|forecast",
        "customer" to "customer|user|ux|experience|support|service",
        "partnership" to "partner|alliance|co-brand|deal|contract|vendor",
        "reliability" to "downtime|uptime|infrastructure|reliab|stabil|incident",
        "process" to "process|crm|system|implement|automat|tool|workflow",
        "compliance" to "complian|regulat|risk|audit|legal",
    )

    fun storyTopics(text: String): List<String> =
        topics.filter { com.interviewassistant.core.ingestion.dotNetRegex(it.second, ignoreCase = true).containsMatchIn(text) }.map { it.first }

    private fun storyQuestionTypes(text: String): List<String> {
        val map = linkedMapOf(
            "growth" to arrayOf("METRICS", "PRODUCT_STRATEGY"), "retention" to arrayOf("METRICS"), "leadership" to arrayOf("LEADERSHIP"),
            "cost" to arrayOf("GENERAL_BUSINESS"), "launch" to arrayOf("PRODUCT_STRATEGY"), "data" to arrayOf("METRICS"),
            "customer" to arrayOf("PRODUCT_DISCOVERY"), "partnership" to arrayOf("STAKEHOLDER"), "reliability" to arrayOf("TECHNICAL_CONCEPT"),
            "process" to arrayOf("PRODUCT_EXECUTION"), "compliance" to arrayOf("COMPLIANCE"),
        )
        return storyTopics(text).flatMap { map[it]!!.asIterable() }.plus("BEHAVIOURAL").plus("RESUME_EXPERIENCE").distinct()
    }

    /** Engine-facing profile containing ONLY user-confirmed facts (the truth boundary for live answers). */
    fun toEngineProfile(p: CandidateProfileRecord, knownGaps: List<String>? = null): CandidateProfile {
        val confirmed = p.confirmedFacts
        val roles = confirmed.filter { it.kind == FactKind.Role }
        val numbers = LinkedHashSet<String>()
        for (f in confirmed) {
            for (m in f.metrics + ResumeParser.extractMetrics(f.text)) {
                numbers.add(m)
                numbers.add(com.interviewassistant.core.ingestion.dotNetRegex("[^\\d.,]").replace(m, ""))
            }
            for (m in com.interviewassistant.core.ingestion.dotNetRegex("\\b\\d+\\+?\\s*(years?|months?)\\b", ignoreCase = true).findAll(f.text)) numbers.add(m.value)
            for (m in com.interviewassistant.core.ingestion.dotNetRegex("(19|20)\\d{2}").findAll(f.period)) numbers.add(m.value) // confirmed employment years
        }
        val yrs = yearsOfExperience(p)
        if (yrs > 0) {
            numbers.add(yrs.toString() + "+")
            numbers.add(yrs.toString())
        }
        val entities = (roles.map { it.company } + roles.map { it.company.split(' ')[0] } +
            confirmed.filter { it.kind == FactKind.Education }.map { it.company } +
            confirmed.filter { it.kind == FactKind.Skill }
                .flatMap { f -> com.interviewassistant.core.ingestion.dotNetRegex("\\b[A-Z][A-Za-z0-9]+\\b").findAll(f.text).map { it.value } })
            .filter { it.length > 1 }.distinct()
        return CandidateProfile(
            name = p.name, headline = p.headline,
            summary = confirmed.filter { it.kind == FactKind.Summary }.joinToString(" ") { it.text },
            coreCompetencies = confirmed.filter { it.kind == FactKind.Skill }.map { it.text },
            experience = roles.map { r ->
                Experience(
                    id = r.id, title = r.title, company = r.company, location = r.location, period = r.period,
                    achievements = confirmed.filter { a -> a.parentId == r.id && a.kind == FactKind.Achievement }.map { it.text },
                )
            },
            verifiedNumbers = numbers.filter { it.isNotEmpty() },
            verifiedEntities = entities,
            knownGaps = knownGaps?.toList() ?: listOf(),
            education = confirmed.filter { it.kind == FactKind.Education }.map { EducationEntry(degree = it.text, school = it.company) },
            languages = confirmed.filter { it.kind == FactKind.Language }.map { LanguageEntry(language = it.text) },
            preferredName = p.preferredName,
            personal = personalEntries(p),
            boundaries = boundaryEntries(p),
        )
    }

    /** Confirmed personal answers only (unconfirmed or rejected entries never reach a prompt). */
    fun personalEntries(p: CandidateProfileRecord): List<PersonalEntry> =
        p.confirmedFacts.filter { it.kind == FactKind.Personal && it.category.isNotEmpty() && it.text.isNotEmpty() }
            .map { PersonalEntry(category = it.category, text = it.text) }

    /** User-defined boundaries (active unless the user rejected/removed them). */
    fun boundaryEntries(p: CandidateProfileRecord): List<String> =
        p.facts.filter { it.kind == FactKind.Boundary && it.status == FactStatus.UserConfirmed && it.text.isNotEmpty() }.map { it.text }

    fun yearsOfExperience(p: CandidateProfileRecord): Int {
        val years = p.confirmedFacts.filter { it.kind == FactKind.Role }
            .flatMap { f -> com.interviewassistant.core.ingestion.dotNetRegex("(19|20)\\d{2}").findAll(f.period).map { it.value.toInt() } }
        return if (years.isEmpty()) 0 else OffsetDateTime.now(ZoneOffset.UTC).year - years.min()
    }

    fun toEngineStory(s: Story): CandidateStory = CandidateStory(
        id = s.id, company = s.company, role = s.role, situation = s.situation, action = s.action, result = s.result,
        verifiedNumbers = s.metrics, skills = s.skills, questionTypes = s.questionTypes,
    )
}
