package com.interviewassistant.app.platform.updates

import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.client.UpdatePolicy
import com.interviewassistant.core.languages.Loc
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** The network half of an update check: fetch the manifest text, download a file. Throws [IOException] on failure. */
interface UpdateTransport {
    suspend fun fetchText(url: String): String
    suspend fun download(url: String, dest: File)
}

/** A downloaded, hash- and signer-verified update waiting for the user's Restart-to-update. */
class PendingUpdate(val version: String, val file: File)

/**
 * The decision core of `InterviewAssistant.App/Services/UpdateService.cs`, member-for-member:
 * the same gates (no feed, non-https feed, unsigned build), the same statuses (the C#'s English
 * log text + `upd_*` key/args rendered through [Loc]), the same check flow and the same
 * [UpdatePolicy] timing/session rules. The Velopack half is replaced by the project's own
 * release system (TRANSFER-MAP §8 D4): the manifest at the feed URL, the APK it names, the
 * manifest's SHA-256, and the same-signer rule — the downloaded APK's signing-certificate
 * digest must equal the installed app's (the Authenticode publisher check's Android form;
 * digest equality is stricter than the C#'s subject comparison). Platform facts arrive as
 * ports (transport, signer probes, installer handoff, clock), so every decision is JVM-tested;
 * `platform/UpdateService.kt` is the thin Android adapter over this class.
 *
 * One deliberate platform difference inside the catch: a coroutine [CancellationException] is
 * rethrown, never reported as a failed check — Kotlin cancellation is how the owner of the
 * check ends it, where the C# counts a cancelled task as a failed check.
 */
