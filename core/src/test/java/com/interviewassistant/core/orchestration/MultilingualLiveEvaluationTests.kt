package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.LanguageDetector
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.live.Presentation
import com.interviewassistant.core.providers.ChatProviderOptions
import com.interviewassistant.core.providers.OpenAiChatAnswerProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `LiveAnswersInAllTenLanguages` in `InterviewAssistant.Tests/MultilingualLiveEvaluationTests.cs` —
 * deferred BY NAME from Package C. The fixture fact and the 10 × 7 `Questions` table were ported by
 * Package B (live/MultilingualLiveEvaluationTests.kt) and are reused here.
 *
 * LIVE multilingual answer-quality evaluation (runs only with OPENAI_API_KEY): 10 languages × 7 question types
 * (behavioural, technical, product-owner, résumé-based, hypothetical, follow-up, gap/bridge). Automated checks:
 * output language matches, 2–4 bullets, no unverified numbers/unsupported claims, Bridge on gap questions, Coach
 * contract in every language. Naturalness/tone require human native review — recorded in docs/v2/MULTILINGUAL_VALIDATION.md.
 */
class MultilingualLiveEvaluationTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    @Test
    fun liveAnswersInAllTenLanguages() = runBlocking {
        val key = System.getenv("OPENAI_API_KEY")
        if (key.isNullOrBlank()) {
            println("SKIPPED: OPENAI_API_KEY not set — multilingual live quality NOT YET VALIDATED")
            return@runBlocking
        }
        val provider = OpenAiChatAnswerProvider(
            { key },
            ChatProviderOptions().apply { model = System.getenv("IA_ANSWER_MODEL") ?: "gpt-5.4-mini" },
        )
        try {
            val kb = Fixtures.testKnowledge()
            val validator = FactValidator(kb.profile, kb.context)
            var problems = 0
            var total = 0
            for ((lang, qs) in com.interviewassistant.core.live.MultilingualLiveEvaluationTests.Questions) {
                val engine = InterviewEngine(kb, null, provider, EngineOptions().apply { autoTick = false; useFastCache = false })
                engine.answerLanguageOverride = lang // short follow-ups like "Why?" are too short to detect reliably
                for (i in qs.indices) {
                    engine.submitManualQuestion(qs[i])
                    val a = engine.current!!
                    total++
                    val text = a.bullets.joinToString(" ")
                    val detected = LanguageDetector.detect(text).code
                    val r = validator.validate(a.bullets, a.mode, AnswerStyle.Technical)
                    val ok = a.bullets.size in 2..4 && detected == lang && !r.hasFactualIssues && (i != 6 || a.mode == AnswerMode.Bridge)
                    if (!ok) problems++
                    println("[$lang] ${if (ok) "OK " else "BAD"} ${a.mode} lang=$detected :: ${qs[i]}")
                }
                engine.presentation = Presentation.Coach
                engine.submitManualQuestion(qs[2])
                assertTrue("coach contract failed for $lang", engine.current!!.coach!!.isValid)
            }
            println("${total - problems}/$total automated checks passed")
            assertTrue("$problems/$total multilingual answers failed automated checks", problems <= total / 10)
        } finally {
            provider.close()
        }
    }
}
