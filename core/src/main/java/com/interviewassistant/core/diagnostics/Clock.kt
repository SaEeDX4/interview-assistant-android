package com.interviewassistant.core.diagnostics

/**
 * Mirror of `InterviewAssistant.Core/Diagnostics/Clock.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** Monotonic millisecond clock; injectable for deterministic tests. */
interface IClock {
    val nowMs: Long
}

class SystemClock : IClock {
    private val startNanos: Long = System.nanoTime()
    override val nowMs: Long get() = (System.nanoTime() - startNanos) / 1_000_000
}

class ManualClock : IClock {
    override var nowMs: Long = 0
    fun advance(ms: Long) {
        nowMs += ms
    }
}
