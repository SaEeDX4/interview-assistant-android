package com.interviewassistant.app.services

import com.interviewassistant.core.audio.SampleEncoding
import com.interviewassistant.core.audio.SourceFormat
import com.interviewassistant.core.languages.Loc
import kotlin.math.abs
import kotlin.math.log10

/**
 * Seam for `InterviewAssistant.App/Services/AudioCaptureService.cs` (223 lines): the interface mirrors the C#
 * class's public surface member-for-member, so [com.interviewassistant.app.viewmodels.MainViewModel] is a
 * complete port now. The platform implementation (AudioRecord / AudioPlaybackCapture, TRANSFER-MAP D1/D2)
 * lands in P5 — see P4-PORT-NOTES DC-91.
 */

/** Mirror of the C#'s `PlaybackDevice` record: an output device the interview audio can be captured from. */
data class PlaybackDevice(val id: String?, val name: String) {
    override fun toString(): String = name
}

/**
 * The fields of NAudio's `WaveFormat` that the C#'s `ToSourceFormat` reads, as a platform-neutral input:
 * [isFloat] already combines the C#'s two float tests (`Encoding == IeeeFloat`, or a
 * `WaveFormatExtensible` whose sub-format is `MEDIASUBTYPE_IEEE_FLOAT`); the P5 implementation computes it
 * from the Android `AudioFormat` it negotiates. The mapping itself is pure and ported below.
 */
data class WaveFormatInfo(
    val isFloat: Boolean,
    val bitsPerSample: Int,
    val sampleRate: Int,
    val channels: Int,
)

/** Mirror of the C#'s `AudioCaptureService.ToSourceFormat(WaveFormat)`. */
fun toSourceFormat(wf: WaveFormatInfo): SourceFormat {
    val enc = if (wf.isFloat) SampleEncoding.Float32 else when (wf.bitsPerSample) {
        16 -> SampleEncoding.Pcm16
        24 -> SampleEncoding.Pcm24
        32 -> SampleEncoding.Pcm32
        else -> throw UnsupportedOperationException("Unsupported bit depth ${wf.bitsPerSample}")
    }
    return SourceFormat(wf.sampleRate, wf.channels, enc)
}

/** Mirror of the C#'s `AudioCaptureService.PeakDb` helper (peak level of one PCM16 frame, dBFS). */
fun peakDb(pcm16: ByteArray): Double {
    var peak = 0
    var i = 0
    while (i + 1 < pcm16.size) {
        val sample = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
        peak = maxOf(peak, abs(sample.toShort().toInt()))
        i += 2
    }
    return if (peak == 0) -100.0 else 20 * log10(peak / 32768.0)
}

/**
 * Loopback capture of a playback device — hears exactly what the meeting app plays to the headset.
 * Converts to 24 kHz mono PCM16 in 100 ms frames (the conversion itself is :core's `PcmConverter`).
 */
interface IAudioCaptureService {
    /** The C#'s `FrameReady` event: a PCM16 frame (100 ms) and its RMS level in dBFS. */
    val frameReadyListeners: MutableList<(ByteArray, Double) -> Unit>

    /** The C#'s `StatusChanged` event: the machine status code (see [status]). */
    val statusChangedListeners: MutableList<(String) -> Unit>

    val currentDeviceName: String

    /** Machine status code (ACTIVE, STOPPED, DEVICE ERROR, NO PLAYBACK DEVICE, RECONNECTING DEVICE). */
    val status: String

    /** Status in the user's language (the C# computes it from [status] through `Loc`). */
    val statusLabel: String

    val sampleRate: Int
    val channels: Int
    val encoding: String

    /** Peak / RMS of the most recent 100 ms frame (dBFS); −100 until the first frame. */
    val lastPeakDb: Double
    val lastRmsDb: Double

    val framesWithSignal: Long
    val isRunning: Boolean

    /**
     * The devices the user can pick; the first entry is the system default. The C# exposes this as a static
     * (`AudioCaptureService.ListDevices()`); on the seam it is an instance member, because enumerating
     * Android audio devices needs the platform instance (P4-PORT-NOTES DC-92).
     */
    fun listDevices(): List<PlaybackDevice>

    /** Starts capture of [deviceId] (null = follow the system default playback device). */
    fun start(deviceId: String?)

    fun stop()

    /** The C#'s `IDisposable.Dispose`. */
    fun dispose()
}

/** The status label exactly as the C# computes it — shared by the P5 implementation and test doubles. */
fun captureStatusLabel(status: String): String =
    Loc.t("audio_" + status.lowercase().replace(' ', '_'))
