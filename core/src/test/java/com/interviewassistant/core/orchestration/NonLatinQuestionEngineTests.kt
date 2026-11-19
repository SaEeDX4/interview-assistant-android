package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * The three engine cases of `InterviewAssistant.Tests/NonLatinQuestionTests.cs`, deferred BY NAME
 * from Package B; the gate/extractor cases of that file live with the intelligence tests.
 */
class NonLatinQuestionEngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    @Test
    fun engine_AnswersANonLatinQuestion() = runBlocking {
        for (question in listOf("你为什么离开上一家公司？", "لماذا تركت وظيفتك السابقة؟", "چرا شغل قبلی خود را ترک کردید؟")) {
            val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false })
            s.start()
            s.say(question)
            s.run(8000)
            s.settle()
            val a = s.e.history.single()
            assertEquals(question, a.question)
        }
    }
}
