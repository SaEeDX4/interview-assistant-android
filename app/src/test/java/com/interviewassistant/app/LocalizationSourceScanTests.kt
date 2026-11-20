package com.interviewassistant.app

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.PersonalCategories
import com.interviewassistant.core.languages.Loc
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Android counterparts of the three source-scanning methods of
 * `InterviewAssistant.Tests/LocalizationCoverageTests.cs` (P7, TEST-MAP closure). The C#
 * originals scan the Windows tree (XAML literals, UI-facing C# literals, Loc key usage);
 * these scan the Android tree with the same rules adapted to Compose/Kotlin sinks. Like
 * the C# hygiene tests they walk up from the test working directory to the repository
 * root (the folder holding settings.gradle.kts) and pass vacuously outside a checkout.
 *
 * One documented exception: `pf_title` is used by BOTH the Windows code
 * (PreInterviewPage.xaml.cs) and this port, and exists in NO language table — a latent
 * gap in the shared i18n assets themselves, faithfully mirrored (Loc falls back to the
 * key on both platforms). It is allowlisted here and registered as DC-196 in
 * P7-PORT-NOTES.md; fixing it is a Windows lockstep change to the shared assets, not an
 * Android-only edit (the assets are byte-identical by gate).
 */
class LocalizationSourceScanTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() = UiStateLocFixtures.installLocTables()

        /** Repository root (settings.gradle.kts), or null outside a source checkout. */
        private fun repoRoot(): File? {
            var d: File? = File(".").absoluteFile
            while (d != null) {
                if (File(d, "settings.gradle.kts").isFile) return d
                d = d.parentFile
            }
            return null
        }

        private fun ktFiles(root: File, vararg dirs: String): List<File> =
            dirs.flatMap { dir ->
                val base = File(root, dir)
                if (!base.isDirectory) emptyList()
                else base.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }

        /** Non-user-facing literals the sink scan tolerates (Compose animation labels — the XamlAllowed counterpart). */
        private val allowedLiterals = setOf("toastExitAlpha", "pulse", "pulseAlpha", "bulletAlpha", "bulletShift")

        // UI-facing sinks whose string argument must come from Loc (logs, exceptions and diagnostics are exempt):
        // the Text composable, the named text-bearing arguments, and toast calls — with at least 3 letters.
        private val uiLiteral = Regex(
            """(?<![\w".])Text\(\s*"[^"]*[A-Za-z]{3,}|""" +
                """\b(?:text|label|title|hint|placeholder|contentDescription)\s*=\s*"([^"]*[A-Za-z]{3,}[^"]*)"|""" +
                """\bToast\(\s*"[^"]*[A-Za-z]{3,}""",
        )
    }

    /** The XAML counterpart: the Compose UI sources carry no hard-coded user text. */
    @Test
    fun composeUiSources_HaveNoHardCodedUserText() {
        val root = repoRoot() ?: return
        val bad = mutableListOf<String>()
        for (f in ktFiles(root, "app/src/main/java/com/interviewassistant/app/ui")) {
            f.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.contains("AppLog.")) return@forEachIndexed
                for (m in uiLiteral.findAll(line)) {
                    val literal = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value
                    if (allowedLiterals.any { literal.contains(it) }) continue
                    bad += "${f.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("Hard-coded UI text:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** The DesktopCode counterpart: no UI-facing literal anywhere in the app module's code. */
    @Test
    fun appCode_HasNoHardCodedUserText() {
        val root = repoRoot() ?: return
        val bad = mutableListOf<String>()
        for (f in ktFiles(root, "app/src/main/java")) {
            f.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.contains("AppLog.")) return@forEachIndexed
                // {expressions} inside a template are data, not text (the C# strips interpolations the same way).
                val withoutInterpolation = line.replace(Regex("""\{[^{}"]*}"""), "")
                for (m in uiLiteral.findAll(withoutInterpolation)) {
                    val literal = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value
                    if (allowedLiterals.any { literal.contains(it) }) continue
                    bad += "${f.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("Hard-coded UI text in code:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun everyKeyUsedInTheAppExistsInEnglish() {
        val root = repoRoot() ?: return
        val used = mutableSetOf<String>()
        val locLiteral = Regex("""\bLoc\.(?:t|f)\(\s*"([a-z][a-z0-9_]+)"""")
        // Files that declare the C# T/F-style private aliases (HomeViewModel, PreInterviewPageControllerImpl)
        // are scanned for the alias calls too — a wider net than the C# original, which is how pf_title was found.
        val aliasLiteral = Regex("""\b(?:f|t)\(\s*"([a-z][a-z0-9_]+)"""")
        val aliasDecl = Regex("""private fun (?:f|t)\(key: String""")
        for (f in ktFiles(root, "app/src/main/java", "client/src/main/java", "core/src/main/java")) {
            val s = f.readText()
            locLiteral.findAll(s).forEach { used += it.groupValues[1] }
            if (aliasDecl.containsMatchIn(s)) aliasLiteral.findAll(s).forEach { used += it.groupValues[1] }
        }
        val documentedSharedGaps = setOf("pf_title") // DC-196: missing on Windows too; see class doc
        val missing = used.filter { k -> !k.endsWith("_") && k !in documentedSharedGaps && !Loc.has(k) }.sorted()
        assertTrue("Keys missing from en.json: " + missing.joinToString(", "), missing.isEmpty())
        // Dynamic keys built from enums/categories (identical families to the C#).
        for (k in FactKind.entries) assertTrue(k.name, Loc.has("fact_kind_" + k.name.lowercase()))
        for (c in PersonalCategories.all) {
            assertTrue(c.key, Loc.has("pc_${c.key}_label") && Loc.has("pc_${c.key}_prompt"))
            assertTrue(c.section, Loc.has("pcs_" + c.section))
        }
        for (s in listOf("active", "stopped", "device_error", "no_playback_device", "reconnecting_device")) {
            assertTrue(s, Loc.has("audio_" + s))
        }
        for (f in listOf("compact", "normal", "large", "extralarge")) assertTrue(f, Loc.has("font_" + f))
    }
}
