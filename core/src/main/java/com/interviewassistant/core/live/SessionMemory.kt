package com.interviewassistant.core.live

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.domain.CoreDateTimeSerializer
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.AcousticSupport
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.BankQuestion
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

/**
 * Mirror of `InterviewAssistant.Core/Live/SessionMemory.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

@Serializable
class QuestionRecord(
    @SerialName("Index") var index: Int = 0,
    @SerialName("Original") var original: String = "",
    @SerialName("DetectedLanguage") var detectedLanguage: String = "und",
    @SerialName("AnswerLanguage") var answerLanguage: String = "en",
    @SerialName("Category") var category: String = "",
    @SerialName("Mode") var mode: String = "",
    /** Language-independent intent signature: category + canonical bank id (if matched) + entities. */
    @SerialName("IntentKey") var intentKey: String = "",
    @SerialName("MatchedQuestionId") var matchedQuestionId: String? = null,
    @SerialName("Entities") var entities: MutableList<String> = mutableListOf(),
    @SerialName("AnswerBullets") var answerBullets: MutableList<String> = mutableListOf(),
    @SerialName("CoachKeywords") var coachKeywords: MutableList<String> = mutableListOf(),
    @SerialName("StoryIds") var storyIds: MutableList<String> = mutableListOf(),
    @SerialName("Metrics") var metrics: MutableList<String> = mutableListOf(),
    @SerialName("FollowUpOf") var followUpOf: Int? = null,
    @SerialName("ReferencesEarlierAnswer") var referencesEarlierAnswer: Boolean = false,
    @SerialName("Source") var source: String = "",
    @SerialName("Presentation") var presentation: String = "Answer",
    @SerialName("FirstBulletLatencyMs") var firstBulletLatencyMs: Long = -1,
    @SerialName("AtUtc") @Serializable(with = CoreDateTimeSerializer::class) var atUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("Truncated") var truncated: Boolean = false,
    /** Interviewer context that preceded the core question (bounded summary). */
    @SerialName("Context") var context: String = "",
    /** Typed by the user (not heard from the interviewer). */
    @SerialName("Typed") var typed: Boolean = false,
    /** Answer variant currently shown for this question (Balanced unless the user asked for another). */
    @SerialName("Style") var style: String = "Balanced",
    @SerialName("VariantCount") var variantCount: Int = 0,
    @SerialName("Confidence") var confidence: String = "",
    @SerialName("QualityFlags") var qualityFlags: MutableList<String> = mutableListOf(),
    /**
     * Acoustic provenance of a heard question (null for typed questions and records from builds before 2.1.0-rc.4):
     * sizes and ratios of the audio the turn was heard in — never audio, never a voiceprint.
     */
    @SerialName("Heard") var heard: HeardEvidence? = null,
)

/**
 * Serializable summary of [SpeechEvidence] kept with a heard question, so the report can re-check that
 * a recorded question was carried by a voice (defence in depth against non-speech that slipped through live).
 */
@Serializable
class HeardEvidence(
    @SerialName("FramesObserved") var framesObserved: Int = 0,
    @SerialName("SpanMs") var spanMs: Int = 0,
    @SerialName("WindowMs") var windowMs: Int = 0,
    @SerialName("ActiveMs") var activeMs: Int = 0,
    @SerialName("VoicedMs") var voicedMs: Int = 0,
    @SerialName("TonalMs") var tonalMs: Int = 0,
    @SerialName("PitchPairs") var pitchPairs: Int = 0,
    @SerialName("SteadyPairs") var steadyPairs: Int = 0,
    @SerialName("JumpPairs") var jumpPairs: Int = 0,
    /** Pitch-run counts (2.1.0-rc.5+; 0 in older records, which then keep the older acoustic rule). */
    @SerialName("TrackedHops") var trackedHops: Int = 0,
    @SerialName("RunHops") var runHops: Int = 0,
    @SerialName("FlatRunHops") var flatRunHops: Int = 0,
    /** Hops in continuous voiced stretches of ≥ 160 ms (-1 = not measured: records written before this count existed). */
    @SerialName("StretchHops") var stretchHops: Int = -1,
    /** Acoustic support the live gate assigned (Strong, Weak, Unknown). */
    @SerialName("Support") var support: String = "",
) {
    fun toEvidence(): SpeechEvidence =
        SpeechEvidence(framesObserved, spanMs, activeMs, voicedMs, tonalMs, pitchPairs, steadyPairs, jumpPairs, windowMs, trackedHops, runHops, flatRunHops, stretchHops)

    companion object {
        fun from(e: SpeechEvidence, support: AcousticSupport): HeardEvidence = HeardEvidence(
            framesObserved = e.framesObserved,
            spanMs = e.spanMs,
            windowMs = e.windowMs,
            activeMs = e.activeMs,
            voicedMs = e.voicedMs,
            tonalMs = e.tonalMs,
            pitchPairs = e.pitchPairs,
            steadyPairs = e.steadyPairs,
            jumpPairs = e.jumpPairs,
            support = support.name,
            trackedHops = e.trackedHops,
            runHops = e.runHops,
            flatRunHops = e.flatRunHops,
            stretchHops = e.stretchHops,
        )
    }
}

