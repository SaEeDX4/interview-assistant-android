package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.ValidationIssue
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.providers.ChatProviderOptions
import com.interviewassistant.core.providers.OpenAiChatAnswerProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/LiveEvaluationTests.cs` — deferred BY NAME from Package C.
 *
 * Optional LIVE evaluation against the real OpenAI API. Runs only when OPENAI_API_KEY is set
 * (e.g. locally or as a CI secret); otherwise it reports "skipped" and passes.
 * Sends unexpected / gap / hypothetical questions through the full engine and checks bullet format,
 * length, unsupported historical claims and unverified numbers.
 */
class LiveEvaluationTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    @Test
    fun liveAnswersMeetFormatAndTruthRules() = runBlocking {
        val key = System.getenv("OPENAI_API_KEY")
        if (key.isNullOrBlank()) {
            println("SKIPPED: OPENAI_API_KEY not set")
            return@runBlocking
        }
        val model = System.getenv("IA_ANSWER_MODEL") ?: "gpt-4.1-mini"
        val kb = Fixtures.testKnowledge()
        val provider = OpenAiChatAnswerProvider({ key }, ChatProviderOptions().apply { this.model = model })
        try {
            val engine = InterviewEngine(kb, null, provider, EngineOptions().apply { autoTick = false; useFastCache = false })
            val validator = FactValidator(kb.profile)
            val questions = listOf(
                "How would you migrate XAB balances from on-chain to an internal ledger without losing integrity?",
                "Have you personally owned a wallet architecture before?",
                "What would you do if a whale tries to game the VIP tier rules?",
                "How would you measure whether cashback paid in XAB is worth its cost?",
                "Tell me about your experience at Arzif.",
                "If you had to cut half the roadmap tomorrow, what would you keep?",
            )
            var problems = 0
            for (q in questions) {
                engine.submitManualQuestion(q)
                val a = engine.current!!
                val r = validator.validate(a.bullets, a.mode)
                val issues = r.issues + a.validationFlags.map { f -> ValidationIssue("FLAG", f, -1) }
                if (a.bullets.size < 2 || a.bullets.size > 4) problems++
                if (r.hasFactualIssues) problems++
                if (q.startsWith("Have you personally") && a.mode != AnswerMode.Bridge) problems++
                println("Q: $q -> ${a.mode}, ${r.wordCount} words, issues: ${issues.map { i -> i.kind }}")
            }
            assertTrue("$problems live answers violated format/truth rules", problems <= 1)
        } finally {
            provider.close()
        }
    }
}
