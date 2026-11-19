package com.interviewassistant.app.platform.audio

import com.interviewassistant.app.services.captureStatusLabel
import com.interviewassistant.core.audio.SourceFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One running capture source, created by a [CaptureSourceFactory] for one resolved route.
 * Implementations own their platform thread and must guarantee no [onData] calls after [stop]
 * returns (the C# unsubscribes `DataAvailable` in `StopCaptureLocked` to the same effect).
 */
interface CaptureSource {
    /** The negotiated source format (the C# reads it off the capture's `WaveFormat`). */
    val format: SourceFormat

    /** The actual device/source name shown to the user (the C#'s `device.FriendlyName`). */
    val deviceName: String

    /**
     * Begins delivering raw buffers to [onData]. Throws [SourceStartException] when the source
     * cannot start (the C#'s `StartRecording` failure path). [onStopped] fires if the source
     * later ends on its own (device loss); it must not fire after [stop].
     */
    fun start(onData: (ByteArray) -> Unit, onStopped: (Exception?) -> Unit)

    fun stop()
}

/** A source start failure; [noDevice] mirrors the C#'s HRESULT 0x80070490 ("element not found") test. */
class SourceStartException(val noDevice: Boolean, cause: Throwable? = null) :
    Exception(cause?.message, cause)

/** Creates the platform source for a resolved route; may throw [SourceStartException]. */
interface CaptureSourceFactory {
    fun create(resolved: CaptureRoutes.Resolved): CaptureSource
}

/** A cancellable scheduled task (the C#'s `Task.Delay` continuations and `Timer`). */
interface CaptureTask {
    fun cancel()
}

/** Scheduling seam: the Android implementation runs on a HandlerThread; tests drive a fake. */
interface CaptureScheduler {
    fun scheduleOnce(delayMs: Long, task: () -> Unit): CaptureTask
    fun schedulePeriodic(periodMs: Long, task: () -> Unit): CaptureTask
}

/**
 * The state machine of `AudioCaptureService.cs`, member for member, over platform seams: start /
 * stop, the status codes, silence injection (100 ms timer; a silent frame when no data has
 * arrived for ≥ 200 ms), automatic restart with [RestartBackoff], default-device following and
 * device-return recovery (the C#'s `IMMNotificationClient` halves). Platform facts (routes,
 * sources, time, scheduling, logging) are injected; everything behavioural lives here and is
 * JVM-tested (P5-PORT-NOTES DC-171).
 *
 * Threading mirrors the C#: one lock guards the state; frame delivery happens outside it.
 */
class CaptureCore(
    private val factory: CaptureSourceFactory,
    private val resolver: (String?) -> CaptureRoutes.Resolved,
    private val scheduler: CaptureScheduler,
    private val nowMs: () -> Long,
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
    private val logError: (String, Exception?) -> Unit = { _, _ -> },
) {
    /** The C#'s `FrameReady` event: a PCM16 frame (100 ms) and its RMS level in dBFS. */
    val frameReadyListeners: MutableList<(ByteArray, Double) -> Unit> = mutableListOf()

    /** The C#'s `StatusChanged` event: the machine status code (see [status]). */
    val statusChangedListeners: MutableList<(String) -> Unit> = mutableListOf()

    private val lock = ReentrantLock()
    private var source: CaptureSource? = null
    private var pipeline: FramePipeline? = null
    private var silenceTask: CaptureTask? = null
    private val lastDataMs = AtomicLong(0)
    private val backoff = RestartBackoff()
    private var session: Int = 0 // increments per capture start; stale sources' data is dropped

    var requestedDeviceId: String? = null
        private set
    private var running: Boolean = false

    var currentDeviceName: String = ""
        private set

    /** Machine status code (ACTIVE, STOPPED, DEVICE ERROR, NO PLAYBACK DEVICE, RECONNECTING DEVICE). */
    var status: String = "STOPPED"
        private set

    /** Status in the user's language, computed exactly as the C# computes it. */
    val statusLabel: String get() = captureStatusLabel(status)

    var sampleRate: Int = 0
        private set
    var channels: Int = 0
        private set
    var encoding: String = ""
        private set

    var lastPeakDb: Double = -100.0
        private set
    var lastRmsDb: Double = -100.0
        private set

    var framesWithSignal: Long = 0
        private set

    val isRunning: Boolean get() = running

    /** The backoff's attempt count (the C#'s `_restartAttempts`), for diagnostics/tests. */
    val restartAttempts: Int get() = backoff.attemptCount

    fun start(deviceId: String?) {
        lock.withLock {
            requestedDeviceId = deviceId
            running = true
            backoff.reset()
            startCaptureLocked()
            if (silenceTask == null) {
                silenceTask = scheduler.schedulePeriodic(SILENCE_TIMER_MS) { injectSilenceIfStalled() }
            }
        }
    }

    fun stop() {
        lock.withLock {
            running = false
            stopCaptureLocked()
            setStatus("STOPPED")
        }
    }

    /** The C#'s `Dispose`: stop, then the silence timer goes away with the service. */
    fun dispose() {
        stop()
        lock.withLock {
            silenceTask?.cancel()
            silenceTask = null
        }
    }

    /**
     * The platform layer's permission verdict (Android-only: the C# needs no microphone
     * permission for loopback). A denied start surfaces through the same status path as a device
     * error — the Live UI and the pre-interview check already render it — and schedules no
     * restart: retrying is the user's next Start press, after they grant the permission
     * (P5-PORT-NOTES DC-172).
     */
    fun noteStartBlockedByPermission() {
        lock.withLock {
            running = false
            setStatus("DEVICE ERROR")
        }
    }

    /** The C#'s `OnDefaultDeviceChanged`: follow the default only while following it (id `null`). */
    fun onDefaultDeviceChanged() {
        if (!running || requestedDeviceId != null) return
        lock.withLock {
            if (running) startCaptureLocked()
        }
    }

    /** The C#'s `OnDeviceStateChanged(Active)`: a returning device restarts capture, backoff reset. */
    fun onDeviceBecameAvailable(deviceId: String?) {
        if (!running) return
        if (deviceId != requestedDeviceId && source != null) return
        lock.withLock {
            if (running) {
                backoff.reset()
                startCaptureLocked()
            }
        }
    }

    private fun startCaptureLocked() {
        stopCaptureLocked()
        try {
            val resolved = resolver(requestedDeviceId)
            if (resolved is CaptureRoutes.Resolved.Absent) {
                throw SourceStartException(noDevice = true)
            }
            val created = factory.create(resolved)
            source = created
            currentDeviceName = created.deviceName
            val format = created.format
            pipeline = FramePipeline(format)
            sampleRate = format.sampleRate
            channels = format.channels
            encoding = format.encoding.toString()
            val mySession = ++session
            lastDataMs.set(nowMs())
            created.start(
                onData = { raw -> onData(mySession, raw) },
                onStopped = { error -> onSourceStopped(error) },
            )
            logInfo("Capture started: $currentDeviceName ($format)")
            setStatus("ACTIVE")
        } catch (ex: Exception) {
            val noDevice = ex is SourceStartException && ex.noDevice
            if (backoff.attemptCount == 0 || backoff.attemptCount % 10 == 0) {
                logError("Could not start capture (attempt ${backoff.attemptCount + 1})", ex)
            }
            setStatus(if (noDevice) "NO PLAYBACK DEVICE" else "DEVICE ERROR")
            scheduleRestart()
        }
    }

    private fun stopCaptureLocked() {
        val old = source ?: return
        try {
            old.stop()
        } catch (ignored: Exception) {
            // Device already gone (the C# swallows the same failures in StopRecording).
        }
        source = null
        pipeline = null
    }

    private fun onData(dataSession: Int, raw: ByteArray) {
        if (raw.isEmpty()) return
        lastDataMs.set(nowMs())
        val pipe = lock.withLock { if (dataSession == session) pipeline else null } ?: return
        for (frame in pipe.push(raw)) {
            lastRmsDb = frame.rmsDb
            lastPeakDb = frame.peakDb
            if (frame.hasSignal) framesWithSignal++
            fireFrame(frame.pcm, frame.rmsDb)
        }
    }

    private fun onSourceStopped(error: Exception?) {
        if (error != null) logWarn("Capture stopped: ${error.message}")
        if (running) {
            setStatus("RECONNECTING DEVICE")
            scheduleRestart()
        }
    }

    private fun injectSilenceIfStalled() {
        if (!running || source == null) return
        // WASAPI loopback delivers no packets while nothing is playing; Android's sources can do
        // the same (a blocked playback-capture app is silent-by-absence). Keep the stream's
        // timeline continuous so server-side VAD can detect end-of-speech.
        if (nowMs() - lastDataMs.get() < SILENCE_AFTER_MS) return
        lastDataMs.set(nowMs() - SILENCE_TIMER_MS)
        fireFrame(FramePipeline.silenceFrame(), -100.0)
    }

    private fun scheduleRestart() {
        if (!running) return
        val delay = backoff.nextDelayMs()
        scheduler.scheduleOnce(delay) {
            lock.withLock {
                if (running) startCaptureLocked()
            }
        }
    }

    private fun setStatus(value: String) {
        status = value
        val snapshot = synchronized(statusChangedListeners) { statusChangedListeners.toList() }
        for (listener in snapshot) listener(value)
    }

    private fun fireFrame(frame: ByteArray, rmsDb: Double) {
        val snapshot = synchronized(frameReadyListeners) { frameReadyListeners.toList() }
        for (listener in snapshot) listener(frame, rmsDb)
    }

    private companion object {
        const val SILENCE_TIMER_MS: Long = 100
        const val SILENCE_AFTER_MS: Long = 200
    }
}
