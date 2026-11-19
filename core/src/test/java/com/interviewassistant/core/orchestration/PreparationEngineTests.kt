package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.knowledge.PromptTemplates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The two engine cases of `InterviewAssistant.Tests/PreparationTests.cs`, deferred BY NAME from
 * Package C (P3-PORT-NOTES.md §C2); the pipeline cases of that file live in preparation/.
 */
class PreparationEngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    @Test
    fun packDrivesTheLiveEngineEndToEnd() = runBlocking {
        val pack = Fixtures.shervinPack()
        val kb = pack.toKnowledgeBase()
        val engine = InterviewEngine(kb, null, null, EngineOptions().apply { autoTick = false })
        engine.submitManualQuestion("Tell me about your experience at Arzif")
        val a = engine.current!!
        assertEquals(AnswerSource.Cache, a.source)
        assertTrue(a.bullets.any { b -> b.contains("60,000") })
        assertTrue(kb.context.buildTranscriptionPrompt().contains("Teroxx"))
        assertFalse(PromptTemplates.LiveAnswer.contains("Teroxx"))
    }

    @Test
    fun lowConfidenceTemplateAnswersAreReferenceNotInstant() = runBlocking {
        val pack = Fixtures.shervinPack()
        val provider = FakeProvider()
        val engine = InterviewEngine(pack.toKnowledgeBase(), null, provider, EngineOptions().apply { autoTick = false })
        val templ = pack.questions.first { q -> q.confidence < 0.7 }
        engine.submitManualQuestion(templ.canonicalQuestion)
        assertEquals(AnswerSource.Llm, engine.current!!.source)
        assertTrue(provider.calls[0][1].content.contains("PREPARED REFERENCE ANSWER"))
    }
}
