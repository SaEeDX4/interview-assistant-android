package com.interviewassistant.app.services

import com.interviewassistant.core.persistence.IDataProtector
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The SecretStore mirror over a fake protector (the Keystore protector is platform code):
 * mask spellings, the file round-trip, trimming, clear, corrupt-file behaviour and the
 * SelfTestRoundTrip semantics (Services/SelfTest.cs "DPAPI credential protection round-trip").
 */
class SecretStoreTests {
    /** A keyed XOR protector: ciphertext differs from plaintext in every byte; the wrong key fails. */
    private class FakeProtector(private val key: Byte) : IDataProtector {
        override fun protect(plain: ByteArray): ByteArray {
            val out = ByteArray(plain.size + 1)
            out[0] = key
            for (i in plain.indices) out[i + 1] = (plain[i].toInt() xor key.toInt()).toByte()
            return out
        }

        override fun unprotect(cipher: ByteArray): ByteArray {
            if (cipher.isEmpty() || cipher[0] != key) throw GeneralSecurityException("wrong key")
            return ByteArray(cipher.size - 1) { (cipher[it + 1].toInt() xor key.toInt()).toByte() }
        }
    }

    private lateinit var dir: File
    private var savedIsolated: String? = null
    private val originalProtector = SecretStore.protector
    private val originalWrongProtector = SecretStore.wrongEntropyProtector

    private val storeFile: File get() = File(AppPaths.roaming, "apikey.dpapi")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("secretstore-test").toFile()
        savedIsolated = AppPaths.isolatedRoot
        AppPaths.isolatedRoot = dir.path
        SecretStore.protector = FakeProtector(0x5A)
        SecretStore.wrongEntropyProtector = { FakeProtector(0x11) }
        SecretStore.clear()
    }

    @After
    fun tearDown() {
        SecretStore.clear()
        SecretStore.protector = originalProtector
        SecretStore.wrongEntropyProtector = originalWrongProtector
        AppPaths.isolatedRoot = savedIsolated
        dir.deleteRecursively()
    }

    @Test
    fun mask_Spellings() {
        assertEquals("(not set)", SecretStore.mask(null))
        assertEquals("(not set)", SecretStore.mask(""))
        assertEquals("••••", SecretStore.mask("short"))
        assertEquals("••••", SecretStore.mask("12345678")) // length 8 still fully masked
        assertEquals("123…6789", SecretStore.mask("123456789"))
        assertEquals("sk-…abcd", SecretStore.mask("sk-1234567890abcd"))
    }

    @Test
    fun roundTrip_StoresCiphertextNotPlaintext_AndReadsItBack() {
        SecretStore.setApiKey("sk-live-123456789")
        assertTrue(SecretStore.hasKey)
        assertEquals("sk-live-123456789", SecretStore.getApiKey()) // in-memory cache

        assertTrue(storeFile.isFile)
        val onDisk = storeFile.readBytes()
        assertFalse(onDisk.contentEquals("sk-live-123456789".toByteArray(Charsets.UTF_8)))
        assertEquals(-1, indexOf(onDisk, "sk-live".toByteArray(Charsets.UTF_8)))

        resetCache() // simulate a process restart: the value must come from the protected file
        assertEquals("sk-live-123456789", SecretStore.getApiKey())
    }

    @Test
    fun setApiKey_TrimsWhitespace() {
        SecretStore.setApiKey("  sk-trimmed  ")
        assertEquals("sk-trimmed", SecretStore.getApiKey())
    }

    @Test
    fun clear_RemovesTheKeyAndTheFile() {
        SecretStore.setApiKey("sk-gone")
        SecretStore.clear()
        assertFalse(SecretStore.hasKey)
        assertNull(SecretStore.getApiKey())
        assertFalse(storeFile.exists())
    }

    @Test
    fun getApiKey_NoFileOrUnreadableFile_ReturnsNull() {
        assertNull(SecretStore.getApiKey())
        storeFile.writeBytes(byteArrayOf(0x7F, 1, 2, 3)) // not written by our protector
        resetCache()
        assertNull(SecretStore.getApiKey())
        assertFalse(SecretStore.hasKey)
    }

    @Test
    fun selfTestRoundTrip_ProtectUnprotectOk_WrongEntropyRejected() {
        assertTrue(SecretStore.selfTestRoundTrip())
    }

    private fun resetCache() {
        val field = SecretStore::class.java.getDeclaredField("cache")
        field.isAccessible = true
        field.set(SecretStore, null)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[i + it] == needle[it] }) return i
        }
        return -1
    }
}
