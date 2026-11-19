package com.interviewassistant.core.persistence

import com.interviewassistant.core.domain.newGuidN
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Mirror of `InterviewAssistant.Core/Persistence/ExportFile.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Adaptation: the C# has two `WriteAsync` overloads (a `Func<Stream, Task>` and an `Action<Stream>`
 * that wraps it). Kotlin cannot overload on a suspending vs non-suspending lambda without making
 * literal-lambda call sites ambiguous, so only the suspending form exists — a synchronous writer is
 * still a valid suspending lambda body (P3-PORT-NOTES).
 *
 * Writes an exported file through a temporary file next to the target, moved into place only when it is complete. A
 * failure (disk full, folder not writable) never leaves a truncated file under the chosen name, and the temporary file
 * is removed again. An existing file of that name stays as it was until the new one is complete.
 */
object ExportFile {
    /** The temporary file used for [path] (same folder, so the final move is a rename). */
    fun tempPathFor(path: String): String = path + "." + newGuidN().substring(0, 8) + ".tmp"

    suspend fun writeAsync(path: String, write: suspend (OutputStream) -> Unit) {
        val tmp = tempPathFor(path)
        try {
            Files.newOutputStream(File(tmp).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { fs ->
                write(fs)
                fs.flush()
            }
            Files.move(File(tmp).toPath(), File(path).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            tryDelete(tmp)
            throw e
        }
    }

    suspend fun writeTextAsync(path: String, text: String) =
        writeAsync(path) { s -> s.write(text.toByteArray(Charsets.UTF_8)) }

    private fun tryDelete(path: String) {
        try {
            val f = File(path)
            if (f.exists()) f.delete()
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) {
                // best effort: the export already failed
            } else throw ex
        }
    }
}
