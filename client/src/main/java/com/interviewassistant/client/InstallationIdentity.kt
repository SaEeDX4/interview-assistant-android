package com.interviewassistant.client

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Mirror of `InterviewAssistant.Client/InstallationIdentity.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Random per-installation identifier (no hardware fingerprinting). Survives upgrades because it lives in the user
 * profile, so reinstalling on the same machine re-uses the same device slot.
 */
object InstallationIdentity {
    private val random = SecureRandom()

    fun getOrCreate(path: String): String {
        try {
            val file = File(path)
            if (file.exists()) {
                val existing = file.readText().trim()
                if (isValid(existing)) return existing
            }
        } catch (e: IOException) {
            // fall through and create a fresh id, as in the C#
        }
        val id = "inst-" + ByteArray(16).also { random.nextBytes(it) }.toHexLower()
        File(path).parentFile?.mkdirs()
        File(path).writeText(id)
        return id
    }

    fun isValid(s: String): Boolean =
        s.length in 12..64 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

    private fun ByteArray.toHexLower(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0xF])
        }
        return sb.toString()
    }
}
