package com.interviewassistant.core.languages

import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirror of `InterviewAssistant.Core/Languages/LanguageRegistry.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Adaptation: .NET `CultureInfo` becomes [java.util.Locale]. The C# `FormattingCulture` additionally forces
 * the Gregorian calendar for Arabic (ar-SA defaults to Umm al-Qura); a bare [Locale] carries no calendar, so
 * the calendar rule is exposed as [usesGregorianCalendar] and honoured by date formatting call sites
 * (dates are a Reports/P3 concern; the client-visible surface — number formatting in [Loc] — is unaffected).
 */

data class LanguageInfo(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val isRightToLeft: Boolean,
    val script: String,
    val cultureName: String,
) {
    /** Human label for pickers: "English", "Persian — فارسی". */
    val displayName: String
        get() = if (nativeName.equals(englishName, ignoreCase = true)) englishName else "$englishName — $nativeName"

    // never the data class debug format in UI
    override fun toString(): String = displayName
}

/**
 * Supported interview/answer/UI languages. Adding a language = one entry here + a UI resource file
 * (+ optional stop-word profile in LanguageDetector). The engine itself is language-agnostic.
 */
object LanguageRegistry {
    val all: List<LanguageInfo> = listOf(
        LanguageInfo("en", "English", "English", false, "Latin", "en-US"),
        LanguageInfo("es", "Spanish", "Español", false, "Latin", "es-ES"),
        LanguageInfo("fr", "French", "Français", false, "Latin", "fr-FR"),
        LanguageInfo("de", "German", "Deutsch", false, "Latin", "de-DE"),
        LanguageInfo("pt", "Portuguese", "Português", false, "Latin", "pt-BR"),
        LanguageInfo("it", "Italian", "Italiano", false, "Latin", "it-IT"),
        LanguageInfo("ar", "Arabic", "العربية", true, "Arabic", "ar-SA"),
        LanguageInfo("fa", "Persian", "فارسی", true, "Arabic", "fa-IR"),
        LanguageInfo("zh", "Chinese (Simplified)", "简体中文", false, "Han", "zh-CN"),
        LanguageInfo("hi", "Hindi", "हिन्दी", false, "Devanagari", "hi-IN"),
    )

    fun find(code: String?): LanguageInfo? {
        if (code.isNullOrBlank()) return null
        var c = code.trim().lowercase()
        if (c.length > 2 && (c[2] == '-' || c[2] == '_')) c = c.substring(0, 2)
        return all.firstOrNull { it.code == c }
    }

    fun isSupported(code: String?): Boolean = find(code) != null
    fun isRtl(code: String?): Boolean = find(code)?.isRightToLeft ?: false
    fun englishName(code: String?): String = find(code)?.englishName ?: "English"

    private val formattingLocales = ConcurrentHashMap<String, Locale>()

    /**
     * Locale for numbers and dates in [code] (English for an unknown code). Mirrors the C#
     * `FormattingCulture` lookup; see [usesGregorianCalendar] for the Arabic calendar rule.
     */
    fun formattingLocale(code: String?): Locale {
        val info = find(code) ?: all[0]
        return formattingLocales.getOrPut(info.code) { Locale.forLanguageTag(info.cultureName) }
    }

    /** Number formatter of [formattingLocale] (what .NET `string.Format(culture, …)` applies to numbers). */
    fun numberFormat(code: String?): NumberFormat = NumberFormat.getNumberInstance(formattingLocale(code))

    /**
     * The C# `FormattingCulture` rule: Arabic formats dates on the Gregorian calendar (billing and export
     * dates must match payment receipts); Persian keeps its Solar Hijri calendar. Every other language uses
     * its culture's default calendar.
     */
    fun usesGregorianCalendar(code: String?): Boolean = (find(code) ?: all[0]).code == "ar"

    /**
     * Resolves the language answers must be written in.
     * answerSetting: "same" → follow detected interviewer language (fallback: locked interview language, then English).
     */
    fun resolveAnswerLanguage(answerSetting: String, interviewSetting: String, detected: String?): String {
        if (answerSetting.isNotBlank() && answerSetting != "same" && isSupported(answerSetting)) return find(answerSetting)!!.code
        if (isSupported(detected)) return find(detected)!!.code
        if (isSupported(interviewSetting)) return find(interviewSetting)!!.code
        return "en"
    }

    /** Prompt instruction that makes the model write natively in the target language (not translate from English). */
    fun answerLanguageRule(code: String): String {
        val l = find(code) ?: all[0]
        if (l.code == "en") return "LANGUAGE: Write in natural spoken English."
        return "LANGUAGE: Write the bullets directly in natural, native-sounding spoken ${l.englishName} (${l.nativeName}) — do not translate word-for-word from English. " +
            "Keep the MODE line in English. Keep company names, product names, acronyms, metrics, dates and established professional terms " +
            "(e.g. Product Owner, KPI, OKR, API, Jira) in their usual professional form for that language."
    }
}
