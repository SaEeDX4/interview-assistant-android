package com.interviewassistant.app.ui.settings

import com.interviewassistant.app.services.AnswerServiceProbe
import com.interviewassistant.app.services.BuildInfo
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.uistate.SettingsCategories
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.FakeHomeDialogs
import com.interviewassistant.app.viewmodels.VmCloudFixture
import com.interviewassistant.app.viewmodels.VmFixture
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * P4-G: the Settings page controller (the C# `SettingsPage.xaml.cs`) over the real MainViewModel
 * fixture — the editing model (load / preview / discard / save), the hotkey validation table, the
 * per-category reset ownership of [com.interviewassistant.app.uistate.SettingsDefaults] applied
 * through the controller, the immediate channel save and the updates seam, honestly consumed.
 * (Reset ownership at the UiState level is pinned by Rc5SettingsTests; this suite pins the
 * controller's application of it.)
 */
class SettingsPageControllerTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private lateinit var cloud: VmCloudFixture
    private lateinit var fixture: VmFixture
    private lateinit var dialogs: FakeHomeDialogs
    private lateinit var controller: SettingsPageControllerImpl

    /** Every ApplySettings call the controller made, as (engineChanged, modelChanged). */
    private val applied = mutableListOf<Pair<Boolean, Boolean>>()
    private var savedCount = 0
    private val restored = mutableListOf<String?>()

    @Before
    fun setUp() {
        SettingsPageControllerImpl.lastCategory = null
        cloud = VmCloudFixture()
        fixture = VmFixture(cloud)
        dialogs = FakeHomeDialogs()
        applied.clear()
        savedCount = 0
        restored.clear()
        SecretStore.clear()
    }

    @After
    fun tearDown() {
        BuildInfo.installInformationalVersion("0.0.0")
        fixture.close()
    }

    private fun buildController(
        probe: suspend (key: () -> String?, model: String) -> AnswerServiceProbe.Result =
            { _, _ -> AnswerServiceProbe.Result(true, 1, null) },
    ): SettingsPageControllerImpl {
        val built = SettingsPageControllerImpl(
            vm = fixture.vm,
            dialogs = dialogs,
            scope = fixture.scope,
            postToUi = { it() },
            applySettings = { engine, model -> applied += engine to model },
            probe = probe,
        )
        built.savedListeners += { savedCount++ }
        built.defaultsRestoredListeners += { restored += it }
        controller = built
        return built
    }

    private val settings get() = cloud.settings

    // ================= load =================

    @Test
    fun initialLoad_MirrorsTheStoredSettings_AndStartsClean() {
        settings.answerModel = "gpt-4.1"
        settings.vadSensitivity = 1.4
        settings.saveSessionTranscript = true
        buildController()

        assertEquals(SettingsCategories.Audio, controller.category)
        assertEquals(0, controller.deviceSelectedIndex) // the null-id "System default"
        assertEquals("gpt-4.1", controller.modelText)
        assertEquals(1.4, controller.vadSensitivity, 0.0)
        assertTrue(controller.saveTranscript)
        assertEquals(Loc.t("set_key_none"), controller.keyStatus)
        assertEquals("Ctrl+Alt+Space", controller.hkToggleText)
        assertFalse(controller.guard.isDirty)
    }

    @Test
    fun loadFromSettings_IsANoOpWhileDirty() {
        buildController()
        controller.onVadChanged(1.5)
        controller.loadFromSettings()
        assertEquals(1.5, controller.vadSensitivity, 1e-9)
        assertTrue(controller.guard.isDirty)
    }

    @Test
    fun showSection_SelectsAndFocuses_KnownSectionsOnly() {
        buildController()
        controller.showSection("hotkeys")
        assertEquals(SettingsCategories.Hotkeys, controller.category)
        assertEquals(SettingsPageControllerImpl.FOCUS_HK_TOGGLE, controller.focusTarget)

        controller.showSection("audio")
        assertEquals(SettingsCategories.Audio, controller.category)
        assertEquals(SettingsPageControllerImpl.FOCUS_DEVICE, controller.focusTarget)

        controller.showSection("bogus")
        assertEquals(SettingsCategories.Audio, controller.category) // the last used category
        assertEquals(SettingsPageControllerImpl.FOCUS_DEVICE, controller.focusTarget) // unchanged: no focus jump
    }

    // ================= audio preview =================

    @Test
    fun deviceSelection_PreviewsLiveAtOnce() {
        buildController()
        controller.onDeviceSelected(1)

        assertEquals("dev-1", settings.playbackDeviceId)
        assertEquals(1, fixture.capture.startCalls)
        assertEquals("dev-1", fixture.capture.lastDeviceId)
        assertTrue(controller.guard.isDirty)
    }

    @Test
    fun discard_RevertsThePreview_AndReloads() {
        buildController()
        controller.onDeviceSelected(1)
        controller.onVadChanged(1.5)
        controller.discard()

        assertNull(settings.playbackDeviceId)
        assertEquals(2, fixture.capture.startCalls)
        assertNull(fixture.capture.lastDeviceId)
        assertEquals(0, controller.deviceSelectedIndex)
        assertEquals(1.0, controller.vadSensitivity, 0.0)
        assertFalse(controller.guard.isDirty)
    }

    @Test
    fun guardDiscard_RevertsOnlyThePreview() {
        buildController()
        controller.onDeviceSelected(1)
        controller.onVadChanged(1.5)
        controller.guard.discard!!()

        // The C#'s Guard.Discard = RevertPreview: the audio returns, the other edits stay pending.
        assertNull(settings.playbackDeviceId)
        assertEquals(1.5, controller.vadSensitivity, 1e-9)
        assertTrue(controller.guard.isDirty)
    }

    // ================= save =================

    @Test
    fun save_WritesThePendingEdits_AndRaisesSaved() {
        buildController()
        controller.onVadChanged(1.5)
        controller.onFastCacheChanged(false)
        controller.onSaveTranscriptChanged(true)

        assertTrue(controller.save())

        assertEquals(1.5, settings.vadSensitivity, 1e-9)
        assertFalse(settings.useFastCache)
        assertTrue(settings.saveSessionTranscript)
        assertEquals(1, savedCount)
        assertEquals(listOf(true to false), applied) // VAD + FastCache are engine changes only
        assertFalse(controller.guard.isDirty)
    }

    @Test
    fun save_HotkeyOnlyChange_AppliesWithNeitherFlag() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Toggle, "Ctrl+Alt+P")

        assertTrue(controller.save())
        assertEquals("Ctrl+Alt+P", settings.hotkeyToggleWindow)
        assertEquals(listOf(false to false), applied)
    }

    @Test
    fun save_TrimsTheStoredShortcutStrings() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Toggle, "  Ctrl+Alt+Space  ")
        assertTrue(controller.save())
        assertEquals("Ctrl+Alt+Space", settings.hotkeyToggleWindow)
    }

    // ================= hotkey validation =================

    @Test
    fun validation_UnparseableShortcut_BlocksTheSave_AndJumpsToHotkeys() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Toggle, "Foo Bar")

        assertFalse(controller.save())
        assertEquals(SettingsCategories.Hotkeys, controller.category)
        assertEquals(SettingsPageControllerImpl.FOCUS_HK_TOGGLE, controller.focusTarget)
        assertTrue(controller.hkToggleHasError)
        assertEquals(Loc.f("set_hotkey_invalid", "Foo Bar"), controller.hkToggleError)
        assertTrue(applied.isEmpty())
        assertEquals(0, savedCount)
    }

    @Test
    fun validation_ShortcutWithoutAModifier_IsInvalid() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Start, "Space")

        assertFalse(controller.save())
        assertEquals(SettingsPageControllerImpl.FOCUS_HK_START, controller.focusTarget)
        assertTrue(controller.hkStartHasError)
        assertEquals(Loc.f("set_hotkey_invalid", "Space"), controller.hkStartError)
    }

    @Test
    fun validation_ConflictBetweenTheEditableFields_BlocksTheSave() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Start, "Ctrl+Alt+Space") // the Toggle value

        assertFalse(controller.save())
        assertEquals(SettingsPageControllerImpl.FOCUS_HK_START, controller.focusTarget)
        assertEquals(Loc.f("set_hotkey_conflict", "Ctrl+Alt+Space"), controller.hkStartError)
        assertFalse(controller.hkToggleHasError)
    }

    @Test
    fun validation_ConflictWithTheFixedCoachAndResetShortcuts_BlocksTheSave() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Manual, "Ctrl+Alt+C") // Coach's fixed value
        assertFalse(controller.save())
        assertEquals(SettingsPageControllerImpl.FOCUS_HK_MANUAL, controller.focusTarget)
        assertEquals(Loc.f("set_hotkey_conflict", "Ctrl+Alt+C"), controller.hkManualError)

        val second = buildController()
        second.onHotkeyTextChanged(HotkeyField.Toggle, "Ctrl+Alt+R") // Reset-adaptive's fixed value
        assertFalse(second.save())
        assertEquals(Loc.f("set_hotkey_conflict", "Ctrl+Alt+R"), second.hkToggleError)
    }

    @Test
    fun validation_Conflicts_AreDetectedOnTheParsedCombination_NotTheText() {
        buildController()
        settings.hotkeyCoachToggle = "ctrl+alt+c" // stored in another case: same combination
        controller.onHotkeyTextChanged(HotkeyField.Manual, "Ctrl+Alt+C")

        assertFalse(controller.save())
        assertTrue(controller.hkManualHasError)
    }

    @Test
    fun validation_EditingAField_ClearsItsErrorFlag_ButKeepsTheMessage() {
        buildController()
        controller.onHotkeyTextChanged(HotkeyField.Toggle, "Foo Bar")
        assertFalse(controller.save())
        assertTrue(controller.hkToggleHasError)

        controller.onHotkeyTextChanged(HotkeyField.Toggle, "Ctrl+Alt+P")
        // Field.ClearOnEdit: the flag clears, the inline message stays until the next save.
        assertFalse(controller.hkToggleHasError)
        assertEquals(Loc.f("set_hotkey_invalid", "Foo Bar"), controller.hkToggleError)
    }

    // ================= reset =================

    @Test
    fun resetSection_RestoresOnlyThatCategory() {
        settings.vadSensitivity = 1.0
        buildController()
        controller.onVadChanged(1.5)
        controller.onSaveTranscriptChanged(true)
        controller.onOpacityChanged(0.8)

        controller.applyReset(SettingsCategories.Listening)

        assertEquals(1.0, settings.vadSensitivity, 0.0) // restored (and saved)
        assertEquals(1.0, controller.vadSensitivity, 0.0)
        // The other categories' pending edits are untouched and still pending.
        assertTrue(controller.saveTranscript)
        assertEquals(0.8, controller.windowOpacity, 0.0)
        assertFalse(settings.saveSessionTranscript)
        assertTrue(controller.guard.isDirty)
        assertEquals(listOf(SettingsCategories.Listening), restored)
    }

    @Test
    fun resetSection_PreservesADevicePreviewOutsideAudio() {
        buildController()
        controller.onDeviceSelected(1) // preview: dev-1
        controller.applyReset(SettingsCategories.Listening)

        assertEquals("dev-1", settings.playbackDeviceId) // the preview is still live
        assertEquals(1, controller.deviceSelectedIndex)
    }

    @Test
    fun resetAudio_DropsThePreview_ForTheDefaultDevice() {
        buildController()
        controller.onDeviceSelected(1)
        controller.applyReset(SettingsCategories.Audio)

        assertNull(settings.playbackDeviceId)
        assertEquals(0, controller.deviceSelectedIndex)
    }

    @Test
    fun restoreAll_RestoresEverything_AndLeavesUnclassifiedStateAlone() {
        settings.interviewFocusPreset = "TopLeft"
        settings.firstRunCompleted = true
        buildController()
        controller.onVadChanged(1.6)
        controller.onSaveTranscriptChanged(true)
        controller.onHotkeyTextChanged(HotkeyField.Toggle, "Ctrl+Alt+P")

        controller.applyReset(null)

        assertEquals(1.0, settings.vadSensitivity, 0.0)
        assertFalse(settings.saveSessionTranscript)
        assertEquals("Ctrl+Alt+Space", settings.hotkeyToggleWindow)
        assertEquals("TopLeft", settings.interviewFocusPreset) // not a Settings category field
        assertTrue(settings.firstRunCompleted)
        assertFalse(controller.guard.isDirty)
        assertEquals(listOf<String?>(null), restored)
    }

    @Test
    fun reset_CancelledInTheDialog_ChangesNothing() = runBlocking {
        buildController()
        controller.onVadChanged(1.5)
        dialogs.confirmAnswer = false

        val done = controller.resetAsync(SettingsCategories.Listening)

        assertFalse(done)
        assertEquals(1.5, controller.vadSensitivity, 1e-9)
        assertEquals(1.0, settings.vadSensitivity, 0.0) // never written either way here
        assertTrue(restored.isEmpty())
        assertTrue(applied.isEmpty())
    }

    // ================= developer key =================

    @Test
    fun removeKey_IsImmediate_AndMarksTheNextSave() {
        buildController()
        controller.removeKey()

        assertFalse(SecretStore.hasKey)
        assertEquals(Loc.t("set_key_removed"), controller.keyStatus)
        assertEquals(listOf(true to true), applied) // the engine is rebuilt right away
    }

    @Test
    fun testKey_ReportsTheProbeResult() {
        var probedKey: String? = null
        var probedModel: String? = null
        buildController { key, model ->
            probedKey = key()
            probedModel = model
            AnswerServiceProbe.Result(ok = true, firstTokenMs = 321, error = null)
        }
        controller.onKeyTextChanged("  sk-test  ")
        controller.testKey()

        assertEquals("sk-test", probedKey)
        assertEquals("gpt-5.4-mini", probedModel)
        assertEquals("✓ " + Loc.f("set_key_ok", 321L, "gpt-5.4-mini"), controller.keyStatus)
    }

    @Test
    fun testKey_WithoutAnyKey_AsksForOne() {
        buildController()
        controller.testKey()
        assertEquals(Loc.t("set_key_enter"), controller.keyStatus)
    }

    // ================= updates =================

    @Test
    fun refreshUpdates_MapsTheBuildAndTheServiceState() {
        BuildInfo.installInformationalVersion("2.1.0-rc.5+abc1234567")
        fixture.updates.statusText = "All good"
        fixture.updates.updateReady = true
        fixture.updates.canCheck = true
        buildController()
        controller.refreshUpdates()

        assertEquals("2.1.0-rc.5", controller.updateVersionText)
        assertEquals("abc1234", controller.updateCommitText)
        assertEquals(Loc.t("channel_rc"), controller.updateChannelText)
        assertEquals("All good", controller.updateStatusText)
        assertTrue(controller.updateRestartVisible)
        assertFalse(controller.updateCheckVisible) // Check hides while a restart is offered
    }

    @Test
    fun channelChange_SavesImmediately_AndAsksForARestart() {
        buildController()
        controller.onChannelSelected(1)

        assertEquals("beta", settings.updateChannel)
        assertEquals(Loc.t("updates_channel_restart"), controller.updateStatusText)
        assertFalse(controller.guard.isDirty) // not a pending edit
    }

    @Test
    fun channelIndex_ReflectsTheStoredChannel() {
        settings.updateChannel = "beta"
        buildController()
        assertEquals(1, controller.channelIndex)
    }

    @Test
    fun checkUpdates_PassesTheSessionState_AndRefreshes() {
        buildController()
        controller.checkUpdates()
        assertEquals(listOf(false), fixture.updates.checkNowCalls) // no session in the fixture
    }

    @Test
    fun restartUpdate_DuringASession_Warns() {
        fixture.updates.tryApplyResult = false
        buildController()
        controller.restartUpdate()
        assertEquals(listOf(Loc.t("warn_update_session") to DialogTone.Warning), dialogs.alerts)
    }

    @Test
    fun restartUpdate_WhenAllowed_DoesNotWarn() {
        fixture.updates.tryApplyResult = true
        buildController()
        controller.restartUpdate()
        assertTrue(dialogs.alerts.isEmpty())
    }

    // ================= relocalize =================

    @Test
    fun relocalize_KeepsTheDeviceSelection_AndStaysClean() {
        buildController()
        controller.relocalize()
        assertEquals(0, controller.deviceSelectedIndex)
        assertFalse(controller.guard.isDirty)
    }
}
