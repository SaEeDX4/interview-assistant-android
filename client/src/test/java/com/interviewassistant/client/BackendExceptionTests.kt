package com.interviewassistant.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BackendException half of `InterviewAssistant.Tests/ErrorStateTests.cs` (P4-C1 accounting):
 * ErrorStateTests constructs BackendException with (0, "network"), (504, "timeout") and (500, "")
 * and asserts through `ErrorCopy.Describe` — those assertions live in the :app ErrorStateTests
 * (P4-A), where they belong. What no suite pinned is the exception's own contract, which the app
 * layer branches on (`CloudService.Classify` on [BackendException.isUpdateRequired], ErrorCopy on
 * status/code/correlationId): the stored fields and the two derived properties, with the C#'s
 * values from `BackendClient.cs` (IsUpdateRequired = Status 426; IsEntitlement = 402 or the
 * device_limit / concurrent_limit / allowance_exhausted codes).
 */
class BackendExceptionTests {
    @Test
    fun fields_AreStoredVerbatim() {
        // The exact constructions ErrorStateTests.cs makes.
        val offline = BackendException(0, "network", "", null)
        assertEquals(0, offline.status)
        assertEquals("network", offline.code)
        assertEquals("", offline.message)
        assertNull(offline.correlationId)
        assertNull(offline.source)

        val timeout = BackendException(504, "timeout", "timeout", "c0ffee12-3456-7890-abcd-ef0123456789")
        assertEquals(504, timeout.status)
        assertEquals("timeout", timeout.code)
        assertEquals("timeout", timeout.message)
        assertEquals("c0ffee12-3456-7890-abcd-ef0123456789", timeout.correlationId)

        val server = BackendException(500, "", "System.NullReferenceException at …", "abcdef1234567890")
        assertEquals(500, server.status)
        assertEquals("", server.code)
    }

    @Test
    fun isUpdateRequired_OnlyForStatus426() {
        assertTrue(BackendException(426, "update_required", "", null).isUpdateRequired)
        assertFalse(BackendException(425, "", "", null).isUpdateRequired)
        assertFalse(BackendException(500, "", "", null).isUpdateRequired)
        assertFalse(BackendException(0, "offline", "", null).isUpdateRequired)
    }

    @Test
    fun isEntitlement_Status402OrTheThreeCodes() {
        assertTrue(BackendException(402, "anything", "", null).isEntitlement)
        assertTrue(BackendException(403, "device_limit", "", null).isEntitlement)
        assertTrue(BackendException(0, "concurrent_limit", "", null).isEntitlement)
        assertTrue(BackendException(429, "allowance_exhausted", "", null).isEntitlement)
        assertFalse(BackendException(500, "server_error", "", null).isEntitlement)
        assertFalse(BackendException(403, "forbidden", "", null).isEntitlement)
        assertFalse(BackendException(401, "signed_out", "", null).isEntitlement)
    }

    @Test
    fun source_CarriesTheTransportDiagnostic_WhenSet() {
        val ex = BackendException(0, "offline", "Cannot reach the service. Check your connection.", "cid-1")
        ex.source = "Unable to resolve host"
        assertEquals("Unable to resolve host", ex.source)
    }
}
