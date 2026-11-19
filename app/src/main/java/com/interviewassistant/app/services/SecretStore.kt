package com.interviewassistant.app.services

import com.interviewassistant.app.platform.KeystoreDataProtector
import com.interviewassistant.core.persistence.IDataProtector
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

/** Mirror of `InterviewAssistant.App/Services/SecretStore.cs`. */

/**
 * Stores the API key encrypted at rest: only this app on this device can decrypt it.
 * The C# uses Windows DPAPI (CurrentUser scope) with the entropy "InterviewAssistant.v1.apikey";
 * the Android counterpart is the Keystore protector (P2, platform layer) under a dedicated key
 * alias — the alias is the entropy binding, so a protector with a different alias cannot read
 * the file (P4-PORT-NOTES DC-79). Never written in plaintext, never logged, never packaged.
 */
object SecretStore {
    private const val FileName = "apikey.dpapi"
    private const val KeyAlias = "interview-assistant.apikey.v1"

    /** The protection seam: the platform Keystore protector in production; fakes in unit tests. */
    var protector: IDataProtector = KeystoreDataProtector(KeyAlias)
        internal set

    /** The wrong-entropy probe for [selfTestRoundTrip]: a protector bound to a different alias. */
    internal var wrongEntropyProtector: () -> IDataProtector =
        { KeystoreDataProtector("$KeyAlias.wrong-entropy") }

    private val filePath: String get() = File(AppPaths.roaming, FileName).path

    private var cache: String? = null

    val hasKey: Boolean get() = !getApiKey().isNullOrEmpty()

    fun getApiKey(): String? {
        if (cache != null) return cache
        try {
            val file = File(filePath)
            if (!file.exists()) return null
            val plain = protector.unprotect(file.readBytes())
            cache = String(plain, Charsets.UTF_8)
            plain.fill(0)
            return cache
        } catch (ex: Exception) {
            // The C# catches CryptographicException | IOException | UnauthorizedAccessException;
            // IllegalArgumentException is the platform protector's malformed-ciphertext signal
            // (the :core WorkspaceStore catch set includes it for the same reason).
            if (ex is GeneralSecurityException || ex is IOException || ex is SecurityException ||
                ex is IllegalArgumentException
            ) {
                AppLog.warn("Stored API key could not be decrypted (" + ex.javaClass.simpleName + "). Please re-enter it.")
                return null
            }
            throw ex
        }
    }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        val plain = trimmed.toByteArray(Charsets.UTF_8)
        val enc = protector.protect(plain)
        plain.fill(0)
        File(filePath).writeBytes(enc)
        cache = trimmed
    }

    fun clear() {
        cache = null
        val file = File(filePath)
        if (file.exists()) file.delete()
    }

    /** Protect/unprotect round-trip without touching the stored key (used by the self-test). */
    fun selfTestRoundTrip(): Boolean {
        val sample = ("sk-selftest-" + UUID.randomUUID()).toByteArray(Charsets.UTF_8)
        val enc = protector.protect(sample)
        if (indexOf(enc, sample.copyOfRange(0, 8)) >= 0) return false // ciphertext must not contain plaintext
        val dec = protector.unprotect(enc)
        try {
            wrongEntropyProtector().unprotect(enc)
            return false
        } catch (e: GeneralSecurityException) { /* expected: the wrong entropy (key alias) must fail */ }
        return dec.contentEquals(sample)
    }

    fun mask(key: String?): String =
        if (key.isNullOrEmpty()) "(not set)" else if (key.length <= 8) "••••" else key.substring(0, 3) + "…" + key.substring(key.length - 4)

    /** `ReadOnlySpan<byte>.IndexOf` counterpart: first index of [needle] in [haystack], or -1. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
