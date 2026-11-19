package com.interviewassistant.app.services

import com.interviewassistant.app.uistate.SettingsDefaults
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The AppSettings mirror against the real model: defaults (audit §3.1), save/load round-trip,
 * the v1→v2→v3 migrations, the settings.json file format System.Text.Json writes, and the
 * SettingsDefaults classification invariant executed against the REAL properties (the Windows
 * Rc5SettingsTests runs it against a FakeSettings mirror + the AppSettings.cs source; here the
 * real class is on the classpath, so the source-derived property list is pinned below).
 * The "Settings persistence" check mirrors Services/SelfTest.cs.
 */
class AppSettingsTests {
    private lateinit var dir: File
    private lateinit var settingsFile: File
    private var savedOverride: String? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("appsettings-test").toFile()
        settingsFile = File(dir, "settings.json")
        savedOverride = AppSettings.overridePath
        AppSettings.overridePath = settingsFile.path
    }

    @After
    fun tearDown() {
        AppSettings.overridePath = savedOverride
        dir.deleteRecursively()
    }

    // ---------------- defaults ----------------

    @Test
    fun defaults_MatchTheWindowsModel() {
        val s = AppSettings()
        assertNull(s.playbackDeviceId)
        assertEquals(3, s.settingsVersion)
        assertEquals("gpt-5.4-mini", s.answerModel)
        assertEquals("gpt-4o-transcribe", s.transcriptionModel)
        assertEquals("auto", s.realtimeProtocol)
        assertEquals(FontPreset.Large, s.fontPreset)
        assertEquals(0.97, s.windowOpacity, 0.0)
        assertEquals(1.0, s.vadSensitivity, 0.0)
        assertTrue(s.useFastCache)
        assertFalse(s.compactMode)
        assertTrue(s.alwaysOnTop)
        assertFalse(s.showDiagnostics)
        assertFalse(s.saveSessionTranscript)
        assertEquals("Ctrl+Alt+Space", s.hotkeyToggleWindow)
        assertEquals("Ctrl+Alt+L", s.hotkeyStartPause)
        assertEquals("Ctrl+Alt+Q", s.hotkeyManualInput)
        assertEquals("Ctrl+Alt+C", s.hotkeyCoachToggle)
        assertEquals("Ctrl+Alt+R", s.hotkeyResetAdaptive)
        assertEquals("cloud", s.mode)
        assertNull(s.uiLanguage)
        assertEquals("same", s.answerLanguage)
        assertNull(s.reportLanguage)
        assertFalse(s.authorizedUseAcknowledged)
        assertTrue(s.autoFitAnswer)
        assertFalse(s.startInCoachMode)
        assertNull(s.cloudDeviceId)
        assertEquals("stable", s.updateChannel)
        assertNull(s.left)
        assertNull(s.top)
        assertEquals(720.0, s.width, 0.0)
        assertEquals(560.0, s.height, 0.0)
        assertFalse(s.firstRunCompleted)
        assertFalse(s.liveIntroDismissed)
        assertNull(s.interviewFocusPreset)
        assertFalse(s.interviewFocusIntroSeen)
        assertNull(s.focusRestoreLeft)
        assertNull(s.focusRestoreTop)
        assertNull(s.focusRestoreWidth)
        assertNull(s.focusRestoreHeight)
        assertNull(s.homeLeft)
        assertNull(s.homeTop)
        assertEquals(980.0, s.homeWidth, 0.0)
        assertEquals(700.0, s.homeHeight, 0.0)
        assertFalse(s.homeMaximized)
        assertNull(s.activeProfileId)
        assertNull(s.activeTargetId)
        assertEquals(0.017, s.priceTranscribePerMinute, 0.0)
        assertEquals(0.75, s.priceInputPerMTok, 0.0)
        assertEquals(4.50, s.priceOutputPerMTok, 0.0)
    }

    @Test
    fun fontSizes_FollowThePreset() {
        assertEquals(16.0, AppSettings(fontPreset = FontPreset.Compact).answerFontSize, 0.0)
        assertEquals(14.0, AppSettings(fontPreset = FontPreset.Compact).questionFontSize, 0.0)
        assertEquals(19.0, AppSettings(fontPreset = FontPreset.Normal).answerFontSize, 0.0)
        assertEquals(16.0, AppSettings(fontPreset = FontPreset.Normal).questionFontSize, 0.0)
        assertEquals(22.0, AppSettings(fontPreset = FontPreset.Large).answerFontSize, 0.0)
        assertEquals(17.0, AppSettings(fontPreset = FontPreset.Large).questionFontSize, 0.0)
        assertEquals(26.0, AppSettings(fontPreset = FontPreset.ExtraLarge).answerFontSize, 0.0)
        assertEquals(19.0, AppSettings(fontPreset = FontPreset.ExtraLarge).questionFontSize, 0.0)
    }

    // ---------------- persistence (SelfTest "Settings persistence" + full round-trip) ----------------

    @Test
    fun settingsPersistence_SelfTestCheck() {
        val s = AppSettings(answerModel = "selftest-model", windowOpacity = 0.85)
        assertTrue(s.save())
        val back = AppSettings.load()
        assertEquals("selftest-model", back.answerModel)
        assertEquals(0.85, back.windowOpacity, 1e-9)
    }

    @Test
    fun saveLoad_RoundTripsEveryProperty() {
        val s = customized()
        assertTrue(s.save())
        val back = AppSettings.load()
        for (p in props()) assertEquals("round-trip ${p.name}", p.getter.invoke(s), p.getter.invoke(back))
    }

    @Test
    fun save_IsAtomic_AndLeavesNoTempFile() {
        assertTrue(AppSettings().save())
        assertTrue(settingsFile.isFile)
        assertFalse(File(settingsFile.path + ".tmp").exists())
    }

    @Test
    fun load_MissingFile_UsesDefaults() {
        val back = AppSettings.load()
        assertEquals("gpt-5.4-mini", back.answerModel)
        assertEquals(3, back.settingsVersion)
    }

    @Test
    fun load_CorruptFile_UsesDefaults() {
        settingsFile.writeText("{ not json")
        assertEquals("gpt-5.4-mini", AppSettings.load().answerModel)
        settingsFile.writeText("")
        assertEquals("gpt-5.4-mini", AppSettings.load().answerModel)
        // a string where System.Text.Json expects the numeric enum: the whole file is refused, as in C#
        settingsFile.writeText("""{"FontPreset": "Large", "AnswerModel": "changed"}""")
        assertEquals("gpt-5.4-mini", AppSettings.load().answerModel)
    }

    @Test
    fun currentPath_FollowsTheOverridePath() {
        assertEquals(settingsFile.path, AppSettings.currentPath)
    }

    // ---------------- migrations ----------------

    @Test
    fun migration_V1File_UpgradesBothModelsAndTheVersion() {
        settingsFile.writeText("""{"SettingsVersion": 1, "AnswerModel": "gpt-4.1-mini", "TranscriptionModel": "gpt-live-transcribe"}""")
        val back = AppSettings.load()
        assertEquals(3, back.settingsVersion)
        assertEquals("gpt-5.4-mini", back.answerModel)
        assertEquals("gpt-4o-transcribe", back.transcriptionModel)
    }

    @Test
    fun migration_V1File_KeepsModelsTheUserChanged() {
        settingsFile.writeText("""{"SettingsVersion": 1, "AnswerModel": "gpt-4o", "TranscriptionModel": "gpt-4o-mini-transcribe"}""")
        val back = AppSettings.load()
        assertEquals(3, back.settingsVersion)
        assertEquals("gpt-4o", back.answerModel)
        assertEquals("gpt-4o-mini-transcribe", back.transcriptionModel)
    }

    @Test
    fun migration_V2File_UpgradesOnlyTheTranscriptionModel() {
        settingsFile.writeText("""{"SettingsVersion": 2, "AnswerModel": "gpt-4.1-mini", "TranscriptionModel": "gpt-live-transcribe"}""")
        val back = AppSettings.load()
        assertEquals(3, back.settingsVersion)
        assertEquals("gpt-4.1-mini", back.answerModel) // the v1 leg does not run for a v2 file
        assertEquals("gpt-4o-transcribe", back.transcriptionModel)
    }

    @Test
    fun migration_FileWithoutAVersion_KeepsTheDefaultVersionAndModels() {
        // System.Text.Json leaves the initializer default (3) when the property is absent: no leg runs.
        settingsFile.writeText("""{"AnswerModel": "gpt-4.1-mini", "TranscriptionModel": "gpt-live-transcribe"}""")
        val back = AppSettings.load()
        assertEquals(3, back.settingsVersion)
        assertEquals("gpt-4.1-mini", back.answerModel)
        assertEquals("gpt-live-transcribe", back.transcriptionModel)
    }

    // ---------------- file format ----------------

    @Test
    fun fileFormat_MatchesTheWindowsSpellings() {
        assertTrue(AppSettings().save())
        val text = settingsFile.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("{\n  \"PlaybackDeviceId\": null,"))
        assertTrue(text.contains("\"SettingsVersion\": 3"))
        assertTrue(text.contains("\"FontPreset\": 2")) // enum as ordinal, like System.Text.Json's plain options
        assertTrue(text.contains("\"WindowOpacity\": 0.97"))
        assertTrue(text.contains("\"VadSensitivity\": 1")) // integral doubles carry no fraction
        assertTrue(text.contains("\"Width\": 720"))
        assertTrue(text.contains("\"HomeWidth\": 980"))
        assertTrue(text.contains("\"PriceTranscribePerMinute\": 0.017"))
        assertTrue(text.contains("\"PriceInputPerMTok\": 0.75"))
        assertTrue(text.contains("\"PriceOutputPerMTok\": 4.5"))
        assertTrue(text.contains("\"CloudDeviceId\": null"))
        assertFalse(text.contains("720.0"))
        // declaration order, with the two derived get-only echoes last (System.Text.Json writes them too)
        assertTrue(text.indexOf("\"AnswerModel\"") < text.indexOf("\"PriceOutputPerMTok\""))
        assertTrue(text.indexOf("\"PriceOutputPerMTok\"") < text.indexOf("\"AnswerFontSize\""))
        assertTrue(text.endsWith("\"AnswerFontSize\": 22,\n  \"QuestionFontSize\": 17\n}"))
    }

    @Test
    fun fileFormat_GuidIsTheLowercaseHyphenatedForm() {
        val id = UUID.fromString("3F2504E0-4F89-41D3-9A0C-0305E82C3301")
        assertTrue(AppSettings(cloudDeviceId = id).save())
        val text = settingsFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"CloudDeviceId\": \"3f2504e0-4f89-41d3-9a0c-0305e82c3301\""))
        assertEquals(id, AppSettings.load().cloudDeviceId)
    }

    // ---------------- classification invariant against the REAL model ----------------

    /** The 49 get/set properties of AppSettings.cs, in source order (the C# test reads them from source). */
    private val expectedProperties = listOf(
        "PlaybackDeviceId", "SettingsVersion", "AnswerModel", "TranscriptionModel", "RealtimeProtocol",
        "FontPreset", "WindowOpacity", "VadSensitivity", "UseFastCache", "CompactMode", "AlwaysOnTop",
        "ShowDiagnostics", "SaveSessionTranscript", "HotkeyToggleWindow", "HotkeyStartPause",
        "HotkeyManualInput", "HotkeyCoachToggle", "HotkeyResetAdaptive", "Mode", "UiLanguage",
        "AnswerLanguage", "ReportLanguage", "AuthorizedUseAcknowledged", "AutoFitAnswer",
        "StartInCoachMode", "CloudDeviceId", "UpdateChannel", "Left", "Top", "Width", "Height",
        "FirstRunCompleted", "LiveIntroDismissed", "InterviewFocusPreset", "InterviewFocusIntroSeen",
        "FocusRestoreLeft", "FocusRestoreTop", "FocusRestoreWidth", "FocusRestoreHeight",
        "HomeLeft", "HomeTop", "HomeWidth", "HomeHeight", "HomeMaximized",
        "ActiveProfileId", "ActiveTargetId",
        "PriceTranscribePerMinute", "PriceInputPerMTok", "PriceOutputPerMTok",
    )

    private data class Prop(val name: String, val getter: Method, val setter: Method)

    private fun props(): List<Prop> {
        val methods = AppSettings::class.java.declaredMethods.filter { !it.isSynthetic }
        return methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .mapNotNull { g ->
                val name = g.name.substring(3)
                val setter = methods.firstOrNull { it.name == "set$name" && it.parameterCount == 1 }
                if (setter == null) null else Prop(name, g, setter)
            }
            .sortedBy { it.name }
    }

    @Test
    fun everyAppSetting_IsClassifiedExactlyOnce_AgainstTheRealModel() {
        val names = props().map { it.name }
        assertEquals(expectedProperties.sorted(), names)
        val classified = SettingsDefaults.preferenceFields + SettingsDefaults.protected + SettingsDefaults.notOnSettingsPage
        assertEquals("no field in two lists", classified.size, classified.distinct().size)
        assertEquals(expectedProperties.sorted(), classified.sorted()) // a new setting must be classified
        val owned = SettingsDefaults.fieldsByCategory.values.flatten()
        assertEquals(owned.size, owned.distinct().size)
        for (id in SettingsDefaults.fieldsByCategory.keys) assertTrue(SettingsDefaults.fieldsOf(id).isNotEmpty())
    }

    @Test
    fun reset_WithTheRealSettings_ChangesOnlyTheSectionFields() {
        for (category in SettingsDefaults.fieldsByCategory.keys) {
            val s = customized()
            val before = snapshot(s)
            val changed = SettingsDefaults.reset(s, AppSettings(), category)
            val fields = SettingsDefaults.fieldsOf(category)
            assertEquals(fields.sorted(), changed.sorted())
            val fresh = AppSettings()
            for (p in props()) {
                val expected = if (p.name in fields) p.getter.invoke(fresh) else before[p.name]
                assertEquals("$category: ${p.name}", expected, p.getter.invoke(s))
            }
        }
    }

    @Test
    fun restoreAll_WithTheRealSettings_NeverTouchesProtectedFields() {
        val s = customized()
        val before = snapshot(s)
        val changed = SettingsDefaults.reset(s, AppSettings(), null)
        assertEquals(SettingsDefaults.preferenceFields.sorted(), changed.sorted())
        val fresh = AppSettings()
        for (p in props()) {
            val expected = if (p.name in SettingsDefaults.preferenceFields) p.getter.invoke(fresh) else before[p.name]
            assertEquals(p.name, expected, p.getter.invoke(s))
        }
        assertTrue(SettingsDefaults.reset(s, AppSettings(), null).isEmpty())
        assertTrue(SettingsDefaults.reset(s, AppSettings(), "bogus").isEmpty())
    }

    // ---------------- helpers (the C# Customized(), against the real model) ----------------

    private fun snapshot(s: AppSettings): Map<String, Any?> = props().associate { it.name to it.getter.invoke(s) }

    /** Every value differs from its default (what a long-time user's settings file could hold). */
    private fun customized(): AppSettings {
        val s = AppSettings()
        for (p in props()) {
            val current = p.getter.invoke(s)
            val value: Any? = when (p.getter.returnType) {
                String::class.java -> "custom-" + p.name
                java.lang.Boolean.TYPE -> !(current as Boolean)
                java.lang.Double.TYPE -> (current as Double) + 0.5
                java.lang.Double::class.java -> 123.0
                Integer.TYPE -> (current as Int) + 7
                UUID::class.java -> UUID.randomUUID()
                FontPreset::class.java -> FontPreset.Compact
                else -> throw UnsupportedOperationException(p.getter.returnType.name)
            }
            p.setter.invoke(s, value)
        }
        val fresh = AppSettings()
        for (p in props()) assertTrue("customized ${p.name}", p.getter.invoke(fresh) != p.getter.invoke(s))
        return s
    }
}
