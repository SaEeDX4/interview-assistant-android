package com.interviewassistant.app.services

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.concurrent.ConcurrentLinkedQueue

/** Mirror of `InterviewAssistant.App/Services/AppLog.cs`. */

/** Small structured log: in-memory ring for the diagnostics panel + daily file. Redacts anything key-like. */
object AppLog {
    private val ring = ConcurrentLinkedQueue<String>()
    private val fileLock = Any()
    private val keyPattern = Regex("""(sk-[A-Za-z0-9_\-]{6})[A-Za-z0-9_\-]+""")

    private val writtenListeners = mutableListOf<(String) -> Unit>()

    /** The C# `event Action<string> Written`: raised with every line written. */
    fun addWrittenListener(listener: (String) -> Unit) {
        synchronized(writtenListeners) { writtenListeners.add(listener) }
    }

    fun removeWrittenListener(listener: (String) -> Unit) {
        synchronized(writtenListeners) { writtenListeners.remove(listener) }
    }

    fun info(m: String) = write("INFO", m)
    fun warn(m: String) = write("WARN", m)
    fun error(m: String, ex: Exception? = null) = write("ERROR", if (ex == null) m else "$m: ${describe(ex)}")

    /**
     * What the log keeps of an exception: file-system errors only by type (their message holds a path with the
     * user name and, for exports, the candidate's name — and Copy diagnostics shares the last lines);
     * others with their message. (JVM type names stand in for the .NET ones — P4-PORT-NOTES DC-81.)
     */
    fun describe(ex: Exception): String =
        if (ex is IOException || ex is SecurityException) ex.javaClass.simpleName
        else "${ex.javaClass.simpleName}: ${ex.message}"

    /** One log line: control characters (line breaks from untrusted text such as a hostile file's JSON keys) become spaces. */
    fun sanitize(message: String): String {
        if (message.none { Character.isISOControl(it) }) return message
        return buildString(message.length) {
            for (c in message) append(if (Character.isISOControl(c)) ' ' else c)
        }
    }

    private fun write(level: String, message: String) {
        val line = "${LocalDateTime.now().format(timeFormat)} $level " +
            keyPattern.replace(sanitize(message)) { it.groupValues[1] + "…" }
        ring.add(line)
        while (ring.size > 400) ring.poll()
        try {
            synchronized(fileLock) {
                File(AppPaths.logs, "app-${LocalDate.now().format(dayFormat)}.log")
                    .appendText(line + System.lineSeparator(), Charsets.UTF_8)
            }
        } catch (e: IOException) { /* logging must never crash the app */ }
        val listeners = synchronized(writtenListeners) { writtenListeners.toList() }
        for (listener in listeners) listener(line)
    }

    val recent: List<String> get() = ring.toList()

    /** Daily log files older than [keepDays] days are deleted (called at startup). */
    fun prune(keepDays: Int = 14): Int {
        var removed = 0
        try {
            val cutoff = LocalDate.now().minusDays(keepDays.toLong())
            val files = File(AppPaths.logs).listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".log") }
                ?: return 0
            for (f in files) {
                val stamp = f.name.removePrefix("app-").removeSuffix(".log")
                val day = try {
                    LocalDate.parse(stamp, dayFormat)
                } catch (e: DateTimeParseException) {
                    null
                }
                if (day != null && day < cutoff) {
                    try {
                        if (f.delete()) removed++
                    } catch (e: SecurityException) { /* retention is best-effort */ }
                }
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is SecurityException) throw e
            // retention is best-effort
        }
        return removed
    }

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val dayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
}
