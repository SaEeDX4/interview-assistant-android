package com.interviewassistant.app.platform.updates

import com.interviewassistant.app.services.AppSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [UpdateCore]: the C# `UpdateService` state machine over fake ports — gates, policy timing,
 * the never-during-a-session rule, manifest/channel selection, hash + same-signer verification,
 * and the installer handoff. Statuses are asserted in the C#'s English (the `upd_*` keys are
 * what the UI renders; the English is what logs and self-tests read, as in the C#).
 */
class UpdateCoreTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val t0: OffsetDateTime = OffsetDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneOffset.UTC)
    private var nowValue: OffsetDateTime = t0
    private val installedSigner = byteArrayOf(1, 2, 3)
    private val apkBytes = byteArrayOf(10, 20, 30, 40)
    private val apkSha: String = MessageDigest.getInstance("SHA-256").digest(apkBytes)
        .joinToString("") { "%02x".format(it) }

    private inner class FakeTransport : UpdateTransport {
        var manifestText: String = manifest(version = "2.0.0")
        var fetchError: Exception? = null
        var fetchCount = 0
        var downloadCount = 0

        override suspend fun fetchText(url: String): String {
            fetchCount++
            fetchError?.let { throw it }
            return manifestText
        }

        override suspend fun download(url: String, dest: File) {
            downloadCount++
            dest.writeBytes(apkBytes)
        }
    }

    private fun manifest(
        version: String,
        channel: String = "stable",
        sha: String = apkSha,
        platform: String = "android",
    ): String =
        """{"releases":[{"platform":"$platform","version":"$version","channel":"$channel",""" +
            """"files":[{"name":"InterviewAssistant.apk","url":"https://example.com/app.apk",""" +
            """"size":4,"sha256":"$sha"}]}]}"""

    private fun core(
        transport: FakeTransport,
        feedUrl: String = "https://admin.cybervalida.online/manifest",
        currentVersion: String = "2.1.0",
        settings: AppSettings = AppSettings(),
        signer: () -> ByteArray? = { installedSigner },
        archiveSigner: (File) -> ByteArray? = { installedSigner },
        installer: (File) -> Boolean = { true },
    ): UpdateCore = UpdateCore(
        settings = settings,
        feedUrl = feedUrl,
        currentVersion = currentVersion,
        transport = transport,
        installedSigner = signer,
        archiveSigner = archiveSigner,
        installer = installer,
        downloadDir = File(tmp.root, "updates"),
        now = { nowValue },
    )

    @Test
    fun blankFeed_DisablesUpdates() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport, feedUrl = "")
        assertEquals("Automatic updates are not enabled for this build.", c.status)
        assertFalse(c.canCheck)
        assertFalse(c.isInstalled)
        c.tickAsync(sessionActive = false)
        c.checkNowAsync(sessionActive = false)
        assertEquals(0, transport.fetchCount)
    }

    @Test
    fun nonHttpsFeed_DisablesUpdates() {
        val c = core(FakeTransport(), feedUrl = "http://example.com/feed")
        assertEquals("Automatic updates are disabled: the update feed must use https.", c.status)
        assertFalse(c.canCheck)
    }

    @Test
    fun unsignedBuild_DisablesUpdates() {
        val c = core(FakeTransport(), signer = { null })
        assertEquals("Automatic updates are disabled: this build is not code-signed.", c.status)
        assertFalse(c.canCheck)
        assertFalse(c.isInstalled)
    }

    @Test
    fun channel_NormalizesLikeThePolicy() {
        val beta = AppSettings().apply { updateChannel = "beta" }
        assertEquals("beta", core(FakeTransport(), settings = beta).channel)
        val odd = AppSettings().apply { updateChannel = "nightly" }
        assertEquals("stable", core(FakeTransport(), settings = odd).channel)
    }

    @Test
    fun tick_WaitsForTheStartupDelay() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport)
        nowValue = t0.plusSeconds(44)
        c.tickAsync(sessionActive = false)
        assertEquals(0, transport.fetchCount)
        nowValue = t0.plusSeconds(46)
        c.tickAsync(sessionActive = false)
        assertEquals(1, transport.fetchCount)
    }

    @Test
    fun tick_NeverChecksDuringASession() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport)
        nowValue = t0.plusHours(2)
        c.tickAsync(sessionActive = true)
        assertEquals(0, transport.fetchCount)
    }

    @Test
    fun tick_RespectsTheSixHourInterval() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport)
        nowValue = t0.plusSeconds(46)
        c.tickAsync(sessionActive = false)
        assertEquals(1, transport.fetchCount)
        nowValue = nowValue.plusHours(1)
        c.tickAsync(sessionActive = false)
        assertEquals(1, transport.fetchCount)
        nowValue = nowValue.plusHours(6)
        c.tickAsync(sessionActive = false)
        assertEquals(2, transport.fetchCount)
    }

    @Test
    fun checkNow_DuringASession_RefusesWithTheSessionStatus() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport)
        c.checkNowAsync(sessionActive = true)
        assertEquals("Updates are checked after the interview.", c.status)
        assertEquals(0, transport.fetchCount)
    }

    @Test
    fun checkNow_OlderOrSameVersion_IsUpToDate() = runBlocking {
        val transport = FakeTransport()
        val c = core(transport) // current 2.1.0; the fake's manifest offers 2.0.0
        c.checkNowAsync(sessionActive = false)
        assertEquals("Up to date (2.1.0, stable).", c.status)
        assertFalse(c.updateReady)
        transport.manifestText = manifest(version = "2.1.0")
        val c2 = core(transport)
        c2.checkNowAsync(sessionActive = false)
        assertEquals("Up to date (2.1.0, stable).", c2.status)
        assertFalse(c2.updateReady)
    }

    @Test
    fun checkNow_ReleaseSupersedesItsOwnPrerelease() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.1.0")
        val c = core(transport, currentVersion = "2.1.0-rc.5")
        c.checkNowAsync(sessionActive = false)
        assertTrue(c.updateReady)
        assertEquals("2.1.0", c.pendingVersion)
    }

    @Test
    fun checkNow_NewVersion_DownloadsVerifiesAndBecomesReady() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0")
        val c = core(transport)
        c.checkNowAsync(sessionActive = false)
        assertEquals(1, transport.downloadCount)
        assertTrue(c.updateReady)
        assertEquals("2.2.0", c.pendingVersion)
        assertEquals(
            "Version 2.2.0 is ready — it installs when you close the app.",
            c.status,
        )
        assertTrue(File(tmp.root, "updates/InterviewAssistant.apk").exists())
    }

    @Test
    fun checkNow_BetaChannel_SelectsTheBetaRelease() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText =
            """{"releases":[""" +
                """{"platform":"android","version":"2.2.0","channel":"stable","files":[{"name":"InterviewAssistant.apk","url":"https://example.com/app.apk","size":4,"sha256":"$apkSha"}]},""" +
                """{"platform":"android","version":"2.3.0-beta.1","channel":"beta","files":[{"name":"InterviewAssistant.apk","url":"https://example.com/app.apk","size":4,"sha256":"$apkSha"}]}]}"""
        val betaSettings = AppSettings().apply { updateChannel = "beta" }
        val c = core(transport, settings = betaSettings)
        c.checkNowAsync(sessionActive = false)
        assertEquals("2.3.0-beta.1", c.pendingVersion)
        // …and a stable build never sees the beta release.
        val stableTransport = FakeTransport()
        stableTransport.manifestText = manifest(version = "2.3.0-beta.1", channel = "beta")
        val stable = core(stableTransport)
        stable.checkNowAsync(sessionActive = false)
        assertFalse(stable.updateReady)
    }

    @Test
    fun checkNow_ShaMismatch_IsUntrustedAndDeletesTheDownload() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0", sha = "0".repeat(64))
        val c = core(transport)
        c.checkNowAsync(sessionActive = false)
        assertEquals(
            "An update was found but its signature could not be verified; it was not installed.",
            c.status,
        )
        assertFalse(c.updateReady)
        assertFalse(File(tmp.root, "updates/InterviewAssistant.apk").exists())
    }

    @Test
    fun checkNow_DifferentSigner_IsUntrustedAndDeletesTheDownload() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0")
        val c = core(transport, archiveSigner = { byteArrayOf(9, 9, 9) })
        c.checkNowAsync(sessionActive = false)
        assertEquals(
            "An update was found but its signature could not be verified; it was not installed.",
            c.status,
        )
        assertFalse(c.updateReady)
        assertFalse(File(tmp.root, "updates/InterviewAssistant.apk").exists())
    }

    @Test
    fun checkNow_UnsignedDownload_IsUntrusted() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0")
        val c = core(transport, archiveSigner = { null })
        c.checkNowAsync(sessionActive = false)
        assertFalse(c.updateReady)
        assertEquals(
            "An update was found but its signature could not be verified; it was not installed.",
            c.status,
        )
    }

    @Test
    fun checkNow_TransportFailure_IsAFailedCheck() = runBlocking {
        val transport = FakeTransport()
        transport.fetchError = IOException("offline")
        val c = core(transport)
        c.checkNowAsync(sessionActive = false)
        assertEquals("Update check failed; will retry later.", c.status)
        assertFalse(c.isChecking)
        assertFalse(c.updateReady)
    }

    @Test
    fun checkNow_WhenAnUpdateIsReady_DoesNotRecheck() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0")
        val c = core(transport)
        c.checkNowAsync(sessionActive = false)
        assertTrue(c.updateReady)
        c.checkNowAsync(sessionActive = false)
        assertEquals(1, transport.fetchCount)
    }

    @Test
    fun tryApplyAndRestart_RequiresIdleAndHandsOffToTheInstaller() = runBlocking {
        val transport = FakeTransport()
        transport.manifestText = manifest(version = "2.2.0")
        var installerCalls = 0
        var installerFile: File? = null
        val c = core(
            transport,
            installer = { f -> installerCalls++; installerFile = f; true },
        )
        // Nothing downloaded yet: refused.
        assertFalse(c.tryApplyAndRestart(sessionActive = false))
        c.checkNowAsync(sessionActive = false)
        assertTrue(c.updateReady)
        // During a session: refused, installer untouched.
        assertFalse(c.tryApplyAndRestart(sessionActive = true))
        assertEquals(0, installerCalls)
        // Idle: handed to the platform installer.
        assertTrue(c.tryApplyAndRestart(sessionActive = false))
        assertEquals(1, installerCalls)
        assertEquals("InterviewAssistant.apk", installerFile?.name)
    }

    @Test
    fun signersEqual_RequiresBothPresentAndIdentical() {
        assertTrue(UpdateCore.signersEqual(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(UpdateCore.signersEqual(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(UpdateCore.signersEqual(null, byteArrayOf(1, 2)))
        assertFalse(UpdateCore.signersEqual(byteArrayOf(1, 2), null))
        assertFalse(UpdateCore.signersEqual(null, null))
    }
}
