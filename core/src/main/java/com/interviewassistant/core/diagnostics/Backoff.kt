package com.interviewassistant.core.diagnostics

import java.time.Duration
import java.util.Random
import kotlin.math.min
import kotlin.math.pow

/**
 * Mirror of `InterviewAssistant.Core/Diagnostics/Backoff.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port. The C#'s `TimeSpan` is `java.time.Duration` here.
 */

/** Bounded exponential backoff with jitter: 1s, 2s, 4s, 8s, 16s, capped at 30s. */
class ExponentialBackoff(initial: Duration? = null, max: Duration? = null) {
    private val initialMs: Double = (initial ?: Duration.ofSeconds(1)).toMillis().toDouble()
    private val maxMs: Double = (max ?: Duration.ofSeconds(30)).toMillis().toDouble()
    private val random = Random()

    var attempt: Int = 0
        private set

    fun next(): Duration {
        val baseMs = min(maxMs, initialMs * 2.0.pow(min(attempt, 10).toDouble()))
        attempt++
        val jitter = 0.85 + random.nextDouble() * 0.3 // ±15% avoids synchronized retries
        return Duration.ofMillis(Math.round(min(maxMs, baseMs * jitter)))
    }

    fun reset() {
        attempt = 0
    }
}
