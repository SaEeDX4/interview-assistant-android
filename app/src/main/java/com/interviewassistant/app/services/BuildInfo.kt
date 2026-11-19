package com.interviewassistant.app.services

import android.content.Context
import com.interviewassistant.core.languages.Loc

/** Mirror of `InterviewAssistant.App/Services/BuildInfo.cs`. */

/**
 * Product version, release channel and build commit from the app's informational version
 * ("2.1.0-rc.5+abc1234…"). The C# reads the assembly's informational-version attribute; the
 * Android counterpart is the installed package's version name, installed once by [install]
 * (P4-PORT-NOTES DC-77). The derivation itself is pure and unchanged.
 */
object BuildInfo {
    /** The informational version string; "0.0.0" until [install] runs (the C#'s attribute fallback). */
    var informationalVersion: String = "0.0.0"
        internal set

    /** Installs the informational version from the installed package (called once at startup). */
    fun install(context: Context) {
        informationalVersion =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }

    /** Test seam: the same install, from a raw informational version string. */
    internal fun installInformationalVersion(value: String) {
        informationalVersion = value
    }

    /** Semantic version without build metadata, e.g. "2.1.0-rc.5". */
    val version: String get() = informationalVersion.split('+')[0]

    /** Short commit id, or "local" for a developer build without CI metadata. */
    val commit: String
        get() {
            val plus = informationalVersion.indexOf('+')
            val meta = if (plus >= 0) informationalVersion.substring(plus + 1) else ""
            return if (meta.length >= 7) meta.substring(0, 7) else if (meta.isNotEmpty()) meta else "local"
        }

    /** "Release Candidate", "Beta" or "Stable" — a release candidate is never presented as final. */
    val channel: String
        get() = if (version.contains("-rc", ignoreCase = true)) {
            "Release Candidate"
        } else if (version.contains("-beta", ignoreCase = true) || version.contains("-alpha", ignoreCase = true)) {
            "Beta"
        } else if (version.contains('-')) {
            "Pre-release"
        } else {
            "Stable"
        }

    val summary: String get() = "Version $version · $channel · Build $commit"

    /** Localized channel name for the UI ("Release Candidate" / "Versionskandidat" / …). */
    val channelLabel: String
        get() = Loc.t(
            when (channel) {
                "Release Candidate" -> "channel_rc"
                "Beta" -> "channel_beta_build"
                "Pre-release" -> "channel_prerelease"
                else -> "channel_stable_build"
            },
        )

    /**
     * Installed through the platform's package manager rather than run portably. Android has no
     * portable mode (the C#'s Velopack-layout probe checks for an unzipped folder run), so every
     * copy is installed (P4-PORT-NOTES DC-77).
     */
    val isInstalled: Boolean get() = true
}
