package com.interviewassistant.app.platform.audio

import com.interviewassistant.core.audio.SampleEncoding
import com.interviewassistant.core.audio.SourceFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5: `CaptureCore` against the C# `AudioCaptureService`'s behaviour — statuses, silence
 * injection, restart/backoff, device following — with fake sources, a fake scheduler and a
 * fake clock. The §7 P5 exit gate itself (a live session on a real device) is NOT covered
 * here and stays open; these tests pin everything CI can pin.
 */
class CaptureCoreTest {

    private class FakeScheduler : CaptureScheduler {
        val onceTasks = mutableListOf<Pair<Long, () -> Unit>>()
        var periodicTask: (() -> Unit)? = null
        var periodicPeriodMs: Long = 0
        var periodicCancelled: Boolean = false

        override fun scheduleOnce(delayMs: Long, task: () -> Unit): CaptureTask {
            onceTasks += delayMs to task
            return object : CaptureTask {
                override fun cancel() {}
            }
        }

        override fun schedulePeriodic(periodMs: Long, task: () -> Unit): CaptureTask {
            periodicPeriodMs = periodMs
            periodicTask = task
            return object : CaptureTask {
                override fun cancel() {
                    periodicCancelled = true
                }
            }
        }

        fun runNextOnce() {
            val (_, task) = onceTasks.removeAt(0)
            task()
        }
    }

    private class FakeSource(
        override val format: SourceFormat = SourceFormat(48_000, 1, SampleEncoding.Pcm16),
        override val deviceName: String = "Fake Device",
        var startFailure: SourceStartException? = null,
    ) : CaptureSource {
        var startCount: Int = 0
        var stopCount: Int = 0
        private var onData: ((ByteArray) -> Unit)? = null
        private var onStopped: ((Exception?) -> Unit)? = null

        override fun start(onData: (ByteArray) -> Unit, onStopped: (Exception?) -> Unit) {
            startFailure?.let { throw it }
            startCount++
            this.onData = onData
            this.onStopped = onStopped
        }

        override fun stop() {
            stopCount++
        }

        fun emit(bytes: ByteArray) {
            onData?.invoke(bytes)
        }

        fun die(error: Exception? = RuntimeException("device gone")) {
            onStopped?.invoke(error)
        }
    }

    private class Fixture {
        var now: Long = 0
        val scheduler = FakeScheduler()
        val sources = mutableListOf<FakeSource>()
        var factoryFailure: SourceStartException? = null
        var presentKinds: Set<CaptureRoutes.RouteKind> = setOf(CaptureRoutes.RouteKind.Builtin)
        val frames = mutableListOf<Pair<ByteArray, Double>>()
        val statuses = mutableListOf<String>()

        val core = CaptureCore(
            factory = object : CaptureSourceFactory {
                override fun create(resolved: CaptureRoutes.Resolved): CaptureSource {
                    factoryFailure?.let { throw it }
                    return FakeSource().also { sources += it }
                }
            },
            resolver = { id -> CaptureRoutes.resolve(id, presentKinds) },
            scheduler = scheduler,
            nowMs = { now },
        )

        init {
            core.frameReadyListeners += { frame, db -> frames += frame to db }
            core.statusChangedListeners += { s -> statuses += s }
        }

