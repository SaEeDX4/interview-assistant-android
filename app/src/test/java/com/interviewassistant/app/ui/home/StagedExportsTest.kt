package com.interviewassistant.app.ui.home

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staged-export watcher (Package P4-E, DC-138): the view models write to the staged path
 * after the save picker returns; once the one-shot write settles, the file is copied to the
 * picked destination and the staging copy is removed. Both writer shapes are covered — a direct
 * write (report export) and a temp file renamed over the target (ExportFile's pattern).
 */
class StagedExportsTest {

    private class Recorder {
        val copies = CopyOnWriteArrayList<Pair<String, String>>()

        fun copy(file: File, destination: String) {
            copies += destination to file.readText()
        }
    }

    private fun awaitTrue(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("$description (timed out)", condition())
    }

    private fun staged(recorder: Recorder, dir: File, scope: CoroutineScope, timeoutMs: Long = 120_000) =
        StagedExports(
            directory = dir,
            scope = scope,
            copy = recorder::copy,
            pollMs = 25,
            settlePolls = 2,
            timeoutMs = timeoutMs,
        )

    @Test
    fun directWrite_isCopiedAfterSettling() {
        val dir = Files.createTempDirectory("staged").toFile()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val recorder = Recorder()
            val exports = staged(recorder, dir, scope)
            val path = exports.stage("interview-report.html", "content://picked/report")
            File(path).writeText("hello report")
            awaitTrue("the settled write is copied") { recorder.copies.size == 1 }
            assertEquals("content://picked/report" to "hello report", recorder.copies[0])
            assertFalse("the staging copy is removed", File(path).exists())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun renamedWrite_isCopied() {
        val dir = Files.createTempDirectory("staged").toFile()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val recorder = Recorder()
            val exports = staged(recorder, dir, scope)
            val path = exports.stage("account.json", "content://picked/account")
            // ExportFile's pattern: write a sibling temp file, then rename it over the target.
            val tmp = File("$path.deadbeef.tmp")
            tmp.writeText("{\"account\":true}")
            assertTrue(tmp.renameTo(File(path)))
            awaitTrue("the renamed write is copied") { recorder.copies.size == 1 }
            assertEquals("content://picked/account" to "{\"account\":true}", recorder.copies[0])
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun neverWritten_timesOutWithoutCopying() {
        val dir = Files.createTempDirectory("staged").toFile()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val recorder = Recorder()
            val exports = staged(recorder, dir, scope, timeoutMs = 600)
            exports.stage("aborted.html", "content://picked/aborted")
            Thread.sleep(1_800)
            assertTrue("nothing was written, so nothing is copied", recorder.copies.isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun restagingTheSameName_replacesThePendingDestination() {
        val dir = Files.createTempDirectory("staged").toFile()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val recorder = Recorder()
            val exports = staged(recorder, dir, scope)
            exports.stage("a.txt", "content://first")
            val path = exports.stage("a.txt", "content://second")
            File(path).writeText("data")
            awaitTrue("the write is copied once") { recorder.copies.size == 1 }
            Thread.sleep(300)
            assertEquals(1, recorder.copies.size)
            assertEquals("content://second" to "data", recorder.copies[0])
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
