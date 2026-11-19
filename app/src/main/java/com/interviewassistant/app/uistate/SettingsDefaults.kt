package com.interviewassistant.app.uistate

import java.lang.reflect.Method

/** Mirror of `InterviewAssistant.App/UiState/SettingsDefaults.cs`. */

/** What applying the changed fields needs: an engine rebuild, and whether model choices changed. */
data class ResetEffect(val engineChanged: Boolean, val modelChanged: Boolean)

/**
 * "Reset this section" and "Restore all defaults" on the Settings page. Each category owns an explicit list of preference
 * fields (AppSettings property names); a reset copies exactly those fields from a fresh `AppSettings`. Account,
 * session, workspace, device id, first-run and authorized-use state, window placement and the UI language are never
 * touched ([protected]), and neither are values that the Settings page does not show ([notOnSettingsPage]).
 * Every AppSettings property is in exactly one of these lists (checked by a unit test), so a new setting must be classified.
 */
object SettingsDefaults {
    /** Preference fields per category, in category order. */
    val fieldsByCategory: Map<String, List<String>> = mapOf(
        SettingsCategories.Audio to listOf("PlaybackDeviceId"),
        SettingsCategories.Answers to listOf("AnswerModel", "TranscriptionModel", "RealtimeProtocol", "FontPreset", "UseFastCache"),
        SettingsCategories.Listening to listOf("VadSensitivity"),
        SettingsCategories.Window to listOf("WindowOpacity", "AlwaysOnTop", "CompactMode", "AutoFitAnswer"),
        SettingsCategories.Hotkeys to listOf("HotkeyToggleWindow", "HotkeyStartPause", "HotkeyManualInput", "HotkeyCoachToggle", "HotkeyResetAdaptive"),
        SettingsCategories.Privacy to listOf("SaveSessionTranscript"),
        SettingsCategories.Updates to listOf("UpdateChannel"),
    )

    /** Every preference field that "Restore all defaults" resets. */
    val preferenceFields: List<String> = SettingsCategories.ids.flatMap { id -> fieldsByCategory[id]!! }

    /** Never changed by a reset: who the user is, what they agreed to, their data and where their windows are. */
    val protected: List<String> = listOf(
        "Mode", "CloudDeviceId", "ActiveProfileId", "ActiveTargetId", // account, session and workspace
        "AuthorizedUseAcknowledged", "FirstRunCompleted", // first run and authorized use
        "UiLanguage", // app language (Home sidebar)
        "Left", "Top", "Width", "Height", "HomeLeft", "HomeTop", "HomeWidth", "HomeHeight", "HomeMaximized", // window placement
        "FocusRestoreLeft", "FocusRestoreTop", "FocusRestoreWidth", "FocusRestoreHeight", // placement before Interview Focus
        "SettingsVersion", // file format
    )

    /** Also never changed by a reset: chosen elsewhere in the app (Live window, interview and report pickers, diagnostics). */
    val notOnSettingsPage: List<String> = listOf(
        "AnswerLanguage", "ReportLanguage", "StartInCoachMode", "ShowDiagnostics", "LiveIntroDismissed",
        "InterviewFocusPreset", "InterviewFocusIntroSeen", // Interview Focus (Live window → More)
        "PriceTranscribePerMinute", "PriceInputPerMTok", "PriceOutputPerMTok",
    )

    /** Changing one of these rebuilds the answer engine (live-safe during an interview). */
    val engineFields: List<String> = listOf("AnswerModel", "TranscriptionModel", "RealtimeProtocol", "UseFastCache", "VadSensitivity")
    /** Changing one of these (developer builds) applies only after the interview stops. */
    val modelFields: List<String> = listOf("AnswerModel", "TranscriptionModel", "RealtimeProtocol")

    /** The fields a reset of [category] changes; null = all preference fields; unknown = none. */
    fun fieldsOf(category: String?): List<String> =
        if (category == null) preferenceFields else fieldsByCategory[category] ?: emptyList()

    /**
     * Copies the fields of [category] (null = every preference field) from [defaults]
     * (a freshly constructed settings object) into [target]. Returns the fields whose value changed.
     *
     * The C# reads/writes the properties by reflection (System.Reflection over the settings type); the JVM
     * counterpart is Java reflection over the bean accessors (getX/setX) — see P4-PORT-NOTES DC-66.
     */
    fun <T : Any> reset(target: T, defaults: T, category: String?): List<String> {
        val changed = mutableListOf<String>()
        for (name in fieldsOf(category)) {
            val getter = findAccessor(target.javaClass, "get", name)
                ?: throw IllegalStateException("${target.javaClass.simpleName} has no setting $name")
            val setter = findAccessor(target.javaClass, "set", name)
                ?: throw IllegalStateException("${target.javaClass.simpleName} has no setting $name")
            val value = getter.invoke(defaults)
            if (getter.invoke(target) == value) continue
            setter.invoke(target, value)
            changed.add(name)
        }
        return changed
    }

    private fun findAccessor(type: Class<*>, prefix: String, name: String): Method? {
        val methodName = prefix + name
        var c: Class<*>? = type
        while (c != null) {
            val m = c.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == (if (prefix == "get") 0 else 1) }
            if (m != null) {
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        return null
    }

    /** What applying the changed fields needs: an engine rebuild, and whether model choices changed. */
    fun effectOf(changed: List<String>): ResetEffect {
        val set = changed.toSet()
        return ResetEffect(engineFields.any { it in set }, modelFields.any { it in set })
    }
}
