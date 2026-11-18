package com.interviewassistant.client

import com.interviewassistant.core.diagnostics.InstallLayout
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `InterviewAssistant.Tests/UpdatePolicyTests.cs` (TRANSFER-MAP §5.2: same test names, same
 * inputs, same expected values). The xUnit [Theory] runs as a case loop inside one test.
 */
class UpdatePolicyTests {
    companion object {
        private val start: OffsetDateTime = OffsetDateTime.parse("2026-10-07T09:00:00Z")
    }

    @Test
    fun neverChecksDuringALiveSession() {
        assertFalse(UpdatePolicy.shouldCheck(sessionActive = true, lastCheck = null, now = start.plusHours(10), appStarted = start))
        assertTrue(UpdatePolicy.shouldCheck(sessionActive = false, lastCheck = null, now = start.plusHours(10), appStarted = start))
    }

    @Test
    fun waitsAfterStartupAndBetweenChecks() {
        assertFalse(UpdatePolicy.shouldCheck(false, null, start.plusSeconds(10), start)) // app just opened
        assertFalse(UpdatePolicy.shouldCheck(false, start.plusHours(1), start.plusHours(2), start))
        assertTrue(UpdatePolicy.shouldCheck(false, start.plusHours(1), start.plusHours(8), start))
    }

    @Test
    fun checkNowSkipsTheIntervalButNeverRunsDuringALiveSession() {
        assertTrue(UpdatePolicy.shouldCheckNow(sessionActive = false))
        assertFalse(UpdatePolicy.shouldCheckNow(sessionActive = true))
    }

    @Test
    fun appliesOnlyWhenIdleAndDownloaded() {
        assertFalse(UpdatePolicy.canApplyNow(sessionActive = true, updateDownloaded = true))
        assertFalse(UpdatePolicy.canApplyNow(false, false))
        assertTrue(UpdatePolicy.canApplyNow(false, true))
    }

    @Test
    fun channelsAreRestricted() {
        // xUnit [Theory] with 5 InlineData cases, run as a case loop (same inputs, same assertions).
        val cases = listOf(
            "beta" to "beta",
            "BETA" to "beta",
            "stable" to "stable",
            null to "stable",
            "nightly" to "stable",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, UpdatePolicy.normalizeChannel(input))
        }
    }
}

/** Port of the `InstallLayoutTests` class in the same Windows file. */
class InstallLayoutTests {
    @Test
    fun installedVelopackLayoutIsInstalledPortableIsNot() {
        val installed = "C:\\Users\\u\\AppData\\Local\\InterviewAssistant.Desktop\\current\\InterviewAssistant.exe"
        val update = "C:\\Users\\u\\AppData\\Local\\InterviewAssistant.Desktop\\Update.exe"
        assertTrue(InstallLayout.isInstalled(installed) { p -> p == update })
        assertFalse(InstallLayout.isInstalled(installed) { false }) // "current" folder alone is not enough
        assertFalse(InstallLayout.isInstalled("D:\\Downloads\\InterviewAssistant\\InterviewAssistant.exe") { true })
        assertFalse(InstallLayout.isInstalled(null) { true })
    }
}
