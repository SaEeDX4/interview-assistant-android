package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ProfileBundle
import com.interviewassistant.core.persistence.ProfileBundleError
import com.interviewassistant.core.persistence.ProfileBundleLimits
import com.interviewassistant.core.persistence.ProfileBundlePreview
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * The data export center: choice lists, FileSlug/FileStamp, the account export (re-auth first,
 * secrets filtered), the profile exports and the .iaprofile import with its preview and error
 * mapping (the C#'s HomeWindow.DataExport.cs + ExportDialog).
 */
class HomeDataExportTests {
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

    private fun grant(req: Request, kind: String): Boolean =
        isAuth(req, "token") && req.url.queryParameter("grant_type") == kind

    private fun tokenJson(): String =
        """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"bearer","user":{"id":"$VM_USER_A","email":"a@x.test"}}"""

    private fun reauthRoutes(f: HomeFixture) {
        f.cloud.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson()
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
    }

    // ---------------- names ----------------

    @Test
    fun fileSlug_Table() {
        assertEquals("Ada-Lovelace", HomeViewModel.fileSlug("Ada Lovelace"))
        assertEquals("Résumé-2026", HomeViewModel.fileSlug("  Résumé — 2026! "))
        assertEquals("profile", HomeViewModel.fileSlug("!!!"))
        assertEquals("profile", HomeViewModel.fileSlug("   "))
        assertEquals(40, HomeViewModel.fileSlug("a".repeat(50)).length)
        assertEquals("a-b", HomeViewModel.fileSlug("a - - b"))
    }

    @Test
    fun fileStamp_IsoDate() {
        assertEquals("2026-11-19", HomeViewModel.fileStamp(LocalDate.of(2026, 11, 19)))
        assertEquals("2026-01-05", HomeViewModel.fileStamp(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun bundleErrorMapping() {
        assertEquals(Loc.t("exp_import_newer"), HomeViewModel.describeBundleError(ProfileBundleError.NewerVersion))
        assertEquals(Loc.t("exp_import_too_large"), HomeViewModel.describeBundleError(ProfileBundleError.TooLarge))
        assertEquals(Loc.t("exp_import_invalid"), HomeViewModel.describeBundleError(ProfileBundleError.Invalid))
    }

    // ---------------- choice lists ----------------

    @Test
    fun choiceLists_AccountTwo_ProfileThree() {
        val f = signedInHomeFixture().also { fixture = it }
        val account = f.home.accountExportChoices()
        assertEquals(listOf("html", "json"), account.map { it.key })
        assertEquals(Loc.t("exp_opt_html"), account[0].label)
        assertEquals(Loc.t("exp_opt_json"), account[1].label)
        val profile = f.home.profileExportChoices()
        assertEquals(listOf("profile-html", "backup", "raw-zip"), profile.map { it.key })
        assertEquals(Loc.t("exp_opt_profile_html"), profile[0].label)
        assertEquals(Loc.t("exp_opt_backup"), profile[1].label)
        assertEquals(Loc.t("exp_opt_raw_zip"), profile[2].label)
    }

    // ---------------- account export ----------------

    @Test
    fun exportAccount_Html_ReauthThenWrite() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        reauthRoutes(f)
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "me/export") && req.method == "GET") {
                200 to """{"account":{"email":"a@x.test"},"profiles":[],"note":"hello"}"""
            } else {
                base(req, body)
            }
        }
        val out = File(f.cloud.dir, "account.html")
        f.files.savePath = { out.path }
        f.dialogs.exportChoice = { ExportFormats.AccountHtml }
        f.home.exportAccountDataAsync()
        assertTrue(out.exists())
        assertTrue(out.readText().contains("a@x.test"))
        assertEquals("interview-assistant-account-2026-11-19.html", f.files.saveCalls.single())
        assertTrue(f.toasts.any { it.first == Loc.t("toast_account_exported") })
        assertEquals("", f.home.privacyMessage.text)
        // Re-authentication came before the export request.
        assertTrue(f.dialogs.calls.contains("currentPassword"))
        assertEquals(1, f.cloud.apiFake.countCalls { isApi(it, "me/export") })
    }

    @Test
    fun exportAccount_Json_WritesJson() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        reauthRoutes(f)
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "me/export") && req.method == "GET") {
                200 to """{"account":{"email":"a@x.test"}}"""
            } else {
                base(req, body)
            }
        }
        val out = File(f.cloud.dir, "account.json")
        f.files.savePath = { out.path }
        f.dialogs.exportChoice = { ExportFormats.AccountJson }
        f.home.exportAccountDataAsync()
        assertEquals("interview-assistant-account-2026-11-19.json", f.files.saveCalls.single())
        assertTrue(out.readText().contains("\"account\""))
    }

    @Test
    fun exportAccount_ReauthCancelled_NothingExported() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        reauthRoutes(f)
        f.dialogs.passwordToEnter = null // the prompt is cancelled
        f.dialogs.exportChoice = { ExportFormats.AccountHtml }
        f.files.savePath = { File(f.cloud.dir, "never.html").path }
        f.home.exportAccountDataAsync()
        assertEquals(0, f.cloud.apiFake.countCalls { isApi(it, "me/export") })
        assertTrue(f.files.saveCalls.isEmpty())
    }

    @Test
    fun exportAccount_ServiceFailure_ShowsInThePrivacySection() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        reauthRoutes(f)
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "me/export") && req.method == "GET") {
                500 to """{"code":"boom","message":"boom"}"""
            } else {
                base(req, body)
            }
        }
        f.dialogs.exportChoice = { ExportFormats.AccountHtml }
        f.files.savePath = { File(f.cloud.dir, "x.html").path }
        f.home.exportAccountDataAsync()
        assertTrue(f.home.privacyMessage.isVisible)
        assertEquals(MessageKind.Error, f.home.privacyMessage.kind)
    }

    // ---------------- profile export / import ----------------

    @Test
    fun exportProfile_Backup_WritesABundle() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val p = f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        val out = File(f.cloud.dir, "backup.iaprofile")
        f.files.savePath = { out.path }
        f.dialogs.exportChoice = { ExportFormats.ProfileBackup }
        f.home.exportProfileAsync()
        assertTrue(out.exists() && out.length() > 0)
        assertEquals("Ada-Lovelace-backup-2026-11-19.iaprofile", f.files.saveCalls.single())
        assertTrue(f.toasts.any { it.first == Loc.t("exp_toast_backup_saved") })
        // And the bundle reads back as this profile.
        val content = ProfileBundle.read(out.inputStream())
        assertEquals("Ada Lovelace", content.preview.profileName)
    }

    @Test
    fun importPreview_RowsAndOptionalLines() {
        val f = signedInHomeFixture().also { fixture = it }
        val created = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        val rows = f.home.importPreviewRows(
            ProfileBundlePreview("Ada Lovelace", 1200, 3, 2, 5, created, "2.1.0"),
        )
        assertEquals(Loc.t("exp_prev_profile"), rows[0].first)
        assertEquals("Ada Lovelace", rows[0].second)
        assertEquals("1,200", rows[1].second) // N0 in the UI culture
        assertEquals(Loc.t("exp_prev_app"), rows.last().first)
        assertEquals("2.1.0", rows.last().second)
        // No created/app rows for an undated, unversioned bundle.
        val bare = f.home.importPreviewRows(
            ProfileBundlePreview("", 0, 0, 0, 0, OffsetDateTime.MIN, ""),
        )
        assertEquals(5, bare.size)
        assertEquals(Loc.t("profile_unnamed"), bare[0].second)
    }

    @Test
    fun importBackup_RoundTrip_CreatesANewProfile() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val p = f.addProfile()
        f.addTarget(p.id)
        // A real bundle of the profile, built with the :core writer (as an export would).
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ProfileBundle.write(f.store(), p.id, out, "2.1.0-rc.5")
        }.toByteArray()
        f.store().deleteProfile(p.id)
        assertTrue(f.store().profiles().isEmpty())
        f.files.picked = FakePickedFile("Ada-Lovelace-backup-2026-11-19.iaprofile", bytes)
        f.home.importProfileBackupAsync()
        val imported = f.store().profiles().single()
        assertEquals("Ada Lovelace", imported.name)
        assertEquals("profiles", f.home.currentPage)
        assertTrue(f.toasts.any { it.first == Loc.t("exp_toast_imported") })
        assertEquals("Ada Lovelace", f.dialogs.lastPreview?.profileName)
    }

    @Test
    fun importBackup_CorruptFile_PlainInvalidMessage() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.files.picked = FakePickedFile("broken.iaprofile", "this is not a backup".toByteArray())
        f.home.importProfileBackupAsync()
        assertTrue(f.store().profiles().isEmpty())
        assertTrue(f.dialogs.alerts.any { it.first == Loc.t("exp_import_invalid") })
    }

    @Test
    fun importBackup_TooLarge_RefusedBeforeReading() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.files.picked = FakePickedFile(
            "huge.iaprofile", ByteArray(0),
            sizeBytes = ProfileBundleLimits.Default.maxFileBytes + 1,
        )
        f.home.importProfileBackupAsync()
        assertTrue(f.dialogs.alerts.any { it.first == Loc.t("exp_import_too_large") })
        assertTrue(f.store().profiles().isEmpty())
    }

    @Test
    fun importBackup_PlanLimit_RefusesTheSecondProfile() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        // The fixture account allows one profile; a bundle whose profile would be the second is
        // refused by the limit check before anything is read or imported.
        val other = CandidateProfileRecord(name = "Grace Hopper")
        f.store().saveProfile(other)
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ProfileBundle.write(f.store(), other.id, out, "2.1.0-rc.5")
        }.toByteArray()
        f.store().deleteProfile(other.id)
        f.addProfile("Grace Hopper") // the name now exists
        // The plan limit (1) blocks a second profile before the collision logic: assert the limit alert.
        f.files.picked = FakePickedFile("grace.iaprofile", bytes)
        f.home.importProfileBackupAsync()
        assertTrue(f.dialogs.alerts.any { it.first == Loc.f("warn_profile_limit", 1) })
    }
}
