package com.interviewassistant.app.platform.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseSelection] + [VersionCompare]: the manifest rules of the project's release system
 * (website `standards/RELEASES.md` + `app.js`), and the version ordering the updater applies.
 */
class UpdateManifestTest {

    private fun sha(c: Char): String = c.toString().repeat(64)

    private fun file(
        name: String = "InterviewAssistant.apk",
        url: String = "https://example.com/app.apk",
        size: Double = 1024.0,
        sha256: String = sha('a'),
    ): ReleaseFile = ReleaseFile(name = name, url = url, size = size, sha256 = sha256)

    private fun release(
        platform: String = "android",
        version: String = "2.2.0",
        channel: String = "stable",
        files: List<ReleaseFile> = listOf(file()),
    ): ReleaseEntry = ReleaseEntry(platform = platform, version = version, channel = channel, files = files)

    @Test
    fun select_PicksTheAndroidReleaseOnTheWantedChannel() {
        val manifest = ReleaseManifest(
            listOf(
                release(platform = "windows", version = "9.9.9"),
                release(version = "2.2.0", channel = "stable"),
                release(version = "2.3.0-beta.1", channel = "beta"),
            ),
        )
        assertEquals("2.2.0", ReleaseSelection.select(manifest, "stable")?.version)
        assertEquals("2.3.0-beta.1", ReleaseSelection.select(manifest, "beta")?.version)
        // No cross-channel fallback (the C# updater's explicit channel).
        val betaOnly = ReleaseManifest(listOf(release(version = "2.3.0-beta.1", channel = "beta")))
        assertNull(ReleaseSelection.select(betaOnly, "stable"))
        // Channel names normalize on both sides ("Beta" counts as beta; anything else as stable).
        val odd = ReleaseManifest(listOf(release(version = "2.0.0", channel = "nightly")))
        assertEquals("2.0.0", ReleaseSelection.select(odd, "stable")?.version)
    }

    @Test
    fun select_SkipsReleasesWithoutAValidApk() {
        val badSha = release(files = listOf(file(sha256 = "not-a-hash")))
        val httpUrl = release(files = listOf(file(url = "http://example.com/app.apk")))
        val negativeSize = release(files = listOf(file(size = -1.0)))
        val emptyName = release(files = listOf(file(name = "")))
        val noApk = release(files = listOf(file(name = "notes.zip")))
        val good = release(version = "2.2.1")
        val manifest = ReleaseManifest(listOf(badSha, httpUrl, negativeSize, emptyName, noApk, good))
        assertEquals("2.2.1", ReleaseSelection.select(manifest, "stable")?.version)
        // The APK is found among other files, not only in first position.
        val mixed = release(files = listOf(file(name = "checksums.txt"), file(name = "app.APK")))
        assertEquals("app.APK", ReleaseSelection.apkFile(mixed)?.name)
    }

    @Test
    fun versionCompare_OrdersReleasesAndPrereleases() {
        // (a, b, expected sign of compare(a, b))
        val cases = listOf(
            Triple("2.1.0", "2.1.0", 0),
            Triple("2.1.1", "2.1.0", 1),
            Triple("2.1.0", "2.1.1", -1),
            Triple("3.0.0", "2.9.9", 1),
            Triple("2.1.0-rc.5", "2.1.0", -1), // a release outranks its prereleases
            Triple("2.1.0", "2.1.0-rc.5", 1),
            Triple("2.1.0-rc.5", "2.1.0-rc.5", 0),
            Triple("2.1.0-rc.5", "2.1.0-rc.6", -1),
            Triple("2.2.0-beta.1", "2.1.0", 1), // a newer core outranks the prerelease tag
            Triple("2.1.0-alpha", "2.1.0-alpha.1", -1), // fewer identifiers first when prefix-equal
            Triple("2.1.0-alpha.1", "2.1.0-alpha.beta", -1), // numeric before alphanumeric
            Triple("2.1.0-beta.2", "2.1.0-beta.11", -1), // numeric identifiers compare numerically
            Triple("2.1.0+build5", "2.1.0", 0), // build metadata is ignored
            Triple("2.1", "2.1.0", 0), // missing core parts read as zero
        )
        for ((a, b, expected) in cases) {
            assertEquals("compare($a, $b)", expected, Integer.signum(VersionCompare.compare(a, b)))
            assertEquals("compare($b, $a)", -expected, Integer.signum(VersionCompare.compare(b, a)))
            assertEquals("isNewer($a, $b)", expected > 0, VersionCompare.isNewer(a, b))
        }
        assertTrue(VersionCompare.isNewer("2.1.0", "2.1.0-rc.5"))
    }
}
