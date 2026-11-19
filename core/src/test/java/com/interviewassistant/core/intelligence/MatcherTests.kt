package com.interviewassistant.core.intelligence

import com.interviewassistant.core.Fixtures
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/MatcherTests.cs` — same cases, same inputs, same expectations. */
class MatcherTests {
    private data class Case(val utterance: String, val expected: String?, val kind: String)

    private fun loadTests(): List<Case> {
        val root = Json.parseToJsonElement(File(Fixtures.knowledgeDir(), "test_questions.json").readText(Charsets.UTF_8)).jsonObject
        return root.getValue("tests").jsonArray.map { t ->
            val o = t.jsonObject
            Case(
                o.getValue("utterance").jsonPrimitive.content,
                o["expected_question_id"]?.jsonPrimitive?.contentOrNull,
                o.getValue("kind").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun canonicalQuestionsMatchThemselvesWithHighConfidence() {
        val kb = Fixtures.testKnowledge()
        val m = QuestionMatcher(kb.questions)
        for (q in kb.questions) {
            val r = m.match(q.canonicalQuestion)
            assertEquals(q.questionId, r.question?.questionId)
            assertTrue("${q.questionId} ${r.score}", r.confidence >= MatchConfidence.Medium)
        }
    }

    @Test
    fun spokenParaphrasesMatchTop1() {
        val kb = Fixtures.testKnowledge()
        val m = QuestionMatcher(kb.questions)
        val tests = loadTests().filter { it.kind == "paraphrase" }
        var ok = 0
        var high = 0
        var wrongHigh = 0
        for ((u, exp) in tests) {
            val r = m.match(TextNormalizer.cleanTranscript(u, Fixtures.testKnowledge().context.aliases))
            if (r.question?.questionId == exp) {
                ok++
                if (r.confidence == MatchConfidence.High) high++
            } else {
                if (r.confidence == MatchConfidence.High) wrongHigh++
                println("MISS '$u' -> ${r.question?.canonicalQuestion} (${r.score} ${r.confidence}) expected $exp")
            }
        }
        val acc = ok.toDouble() / tests.size
        println("Top-1 accuracy $acc ($ok/${tests.size}); high-confidence hits $high; wrong-but-high $wrongHigh")
        assertTrue("accuracy $acc", acc >= 0.9)
        assertTrue("wrong high-confidence matches: $wrongHigh", wrongHigh <= 2)
    }

    @Test
    fun unexpectedQuestionsAreNotServedFromCache() {
        val kb = Fixtures.testKnowledge()
        val m = QuestionMatcher(kb.questions)
        val tests = loadTests().filter { it.kind == "unexpected" }
        var high = 0
        for ((u) in tests) {
            val r = m.match(u)
            println("'$u' -> ${r.question?.canonicalQuestion} ${r.score} ${r.confidence}")
            if (r.confidence == MatchConfidence.High) high++
        }
        assertTrue("$high unexpected questions hit the cache with high confidence", high <= 1)
    }

    @Test
    fun keyQuestionsMatch() {
        val cases = listOf(
            "How would you prioritize improvements to the XAB rewards ecosystem?" to "How would you prioritize improvements to the XAB rewards ecosystem?",
            "So tell me a little bit about yourself." to "Tell me about yourself.",
            "how would you stop duplicate transactions in the ledger" to "How would you prevent duplicate financial transactions?",
            "What's your experience with Jira?" to "What is your experience with Jira and Agile?",
            "why teroxx" to "Why do you want to work at Teroxx?",
        )
        val kb = Fixtures.testKnowledge()
        for ((spoken, expected) in cases) {
            val r = QuestionMatcher(kb.questions).match(TextNormalizer.cleanTranscript(spoken, Fixtures.testKnowledge().context.aliases))
            assertEquals(spoken, expected, r.question?.canonicalQuestion)
        }
    }

    @Test
    fun matchingIsFast() {
        val kb = Fixtures.testKnowledge()
        val m = QuestionMatcher(kb.questions)
        m.match("warm up")
        val start = System.nanoTime()
        repeat(200) { m.match("How would you design VIP tier governance for XAB holders and measure it?") }
        val perMatch = (System.nanoTime() - start) / 1_000_000.0 / 200
        println("$perMatch ms per match")
        assertTrue("$perMatch ms per match", perMatch < 15)
    }
}