@Serializable
class InterruptionEvent(
    @SerialName("AfterQuestionIndex") var afterQuestionIndex: Int = 0,
    @SerialName("Confidence") var confidence: Double = 0.0,
    @SerialName("ElapsedMs") var elapsedMs: Long = 0,
    @SerialName("ExpectedMs") var expectedMs: Long = 0,
    @SerialName("Note") var note: String = "",
    @SerialName("AtUtc") @Serializable(with = CoreDateTimeSerializer::class) var atUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
)

/**
 * Bounded memory of the whole interview as ONE conversation. Language-independent: intent keys, categories,
 * entities and story ids are stored separately from the original wording, so an English question and a German
 * follow-up share context. Old records are compacted (answer text dropped) instead of growing forever, and a
 * rolling summary keeps the prompt size constant. Serializable so it survives disconnects (encrypted by the store).
 */
@Serializable
class SessionMemory(
    @SerialName("Questions") var questions: MutableList<QuestionRecord> = mutableListOf(),
    @SerialName("Interruptions") var interruptions: MutableList<InterruptionEvent> = mutableListOf(),
    @SerialName("StoryUse") var storyUse: MutableMap<String, Int> = mutableMapOf(),
    @SerialName("StoryLastUsedAt") var storyLastUsedAt: MutableMap<String, Int> = mutableMapOf(),
    @SerialName("MetricUse") var metricUse: MutableMap<String, Int> = mutableMapOf(),
    @SerialName("TopicCounts") var topicCounts: MutableMap<String, Int> = mutableMapOf(),
    @SerialName("CompanyTopics") var companyTopics: MutableSet<String> = mutableSetOf(),
    @SerialName("UnresolvedTopics") var unresolvedTopics: MutableList<String> = mutableListOf(),
    @SerialName("RollingSummary") var rollingSummary: String = "",
    @SerialName("MaxDetailedRecords") var maxDetailedRecords: Int = 12,
    @SerialName("MaxRecords") var maxRecords: Int = 400,
) {
    val last: QuestionRecord? get() = questions.lastOrNull()

    fun add(r: QuestionRecord, storyMetrics: Iterable<String>? = null): QuestionRecord {
        r.index = if (questions.isEmpty()) 1 else questions.last().index + 1
        questions.add(r)
        for (s in r.storyIds) {
            storyUse[s] = (storyUse[s] ?: 0) + 1
            storyLastUsedAt[s] = r.index
        }
        for (m in (r.metrics + (storyMetrics ?: emptyList())).distinct()) metricUse[m] = (metricUse[m] ?: 0) + 1
        if (r.category.isNotEmpty()) topicCounts[r.category] = (topicCounts[r.category] ?: 0) + 1
        compact()
        return r
    }

    /** Records answer content after it has been shown (bullets arrive after the record is created). */
    fun completeAnswer(r: QuestionRecord, bullets: Iterable<String>, storyIds: Iterable<String>, metricsInAnswer: Iterable<String>) {
        r.answerBullets = bullets.toMutableList()
        val newStories = storyIds.distinct().filter { it !in r.storyIds }
        for (s in newStories) {
            r.storyIds.add(s)
            storyUse[s] = (storyUse[s] ?: 0) + 1
            storyLastUsedAt[s] = r.index
        }
        val newMetrics = metricsInAnswer.distinct().filter { it !in r.metrics }
        for (m in newMetrics) {
            r.metrics.add(m)
            metricUse[m] = (metricUse[m] ?: 0) + 1
        }
        compact()
    }

    /** Stories used within the last [window] questions (recency) or more than once overall. */
    fun recentlyUsedStories(window: Int = 3): Set<String> {
        val cut = (last?.index ?: 0) - window
        return storyLastUsedAt.filter { (key, value) -> value > cut || (storyUse[key] ?: 0) >= 2 }.keys.toHashSet()
    }

    fun overusedMetrics(threshold: Int = 2): List<String> = metricUse.filter { it.value >= threshold }.keys.toList()

    /** Penalty for re-using a story: higher when used recently/often. Used to rank evidence. */
    fun storyPenalty(storyId: String): Double {
        val n = storyUse[storyId] ?: return 0.0
        val age = (last?.index ?: 0) - (storyLastUsedAt[storyId] ?: 0)
        return n * 1.0 + (if (age <= 2) 2.0 else if (age <= 5) 1.0 else 0.2)
    }

    /** Compact, bounded context for the prompt: rolling summary + last few exchanges + anti-repetition hints. */
    fun buildPromptContext(recent: Int = 4): String {
        val sb = StringBuilder()
        if (rollingSummary.isNotEmpty()) sb.append("Earlier in this interview: " + rollingSummary + "\n")
        for (q in questions.takeLast(recent)) {
            sb.append("Q${q.index}${if (q.detectedLanguage != "und") " [${q.detectedLanguage}]" else ""}: ${trim(q.original, 30)}")
            if (q.answerBullets.isNotEmpty()) sb.append(" → suggested: " + trim(q.answerBullets.joinToString(" "), 35))
            sb.append("\n")
        }
        val used = storyUse.filter { it.value > 0 }.keys.toList()
        if (used.isNotEmpty()) sb.append("Stories already used (prefer a different verified story if one fits): " + used.joinToString(", ") + "\n")
        val metrics = overusedMetrics(1)
        if (metrics.isNotEmpty()) sb.append("Metrics already mentioned (do not repeat unless asked): " + metrics.take(12).joinToString(", ") + "\n")
        if (interruptions.isNotEmpty()) sb.append("Interviewer pace: ${interruptions.size} fast-follow/interruption events — keep answers tight.\n")
        return sb.toString().trimEnd()
    }

    private fun compact() {
        val detailed = questions.filter { !it.truncated }
        if (detailed.size > maxDetailedRecords) {
            for (q in detailed.take(detailed.size - maxDetailedRecords)) {
                q.truncated = true
                q.answerBullets = q.answerBullets.take(1).map { trim(it, 12) }.toMutableList()
            }
            val topics = topicCounts.entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key.lowercase()}×${it.value}" }
            rollingSummary = "${questions.count { it.truncated }} earlier questions; topics: $topics" +
                (if (companyTopics.isNotEmpty()) "; company topics: " + companyTopics.take(8).joinToString(", ") else "") + "."
        }
        if (questions.size > maxRecords) questions.subList(0, questions.size - maxRecords).clear()
        if (interruptions.size > 200) interruptions.subList(0, interruptions.size - 200).clear()
    }

    fun toJson(): String = CoreJson.instance.encodeToString(serializer(), this)

    companion object {
        private val AlreadyMentioned = dotNetRegex(
            "(you (already|just) (mentioned|said|told|talked about)|as you (said|mentioned)|you mentioned (that|earlier)|you've mentioned|apart from|other than|besides (that|the)|another example|a different example" +
                "|sie haben (bereits|schon) (erwähnt|gesagt)|ein anderes beispiel|vous avez (déjà )?(mentionné|dit|parlé)|un autre exemple|ya (mencion|dij)|otro ejemplo" +
                "|voc[êe] j[áa] (mencionou|disse)|outro exemplo|hai gi[àa] (menzionato|detto)|un altro esempio|قبلاً (گفتید|اشاره کردید)|مثال دیگری|ذكرت|مثال آخر|你(已经)?提到|另一个例子|आपने (पहले )?बताया|दूसरा उदाहरण)",
            ignoreCase = true,
        )

        fun referencesEarlier(question: String): Boolean = AlreadyMentioned.containsMatchIn(question)

        fun fromJson(json: String): SessionMemory = CoreJson.instance.decodeFromString(serializer().nullable, json) ?: SessionMemory()

        private fun trim(s: String, words: Int): String {
            val w = s.split(' ').filter { it.isNotEmpty() }
            return if (w.size <= words) s else w.take(words).joinToString(" ") + "…"
        }

        /** Language-independent intent key (bank id when matched, else category + sorted content tokens). */
        fun intentKeyFor(question: String, category: String, match: BankQuestion?): String =
            if (match != null) "Q:" + match.questionId else category + ":" + TextNormalizer.fingerprint(question)
    }
}
