package com.interviewassistant.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.interviewassistant.app.services.AppLog

/**
 * Opens a gated https URL in a Custom Tab (TRANSFER-MAP §6.2 browser row; P6-PORT-NOTES
 * DC-186): the Paddle checkout/portal path and every other `CloudService.openExternal`
 * target (legal pages) go through here once MainActivity installs this as
 * `CloudService.urlLauncher`. The https/DNS gate itself stays in CloudService — this is
 * only the platform act. Falls back to a plain browser intent when no Custom Tab
 * provider exists; false when nothing can open the URL (CloudService then reports the
 * link as not valid, exactly as the C# reports a failed `Process.Start`).
 */
object CustomTabsUrlLauncher {
    fun open(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
            true
        } catch (ex: ActivityNotFoundException) {
            openInBrowser(context, uri)
        } catch (ex: RuntimeException) {
            AppLog.warn("Custom Tab launch failed: " + ex.javaClass.simpleName)
            openInBrowser(context, uri)
        }
    }

    private fun openInBrowser(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (ex: ActivityNotFoundException) {
            false
        }
    }
}
