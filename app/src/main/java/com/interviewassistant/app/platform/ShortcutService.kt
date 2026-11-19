package com.interviewassistant.app.platform

import android.view.KeyEvent
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.HotkeyParser

/**
 * The D2 substitute for `HotkeyService.cs` (TRANSFER-MAP §8 D2): the same five actions the C#
 * binds system-wide — toggle window, start/pause, manual input, coach, reset adaptive — fired
 * from the surfaces Android offers a normal app: the foreground service's notification actions
 * and hardware-keyboard shortcuts while the app is in front. The stored shortcut strings, their
 * defaults and their Settings validation are untouched (they are the C#'s own, P4-G); only the
 * trigger surface differs, which is exactly the recorded D2 residual gap.
 *
 * Matching is the C#'s parse in reverse: a stored string parses through [HotkeyParser] to
 * (fsModifiers, Win32 vk); a key event maps to the same pair through [KeyEventMapping]; equal
 * pairs fire the action. An unparseable stored string matches nothing (the C#'s `Register`
 * refuses it with `set_hotkey_invalid` at save time; Settings validation keeps one from being
 * stored, and a hand-edited settings.json degrades to "this shortcut does nothing", never a
 * crash).
 */
/**
 * The shortcut actions. The first five are the C#'s hotkeys, in its order; [Stop] is
 * notification-only (the FGS notification's third action) — it has no stored combination and
 * the keyboard matcher never produces it.
 */
enum class ShortcutAction { ToggleWindow, StartPause, ManualInput, Coach, ResetAdaptive, Stop }

/** Pure matching of key events (as fsModifiers + Win32 vk) against the stored settings strings. */
object ShortcutMatcher {
    /** The five C# actions and the settings property that stores each one's combination, in C# order. */
    fun storedCombos(settings: AppSettings): List<Pair<ShortcutAction, HotkeyParser.Combo>> {
        val stored = listOf(
            ShortcutAction.ToggleWindow to settings.hotkeyToggleWindow,
            ShortcutAction.StartPause to settings.hotkeyStartPause,
            ShortcutAction.ManualInput to settings.hotkeyManualInput,
            ShortcutAction.Coach to settings.hotkeyCoachToggle,
            ShortcutAction.ResetAdaptive to settings.hotkeyResetAdaptive,
        )
        return stored.mapNotNull { (action, text) ->
            HotkeyParser.tryParse(text)?.let { combo -> action to combo }
        }
    }

    /** The action whose stored combination equals (modifiers, vk), or null. First match wins. */
    fun actionFor(settings: AppSettings, modifiers: Int, vk: Int): ShortcutAction? =
        storedCombos(settings).firstOrNull { (_, combo) -> combo.modifiers == modifiers && combo.vk == vk }?.first
}

/**
 * Android key events in the C#'s coordinate system: `KeyEvent.metaState` becomes the user32
 * fsModifiers mask, `KeyEvent.keyCode` becomes the Win32 virtual-key code `KeyInterop` would
 * produce for the equivalent WPF `Key`. Only keys a hardware keyboard can combine with modifiers
 * into the stored shortcuts are mapped; anything else matches nothing.
 */
object KeyEventMapping {
    fun modifiersOf(metaState: Int): Int {
        var modifiers = 0
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers = modifiers or HotkeyParser.MOD_CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) modifiers = modifiers or HotkeyParser.MOD_ALT
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers = modifiers or HotkeyParser.MOD_SHIFT
        if (metaState and KeyEvent.META_META_ON != 0) modifiers = modifiers or HotkeyParser.MOD_WIN
        return modifiers
    }

    /** Win32 virtual-key code for an Android key code, or null when unmapped. */
    fun vkOf(keyCode: Int): Int? = VK_BY_KEYCODE[keyCode]

    private val VK_BY_KEYCODE: Map<Int, Int> = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 0x41 + i) // A–Z
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 0x30 + i) // 0–9
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 0x70 + i) // F1–F12
        put(KeyEvent.KEYCODE_SPACE, 0x20)
        put(KeyEvent.KEYCODE_TAB, 0x09)
        put(KeyEvent.KEYCODE_ENTER, 0x0D)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x0D)
        put(KeyEvent.KEYCODE_ESCAPE, 0x1B)
        put(KeyEvent.KEYCODE_DEL, 0x08) // Backspace
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x2E) // Delete
        put(KeyEvent.KEYCODE_INSERT, 0x2D)
        put(KeyEvent.KEYCODE_HOME, 0x24)
        put(KeyEvent.KEYCODE_MOVE_END, 0x23)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x21)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x22)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x25)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x26)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x27)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x28)
        put(KeyEvent.KEYCODE_MINUS, 0xBD)
        put(KeyEvent.KEYCODE_EQUALS, 0xBB)
        put(KeyEvent.KEYCODE_COMMA, 0xBC)
        put(KeyEvent.KEYCODE_PERIOD, 0xBE)
        put(KeyEvent.KEYCODE_SLASH, 0xBF)
        put(KeyEvent.KEYCODE_SEMICOLON, 0xBA)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0xDE)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0xDB)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0xDD)
        put(KeyEvent.KEYCODE_BACKSLASH, 0xDC)
        put(KeyEvent.KEYCODE_GRAVE, 0xC0)
    }
}

/**
 * Dispatch point for shortcut actions. Two handler tiers, mirroring where the C#'s actions land:
 * view-model actions (StartPause, Stop, Coach, ResetAdaptive) are handled process-wide by the
 * composition root (they execute the main view model's commands — the notification can fire
 * them with no activity alive); window actions (ToggleWindow, ManualInput) need the activity,
 * which installs its handler while it exists. A window action with no activity installed falls
 * to [activityStarter], which brings the activity forward (the notification's whole point).
 */
object ShortcutService {
    /** Handles the view-model actions; installed by the composition root. */
    var commandHandler: ((ShortcutAction) -> Unit)? = null

    /** Handles ToggleWindow / ManualInput; installed by MainActivity while it is alive. */
    var windowHandler: ((ShortcutAction) -> Unit)? = null

    /** Brings the activity forward for a window action nobody is installed to handle. */
    var activityStarter: ((ShortcutAction) -> Unit)? = null

    fun dispatch(action: ShortcutAction) {
        when (action) {
            ShortcutAction.ToggleWindow, ShortcutAction.ManualInput -> {
                val handler = windowHandler
                if (handler != null) handler(action) else activityStarter?.invoke(action)
            }
            else -> commandHandler?.invoke(action)
        }
    }

    /**
     * A hardware-keyboard key event (the activity's `dispatchKeyEvent` forwards key-downs from
     * keyboard devices here). Returns true when a stored shortcut matched and fired.
     */
    fun onKeyEvent(settings: AppSettings, metaState: Int, keyCode: Int): Boolean {
        val vk = KeyEventMapping.vkOf(keyCode) ?: return false
        val action = ShortcutMatcher.actionFor(settings, KeyEventMapping.modifiersOf(metaState), vk)
            ?: return false
        dispatch(action)
        return true
    }
}
