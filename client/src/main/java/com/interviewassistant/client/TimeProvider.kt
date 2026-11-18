package com.interviewassistant.client

import java.time.OffsetDateTime
import kotlin.time.Duration

/**
 * Mirror of the .NET `TimeProvider` abstraction as the Windows client consumes it
 * (`GetUtcNow`, and delays that tests can leave on the system timer). The Windows client's own
 * test double (`ManualTime` in CloudAuthTests.cs) overrides only `GetUtcNow`; [delay] therefore
 * defaults to the real coroutine delay, exactly like the .NET default timer.
 *
 * This type has no Windows file of its own — it stands in for the BCL type the C# client code is
 * written against (P2-PORT-NOTES.md). It lives in `:client` because only the client mirror uses it.
 */
interface TimeProvider {
    fun utcNow(): OffsetDateTime

    suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
    }

    companion object {
        val System: TimeProvider = object : TimeProvider {
            override fun utcNow(): OffsetDateTime = OffsetDateTime.now(java.time.ZoneOffset.UTC)
        }
    }
}
