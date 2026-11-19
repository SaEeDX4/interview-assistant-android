package com.interviewassistant.core.languages

import com.interviewassistant.core.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/LanguageTests.cs` — same cases, same inputs, same expectations. */
class LanguageTests {
    @Test
    fun detectsAllTenLaunchLanguages() {
        val cases = listOf(
            "How would you prioritize the product backlog for this team?" to "en",
            "¿Cómo priorizaría usted el backlog del producto para este equipo?" to "es",
            "Comment prioriseriez-vous le backlog produit pour cette équipe ?" to "fr",
            "Wie würden Sie das Produkt-Backlog für dieses Team priorisieren?" to "de",
            "Como você priorizaria o backlog do produto para esta equipe?" to "pt",
            "Come darebbe priorità al backlog del prodotto per questo team?" to "it",
            "كيف ستحدد أولويات قائمة المنتج لهذا الفريق؟" to "ar",
            "چگونه بک‌لاگ محصول را برای این تیم اولویت‌بندی می‌کنید؟" to "fa",
            "你会如何为这个团队确定产品待办事项的优先级？" to "zh",
            "आप इस टीम के लिए प्रोडक्ट बैकलॉग को कैसे प्राथमिकता देंगे?" to "hi",
        )
        for ((text, expected) in cases) assertEquals(expected, LanguageDetector.detect(text).code)
    }

    @Test
    fun shortOrUnknownTextFallsBackToLockedLanguage() {
        assertEquals("de", LanguageDetector.detectOr("OK", "de"))
        assertEquals("fr", LanguageDetector.detectOr("", "fr"))
        assertEquals("und", LanguageDetector.detect("12345 ?!").code)
    }

    @Test
    fun registryHasTenLanguagesWithRtlForArabicAndPersian() {
        assertEquals(10, LanguageRegistry.all.size)
        assertEquals(listOf("ar", "fa"), LanguageRegistry.all.filter { it.isRightToLeft }.map { it.code })
        assertTrue(LanguageRegistry.isSupported("pt-BR"))
        assertFalse(LanguageRegistry.isSupported("xx"))
    }

    @Test
    fun answerLanguageResolution() {
        val cases = listOf(
            arrayOf("same", "auto", "de", "de"), // follow interviewer
            arrayOf("fa", "en", "en", "fa"), // cross-language: English question, Persian answer
            arrayOf("same", "fr", "und", "fr"), // undetected → locked interview language
            arrayOf("same", "auto", null, "en"),
        )
        for ((answer, interview, detected, expected) in cases)
            assertEquals(expected, LanguageRegistry.resolveAnswerLanguage(answer!!, interview!!, detected))
    }

    @Test
    fun answerRulePreservesTermsAndAvoidsLiteralTranslation() {
        val rule = LanguageRegistry.answerLanguageRule("fa")
        assertTrue(rule.contains("Persian"))
        assertTrue(rule.contains("فارسی"))
        assertTrue(rule.contains("not translate word-for-word"))
        assertTrue(rule.contains("Product Owner"))
    }
}

class UiStringsTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() = Fixtures.installLocTables()
    }

    @Test
    fun allTenLanguagesAreCompleteAndDistinct() {
        for (l in LanguageRegistry.all) {
            assertTrue(l.code, UiStrings.isComplete(l.code))
            if (l.code != "en") assertNotEquals(UiStrings.get("en", "waiting"), UiStrings.get(l.code, "waiting"))
        }
    }

    @Test
    fun lookupNormalisesAndFallsBack() {
        val cases = listOf(
            "ar" to "ar", "fa" to "fa", "de-DE" to "de", "xx" to "en", null to "en",
        )
        for ((code, expected) in cases)
            assertEquals(UiStrings.get(expected, "start"), UiStrings.get(code, "start"))
    }

    @Test
    fun placeholdersSurviveTranslationAndUnknownKeysAreVisible() {
        for (l in LanguageRegistry.all)
            assertTrue(l.code, UiStrings.format(l.code, "minutes_left", 7).contains("7"))
        assertEquals("no_such_key", UiStrings.get("de", "no_such_key"))
    }

    @Test
    fun rtlLanguagesContainArabicScript() {
        for (code in listOf("ar", "fa"))
            assertTrue(code, UiStrings.get(code, "question").any { it in '؀'..'ۿ' })
    }
}
