package com.interviewassistant.app.platform

import android.view.KeyEvent
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.HotkeyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5: the D2 shortcut matcher — a key event (as fsModifiers + Win32 vk) fires the action whose
 * stored combination it equals. The stored strings are the C#'s own (defaults first); matching
 * goes through the same [HotkeyParser] the Settings validation uses.
 */
class ShortcutMatcherTest {

    private val ctrlAlt: Int = HotkeyParser.MOD_CONTROL or HotkeyParser.MOD_ALT

    @Test
    fun defaults_MatchTheirActions() {
        val settings = AppSettings()
        assertEquals(ShortcutAction.ToggleWindow, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x20)) // Space
        assertEquals(ShortcutAction.StartPause, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x4C)) // L
        assertEquals(ShortcutAction.ManualInput, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x51)) // Q
        assertEquals(ShortcutAction.Coach, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x43)) // C
        assertEquals(ShortcutAction.ResetAdaptive, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x52)) // R
    }

    @Test
    fun nonMatches_ReturnNull() {
        val settings = AppSettings()
        assertNull(ShortcutMatcher.actionFor(settings, ctrlAlt, 0x41)) // Ctrl+Alt+A is unbound
        assertNull(ShortcutMatcher.actionFor(settings, HotkeyParser.MOD_CONTROL, 0x20)) // Ctrl alone
        assertNull(ShortcutMatcher.actionFor(settings, 0, 0x20)) // no modifiers
    }

    @Test
    fun customStoredStrings_AreHonored() {
        val settings = AppSettings()
        settings.hotkeyStartPause = "Ctrl+Shift+P"
        val ctrlShift = HotkeyParser.MOD_CONTROL or HotkeyParser.MOD_SHIFT
        assertEquals(ShortcutAction.StartPause, ShortcutMatcher.actionFor(settings, ctrlShift, 0x50))
        assertNull(ShortcutMatcher.actionFor(settings, ctrlAlt, 0x4C)) // the old combination is gone
    }

    @Test
    fun unparseableStoredString_MatchesNothing_AndOthersStillWork() {
        val settings = AppSettings()
        settings.hotkeyCoachToggle = "Banana"
        assertNull(ShortcutMatcher.actionFor(settings, ctrlAlt, 0x43))
        assertEquals(ShortcutAction.ToggleWindow, ShortcutMatcher.actionFor(settings, ctrlAlt, 0x20))
        assertEquals(4, ShortcutMatcher.storedCombos(settings).size)
    }

    @Test
    fun keyEventMapping_ProducesTheCSharpCoordinates() {
        assertEquals(ctrlAlt, KeyEventMapping.modifiersOf(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON))
        assertEquals(HotkeyParser.MOD_SHIFT, KeyEventMapping.modifiersOf(KeyEvent.META_SHIFT_ON))
        assertEquals(0x4C, KeyEventMapping.vkOf(KeyEvent.KEYCODE_L))
        assertEquals(0x20, KeyEventMapping.vkOf(KeyEvent.KEYCODE_SPACE))
        assertEquals(0x70, KeyEventMapping.vkOf(KeyEvent.KEYCODE_F1))
        assertNull(KeyEventMapping.vkOf(KeyEvent.KEYCODE_VOLUME_UP)) // not a shortcut key
    }

    @Test
    fun onKeyEvent_DispatchesMatchesOnly() {
        val fired = mutableListOf<ShortcutAction>()
        ShortcutService.commandHandler = { action -> fired += action }
        try {
            val settings = AppSettings()
            assertTrue(
                ShortcutService.onKeyEvent(
                    settings,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
                    KeyEvent.KEYCODE_L,
                ),
            )
            assertEquals(listOf(ShortcutAction.StartPause), fired)
            assertFalse(ShortcutService.onKeyEvent(settings, KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_L))
            assertEquals(1, fired.size)
        } finally {
            ShortcutService.commandHandler = null
        }
    }
}
