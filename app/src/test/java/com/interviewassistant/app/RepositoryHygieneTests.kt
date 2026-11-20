package com.interviewassistant.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `InterviewAssistant.Tests/RepositoryHygieneTests.cs` (P7, TEST-MAP closure):
 * fails if anything resembling an API key is committed. Same regex, same fake-fixture
 * marker, same size bound, same "pass outside a checkout" behaviour. Adaptations: the
 * repository root is the folder holding settings.gradle.kts (the .sln counterpart),
 * and the excluded generated folders are this build's (`.git`, `build`, `.gradle`
 * instead of `bin`/`obj`/`.claude/worktrees`).
 */
class RepositoryHygieneTests {

    @Test
    fun noSecretsInRepository() {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        if (dir == null) return // not running from a source checkout
        val secret = Regex(
            """sk-(proj-)?[A-Za-z0-9_\-]{20,}|(api[_-]?key|secret)\s*[:=]\s*["']?[A-Za-z0-9_\-]{16,}""",
            RegexOption.IGNORE_CASE,
        )
        val excluded = Regex("""[\\/](\.git|build|\.gradle)[\\/]""")
        val offenders = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.length() < 2_000_000 && !excluded.containsMatchIn(it.path) }
            .forEach { f ->
                // Deliberately fake values in tests are allowed only on lines explicitly marked as such.
                val text = try {
                    String(f.readBytes(), Charsets.UTF_8)
                } catch (e: Exception) {
                    return@forEach
                }
                if (text.lineSequence().any { l -> secret.containsMatchIn(l) && !l.contains(FAKE_MARKER) }) {
                    offenders += f.path
                }
            }
        assertTrue("Files resembling committed secrets:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private companion object {
        const val FAKE_MARKER = "fake-credential: test fixture"
    }
}
