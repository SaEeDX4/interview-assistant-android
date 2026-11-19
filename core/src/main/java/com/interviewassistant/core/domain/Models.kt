package com.interviewassistant.core.domain

import com.interviewassistant.core.knowledge.BankQuestion
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.knowledge.KnowledgeSnippet
import com.interviewassistant.core.knowledge.PromptTemplates
import com.interviewassistant.core.knowledge.TargetContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Mirror of `InterviewAssistant.Core/Domain/Models.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Wire format: the C# types are serialized by System.Text.Json with DEFAULT options (WorkspaceStore /
 * ProfileBundle), so property names are the PascalCase member names (pinned here with [SerialName]),
 * enums travel as their names (the C# enums carry `JsonStringEnumConverter`), nulls and defaults are
 * written. Kotlin property names are camelCase per the codebase convention; the wire names are the C# ones.
 * This file also hosts the :core JSON primitives (the [CoreJson] configuration and the System.DateTime
 * serializers), the same role `ApiContracts.kt` plays in `:contracts` — `:core` depends on nothing.
 */

/** Where a fact came from and whether it may be used as a historical claim. */
@Serializable
enum class FactStatus {
    /** Extracted from a source document; NOT yet usable as a verified claim. */
    Source,

    /** User reviewed and confirmed: may be used as a historical claim in answers. */
    UserConfirmed,

    /** Inferred by AI; never used as a historical claim unless the user confirms it. */
    AiInference,

    /** Generated domain/hypothetical knowledge; never a historical claim. */
    GeneratedKnowledge,

    /** User rejected; excluded everywhere. */
    Rejected,
}

@Serializable
enum class FactKind { Summary, Role, Achievement, Skill, Education, Language, Certification, Project, Other, Personal, Boundary }

/** Where a fact came from (provenance shown in the profile editor). */
@Serializable
enum class FactSource { Unknown, ResumeImport, Manual, Questionnaire, Document }

@Serializable
enum class DocumentKind { Resume, JobDescription, CompanyMaterial, LinkedIn, Portfolio, Notes }

@Serializable
enum class InterviewType { Unknown, HR, HiringManager, Technical, Case, Panel }

@Serializable
enum class PreparationState { NotPrepared, Preparing, Prepared, Failed, Stale }

/** System.Guid in "n" format (32 lowercase hex digits), as the C# models use for ids. */
internal fun newGuidN(): String = UUID.randomUUID().toString().replace("-", "")

@Serializable
data class Provenance(
    @SerialName("DocumentId") val documentId: String,
    @SerialName("LineStart") val lineStart: Int,
    @SerialName("LineEnd") val lineEnd: Int,
    @SerialName("Snippet") val snippet: String,
)

@Serializable
class SourceDocument(
    @SerialName("Id") val id: String = newGuidN(),
    @SerialName("FileName") val fileName: String = "",
    @SerialName("Kind") val kind: DocumentKind = DocumentKind.Resume,
    @SerialName("Language") var language: String = "und",
    @SerialName("Text") val text: String = "",
    @SerialName("Sha256") val sha256: String = "",
    @SerialName("NeedsOcr") val needsOcr: Boolean = false,
    @SerialName("AddedUtc") @Serializable(with = CoreDateTimeSerializer::class) val addedUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("SecurityFlags") val securityFlags: MutableList<String> = mutableListOf(),
)

@Serializable
class ProfileFact(
    @SerialName("Id") val id: String = newGuidN().substring(0, 12),
    @SerialName("Kind") val kind: FactKind = FactKind.Other,
    @SerialName("Text") var text: String = "",
    @SerialName("Company") var company: String = "",
    @SerialName("Title") var title: String = "",
    @SerialName("Location") var location: String = "",
    @SerialName("Period") var period: String = "",
    /** Id of the Role fact this fact belongs to (achievements under a role). */
    @SerialName("ParentId") var parentId: String? = null,
    @SerialName("Metrics") var metrics: MutableList<String> = mutableListOf(),
    @SerialName("Provenance") val provenance: Provenance? = null,
    @SerialName("Status") var status: FactStatus = FactStatus.Source,
    @SerialName("Confidence") var confidence: Double = 0.8,
    @SerialName("Flags") var flags: MutableList<String> = mutableListOf(),
    @SerialName("Language") var language: String = "und",
    /** Personal/boundary facts: [PersonalCategories] key (hobby, motivation, …). */
    @SerialName("Category") var category: String = "",
    @SerialName("Source") var source: FactSource = FactSource.Unknown,
    /** File name or questionnaire label the fact came from (no content). */
    @SerialName("SourceName") var sourceName: String = "",
    @SerialName("CreatedUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var createdUtc: OffsetDateTime? = null,
    @SerialName("UpdatedUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var updatedUtc: OffsetDateTime? = null,
    @SerialName("ConfirmedUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var confirmedUtc: OffsetDateTime? = null,
) {
    val isVerifiedClaim: Boolean get() = status == FactStatus.UserConfirmed

    /** Shown in the review list (every kind, including the résumé summary — nothing hidden can be "awaiting review"). */
    val isReviewable: Boolean get() = status != FactStatus.Rejected && status != FactStatus.GeneratedKnowledge
    val awaitingReview: Boolean get() = isReviewable && (status == FactStatus.Source || status == FactStatus.AiInference)

    /** Confirms (or un-confirms) the fact and records when. */
    fun setConfirmed(confirmed: Boolean, nowUtc: OffsetDateTime) {
        if (confirmed && status != FactStatus.UserConfirmed) {
            status = FactStatus.UserConfirmed; confirmedUtc = nowUtc; updatedUtc = nowUtc
        } else if (!confirmed && status == FactStatus.UserConfirmed) {
            status = FactStatus.Source; confirmedUtc = null; updatedUtc = nowUtc
        }
    }
}

/**
 * Counts for the profile header, list footer and filters — ONE derivation used everywhere (no drift).
 *
 * @param confirmed All confirmed facts: experience facts plus interview-profile answers.
 * @param confirmedProfileAnswers Confirmed interview-profile answers (personal topics and boundaries), which the
 * Experience list does not show — counts shown to the user name both parts, so adding a hobby never makes one number
 * silently disagree with another.
 */
@Serializable
data class FactStats(
    @SerialName("Total") val total: Int,
    @SerialName("Confirmed") val confirmed: Int,
    @SerialName("ToReview") val toReview: Int,
    @SerialName("ConfirmedProfileAnswers") val confirmedProfileAnswers: Int = 0,
) {
    val confirmedExperience: Int get() = confirmed - confirmedProfileAnswers

    companion object {
        fun of(facts: Iterable<ProfileFact>): FactStats {
            val list = facts.filter { it.isReviewable }
            return FactStats(
                list.size,
                list.count { it.isVerifiedClaim },
                list.count { it.awaitingReview },
                list.count { it.isVerifiedClaim && (it.kind == FactKind.Personal || it.kind == FactKind.Boundary) },
            )
        }
    }
}

/**
 * Interview-safe personal and professional-identity topics of the Candidate Knowledge Profile. Everything is optional;
 * nothing sensitive (health, religion, politics, family status, age…) is asked. Answers come ONLY from confirmed entries.
 */
object PersonalCategories {
    @Serializable
    data class Category(
        @SerialName("Key") val key: String,
        @SerialName("Section") val section: String,
        @SerialName("Label") val label: String,
        @SerialName("Prompt") val prompt: String,
        @SerialName("Boundary") val boundary: Boolean = false,
    )

    val all: List<Category> = listOf(
        Category("hobby", "personal", "Hobbies", "What are your hobbies?"),
        Category("outside_work", "personal", "Outside work", "What do you enjoy doing outside work?"),
        Category("learning", "personal", "Currently learning", "What do you enjoy learning about at the moment?"),
        Category("motivation", "identity", "What motivates you", "What motivates you professionally?"),
        Category("strength", "identity", "Strength to remember", "What strength do you want interviewers to remember?"),
        Category("weakness", "identity", "Real weakness (and how you manage it)", "What real weakness are you comfortable discussing, and how do you manage it?"),
        Category("work_style", "identity", "Work style", "How do you prefer to work (pace, autonomy, structure)?"),
        Category("team", "identity", "Team you work best in", "What type of team do you work best in?"),
        Category("leadership_style", "identity", "Leadership style", "How would you describe your leadership style?"),
        Category("communication_style", "identity", "Communication with stakeholders", "How do you communicate with stakeholders and handle disagreement?"),
        Category("why_looking", "goals", "Why you are looking", "Why are you looking for your next role?"),
        Category("career_goal", "goals", "Career goals", "What are your career goals for the next few years?"),
        Category("ideal_role", "goals", "Ideal next role / company", "What does your ideal next role, product or company look like?"),
        Category("location", "basics", "Location / time zone", "Where are you based (city, time zone)?"),
        Category("remote", "basics", "Remote / hybrid / relocation", "What is your preference for remote, hybrid or relocation?"),
        Category("availability", "basics", "Availability / notice period", "When could you start (notice period)?"),
        Category("work_authorization", "basics", "Work authorization (optional)", "Optional: anything you want said about work authorization?"),
        Category("pronunciation", "basics", "Name pronunciation", "How should your name be pronounced (optional)?"),
        Category("avoid_topic", "boundaries", "Topics never to speculate about", "Topics the assistant must never speculate about or mention.", boundary = true),
        Category("disallowed_claim", "boundaries", "Claims that must never be made", "Claims that must never be made about you.", boundary = true),
        Category("answer_style", "preferences", "Answer style preferences", "How should suggested answers sound (simple English, more technical, vocabulary to use/avoid)?"),
    )

    fun find(key: String?): Category? = all.firstOrNull { it.key == key }
}

@Serializable
class Story(
    @SerialName("Id") val id: String = "",
    @SerialName("Company") val company: String = "",
    @SerialName("Role") val role: String = "",
    @SerialName("Situation") val situation: String = "",
    @SerialName("Action") val action: String = "",
    @SerialName("Result") val result: String = "",
    @SerialName("Metrics") val metrics: List<String> = listOf(),
    @SerialName("Skills") val skills: List<String> = listOf(),
    @SerialName("QuestionTypes") val questionTypes: List<String> = listOf(),
    /** Confirmed fact ids this story is built from (provenance chain). */
    @SerialName("FactIds") val factIds: List<String> = listOf(),
)

/** A candidate (person being interviewed). One account may own several. */
@Serializable
class CandidateProfileRecord(
    /** Profile schema: 0/1 = résumé facts only (RC1), 2 = Candidate Knowledge Profile with provenance. */
    @SerialName("SchemaVersion") var schemaVersion: Int = 0,
    @SerialName("Id") val id: String = newGuidN(),
    @SerialName("Name") var name: String = "",
    @SerialName("PreferredName") var preferredName: String = "",
    @SerialName("Headline") var headline: String = "",
    @SerialName("Location") var location: String = "",
    @SerialName("TimeZone") var timeZone: String = "",
    @SerialName("InterviewLanguage") var interviewLanguage: String = "auto",
    @SerialName("AnswerLanguage") var answerLanguage: String = "same",
    @SerialName("SpeakingStyle") var speakingStyle: String = "professional",
    @SerialName("AnswerLength") var answerLength: String = "balanced",
    @SerialName("Seniority") var seniority: String = "",
    @SerialName("Notes") var notes: String = "",
    @SerialName("LinkedInText") var linkedInText: String = "",
    @SerialName("PortfolioLinks") var portfolioLinks: MutableList<String> = mutableListOf(),
    @SerialName("Documents") var documents: MutableList<SourceDocument> = mutableListOf(),
    @SerialName("Facts") var facts: MutableList<ProfileFact> = mutableListOf(),
    @SerialName("Stories") var stories: MutableList<Story> = mutableListOf(),
    @SerialName("CreatedUtc") @Serializable(with = CoreDateTimeSerializer::class) val createdUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("UpdatedUtc") @Serializable(with = CoreDateTimeSerializer::class) var updatedUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
) {
    val confirmedFacts: List<ProfileFact> get() = facts.filter { it.isVerifiedClaim }

    /** Facts awaiting review — the same derivation as the list and filters ([FactStats]). */
    val pendingReviewCount: Int get() = FactStats.of(facts).toReview
    val stats: FactStats get() = FactStats.of(facts)

    fun confirmAll(filter: ((ProfileFact) -> Boolean)? = null) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        for (f in facts.filter { it.awaitingReview && (filter?.invoke(it) ?: true) }) f.setConfirmed(true, now)
        updatedUtc = now
    }

    companion object {
        const val CurrentSchemaVersion = 2
    }
}

/** A specific job interview a profile is preparing for. */
@Serializable
class InterviewTarget(
    @SerialName("Id") val id: String = newGuidN(),
    @SerialName("ProfileId") val profileId: String = "",
    @SerialName("JobTitle") var jobTitle: String = "",
    @SerialName("Company") var company: String = "",
    @SerialName("JobDescriptionText") var jobDescriptionText: String = "",
    @SerialName("JobDescriptionUrl") var jobDescriptionUrl: String = "",
    @SerialName("InterviewType") var interviewType: InterviewType = InterviewType.Unknown,
    @SerialName("InterviewAtUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var interviewAtUtc: OffsetDateTime? = null,
    @SerialName("ExpectedLanguage") var expectedLanguage: String = "auto",
    @SerialName("AnswerLanguage") var answerLanguage: String = "same",
    @SerialName("RoleNotes") var roleNotes: String = "",
    @SerialName("CompanyUrls") var companyUrls: MutableList<String> = mutableListOf(),
    @SerialName("Documents") var documents: MutableList<SourceDocument> = mutableListOf(),
    @SerialName("Preparation") var preparation: PreparationState = PreparationState.NotPrepared,
    @SerialName("PreparedUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var preparedUtc: OffsetDateTime? = null,
    @SerialName("PreparationError") var preparationError: String? = null,
    @SerialName("CreatedUtc") @Serializable(with = CoreDateTimeSerializer::class) val createdUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("UpdatedUtc") @Serializable(with = CoreDateTimeSerializer::class) var updatedUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
) {
    val displayName: String get() = if (company.isBlank()) jobTitle else "$jobTitle · $company"
}

@Serializable
class RequirementMatch(
    @SerialName("Requirement") val requirement: String = "",
    @SerialName("FactIds") val factIds: List<String> = listOf(),
    @SerialName("Score") val score: Double = 0.0,
    @SerialName("IsGap") val isGap: Boolean = false,
)

/** Per-target preparation output. Converted to a KnowledgeBase for the live engine. */
@Serializable
class PreparedPack(
    @SerialName("SchemaVersion") val schemaVersion: Int = 1,
    @SerialName("GeneratorVersion") val generatorVersion: String = "",
    @SerialName("ProfileId") val profileId: String = "",
    @SerialName("TargetId") val targetId: String = "",
    @SerialName("CreatedUtc") @Serializable(with = CoreDateTimeSerializer::class) val createdUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("Context") val context: TargetContext = TargetContext(),
    @SerialName("Profile") val profile: CandidateProfile = CandidateProfile(),
    @SerialName("Stories") val stories: List<CandidateStory> = listOf(),
    @SerialName("Questions") val questions: List<BankQuestion> = listOf(),
    @SerialName("Snippets") val snippets: List<KnowledgeSnippet> = listOf(),
    @SerialName("MatchMap") val matchMap: List<RequirementMatch> = listOf(),
    @SerialName("Gaps") val gaps: List<String> = listOf(),
    @SerialName("Terminology") val terminology: List<String> = listOf(),
    @SerialName("DifficultQuestions") val difficultQuestions: List<String> = listOf(),
    @SerialName("QuestionsToAsk") val questionsToAsk: List<String> = listOf(),
    @SerialName("TruthPolicy") val truthPolicy: String = "",
    @SerialName("StageLog") val stageLog: List<String> = listOf(),
    @SerialName("Warnings") val warnings: List<String> = listOf(),
) {
    fun toKnowledgeBase(): KnowledgeBase {
        context.employers = profile.experience.map { it.company }
        return KnowledgeBase(
            profile = profile, stories = stories, questions = questions, snippets = snippets,
            systemPromptTemplate = PromptTemplates.LiveAnswer, context = context, directory = "",
        )
    }
}

// ---- :core JSON primitives (System.Text.Json DEFAULT options semantics) ----

/**
 * The JSON configuration mirroring System.Text.Json default options as the C# Core uses them
 * (`WorkspaceStore.Json`, ProfileBundle's typed reads): PascalCase wire names (pinned by [SerialName]),
 * defaults and nulls written, unknown properties ignored on read.
 */
object CoreJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/**
 * System.DateTime (UTC): ISO-8601 with the `Z` suffix; the fractional part appears only when non-zero,
 * with up to 7 digits (100 ns ticks) and no trailing zeros — exactly System.Text.Json's rendering.
 * Reading accepts any ISO offset and normalizes to UTC, like System.Text.Json into a UTC DateTime.
 * (Same implementation as `:contracts`' UtcDateTimeSerializer; `:core` depends on nothing, so it is
 * hosted here rather than shared.)
 */
object CoreDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("System.DateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(formatUtc(value))
    override fun deserialize(decoder: Decoder): OffsetDateTime = parseUtc(decoder.decodeString())

    internal fun formatUtc(value: OffsetDateTime): String {
        val utc = value.withOffsetSameInstant(ZoneOffset.UTC)
        val base = utc.format(BASE)
        if (utc.nano == 0) return base + "Z"
        // Ticks are 100 ns units: 7 digits taken from the 9-digit nanosecond fraction, trailing zeros trimmed.
        val ticks = (utc.nano / 100).toString().padStart(7, '0').trimEnd('0')
        return "$base.$ticks" + "Z"
    }

    internal fun parseUtc(text: String): OffsetDateTime {
        val trimmed = text.trim()
        return try {
            OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withOffsetSameInstant(ZoneOffset.UTC)
        } catch (e: RuntimeException) {
            try {
                // No offset (System.Text.Json would read it as an unspecified DateTime): treated as UTC.
                LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC)
            } catch (e2: RuntimeException) {
                OffsetDateTime.parse(trimmed.replace("Z", "+00:00"), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
            }
        }
    }

    private val BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
}

object CoreDateTimeNullableSerializer : KSerializer<OffsetDateTime?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("System.DateTime?", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(CoreDateTimeSerializer.formatUtc(value))
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime? =
        if (decoder.decodeNotNullMark()) CoreDateTimeSerializer.parseUtc(decoder.decodeString()) else decoder.decodeNull()
}
