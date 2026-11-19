package com.interviewassistant.core.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mirror of `InterviewAssistant.Core/Audio/AcousticAnalyzer.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/**
 * Acoustic summary of one captured frame (milliseconds of each kind of sound) plus pitch-movement counts over pairs of
 * consecutive periodic hops: [steadyPairs] held their pitch within 0.6 % (a note or tone), [jumpPairs]
 * moved by more than 2.5 % (no continuous voice pitch, e.g. a knock's wobbling resonance).
 *
 * [trackedHops], [runHops] and [flatRunHops] come from a [PitchRunTracker]
 * (pitch runs cross frame boundaries): periodic hops seen, hops in continuous pitch runs of at least five windows, and
 * those of them in runs whose pitch never left a 2 % band — a held note, buzz or chord, not a voice. [stretchHops]:
 * hops in continuous periodic stretches of at least 160 ms (any pitch movement).
 */
data class FrameAcoustics(
    val durationMs: Int,
    val activeMs: Int,
    val voicedMs: Int,
    val tonalMs: Int,
    val pitchPairs: Int = 0,
    val steadyPairs: Int = 0,
    val jumpPairs: Int = 0,
    val trackedHops: Int = 0,
    val runHops: Int = 0,
    val flatRunHops: Int = 0,
    val stretchHops: Int = 0,
) {
    operator fun plus(b: FrameAcoustics): FrameAcoustics =
        FrameAcoustics(
            durationMs + b.durationMs, activeMs + b.activeMs, voicedMs + b.voicedMs, tonalMs + b.tonalMs,
            pitchPairs + b.pitchPairs, steadyPairs + b.steadyPairs, jumpPairs + b.jumpPairs,
            trackedHops + b.trackedHops, runHops + b.runHops, flatRunHops + b.flatRunHops, stretchHops + b.stretchHops,
        )
}

/**
 * Follows the pitch of consecutive analysis windows across frames and splits it into runs at every break (a hop with no
 * voice-range pitch, or a jump of more than 2.5 % per hop). A closed run of at least [MinRunWindows] windows is
 * "flat" when its whole pitch span stays under [FlatSpan] (2 %): a held note, a buzz with vibrato, a chord.
 * A voice — even a calm, monotone "Tell me more." — glides by several percent within each voiced stretch (intonation,
 * declination, micro-prosody), so its runs are not flat although consecutive hops differ by under 0.6 %.
 * It also measures continuous periodic STRETCHES (consecutive windows with a voice-range pitch, whatever the pitch does):
 * hops in stretches of at least [MinStretchWindows] windows (160 ms). A spoken word of two or more
 * syllables has one — vowels and voiced consonants run together — even when a low, creaky voice's pitch estimate jumps
 * between harmonics and so forms no run; a door knock's ringing decays within ~120 ms per strike and has none.
 * One tracker per audio stream (it is stateful); [reset] when the stream restarts. Thread-safe: frames are
 * tracked on the capture thread while [openRun] is read by the transcriber thread (partial trust boundary).
 */
class PitchRunTracker {
    data class TrackResult(val tracked: Int, val runs: Int, val flat: Int, val stretch: Int)
    data class OpenRunResult(val runs: Int, val flat: Int, val stretch: Int)

    private val sync = Any()
    private var last: Double = 0.0
    private var minP: Double = 0.0
    private var maxP: Double = 0.0
    private var len: Int = 0
    private var stretch: Int = 0
    private var tracked: Int = 0
    private var runHops: Int = 0
    private var flatHops: Int = 0
    private var stretchHops: Int = 0

    fun reset() {
        synchronized(sync) {
            len = 0
            stretch = 0
            last = 0.0
            tracked = 0
            runHops = 0
            flatHops = 0
            stretchHops = 0
        }
    }

    /**
     * Tracks one frame's distinct analysis windows (periods, 0 = none; window start offsets; hop size in samples) and
     * returns the counts credited since the previous frame (runs and stretches are credited when they close).
     */
    internal fun track(period: DoubleArray, starts: IntArray, hops: Int, hop: Int): TrackResult {
        synchronized(sync) {
            // Distinct windows only (the last hop may re-analyse the previous window); across frames one window is unseen.
            for (h in 0 until hops) {
                if (h > 0 && starts[h] == starts[h - 1]) continue
                add(period[h], if (h == 0) 2 else (starts[h] - starts[h - 1]) / hop)
            }
            val r = TrackResult(tracked, runHops, flatHops, stretchHops)
            tracked = 0
            runHops = 0
            flatHops = 0
            stretchHops = 0
            return r
        }
    }

    /** One analysis window: its pitch period (0 = none) and its distance in hops from the previous one. */
    private fun add(period: Double, hopsSincePrevious: Int) {
        if (period <= 0) {
            close()
            closeStretch()
            return
        }
        tracked++
        stretch++
        if (len > 0 && abs(ln(period / last)) <= JumpPerHop * max(1, hopsSincePrevious)) {
            len++
            last = period
            minP = min(minP, period)
            maxP = max(maxP, period)
            return
        }
        close()
        len = 1
        last = period
        minP = period
        maxP = period
    }

    private fun close() {
        if (len >= MinRunWindows) {
            runHops += len
            if (ln(maxP / minP) < FlatSpan) flatHops += len
        }
        len = 0
    }

    private fun closeStretch() {
        if (stretch >= MinStretchWindows) stretchHops += stretch
        stretch = 0
    }

    /**
     * The run and the stretch still in progress as if they closed now (run hops, flat hops, stretch hops): decisions
     * mid-sound must see a long held note before it ends, not only the runs that already closed.
     */
    fun openRun(): OpenRunResult {
        synchronized(sync) {
            val runsFlat: Pair<Int, Int> = if (len >= MinRunWindows) {
                len to (if (ln(maxP / minP) < FlatSpan) len else 0)
            } else {
                0 to 0
            }
            return OpenRunResult(runsFlat.first, runsFlat.second, if (stretch >= MinStretchWindows) stretch else 0)
        }
    }

    companion object {
        const val MinRunWindows = 5
        const val MinStretchWindows = 8
        const val FlatSpan = 0.02
        private const val JumpPerHop = 0.025
    }
}

/**
 * Cheap, dependency-free speech evidence from 24 kHz mono PCM16 (the exact stream sent for transcription).
 * Each 20 ms hop is analysed over a 40 ms window at 12 kHz (the last hop of a frame uses the frame's last full window):
 *  - ACTIVE: level above the floor (something is playing);
 *  - VOICED: a periodic signal with a pitch between ~80 and ~400 Hz (normalized autocorrelation) — human voiced speech;
 *  - TONAL: nearly all energy in one or two narrow spectral peaks with no harmonic ladder — beeps, chimes, notification
 *    tones (voiced speech always shows several harmonics). A periodic hop also counts as TONAL when the signal repeats
 *    faster than any voice (a period shorter than 2.5 ms, i.e. a fundamental above 400 Hz): the in-range
 *    autocorrelation peak is then only a multiple of that period — chimes, alert tones, most notification sounds.
 *  - SUSTAINED (active, neither voiced nor tonal): a periodic hop whose spectrum is unchanged in the disjoint windows
 *    40 ms before and after it. Struck or held notes and alert tones ring with fixed partials; voiced speech does not
 *    hold its harmonics and formants still for 80–120 ms (pitch glides and articulation move them).
 *  - PITCH MOVEMENT between consecutive periodic hops (20 ms apart): a voice's pitch glides continuously (typically
 *    0.5–1.5 % per hop with intonation and jitter); a note or alert tone holds it (< 0.6 %). Jumps (> 2.5 %) are counted
 *    for diagnostics. Counted per pair so the evidence for a whole segment can say how voice-like the pitch was.
 * Speech evidence = voiced and not tonal. A Windows beep is tonal; a screenshot/click sound is an unvoiced noise
 * burst; neither produces speech evidence. Harmonic sounds with a moving pitch inside the voice range (singing, music,
 * a low alert tone with vibrato, the low ringing of a door knock) can still look voiced, so this is one layer of
 * several, never the only one (measured limits: tests/fixtures/audio/MANIFEST.md).
 */
object AcousticAnalyzer {
    const val SampleRate = 24000
    private const val Decimated = SampleRate / 2 // 12 kHz
    private const val HopMs = 20
    private const val WindowMs = 40
    private const val Window = Decimated * WindowMs / 1000 // 480 samples
    private const val MinLag = Decimated / 400 // 30
    private const val MaxLag = Decimated / 80 // 150
    private const val Fft = 512
    const val ActiveDbfs = -50.0
    const val VoicedCorrelation = 0.5
    const val TonalConcentration = 0.97

    /** Spectral cosine similarity above which two disjoint 40 ms windows hold the same sustained sound. */
    const val SustainedSimilarity = 0.95

    /** A shorter period is the true one when it is (almost) as periodic as the in-range peak. */
    private const val ShortPeriodMargin = 0.02
    private const val MinShortLag = 5 // 2.4 kHz
    private const val SpectrumBins = Fft / 2
    private const val Inactive: Byte = 0
    private const val Unvoiced: Byte = 1
    private const val Voiced: Byte = 2
    private const val Tonal: Byte = 3

    /** |ln(f2/f1)| below which consecutive periodic hops hold the same pitch (0.6 %). */
    const val SteadyPitchDelta = 0.006

    /** |ln(f2/f1)| above which consecutive periodic hops have no continuous pitch (2.5 %). */
    const val PitchJumpDelta = 0.025

    private val threadX = ThreadLocal<DoubleArray?>()
    private val threadRe = ThreadLocal<DoubleArray?>()
    private val threadIm = ThreadLocal<DoubleArray?>()
    private val threadR = ThreadLocal<DoubleArray?>()
    private val threadSpectra = ThreadLocal<Array<DoubleArray?>?>()
    private val threadKind = ThreadLocal<ByteArray?>()
    private val threadPeriod = ThreadLocal<DoubleArray?>()
    private val threadStart = ThreadLocal<IntArray?>()

    /** Analyses one frame; with a [runs] tracker the frame's pitch also continues the stream's runs. */
    fun analyze(pcm16: ByteArray, runs: PitchRunTracker? = null): FrameAcoustics {
        val n24 = pcm16.size / 2
        if (n24 < 2) return FrameAcoustics(0, 0, 0, 0)
        val n12 = n24 / 2
        val existingX = threadX.get()
        val x = if (existingX != null && existingX.size >= n12) existingX else DoubleArray(max(n12, Window)).also { threadX.set(it) }
        for (i in 0 until n12) {
            val s0 = (pcm16[i * 4].toInt() and 0xFF) or (pcm16[i * 4 + 1].toInt() shl 8)
            val s1 = (pcm16[i * 4 + 2].toInt() and 0xFF) or (pcm16[i * 4 + 3].toInt() shl 8)
            x[i] = (s0 + s1) / 65536.0 // average pair (cheap anti-aliasing), scaled to [-0.5, 0.5]
        }
        val durationMs = n24 * 1000 / SampleRate
        val hop = Decimated * HopMs / 1000
        val hops = max(1, n12 / hop)
        val msPerHop = max(1, durationMs / hops)
        var active = 0
        var voiced = 0
        var tonal = 0
        var kind = threadKind.get()
        var spectra = threadSpectra.get()
        var period = threadPeriod.get()
        var starts = threadStart.get()
        if (kind == null || kind.size < hops) {
            kind = ByteArray(hops)
            spectra = arrayOfNulls(hops)
            period = DoubleArray(hops)
            starts = IntArray(hops)
            threadKind.set(kind)
            threadSpectra.set(spectra)
            threadPeriod.set(period)
            threadStart.set(starts)
        }
        val kindArr = kind!!
        val spectraArr = spectra!!
        val periodArr = period!!
        val startsArr = starts!!
        for (h in 0 until hops) {
            kindArr[h] = Inactive
            periodArr[h] = 0.0
            var start = h * hop
            var len = min(Window, n12 - start)
            // The last hop of a frame analyses the frame's last full window, not a truncated 20 ms one (a half window
            // smears narrow partials, so a pure tone stopped looking tonal on every fifth hop).
            if (len < Window) {
                start = max(0, n12 - Window)
                len = min(Window, n12 - start)
            }
            startsArr[h] = start
            if (len <= MinLag) continue
            var mean = 0.0
            for (i in start until start + len) mean += x[i]
            mean /= len
            var energy = 0.0
            for (i in start until start + len) {
                val d = x[i] - mean
                energy += d * d
            }
            val rms = sqrt(energy / len) * 2 // back to full scale
            val db = if (rms <= 1e-9) -120.0 else 20 * log10(rms)
            if (db < ActiveDbfs) continue
            active += msPerHop
            val power = spectraArr[h] ?: DoubleArray(SpectrumBins).also { spectraArr[h] = it }
            val (conc, _, peaks) = spectrum(x, start, len, mean, power)
            // Narrow-band sound: energy in one or two partials and almost nothing else (beeps, chimes, notification
            // tones). Voiced speech always shows a ladder of harmonics, so it has several strong spectral peaks.
            val isTonal = if (peaks <= 1) conc >= 0.9 else peaks <= 2 && conc >= TonalConcentration
            if (isTonal) {
                kindArr[h] = Tonal
                continue
            }
            kindArr[h] = Unvoiced
            if (len <= MaxLag + 60) continue
            val (r, aboveVoice, lag) = periodicity(x, start, len, mean)
            if (r >= VoicedCorrelation) {
                kindArr[h] = if (aboveVoice) Tonal else Voiced
                if (!aboveVoice) periodArr[h] = lag
            }
        }
        val runCounts = runs?.track(periodArr, startsArr, hops, hop)
        var pairs = 0
        var steady = 0
        var jumps = 0
        for (h in 1 until hops) {
            // Pitch movement over every pair of consecutive periodic hops in the voice range (sustained ones included:
            // their steadiness is exactly what distinguishes a held tone from a voice).
            // Only hops a full hop apart: the last hop of a frame re-analyses (part of) the previous window.
            if (periodArr[h] <= 0 || periodArr[h - 1] <= 0 || startsArr[h] - startsArr[h - 1] != hop) continue
            val delta = abs(ln(periodArr[h] / periodArr[h - 1]))
            pairs++
            if (delta < SteadyPitchDelta) steady++
            else if (delta > PitchJumpDelta) jumps++
        }
        for (h in 0 until hops) {
            if (kindArr[h] == Tonal) tonal += msPerHop
            else if (kindArr[h] == Voiced) {
                // A sustained note is neither speech nor a narrow-band beep: it only counts as active sound.
                if (!sustained(h, hops, kindArr, spectraArr)) voiced += msPerHop
            }
        }
        return FrameAcoustics(
            durationMs, min(active, durationMs), min(voiced, durationMs), min(tonal, durationMs), pairs, steady, jumps,
            runCounts?.tracked ?: 0, runCounts?.runs ?: 0, runCounts?.flat ?: 0, runCounts?.stretch ?: 0,
        )
    }

    private data class PeriodicityResult(val best: Double, val aboveVoiceRange: Boolean, val period: Double)

    /**
     * Maximum normalized autocorrelation over human pitch lags, and whether the signal's true period is shorter than any
     * voice's: a lag under 2.5 ms (above 400 Hz) that is about as periodic as the in-range peak, which is (close to) an
     * integer multiple of it. The in-range peak is then a multiple of a chime/alert-tone period, not a voice pitch.
     */
    private fun periodicity(x: DoubleArray, start: Int, len: Int, mean: Double): PeriodicityResult {
        val r = threadR.get() ?: DoubleArray(MaxLag + 2).also { threadR.set(it) }
        r.fill(0.0)
        val n = len
        var bestLag = 0
        var best = 0.0
        var lag = MinShortLag - 1
        while (lag <= MaxLag && lag < n - 60) {
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            var i = 0
            while (i + lag < n) {
                val a = x[start + i] - mean
                val b = x[start + i + lag] - mean
                xy += a * b
                xx += a * a
                yy += b * b
                i++
            }
            r[lag] = if (xx <= 0 || yy <= 0) 0.0 else xy / sqrt(xx * yy)
            if (lag >= MinLag && r[lag] > best) {
                best = r[lag]
                bestLag = lag
            }
            lag++
        }
        if (best < VoicedCorrelation) return PeriodicityResult(best, false, 0.0)
        val period = interpolate(r, bestLag)
        for (shortLag in MinShortLag until MinLag) {
            if (r[shortLag] < best - ShortPeriodMargin || r[shortLag] < r[shortLag - 1] || r[shortLag] < r[shortLag + 1]) continue
            val shortPeriod = interpolate(r, shortLag)
            val k = Math.rint(period / shortPeriod)
            if (k >= 2 && abs(period - k * shortPeriod) <= 0.04 * period) return PeriodicityResult(best, true, period)
        }
        return PeriodicityResult(best, false, period)
    }

    /** Parabolic interpolation of a correlation peak (fractional lag). */
    private fun interpolate(r: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag + 1 >= r.size) return lag.toDouble()
        val a = r[lag - 1]
        val b = r[lag]
        val c = r[lag + 1]
        val d = a - 2 * b + c
        return if (d < 0) lag + 0.5 * (a - c) / d else lag.toDouble()
    }

    /**
     * A voiced-looking hop whose spectrum is unchanged in the disjoint windows 40 ms before and after it (those of this
     * frame that hold sound; at least one) is a sustained note or tone, not speech.
     */
    private fun sustained(h: Int, hops: Int, kind: ByteArray, spectra: Array<DoubleArray?>): Boolean {
        var partners = 0
        var j = h - 2
        while (j <= h + 2) {
            if (j >= 0 && j < hops && kind[j] != Inactive) {
                if (cosine(spectra[h]!!, spectra[j]!!) < SustainedSimilarity) return false
                partners++
            }
            j += 4
        }
        return partners > 0
    }

    /** Cosine similarity of two power spectra over 70 Hz – 4 kHz (the band the peak analysis uses). */
    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        val lo = (70.0 * Fft / Decimated).toInt()
        val hi = (4000.0 * Fft / Decimated).toInt()
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        for (k in lo..hi) {
            xy += a[k] * b[k]
            xx += a[k] * a[k]
            yy += b[k] * b[k]
        }
        return if (xx <= 0 || yy <= 0) 0.0 else xy / sqrt(xx * yy)
    }

    private data class SpectrumResult(val share: Double, val peakHz: Double, val peaks: Int)

    /** Share of spectral power in the two strongest narrow peaks (±2 bins). Pure/dual tones ≈ 0.9+, speech ≈ 0.2–0.5. */
    private fun spectrum(x: DoubleArray, start: Int, len: Int, mean: Double, power: DoubleArray): SpectrumResult {
        val re = threadRe.get() ?: DoubleArray(Fft).also { threadRe.set(it) }
        val im = threadIm.get() ?: DoubleArray(Fft).also { threadIm.set(it) }
        re.fill(0.0)
        im.fill(0.0)
        val n = min(len, Fft)
        for (i in 0 until n) {
            val hann = 0.5 - 0.5 * cos(2 * Math.PI * i / (n - 1))
            re[i] = (x[start + i] - mean) * hann
        }
        fftInPlace(re, im)
        val half = Fft / 2
        val p = DoubleArray(half)
        var total = 0.0
        power.fill(0.0)
        for (k in 1 until half) {
            p[k] = re[k] * re[k] + im[k] * im[k]
            total += p[k]
            power[k] = p[k]
        }
        if (total <= 0) return SpectrumResult(0.0, 0.0, 0)
        // Strong peaks: local maxima within 20 dB of the strongest bin, between 70 Hz and 4 kHz.
        var maxP = 0.0
        for (k in 1 until half) maxP = max(maxP, p[k])
        val lo = max(2, (70.0 * Fft / Decimated).toInt())
        val hi = min(half - 2, (4000.0 * Fft / Decimated).toInt())
        var strong = 0
        for (k in lo..hi) {
            if (p[k] >= maxP * 0.01 && p[k] >= p[k - 1] && p[k] > p[k + 1] && p[k] >= p[k - 2] && p[k] > p[k + 2]) strong++
        }
        var peaksPower = 0.0
        var peakHz = 0.0
        for (round in 0 until 2) {
            var best = 1
            for (k in 2 until half) if (p[k] > p[best]) best = k
            if (round == 0) peakHz = best * Decimated.toDouble() / Fft
            for (k in max(1, best - 2)..min(half - 1, best + 2)) {
                peaksPower += p[k]
                p[k] = 0.0
            }
        }
        return SpectrumResult(peaksPower / total, peakHz, strong)
    }

    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = i + k + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Acoustic evidence for one interviewer segment or turn. [windowMs] is the time range the evidence was asked
 * for; [spanMs] the audio actually analysed in it (less when capture frames were missing).
 */
data class SpeechEvidence(
    val framesObserved: Int,
    val spanMs: Int,
    val activeMs: Int,
    val voicedMs: Int,
    val tonalMs: Int,
    val pitchPairs: Int = 0,
    val steadyPairs: Int = 0,
    val jumpPairs: Int = 0,
    val windowMs: Int = 0,
    val trackedHops: Int = 0,
    val runHops: Int = 0,
    val flatRunHops: Int = 0,
    val stretchHops: Int = 0,
) {
    /** No audio was analysed (no capture data for the period): acoustic layers abstain. */
    val unknown: Boolean get() = framesObserved == 0

    /**
     * Less than three quarters of the requested window was analysed (capture stalled, a segment's start was lost, or a
     * monologue outlasted the evidence buffer): the words cannot be compared with the audio, so the speech-rate checks
     * abstain instead of vetoing real speech they could not hear. Whether the audio that WAS heard is a voice still counts.
     */
    val incomplete: Boolean get() = !unknown && windowMs > 0 && spanMs * 4 < windowMs * 3

    /** Enough voiced, non-tonal sound for a real human utterance (even a short "Why?"). */
    val hasSpeech: Boolean get() = !unknown && voicedMs >= MinVoicedMs

    /** A short sound with no speech in it: a beep, chime, click or screenshot sound. */
    val isShortNonSpeechEvent: Boolean get() = !unknown && voicedMs < MinVoicedMs && activeMs <= ShortEventMs

    /** Share of the periodic sound that was narrow-band (chimes, alert tones): speech ≈ 0–3 %. */
    val tonalShare: Double get() = if (voicedMs + tonalMs == 0) 0.0 else tonalMs.toDouble() / (voicedMs + tonalMs)

    /** Share of consecutive periodic hops that held their pitch (notes, tones): speech ≈ 15–60 %. */
    val pitchSteadiness: Double get() = if (pitchPairs == 0) 0.0 else steadyPairs.toDouble() / pitchPairs

    /** Share of consecutive periodic hops whose pitch jumped (knocks, rattles): speech is continuous. */
    val pitchJumpShare: Double get() = if (pitchPairs == 0) 0.0 else jumpPairs.toDouble() / pitchPairs

    /** Pitch runs were tracked for this audio (evidence recorded before 2.1.0-rc.5 has none). */
    val pitchTracked: Boolean get() = trackedHops > 0

    /** Share of the hops in continuous pitch runs whose pitch never left a 2 % band (held tones: 66–100 %, voices ≤ 40 %). */
    val flatRunShare: Double get() = if (runHops == 0) 0.0 else flatRunHops.toDouble() / runHops

    /**
     * A held tone: most of the continuous pitch sits in runs that never moved by 2 % (a note, a buzz with vibrato, a
     * chord), or the pitch is both hop-to-hop steady and at least half flat-run. Judged by the runs once
     * [MinRunHops] hops are in runs. Hop-to-hop steadiness ALONE never rejects a sound with enough run
     * evidence: a calm, falling "Tell me more." changes its pitch by under 0.6 % per 20 ms yet glides several percent
     * over each word (the rc.4 regression). With fewer run hops than that — a 300–400 ms buzz or piano note, a pulsing
     * tone whose pulses are too short for runs — the runs cannot judge, and the rc.4 rule (≥ 10 pitch pairs, ≥ 65 %
     * steady) still applies: no real speech fixture combines so few runs with so steady a pitch. Evidence without run
     * tracking (recorded by older builds) keeps the rc.4 rule.
     */
    val heldTone: Boolean
        get() = if (runsJudged) {
            flatRunShare >= MaxFlatRunShare || (pitchPairs >= MinPitchPairs && pitchSteadiness >= SteadyWithFlatRuns && flatRunShare >= MinFlatRunShareWhenSteady)
        } else {
            pitchPairs >= MinPitchPairs && pitchSteadiness >= MaxPitchSteadiness
        }

    /** The held-tone test is decided by pitch runs (tracked, and at least [MinRunHops] hops in runs). */
    private val runsJudged: Boolean get() = pitchTracked && runHops >= MinRunHops

    /**
     * Voiced sound that also BEHAVES like a voice: little narrow-band energy and a pitch that moves rather than holding
     * still ([heldTone]). Too little pitch to judge (a very short "Why?") counts as voice-like when the other
     * signs agree. Pitch JUMPS are reported but deliberately not used to reject: creaky voice and vocal fry make real
     * voices jumpy too, and rejecting a real interviewer is the worse failure.
     */
    val voiceLike: Boolean get() = hasSpeech && !tooTonal && !heldTone

    /**
     * Narrow-band energy dominates. Judged only once at least [MinTonalMs] of tonal sound was heard: in a
     * half-second "And then?" two or three tonal hops are measurement noise, while every chime and alert tone measured
     * holds 200 ms or more.
     */
    val tooTonal: Boolean get() = tonalMs >= MinTonalMs && tonalShare >= MaxTonalShare

    /**
     * The pitch track is long enough to tell a moving voice from a held tone. Live (partial) text is only trusted once
     * this holds; a turn too short to reach it is judged when it ends.
     */
    val pitchJudged: Boolean get() = if (pitchTracked) runHops >= MinRunHops else pitchPairs >= MinPitchPairs

    /**
     * At least one continuous voiced stretch of 160 ms or more was heard ([PitchRunTracker.MinStretchWindows]):
     * a spoken word of two or more syllables has one, a knock or a click does not.
     */
    val sustainedVoicing: Boolean get() = stretchHops > 0

    /** Stretches were measured (pitch tracked, and not a record written before the count existed: -1). */
    val stretchMeasured: Boolean get() = pitchTracked && stretchHops >= 0

    /** Why the audio does not behave like a voice (for diagnostics); empty when it does. */
    val notVoiceReason: String
        get() = if (!hasSpeech) "no voiced sound"
        else if (tooTonal) "tonal ${percent0(tonalShare)}"
        else if (heldTone) (if (runsJudged) "held pitch ${percent0(flatRunShare)}" else "steady pitch ${percent0(pitchSteadiness)}")
        else ""

    operator fun plus(b: SpeechEvidence): SpeechEvidence =
        SpeechEvidence(
            framesObserved + b.framesObserved, spanMs + b.spanMs, activeMs + b.activeMs, voicedMs + b.voicedMs, tonalMs + b.tonalMs,
            pitchPairs + b.pitchPairs, steadyPairs + b.steadyPairs, jumpPairs + b.jumpPairs, windowMs + b.windowMs,
            trackedHops + b.trackedHops, runHops + b.runHops, flatRunHops + b.flatRunHops, stretchHops + b.stretchHops,
        )

    override fun toString(): String = if (unknown) "acoustics: n/a"
    else "voiced $voicedMs ms, active $activeMs ms, tonal $tonalMs ms over $spanMs ms" +
        (if (pitchPairs > 0) ", pitch steady ${percent0(pitchSteadiness)} / jumps ${percent0(pitchJumpShare)} of $pitchPairs" else "") +
        (if (runHops > 0) ", flat runs ${percent0(flatRunShare)} of $runHops" else "") +
        (if (stretchHops > 0) ", stretches $stretchHops" else "")

    companion object {
        /**
         * A segment whose audio window is not known (e.g. its start was lost in a reconnect, or its evidence never arrived):
         * no frames, and a window so large that any turn containing it counts as [incomplete] — the acoustic layers
         * abstain rather than judging a whole question by a fragment of its audio.
         */
        val Unheard: SpeechEvidence get() = SpeechEvidence(0, 0, 0, 0, 0, windowMs = UnheardWindowMs)
        const val UnheardWindowMs = 600_000
        const val MinVoicedMs = 100
        const val ShortEventMs = 1500
        const val MaxTonalShare = 0.2
        const val MinTonalMs = 100
        const val MaxPitchSteadiness = 0.65
        const val MinPitchPairs = 10
        const val MinRunHops = 10
        const val MaxFlatRunShare = 0.75
        const val SteadyWithFlatRuns = 0.65
        const val MinFlatRunShareWhenSteady = 0.5

        /** .NET `{value:P0}` in an en-US-style rendering: rounded to whole percent, no space before %. */
        internal fun percent0(value: Double): String = "${Math.rint(value * 100).toLong()}%"
    }
}

/**
 * Bounded timeline of analysed frames (last ~3 minutes) so the evidence for a VAD segment can be summed when the
 * server reports speech start/stop. Thread-safe: frames arrive on the capture thread, queries on the event thread.
 */
class SpeechEvidenceTimeline(capacity: Int = 1800) {
    private class Entry(var atMs: Long, var a: FrameAcoustics)

    private val ring: Array<Entry> = Array(capacity) { Entry(0, FrameAcoustics(0, 0, 0, 0)) }
    private var head: Int = 0
    private var count: Int = 0
    private val lock = Any()

    fun add(atMs: Long, a: FrameAcoustics) {
        synchronized(lock) {
            ring[head].atMs = atMs
            ring[head].a = a
            head = (head + 1) % ring.size
            if (count < ring.size) count++
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }

    /** Sums frames whose capture time lies in [fromMs, toMs]. */
    fun sum(fromMs: Long, toMs: Long): SpeechEvidence {
        synchronized(lock) {
            var frames = 0
            var total = FrameAcoustics(0, 0, 0, 0)
            for (i in 0 until count) {
                val entry = ring[(head - 1 - i + ring.size) % ring.size]
                if (entry.atMs < fromMs) break
                if (entry.atMs > toMs) continue
                frames++
                total += entry.a
            }
            return SpeechEvidence(
                frames, total.durationMs, total.activeMs, total.voicedMs, total.tonalMs,
                total.pitchPairs, total.steadyPairs, total.jumpPairs, max(0L, toMs - fromMs).toInt(), total.trackedHops, total.runHops, total.flatRunHops,
                total.stretchHops,
            )
        }
    }
}
