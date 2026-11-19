package com.interviewassistant.app.ui.settings

import com.interviewassistant.app.services.AnswerServiceProbe
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.BuildFlavor
import com.interviewassistant.app.services.BuildInfo
import com.interviewassistant.app.services.FontPreset
import com.interviewassistant.app.services.HotkeyParser
import com.interviewassistant.app.services.PlaybackDevice
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.uistate.DirtyStateGuard
import com.interviewassistant.app.uistate.SettingsCategories
import com.interviewassistant.app.uistate.SettingsDefaults
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.HomeDialogs
import com.interviewassistant.app.viewmodels.MainViewModel
import com.interviewassistant.app.viewmodels.ObservableObject
import com.interviewassistant.app.viewmodels.SettingsPageController
import com.interviewassistant.client.UpdatePolicy
import com.interviewassistant.core.languages.Loc
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One of the three editable shortcut fields of the Hotkeys category (the C#'s HkToggle / HkStart / HkManual). */
enum class HotkeyField { Toggle, Start, Manual }

/**
 * Mirror of `InterviewAssistant.App/Views/SettingsPage.xaml.cs`: the Settings page's editing model
 * over [AppSettings]. One category at a time; edits are pending until Save (the shell footer binds
 * the guard's dirty state); switching category is not navigation and keeps pending edits; a failed
 * hotkey validation selects the Hotkeys category, focuses the first bad field and returns false, so
 * Home's navigation guard keeps the user on the page. The audio device previews live (selecting
 * writes the setting and restarts capture at once); Discard — and the guard's own discard, which
 * Home invokes when the window closes — returns to the saved device. Reset / Restore-all ask in an
 * in-app dialog and then apply like a save, through the same [applySettings] the C# calls
 * `MainWindow.ApplySettings` with. The update channel is the one exception to the pending model:
 * it saves immediately (it only takes effect after a restart).
 *
 * Platform mappings (P4-PORT-NOTES DC-158…DC-161): the hotkey parser is [HotkeyParser] (the OS
 * registration service is P5); the C#'s keyboard-focus-within dirty rule for the two editable
 * combos has no Compose counterpart — controlled text fields only report user edits to the page's
 * callbacks, and every programmatic load runs under the guard's suspend, which covers the same
 * cases (DC-159); [applySettings] is constructor-injected because the C#'s `_main` (the Live
 * window) is a destination of the same activity here, hosted by MainActivity.
 */
class SettingsPageControllerImpl(
    private val vm: MainViewModel,
    private val dialogs: HomeDialogs,
    private val scope: CoroutineScope,
    private val postToUi: (() -> Unit) -> Unit,
    private val applySettings: (engineChanged: Boolean, modelChanged: Boolean) -> Unit,
    private val probe: suspend (key: () -> String?, model: String) -> AnswerServiceProbe.Result =
        { key, model -> AnswerServiceProbe.runAsync(key, model) },
) : ObservableObject(), SettingsPageController {

    companion object {
        /** Focus target: the Audio category's device combo (the C#'s DeviceBox). */
        const val FOCUS_DEVICE: String = "device"

        /** Focus target: the Toggle shortcut field (the C#'s HkToggle). */
        const val FOCUS_HK_TOGGLE: String = "hkToggle"

        /** Focus target: the Start/Pause shortcut field (the C#'s HkStart). */
        const val FOCUS_HK_START: String = "hkStart"

        /** Focus target: the Manual-input shortcut field (the C#'s HkManual). */
        const val FOCUS_HK_MANUAL: String = "hkManual"

        /** Last category shown in this app session: the Settings button reopens it (the C#'s static). */
        internal var lastCategory: String? = null

        // The sliders' ranges and ticks (audit-app §2.4): end-wait 0.6–1.8 step 0.1, opacity 0.75–1.0 step 0.05.
        private const val VAD_MIN: Double = 0.6
        private const val VAD_MAX: Double = 1.8
        private const val VAD_STEP: Double = 0.1
        private const val OPACITY_MIN: Double = 0.75
        private const val OPACITY_MAX: Double = 1.0
        private const val OPACITY_STEP: Double = 0.05
    }

    private val settings: AppSettings get() = vm.settings

    override val guard: DirtyStateGuard = DirtyStateGuard("settings")
    override val savedListeners: MutableList<() -> Unit> = mutableListOf()
    override val saveFailedListeners: MutableList<() -> Unit> = mutableListOf()
    override val defaultsRestoredListeners: MutableList<(String?) -> Unit> = mutableListOf()

    // ---- combo item sets (the C#'s Initialize) ----

    val modelItems: List<String> = listOf("gpt-5.4-mini", "gpt-4.1-mini", "gpt-5-mini", "gpt-4.1")
    val sttItems: List<String> = listOf("gpt-4o-transcribe", "gpt-4o-mini-transcribe")
    val protocolItems: List<String> = listOf("auto", "ga", "beta")

    /** The update channel combo's values, in display order (labels are `channel_stable` / `channel_beta`). */
    val channelValues: List<String> = listOf("stable", "beta")

    /** True in developer builds: the key panel and the model/protocol fields exist (the C#'s BuildFlavor gate). */
    val developerBuild: Boolean get() = BuildFlavor.Developer

    // ---- category state ----

    /** The category shown now (the C#'s CurrentCategory). */
    var category: String by bindable(SettingsCategories.Audio)
        private set

    /**
     * A one-shot focus request for the page: set when a section is deep-linked (Audio → the device
     * combo, Hotkeys → the Toggle field) or a save fails validation (the first bad shortcut field).
     * The page requests the focus, then calls [clearFocusTarget] (the C#'s Dispatcher.BeginInvoke
     * focus after layout).
     */
    var focusTarget: String? by bindable(null)
        private set

    fun clearFocusTarget() {
        focusTarget = null
    }

    // ---- Audio ----

    var deviceItems: List<PlaybackDevice> by bindable(emptyList())
        private set
    var deviceSelectedIndex: Int by bindable(-1)
        private set

    /** The device the picker shows as chosen (the C#'s `DeviceBox.SelectedItem`). */
    val selectedDevice: PlaybackDevice? get() = deviceItems.getOrNull(deviceSelectedIndex)

    /** The live level for the Audio meter (the C# page's DataContext is the main view model). */
    val audioLevelFlow: StateFlow<Double>

    // ---- Answers ----

    /** The developer key box's current text (never persisted anywhere but the secret store, on Save). */
    var keyText: String by bindable("")
    var keyStatus: String by bindable("")
        private set
    var modelText: String by bindable("")
        private set
    var sttText: String by bindable("")
        private set

    /** Index into [protocolItems]; -1 = the stored protocol is not in the list (the C#'s null selection). */
    var protocolIndex: Int by bindable(-1)
        private set

    /** Index into `FontPreset.entries`. */
    var fontIndex: Int by bindable(-1)
        private set
    var fastCache: Boolean by bindable(true)
        private set

    // ---- Listening / Window / Privacy ----

    var vadSensitivity: Double by bindable(1.0)
        private set
    var windowOpacity: Double by bindable(0.97)
        private set
    var alwaysOnTop: Boolean by bindable(true)
        private set
    var saveTranscript: Boolean by bindable(false)
        private set

    // ---- Hotkeys ----

    var hkToggleText: String by bindable("")
        private set
    var hkStartText: String by bindable("")
        private set
    var hkManualText: String by bindable("")
        private set
    var hkToggleError: String? by bindable(null)
        private set
    var hkStartError: String? by bindable(null)
        private set
    var hkManualError: String? by bindable(null)
        private set
    var hkToggleHasError: Boolean by bindable(false)
        private set
    var hkStartHasError: Boolean by bindable(false)
        private set
    var hkManualHasError: Boolean by bindable(false)
        private set

    // ---- Updates ----

    var updateVersionText: String by bindable("")
        private set
    var updateChannelText: String by bindable("")
        private set
    var updateCommitText: String by bindable("")
        private set
    var updateStatusText: String by bindable("")
        private set
    var updateRestartVisible: Boolean by bindable(false)
        private set
    var updateCheckVisible: Boolean by bindable(true)
        private set
    var updateCheckEnabled: Boolean by bindable(false)
        private set

    /** Index into [channelValues]. */
    var channelIndex: Int by bindable(0)
        private set

    // ---- internals ----

    /** Audio device saved in settings (the picker previews a device live; Discard returns to this one). */
    private var savedDeviceId: String? = null
    private var keyRemoved: Boolean = false

    init {
        // Building the state raises change events (a slider value coerces, lists rebuild): none of
        // that is a user edit — the C# constructs the page under the same suspension.
        guard.suspend().use {
            guard.save = { save() }
            guard.discard = { revertPreview() }
            vm.audioLevel // the read registers the bindable (the C1 pattern)
            audioLevelFlow = vm.propertyFlow("audioLevel")
            selectCategory(SettingsCategories.parse(null, lastCategory), focusField = false)
            loadFromSettings()
            refreshUpdates()
        }
    }

    // ================= categories =================

    override fun showSection(section: String?) {
        selectCategory(
            SettingsCategories.parse(section, lastCategory),
            focusField = SettingsCategories.isKnown(section?.trim()?.lowercase()),
        )
    }

    /** Selects a category from the list (a click or the Up/Down/Home/End keys): never navigation, edits stay. */
    fun selectCategory(id: String) = selectCategory(id, focusField = false)

    private fun selectCategory(id: String, focusField: Boolean) {
        category = id
        lastCategory = id
        if (id == SettingsCategories.Updates) refreshUpdates()
        if (!focusField) return
        focusTarget = when (id) {
            SettingsCategories.Audio -> FOCUS_DEVICE
            SettingsCategories.Hotkeys -> FOCUS_HK_TOGGLE
            else -> null
        }
    }

    /** Secondary help shown as ⓘ next to the category heading (the C#'s CategoryHintKey). */
    fun categoryHintKey(id: String): String? = when (id) {
        SettingsCategories.Audio -> "set_audio_hint"
        SettingsCategories.Hotkeys -> "set_hotkeys_hint"
        else -> null
    }

    // ================= values =================

    override fun loadFromSettings() {
        if (guard.isDirty) return
        guard.suspend().use {
            savedDeviceId = settings.playbackDeviceId
            keyRemoved = false
            for (id in SettingsCategories.ids) loadCategory(id)
            clearErrors()
        }
    }

    /** Shows the stored values of one category (the others keep what is on screen). */
    private fun loadCategory(id: String) {
        guard.suspend().use {
            when (id) {
                SettingsCategories.Audio -> loadDevices(settings.playbackDeviceId)
                SettingsCategories.Answers -> {
                    keyText = ""
                    keyStatus = if (SecretStore.hasKey) {
                        Loc.f("set_key_saved", SecretStore.mask(SecretStore.getApiKey()))
                    } else {
                        Loc.t("set_key_none")
                    }
                    modelText = settings.answerModel
                    sttText = settings.transcriptionModel
                    protocolIndex = protocolItems.indexOf(settings.realtimeProtocol)
                    fontIndex = FontPreset.entries.indexOf(settings.fontPreset)
                    fastCache = settings.useFastCache
                }
                SettingsCategories.Listening -> vadSensitivity = settings.vadSensitivity
                SettingsCategories.Window -> {
                    windowOpacity = settings.windowOpacity
                    alwaysOnTop = settings.alwaysOnTop
                }
                SettingsCategories.Hotkeys -> {
                    hkToggleText = settings.hotkeyToggleWindow
                    hkStartText = settings.hotkeyStartPause
                    hkManualText = settings.hotkeyManualInput
                    clearErrors()
                }
                SettingsCategories.Privacy -> saveTranscript = settings.saveSessionTranscript
                SettingsCategories.Updates -> loadUpdatePreference()
            }
        }
    }

    /** True when anything on the page differs from the stored settings (a pending edit). */
    internal fun differsFromStored(): Boolean {
        if (selectedDevice?.id != savedDeviceId) return true
        if (developerBuild) {
            val model = modelText.trim()
            val stt = sttText.trim()
            if (keyText.isNotEmpty() ||
                (model.isNotEmpty() && model != settings.answerModel) ||
                (stt.isNotEmpty() && stt != settings.transcriptionModel) ||
                (protocolItems.getOrNull(protocolIndex) ?: "auto") != settings.realtimeProtocol
            ) {
                return true
            }
        }
        return fontIndex != FontPreset.entries.indexOf(settings.fontPreset) ||
            fastCache != settings.useFastCache ||
            abs(vadSensitivity - settings.vadSensitivity) > 0.001 ||
            abs(windowOpacity - settings.windowOpacity) > 0.001 ||
            alwaysOnTop != settings.alwaysOnTop ||
            hkToggleText.trim() != settings.hotkeyToggleWindow ||
            hkStartText.trim() != settings.hotkeyStartPause ||
            hkManualText.trim() != settings.hotkeyManualInput ||
            saveTranscript != settings.saveSessionTranscript
    }

    override fun relocalize() {
        guard.suspend().use {
            // The "System default" device label is localized: rebuild the list, keep the chosen device.
            loadDevices(selectedDevice?.id)
            keyStatus = Loc.relocalize(keyStatus) ?: keyStatus
            hkToggleError = hkToggleError?.let { Loc.relocalize(it) ?: it }
            hkStartError = hkStartError?.let { Loc.relocalize(it) ?: it }
            hkManualError = hkManualError?.let { Loc.relocalize(it) ?: it }
            refreshUpdates()
        }
    }

    // ---- Audio ----

    private fun loadDevices(selectId: String?) {
        guard.suspend().use {
            val devices = vm.capture.listDevices()
            deviceItems = devices
            val index = devices.indexOfFirst { it.id == selectId }
            deviceSelectedIndex = if (index >= 0) index else if (devices.isNotEmpty()) 0 else -1
        }
    }

    /** The refresh button: rebuilds the device list, keeping the chosen device. */
    fun refreshDevices() = loadDevices(selectedDevice?.id)

    fun onDeviceSelected(index: Int) {
        val device = deviceItems.getOrNull(index) ?: return
        if (guard.isSuspended) return // list rebuilt by code, the same device stays selected
        deviceSelectedIndex = index
        // Live preview: the level meter follows the chosen device immediately; Save keeps it,
        // Discard returns to the saved one.
        settings.playbackDeviceId = device.id
        vm.restartAudio()
        guard.markDirty()
    }

    private fun revertPreview() {
        if (settings.playbackDeviceId != savedDeviceId) {
            settings.playbackDeviceId = savedDeviceId
            vm.restartAudio()
        }
    }

    override fun discard() {
        revertPreview()
        guard.markClean()
        loadFromSettings()
    }

    // ---- Answers ----

    fun onKeyTextChanged(text: String) {
        keyText = text
        guard.markDirty()
    }

    /** Tests the typed key (or the stored one when the box is empty) against the answer model. */
    fun testKey() {
        scope.launch {
            val key = if (keyText.isBlank()) SecretStore.getApiKey() else keyText.trim()
            if (key.isNullOrEmpty()) {
                postToUi { keyStatus = Loc.t("set_key_enter") }
                return@launch
            }
            postToUi { keyStatus = Loc.t("set_key_testing") }
            val model = modelText
            val result = probe({ key }, model)
            postToUi {
                keyStatus = if (result.ok) {
                    "✓ " + Loc.f("set_key_ok", result.firstTokenMs, model)
                } else {
                    "✗ " + (result.error ?: "")
                }
            }
        }
    }

    fun removeKey() {
        // Removing the key is immediate (a security action, not a pending edit): the engine is rebuilt right away.
        SecretStore.clear()
        guard.suspend().use { keyText = "" }
        keyStatus = Loc.t("set_key_removed")
        keyRemoved = true
        applySettings(engineChanged = true, modelChanged = true)
    }

    fun onModelTextChanged(text: String) {
        modelText = text
        guard.markDirty()
    }

    fun onModelItemSelected(index: Int) {
        modelItems.getOrNull(index)?.let { modelText = it }
        guard.markDirty()
    }

    fun onSttTextChanged(text: String) {
        sttText = text
        guard.markDirty()
    }

    fun onSttItemSelected(index: Int) {
        sttItems.getOrNull(index)?.let { sttText = it }
        guard.markDirty()
    }

    fun onProtocolSelected(index: Int) {
        protocolIndex = index
        guard.markDirty()
    }

    fun onFontSelected(index: Int) {
        fontIndex = index
        guard.markDirty()
    }

    fun onFastCacheChanged(checked: Boolean) {
        fastCache = checked
        guard.markDirty()
    }

    // ---- Listening / Window / Privacy ----

    fun onVadChanged(value: Double) {
        vadSensitivity = SliderSnap.snap(value, VAD_MIN, VAD_MAX, VAD_STEP)
        guard.markDirty()
    }

    fun onOpacityChanged(value: Double) {
        windowOpacity = SliderSnap.snap(value, OPACITY_MIN, OPACITY_MAX, OPACITY_STEP)
        guard.markDirty()
    }

    fun onTopmostChanged(checked: Boolean) {
        alwaysOnTop = checked
        guard.markDirty()
    }

    fun onSaveTranscriptChanged(checked: Boolean) {
        saveTranscript = checked
        guard.markDirty()
    }

    // ---- Hotkeys ----

    fun onHotkeyTextChanged(field: HotkeyField, text: String) {
        when (field) {
            HotkeyField.Toggle -> hkToggleText = text
            HotkeyField.Start -> hkStartText = text
            HotkeyField.Manual -> hkManualText = text
        }
        setFieldError(field, null) // Field.ClearOnEdit: editing clears the field's error flag
        guard.markDirty()
    }

    private fun hotkeyText(field: HotkeyField): String = when (field) {
        HotkeyField.Toggle -> hkToggleText
        HotkeyField.Start -> hkStartText
        HotkeyField.Manual -> hkManualText
    }

    private fun setFieldError(field: HotkeyField, message: String?) {
        when (field) {
            HotkeyField.Toggle -> {
                if (message != null) hkToggleError = message
                hkToggleHasError = message != null
            }
            HotkeyField.Start -> {
                if (message != null) hkStartError = message
                hkStartHasError = message != null
            }
            HotkeyField.Manual -> {
                if (message != null) hkManualError = message
                hkManualHasError = message != null
            }
        }
    }

    private fun clearErrors() {
        hkToggleError = null
        hkStartError = null
        hkManualError = null
        hkToggleHasError = false
        hkStartHasError = false
        hkManualHasError = false
    }

    // ================= save =================

    override fun save(): Boolean {
        clearErrors()
        val taken = mutableMapOf<Pair<Int, Int>, String>()
        taken[comboOf(settings.hotkeyCoachToggle)] = settings.hotkeyCoachToggle
        taken[comboOf(settings.hotkeyResetAdaptive)] = settings.hotkeyResetAdaptive
        var firstBad: HotkeyField? = null
        for (field in HotkeyField.entries) {
            val text = hotkeyText(field).trim()
            val parsed = HotkeyParser.tryParse(text)
            if (parsed == null) {
                setFieldError(field, Loc.f("set_hotkey_invalid", text))
                if (firstBad == null) firstBad = field
            } else if (taken.putIfAbsent(parsed.modifiers to parsed.vk, text) != null) {
                setFieldError(field, Loc.f("set_hotkey_conflict", text))
                if (firstBad == null) firstBad = field
            }
        }
        if (firstBad != null) {
            // the problem is shown in its category (made visible first, so the field can take focus after layout)
            selectCategory(SettingsCategories.Hotkeys, focusField = false)
            focusTarget = when (firstBad) {
                HotkeyField.Toggle -> FOCUS_HK_TOGGLE
                HotkeyField.Start -> FOCUS_HK_START
                HotkeyField.Manual -> FOCUS_HK_MANUAL
            }
            return false
        }

        var engineChanged = keyRemoved
        var modelChanged = keyRemoved
        if (developerBuild && keyText.isNotBlank()) {
            SecretStore.setApiKey(keyText)
            engineChanged = true
            modelChanged = true
        }
        val model = modelText.trim()
        val stt = sttText.trim()
        val proto = protocolItems.getOrNull(protocolIndex) ?: "auto"
        if (developerBuild &&
            ((model.isNotEmpty() && model != settings.answerModel) ||
                (stt.isNotEmpty() && stt != settings.transcriptionModel) ||
                proto != settings.realtimeProtocol)
        ) {
            engineChanged = true
            modelChanged = true
        }
        if (abs(vadSensitivity - settings.vadSensitivity) > 0.001 || fastCache != settings.useFastCache) {
            engineChanged = true
        }
        settings.answerModel = if (model.isNotEmpty()) model else settings.answerModel
        settings.transcriptionModel = if (stt.isNotEmpty()) stt else settings.transcriptionModel
        settings.realtimeProtocol = proto
        settings.fontPreset = FontPreset.entries.getOrNull(fontIndex) ?: settings.fontPreset
        settings.useFastCache = fastCache
        settings.vadSensitivity = vadSensitivity
        settings.windowOpacity = windowOpacity
        settings.alwaysOnTop = alwaysOnTop
        settings.hotkeyToggleWindow = hkToggleText.trim()
        settings.hotkeyStartPause = hkStartText.trim()
        settings.hotkeyManualInput = hkManualText.trim()
        settings.saveSessionTranscript = saveTranscript
        settings.playbackDeviceId = selectedDevice?.id
        val written = settings.save()
        savedDeviceId = settings.playbackDeviceId
        keyRemoved = false
        guard.suspend().use { keyText = "" }
        keyStatus = if (SecretStore.hasKey) {
            Loc.f("set_key_saved", SecretStore.mask(SecretStore.getApiKey()))
        } else {
            Loc.t("set_key_none")
        }
        guard.markClean()
        applySettings(engineChanged, modelChanged)
        if (written) {
            for (listener in savedListeners.toList()) listener()
        } else {
            for (listener in saveFailedListeners.toList()) listener()
        }
        return true
    }

    private fun comboOf(text: String): Pair<Int, Int> =
        HotkeyParser.tryParse(text)?.let { it.modifiers to it.vk } ?: (0 to 0)

    // ================= reset to defaults =================

    /** "Reset this section": asks in the in-app dialog, then restores. */
    fun resetSection() {
        scope.launch { resetAsync(category) }
    }

    /** "Restore all defaults": asks in the in-app dialog, then restores. */
    fun restoreAll() {
        scope.launch { resetAsync(null) }
    }

    /**
     * The reset flow of the C#'s `ResetAsync`: confirm (Cancel is the default), then [applyReset].
     * False = cancelled, nothing changed.
     */
    internal suspend fun resetAsync(category: String?): Boolean {
        val message = if (category == null) {
            Loc.t("set_restore_body")
        } else {
            Loc.f(
                "set_restore_section_body",
                Loc.t(SettingsCategories.all[SettingsCategories.indexOf(category)].titleKey),
            )
        }
        val confirmed = dialogs.confirm(
            message = message,
            confirmText = Loc.t("set_restore_confirm"),
            destructive = true,
            title = Loc.t("set_restore_title"),
        )
        if (!confirmed) return false
        applyReset(category)
        return true
    }

    /**
     * Restores the defaults of one category (or all) like a save: stored, applied and confirmed.
     * Only that category's fields change ([SettingsDefaults]); pending edits of other categories
     * stay pending, and an unsaved device preview outside the Audio category stays previewed.
     */
    internal fun applyReset(category: String?) {
        val preview = settings.playbackDeviceId // an unsaved device preview is not a stored value
        settings.playbackDeviceId = savedDeviceId
        val changed = SettingsDefaults.reset(settings, AppSettings(), category)
        val written = settings.save()
        savedDeviceId = settings.playbackDeviceId
        if (category == null) {
            guard.markClean()
            loadFromSettings()
        } else {
            if (category != SettingsCategories.Audio) settings.playbackDeviceId = preview
            loadCategory(category)
            if (!differsFromStored()) guard.markClean()
        }
        if ("UpdateChannel" in changed) updateStatusText = Loc.t("updates_channel_restart")
        val effect = SettingsDefaults.effectOf(changed)
        applySettings(effect.engineChanged, effect.modelChanged)
        if (written) {
            for (listener in defaultsRestoredListeners.toList()) listener(category)
        } else {
            for (listener in saveFailedListeners.toList()) listener()
        }
    }

    // ================= updates =================

    private fun loadUpdatePreference() {
        guard.suspend().use {
            channelIndex = if (UpdatePolicy.normalizeChannel(settings.updateChannel) == "beta") 1 else 0
        }
    }

    override fun refreshUpdates() {
        val u = vm.updates
        updateVersionText = BuildInfo.version
        updateChannelText = BuildInfo.channelLabel
        updateCommitText = BuildInfo.commit
        updateStatusText = Loc.relocalize(u.statusText) ?: ""
        updateRestartVisible = u.updateReady
        // Builds that cannot update (unsigned, portable, no feed) keep the button disabled; the status says why.
        updateCheckVisible = !u.updateReady
        updateCheckEnabled = u.canCheck && !u.isChecking
    }

    fun onChannelSelected(index: Int) {
        // Saved right away, not through the Save footer: the preference only takes effect after a restart.
        if (guard.isSuspended) return
        val channel = channelValues.getOrNull(index) ?: return
        channelIndex = index
        if (channel == settings.updateChannel) return
        settings.updateChannel = channel
        settings.save()
        updateStatusText = Loc.t("updates_channel_restart")
    }

    fun checkUpdates() {
        scope.launch {
            vm.updates.checkNowAsync(vm.sessionActive)
            postToUi { refreshUpdates() }
        }
    }

    fun restartUpdate() {
        if (!vm.updates.tryApplyAndRestart(vm.sessionActive)) {
            scope.launch { dialogs.alert(Loc.t("warn_update_session"), DialogTone.Warning) }
        }
    }

}
