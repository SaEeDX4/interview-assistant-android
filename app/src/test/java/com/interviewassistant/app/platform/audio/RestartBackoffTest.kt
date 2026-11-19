package com.interviewassistant.app.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P5: `AudioCaptureService.ScheduleRestart`'s backoff, verbatim — the delay uses the attempt
 * count before incrementing: 1 s, 2 s, 4 s, then 8 s flat (the shift clamps at 3; the nominal
 * 10 s cap never binds).
 */
class RestartBackoffTest {

    @Test
    fun delays_DoublingThenFlat() {
        val backoff = RestartBackoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L, 8000L), (1..6).map { backoff.nextDelayMs() })
        assertEquals(6, backoff.attemptCount)
    }

    @Test
    fun reset_StartsOver() {
        val backoff = RestartBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(0, backoff.attemptCount)
        assertEquals(1000L, backoff.nextDelayMs())
    }
}
