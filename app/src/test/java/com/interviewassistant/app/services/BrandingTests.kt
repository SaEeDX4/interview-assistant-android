package com.interviewassistant.app.services

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Branding mirror: defaults, per-key override from branding.json, and a bad file
 * logged + ignored (values unchanged). One sequential flow — Branding is process-global
 * state with private setters, exactly as in C#.
 */
class BrandingTests {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("branding-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun load_MissingFile_KeepsTheCurrentValues() {
        val before = Triple(Branding.productName, Branding.companyName, Branding.supportUrl)
        Branding.load(dir.path)
        assertEquals(before, Triple(Branding.productName, Branding.companyName, Branding.supportUrl))
    }

    @Test
    fun defaults_ThenOverrides_ThenBadJsonIgnored() {
        // defaults (nothing in this suite loads an override before this test's own loads)
        assertEquals("Interview Assistant", Branding.productName)
        assertEquals("Interview Assistant", Branding.companyName)
        assertEquals("", Branding.supportUrl)

        // present keys override, absent keys keep their values
        File(dir, "branding.json").writeText(
            """{"ProductName": "Acme Interviews", "SupportUrl": "https://support.example.com"}""",
        )
        Branding.load(dir.path)
        assertEquals("Acme Interviews", Branding.productName)
        assertEquals("Interview Assistant", Branding.companyName)
        assertEquals("https://support.example.com", Branding.supportUrl)

        // bad JSON: logged and ignored, values unchanged
        File(dir, "branding.json").writeText("{ not json")
        Branding.load(dir.path)
        assertEquals("Acme Interviews", Branding.productName)
        assertEquals("https://support.example.com", Branding.supportUrl)

        // a non-string value refuses the whole file (System.Text.Json into Dictionary<string, string>)
        File(dir, "branding.json").writeText("""{"CompanyName": "Acme Corp", "ProductName": 42}""")
        Branding.load(dir.path)
        assertEquals("Interview Assistant", Branding.companyName)
        assertEquals("Acme Interviews", Branding.productName)
    }
}
