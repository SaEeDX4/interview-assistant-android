package com.interviewassistant.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/AudioTests.cs`. */
class AudioTests {

    private fun sineFloatStereo(rate: Int, freq: Double, seconds: Double, amp: Float = 0.5f): ByteArray {
        val n = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (amp * sin(2 * PI * freq * i / rate)).toFloat()
            buffer.putFloat(i * 8, v)
            buffer.putFloat(i * 8 + 4, v)
        }
        return buffer.array()
    }

    private fun toShorts(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 2) { i -> (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8) }

    @Test
    fun convertsToMono24kWithCorrectLengthAndAmplitude() {
        for (rate in listOf(48000, 44100, 96000, 16000)) {
            val conv = PcmConverter(SourceFormat(rate, 2, SampleEncoding.Float32))
            val input = sineFloatStereo(rate, 440.0, 1.0)
            val output = mutableListOf<Byte>()
            // Feed in odd-sized chunks (including partial frames) like WASAPI does.
            var pos = 0
            val chunk = 3333
            while (pos < input.size) {
                val len = min(chunk, input.size - pos)
                output.addAll(conv.convert(input.copyOfRange(pos, pos + len)).toList())
                pos += len
            }
            val samples = toShorts(output.toByteArray())
            assertTrue("rate $rate: ${samples.size} samples", samples.size in 23900..24000)
            val peak = samples.drop(100).maxOf { abs(it) } / 32768.0
            assertTrue("rate $rate: peak $peak", peak in 0.42..0.52)
        }
    }

    @Test
    fun chunkBoundariesAreContinuous() {
        val conv = PcmConverter(SourceFormat(48000, 2, SampleEncoding.Float32))
        val input = sineFloatStereo(48000, 200.0, 0.5)
        val whole = toShorts(PcmConverter(SourceFormat(48000, 2, SampleEncoding.Float32)).convert(input))
        val parts = mutableListOf<Byte>()
        var p = 0
        while (p < input.size) {
            val len = min(960 * 8 + 4, input.size - p)
            parts.addAll(conv.convert(input.copyOfRange(p, p + len)).toList())
            p += 960 * 8 + 4
        }
        val chunked = toShorts(parts.toByteArray())
        assertEquals(whole.size, chunked.size)
        for (i in whole.indices) {
            assertTrue("sample $i: ${chunked[i]} vs ${whole[i]}", chunked[i] - whole[i] in -2..2)
        }
    }

    @Test
    fun pcm16InputAndLevelMeter() {
        val conv = PcmConverter(SourceFormat(24000, 1, SampleEncoding.Pcm16))
        val silence = ByteArray(4800)
        assertEquals(-100.0, PcmConverter.levelDb(conv.convert(silence)), 0.0)
        val loud = ByteArray(4800)
        for (i in 0 until 2400) {
            val s = if (i % 2 == 0) 16000 else -16000
            loud[i * 2] = (s and 0xFF).toByte()
            loud[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        val level = PcmConverter.levelDb(conv.convert(loud))
        assertTrue("level $level", level in -7.0..-5.0)
    }

    @Test
    fun framerProduces100msFrames() {
        val f = AudioFramer()
        val frames = f.push(ByteArray(4800 * 2 + 100))
        assertEquals(2, frames.size)
        for (fr in frames) assertEquals(4800, fr.size)
        assertEquals(1, f.push(ByteArray(4700)).size)
    }
}
