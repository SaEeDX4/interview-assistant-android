package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.lang.reflect.Method
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `Rc5SettingsTests.cs` — the JVM-portable cases (categories, deep links, reset-to-defaults).
 * Deferred by name (WPF/source scans; counterparts in P4-D/F): ResetAndRestore_AskInTheInAppDialog_CancelIsTheDefault_AndApplyLikeASave
 * (source half — its en copy assertions are ported below as resetRestoreCopy), SettingsPage_ShowsOneCategoryAtATime_WithAnAccessibleList_AndKeepsTheExplicitSaveModel,
 * Updates_AreInSettings_NotOnTheAccountPage (source half — its en copy assertion is ported below),
 * CheckNow_ReusesTheBackgroundCheck_AndNeverFakesAnUpdateForUnsignedBuilds, InfoTip_IsFocusable_NamedAndDescribedForScreenReaders,
 * SecondaryHints_AreInfoTips_EssentialStatementsStayVisible. The AppSettings.cs source half of
 * EveryAppSetting_IsClassifiedExactlyOnce (mirror-vs-source sync) lands with the P4 Settings port; the
 * classification invariant itself is ported here against the FakeSettings mirror.
 */
class Rc5SettingsTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    // ---------------- categories ----------------

    @Test
    fun categories_AreOrdered_WithTitleAndOneLineDescription_InEveryLanguage() {
        assertEquals(listOf("audio", "answers", "listening", "window", "hotkeys", "privacy", "updates"), SettingsCategories.ids)
        assertEquals(SettingsCategories.ids, SettingsCategories.all.map { c -> c.id })
        assertEquals("set_nav_audio", SettingsCategories.all[0].titleKey)
        assertEquals("set_cat_audio_desc", SettingsCategories.all[0].descriptionKey)
        assertEquals("Meeting audio device and level", Loc.tableFor("en")["set_cat_audio_desc"])
        for (lang in LanguageRegistry.all.map { it.code }) {
            val t = Loc.tableFor(lang)
            for (c in SettingsCategories.all) {
                assertTrue("$lang: ${c.titleKey}", t.containsKey(c.titleKey))
                val d = t[c.descriptionKey]
                assertTrue("$lang: ${c.descriptionKey}", d != null && d.isNotEmpty() && !d.contains('\n'))
            }
        }
    }

    @Test
    fun parse_SelectsTheSection_FallsBackToLastUsedThenAudio() {
        // (section, lastUsed, expected)
        val cases = listOf(
            Triple("audio", null, "audio"),
            Triple("updates", null, "updates"),
            Triple(" HOTKEYS ", null, "hotkeys"),
            Triple(null, null, "audio"), // no section: Audio …
            Triple(null, "privacy", "privacy"), // … or the last used category
            Triple("bogus", "window", "window"), // unknown section: last used
            Triple("bogus", "nope", "audio"), // unknown last used: Audio
            Triple("", null, "audio"),
        )
        for ((section, last, expected) in cases)
            assertEquals(expected, SettingsCategories.parse(section, last))
    }

    @Test
    fun deepLinks_SelectTheirCategory() {
        for (id in SettingsCategories.ids) {
            val route = HomeRoute.parse("settings/$id")
            assertEquals("settings", route.page)
            assertEquals(id, SettingsCategories.parse(route.section))
        }
        assertEquals("audio", SettingsCategories.parse(HomeRoute.parse("settings").section))
    }

    @Test
    fun upDown_MoveWithinTheList_ClampedAtBothEnds() {
        assertEquals("answers", SettingsCategories.move("audio", +1))
        assertEquals("audio", SettingsCategories.move("audio", -1))
        assertEquals("updates", SettingsCategories.move("updates", +1))
        assertEquals("privacy", SettingsCategories.move("updates", -1))
        assertEquals("audio", SettingsCategories.move("bogus", +1))
        assertEquals(6, SettingsCategories.indexOf("updates"))
        assertEquals(-1, SettingsCategories.indexOf(null))
    }

    // ---------------- reset to defaults ----------------

    /** Same properties and defaults as the C# test's FakeSettings mirror of AppSettings. */
    enum class FakeFont { Compact, Normal, Large, ExtraLarge }

    class FakeSettings {
        var playbackDeviceId: String? = null
        var settingsVersion: Int = 3
        var answerModel: String = "gpt-5.4-mini"
        var transcriptionModel: String = "gpt-4o-transcribe"
        var realtimeProtocol: String = "auto"
        var fontPreset: FakeFont = FakeFont.Large
        var windowOpacity: Double = 0.97
        var vadSensitivity: Double = 1.0
        var useFastCache: Boolean = true
        var compactMode: Boolean = false
        var alwaysOnTop: Boolean = true
        var showDiagnostics: Boolean = false
        var saveSessionTranscript: Boolean = false
        var hotkeyToggleWindow: String = "Ctrl+Alt+Space"
        var hotkeyStartPause: String = "Ctrl+Alt+L"
        var hotkeyManualInput: String = "Ctrl+Alt+Q"
        var hotkeyCoachToggle: String = "Ctrl+Alt+C"
        var hotkeyResetAdaptive: String = "Ctrl+Alt+R"
        var mode: String = "cloud"
        var uiLanguage: String? = null
        var answerLanguage: String = "same"
        var reportLanguage: String? = null
        var authorizedUseAcknowledged: Boolean = false
        var autoFitAnswer: Boolean = true
        var startInCoachMode: Boolean = false
        var cloudDeviceId: UUID? = null
        var updateChannel: String = "stable"
        var left: Double? = null
        var top: Double? = null
        var width: Double = 720.0
        var height: Double = 560.0
        var firstRunCompleted: Boolean = false
        var liveIntroDismissed: Boolean = false
        var interviewFocusPreset: String? = null
        var interviewFocusIntroSeen: Boolean = false
        var focusRestoreLeft: Double? = null
        var focusRestoreTop: Double? = null
        var focusRestoreWidth: Double? = null
        var focusRestoreHeight: Double? = null
        var homeLeft: Double? = null
        var homeTop: Double? = null
        var homeWidth: Double = 980.0
        var homeHeight: Double = 700.0
        var homeMaximized: Boolean = false
        var activeProfileId: String? = null
        var activeTargetId: String? = null
        var priceTranscribePerMinute: Double = 0.017
        var priceInputPerMTok: Double = 0.75
        var priceOutputPerMTok: Double = 4.50
    }

    private data class Prop(val name: String, val getter: Method, val setter: Method)

    private fun fakeProps(): List<Prop> {
        val methods = FakeSettings::class.java.declaredMethods
        return methods.filter { it.name.startsWith("get") && it.parameterCount == 0 && it.name != "getClass" }
            .map { g ->
                val name = g.name.substring(3)
                Prop(name, g, methods.first { it.name == "set$name" && it.parameterCount == 1 })
            }
            .sortedBy { it.name }
    }

    /** Every value differs from its default (what a long-time user's settings file could hold). */
    private fun customized(): FakeSettings {
        val s = FakeSettings()
        for (p in fakeProps()) {
            val current = p.getter.invoke(s)
            val value: Any? = when (p.getter.returnType) {
                String::class.java -> "custom-" + p.name
                java.lang.Boolean.TYPE -> !(current as Boolean)
                java.lang.Double.TYPE -> (current as Double) + 0.5
                java.lang.Double::class.java -> 123.0
                Integer.TYPE -> (current as Int) + 7
                UUID::class.java -> UUID.randomUUID()
                FakeFont::class.java -> FakeFont.Compact
                else -> throw UnsupportedOperationException(p.getter.returnType.name)
            }
            p.setter.invoke(s, value)
        }
        val fresh = FakeSettings()
        for (p in fakeProps()) assertTrue("customized ${p.name}", p.getter.invoke(fresh) != p.getter.invoke(s))
        return s
    }

    @Test
    fun everyAppSetting_IsClassifiedExactlyOnce() {
        val propNames = fakeProps().map { it.name }
        assertEquals(49, propNames.size)
        val classified = SettingsDefaults.preferenceFields + SettingsDefaults.protected + SettingsDefaults.notOnSettingsPage
        assertEquals(classified.size, classified.distinct().size) // no field in two lists
        assertEquals(propNames.sorted(), classified.sorted()) // a new setting must be classified
        // each category owns its fields alone
        val owned = SettingsCategories.ids.flatMap { id -> SettingsDefaults.fieldsOf(id) }
        assertEquals(owned.size, owned.distinct().size)
        for (id in SettingsCategories.ids) assertTrue(SettingsDefaults.fieldsOf(id).isNotEmpty())
    }

    @Test
    fun protectedFields_CoverAccountSessionWorkspaceFirstRunPlacementAndUiLanguage() {
        for (f in listOf(
            "Mode", "CloudDeviceId", "ActiveProfileId", "ActiveTargetId", "AuthorizedUseAcknowledged", "FirstRunCompleted", "UiLanguage",
            "Left", "Top", "Width", "Height", "HomeLeft", "HomeTop", "HomeWidth", "HomeHeight", "HomeMaximized",
        )) {
            assertTrue(f, SettingsDefaults.protected.contains(f))
            assertFalse(f, SettingsDefaults.preferenceFields.contains(f))
        }
        // the fields the brief lists per section
        assertEquals(listOf("PlaybackDeviceId"), SettingsDefaults.fieldsOf("audio"))
        assertTrue(SettingsDefaults.fieldsOf("answers").contains("FontPreset"))
        assertTrue(SettingsDefaults.fieldsOf("answers").contains("UseFastCache"))
        assertEquals(listOf("VadSensitivity"), SettingsDefaults.fieldsOf("listening"))
        assertEquals(listOf("WindowOpacity", "AlwaysOnTop", "CompactMode", "AutoFitAnswer"), SettingsDefaults.fieldsOf("window"))
        assertTrue(SettingsDefaults.fieldsOf("hotkeys").contains("HotkeyToggleWindow"))
        assertEquals(listOf("SaveSessionTranscript"), SettingsDefaults.fieldsOf("privacy"))
        assertEquals(listOf("UpdateChannel"), SettingsDefaults.fieldsOf("updates"))
    }

    @Test
    fun resetSection_ResetsExactlyItsFields() {
        for (category in listOf("audio", "answers", "listening", "window", "hotkeys", "privacy", "updates")) {
            val s = customized()
            val before = customized() // same customized values (Guid differs, but CloudDeviceId is never reset)
            before.cloudDeviceId = s.cloudDeviceId
            val fresh = FakeSettings()
            val changed = SettingsDefaults.reset(s, FakeSettings(), category)
            val fields = SettingsDefaults.fieldsOf(category)
            assertEquals("$category changed", fields.sorted(), changed.sorted())
            for (p in fakeProps()) {
                val expected = if (p.name in fields) p.getter.invoke(fresh) else p.getter.invoke(before)
                assertEquals("$category: ${p.name}", expected, p.getter.invoke(s))
            }
        }
    }

    @Test
    fun restoreAll_EqualsFreshDefaultsForPreferences_AndNeverTouchesProtectedFields() {
        val s = customized()
        val keep = fakeProps().associate { p -> p.name to p.getter.invoke(s) }
        val fresh = FakeSettings()
        val changed = SettingsDefaults.reset(s, FakeSettings(), null)
        assertEquals(SettingsDefaults.preferenceFields.sorted(), changed.sorted())
        for (p in fakeProps()) {
            if (p.name in SettingsDefaults.preferenceFields) {
                assertEquals(p.name, p.getter.invoke(fresh), p.getter.invoke(s))
            } else {
                assertEquals(p.name, keep[p.name], p.getter.invoke(s)) // account, session, workspace, first run, placement, languages …
            }
        }
        // already at defaults: nothing changes, nothing to apply
        assertTrue(SettingsDefaults.reset(s, FakeSettings(), null).isEmpty())
        assertTrue(SettingsDefaults.reset(s, FakeSettings(), "bogus").isEmpty())
    }

    @Test
    fun resetEffect_RebuildsTheEngineOnlyWhenNeeded() {
        assertEquals(ResetEffect(false, false), SettingsDefaults.effectOf(listOf("WindowOpacity", "UpdateChannel")))
        assertEquals(ResetEffect(true, false), SettingsDefaults.effectOf(listOf("VadSensitivity")))
        assertEquals(ResetEffect(true, false), SettingsDefaults.effectOf(listOf("UseFastCache")))
        assertEquals(ResetEffect(true, true), SettingsDefaults.effectOf(listOf("AnswerModel")))
    }

    /** The en copy assertions of the (otherwise source-level) ResetAndRestore dialog test. */
    @Test
    fun resetRestoreCopy_MatchesTheInAppDialogTexts() {
        val en = Loc.tableFor("en")
        assertEquals("Restore defaults?", en["set_restore_title"])
        assertEquals("This resets Interview Assistant preferences. Your account, profiles, interviews and reports will not be changed.", en["set_restore_body"])
        assertTrue(en["set_restore_section_body"]!!.startsWith("This resets the {0} preferences."))
        assertEquals("Restore defaults", en["set_restore_confirm"])
    }

    /** The en copy assertion of the (otherwise source-level) Updates-in-Settings test. */
    @Test
    fun updatesCopy_InstallNote() {
        assertEquals(
            "Updates download in the background and install when you close the app — never during an interview.",
            Loc.tableFor("en")["set_upd_install_note"],
        )
    }
}
