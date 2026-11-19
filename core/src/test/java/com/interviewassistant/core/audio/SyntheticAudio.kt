package com.interviewassistant.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Mirror of `InterviewAssistant.Tests/SyntheticAudio.cs` — deterministic 24 kHz mono PCM16 test signals.
 * The C# uses `System.Random(seed)`, whose seeded algorithm is the legacy subtractive generator; [DotNetRandom]
 * reproduces it exactly so the synthetic signals are bit-identical to the Windows ones.
 */
class DotNetRandom(seed: Int) {
    private val seedArray = IntArray(56)
    private var inext: Int = 0
    private var inextp: Int = 21

    init {
        val subtraction = if (seed == Int.MIN_VALUE) Int.MAX_VALUE else abs(seed)
        var mj = MSEED - subtraction
        seedArray[55] = mj
        var mk = 1
        for (i in 1..54) {
            val ii = (21 * i) % 55
            seedArray[ii] = mk
            mk = mj - mk
            if (mk < 0) mk += MBIG
            mj = seedArray[ii]
        }
        repeat(4) {
            for (i in 1..55) {
                seedArray[i] -= seedArray[1 + (i + 30) % 55]
                if (seedArray[i] < 0) seedArray[i] += MBIG
            }
        }
    }

    private fun internalSample(): Int {
        var locInext = inext + 1
        if (locInext >= 56) locInext = 1
        var locInextp = inextp + 1
        if (locInextp >= 56) locInextp = 1
        var retVal = seedArray[locInext] - seedArray[locInextp]
        if (retVal == MBIG) retVal--
        if (retVal < 0) retVal += MBIG
        seedArray[locInext] = retVal
        inext = locInext
        inextp = locInextp
        return retVal
    }

    fun nextDouble(): Double = internalSample() * (1.0 / MBIG)

    companion object {
        private const val MBIG = Int.MAX_VALUE
        private const val MSEED = 161803398
    }
}

object SyntheticAudio {
    const val Rate = 24000
    const val FrameMs = 100

    fun frames(signal: DoubleArray): List<ByteArray> {
        val per = Rate * FrameMs / 1000
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < signal.size) {
            val frame = ByteArray(per * 2)
            var j = 0
            while (j < per && i + j < signal.size) {
                val s = (signal[i + j] * 32767).coerceIn(-32768.0, 32767.0).toInt()
                frame[j * 2] = (s and 0xFF).toByte()
                frame[j * 2 + 1] = (s shr 8 and 0xFF).toByte()
                j++
            }
            out.add(frame)
            i += per
        }
        return out
    }

    fun silence(ms: Int): DoubleArray = DoubleArray(Rate * ms / 1000)

    /** Speech-like signal: syllables of voiced vowels (pitch 95–240 Hz drifting) with short unvoiced bursts between. */
    fun speech(ms: Int, basePitch: Double = 130.0, level: Double = 0.25, seed: Int = 1): DoubleArray {
        val rnd = DotNetRandom(seed)
        val n = Rate * ms / 1000
        val y = DoubleArray(n)
        var phase = 0.0
        // Formant resonators (two-pole) for a few vowels, cycled per syllable.
        val vowels = arrayOf(
            Triple(700.0, 1220.0, 2600.0), Triple(300.0, 2300.0, 3000.0), Triple(500.0, 900.0, 2500.0),
            Triple(400.0, 1900.0, 2550.0), Triple(350.0, 800.0, 2400.0),
        )
        val syl = Rate * 220 / 1000 // ~4.5 syllables / s
        val s1 = DoubleArray(3)
        val s2 = DoubleArray(3)
        for (i in 0 until n) {
            val k = i / syl
            val inSyl = (i % syl) / syl.toDouble()
            val (f1, f2, f3) = vowels[k % vowels.size]
            val pitch = basePitch * (1 + 0.18 * sin(2 * PI * i / Rate * 0.7 + seed) + 0.06 * sin(2 * PI * inSyl))
            phase += pitch / Rate
            val consonant = inSyl < 0.18
            val src: Double
            if (consonant) {
                src = (rnd.nextDouble() * 2 - 1) * 0.3 // unvoiced burst
            } else if (phase >= 1) {
                phase -= 1
                src = 1.0
            } else {
                src = 0.0
            }
            val env = if (consonant) 0.5 else sin(PI * min(1.0, (inSyl - 0.18) / 0.82))
            var outv = 0.0
            var fi = 0
            for (f in doubleArrayOf(f1, f2, f3)) {
                val r = exp(-PI * 90 / Rate)
                val th = 2 * PI * f / Rate
                val v = src + 2 * r * cos(th) * s1[fi] - r * r * s2[fi]
                s2[fi] = s1[fi]
                s1[fi] = v
                outv += v * (if (fi == 0) 1.0 else if (fi == 1) 0.6 else 0.3)
                fi++
            }
            y[i] = outv * env
        }
        return normalize(y, level)
    }

    /** Sine beep with a short attack and exponential decay (Windows "Default Beep"/"Ding" style). */
    fun beep(ms: Int, hz: Double = 880.0, level: Double = 0.4, secondHz: Double = 0.0): DoubleArray {
        val n = Rate * ms / 1000
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / Rate
            val env = min(1.0, t / 0.01) * exp(-3 * t / (ms / 1000.0))
            var v = sin(2 * PI * hz * t)
            if (secondHz > 0 && t > ms / 2000.0) v = sin(2 * PI * secondHz * t) // two-note chime
            y[i] = v * env * level
        }
        return y
    }

    /** Camera-shutter / click style sound: two short broadband noise bursts. */
    fun shutter(seed: Int = 3): DoubleArray {
        val rnd = DotNetRandom(seed)
        val y = DoubleArray(Rate * 350 / 1000)
        for (i in y.indices) {
            val t = i.toDouble() / Rate
            val env = exp(-t * 60) + (if (t > 0.12) exp(-(t - 0.12) * 50) else 0.0)
            y[i] = (rnd.nextDouble() * 2 - 1) * env * 0.5
        }
        return y
    }

    /** Sustained major chord (music fragment) — harmonic, can look voiced. */
    fun chord(ms: Int, level: Double = 0.3): DoubleArray {
        val n = Rate * ms / 1000
        val y = DoubleArray(n)
        val notes = doubleArrayOf(261.6, 329.6, 392.0, 523.3)
        for (i in 0 until n) {
            val t = i.toDouble() / Rate
            var v = 0.0
            for (f in notes) {
                for (h in 1..4) v += sin(2 * PI * f * h * t) / (h * h)
            }
            y[i] = v
        }
        return normalize(y, level)
    }

    fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private fun normalize(y: DoubleArray, level: Double): DoubleArray {
        val peak = y.maxOfOrNull { abs(it) } ?: 0.0
        if (peak <= 0) return y
        for (i in y.indices) y[i] = y[i] / peak * level
        return y
    }
}
