package com.interviewassistant.core.knowledge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirror of `InterviewAssistant.Core/Knowledge/KnowledgeModels.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Wire names are the C#'s `[JsonPropertyName]` snake_case names, pinned with [SerialName] exactly as the
 * C# pins them with attributes (this layer's JSON is System.Text.Json with those attributes, both in
 * `KnowledgeBase.Load` and inside the domain layer's `CoreJson` packs). The models are plain classes, not
 * data classes: the C# types have reference equality, and engine code relies on it (e.g. story
 * de-duplication in `ContextRetriever` uses `List.Contains`).
 */

enum class AnswerMode { Verified, Hypothetical, Bridge }

@Serializable
class BankQuestion(
    @SerialName("question_id") val questionId: String = "",
    @SerialName("canonical_question") val canonicalQuestion: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("intent") val intent: String = "",
    @SerialName("keywords") val keywords: List<String> = listOf(),
    @SerialName("semantic_variants") val semanticVariants: List<String> = listOf(),
    @SerialName("answer_mode") val answerModeRaw: String = "HYPOTHETICAL",
    @SerialName("short_bullets") val shortBullets: List<String> = listOf(),
    @SerialName("optional_full_answer") val optionalFullAnswer: String = "",
    @SerialName("candidate_evidence") val candidateEvidence: List<String> = listOf(),
    @SerialName("gap_warning") val gapWarning: String = "",
    @SerialName("technical_notes") val technicalNotes: String = "",
    @SerialName("product_notes") val productNotes: String = "",
    @SerialName("follow_up_questions") val followUpQuestions: List<String> = listOf(),
    /** Exactly 3 Coach Mode keywords (generated if absent). */
    @SerialName("coach_keywords") val coachKeywords: List<String> = listOf(),
    /** One short answer structure, e.g. "Situation → what I did → result". */
    @SerialName("answer_structure") val answerStructure: String = "",
    @SerialName("story_ids") val storyIds: List<String> = listOf(),
    @SerialName("language") val language: String = "en",
    /** Quality confidence of the prepared answer. Below 0.7 it is used as LLM reference, not shown instantly. */
    @SerialName("confidence") val confidence: Double = 1.0,
) {
    /** Story ids used by this answer (falls back to candidate_evidence for older banks). */
    val effectiveStoryIds: List<String> get() = if (storyIds.isNotEmpty()) storyIds else candidateEvidence

    val mode: AnswerMode
        get() = when (answerModeRaw.uppercase()) {
            "VERIFIED" -> AnswerMode.Verified
            "BRIDGE" -> AnswerMode.Bridge
            else -> AnswerMode.Hypothetical
        }
}

@Serializable
class QuestionBankFile(
    @SerialName("questions") val questions: List<BankQuestion> = listOf(),
)

@Serializable
class CandidateStory(
    @SerialName("id") val id: String = "",
    @SerialName("company") val company: String = "",
    @SerialName("role") val role: String = "",
    @SerialName("situation") val situation: String = "",
    @SerialName("action") val action: String = "",
    @SerialName("result") val result: String = "",
    @SerialName("verified_numbers") val verifiedNumbers: List<String> = listOf(),
    @SerialName("skills") val skills: List<String> = listOf(),
    @SerialName("question_types") val questionTypes: List<String> = listOf(),
) {
    fun toEvidenceLine(): String = "[$company, $role] $action Result: $result"
}

@Serializable
class StoriesFile(
    @SerialName("stories") val stories: List<CandidateStory> = listOf(),
)

@Serializable
class Experience(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("company") val company: String = "",
    @SerialName("location") val location: String = "",
    @SerialName("period") val period: String = "",
    @SerialName("achievements") val achievements: List<String> = listOf(),
)

@Serializable
class CandidateProfile(
    @SerialName("name") val name: String = "",
    @SerialName("headline") val headline: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("core_competencies") val coreCompetencies: List<String> = listOf(),
    @SerialName("experience") val experience: List<Experience> = listOf(),
    @SerialName("verified_numbers") val verifiedNumbers: List<String> = listOf(),
    @SerialName("verified_entities") val verifiedEntities: List<String> = listOf(),
    @SerialName("known_gaps") val knownGaps: List<String> = listOf(),
    /** Confirmed education facts (degree, institution) — empty unless the user confirmed them. */
    @SerialName("education") val education: List<EducationEntry> = listOf(),
    /** Confirmed spoken languages with level. */
    @SerialName("languages") val languages: List<LanguageEntry> = listOf(),
    @SerialName("preferred_name") val preferredName: String = "",
    /** Confirmed personal / professional-identity answers (hobbies, motivation, team preference…). Never invented. */
    @SerialName("personal") val personal: List<PersonalEntry> = listOf(),
    /** Topics and claims the user forbade ("never mention X"). */
    @SerialName("boundaries") val boundaries: List<String> = listOf(),
) {
    fun personalFor(category: String): List<String> = personal.filter { it.category == category }.map { it.text }
}

@Serializable
class PersonalEntry(
    @SerialName("category") val category: String = "",
    @SerialName("text") val text: String = "",
)

@Serializable
class EducationEntry(
    @SerialName("degree") val degree: String = "",
    @SerialName("school") val school: String = "",
    @SerialName("usage_note") val usageNote: String = "",
) {
    override fun toString(): String =
        listOf(degree, school).filter { it.isNotEmpty() }.joinToString(", ") + (if (usageNote.isNotEmpty()) " ($usageNote)" else "")
}

@Serializable
class LanguageEntry(
    @SerialName("language") val language: String = "",
    @SerialName("level") val level: String = "",
) {
    override fun toString(): String = if (level.isNotEmpty()) "$language $level" else language
}
