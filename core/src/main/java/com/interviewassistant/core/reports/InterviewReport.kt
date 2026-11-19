package com.interviewassistant.core.reports

import com.interviewassistant.core.diagnostics.SessionMetrics
import com.interviewassistant.core.domain.CoreDateTimeSerializer
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.intelligence.PromptEchoDetector
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.intelligence.UtteranceGate
import com.interviewassistant.core.intelligence.UtteranceKind
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.live.InterruptionEvent
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.live.SessionMemory
import java.time.OffsetDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Mirror of `InterviewAssistant.Core/Reports/InterviewReport.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations: the report types are `@Serializable` classes with the System.Text.Json
 * property names kept as `@SerialName`s and the shared [CoreJson] conventions; the C# value tuples in
 * `StoriesRecommended`/`MetricsRecommended` become [StringIntPair], whose `Item1`/`Item2` serial names
 * keep the JSON bytes identical to System.Text.Json's ValueTuple rendering. `DateTime` becomes
 * [OffsetDateTime] with the shared UTC serializer. The indented writer uses a two-space indent like
 * System.Text.Json's `WriteIndented`.
 */
@Serializable
class ReportQuestion(
    @SerialName("Index") val index: Int = 0,
    @SerialName("OriginalWording") val originalWording: String = "",
    @SerialName("DetectedLanguage") val detectedLanguage: String = "und",
    @SerialName("AnswerLanguage") val answerLanguage: String = "en",
    @SerialName("Category") val category: String = "",
    @SerialName("Mode") val mode: String = "",
    @SerialName("SuggestionsShown") val suggestionsShown: List<String> = listOf(),
    @SerialName("Presentation") val presentation: String = "Answer",
    @SerialName("Source") val source: String = "",
    @SerialName("FirstBulletMs") val firstBulletMs: Long = 0,
    @SerialName("FollowUpOf") val followUpOf: Int? = null,
    @SerialName("ReferencesEarlier") val referencesEarlier: Boolean = false,
    /** Interviewer context before the question (summary). */
    @SerialName("Context") val context: String = "",
    @SerialName("Typed") val typed: Boolean = false,
    /** Set for a question listed under [InterviewReport.uncertainQuestions]: why its audio is doubtful. */
    @SerialName("UncertainReason") val uncertainReason: String = "",
)

/** One `(Label, Count)` pair — serializes as `{"Item1":…,"Item2":…}` exactly like a System.Text.Json ValueTuple. */
@Serializable
data class StringIntPair(
    @SerialName("Item1") val item1: String,
    @SerialName("Item2") val item2: Int,
)

/**
 * Post-interview report. Observations (what the app saw) are kept separate from inferences (labelled).
 * With the candidate microphone OFF (default) the report never claims to know what the candidate said.
 */
@Serializable
class InterviewReport(
    @SerialName("SessionId") val sessionId: String = "",
    @SerialName("ProfileLabel") val profileLabel: String = "",
    @SerialName("TargetLabel") val targetLabel: String = "",
    @SerialName("StartedUtc") @Serializable(with = CoreDateTimeSerializer::class) val startedUtc: OffsetDateTime,
    @SerialName("EndedUtc") @Serializable(with = CoreDateTimeSerializer::class) val endedUtc: OffsetDateTime,
    @SerialName("ReportLanguage") val reportLanguage: String = "en",
    @SerialName("CandidateMicrophoneEnabled") val candidateMicrophoneEnabled: Boolean = false,
    @SerialName("Questions") val questions: List<ReportQuestion> = listOf(),
    @SerialName("CategoryCounts") val categoryCounts: Map<String, Int> = mapOf(),
    @SerialName("LanguagesUsed") val languagesUsed: List<String> = listOf(),
    @SerialName("DifficultyAreas") val difficultyAreas: List<String> = listOf(),
    @SerialName("StoriesRecommended") val storiesRecommended: List<StringIntPair> = listOf(),
    @SerialName("MetricsRecommended") val metricsRecommended: List<StringIntPair> = listOf(),
    @SerialName("RepeatedTopics") val repeatedTopics: List<String> = listOf(),
    @SerialName("TopicsNotCovered") val topicsNotCovered: List<String> = listOf(),
    @SerialName("GapQuestions") val gapQuestions: List<String> = listOf(),
    @SerialName("Interruptions") val interruptions: List<InterruptionEvent> = listOf(),
    @SerialName("LatencyP50Ms") val latencyP50Ms: Long = -1,
    @SerialName("LatencyP95Ms") val latencyP95Ms: Long = -1,
    @SerialName("TranscriptionIssues") val transcriptionIssues: List<String> = listOf(),
    @SerialName("StrengthsOfPreparation") val strengthsOfPreparation: List<String> = listOf(),
    @SerialName("PreparationWeaknesses") val preparationWeaknesses: List<String> = listOf(),
    @SerialName("FollowUpPreparation") val followUpPreparation: List<String> = listOf(),
    @SerialName("ThankYouThemes") val thankYouThemes: List<String> = listOf(),
    @SerialName("UnusedRelevantStories") val unusedRelevantStories: List<String> = listOf(),
    /**
     * Heard questions whose recorded audio evidence fails the acoustic checks on re-validation (e.g. a non-speech sound
     * that slipped through live). Listed for review — never silently dropped — and excluded from every statistic.
     */
    @SerialName("UncertainQuestions") val uncertainQuestions: List<ReportQuestion> = listOf(),
    /** Turns that were not interview questions (non-speech, prompt echo, logistics, remarks to others) — counts only. */
    @SerialName("ExcludedTurns") val excludedTurns: Int = 0,
) {
    fun toJson(): String = ReportSerializer.toJson(this)
}

@OptIn(ExperimentalSerializationApi::class)
private val reportJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

object ReportBuilder {

    fun build(
        memoryIn: SessionMemory,
        kb: KnowledgeBase,
        metrics: SessionMetrics?,
        sessionId: String,
        startedUtc: OffsetDateTime,
        endedUtc: OffsetDateTime,
        reportLanguage: String = "en",
        pack: PreparedPack? = null,
        reconnects: Int = 0,
        duplicatesSuppressed: Int = 0,
        profileLabel: String = "",
        targetLabel: String = "",
    ): InterviewReport {
        var memory = memoryIn
        // Defence in depth: the engine never records suppressed turns, but a record that is empty or reads like the
        // recognizer's own vocabulary prompt must not count as a question or skew any statistic below.
        val echo = PromptEchoDetector(kb.context.buildTranscriptionPrompt(), kb.context.transcriptionTerms())
        var valid = memory.questions.filter { q -> q.original.trim().isNotEmpty() && (q.typed || !echo.check(q.original).isEcho) }
        val invalid = memory.questions.size - valid.size
        // Second line of defence for heard questions: re-check the recorded audio evidence with the live gate's acoustic
        // rules. A question that fails is moved to its own, clearly labelled list instead of disappearing.
        val gate = UtteranceGate.`for`(kb)
        val uncertain = mutableListOf<Pair<QuestionRecord, String>>()
        for (q in valid) {
            val heard = q.heard
            if (q.typed || heard == null) continue // typed, or recorded before evidence was kept: nothing to re-check
            val v = gate.evaluate(q.original, heard.toEvidence())
            val reason = when (v.kind) {
                UtteranceKind.NonSpeech, UtteranceKind.ShortAcousticEvent -> "why_nonspeech"
                UtteranceKind.ImplausibleTranscript -> "why_implausible"
                UtteranceKind.ContextHallucination -> "why_context"
                else -> null
            }
            if (reason != null) uncertain.add(q to reason)
        }
        if (uncertain.isNotEmpty()) {
            val doubtfulRecords = uncertain.map { it.first }
            valid = valid.filter { q -> q !in doubtfulRecords }
        }
        if (invalid > 0 || uncertain.isNotEmpty()) memory = filtered(memory, valid)
        // An "interruption" is measured when the next question starts: a doubtful question cannot count as one either.
        if (uncertain.isNotEmpty()) {
            val doubtfulIdx = uncertain.map { it.first.index }.toSet()
            memory.interruptions.removeAll { i -> (i.afterQuestionIndex + 1) in doubtfulIdx }
        }
        fun l(key: String): String = ReportLabels.get(reportLanguage, key)
        fun toReport(q: QuestionRecord, reason: String = ""): ReportQuestion = ReportQuestion(
            index = q.index, originalWording = q.original, detectedLanguage = q.detectedLanguage, answerLanguage = q.answerLanguage,
            category = q.category, mode = q.mode,
            suggestionsShown = if (q.answerBullets.isNotEmpty()) q.answerBullets.toList() else q.coachKeywords.toList(),
            presentation = q.presentation, source = q.source,
            firstBulletMs = q.firstBulletLatencyMs, followUpOf = q.followUpOf, referencesEarlier = q.referencesEarlierAnswer,
            context = q.context, typed = q.typed,
            uncertainReason = if (reason.isNotEmpty()) l(reason) else "",
        )
        val qs = memory.questions.map { q -> toReport(q) }
        val doubtful = uncertain.map { u -> toReport(u.first, u.second) }

        val storyLabel = kb.stories.associate { s -> s.id to "${s.company}: ${short(s.action, 10)}" }
        val stories = memory.storyUse.entries.sortedByDescending { it.value }
            .map { kv -> StringIntPair(storyLabel[kv.key] ?: kv.key, kv.value) }
        val askedText = memory.questions.joinToString(" ") { it.original }.lowercase()
        val askedTokens = TextNormalizer.tokenize(askedText).toSet()

        val notCovered = mutableListOf<String>()
        if (pack != null) {
            for (m in pack.matchMap) {
                val key = TextNormalizer.tokenize(m.requirement).filter { it.length > 4 }.take(4)
                if (key.isNotEmpty() && key.none { it in askedTokens }) notCovered.add(short(m.requirement, 12))
            }
        }

        val gapQs = memory.questions.filter { q -> q.mode == "Bridge" || q.category == "GAP_EXPERIENCE" }.map { it.original }
        val difficult = mutableListOf<String>()
        fun r(key: String, vararg a: Any?): String = Loc.format(reportLanguage, key, *a)
        difficult.addAll(gapQs.map { g -> r("rpt_experience_gap", short(g, 14)) })
        difficult.addAll(memory.questions.filter { q -> q.source == "Error" || q.source == "CacheFallback" }.map { q -> r("rpt_no_ai_answer", short(q.original, 12)) })
        difficult.addAll(memory.questions.filter { q -> q.referencesEarlierAnswer }.map { q -> r("rpt_referred_back", short(q.original, 12)) })

        // Latency statistics cover heard questions only (a typed question has no speech end to measure from).
        val latencies = memory.questions.filter { q -> !q.typed }.map { it.firstBulletLatencyMs }.filter { it >= 0 }
        val issues = mutableListOf<String>()
        val undetected = memory.questions.count { q -> q.detectedLanguage == "und" }
        if (undetected > 0) issues.add(r("rpt_language_undetected", undetected))
        val veryShort = memory.questions.count { q -> TextNormalizer.wordCount(q.original) <= 3 }
        if (veryShort > 0) issues.add(r("rpt_very_short", veryShort))
        if (reconnects > 0) issues.add(r("rpt_reconnects", reconnects))
        if (duplicatesSuppressed > 0) issues.add(r("rpt_duplicates", duplicatesSuppressed))

        val prepared = memory.questions.count { q -> q.source == "Cache" }
        val strengths = mutableListOf<String>()
        if (memory.questions.isNotEmpty()) strengths.add(r("rpt_prepared_matches", prepared, memory.questions.size))
        if (stories.isNotEmpty()) strengths.add(r("rpt_stories_suggested", stories.size))
        if (memory.questions.isNotEmpty() && (memory.storyUse.values.maxOrNull() ?: 0) <= 2) strengths.add(r("rpt_low_repetition"))
        val weaknesses = mutableListOf<String>()
        val unprepared = memory.questions.filter { q -> q.matchedQuestionId == null || q.source == "Llm" }
            .groupBy { it.category }.filter { it.value.size >= 2 }.map { it.key.lowercase() }
        if (unprepared.isNotEmpty()) weaknesses.add(r("rpt_needed_ai", unprepared.joinToString(", ")))
        if (gapQs.isNotEmpty()) weaknesses.add(r("rpt_gap_count", gapQs.size))
        val overused = memory.storyUse.entries.filter { it.value >= 3 }.map { kv -> storyLabel[kv.key] ?: kv.key }
        if (overused.isNotEmpty()) weaknesses.add(r("rpt_overused", overused.joinToString("; ")))

        val followUp = gapQs.map { g -> r("rpt_prepare_bridge", short(g, 12)) } + notCovered.take(5).map { n -> r("rpt_not_asked", n) }
        val themes = memory.topicCounts.entries.sortedByDescending { it.value }.take(3)
            .map { kv -> r("rpt_topic_discussed", kv.key.lowercase().replace('_', ' ')) }.toMutableList()
        if (kb.context.companyName.isNotEmpty()) themes.add(0, r("rpt_thank_them", kb.context.roleTitle, kb.context.companyName))
        if (memory.companyTopics.isNotEmpty()) themes.add(r("rpt_reference_learned", memory.companyTopics.take(3).joinToString(", ")))
        val unused = kb.stories.filter { s -> s.id !in memory.storyUse }.take(5).map { s -> storyLabel.getValue(s.id) }

        return InterviewReport(
            sessionId = sessionId, profileLabel = profileLabel, targetLabel = targetLabel, startedUtc = startedUtc, endedUtc = endedUtc,
            reportLanguage = reportLanguage,
            candidateMicrophoneEnabled = false, questions = qs,
            categoryCounts = memory.questions.groupBy { it.category }.mapValues { it.value.size },
            languagesUsed = memory.questions.map { it.detectedLanguage }.filter { it != "und" }.distinct(),
            difficultyAreas = difficult, storiesRecommended = stories,
            metricsRecommended = memory.metricUse.entries.sortedByDescending { it.value }.map { kv -> StringIntPair(kv.key, kv.value) },
            repeatedTopics = memory.topicCounts.entries.filter { it.value >= 3 }.map { kv -> "${kv.key.lowercase().replace('_', ' ')} ×${kv.value}" },
            topicsNotCovered = notCovered, gapQuestions = gapQs, interruptions = memory.interruptions.toList(),
            latencyP50Ms = SessionMetrics.percentile(latencies, 50.0), latencyP95Ms = SessionMetrics.percentile(latencies, 95.0),
            transcriptionIssues = issues, strengthsOfPreparation = strengths, preparationWeaknesses = weaknesses,
            followUpPreparation = followUp,
            thankYouThemes = themes, unusedRelevantStories = unused,
            excludedTurns = (metrics?.totalSuppressed ?: 0) + invalid, uncertainQuestions = doubtful,
        )
    }

    /** A copy of the memory restricted to valid records, with story/metric/topic statistics recomputed from them. */
    private fun filtered(m: SessionMemory, valid: List<QuestionRecord>): SessionMemory {
        val f = SessionMemory(
            questions = valid.toMutableList(), interruptions = m.interruptions.toMutableList(),
            companyTopics = m.companyTopics.toMutableSet(), rollingSummary = m.rollingSummary,
        )
        for (q in valid) {
            for (s in q.storyIds) f.storyUse[s] = (f.storyUse[s] ?: 0) + 1
            for (x in q.metrics) f.metricUse[x] = (f.metricUse[x] ?: 0) + 1
            if (q.category.isNotEmpty()) f.topicCounts[q.category] = (f.topicCounts[q.category] ?: 0) + 1
        }
        return f
    }

    private fun short(s: String, words: Int): String {
        val w = s.split(' ').filter { it.isNotEmpty() }
        return if (w.size <= words) s.trimEnd('.') else w.take(words).joinToString(" ") + "…"
    }
}

/** Stored report round-trip (workspace persistence). Unreadable/corrupt JSON yields null, never an exception. */
object ReportSerializer {
    fun toJson(r: InterviewReport): String = reportJson.encodeToString(InterviewReport.serializer(), r)

    fun fromJson(json: String): InterviewReport? {
        return try {
            if (json.isBlank()) null else reportJson.decodeFromString(InterviewReport.serializer(), json)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
