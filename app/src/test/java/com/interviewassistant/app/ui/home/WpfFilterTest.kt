package com.interviewassistant.app.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The WPF-filter -> MIME mapping of the shell's file pickers (Package P4-E): the view models
 * pass the original's filter strings; the picker must offer exactly those document kinds, and
 * widen (never hide files) for kinds Android has no MIME type for.
 */
class WpfFilterTest {

    @Test
    fun documentsFilter_mapsEveryExtension() {
        val filter = "Documents (*.pdf;*.docx;*.txt;*.md)|*.pdf;*.docx;*.txt;*.md"
        assertArrayEquals(
            arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/markdown",
            ),
            WpfFilter.mimeTypesFor(filter),
        )
    }

    @Test
    fun singleExtensionFilters() {
        assertArrayEquals(arrayOf("text/html"), WpfFilter.mimeTypesFor("HTML files (*.html)|*.html"))
        assertArrayEquals(arrayOf("application/json"), WpfFilter.mimeTypesFor("JSON files (*.json)|*.json"))
    }

    @Test
    fun unknownExtension_widensToAllTypes() {
        // The profile backup bundle's own extension has no MIME type: the picker must not hide it.
        assertArrayEquals(arrayOf("*/*"), WpfFilter.mimeTypesFor("Backup (*.iaprofile)|*.iaprofile"))
    }

    @Test
    fun emptyOrStarFilter_isAllTypes() {
        assertArrayEquals(arrayOf("*/*"), WpfFilter.mimeTypesFor(""))
        assertArrayEquals(arrayOf("*/*"), WpfFilter.mimeTypesFor("All files (*.*)|*.*"))
    }

    @Test
    fun duplicateExtensions_areDeduplicated() {
        assertArrayEquals(
            arrayOf("text/plain"),
            WpfFilter.mimeTypesFor("Text (*.txt;*.txt)|*.txt;*.txt"),
        )
    }

    @Test
    fun saveMimeType_comesFromTheFileName() {
        assertEquals("text/html", WpfFilter.mimeTypeFor("interview-report.html"))
        assertEquals("application/json", WpfFilter.mimeTypeFor("interview-assistant-account-2026-01-01.json"))
        assertEquals("application/pdf", WpfFilter.mimeTypeFor("profile.PDF"))
        assertEquals("*/*", WpfFilter.mimeTypeFor("profile-backup.iaprofile"))
        assertEquals("*/*", WpfFilter.mimeTypeFor("noextension"))
    }
}
