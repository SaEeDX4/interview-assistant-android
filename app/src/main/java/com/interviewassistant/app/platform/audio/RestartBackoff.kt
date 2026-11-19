package com.interviewassistant.app.platform.audio

import kotlin.math.min

/**
 * The capture restart backoff of `AudioCaptureService.ScheduleRestart`, verbatim:
 * `Math.Min(10_000, 1000 * (1 << Math.Min(_restartAttempts++, 3)))` — the delay is computed from
 * the attempt count *before* it is incremented (post-increment), so the sequence is
 * 1 s, 2 s, 4 s, 8 s, 8 s, … (the 10 s cap never binds: the shift is clamped at 3).
 */
class RestartBackoff {
    private var attempts: Int = 0

    /** Attempts since the last reset (the C#'s `_restartAttempts`, exposed for tests/logging). */
    val attemptCount: Int get() = attempts

    /** The delay before the next restart, advancing the attempt counter exactly as the C# does. */
    fun nextDelayMs(): Long {
        val delay = min(10_000, 1000 * (1 shl min(attempts, 3)))
        attempts++
        return delay.toLong()
    }

    /** The C#'s resets: `Start` zeroes the counter, as does a device becoming available again. */
    fun reset() {
        attempts = 0
    }
}
