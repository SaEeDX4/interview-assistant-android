package com.interviewassistant.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android counterparts of `InterviewAssistant.Tests/LicenseComplianceTests.cs` (P7,
 * TEST-MAP closure), adapted from the Windows repo's `THIRD_PARTY_NOTICES.md` +
 * `licenses/` folder + `.csproj` PackageReferences to this repo's
 * `parity/THIRD_PARTY_NOTICES.md` + the Gradle version catalog
 * (`gradle/libs.versions.toml`), which is where this build's shipped package
 * references live. Same rules, same direction of checks:
 * notices → every entry is real and substantive; catalog → every shipped
 * dependency is listed; the required licences are present.
 * Like the C#, the tests pass vacuously outside a source checkout.
 */
class LicenseComplianceTests {

    companion object {
        /** Repository root (settings.gradle.kts), or null outside a source checkout. */
        private fun repoRoot(): File? {
            var d: File? = File(".").absoluteFile
            while (d != null) {
                if (File(d, "settings.gradle.kts").isFile) return d
                d = d.parentFile
            }
            return null
        }

        private fun notices(root: File): String = File(root, "parity/THIRD_PARTY_NOTICES.md").readText()

        /** Every `group:name` in the catalog's [libraries] section (the shipped + test package references). */
        private fun catalogCoordinates(root: File): Set<String> {
            val toml = File(root, "gradle/libs.versions.toml").readText()
            val libraries = toml.substringAfter("[libraries]").substringBefore("[plugins]")
            val coords = mutableSetOf<String>()
            val entry = Regex("""group\s*=\s*"([^"]+)",\s*name\s*=\s*"([^"]+)"""")
            entry.findAll(libraries).forEach { coords += it.groupValues[1] + ":" + it.groupValues[2] }
            // The Compose BOM pins the androidx.compose.ui/material3 artifacts listed without coordinates.
            if (libraries.contains("androidx-compose-bom")) {
                coords += "androidx.compose:compose-bom"
                coords += "androidx.compose.ui:ui"
                coords += "androidx.compose.ui:ui-graphics"
                coords += "androidx.compose.ui:ui-tooling"
                coords += "androidx.compose.ui:ui-tooling-preview"
                coords += "androidx.compose.material3:material3"
            }
            // The Kotlin Gradle plugin ships the standard library with the app.
            coords += "org.jetbrains.kotlin:kotlin-stdlib"
            // Transitive runtime dependencies (OkHttp's and the kotlinx libraries'), named in the notices' prose.
            coords += "com.squareup.okio:okio"
            coords += "org.jetbrains.kotlinx:kotlinx-coroutines-core"
            coords += "org.jetbrains.kotlinx:kotlinx-serialization-core"
            coords += "org.jetbrains:annotations"
            return coords
        }

        /** Coordinates the notices file lists in backticks. */
        private fun noticedCoordinates(notices: String): Set<String> =
            Regex("""`([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+)`""").findAll(notices).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyLicenseEntryInTheNoticesIsRealAndSubstantive() {
        val root = repoRoot() ?: return
        val text = notices(root)
        val noticed = noticedCoordinates(text)
        assertTrue("only ${noticed.size} dependencies listed", noticed.size >= 8)
        val real = catalogCoordinates(root)
        for (coord in noticed) {
            assertTrue("$coord is listed in the notices but is not a dependency of this build", coord in real)
        }
        // Every listed dependency carries a licence name on its line (the substantive-content rule).
        for (line in text.lines()) {
            val coord = noticed.firstOrNull { line.contains("`$it`") } ?: continue
            assertTrue("$coord has no licence named in the notices", line.contains("License") || line.contains("Licence"))
        }
    }

    @Test
    fun everyShippedPackageReferenceIsListedInTheNotices() {
        val root = repoRoot() ?: return
        val text = notices(root)
        for (coord in catalogCoordinates(root)) {
            assertTrue("$coord is a dependency of this build but is not listed in parity/THIRD_PARTY_NOTICES.md", text.contains("`$coord`"))
        }
    }

    @Test
    fun requiredRuntimeAndPackageLicensesArePresent() {
        val root = repoRoot() ?: return
        val text = notices(root)
        // The runtime packages whose licences must always travel with this build.
        for (coord in listOf(
            "com.squareup.okhttp3:okhttp",
            "com.android.billingclient:billing",
            "androidx.browser:browser",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
            "org.jetbrains.kotlin:kotlin-stdlib",
        )) {
            assertTrue("$coord missing from the notices", text.contains("`$coord`"))
        }
        assertTrue("the notices must name the Apache License 2.0", text.contains("Apache License 2.0"))
    }

    @Test
    fun noticesFileIsPresentInTheRepositoryTree() {
        // The C#'s ReleaseBuildShipsTheNotices half is a packaging assertion; the Android
        // packaging step is P8 (see the notices file's own Notes). What a JVM test can pin
        // here: the notices exist in the tree, at the parity gate's location, with real content.
        val root = repoRoot() ?: return
        val file = File(root, "parity/THIRD_PARTY_NOTICES.md")
        assertTrue("parity/THIRD_PARTY_NOTICES.md is missing", file.isFile)
        assertTrue("the notices are too short to be substantive", file.readText().trim().length > 200)
    }

    @Test
    fun releaseAssetNoticesMatchTheParitySourceByteForByte() {
        // DC-211: the release source set carries the notices as a static asset
        // (app/src/release/assets/THIRD_PARTY_NOTICES.md) so AGP's standard
        // merge puts them at assets/THIRD_PARTY_NOTICES.md in the APK/AAB.
        // This test is the drift guard: the shipped copy must equal the single
        // source of truth byte for byte. (The generated-directory mechanisms
        // tried first silently produced an APK without the notices — found by
        // inspecting the downloaded CI artifact, not the build log.)
        val root = repoRoot() ?: return
        val source = File(root, "parity/THIRD_PARTY_NOTICES.md")
        val shipped = File(root, "app/src/release/assets/THIRD_PARTY_NOTICES.md")
        assertTrue("app/src/release/assets/THIRD_PARTY_NOTICES.md is missing", shipped.isFile)
        assertTrue(
            "the release asset notices differ from parity/THIRD_PARTY_NOTICES.md",
            source.readBytes().contentEquals(shipped.readBytes()),
        )
    }
}
