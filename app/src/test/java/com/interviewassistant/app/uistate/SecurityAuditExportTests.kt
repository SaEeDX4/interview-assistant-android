package com.interviewassistant.app.uistate

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of the one `InterviewAssistant.Tests/SecurityAuditExportTests.cs` method whose subject
 * lives in the App's UiState layer (P7, TEST-MAP closure): `LocalDataDeletion_ReportsAFolderItCouldNotRemove`.
 * The rest of that C# file is ported in `:core` (`core/persistence/SecurityAuditExportTests.kt`)
 * or recorded as (c) in parity/TEST-MAP.md. Same scenario, same expected values; the C#'s own
 * Windows-only assertion branch is kept as a platform branch (on Linux an open file does not
 * lock its folder, exactly the difference the C# test itself encodes).
 */
class SecurityAuditExportTests {

    @Test
    fun localDataDeletion_ReportsAFolderItCouldNotRemove() {
        val root = Files.createTempDirectory("ia-localdata-").toFile()
        try {
            File(root, "a/sub").mkdirs()
            File(root, "a/sub/f.txt").writeText("x")

            assertNull(LocalData.delete(File(root, "a").path, null, File(root, "missing").path))
            assertFalse(File(root, "a").exists())

            // A file where a folder is expected cannot be deleted as a folder: reported, the other folder is still removed.
            val notADir = File(root, "file-not-dir")
            notADir.writeText("x")
            val other = File(root, "b")
            other.mkdirs()
            val locked = File(root, "locked")
            locked.mkdirs()
            FileOutputStream(File(locked, "open.txt")).use { stream ->
                stream.write("x".toByteArray())
                val failure = LocalData.delete(locked.path, other.path)
                if (System.getProperty("os.name").startsWith("Windows")) {
                    assertEquals(locked.path, failure?.folder)
                    // The type only, never the path.
                    assertFalse(failure!!.error.contains(root.path))
                }
                assertFalse(other.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
