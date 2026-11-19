package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.BuildInfo
import com.interviewassistant.app.uistate.HealthCheckId
import com.interviewassistant.app.uistate.HealthOutcome
import com.interviewassistant.app.uistate.ServiceReach
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Help & diagnostics: the four diagnostics values, the friendly support text (version in, no
 * errors/secrets), and the health check over its four facts (the C#'s HomeWindow.Help.cs).
 */
class HomeHelpTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private var fixture: HomeFixture? = null

    @Before
    fun setUp() {
        Loc.language = "en"
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        Loc.language = "en"
    }

    @Test
    fun diagnostics_SignedIn_ConnectionAudioQuestions() {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.refreshHelpDiagnostics()
        assertEquals(Loc.t("help_diag_conn_ok"), f.home.helpConnectionText)
        assertTrue(f.home.helpAudioText.startsWith("Fake Headset"))
        assertEquals("0", f.home.helpQuestionsText)
        assertEquals(Loc.t("help_diag_none"), f.home.helpLatencyText) // no engine sample yet
        assertEquals(4 + 0, f.home.helpDiagnosticsTexts.size) // four values, no health rows yet
    }

    @Test
    fun diagnostics_SignedOut_ConnectionIsSignedOut() {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.refreshHelpDiagnostics()
        assertEquals(Loc.t("acct_signed_out"), f.home.helpConnectionText)
    }

    @Test
    fun supportLine_EnglishShape() {
        val f = signedInHomeFixture().also { fixture = it }
        f.signer = "Test Signer"
        val line = f.home.supportLine()
        assertTrue(line.startsWith("Interview Assistant ${BuildInfo.version} (${BuildInfo.channel}) build ${BuildInfo.commit}"))
        assertTrue(line.contains(" · installed · signed · Test OS 1.0"))
        f.signer = null
        assertTrue(f.home.supportLine().contains(" · unsigned · "))
    }

    @Test
    fun diagnosticsText_ContainsVersionAndFriendlyLines_NoSecrets() {
        val f = signedInHomeFixture().also { fixture = it }
        val text = f.home.diagnosticsText()
        assertTrue(text.contains(BuildInfo.version))
        assertTrue(text.contains("Service: SignedIn"))
        assertTrue(text.contains("audio STOPPED"))
        // No credentials, tokens or raw exception text ever reach the clipboard text.
        assertFalse(text.contains("at-0"))
        assertFalse(text.contains("rt-0"))
        assertFalse(text.contains("Exception"))
    }

    @Test
    fun healthCheck_AllFactsGood_AllRowsPass() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val rows = f.home.runHealthCheckAsync()
        assertEquals(4, rows.size)
        assertEquals(HealthCheckId.entries.toSet(), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.outcome == HealthOutcome.Pass })
        assertEquals(Loc.f("help_diag_result", 4, 4), f.home.healthStatusText)
        assertTrue(f.home.healthStatusVisible)
        // The diagnostics text now carries the health-check line.
        assertTrue(f.home.diagnosticsText().contains("Health check:"))
        assertEquals(4 + 4, f.home.helpDiagnosticsTexts.size)
    }

    @Test
    fun healthCheck_ServiceUnreachable_WarnsOnlyThatRow() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        assertEquals(ServiceReach.Reachable, f.home.serviceReachAsync())
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "config")) 500 to """{"code":"boom","message":"boom"}""" else base(req, body)
        }
        assertEquals(ServiceReach.Unreachable, f.home.serviceReachAsync())
        val rows = f.home.runHealthCheckAsync()
        val service = rows.single { it.id == HealthCheckId.Service }
        assertEquals(HealthOutcome.Warn, service.outcome)
        assertEquals("hc_service_warn", service.detailKey)
        assertEquals(Loc.f("help_diag_result", 3, 4), f.home.healthStatusText)
    }

    @Test
    fun healthCheck_ReentrantCall_ReturnsTheRunningRows() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val rows = f.home.runHealthCheckAsync()
        val again = f.home.runHealthCheckAsync()
        assertEquals(rows.map { it.id }, again.map { it.id })
    }

    @Test
    fun copyDiagnostics_CopiesTheText() {
        val f = signedInHomeFixture().also { fixture = it }
        f.home.onCopyDiagnosticsClick()
        assertEquals(f.home.diagnosticsText(), f.clipboard.text)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_copied") })
    }

    @Test
    fun copyDiagnostics_ClipboardBusy_ReportsTheCopyError() {
        val f = signedInHomeFixture().also { fixture = it }
        f.clipboard.busy = true
        f.home.onCopyDiagnosticsClick()
        assertTrue(f.toasts.any { it.first == Loc.t("err_diag_copy") })
    }

    @Test
    fun healthRowPresentation_PassAndWarn() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val rows = f.home.runHealthCheckAsync()
        val row = rows.first()
        assertEquals("B.Listening", f.home.healthRowBrushKey(row))
        assertEquals("\uE73E", f.home.healthRowIcon(row))
        assertTrue(f.home.healthRowName(row).contains(Loc.t("hc_pass")))
    }
}
