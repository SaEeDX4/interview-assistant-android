package com.interviewassistant.core.languages

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.reports.ExportLabels
import java.time.DayOfWeek
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/L10nDateAndBidiTests.cs`, adapted to the Kotlin registry surface
 * (P2's DC-5: a JVM `Locale` carries no calendar, so the calendar facts are asserted through the registry's
 * own [LanguageRegistry.usesGregorianCalendar] flag and the registered culture names).
 *
 * The five ExportLabels date tests deferred from the Reports package are ported at the bottom of this
 * class (Package D): ExportDates_HaveNoWeekday (5 cases), ExportDates_HaveNoWeekday_InAnyLanguage
 * (10 cases), ExportDates_ReadInsideTheSentence, ExportDates_KeepTheCulturesOwnOrder,
 * LongDatePattern_LosesOnlyTheWeekday (7 cases — asserted against ExportLabels.stripWeekday, the pure
 * pattern algorithm the C# exercises through a cloned culture's LongDatePattern).
 * Deferred to the App/UI package: AccountPage_IsolatesVersionsAndEmailsInLocalizedSentences (Windows source
 * scan) and the source-text half of ArabicUiFormatting_EverywhereUsesTheGregorianCalendar.
 */
class L10nDateAndBidiTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() = Fixtures.installLocTables()
    }

    @Test
    fun arabicFormattingCultureIsGregorian() {
        // The shared tables name the culture ar-SA; only the calendar changes (Gregorian, so billing dates
        // match payment receipts — the registry records that as its usesGregorianCalendar rule).
        assertEquals("ar-SA", LanguageRegistry.find("ar")!!.cultureName)
        assertTrue(LanguageRegistry.usesGregorianCalendar("ar"))
    }

    @Test
    fun persianFormattingCultureKeepsItsCalendar() {
        assertEquals("fa-IR", LanguageRegistry.find("fa")!!.cultureName)
        assertFalse(LanguageRegistry.usesGregorianCalendar("fa"))
    }

    @Test
    fun formattingCultureOtherLanguagesUseTheirRegisteredCulture() {
        for (l in LanguageRegistry.all.filter { it.code != "ar" })
            assertEquals(l.code, l.cultureName, LanguageRegistry.formattingLocale(l.code).toLanguageTag())
        assertEquals("en-US", LanguageRegistry.formattingLocale("xx").toLanguageTag())
        assertSame(LanguageRegistry.formattingLocale("ar"), LanguageRegistry.formattingLocale("ar-EG"))
    }

    /** The behavioral core of ArabicUiFormatting_EverywhereUsesTheGregorianCalendar: Loc formats with the registry's formatting culture. */
    @Test
    fun arabicUiFormattingUsesTheRegistryFormattingCulture() {
        val before = Loc.language
        try {
            Loc.language = "ar"
            assertSame(LanguageRegistry.formattingLocale("ar"), Loc.locale)
            assertTrue(LanguageRegistry.usesGregorianCalendar("ar"))
            assertFalse(LanguageRegistry.usesGregorianCalendar("fa"))
        } finally {
            Loc.language = before
        }
    }

    // ------------------------------------------------------------------
    // Package D: the ExportLabels date tests deferred from the Reports package.
    // Sunday 18 October 2026, midday UTC (the same calendar day in every time zone used here).
    private val sunday: OffsetDateTime = OffsetDateTime.of(2026, 10, 18, 12, 0, 0, 0, ZoneOffset.UTC)
    private val languages = listOf("en", "es", "fr", "de", "pt", "it", "ar", "fa", "zh", "hi")

    @Test
    fun exportDates_HaveNoWeekday() {
        for (lang in listOf("it", "pt", "de", "en", "fr")) {
            val culture = ExportLabels.culture(lang)
            val text = ExportLabels.date(lang, sunday, ZoneId.of("UTC"))
            for (day in DayOfWeek.values()) {
                val name = day.getDisplayName(TextStyle.FULL, culture)
                assertFalse("$lang: $name in $text", text.contains(name, ignoreCase = true))
            }
            assertTrue(lang, text.contains("18"))
            assertTrue(lang, text.contains("2026"))
            val monthName = Month.OCTOBER.getDisplayName(TextStyle.FULL, culture)
            assertTrue("$lang: $monthName in $text", text.contains(monthName, ignoreCase = true))
        }
    }

    @Test
    fun exportDates_HaveNoWeekday_InAnyLanguage() {
        for (lang in languages) {
            val culture = ExportLabels.culture(lang)
            val text = ExportLabels.date(lang, sunday, ZoneId.of("UTC"))
            for (day in DayOfWeek.values()) {
                val name = day.getDisplayName(TextStyle.FULL, culture)
                assertFalse("$lang: $name in $text", text.contains(name, ignoreCase = true))
            }
            assertFalse(lang, text.isBlank())
        }
    }

    @Test
    fun exportDates_ReadInsideTheSentence() {
        assertEquals("Si rinnova il 18 ottobre 2026", ExportLabels.format("it", "exp_renews_on", ExportLabels.date("it", sunday, ZoneId.of("UTC"))))
        assertEquals("Renova em 18 de outubro de 2026", ExportLabels.format("pt", "exp_renews_on", ExportLabels.date("pt", sunday, ZoneId.of("UTC"))))
        assertEquals("October 18, 2026", ExportLabels.date("en", sunday, ZoneId.of("UTC")))
        assertEquals("2026年10月18日", ExportLabels.date("zh", sunday, ZoneId.of("UTC")))
    }

    @Test
    fun exportDates_KeepTheCulturesOwnOrder() {
        // Only the weekday goes: the rest of each culture's long date pattern is used as it is (no generic fallback).
        for (lang in languages) {
            val culture = ExportLabels.culture(lang)
            val chronology = if (LanguageRegistry.usesGregorianCalendar(lang)) IsoChronology.INSTANCE else java.time.chrono.Chronology.ofLocale(culture)
            val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.LONG, null, chronology, culture)
            val stripped = ExportLabels.longDateWithoutWeekday(culture)
            assertTrue("$lang: $pattern -> $stripped", stripped.length < pattern.length || !pattern.contains("ddd"))
        }
    }

    @Test
    fun longDatePattern_LosesOnlyTheWeekday() {
        val cases = listOf(
            "dddd, MMMM d, yyyy" to "MMMM d, yyyy",
            "dddd d MMMM yyyy" to "d MMMM yyyy",
            "dddd, d. MMMM yyyy" to "d. MMMM yyyy",
            "dddd, d 'de' MMMM 'de' yyyy" to "d 'de' MMMM 'de' yyyy",
            "yyyy年M月d日dddd" to "yyyy年M月d日",
            "d MMMM yyyy, dddd" to "d MMMM yyyy",
            "d MMMM yyyy" to "d MMMM yyyy",
        )
        for ((pattern, expected) in cases) assertEquals(pattern, expected, ExportLabels.stripWeekday(pattern))
    }
}
