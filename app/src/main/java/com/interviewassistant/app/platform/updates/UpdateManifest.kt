package com.interviewassistant.app.platform.updates

import com.interviewassistant.client.UpdatePolicy
import kotlinx.serialization.Serializable

/**
 * The release manifest of the project's own release system (TRANSFER-MAP §8 D4: the website
 * Step-41 service — Cloudflare R2/D1 + the `interview-assistant-releases` Worker, manifest at
 * the bundled `updateUrl`). The contract is the one the download page renders from
 * (website `standards/RELEASES.md` + `app.js` `pickReleases`/`validFile`):
 *
 * `{ "releases": [ { "platform", "version", "channel", "publishedAt", "notes",
 * "minRequirement", "files": [ { "name", "url", "size", "sha256", "kind", "label" } ] } ] }`.
 *
 * Manifest data is untrusted input (RELEASES.md): a file counts only with a non-empty name, an
 * https URL, a finite non-negative size and a 64-hex SHA-256 — the page's `validFile` rules,
 * mirrored in [ReleaseSelection.isValidFile].
 */
@Serializable
data class ReleaseManifest(
    val releases: List<ReleaseEntry> = emptyList(),
)

@Serializable
data class ReleaseEntry(
    val platform: String = "",
    val version: String = "",
    val channel: String = "",
    val publishedAt: String = "",
    val notes: String = "",
    val minRequirement: String = "",
    val files: List<ReleaseFile> = emptyList(),
)

@Serializable
data class ReleaseFile(
    val name: String = "",
    val url: String = "",
    /** Bytes, as a JSON number (the page validates `typeof number`, finite, >= 0). */
    val size: Double = -1.0,
    val sha256: String = "",
    val kind: String = "",
    val label: String = "",
)

/** Which release (if any) this build may update to, and which file of it is the installable APK. */
object ReleaseSelection {
    private val sha256Hex = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)

    /** The page's `validFile`, verbatim (its https test is the case-sensitive `^https://`). */
    fun isValidFile(f: ReleaseFile): Boolean =
        f.name.isNotEmpty() &&
            f.url.startsWith("https://") &&
            f.size.isFinite() && f.size >= 0 &&
            sha256Hex.matches(f.sha256)

    /**
     * The APK of a release: the first valid file whose name ends in `.apk`. A release the page
     * would show but that carries no APK is not installable by this client, so [select] skips it.
     */
    fun apkFile(release: ReleaseEntry): ReleaseFile? =
        release.files.firstOrNull { isValidFile(it) && it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * The first valid Android release on the wanted channel. Channel matching is exact after
     * [UpdatePolicy.normalizeChannel] on both sides — the counterpart of the C# updater's
     * explicit channel (Velopack `ExplicitChannel`): a beta build does not fall back to stable,
     * and a stable build never sees beta. First match wins, as in the page's `pickReleases`.
     */
    fun select(manifest: ReleaseManifest, channel: String): ReleaseEntry? {
        val wanted = UpdatePolicy.normalizeChannel(channel)
        for (r in manifest.releases) {
            if (r.platform != "android") continue
            if (r.version.isBlank()) continue
            if (UpdatePolicy.normalizeChannel(r.channel) != wanted) continue
            if (apkFile(r) == null) continue
            return r
        }
        return null
    }
}

/**
 * Version ordering for the manifest's version strings (the Velopack `SemanticVersion`
 * comparison the C# updater performs, restricted to the shapes this project ships:
 * dotted numeric core + optional `-prerelease` identifiers + ignored `+metadata`).
 * A release outranks its own prereleases; prerelease identifiers compare numerically when
 * both are numeric, numeric before alphanumeric, and a shorter list before a longer prefix-equal
 * one (semver §11). Downgrades never count as updates (the C#'s `AllowVersionDowngrade = false`
 * is the caller's `compare(candidate, current) > 0` test).
 */
object VersionCompare {
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val coreLen = maxOf(pa.core.size, pb.core.size)
        for (i in 0 until coreLen) {
            val x = pa.core.getOrElse(i) { 0L }
            val y = pb.core.getOrElse(i) { 0L }
            if (x != y) return if (x < y) -1 else 1
        }
        if (pa.pre.isEmpty() && pb.pre.isEmpty()) return 0
        if (pa.pre.isEmpty()) return 1 // a release is newer than its prereleases
        if (pb.pre.isEmpty()) return -1
        val preLen = maxOf(pa.pre.size, pb.pre.size)
        for (i in 0 until preLen) {
            if (i >= pa.pre.size) return -1
            if (i >= pb.pre.size) return 1
            val x = pa.pre[i]
            val y = pb.pre[i]
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1 // numeric identifiers sort before alphanumeric ones
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private class Parts(val core: List<Long>, val pre: List<String>)

    private fun parse(v: String): Parts {
        val noMeta = v.substringBefore('+').trim()
        val corePart = noMeta.substringBefore('-')
        val prePart = if (noMeta.contains('-')) noMeta.substringAfter('-') else ""
        val core = corePart.split('.').map { it.trim().toLongOrNull() ?: 0L }
        val pre = if (prePart.isEmpty()) emptyList() else prePart.split('.')
        return Parts(core, pre)
    }
}
