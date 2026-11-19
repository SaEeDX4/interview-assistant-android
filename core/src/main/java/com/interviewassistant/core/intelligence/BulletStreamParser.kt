package com.interviewassistant.core.intelligence

import com.interviewassistant.core.knowledge.AnswerMode

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/BulletStreamParser.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Converts a token stream into STABLE display units. A bullet is only emitted once complete (newline seen
 * or stream ended), so text already shown never changes under the reader. The trailing incomplete bullet
 * is exposed separately as a "pending" preview.
 */
class BulletStreamParser {
    private val buffer = StringBuilder()
    private val bulletList = mutableListOf<String>()
    private var modeParsed = false

    var mode: AnswerMode? = null
        private set

    val bullets: List<String> get() = bulletList

    var pending: String = ""
        private set

    /** Feeds a delta; returns bullets that became complete with this delta. */
    fun push(delta: String): List<String> {
        buffer.append(delta)
        val completed = mutableListOf<String>()
        while (true) {
            val text = buffer.toString()
            val nl = text.indexOf('\n')
            if (nl < 0) break
            val line = text.substring(0, nl)
            buffer.deleteRange(0, nl + 1)
            val item = processLine(line)
            if (item != null) {
                bulletList.add(item)
                completed.add(item)
            }
        }
        val pendingText = buffer.toString()
        pending = if (tryParseMode(pendingText, peekOnly = true)) "" else TextNormalizer.stripBullet(pendingText)
        return completed
    }

    /** Flushes the final line at end of stream. */
    fun complete(): List<String> {
        val rest = buffer.toString()
        buffer.setLength(0)
        pending = ""
        val item = processLine(rest) ?: return listOf()
        bulletList.add(item)
        return listOf(item)
    }

    private fun processLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (tryParseMode(trimmed, peekOnly = false)) return null
        val content = TextNormalizer.stripBullet(trimmed)
        if (content.isEmpty()) return null
        return content
    }

    private fun tryParseMode(line: String, peekOnly: Boolean): Boolean {
        val t = line.trim().trimStart('*').trim()
        if (!t.startsWith("MODE", ignoreCase = true)) return false
        if (peekOnly) return true
        if (modeParsed) return true
        modeParsed = true
        val upper = t.uppercase()
        mode = if (upper.contains("VERIFIED")) AnswerMode.Verified else if (upper.contains("BRIDGE")) AnswerMode.Bridge else AnswerMode.Hypothetical
        return true
    }
}
