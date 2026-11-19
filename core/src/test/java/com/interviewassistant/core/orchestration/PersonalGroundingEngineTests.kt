package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The three `Engine_*` facts of `InterviewAssistant.Tests/PersonalGroundingTests.cs`, deferred BY NAME
 * from Package B; the grounding-unit cases of that file live in intelligence/.
 */
class PersonalGroundingEngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private const val Q = "What are your hobbies?"
    }

    @Test
    fun engine_DropsInventedPersonalBullets_AndFallsBackToTheConfirmedWords() = runBlocking {
        val s = InterviewSimulator(
            Rc2EngineAcceptanceTests.withPersonal("hobby" to "Hiking and photography"),
            options = EngineOptions().apply { autoTick = false; useFastCache = false },
        )
        s.p.responder = {
            arrayOf(
                "MODE: VERIFIED\n",
                "• Hiking keeps me grounded and helps me recharge.\n",
                "• Photography taught me patience and an eye for detail.\n",
                "• Both make me a calmer product owner.",
            )
        }
        s.start()
        s.e.submitManualQuestion(Q)
        s.settle()
        val a = s.e.history.single()
        assertEquals(listOf("Hiking and photography"), a.bullets)
        assertEquals(AnswerSource.CacheFallback, a.source)
        assertEquals(3, a.validationFlags.count { f -> f.startsWith("DROPPED_UNGROUNDED_PERSONAL") })
    }

    @Test
    fun engine_FactsInAnotherLanguage_AreNotComparedWordForWord() = runBlocking {
        // German facts, English answer: a word-by-word check would drop every translated bullet.
        val s = InterviewSimulator(
            Rc2EngineAcceptanceTests.withPersonal("hobby" to "Wandern und Fotografie in den Bergen"),
            options = EngineOptions().apply { autoTick = false; useFastCache = false },
        )
        s.p.responder = { arrayOf("MODE: VERIFIED\n", "• In my free time I enjoy hiking and photography in the mountains.") }
        s.start()
        s.e.submitManualQuestion(Q)
        s.settle()
        val a = s.e.history.single()
        assertEquals(listOf("In my free time I enjoy hiking and photography in the mountains."), a.bullets)
    }

    @Test
    fun engine_KeepsAGroundedRephrasing_AndDropsOnlyTheEmbellishment() = runBlocking {
        val s = InterviewSimulator(
            Rc2EngineAcceptanceTests.withPersonal("hobby" to "Hiking and photography"),
            options = EngineOptions().apply { autoTick = false; useFastCache = false },
        )
        s.p.responder = {
            arrayOf(
                "MODE: VERIFIED\n",
                "• In my free time I enjoy hiking and photography.\n",
                "• It keeps me balanced and creative.",
            )
        }
        s.start()
        s.e.submitManualQuestion(Q)
        s.settle()
        val a = s.e.history.single()
        assertEquals(listOf("In my free time I enjoy hiking and photography."), a.bullets)
        assertEquals(AnswerSource.Llm, a.source)
        val prompt = s.p.calls[0][1].content
        assertTrue(prompt.contains("Do NOT add reasons, feelings, benefits, personality traits"))
    }
}
