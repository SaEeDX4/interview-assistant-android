package com.interviewassistant.core.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mirror of `InterviewAssistant.Core/Audio/PcmConverter.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

enum class SampleEncoding { Pcm16, Pcm24, Pcm32, Float32 }

data class SourceFormat(val sampleRate: Int, val channels: Int, val encoding: SampleEncoding) {
    val bytesPerSample: Int
        get() = when (encoding) {
            SampleEncoding.Pcm16 -> 2
            SampleEncoding.Pcm24 -> 3
            else -> 4
        }
    val blockAlign: Int get() = bytesPerSample * channels
}

/**
 * Streaming converter from any WASAPI mix format (typically 48 kHz stereo float32) to the 24 kHz mono
 * little-endian PCM16 required by the realtime transcription API. Down-mixes channels, resamples with
 * box-filter averaging (cheap anti-aliasing for speech) and keeps fractional state across buffers so
 * there are no clicks at chunk boundaries. Not thread-safe: use from the capture thread only.
 */
class PcmConverter(source: SourceFormat) {
    private val src: SourceFormat = source
    private val ratio: Double = source.sampleRate.toDouble() / TargetRate // input samples per output sample
    private var pending = FloatArray(8192)
    private var pendingCount: Int = 0
    private var pos: Double = 0.0 // fractional read position into pending
    private var partialFrame = ByteArray(0)

    init {
        require(source.sampleRate > 0 && source.channels > 0) { "Invalid source format" }
    }

    /** Converts a raw capture buffer; returns PCM16 mono 24 kHz bytes (may be empty). */
    fun convert(input: ByteArray): ByteArray {
        // Handle buffers that split a sample frame.
        var data = input
        if (partialFrame.isNotEmpty()) {
            val joined = ByteArray(partialFrame.size + input.size)
            partialFrame.copyInto(joined, 0)
            input.copyInto(joined, partialFrame.size)
            data = joined
        }
        val frames = data.size / src.blockAlign
        val used = frames * src.blockAlign
        partialFrame = if (data.size > used) data.copyOfRange(used, data.size) else ByteArray(0)

        ensureCapacity(pendingCount + frames)
        for (f in 0 until frames) {
            var sum = 0f
            val baseOffset = f * src.blockAlign
            for (c in 0 until src.channels) sum += readSample(data, baseOffset + c * src.bytesPerSample)
            pending[pendingCount++] = sum / src.channels
        }

        val outCount = max(0.0, floor((pendingCount - pos) / ratio)).toInt()
        if (outCount <= 0) return ByteArray(0)
        val output = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val start = pos
            val end = pos + ratio
            val v: Float
            if (ratio >= 1.0) {
                val s = start.toInt()
                val e = min(ceil(end).toInt(), pendingCount)
                var acc = 0f
                var n = 0
                for (k in s until e) {
                    acc += pending[k]
                    n++
                }
                v = if (n > 0) acc / n else 0f
            } else {
                val s = start.toInt()
                val frac = start - s
                val a = pending[s]
                val b = if (s + 1 < pendingCount) pending[s + 1] else a
                v = (a + (b - a) * frac).toFloat()
            }
            pos = end
            val clamped = v.coerceIn(-1f, 1f)
            val sample = Math.rint((clamped * 32767f).toDouble()).toInt()
            output[i * 2] = (sample and 0xFF).toByte()
            output[i * 2 + 1] = (sample shr 8 and 0xFF).toByte()
        }
        // Discard consumed input, keep the fractional remainder.
        val consumed = min(floor(pos).toInt(), pendingCount)
        if (consumed > 0) {
            System.arraycopy(pending, consumed, pending, 0, pendingCount - consumed)
            pendingCount -= consumed
            pos -= consumed
        }
        return output
    }

    private fun readSample(d: ByteArray, o: Int): Float = when (src.encoding) {
        SampleEncoding.Float32 -> Float.fromBits(readInt32Le(d, o))
        SampleEncoding.Pcm16 -> readInt16Le(d, o) / 32768f
        SampleEncoding.Pcm24 -> ((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() shl 16)) / 8388608f
        SampleEncoding.Pcm32 -> readInt32Le(d, o) / 2147483648f
    }

    private fun readInt16Le(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or (d[o + 1].toInt() shl 8)

    private fun readInt32Le(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or ((d[o + 2].toInt() and 0xFF) shl 16) or (d[o + 3].toInt() shl 24)

    private fun ensureCapacity(n: Int) {
        if (n <= pending.size) return
        pending = pending.copyOf(max(n, pending.size * 2))
    }

    companion object {
        const val TargetRate = 24000

        /** RMS level of a PCM16 buffer in dBFS (-100 .. 0). */
        fun levelDb(pcm16: ByteArray): Double {
            val n = pcm16.size / 2
            if (n == 0) return -100.0
            var acc = 0.0
            for (i in 0 until n) {
                val s = ((pcm16[i * 2].toInt() and 0xFF) or (pcm16[i * 2 + 1].toInt() shl 8)) / 32768.0
                acc += s * s
            }
            val rms = sqrt(acc / n)
            return if (rms <= 1e-5) -100.0 else max(-100.0, 20 * log10(rms))
        }
    }
}

/** Groups converted audio into fixed ~100 ms frames for the WebSocket (fewer, larger messages). */
class AudioFramer(frameMs: Int = 100) {
    private val frameBytes: Int = PcmConverter.TargetRate * 2 * frameMs / 1000
    private var buffer = ByteArray(0)

    fun push(pcm: ByteArray): List<ByteArray> {
        buffer = buffer + pcm
        val frames = mutableListOf<ByteArray>()
        while (buffer.size >= frameBytes) {
            frames.add(buffer.copyOfRange(0, frameBytes))
            buffer = buffer.copyOfRange(frameBytes, buffer.size)
        }
        return frames
    }
}
