package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.Branding
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.StoredSession
import com.interviewassistant.core.reports.HtmlReportRenderer
import com.interviewassistant.core.reports.ReportSerializer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Reports: rows come only from sessions with a report, the preview shows the questions (never
 * scores), and Open/Copy/Export/Delete act on the selected report (the C#'s HomeWindow reports).
 */
class HomeReportsTests {
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

    private fun signedIn(): HomeFixture = signedInHomeFixture().also { fixture = it }

    @Test
    fun rows_OnlySessionsWithReports_NewestFirst() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        // A session without a report never becomes a row.
        f.store().saveSession(
            StoredSession(
                profileId = p.id, targetId = tg.id,
                startedUtc = OffsetDateTime.of(2026, 11, 19, 10, 0, 0, 0, ZoneOffset.UTC),
                reportJson = "",
            ),
        )
        val older = f.addReportSession(p.id, tg.id, OffsetDateTime.of(2026, 11, 17, 10, 0, 0, 0, ZoneOffset.UTC))
        val newer = f.addReportSession(p.id, tg.id, OffsetDateTime.of(2026, 11, 18, 10, 0, 0, 0, ZoneOffset.UTC))
        f.home.navigateAsync(HomeRoute("reports"))
        assertEquals(listOf(newer.id, older.id), f.home.reportRows.map { it.session.id })
        val row = f.home.reportRows.first()
        assertEquals("Senior Engineer · Acme", row.title) // the stored interview's display name
        assertEquals(Loc.f("report_row_questions", 1), row.questions)
    }

    @Test
    fun preview_ShowsQuestionsAndMeta_NoScores() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        assertTrue(f.home.reportPreviewBody.contains("Tell me about yourself"))
        assertTrue(f.home.reportPreviewMeta.contains("25")) // 25 minutes
        assertFalse(f.home.reportPreviewBody.contains("score", ignoreCase = true))
        assertTrue(f.home.reportOpenEnabled)
        assertTrue(f.home.reportCopyEnabled)
        assertTrue(f.home.reportExportEnabled)
        assertTrue(f.home.reportDeleteEnabled)
    }

    @Test
    fun preview_UnreadableReport_ShowsThePlainMessage() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.store().saveSession(
            StoredSession(
                profileId = p.id, targetId = tg.id,
                startedUtc = OffsetDateTime.of(2026, 11, 18, 10, 0, 0, 0, ZoneOffset.UTC),
                reportJson = "{not a report",
            ),
        )
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        assertEquals(Loc.t("report_unreadable"), f.home.reportPreviewBody)
        assertFalse(f.home.reportOpenEnabled)
        assertTrue(f.home.reportDeleteEnabled) // a broken report can still be deleted
    }

    @Test
    fun copy_PutsThePlainTextReportOnTheClipboard() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        val s = f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        f.home.onCopyReportClick()
        val report = ReportSerializer.fromJson(s.reportJson)!!
        assertEquals(HtmlReportRenderer.renderText(report, Branding.productName), f.clipboard.text)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_report_copied") })
    }

    @Test
    fun open_WritesTheViewerCopy_AndOpensIt() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        f.home.onOpenReportClick()
        assertEquals(1, f.shell.openedFiles.size)
        assertTrue(java.io.File(f.shell.openedFiles.single()).exists())
    }

    @Test
    fun export_WritesTheHtmlReport() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        val s = f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        val out = java.io.File(f.cloud.dir, "report.html")
        f.files.savePath = { out.path }
        f.home.exportReportAsync()
        val report = ReportSerializer.fromJson(s.reportJson)!!
        assertEquals(HtmlReportRenderer.render(report, Branding.productName), out.readText())
        assertTrue(f.toasts.any { it.first == Loc.t("toast_report_exported") })
    }

    @Test
    fun delete_Confirm_RemovesTheSession() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        val s = f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        f.home.onReportSelectionChanged(0)
        f.dialogs.confirmAnswer = true
        f.home.deleteReportAsync()
        assertTrue(f.store().sessions().none { it.id == s.id })
        assertTrue(f.home.reportRows.isEmpty())
        assertTrue(f.toasts.any { it.first == Loc.t("toast_report_deleted") })
    }

    @Test
    fun noSelection_PreviewAsksToSelect() = runBlocking {
        val f = signedIn()
        val p = f.addProfile()
        val tg = f.addTarget(p.id)
        f.addReportSession(p.id, tg.id)
        f.home.navigateAsync(HomeRoute("reports"))
        // Nothing selected yet (the C# selects only on click/refresh with an id).
        f.home.onReportSelectionChanged(-1)
        assertFalse(f.home.reportDeleteEnabled)
    }
}
