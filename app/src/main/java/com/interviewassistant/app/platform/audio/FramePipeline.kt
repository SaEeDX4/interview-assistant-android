package com.interviewassistant.app.platform.audio

import com.interviewassistant.app.services.peakDb
import com.interviewassistant.core.audio.AudioFramer
import com.interviewassistant.core.audio.PcmConverter
import com.interviewassistant.core.audio.SourceFormat

/**
 * One converted 100 ms frame and its levels, as `AudioCaptureService.OnData` computes them:
 * the frame bytes, its RMS level (what `FrameReady` carries), its peak, and whether it counts
 * as signal (`rms > -50`, the C#'s `FramesWithSignal` rule).
 */
data class CaptureFrame(val pcm: ByteArray, val rmsDb: Double, val peakDb: Double, val hasSignal: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is CaptureFrame && rmsDb == other.rmsDb && peakDb == other.peakDb &&
            hasSignal == other.hasSignal && pcm.contentEquals(other.pcm)

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + rmsDb.hashCode()
        result = 31 * result + peakDb.hashCode()
        result = 31 * result + hasSignal.hashCode()
        return result
    }
}

/**
 * Raw capture buffers in, 24 kHz mono PCM16 100 ms frames out — the body of the C#'s `OnData`
 * over :core's mirrored [PcmConverter] and [AudioFramer], identical for every source (microphone
 * route or AudioPlaybackCapture), exactly as the C# runs every WASAPI buffer through the one
 * converter/framer pair. Not thread-safe, like the C#'s pair: the owning service calls it from
 * the capture thread (and constructs a fresh pipeline per capture start, as the C# does).
 */
class FramePipeline(source: SourceFormat) {
    private val converter = PcmConverter(source)
    private val framer = AudioFramer()

    /** Converts one raw buffer; returns the complete 100 ms frames it produced (possibly none). */
    fun push(raw: ByteArray): List<CaptureFrame> {
        if (raw.isEmpty()) return emptyList() // the C#'s `BytesRecorded == 0` early-out
        val pcm = converter.convert(raw)
        if (pcm.isEmpty()) return emptyList()
        return framer.push(pcm).map { frame ->
            val rms = PcmConverter.levelDb(frame)
            CaptureFrame(frame, rms, peakDb(frame), rms > SIGNAL_THRESHOLD_DB)
        }
    }

    companion object {
        /** The C#'s signal rule: a frame counts towards `FramesWithSignal` when its RMS exceeds −50 dBFS. */
        const val SIGNAL_THRESHOLD_DB: Double = -50.0

        /** Bytes in one 100 ms frame of 24 kHz PCM16 (the C#'s silence frame: `TargetRate * 2 / 10`). */
        const val SILENCE_FRAME_BYTES: Int = PcmConverter.TargetRate * 2 / 10

        /** A fresh silent frame (the array is never shared: consumers may hold frames). */
        fun silenceFrame(): ByteArray = ByteArray(SILENCE_FRAME_BYTES)
    }
}
