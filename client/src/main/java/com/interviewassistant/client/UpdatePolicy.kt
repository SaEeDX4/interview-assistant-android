package com.interviewassistant.client

import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Mirror of `InterviewAssistant.Client/UpdatePolicy.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/**
 * When the app may check for, download and apply updates. Rule: nothing update-related happens while a live
 * session is running or paused (no bandwidth, CPU or restart risk mid-interview); applying happens on exit or when the
 * user explicitly restarts while idle.
 */
object UpdatePolicy {
    val checkInterval: Duration = 6.hours
    val startupDelay: Duration = 45.seconds

    fun shouldCheck(sessionActive: Boolean, lastCheck: OffsetDateTime?, now: OffsetDateTime, appStarted: OffsetDateTime): Boolean =
        !sessionActive &&
            java.time.Duration.between(appStarted, now) >= java.time.Duration.ofSeconds(startupDelay.inWholeSeconds) &&
            (lastCheck == null || java.time.Duration.between(lastCheck, now) >= java.time.Duration.ofHours(checkInterval.inWholeHours))

    /** A user-initiated check ("Check for updates"): no interval, but never during a live session. */
    fun shouldCheckNow(sessionActive: Boolean): Boolean = !sessionActive

    fun canApplyNow(sessionActive: Boolean, updateDownloaded: Boolean): Boolean = updateDownloaded && !sessionActive

    /** "stable" or "beta"; anything else is treated as stable. */
    fun normalizeChannel(channel: String?): String = if (channel.equals("beta", ignoreCase = true)) "beta" else "stable"
}