class UpdateCore(
    private val settings: AppSettings,
    feedUrl: String,
    private val currentVersion: String,
    private val transport: UpdateTransport,
    private val installedSigner: () -> ByteArray?,
    private val archiveSigner: (File) -> ByteArray?,
    private val installer: (File) -> Boolean,
    private val downloadDir: File,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
) {
    /** English status (logs/self-test). The UI shows [statusText] in the user's language. */
    var status: String = ""
        private set
    private var statusKey = "upd_none"
    private var statusArgs: Array<out Any?> = emptyArray()

    val statusText: String get() = Loc.f(statusKey, *statusArgs)

    private fun setStatus(english: String, key: String, vararg args: Any?) {
        status = english
        statusKey = key
        statusArgs = args
    }

    /** The C#'s `Changed` event. */
    val changedListeners: MutableList<() -> Unit> = mutableListOf()

    private fun fireChanged() {
        val snapshot = synchronized(changedListeners) { changedListeners.toList() }
        for (listener in snapshot) listener()
    }

    private val feedUrl: String = feedUrl
    private val started: OffsetDateTime = now()
    private var lastCheck: OffsetDateTime? = null
    private var pending: PendingUpdate? = null

    /** True when a check can run at all (the C#'s `_mgr != null`): https feed + a signed installed build. */
    private val clientEnabled: Boolean

    init {
        clientEnabled = when {
            feedUrl.isBlank() -> {
                setStatus("Automatic updates are not enabled for this build.", "upd_not_enabled")
                false
            }
            !feedUrl.startsWith("https://", ignoreCase = true) -> {
                setStatus("Automatic updates are disabled: the update feed must use https.", "upd_not_https")
                false
            }
            installedSigner() == null -> {
                setStatus("Automatic updates are disabled: this build is not code-signed.", "upd_unsigned")
                AppLog.info("Updates disabled: the installed app carries no signing certificate")
                false
            }
            else -> true
        }
    }

    /**
     * Installed through the platform's package manager (Android has no portable mode — the
     * C#'s Velopack-layout probe has no counterpart; P4-PORT-NOTES DC-77). False whenever the
     * client is disabled, exactly as the C#'s `_mgr?.IsInstalled == true` is false with no manager.
     */
    val isInstalled: Boolean get() = clientEnabled

    val updateReady: Boolean get() = pending != null
    val pendingVersion: String? get() = pending?.version

    /** The C#'s live property: the normalized settings channel (the manager's channel is the construction-time one). */
    val channel: String get() = UpdatePolicy.normalizeChannel(settings.updateChannel)

    /** The channel this instance selects releases for, captured at construction like the C#'s UpdateManager. */
    private val selectionChannel: String = UpdatePolicy.normalizeChannel(settings.updateChannel)

    /** A check can run: a signed, installed copy with an https feed. */
    val canCheck: Boolean get() = clientEnabled

    var isChecking: Boolean = false
        private set

    /** Background tick (called periodically). Respects the live-session rule. */
    suspend fun tickAsync(sessionActive: Boolean) {
        if (!canCheck || pending != null || isChecking) return
        if (!UpdatePolicy.shouldCheck(sessionActive, lastCheck, now(), started)) return
        checkAsync()
    }

    /**
     * "Check for updates" (Settings → Updates): the same check as the background tick, without
     * its interval — and still never during a live session.
     */
    suspend fun checkNowAsync(sessionActive: Boolean) {
        if (!canCheck || pending != null || isChecking) return
        if (!UpdatePolicy.shouldCheckNow(sessionActive)) {
            setStatus("Updates are checked after the interview.", "upd_after_session")
            fireChanged()
            return
        }
        checkAsync()
    }

    private suspend fun checkAsync() {
        lastCheck = now()
        isChecking = true
        setStatus("Checking for updates…", "upd_checking")
        fireChanged()
        try {
            val manifest = json.decodeFromString(ReleaseManifest.serializer(), transport.fetchText(feedUrl))
            val release = ReleaseSelection.select(manifest, selectionChannel)
            val file = release?.let { ReleaseSelection.apkFile(it) }
            if (release == null || file == null || !VersionCompare.isNewer(release.version, currentVersion)) {
                setStatus(
                    "Up to date ($currentVersion, $channel).",
                    "upd_up_to_date",
                    "$currentVersion, $channel",
                )
                return
            }
            downloadDir.mkdirs()
            // The manifest is untrusted input: the file name is used as a bare name only.
            val dest = File(downloadDir, File(file.name).name)
            transport.download(file.url, dest)
            if (!verifyDownloaded(dest, file)) {
                setStatus(
                    "An update was found but its signature could not be verified; it was not installed.",
                    "upd_untrusted",
                )
                return
            }
            pending = PendingUpdate(release.version, dest)
            setStatus(
                "Version ${release.version} is ready — it installs when you close the app.",
                "upd_ready",
                release.version,
            )
            AppLog.info("Update downloaded: " + release.version)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: IOException) {
            setStatus("Update check failed; will retry later.", "upd_failed")
            AppLog.warn("Update check failed: " + ex.javaClass.simpleName)
        } catch (ex: Exception) {
            // Any other failure (manifest parse, updater internals) is a failed check too, never a UI-level crash.
            setStatus("Update check failed; will retry later.", "upd_failed")
            AppLog.warn("Update check failed (unexpected): " + ex.javaClass.simpleName)
        } finally {
            isChecking = false
            fireChanged()
        }
    }

    /**
     * The C#'s `PackageTrusted`, with the release system's SHA-256 checked first: the hash is
     * the panel's published integrity proof for the exact bytes; the signer rule then requires
     * the APK's signing certificate to be the installed app's own. Any failure deletes the
     * download, as the C# deletes an untrusted package.
     */
    private fun verifyDownloaded(dest: File, file: ReleaseFile): Boolean {
        if (!sha256Hex(dest).equals(file.sha256, ignoreCase = true)) {
            AppLog.warn("Update package rejected: SHA-256 mismatch")
            deleteQuietly(dest)
            return false
        }
        val signer = archiveSigner(dest)
        if (!signersEqual(installedSigner(), signer)) {
            AppLog.warn(
                "Update package rejected: " +
                    if (signer == null) "missing or invalid signature" else "different publisher",
            )
            deleteQuietly(dest)
            return false
        }
        return true
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (ex: SecurityException) {
            // Best effort only, exactly as the C#'s delete of a rejected package.
        }
    }

    /**
     * Called during shutdown. The C# hands the update to Velopack, which installs it after the
     * process exits; Android has no exit-install hook (TRANSFER-MAP §8 D4), so the verified
     * download stays in place and Restart-to-update remains the install path — nothing is
     * silently applied and nothing is discarded.
     */
    fun applyOnExit() {
        val p = pending ?: return
        AppLog.info("Update ${p.version} stays ready; it installs from Restart to update")
    }

    /** User-initiated "Restart to update" — refused while a session is active. */
    fun tryApplyAndRestart(sessionActive: Boolean): Boolean {
        val p = pending ?: return false
        if (!clientEnabled || !UpdatePolicy.canApplyNow(sessionActive, updateDownloaded = true)) return false
        // The platform installer takes over from here; its prompt is the user's confirm
        // (the D4 residual — Velopack's silent apply has no Android equivalent).
        return installer(p.file)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The same-signer rule: both certificates present and byte-identical digests. */
        fun signersEqual(installed: ByteArray?, archive: ByteArray?): Boolean =
            installed != null && archive != null && MessageDigest.isEqual(installed, archive)

        /** SHA-256 of a file's bytes, lowercase hex — the manifest's spelling. */
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
