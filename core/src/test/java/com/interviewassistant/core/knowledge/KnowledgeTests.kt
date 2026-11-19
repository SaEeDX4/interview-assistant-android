package com.interviewassistant.core.knowledge

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.TextNormalizer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/KnowledgeTests.cs` — same cases, same inputs, same expectations. */
class KnowledgeTests {
    @Test
    fun allRequiredKnowledgeFilesExist() {
        for (f in listOf(
            "candidate_profile.json", "candidate_stories.json", "role_brief.md", "company_brief.md", "product_brief.md",
            "whitepaper_notes.md", "product_owner_playbook.md", "crypto_fintech_playbook.md", "question_bank.json",
            "answer_policy.md", "target_context.json", "test_questions.json",
        ))
            assertTrue(f, File(Fixtures.knowledgeDir(), f).exists())
    }

    @Test
    fun loadsProfileWithAllFiveEmployers() {
        val kb = Fixtures.testKnowledge()
        assertEquals("Shervin Fallahdoust", kb.profile.name)
        assertEquals(
            listOf("Gabrielyte UAB", "Arzif Crypto Exchange", "LG Electronics", "Philips", "Epson"),
            kb.profile.experience.map { it.company },
        )
        assertTrue(kb.profile.experience[1].achievements.any { it.contains("60,000 active traders") })
        assertTrue(kb.snippets.size > 40)
    }

    @Test
    fun questionBankHasAtLeast80UniqueQuestionsWithAllFields() {
        val kb = Fixtures.testKnowledge()
        assertTrue("only ${kb.questions.size}", kb.questions.size >= 80)
        assertEquals(kb.questions.size, kb.questions.map { it.questionId }.distinct().size)
        for (q in kb.questions) {
            assertTrue(q.canonicalQuestion.isNotBlank())
            assertTrue(q.keywords.isNotEmpty())
            assertTrue(q.semanticVariants.isNotEmpty())
            assertTrue(q.optionalFullAnswer.isNotBlank())
            for (id in q.candidateEvidence) assertTrue(kb.stories.any { s -> s.id == id })
        }
    }

    /** Automated answer-quality evaluation of every prepared answer (format + factuality). */
    @Test
    fun everyPreparedAnswerPassesQualityAndFactChecks() {
        val kb = Fixtures.testKnowledge()
        val v = FactValidator(kb.profile)
        val failures = mutableListOf<String>()
        var totalWords = 0
        for (q in kb.questions) {
            val r = v.validate(q.shortBullets, q.mode)
            totalWords += r.wordCount
            for (i in r.issues) failures.add("${q.questionId} ${i.kind} ${i.detail} :: ${if (i.bulletIndex >= 0) q.shortBullets[i.bulletIndex] else ""}")
            if (q.shortBullets.size < 3 || q.shortBullets.size > 4) failures.add("${q.questionId} has ${q.shortBullets.size} bullets")
            if (q.mode != AnswerMode.Verified)
                for (b in q.shortBullets)
                    if (!b.contains("I'd") && !b.contains("I would") && b.startsWith("I ")) { /* allowed: present-tense statements */ }
            // Presentation-style labels (no verb) are not speakable.
            for (b in q.shortBullets) if (TextNormalizer.wordCount(b) < 7) failures.add("${q.questionId} label-like bullet: $b")
        }
        println("Average words per answer: ${totalWords.toDouble() / kb.questions.size}")
        for (f in failures) println(f)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun bridgeAnswersStartWithHonestBridge() {
        val kb = Fixtures.testKnowledge()
        val bridge = Regex("(haven't|wasn't|correct)", RegexOption.IGNORE_CASE)
        for (q in kb.questions.filter { it.mode == AnswerMode.Bridge })
            assertTrue(q.questionId, bridge.containsMatchIn(q.shortBullets[0]))
    }

    @Test
    fun hypotheticalAnswersDoNotClaimFabricatedHistory() {
        val kb = Fixtures.testKnowledge()
        val fabricated = Regex("\\bI (built|implemented|owned|designed) (a|the|an) (ledger|wallet|reward|token)")
        for (q in kb.questions.filter { it.mode == AnswerMode.Hypothetical })
            for (b in q.shortBullets)
                assertTrue(q.questionId, !fabricated.containsMatchIn(b))
    }

    @Test
    fun testQuestionFileHasAtLeast80Utterances() {
        val root = Json.parseToJsonElement(File(Fixtures.knowledgeDir(), "test_questions.json").readText(Charsets.UTF_8)).jsonObject
        assertTrue(root.getValue("tests").jsonArray.size >= 80)
    }
}
