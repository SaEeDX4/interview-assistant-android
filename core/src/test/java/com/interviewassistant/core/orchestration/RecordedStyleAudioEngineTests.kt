package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.AudioFixture
import com.interviewassistant.core.audio.AudioFixtures
import com.interviewassistant.core.audio.RecordedStyleAudioTests
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The five `Engine_*` cases of `InterviewAssistant.Tests/RecordedStyleAudioTests.cs`, deferred BY NAME
 * from Package C (P3-PORT-NOTES.md §C2); the acoustic-layer cases of that file live in audio/.
 */
class RecordedStyleAudioEngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private val echoPrompt: String get() = Fixtures.testKnowledge().context.buildTranscriptionPrompt()

        private val ContextHallucinations = listOf(
            "What is your experience with FinTech products?",
            "How would you increase adoption of the XAB token at Teroxx?",
            "Tell me about your experience as a Product Owner with blockchain products.",
        )

        private suspend fun playAsync(f: AudioFixture, transcript: String?, partials: Boolean = true): InterviewSimulator {
            val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false })
            s.start()
            s.run(1000) // capture running, room quiet
            s.soundFromPcm(AudioFixtures.pcm(f), transcript, partials)
            s.run(f.durationMs + 4500)
            s.settle()
            return s
        }

        /** The UI never saw the invented words, nor a "transcribing" / "question detected" state for them. */
        private fun assertNothingShown(s: InterviewSimulator, hallucination: String?) {
            for (t in s.liveTexts) assertTrue("live text shown: '$t' (for '$hallucination}')", t.isEmpty())
            assertFalse(s.statuses.contains(EngineStatus.Finalizing))
            assertFalse(s.statuses.contains(EngineStatus.SpeechDetected))
        }

        private fun assertNothingAnswered(s: InterviewSimulator, what: String) {
            assertTrue("$what: an answer was started", s.started.isEmpty())
            assertTrue("$what: a question was recorded", s.e.memory.questions.isEmpty())
            assertEquals(what, 0, s.p.requests.get())
        }
    }

    @Test
    fun engine_NonSpeechFixture_WithHallucinatedTranscript_AnswersNothing() = runBlocking {
        for (f in AudioFixtures.all.filter { x -> !x.isSpeech }) {
            val file = f.file
            for (hallucination in listOf(echoPrompt, "Thank you.", null)) {
                val s = playAsync(f, hallucination)
                assertNothingAnswered(s, "$file heard as '$hallucination'")
                if (!RecordedStyleAudioTests.KnownVoiceLookalikes.containsKey(file)) assertNothingShown(s, hallucination)
            }
        }
    }

    @Test
    fun engine_NonSpeechFixture_HeardAsAShortQuestion_IsVetoedAcoustically() = runBlocking {
        // The hard case for the text layer: the recognizer turns the sound into a plausible short question. Only the
        // acoustic veto can stop it, so this holds for every fixture that does not behave like a voice — all system sounds.
        for (f in AudioFixtures.all.filter { x -> !x.isSpeech }) {
            val file = f.file
            for (question in listOf("How so?", "Why?", "Really?", "Tell me more.", "What happened?")) {
                val s = playAsync(f, question)
                if (RecordedStyleAudioTests.KnownVoiceLookalikes.containsKey(file)) continue // documented residual: a short phrase over a voice-like sound
                assertNothingAnswered(s, "$file heard as '$question'")
                assertNothingShown(s, question)
                assertTrue(
                    s.e.metrics.suppression.toString(),
                    s.e.metrics.shortAcousticEventSuppressed + s.e.metrics.nonSpeechSuppressed >= 1,
                )
            }
        }
    }

    @Test
    fun engine_NonSpeechFixture_HeardAsAContextDerivedQuestion_AnswersNothing() = runBlocking {
        // The RC3 manual failure: a short system sound became "What is your experience with FinTech products?". A well-
        // formed interview question built from the prompt vocabulary is never accepted from audio that is not a voice, nor
        // from a voice too short to have said it. Singing is the documented exception (long, genuinely voiced audio).
        for (f in AudioFixtures.all.filter { x -> !x.isSpeech }) {
            val file = f.file
            for (hallucination in ContextHallucinations) {
                val s = playAsync(f, hallucination)
                if (f.kind == "music-sung") continue
                assertNothingAnswered(s, "$file heard as '$hallucination'")
                assertTrue(s.e.history.isEmpty())
                if (RecordedStyleAudioTests.KnownVoiceLookalikes.containsKey(file)) {
                    // A voice-like knock can carry the first few words for a moment (they fit the voiced audio), but the
                    // sentence loses its trust as soon as it outgrows the sound: never the full invention, never "finalizing".
                    assertFalse(
                        s.liveTexts.any { t -> t.isNotEmpty() && t.trimEnd('?', '.') == hallucination.trimEnd('?', '.') },
                    )
                    assertEquals("", s.liveTexts.lastOrNull() ?: "")
                    assertFalse(s.statuses.contains(EngineStatus.Finalizing))
                    continue
                }
                assertNothingShown(s, hallucination)
            }
        }
    }

    @Test
    fun engine_SpeechFixture_IsAnswered() = runBlocking {
        for (f in AudioFixtures.all.filter { x -> x.isSpeech }) {
            val s = playAsync(f, f.text)
            assertEquals(f.file, 1, s.e.memory.questions.size)
            assertEquals(f.file, f.text, s.e.memory.questions[0].original)
            assertEquals(f.file, 1, s.started.size)
            assertEquals(f.file, 0, s.e.metrics.nonSpeechSuppressed + s.e.metrics.shortAcousticEventSuppressed)
        }
    }

    @Test
    fun engine_SpeechFixture_ShowsLiveWordsBeforeTheTurnEnds() = runBlocking {
        // The trust boundary must not make real speech feel laggy: words appear while the interviewer is still talking
        // (or within the VAD tail), not only once the turn is final.
        val files = listOf(
            "speech_ninetydays__callq.wav",
            "speech_ninetydays__calln.wav",
            "speech_fast_ninetydays__callq.wav",
            "speech_de_warum__callq.wav",
            "speech_es_reto__calln.wav",
            "speech_tellme__callq.wav",
        )
        for (file in files) {
            val f = AudioFixtures.get(file)
            val start = 1000L // playAsync starts the sound after one second of quiet
            val s = playAsync(f, f.text)
            val firstShown = s.liveTimeline.firstOrNull { x -> x.second.isNotEmpty() }
            assertTrue("$file: no live text was ever shown", firstShown != null)
            val soundEnd = start + (f.durationMs + 99) / 100 * 100
            assertTrue(
                "$file: first live words only at +${firstShown!!.first - start} ms",
                firstShown.first <= soundEnd + 400,
            )
            assertTrue(s.statuses.contains(EngineStatus.SpeechDetected))
            assertEquals(file, 1, s.e.memory.questions.size)
        }
    }
}
