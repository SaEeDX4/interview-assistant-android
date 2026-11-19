package com.interviewassistant.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/AcousticAnalyzerTests.cs`. */
class AcousticAnalyzerTests {

    private fun sum(signal: DoubleArray): FrameAcoustics {
        var total = FrameAcoustics(0, 0, 0, 0)
        for (f in SyntheticAudio.frames(signal)) total += AcousticAnalyzer.analyze(f)
        println(total)
        return total
    }

    @Test
    fun speech_IsVoiced_AndNotTonal() {
        for (pitch in listOf(95.0, 130.0, 210.0, 240.0)) {
            val a = sum(SyntheticAudio.speech(2000, pitch))
            assertTrue("pitch $pitch: voiced ${a.voicedMs}", a.voicedMs >= 1000)
            assertTrue("pitch $pitch: tonal ${a.tonalMs}", a.tonalMs <= a.durationMs / 5) // occasional single-harmonic windows are fine
        }
    }

    @Test
    fun shortWhy_HasEnoughVoicedEvidence() {
        val a = sum(SyntheticAudio.concat(SyntheticAudio.silence(200), SyntheticAudio.speech(320, 160.0, seed = 7), SyntheticAudio.silence(300)))
        assertTrue("voiced ${a.voicedMs}", a.voicedMs >= SpeechEvidence.MinVoicedMs)
    }

    @Test
    fun beepsAndChimes_HaveNoSpeechEvidence() {
        for ((hz, second) in listOf(440.0 to 0.0, 880.0 to 0.0, 1200.0 to 0.0, 660.0 to 990.0, 523.0 to 784.0)) {
            val a = sum(SyntheticAudio.concat(SyntheticAudio.silence(100), SyntheticAudio.beep(600, hz, secondHz = second), SyntheticAudio.silence(300)))
            assertTrue("$hz/$second: voiced ${a.voicedMs}", a.voicedMs < SpeechEvidence.MinVoicedMs)
            assertTrue("$hz/$second: active ${a.activeMs}", a.activeMs > 0)
        }
    }

    @Test
    fun shutterNoise_HasNoSpeechEvidence() {
        val a = sum(SyntheticAudio.concat(SyntheticAudio.shutter(), SyntheticAudio.silence(400)))
        assertTrue("voiced ${a.voicedMs}", a.voicedMs < SpeechEvidence.MinVoicedMs)
    }

    @Test
    fun silence_IsInactive() {
        val a = sum(SyntheticAudio.silence(1000))
        assertEquals(0, a.activeMs)
        assertEquals(1000, a.durationMs)
    }

    @Test
    fun quietSpeech_StillVoiced() {
        val a = sum(SyntheticAudio.speech(1500, 140.0, level = 0.03))
        assertTrue("voiced ${a.voicedMs}", a.voicedMs >= 600)
    }

    @Test
    fun timeline_SumsOnlyTheRequestedWindow() {
        val tl = SpeechEvidenceTimeline(10)
        for (i in 0 until 25) tl.add(i * 100L, FrameAcoustics(100, 100, if (i >= 20) 80 else 0, 0))
        val ev = tl.sum(2000, 2400)
        assertEquals(5, ev.framesObserved)
        assertEquals(400, ev.voicedMs)
        assertTrue(tl.sum(0, 500).unknown) // evicted from the bounded ring
    }

    @Test
    fun analysis_IsCheapEnoughForTheCaptureThread() {
        val frames = SyntheticAudio.frames(SyntheticAudio.speech(10_000))
        var start = System.nanoTime()
        for (f in frames) AcousticAnalyzer.analyze(f)
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames.size
        println("$perFrameMs ms per 100 ms frame")
        assertTrue("$perFrameMs ms per frame", perFrameMs < 10)
        // rc.5: the engine path also follows pitch runs across frames (PitchRunTracker) — same budget.
        val runs = PitchRunTracker()
        start = System.nanoTime()
        for (f in frames) AcousticAnalyzer.analyze(f, runs)
        val trackedMs = (System.nanoTime() - start) / 1e6 / frames.size
        println("$trackedMs ms per 100 ms frame with pitch-run tracking")
        assertTrue("$trackedMs ms per frame with pitch-run tracking", trackedMs < 10)
    }
}
