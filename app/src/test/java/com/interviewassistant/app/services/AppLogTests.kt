package com.interviewassistant.app.services

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The AppLog mirror: redaction, sanitizing, exception description, the 400-line ring, listeners, prune. */
class AppLogTests {
    private lateinit var dir: File
    private var savedIsolated: String? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("applog-test").toFile()
        savedIsolated = AppPaths.isolatedRoot
        AppPaths.isolatedRoot = null
        AppPaths.installBaseDirs(dir.path, dir.path, dir.path)
    }

    @After
    fun tearDown() {
        AppPaths.isolatedRoot = savedIsolated
        dir.deleteRecursively()
    }

    @Test
    fun sanitize_ReplacesControlCharactersWithSpaces() {
        assertEquals("a b c d", AppLog.sanitize("a\nb\tc\u0001d"))
        assertEquals("plain text", AppLog.sanitize("plain text"))
        assertEquals("", AppLog.sanitize(""))
    }

    @Test
    fun write_RedactsApiKeys_KeepingSkPlusSixCharacters() {
        val marker = "redact-${System.nanoTime()}"
        AppLog.info("$marker key sk-abcdef1234567890 done")
        val line = AppLog.recent.last { it.contains(marker) }
        assertTrue(line.contains("sk-abcdef…"))
        assertFalse(line.contains("1234567890"))
        // too short to match the key pattern: left alone
        AppLog.info("$marker short sk-abc")
        assertTrue(AppLog.recent.last { it.contains(marker) }.contains("sk-abc"))
    }

    @Test
    fun write_LineFormatIsTimeLevelMessage_AndTheDailyFileIsWritten() {
        val marker = "format-${System.nanoTime()}"
        AppLog.info(marker)
        val line = AppLog.recent.last { it.contains(marker) }
        assertTrue(line.matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3} INFO .*""")))
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val file = File(AppPaths.logs, "app-$today.log")
        assertTrue(file.isFile)
        assertTrue(file.readText(Charsets.UTF_8).contains(marker))
    }

    @Test
    fun describe_TypeNameOnlyForIoAndSecurity_FullMessageOtherwise() {
        assertEquals("FileNotFoundException", AppLog.describe(FileNotFoundException("/secret/path/file.txt")))
        assertEquals("IOException", AppLog.describe(IOException("some path")))
        assertEquals("IllegalStateException: boom", AppLog.describe(IllegalStateException("boom")))
    }

    @Test
    fun error_AppendsTheExceptionDescription() {
        val marker = "err-${System.nanoTime()}"
        AppLog.error(marker, IllegalStateException("kaput"))
        assertTrue(AppLog.recent.last { it.contains(marker) }.contains("ERROR $marker: IllegalStateException: kaput"))
        AppLog.error(marker)
        assertTrue(AppLog.recent.last { it.contains(marker) }.endsWith("ERROR $marker"))
    }

    @Test
    fun ring_KeepsOnlyTheLast400Lines() {
        val marker = "ring-${System.nanoTime()}"
        for (i in 0..404) AppLog.info("$marker m$i")
        val recent = AppLog.recent
        assertEquals(400, recent.size)
        assertTrue(recent.first().contains("$marker m5"))
        assertTrue(recent.last().contains("$marker m404"))
    }

    @Test
    fun written_NotifiesListenersUntilRemoved() {
        val marker = "listen-${System.nanoTime()}"
        val received = mutableListOf<String>()
        val listener: (String) -> Unit = { received.add(it) }
        AppLog.addWrittenListener(listener)
        try {
            AppLog.info("$marker one")
        } finally {
            AppLog.removeWrittenListener(listener)
        }
        AppLog.info("$marker two")
        assertEquals(1, received.size)
        assertTrue(received[0].contains("$marker one"))
    }

    @Test
    fun prune_DeletesDailyFilesOlderThanTheRetention_KeepsTheBoundaryDay() {
        val logs = File(AppPaths.logs)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        fun stamp(daysAgo: Long) = LocalDate.now().minusDays(daysAgo).format(fmt)
        val old = File(logs, "app-${stamp(20)}.log").apply { writeText("old") }
        val boundary = File(logs, "app-${stamp(14)}.log").apply { writeText("boundary") }
        val recent = File(logs, "app-${stamp(1)}.log").apply { writeText("recent") }
        val notADate = File(logs, "app-notadate.log").apply { writeText("x") }
        val other = File(logs, "notes.log").apply { writeText("x") }

        assertEquals(1, AppLog.prune())
        assertFalse(old.exists())
        assertTrue(boundary.exists()) // day == cutoff is not older than the retention
        assertTrue(recent.exists())
        assertTrue(notADate.exists())
        assertTrue(other.exists())
    }
}
