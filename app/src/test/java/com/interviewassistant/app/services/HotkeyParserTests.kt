package com.interviewassistant.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P4-G: the hotkey parser's parity with the C#'s `HotkeyService.TryParse` (incl. the
 * `Enum.TryParse<Key>` semantics that come from .NET, not from the app's own code). The C# has no
 * unit tests pinning TryParse directly; these pin the behaviors the Settings save validation and
 * the (P5) registration service both rely on — the stored defaults first.
 */
class HotkeyParserTests {

    @Test
    fun defaults_ParseToCtrlAltPlusTheirKey() {
        // The five stored defaults, verbatim (AppSettings defaults, cross-platform settings parity).
        assertEquals(HotkeyParser.Combo(3, 0x20), HotkeyParser.tryParse("Ctrl+Alt+Space"))
        assertEquals(HotkeyParser.Combo(3, 0x4C), HotkeyParser.tryParse("Ctrl+Alt+L"))
        assertEquals(HotkeyParser.Combo(3, 0x51), HotkeyParser.tryParse("Ctrl+Alt+Q"))
        assertEquals(HotkeyParser.Combo(3, 0x43), HotkeyParser.tryParse("Ctrl+Alt+C"))
        assertEquals(HotkeyParser.Combo(3, 0x52), HotkeyParser.tryParse("Ctrl+Alt+R"))
    }

    @Test
    fun aliases_AndCase_AreInsigificant() {
        assertEquals(HotkeyParser.Combo(3, 0x20), HotkeyParser.tryParse("control+ALT+space"))
        assertEquals(HotkeyParser.Combo(3, 0x20), HotkeyParser.tryParse("CTRL+Alt+SPACE"))
        assertEquals(HotkeyParser.Combo(0x1 or 0x4 or 0x8, 0x70), HotkeyParser.tryParse("Win+Shift+Alt+F1"))
        assertEquals(HotkeyParser.Combo(2, 0x09), HotkeyParser.tryParse("Ctrl + Tab"))
    }

    @Test
    fun blank_NoModifier_AndUnknownParts_Fail() {
        assertNull(HotkeyParser.tryParse(null))
        assertNull(HotkeyParser.tryParse(""))
        assertNull(HotkeyParser.tryParse("   "))
        assertNull(HotkeyParser.tryParse("Space")) // a key with no modifier
        assertNull(HotkeyParser.tryParse("Ctrl+Alt")) // modifiers with no key
        assertNull(HotkeyParser.tryParse("Ctrl+Banana"))
        assertNull(HotkeyParser.tryParse("Banana+Space"))
    }

    @Test
    fun emptySegments_AreIgnored_AndTheLastKeyPartWins() {
        assertEquals(HotkeyParser.Combo(3, 0x4C), HotkeyParser.tryParse("Ctrl++Alt+L"))
        assertEquals(HotkeyParser.Combo(3, 0x4C), HotkeyParser.tryParse("Ctrl+Alt+L+"))
        assertEquals(HotkeyParser.Combo(3, 0x51), HotkeyParser.tryParse("Ctrl+Alt+L+Q"))
    }

    @Test
    fun numericParts_FollowEnumTryParse() {
        // A decimal integer parses as that WPF Key value: 97 = F1 (VK 0x70).
        assertEquals(HotkeyParser.Combo(3, 0x70), HotkeyParser.tryParse("Ctrl+Alt+97"))
        // 0 = Key.None: the only numeric value that fails the final check.
        assertNull(HotkeyParser.tryParse("Ctrl+Alt+0"))
        // An undefined numeric value still parses (Enum.TryParse accepts it); its VK is 0.
        assertEquals(HotkeyParser.Combo(3, 0), HotkeyParser.tryParse("Ctrl+Alt+999"))
        // A comma-separated list combines with OR: 25 (Space) | 97 (F1) = 121 = NumLock (VK 0x90).
        assertEquals(HotkeyParser.Combo(3, 0x90), HotkeyParser.tryParse("Ctrl+Alt+Space,F1"))
    }

    @Test
    fun modifierNamedKeys_AsKeyParts_ParseAsKeys() {
        // "LeftCtrl" is not a modifier name, so it lands in the key slot like the C# (Key.LeftCtrl).
        assertEquals(HotkeyParser.Combo(2, 0xA2), HotkeyParser.tryParse("Ctrl+LeftCtrl"))
        // ... and alone it has no modifier, so the combination fails.
        assertNull(HotkeyParser.tryParse("LeftCtrl"))
    }
}
