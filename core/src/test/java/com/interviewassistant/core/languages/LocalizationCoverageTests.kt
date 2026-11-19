package com.interviewassistant.core.languages

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.PersonalCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/LocalizationCoverageTests.cs` — the table-driven cases run over the real
 * byte-identical i18n assets in `:core`. The two Windows source scans (XamlHasNoHardCodedUserText,
 * DesktopCodeHasNoHardCodedUserText) and the source-scan half of EveryKeyUsedInTheAppExistsInEnglish have no
 * Android source tree to scan yet; they are deferred to the App/UI package (see P3-PORT-NOTES.md, Package B).
 */
class LocalizationCoverageTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() = Fixtures.installLocTables()

        /** Values that are legitimately identical across languages (names, acronyms, hotkeys, formats, symbols). */
        private val sameInEveryLanguage: Set<String> = setOf(
            "history_label", "min_label", "svc_ref", "set_key_saved", "fact_line", "device_row", "about_product",
            "coach", "pf_answer_ok", "prep_progress", "acct_signed_in_as", "start_signed_in", "min_today", "bill_payment_problem",
        )

        /**
         * Reviewed cognates: words that are spelled the same as English and are the natural term in that language
         * (e.g. German "Interviews", French "Version", Italian "Account"). Anything else identical to English fails.
         */
        private val cognates: Map<String, Set<String>> = mapOf(
            "es" to setOf("fact_kind_personal", "channel_beta_build", "font_normal", "set_nav_audio"),
            "pt" to setOf("channel_beta_build", "font_normal"),
            "it" to setOf("account", "password", "channel_beta_build", "set_nav_audio", "exp_section_account", "exp_tech_account"),
            "de" to setOf(
                "upgrade", "interviews", "report_default_label", "channel_beta_build", "about_version", "font_normal", "set_nav_audio",
                "exp_docs_target", "exp_prev_interviews",
            ),
            "fr" to setOf(
                "question", "pause", "structure", "file_filter_documents", "fact_kind", "fact_kind_certification", "report_preview_meta",
                "channel_stable_build", "about_version", "about_install", "about_service", "pf_check_stt", "font_compact", "font_normal", "set_nav_audio",
                "exp_col_date", "exp_tech_session", "exp_tech_document",
            ),
        )

        private val placeholder: Regex = Regex("\\{\\d+\\}")

        /** True when the value contains ordinary English words (not only names, acronyms, numbers or hotkeys). */
        private fun hasEnglishWords(v: String): Boolean {
            val cleaned = Regex("\\{\\d+\\}|Ctrl\\+Alt\\+\\w+|Interview Assistant|OpenAI|Paddle|Chrome|Meet|Zoom|Teams|Windows|SmartScreen|DPAPI|API|PDF|DOCX|TXT|ZIP|HTML|UTC|dBFS|Hz|AI|PASS|FAIL|WARNING|Bluetooth|Coach|Pro|ms|min|·|—|…|[^\\p{L}\\s]").replace(v, " ")
            return Regex("\\b[a-zA-Z]{3,}\\b").findAll(cleaned).count() > 0
        }
    }

    /**
     * rc.5: a key defined twice in one table silently keeps only one value (System.Text.Json takes the last). Parallel
     * feature work merged the same keys twice once; every table must define each key exactly once.
     * (The C# scans the i18n folder; here the ten byte-identical assets in `:core` are the tables.)
     */
    @Test
    fun noTableDefinesAKeyTwice() {
        val keyLine = Regex("^\\s*\"([^\"]+)\"\\s*:", RegexOption.MULTILINE)
        for (code in LanguageRegistry.all.map { it.code }) {
            val keys = keyLine.findAll(Fixtures.i18nAssetText(code)).map { it.groupValues[1] }.toList()
            val duplicates = keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            assertTrue("$code.json defines ${duplicates.joinToString(", ")} more than once", duplicates.isEmpty())
        }
    }

    @Test
    fun englishTableIsTheReference() {
        assertTrue("${Loc.keys.size} keys", Loc.keys.size > 600)
        val keyShape = Regex("^[a-z0-9_]+$")
        for (k in Loc.keys) assertTrue(k, keyShape.matches(k))
    }

    @Test
    fun everySupportedLanguageIsComplete() {
        for (lang in listOf("es", "fr", "de", "pt", "it", "ar", "fa", "zh", "hi")) {
            val missing = Loc.missingKeys(lang)
            assertTrue("$lang: ${missing.size} missing keys: ${missing.take(40).joinToString(", ")}", missing.isEmpty())
            val t = Loc.tableFor(lang)
            val en = Loc.tableFor("en")
            val placeholderMismatch = en.keys.filter { k ->
                t.containsKey(k) &&
                    placeholder.findAll(en.getValue(k)).map { it.value }.sorted().toList() !=
                    placeholder.findAll(t.getValue(k)).map { it.value }.sorted().toList()
            }
            assertTrue("$lang: placeholder mismatch in ${placeholderMismatch.joinToString(", ")}", placeholderMismatch.isEmpty())
            // Fallback-only detection: a value identical to English means the section was not translated.
            val untranslated = en.keys.filter { k ->
                val v = t[k]
                v != null && v == en[k] && !sameInEveryLanguage.contains(k) &&
                    !(cognates[lang]?.contains(k) == true) && hasEnglishWords(v)
            }
            println("$lang: ${t.size} keys, ${untranslated.size} identical to English")
            assertTrue("$lang: untranslated (identical to English): ${untranslated.take(40).joinToString(", ")}", untranslated.isEmpty())
            val extra = t.keys.filter { k -> !en.containsKey(k) }
            assertTrue("$lang: keys not in English: ${extra.joinToString(", ")}", extra.isEmpty())
        }
    }

    @Test
    fun rightToLeftLanguagesAreRegisteredAsRtl() {
        for (lang in listOf("fa", "ar")) assertTrue(lang, LanguageRegistry.isRtl(lang))
    }

    @Test
    fun liveLanguageSwitchRaisesAnEventAndChangesText() {
        val before = Loc.language
        var raised = 0
        val handler: () -> Unit = { raised++ }
        Loc.addLanguageChangedListener(handler)
        try {
            Loc.language = "de"
            val de = Loc.t("start")
            Loc.language = "fa"
            assertTrue(Loc.isRtl)
            assertNotEquals(de, Loc.t("start"))
            assertEquals(2, raised)
        } finally {
            Loc.removeLanguageChangedListener(handler)
            Loc.language = before
        }
    }

    @Test
    fun messagesAlreadyOnScreenFollowALanguageSwitch() {
        val before = Loc.language
        try {
            Loc.language = "en"
            val shown = Loc.t("svc_unreachable") + " " + Loc.f("svc_ref", "1a2b3c4d")
            Loc.language = "fa"
            val now = Loc.relocalize(shown)!!
            assertTrue(now.startsWith(Loc.t("svc_unreachable")))
            assertTrue(now.endsWith(Loc.f("svc_ref", "1a2b3c4d")))
            assertEquals(Loc.t("toast_dismiss"), Loc.relocalize(Loc.tableFor("hi").getValue("toast_dismiss"))) // from any language
            assertEquals("How would you prioritise the backlog?", Loc.relocalize("How would you prioritise the backlog?")) // user data unchanged
            assertNull(Loc.relocalize(null))
        } finally {
            Loc.language = before
        }
    }

    @Test
    fun uiLanguageNeverChangesTheAnswerLanguage() {
        val before = Loc.language
        try {
            Loc.language = "fa"
            assertEquals("en", LanguageRegistry.resolveAnswerLanguage("same", "en", "en"))
            assertEquals("de", LanguageRegistry.resolveAnswerLanguage("same", "en", "de"))
        } finally {
            Loc.language = before
        }
    }

    /**
     * The dynamic-key half of the C#'s EveryKeyUsedInTheAppExistsInEnglish (keys built from enums/categories at
     * runtime). The source-scan half (Loc.T/Loc.F call sites and {v:Loc …} bindings in the Windows app) is
     * deferred to the App/UI package — there is no Android UI source tree to scan yet.
     */
    @Test
    fun everyDynamicKeyExistsInEnglish() {
        for (k in FactKind.values()) assertTrue(k.name, Loc.has("fact_kind_" + k.name.lowercase()))
        for (c in PersonalCategories.all) {
            assertTrue(c.key, Loc.has("pc_${c.key}_label") && Loc.has("pc_${c.key}_prompt"))
            assertTrue(c.section, Loc.has("pcs_" + c.section))
        }
        for (s in listOf("active", "stopped", "device_error", "no_playback_device", "reconnecting_device")) assertTrue(s, Loc.has("audio_" + s))
        for (f in listOf("compact", "normal", "large", "extralarge")) assertTrue(f, Loc.has("font_" + f))
    }
}
