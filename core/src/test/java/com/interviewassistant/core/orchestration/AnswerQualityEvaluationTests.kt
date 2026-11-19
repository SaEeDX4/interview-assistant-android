package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.EvidenceIndex
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.TextNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/AnswerQualityEvaluationTests.cs` — deferred BY NAME from Package B.
 *
 * Offline answer-quality evaluation (no API key). A deliberately adversarial model stub answers a held-out question set
 * with the failure modes a real model produces: unsupported "I built…" claims, invented metrics, repeated bullets and
 * over-long answers, mixed with genuinely confirmed experience. The suite measures what the candidate would actually
 * see after the engine's planner, validator and quality gate: unsupported-claim rate, fabricated-metric rate,
 * verified-fact retention, answer length, relevance of the prompt to the asked question, story repetition and
 * personal-question honesty. The live-model counterpart is [LiveEvaluationTests] (needs OPENAI_API_KEY).
 * (The C# `KbWithPersonal` helper is the same construction as Rc2's WithPersonal; the shared mirror is reused.)
 */
class AnswerQualityEvaluationTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private const val SupportedClaim = "• I led blockchain operations at Arzif Crypto Exchange, so I know the operational side well.\n"
        private const val UnsupportedClaim = "• I built a closed-loop token ledger with double-entry accounting for a bank.\n"
        private const val FabricatedMetric = "• That work increased retention by 37% within one quarter.\n"
        private const val Hypothetical = "• I'd agree the goal with stakeholders, then ship in small, measurable steps.\n"

        // Held-out: phrasings that are NOT in the prepared bank verbatim (cache disabled so every answer is generated).
        private val Professional = listOf(
            "Have you personally built a financial ledger for a product like ours?",
            "Tell me how your blockchain operations background would help this team.",
            "How would you design a referral programme that resists fraud?",
            "What would you change first in our rewards app?",
            "How do you decide what goes into the next sprint when everything feels urgent?",
            "Walk me through how you would launch the token in a new market.",
            "How would you explain double-entry bookkeeping to a marketing colleague?",
            "Have you ever run a MiCAR licensing process yourself?",
            "Which metrics would you put on the dashboard for the first quarter?",
            "How would you handle a compliance officer who blocks your roadmap item?",
            "What risks do you see in letting users swap rewards for tokens?",
            "Describe how you would onboard VIP clients in the first month.",
        )

        private val Personal = listOf("What are your hobbies?", "What motivates you?", "What do you do outside work?", "What kind of team do you prefer?")
    }

    private suspend fun ask(s: InterviewSimulator, q: String) {
        s.say(q)
        s.run(q.split(' ').size * s.msPerWord + 4500)
        s.settle()
        s.run(15_000)
    }

    @Test
    fun adversarialModelOutput_IsCleanedBeforeTheCandidateSeesIt() = runBlocking {
        val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false; useFastCache = false })
        s.p.responder = { arrayOf("MODE: HYPOTHETICAL\n", SupportedClaim, UnsupportedClaim, FabricatedMetric, Hypothetical, Hypothetical) }
        s.start()
        for (q in Professional) ask(s, q)

        val kb = Fixtures.testKnowledge()
        val validator = FactValidator(kb.profile, kb.context, EvidenceIndex.`for`(kb))
        val answers = s.e.history
        val bullets = answers.flatMap { a -> a.bullets }
        val unsupported = bullets.count { b -> validator.hasUnsupportedClaim(b) }
        val fabricated = bullets.count { b -> b.contains("37%") }
        val keptVerified = answers.count { a -> a.bullets.any { b -> b.contains("I led blockchain operations") } }
        val softenedVerified = bullets.count { b -> b.contains("I'd lead blockchain operations") }
        val duplicates = answers.count { a -> a.bullets.size != a.bullets.map { b -> b.lowercase() }.distinct().size }
        val words = bullets.map { b -> TextNormalizer.wordCount(b) }
        val relevant = s.p.calls.zip(Professional).count { (call, q) -> call.last().content.contains(q.trimEnd('?'), ignoreCase = true) }
        val storyUse = s.e.memory.questions.flatMap { q -> q.storyIds }.groupingBy { x -> x }.eachCount().values.maxOrNull() ?: 0

        assertEquals(Professional.size, answers.size)
        assertEquals(0, unsupported) // every unsupported "I built" became "I'd…"
        assertEquals(0, fabricated) // invented past metrics never reach the screen
        assertEquals(answers.size, keptVerified) // confirmed experience stays confirmed
        assertEquals(0, softenedVerified)
        assertEquals(0, duplicates)
        for (a in answers) assertTrue("bullets ${a.bullets.size}", a.bullets.size in 2..3) // default format: 3 short bullets, never more
        assertTrue("longest bullet ${words.maxOrNull()} words", (words.maxOrNull() ?: 0) <= 30)
        assertEquals(Professional.size, relevant)
        // (storyUse is reported by the C# via test output only; computed above for parity.)
        assertTrue(storyUse >= 0)
    }

    @Test
    fun personalQuestions_UseConfirmedProfileAnswersOrHonestGuidance_NeverInventions() = runBlocking {
        // With confirmed interview-profile answers: the prompt carries exactly those facts and the model is told to use only them.
        val with = Rc2EngineAcceptanceTests.withPersonal(
            "hobby" to "Long-distance cycling", "motivation" to "Building products people use every day",
            "outside_work" to "Volunteering as a mentor for new founders", "team" to "Small cross-functional teams with direct feedback",
        )
        val s = InterviewSimulator(with, options = EngineOptions().apply { autoTick = false; useFastCache = false })
        s.start()
        for (q in Personal) ask(s, q)
        val expected = listOf("Long-distance cycling", "Building products people use every day", "Volunteering as a mentor", "Small cross-functional teams")
        val grounded = s.p.calls.zip(expected).count { (call, text) -> call.any { m -> m.content.contains(text) } }

        // Without profile answers: no model call that could invent a hobby — an instant, honest prompt to the candidate.
        val without = InterviewSimulator(Rc2EngineAcceptanceTests.withPersonal(), options = EngineOptions().apply { autoTick = false; useFastCache = false })
        without.start()
        for (q in Personal) ask(without, q)
        val guidance = without.e.history.count { a -> a.source == AnswerSource.Guidance }

        assertEquals(Personal.size, grounded)
        assertEquals(Personal.size, guidance)
        assertEquals(0, without.p.requests.get())
    }
}
