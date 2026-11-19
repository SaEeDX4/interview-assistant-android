package com.interviewassistant.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/NonLatinQuestionTests.cs` — RC4 review finding: recognizers write
 * "？" (Chinese) and "؟" (Arabic, Persian), and word counts only see Latin letters — a whole Chinese or
 * Arabic question looked like a one-word turn without "?" and was dropped silently.
 *
 * The `TurnDetector.IsNonQuestion` assertion of QuestionMarkOfAnyScript_IsAQuestion and the
 * Engine_AnswersANonLatinQuestion cases are deferred (Turn / Orchestration packages) — see P3-PORT-NOTES.md.
 */
class NonLatinQuestionTests {
    @Test
    fun questionMarkOfAnyScriptIsAQuestion() {
        val cases = listOf(
            "你为什么离开上一家公司？",
            "为什么？",
            "لماذا تركت وظيفتك السابقة؟",
            "چرا شغل قبلی خود را ترک کردید؟",
            "Warum haben Sie sich bei uns beworben?",
        )
        for (text in cases) {
            assertTrue(text, UtteranceGate.isQuestionSentence(text))
            assertTrue(text, TextNormalizer.endsWithQuestionMark(text))
        }
    }

    @Test
    fun chineseContextThenQuestionSplitsIntoSentences() {
        val gate = UtteranceGate(PromptEchoDetector("Job interview"))
        val x = QuestionExtractor.extract("我们的团队很小，预算有限。你会先做什么？", "", null, gate)
        assertEquals("你会先做什么？", x.coreQuestion)
        assertTrue(x.contextSummary.contains("预算有限"))
    }
}
