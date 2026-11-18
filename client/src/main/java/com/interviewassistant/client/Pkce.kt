package com.interviewassistant.client

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Mirror of `InterviewAssistant.Client/Pkce.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** RFC 7636 PKCE helpers (S256 only). */
object Pkce {
    private val random = SecureRandom()

    /** 64 chars, within 43–128. */
    fun createVerifier(): String = base64Url(randomBytes(48))

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun createState(): String = base64Url(randomBytes(24))

    fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun isValidVerifier(v: String): Boolean =
        v.length in 43..128 && v.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' || it == '_' || it == '~' }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

/**
 * `Uri.EscapeDataString` equivalent used across the client mirror: every character except the
 * RFC 3986 unreserved set (A–Z a–z 0–9 `-` `_` `.` `~`) is percent-escaped as uppercase UTF-8 hex —
 * the same output .NET produces for the values this app escapes (URLs, language codes, challenges).
 */
internal object UriEscape {
    fun escapeDataString(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    /** `Uri.UnescapeDataString` equivalent (UTF-8 percent-decoding; `+` is a literal plus, as in .NET). */
    fun unescapeDataString(value: String): String {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length + 0 && i + 2 <= value.length - 1) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val HEX = "0123456789ABCDEF"
}
