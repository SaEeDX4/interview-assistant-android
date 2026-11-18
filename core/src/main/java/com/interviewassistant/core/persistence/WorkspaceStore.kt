package com.interviewassistant.core.persistence

/**
 * Mirror of `InterviewAssistant.Core/Persistence/WorkspaceStore.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * P2 slice: [IDataProtector] and [NoProtection] only — the types `:client` consumes
 * (`ProtectedFileTokenStore`). The `WorkspaceStore` document store itself is ported in Phase P3 with the
 * rest of the Persistence package (TRANSFER-MAP §7); it is not stubbed here, it is simply not part of
 * this file yet.
 */

/** Encrypts data at rest. Desktop uses Windows DPAPI; tests use [NoProtection]. */
interface IDataProtector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(cipher: ByteArray): ByteArray
}

class NoProtection : IDataProtector {
    override fun protect(plain: ByteArray): ByteArray = plain
    override fun unprotect(cipher: ByteArray): ByteArray = cipher
}
