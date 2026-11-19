package com.interviewassistant.client

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * TEMPORARY diagnostic probe (P2 CI runs #75/#79): in CloudAuthTests, an [AuthException] thrown
 * by an `async { receiver.waitForCode(...) }` child surfaced as the test failure even though the
 * test catches it around `await()`. These two minimal tests isolate whether deferred-exception
 * semantics themselves work in this test environment. Delete after diagnosis.
 */
class CoroutinesProbeTest {

    @Test
    fun probeAsyncThrowCaughtByAwait() = runBlocking {
        val d = async { throw AuthException("browser_error", "User cancelled") }
        try {
            d.await()
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("User cancelled", e.message)
        }
    }

    private val signal = CompletableDeferred<String?>()

    private suspend fun probeWait(): String {
        val outcome = try {
            withTimeout(20.seconds) { signal.await() }
        } catch (e: TimeoutCancellationException) {
            throw java.util.concurrent.TimeoutException("timeout")
        }
        if (outcome == "ERROR") throw AuthException("browser_error", "User cancelled")
        return outcome ?: throw java.util.concurrent.TimeoutException("cancelled")
    }

    @Test
    fun probeReceiverStyleThrowCaughtByAwait() = runBlocking {
        val wait = async { probeWait() }
        Thread {
            Thread.sleep(50)
            signal.complete("ERROR")
        }.start()
        try {
            wait.await()
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("User cancelled", e.message)
        }
    }
}
