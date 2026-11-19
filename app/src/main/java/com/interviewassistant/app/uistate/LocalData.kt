package com.interviewassistant.app.uistate

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Comparator

/** Mirror of `InterviewAssistant.App/UiState/LocalData.cs`. */

/** Removal of an account's data on this PC after the account was deleted (plain class, unit-tested). */
object LocalData {
    /** A folder that could not be removed, and the exception type (never its message, which holds the path). */
    data class Failure(val folder: String, val error: String)

    /**
     * Deletes each existing folder (null entries are skipped) and keeps going after a failure. Returns the first folder
     * that could not be removed, or null when everything is gone — success is reported only when it is true.
     */
    fun delete(vararg folders: String?): Failure? {
        var first: Failure? = null
        for (dir in folders) {
            if (dir.isNullOrEmpty()) continue
            try {
                val f = File(dir)
                // NIO walk + delete (deepest first): failures surface as IOException, like Directory.Delete —
                // Kotlin's deleteRecursively() would swallow them and report a false success.
                // .NET Directory.Delete on a path that is a file throws IOException; mirror that instead of deleting it.
                if (f.exists() && !f.isDirectory) throw IOException("The path is not a directory")
                if (f.isDirectory) Files.walk(f.toPath()).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            } catch (ex: Exception) {
                // C# catches IOException or UnauthorizedAccessException; on the JVM the access-denied
                // counterpart is SecurityException (P4-PORT-NOTES DC-63).
                if (ex is IOException || ex is SecurityException) first = first ?: Failure(dir, ex.javaClass.simpleName)
                else throw ex
            }
        }
        return first
    }
}
