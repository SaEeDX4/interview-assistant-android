package com.interviewassistant.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.core.content.FileProvider
import com.interviewassistant.app.platform.updates.UpdateCore
import com.interviewassistant.app.platform.updates.UpdateTransport
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.IUpdateService
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Android [IUpdateService] (TRANSFER-MAP §2 row 32, §8 D4): a thin adapter over
 * [UpdateCore]. It supplies the platform facts the core decides with — OkHttp transport,
 * the installed app's signing-certificate digest and a downloaded APK's (PackageManager,
 * the Authenticode counterpart), and the installer handoff (a FileProvider URI + the
 * platform's package-installer prompt; the user confirms there, per the D4 residual).
 */
class UpdateService(
    context: Context,
    settings: AppSettings,
    feedUrl: String,
    currentVersion: String,
) : IUpdateService {
    private val appContext: Context = context.applicationContext

    private val core = UpdateCore(
        settings = settings,
        feedUrl = feedUrl,
        currentVersion = currentVersion,
        transport = OkHttpUpdateTransport(),
        installedSigner = { signerDigest(appContext, archivePath = null) },
        archiveSigner = { file -> signerDigest(appContext, archivePath = file.absolutePath) },
        installer = { file -> launchInstaller(appContext, file) },
        downloadDir = File(appContext.cacheDir, "updates"),
    )

    override val status: String get() = core.status
    override val statusText: String get() = core.statusText
    override val isInstalled: Boolean get() = core.isInstalled
    override val updateReady: Boolean get() = core.updateReady
    override val pendingVersion: String? get() = core.pendingVersion
    override val channel: String get() = core.channel
    override val canCheck: Boolean get() = core.canCheck
    override val isChecking: Boolean get() = core.isChecking
    override val changedListeners: MutableList<() -> Unit> get() = core.changedListeners

    override suspend fun tickAsync(sessionActive: Boolean) = core.tickAsync(sessionActive)

    override suspend fun checkNowAsync(sessionActive: Boolean) = core.checkNowAsync(sessionActive)

    override fun applyOnExit() = core.applyOnExit()

    override fun tryApplyAndRestart(sessionActive: Boolean): Boolean = core.tryApplyAndRestart(sessionActive)

    companion object {
        /**
         * The SHA-256 digest of the first signing certificate of the installed app
         * (`archivePath == null`) or of an APK file on disk; null when the package is
         * unreadable or unsigned. (One signer is the comparison unit, as the C# compares
         * one publisher subject; the platform installer itself enforces full signer
         * continuity when the user confirms.)
         */
        internal fun signerDigest(context: Context, archivePath: String?): ByteArray? {
            val pm = context.packageManager
            val info = try {
                if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            } catch (ex: PackageManager.NameNotFoundException) {
                null
            } catch (ex: RuntimeException) {
                null
            }
            val signers: Array<Signature> = info?.signingInfo?.apkContentsSigners ?: return null
            val first = signers.firstOrNull() ?: return null
            return MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        }

        /** Hands the verified APK to the platform installer; false when no installer takes it. */
        internal fun launchInstaller(context: Context, file: File): Boolean {
            return try {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (ex: ActivityNotFoundException) {
                AppLog.warn("Update install failed: no package installer available")
                false
            } catch (ex: IllegalArgumentException) {
                AppLog.warn("Update install failed: " + AppLog.describe(ex))
                false
            } catch (ex: SecurityException) {
                AppLog.warn("Update install failed: " + AppLog.describe(ex))
                false
            }
        }
    }
}

/** OkHttp transport for [UpdateCore] (the catalog OkHttp, as everywhere else in the port). */
private class OkHttpUpdateTransport : UpdateTransport {
    private val client = OkHttpClient()

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Manifest fetch failed: HTTP " + response.code)
            response.body?.string() ?: throw IOException("Manifest fetch failed: empty body")
        }
    }

    override suspend fun download(url: String, dest: File) {
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP " + response.code)
                val body = response.body ?: throw IOException("Download failed: empty body")
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        }
    }
}
