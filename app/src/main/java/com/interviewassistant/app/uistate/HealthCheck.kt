package com.interviewassistant.app.uistate

import java.math.BigDecimal
import java.math.RoundingMode

/** Mirror of `InterviewAssistant.App/UiState/HealthCheck.cs`. */

/** The four rows of the in-app health check (Help & about → Diagnostics → Run self-test). */
enum class HealthCheckId { AudioDevice, Service, Settings, Disk }

enum class HealthOutcome { Pass, Warn }

/** What the answer service looked like when the check ran. */
enum class ServiceReach { Reachable, Unreachable, DeveloperMode, NotConfigured }

/** Measured facts (gathered by the Help page; no WPF types here so the evaluation is unit-tested). */
data class HealthFacts(val playbackDevices: Int, val service: ServiceReach, val settingsReadable: Boolean, val diskWritable: Boolean)

/** One row: outcome plus the localization keys of its label and plain-language detail. */
data class HealthRow(val id: HealthCheckId, val outcome: HealthOutcome, val labelKey: String, val detailKey: String)

/**
 * A quick, friendly health check — not the CI self-test (--selftest). Every row is Pass or Warn (never a scary
 * "fail"): the app keeps working with defaults, typed questions or after reconnecting, and the detail says what to do.
 */
object HealthCheck {
    fun evaluate(f: HealthFacts): List<HealthRow> = listOf(
        if (f.playbackDevices > 0)
            HealthRow(HealthCheckId.AudioDevice, HealthOutcome.Pass, "help_diag_audio", "hc_audio_ok")
        else
            HealthRow(HealthCheckId.AudioDevice, HealthOutcome.Warn, "help_diag_audio", "hc_audio_warn"),
        when (f.service) {
            ServiceReach.Reachable -> HealthRow(HealthCheckId.Service, HealthOutcome.Pass, "hc_service", "hc_service_ok")
            ServiceReach.DeveloperMode -> HealthRow(HealthCheckId.Service, HealthOutcome.Pass, "hc_service", "hc_service_dev")
            ServiceReach.NotConfigured -> HealthRow(HealthCheckId.Service, HealthOutcome.Warn, "hc_service", "acct_not_configured_long")
            else -> HealthRow(HealthCheckId.Service, HealthOutcome.Warn, "hc_service", "hc_service_warn")
        },
        if (f.settingsReadable)
            HealthRow(HealthCheckId.Settings, HealthOutcome.Pass, "settings", "hc_settings_ok")
        else
            HealthRow(HealthCheckId.Settings, HealthOutcome.Warn, "settings", "hc_settings_warn"),
        if (f.diskWritable)
            HealthRow(HealthCheckId.Disk, HealthOutcome.Pass, "hc_disk", "hc_disk_ok")
        else
            HealthRow(HealthCheckId.Disk, HealthOutcome.Warn, "hc_disk", "hc_disk_warn"),
    )

    fun passed(rows: List<HealthRow>): Int = rows.count { it.outcome == HealthOutcome.Pass }

    /** Friendly "last answer time": seconds from the end of speech (or of a typed question) to the first bullet; null without a sample. */
    fun answerSeconds(speechEndToFirstBulletMs: Long, finalizedToFirstBulletMs: Long): Double? {
        val ms = if (speechEndToFirstBulletMs > 0) speechEndToFirstBulletMs else finalizedToFirstBulletMs
        // .NET Math.Round(x, 1) rounds midpoints to even; BigDecimal HALF_EVEN is its exact counterpart.
        return if (ms > 0) BigDecimal(ms / 1000.0).setScale(1, RoundingMode.HALF_EVEN).toDouble() else null
    }
}
