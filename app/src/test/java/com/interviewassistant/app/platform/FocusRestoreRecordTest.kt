package com.interviewassistant.app.platform

import com.interviewassistant.app.services.AppSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5: the Interview Focus restore record (the C#'s `FocusRestore*` settings) behind the PiP
 * substitute (D9): existence follows the Live screen's own rule — both dimensions present and
 * positive — and clearing returns every field to unset.
 */
class FocusRestoreRecordTest {

    private lateinit var settingsFile: File
    private var savedOverride: String? = null

    @Before
    fun setUp() {
        settingsFile = File.createTempFile("focus-restore-settings", ".json")
        savedOverride = AppSettings.overridePath
        AppSettings.overridePath = settingsFile.path
    }

    @After
    fun tearDown() {
        AppSettings.overridePath = savedOverride
        settingsFile.delete()
    }

    @Test
    fun freshSettings_HaveNoRecord() {
        assertFalse(FocusRestoreRecord.has(AppSettings()))
    }

    @Test
    fun write_RecordsThePreFocusState_AndHasFollowsTheRule() {
        val settings = AppSettings()
        FocusRestoreRecord.write(settings, left = 0.0, top = 0.0, width = 412.0, height = 915.0)
        assertTrue(FocusRestoreRecord.has(settings))
        assertEquals(412.0, settings.focusRestoreWidth!!, 0.0)
        assertEquals(915.0, settings.focusRestoreHeight!!, 0.0)
        // The record persists (the C# saves the placement with the settings).
        val reloaded = AppSettings.load()
        assertTrue(FocusRestoreRecord.has(reloaded))
    }

    @Test
    fun zeroOrPartialDimensions_AreNotARecord() {
        val settings = AppSettings()
        settings.focusRestoreWidth = 0.0
        settings.focusRestoreHeight = 900.0
        assertFalse(FocusRestoreRecord.has(settings))
        settings.focusRestoreWidth = 400.0
        settings.focusRestoreHeight = null
        assertFalse(FocusRestoreRecord.has(settings))
    }

    @Test
    fun clear_RemovesEveryField() {
        val settings = AppSettings()
        FocusRestoreRecord.write(settings, left = 1.0, top = 2.0, width = 400.0, height = 900.0)
        FocusRestoreRecord.clear(settings)
        assertFalse(FocusRestoreRecord.has(settings))
        assertNull(settings.focusRestoreLeft)
        assertNull(settings.focusRestoreTop)
        assertNull(settings.focusRestoreWidth)
        assertNull(settings.focusRestoreHeight)
    }
}
