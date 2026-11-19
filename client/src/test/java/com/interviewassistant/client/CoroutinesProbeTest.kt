package com.interviewassistant.client

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * TEMPORARY diagnostic probe, round 2 (P2 CI runs #75/#79/#80): round 1 proved that even a
 * minimal `async { throw AuthException }` + `await()` in a try/catch(AuthException) fails in
 * this environment — the exception escapes the catch. These variants discriminate why:
 * catch-Throwable (class identity?) vs supervisorScope (parent job propagation?) vs a delayed
 * throw (immediate-failure scheduling?). Delete after diagnosis.
 */
class CoroutinesProbeTest {

    @Test
    fun probeCatchThrowable() = runBlocking {
        val d = async { throw AuthException("browser_error", "User cancelled") }
        try {
            d.await()
            fail("expected AuthException")
        } catch (e: Throwable) {
            assertEquals("com.interviewassistant.client.AuthException", e.javaClass.name)
            assertEquals("User cancelled", e.message)
        }
    }

    @Test
    fun probeSupervisorScope() = runBlocking {
        supervisorScope {
            val d = async { throw AuthException("browser_error", "User cancelled") }
            try {
                d.await()
                fail("expected AuthException")
            } catch (e: AuthException) {
                assertEquals("User cancelled", e.message)
            }
        }
    }

    @Test
    fun probeDelayedThrow() = runBlocking {
        val d = async {
            delay(10)
            throw AuthException("browser_error", "User cancelled")
        }
        try {
            d.await()
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("User cancelled", e.message)
        }
    }
}
