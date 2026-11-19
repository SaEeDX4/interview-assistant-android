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
 * P5: the frame pipeline (raw buffers → :core `PcmConverter`/`AudioFramer` → 100 ms frames with
 * the C#'s levels), fed by fake PCM sources in both source shapes the platform produces: the
 * microphone's 48 kHz PCM16 mono and playback capture's 48 kHz float/PCM shapes.
 */
class FramePipelineTest {

    /** PCM16 mono at [rate], a sine of [seconds] at amplitude [amp] (0..1). */
    private fun pcm16Mono(rate: Int, seconds: Double, amp: Double, freq: Double = 440.0): ByteArray {
        val n = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (amp * sin(2 * PI * freq * i / rate) * 32767).toInt()
            buffer.putShort(i * 2, v.toShort())
        }
        return buffer.array()
    }

    /** Float32 stereo at [rate], like a WASAPI mix format / playback capture float shape. */
    private fun floatStereo(rate: Int, seconds: Double, amp: Float, freq: Double = 440.0): ByteArray {
        val n = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (amp * sin(2 * PI * freq * i / rate)).toFloat()
            buffer.putFloat(i * 8, v)
            buffer.putFloat(i * 8 + 4, v)
        }
        return buffer.array()
    }

    @Test
    fun micShape_OneSecond_ProducesTenExactFrames() {
        val pipeline = FramePipeline(SourceFormat(48_000, 1, SampleEncoding.Pcm16))
        val frames = pipeline.push(pcm16Mono(48_000, 1.0, 0.5))
        assertEquals(10, frames.size)
        for (frame in frames) {
            assertEquals(FramePipeline.SILENCE_FRAME_BYTES, frame.pcm.size) // 4800 = 100 ms at 24 kHz
            assertTrue("rms ${frame.rmsDb}", frame.rmsDb in -12.0..-6.0) // 0.5-amplitude sine ≈ −9 dBFS RMS
            assertTrue(frame.hasSignal)
        }
    }

    @Test
    fun playbackShape_FloatStereo_ConvertsToSameFrames() {
        val pipeline = FramePipeline(SourceFormat(48_000, 2, SampleEncoding.Float32))
        val frames = pipeline.push(floatStereo(48_000, 1.0, 0.5f))
        assertEquals(10, frames.size)
        assertTrue(frames.all { it.pcm.size == 4800 && it.hasSignal })
    }

    @Test
    fun oddChunks_NoSamplesLostOrDuplicated() {
        val input = pcm16Mono(48_000, 1.0, 0.5)
        val pipeline = FramePipeline(SourceFormat(48_000, 1, SampleEncoding.Pcm16))
        val frames = mutableListOf<CaptureFrame>()
        var pos = 0
        while (pos < input.size) {
            val len = minOf(3333, input.size - pos)
            frames += pipeline.push(input.copyOfRange(pos, pos + len))
            pos += len
        }
        assertEquals(10, frames.size)
        // Byte-identical to the single-push result (the C#'s chunk-boundary continuity).
        val whole = FramePipeline(SourceFormat(48_000, 1, SampleEncoding.Pcm16)).push(input)
        assertEquals(whole.map { it.pcm.toList() }, frames.map { it.pcm.toList() })
    }

    @Test
    fun quietAndSilentFrames_DoNotCountAsSignal() {
        val pipeline = FramePipeline(SourceFormat(48_000, 1, SampleEncoding.Pcm16))
        val quiet = pipeline.push(pcm16Mono(48_000, 0.3, 0.001)) // ≈ −63 dBFS RMS, below the −50 rule
        assertTrue(quiet.isNotEmpty())
        assertTrue(quiet.all { !it.hasSignal })
        val silent = pipeline.push(ByteArray(48_000 * 2 / 10))
        assertTrue(silent.all { it.rmsDb == -100.0 && it.peakDb == -100.0 && !it.hasSignal })
    }

    @Test
    fun emptyInput_ProducesNothing() {
        val pipeline = FramePipeline(SourceFormat(48_000, 1, SampleEncoding.Pcm16))
        assertTrue(pipeline.push(ByteArray(0)).isEmpty())
    }

    @Test
    fun silenceFrame_IsOneZeroedFrame() {
        val frame = FramePipeline.silenceFrame()
        assertEquals(4800, frame.size)
        assertTrue(frame.all { it == 0.toByte() })
        // A fresh array per call: a consumer holding one frame can never see another's content.
        assertFalse(frame === FramePipeline.silenceFrame())
    }
}