        /** 100 ms of loud 48 kHz PCM16 mono (one output frame's worth). */
        fun loud100ms(): ByteArray {
            val n = 4800
            val buffer = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                buffer.putShort(i * 2, (0.5 * sin(2 * PI * 440.0 * i / 48_000) * 32767).toInt().toShort())
            }
            return buffer.array()
        }
    }

    @Test
    fun start_Activates_AndFramesFlowWithLevels() {
        val f = Fixture()
        f.core.start(null)
        assertEquals("ACTIVE", f.core.status)
        assertTrue(f.core.isRunning)
        assertEquals(48_000, f.core.sampleRate)
        assertEquals(1, f.core.channels)
        assertEquals("Pcm16", f.core.encoding)
        assertEquals("Fake Device", f.core.currentDeviceName)
        assertEquals(100L, f.scheduler.periodicPeriodMs) // the C#'s 100 ms silence timer

        f.sources[0].emit(f.loud100ms())
        assertEquals(1, f.frames.size)
        assertEquals(4800, f.frames[0].first.size)
        assertTrue(f.frames[0].second > -50.0)
        assertEquals(1L, f.core.framesWithSignal)
        assertTrue(f.core.lastPeakDb > -10.0)
    }

    @Test
    fun stop_StopsSource_AndReportsStopped() {
        val f = Fixture()
        f.core.start(null)
        f.core.stop()
        assertEquals("STOPPED", f.core.status)
        assertFalse(f.core.isRunning)
        assertEquals(1, f.sources[0].stopCount)
        assertEquals(listOf("ACTIVE", "STOPPED"), f.statuses)
    }

    @Test
    fun startFailure_NoDevice_ReportsNoPlaybackDevice_AndRetriesAfterOneSecond() {
        val f = Fixture()
        f.factoryFailure = SourceStartException(noDevice = true)
        f.core.start(null)
        assertEquals("NO PLAYBACK DEVICE", f.core.status)
        assertEquals(1, f.scheduler.onceTasks.size)
        assertEquals(1000L, f.scheduler.onceTasks[0].first)

        f.factoryFailure = null
        f.scheduler.runNextOnce()
        assertEquals("ACTIVE", f.core.status)
        assertEquals(1, f.sources.size)
    }

    @Test
    fun startFailure_Other_ReportsDeviceError() {
        val f = Fixture()
        f.factoryFailure = SourceStartException(noDevice = false)
        f.core.start(null)
        assertEquals("DEVICE ERROR", f.core.status)
        assertTrue(f.core.isRunning) // still running: the restart is already scheduled, as in the C#
        assertEquals(1, f.scheduler.onceTasks.size)
    }

    @Test
    fun absentRoute_ReportsNoPlaybackDevice_WithoutCreatingASource() {
        val f = Fixture()
        f.core.start(CaptureRoutes.ID_BLUETOOTH) // not in presentKinds
        assertEquals("NO PLAYBACK DEVICE", f.core.status)
        assertEquals(0, f.sources.size)
    }

    @Test
    fun unexpectedStop_ReportsReconnecting_AndRestarts() {
        val f = Fixture()
        f.core.start(null)
        f.sources[0].die()
        assertEquals("RECONNECTING DEVICE", f.core.status)
        assertEquals(1, f.scheduler.onceTasks.size)
        assertEquals(1000L, f.scheduler.onceTasks[0].first)
        f.scheduler.runNextOnce()
        assertEquals("ACTIVE", f.core.status)
        assertEquals(2, f.sources.size)
    }

    @Test
    fun repeatedFailures_BackoffDoublesThenFlattens() {
        val f = Fixture()
        f.factoryFailure = SourceStartException(noDevice = false)
        f.core.start(null)
        val delays = mutableListOf<Long>()
        repeat(5) {
            delays += f.scheduler.onceTasks.last().first
            f.scheduler.runNextOnce() // fails again, schedules the next
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), delays)
    }

    @Test
    fun silenceInjection_KeepsTimelineWhenSourceIsQuiet() {
        val f = Fixture()
        f.core.start(null)
        val tick = { f.scheduler.periodicTask!!.invoke() }

        f.now = 199
        tick()
        assertEquals(0, f.frames.size) // not yet 200 ms of quiet

        f.now = 200
        tick()
        assertEquals(1, f.frames.size) // first silent frame
        assertEquals(4800, f.frames[0].first.size)
        assertTrue(f.frames[0].first.all { it == 0.toByte() })
        assertEquals(-100.0, f.frames[0].second, 0.0)
        assertEquals(0L, f.core.framesWithSignal) // silence never counts as signal

        f.now = 300
        tick()
        assertEquals(2, f.frames.size) // and again every ~100 ms while stalled

        // Real data resumes: the stall clock resets and injection pauses.
        f.sources[0].emit(f.loud100ms())
        assertEquals(3, f.frames.size)
        f.now = 450
        tick()
        assertEquals(3, f.frames.size)
    }

    @Test
    fun silenceInjection_NeverRunsWhileStopped() {
        val f = Fixture()
        f.core.start(null)
        f.core.stop()
        f.now = 10_000
        f.scheduler.periodicTask!!.invoke()
        assertEquals(0, f.frames.size)
    }

    @Test
    fun startWhileRunning_SwitchesSourceAndResetsBackoff() {
        val f = Fixture()
        f.core.start(null)
        f.core.start(CaptureRoutes.ID_BUILTIN)
        assertEquals(2, f.sources.size)
        assertEquals(1, f.sources[0].stopCount) // the old source is stopped first, as in the C#
        assertEquals(CaptureRoutes.ID_BUILTIN, f.core.requestedDeviceId)
        assertEquals(0, f.core.restartAttempts)
        assertEquals("ACTIVE", f.core.status)
    }

    @Test
    fun deviceBecameAvailable_RevivesCaptureWaitingOnIt() {
        val f = Fixture()
        f.core.start(CaptureRoutes.ID_BLUETOOTH) // absent: NO PLAYBACK DEVICE, restart pending
        assertEquals("NO PLAYBACK DEVICE", f.core.status)
        f.presentKinds = f.presentKinds + CaptureRoutes.RouteKind.Bluetooth
        f.core.onDeviceBecameAvailable(CaptureRoutes.ID_BLUETOOTH)
        assertEquals("ACTIVE", f.core.status)
        assertEquals(1, f.sources.size)
    }

    @Test
    fun defaultDeviceChanged_RestartsOnlyWhenFollowingDefault() {
        val f = Fixture()
        f.core.start(null)
        f.core.onDefaultDeviceChanged()
        assertEquals(2, f.sources.size) // restarted onto the new default

        val g = Fixture()
        g.core.start(CaptureRoutes.ID_BUILTIN)
        g.core.onDefaultDeviceChanged()
        assertEquals(1, g.sources.size) // a pinned route does not follow the default
    }

    @Test
    fun permissionBlocked_DeviceError_AndNoRestartScheduled() {
        val f = Fixture()
        f.core.noteStartBlockedByPermission()
        assertEquals("DEVICE ERROR", f.core.status)
        assertFalse(f.core.isRunning)
        assertEquals(0, f.scheduler.onceTasks.size)
        assertEquals(0, f.sources.size)
    }

    @Test
    fun dispose_Stops_AndCancelsTheSilenceTimer() {
        val f = Fixture()
        f.core.start(null)
        f.core.dispose()
        assertEquals("STOPPED", f.core.status)
        assertTrue(f.scheduler.periodicCancelled)
    }
}
