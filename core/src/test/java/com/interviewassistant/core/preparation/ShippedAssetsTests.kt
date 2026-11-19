package com.interviewassistant.core.preparation

import com.interviewassistant.core.Fixtures
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/ShippedAssetsTests.cs` — a gap closed by Package D.
 *
 * What the installer ships (knowledge/ next to the EXE) must be found, complete and free of any candidate's data.
 * Android adaptation: the shipped layout is `:core`'s `src/main/assets/knowledge/` (the same files the
 * app packages), materialized into an app-shaped temp dir exactly as Fixtures.assets() locates them.
 */
class ShippedAssetsTests {

    /** The on-disk shipped generic-knowledge directory (source of Fixtures.assets()). */
    private fun genericDir(): File {
        val roots = listOf(
            File("src/main/assets/knowledge"),
            File("core/src/main/assets/knowledge"),
            File("../core/src/main/assets/knowledge"),
        )
        return roots.firstOrNull { f -> File(f, PreparationAssets.LibraryFileName).isFile }
            ?: throw IllegalStateException("shipped knowledge assets not found from ${File(".").absolutePath}")
    }

    private fun copyDir(from: File, to: File) {
        to.mkdirs()
        from.walkTopDown().forEach { f ->
            if (f.isFile) {
                val target = File(to, f.relativeTo(from).path)
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
            }
        }
    }

    @Test
    fun publishedLayoutIsFoundWithoutAnySampleQuestionBank() {
        val app = Files.createTempDirectory("ia-pub-").toFile()
        try {
            copyDir(genericDir(), File(app, "knowledge"))
            assertFalse(File(app, "knowledge/question_bank.json").exists()) // no candidate pack is shipped
            val assets = PreparationAssets.loadShipped(app.toString())
            assertTrue("library ${assets.library.size}", assets.library.size >= 50)
            assertTrue(assets.playbooks.isNotEmpty())
        } finally {
            app.deleteRecursively()
        }
    }

    @Test
    fun shippedKnowledgeContainsNoSampleCandidateOrCompany() {
        val sample = Fixtures.testKnowledge().context
        val forbidden = (listOf(sample.candidateName, sample.companyName) + sample.candidateName.split(' '))
            .filter { t -> t.length >= 4 }.distinct()
        assertTrue(forbidden.isNotEmpty())
        genericDir().walkTopDown().filter { f -> f.isFile }.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            for (term in forbidden) {
                assertFalse("${file.name} mentions '$term'", text.contains(term, ignoreCase = true))
            }
        }
    }
}
