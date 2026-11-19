package com.interviewassistant.app.services

/**
 * Mirror of the pure half of `InterviewAssistant.App/Services/HotkeyService.cs`: turning a stored
 * shortcut string ("Ctrl+Alt+Space") into a (modifiers, virtual-key) pair. The platform half —
 * registering the combination with the OS — is the P5 platform service (TRANSFER-MAP §3.2 row 15:
 * on Android the stored strings bind to the D2 substitutes; the strings and defaults are preserved
 * verbatim for cross-platform settings parity). The parser lands here, in P4-G, because the
 * Settings page's save validation is its first consumer (P4-PORT-NOTES DC-158).
 *
 * The port follows `HotkeyService.TryParse` + `KeyInterop.VirtualKeyFromKey` exactly, including
 * the parts that come from `Enum.TryParse<Key>` rather than from the app's own code: a part is a
 * WPF `Key` name (case-insensitive), a decimal integer (any Int value parses as that Key value —
 * only 0, `Key.None`, fails the final check), or a comma-separated list of those, combined with
 * OR. A key with no Win32 virtual-key code maps to vk 0 and still parses, exactly as in the C#.
 */
object HotkeyParser {
    // The C#'s fsModifiers values (user32 MOD_*), without MOD_NOREPEAT (a registration flag).
    const val MOD_ALT: Int = 0x0001
    const val MOD_CONTROL: Int = 0x0002
    const val MOD_SHIFT: Int = 0x0004
    const val MOD_WIN: Int = 0x0008

    /** A parsed shortcut: the modifier mask and the Win32 virtual-key code of the key part. */
    data class Combo(val modifiers: Int, val vk: Int)

    /**
     * Parses [text] ("Ctrl+Alt+Space") into a [Combo], or null when the C#'s TryParse would fail:
     * blank text, an unparsable part, no key part, a final key of `Key.None`, or no modifier.
     */
    fun tryParse(text: String?): Combo? {
        if (text.isNullOrBlank()) return null
        var modifiers = 0
        var key: Int? = null // the WPF Key enum value of the last key part seen
        for (rawPart in text.split('+')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue // StringSplitOptions.RemoveEmptyEntries | TrimEntries
            when (part.lowercase()) {
                "ctrl", "control" -> modifiers = modifiers or MOD_CONTROL
                "alt" -> modifiers = modifiers or MOD_ALT
                "shift" -> modifiers = modifiers or MOD_SHIFT
                "win" -> modifiers = modifiers or MOD_WIN
                else -> key = parseKeyPart(part) ?: return null
            }
        }
        val k = key ?: return null
        if (k == KEY_NONE || modifiers == 0) return null
        return Combo(modifiers, VK_BY_KEY_VALUE[k] ?: 0)
    }

    private const val KEY_NONE: Int = 0

    /**
     * `Enum.TryParse<Key>(part, ignoreCase: true)`: a name from the Key table, a decimal integer,
     * or a comma-separated list of names/integers combined with OR. Null when any element fails.
     */
    private fun parseKeyPart(part: String): Int? {
        var combined = 0
        for (element in part.split(',')) {
            val e = element.trim()
            if (e.isEmpty()) return null
            val value = KEY_VALUE_BY_NAME[e.lowercase()] ?: e.toIntOrNull() ?: return null
            combined = combined or value
        }
        return combined
    }

    /** WPF `Key` name (lowercased) → enum value, in the enum's declaration order (values pinned by the table below). */
    private val KEY_VALUE_BY_NAME: Map<String, Int> = run {
        val entries = mutableListOf<Pair<String, Int>>()
        fun add(name: String, value: Int) {
            entries += name.lowercase() to value
        }
                add("None", 0); add("Cancel", 1); add("Back", 2); add("Tab", 3); add("LineFeed", 4)
        add("Clear", 5); add("Return", 6); add("Enter", 7); add("Shift", 8); add("Control", 9)
        add("Alt", 10); add("Pause", 11); add("Capital", 12); add("KanaMode", 13)
        add("HanguelMode", 14); add("HangulMode", 15); add("JunjaMode", 16); add("FinalMode", 17)
        add("HanjaMode", 18); add("KanjiMode", 19); add("Escape", 20); add("ImeConvert", 21)
        add("ImeNonConvert", 22); add("ImeAccept", 23); add("ImeModeChange", 24); add("Space", 25)
        add("Prior", 26); add("PageUp", 26); add("Next", 27); add("PageDown", 27); add("End", 28)
        add("Home", 29); add("Left", 30); add("Up", 31); add("Right", 32); add("Down", 33)
        add("Select", 34); add("Print", 35); add("Execute", 36); add("Snapshot", 37)
        add("Insert", 38); add("Delete", 39); add("Help", 40)
        for (i in 0..9) add("D$i", 41 + i)
        for (i in 0..25) add(('A' + i).toString(), 51 + i)
        add("LWin", 77); add("RWin", 78); add("Apps", 79); add("Sleep", 80)
        for (i in 0..9) add("NumPad$i", 81 + i)
        add("Multiply", 91); add("Add", 92); add("Separator", 93); add("Subtract", 94)
        add("Decimal", 95); add("Divide", 96)
        for (i in 1..24) add("F$i", 96 + i)
        add("NumLock", 121); add("Scroll", 122); add("LeftShift", 123); add("RightShift", 124)
        add("LeftCtrl", 125); add("RightCtrl", 126); add("LeftAlt", 127); add("RightAlt", 128)
        add("BrowserBack", 129); add("BrowserForward", 130); add("BrowserRefresh", 131)
        add("BrowserStop", 132); add("BrowserSearch", 133); add("BrowserFavorites", 134)
        add("BrowserHome", 135); add("VolumeMute", 136); add("VolumeDown", 137); add("VolumeUp", 138)
        add("MediaNextTrack", 139); add("MediaPreviousTrack", 140); add("MediaStop", 141)
        add("MediaPlayPause", 142); add("LaunchMail", 143); add("SelectMedia", 144)
        add("LaunchApplication1", 145); add("LaunchApplication2", 146)
        add("Oem1", 147); add("OemPlus", 148); add("OemComma", 149); add("OemMinus", 150)
        add("OemPeriod", 151); add("Oem2", 152); add("Oem3", 153); add("Oem4", 154)
        add("Oem5", 155); add("Oem6", 156); add("Oem7", 157); add("Oem8", 158); add("Oem102", 159)
        add("ImeProcessed", 160); add("System", 161); add("OemAttention", 162); add("OemFinish", 163)
        add("OemCopy", 164); add("OemAuto", 165); add("OemEnlw", 166); add("OemBackTab", 167)
        add("Attention", 168); add("Crsel", 169); add("Exsel", 170); add("EraseEof", 171)
        add("Play", 172); add("Zoom", 173); add("NoName", 174); add("Pa1", 175)
        add("OemClear", 176); add("Dead", 177)
            entries.toMap()
    }

    /** WPF `Key` enum value → Win32 virtual-key code (`KeyInterop.VirtualKeyFromKey`); absent = 0. */
    private val VK_BY_KEY_VALUE: Map<Int, Int> = buildMap {
        put(1, 0x03) // Cancel
        put(2, 0x08) // Back
        put(3, 0x09) // Tab
        put(5, 0x0C) // Clear
        put(6, 0x0D) // Return
        put(7, 0x0D) // Enter
        put(8, 0x10) // Shift
        put(9, 0x11) // Control
        put(10, 0x12) // Alt
        put(11, 0x13) // Pause
        put(12, 0x14) // Capital
        put(13, 0x15); put(14, 0x15); put(15, 0x15) // Kana / Hanguel / Hangul mode
        put(16, 0x17) // Junja mode
        put(17, 0x18) // Final mode
        put(18, 0x19); put(19, 0x19) // Hanja / Kanji mode
        put(20, 0x1B) // Escape
        put(21, 0x1C); put(22, 0x1D); put(23, 0x1E); put(24, 0x1F) // IME convert / non-convert / accept / mode change
        put(25, 0x20) // Space
        put(26, 0x21) // Prior / PageUp
        put(27, 0x22) // Next / PageDown
        put(28, 0x23) // End
        put(29, 0x24) // Home
        put(30, 0x25); put(31, 0x26); put(32, 0x27); put(33, 0x28) // Left / Up / Right / Down
        put(34, 0x29) // Select
        put(35, 0x2A) // Print
        put(36, 0x2B) // Execute
        put(37, 0x2C) // Snapshot
        put(38, 0x2D) // Insert
        put(39, 0x2E) // Delete
        put(40, 0x2F) // Help
        for (i in 0..9) put(41 + i, 0x30 + i) // D0..D9
        for (i in 0..25) put(51 + i, 0x41 + i) // A..Z
        put(77, 0x5B); put(78, 0x5C); put(79, 0x5D) // LWin / RWin / Apps
        put(80, 0x5F) // Sleep
        for (i in 0..9) put(81 + i, 0x60 + i) // NumPad0..9
        put(91, 0x6A); put(92, 0x6B); put(93, 0x6C); put(94, 0x6D); put(95, 0x6E); put(96, 0x6F)
        for (i in 1..24) put(96 + i, 0x6F + i) // F1..F24
        put(121, 0x90); put(122, 0x91) // NumLock / Scroll
        put(123, 0xA0); put(124, 0xA1); put(125, 0xA2); put(126, 0xA3); put(127, 0xA4); put(128, 0xA5)
        put(129, 0xA6); put(130, 0xA7); put(131, 0xA8); put(132, 0xA9); put(133, 0xAA); put(134, 0xAB)
        put(135, 0xAC); put(136, 0xAD); put(137, 0xAE); put(138, 0xAF); put(139, 0xB0); put(140, 0xB1)
        put(141, 0xB2); put(142, 0xB3); put(143, 0xB4); put(144, 0xB5); put(145, 0xB6); put(146, 0xB7)
        put(147, 0xBA); put(148, 0xBB); put(149, 0xBC); put(150, 0xBD); put(151, 0xBE); put(152, 0xBF)
        put(153, 0xC0); put(154, 0xDB); put(155, 0xDC); put(156, 0xDD); put(157, 0xDE); put(158, 0xDF)
        put(159, 0xE2) // Oem102
        put(176, 0xFE) // OemClear
    }
}
