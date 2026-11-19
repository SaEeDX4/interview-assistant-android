package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.AudioFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The engine cases of `InterviewAssistant.Tests/Rc5FollowUpTests.cs`, deferred BY NAME from Package C
 * (P3-PORT-NOTES.md §C2); the text/gate cases of that file live with the intelligence/turn tests.
 * The C# `PlayPcmAsync`/`Shape` helpers are mirrored in the companion (Rc5EngineAuditTests uses them too).
 *
 * RC5 P0: candidate-directed imperative follow-ups ("Tell me more.", "Go on.", "Walk me through that.") are real turns —
 * recognized by grammar (an elicitation verb in imperative position), heard over real, often flat, speech — while the
 * same words over a sound that is not a voice still create nothing.
 */
class Rc5FollowUpEngineTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        val Positives: List<String> = listOf(
            "Tell me more.", "Walk me through that.", "Explain further.", "Go on.", "Give me an example.", "Elaborate.",
            "Continue.", "Talk me through it.", "Can you expand on that?", "Give me more detail.", "Take me through your thinking.",
            "Walk me through the process.", "Explain how you did that.", "Give me another example.",
            "Why?", "Why.", "How so?", "Really?",
        )

        fun followUpFixtures(): List<String> =
            AudioFixtures.all.filter { f -> f.isSpeech && (f.file.startsWith("speech_fu_") || f.file.startsWith("speech_tellmemore")) }
                .map { f -> f.file }

        fun nonSpeech(): List<String> =
            AudioFixtures.all.filter { f -> !f.isSpeech && !com.interviewassistant.core.audio.RecordedStyleAudioTests.KnownVoiceLookalikes.containsKey(f.file) }
                .map { f -> f.file }

        /** (file, shape, ms) triples: short held tones and pulsing tones (rc.5 engine audit, finding 1). */
        fun shortNonSpeech(): List<Triple<String, String, Int>> = listOf(
            Triple("error_low_buzz__direct.wav", "first", 300), Triple("error_low_buzz__direct.wav", "first", 400),
            Triple("error_low_buzz__callq.wav", "first", 300), Triple("error_low_buzz__callq.wav", "first", 400),
            Triple("error_low_buzz__calln.wav", "first", 360), Triple("error_low_buzz__calln.wav", "first", 400),
            Triple("music_piano__callq.wav", "first", 360), Triple("music_piano__callq.wav", "first", 400),
            Triple("error_low_buzz__direct.wav", "pulsed", 70), Triple("error_low_buzz__callq.wav", "pulsed", 70),
            Triple("error_low_buzz__calln.wav", "pulsed", 70), Triple("music_band__calln.wav", "pulsed", 70),
        )

        /**
         * The first [ms] ms of a fixture's sound ("first"), or the sound pulsed [ms] ms
         * on / (100 − ms) ms off ("pulsed"), measured from its onset.
         */
        fun shape(file: String, shape: String, ms: Int): ByteArray {
            val pcm = AudioFixtures.pcm(AudioFixtures.get(file))
            val s = ShortArray(pcm.size / 2)
            for (i in s.indices) {
                s[i] = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
            }
            val on = maxOf(0, s.indexOfFirst { v -> kotlin.math.abs(v.toInt()) > 300 })
            val o: ShortArray
            if (shape == "first") {
                o = ShortArray(minOf(s.size, on + ms * 24))
                s.copyInto(o, 0, 0, o.size)
            } else {
                o = s.copyOf()
                for (i in on until o.size) if ((i - on) / 24 % 100 >= ms) o[i] = 0
            }
            val b = ByteArray(o.size * 2)
            for (i in o.indices) {
                b[i * 2] = (o[i].toInt() and 0xFF).toByte()
                b[i * 2 + 1] = (o[i].toInt() shr 8 and 0xFF).toByte()
            }
            return b
        }

        suspend fun playPcm(pcm: ByteArray, transcript: String?): InterviewSimulator {
            val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false })
            s.start()
            s.run(1000)
            s.soundFromPcm(pcm, transcript, partials = true)
            s.run(pcm.size / 48 + 4500)
            s.settle()
            return s
        }

        suspend fun play(file: String, transcript: String?): InterviewSimulator =
            playPcm(AudioFixtures.pcm(AudioFixtures.get(file)), transcript)
    }

    // ---------------- audio fixtures through the engine ----------------

    @Test
    fun followUpFixture_CreatesExactlyOneTurnHistoryItemAndRequest() = runBlocking {
        for (file in followUpFixtures()) {
            val f = AudioFixtures.get(file)
            val s = play(f.file, f.text)
            assertEquals(file, 1, s.e.memory.questions.size)
            assertEquals(file, 1, s.e.history.size)
            assertEquals(file, 1, s.e.metrics.questions)
            assertEquals(file, 1, s.p.requests.get() + s.e.metrics.cacheAnswers) // one answer: from the model or a prepared answer
            assertEquals(file, 1, s.started.size)
            assertEquals(file, 0, s.e.metrics.totalSuppressed)
        }
    }

    @Test
    fun samePhrasesOverNonSpeech_CreateNothing() = runBlocking {
        for (file in nonSpeech()) {
            for (text in Positives) {
                val s = play(file, text)
                val what = "$file heard as '$text'"
                assertTrue("$what: a question was recorded", s.e.memory.questions.isEmpty())
                assertTrue("$what: a history item was created", s.e.history.isEmpty())
                assertTrue("$what: an AI request was made", s.p.requests.get() == 0)
                assertTrue("$what: an answer was started", s.started.isEmpty())
                for (t in s.liveTexts) assertTrue("$what: live text '$t'", t.isEmpty())
                assertEquals(what, 0, s.e.metrics.followUpsAccepted)
            }
        }
    }

    @Test
    fun samePhrasesOverShortNonSpeech_CreateNothing() = runBlocking {
        for ((file, shape, ms) in shortNonSpeech()) {
            val pcm = shape(file, shape, ms)
            for (text in Positives) {
                val s = playPcm(pcm, text)
                val what = "$file ($shape $ms) heard as '$text'"
                assertTrue("$what: a question was recorded", s.e.memory.questions.isEmpty())
                assertTrue("$what: a history item was created", s.e.history.isEmpty())
                assertTrue("$what: an AI request was made", s.p.requests.get() == 0)
                assertTrue("$what: an answer was started", s.started.isEmpty())
                for (t in s.liveTexts) assertTrue("$what: live text '$t'", t.isEmpty())
                assertEquals(what, 0, s.e.metrics.followUpsAccepted)
            }
        }
    }

    // ---------------- engine: follow-ups in a conversation ----------------

    @Test
    fun quickFollowUp_AfterACompleteQuestion_IsItsOwnTurn() = runBlocking {
        // rc.4: a follow-up starting within 2.5 s of the question was merged into it ("…? Tell me more.") and replaced
        // its answer; no new history item appeared. Changed after the rc.5 engine audit (finding 7): a 1.5 s pause is a
        // quick multi-part question and merges; 2.2 s of silence is past the candidate-reply allowance and still inside
        // the 2.5 s continuation window, so this still tests the split.
        val s = Rc2EngineAcceptanceTests.startAsync()
        s.say("What did you build at Teroxx?", 2200, "Tell me more.") // the first turn has finalized; the second starts 2.2 s later
        s.run(14_000)
        s.settle()
        assertEquals(2, s.e.memory.questions.size)
        assertEquals(2, s.e.history.size)
        assertEquals("Tell me more.", s.e.memory.questions[1].original)
        assertEquals(1, s.e.metrics.followUpTurnsSplit)
    }

    @Test
    fun unfinishedQuestion_ThenGoOn_StillMergesIntoOneTurn() = runBlocking {
        // The continuation rule is unchanged for a question that was still open ("…and").
        val s = Rc2EngineAcceptanceTests.startAsync()
        s.say("Tell me about the ledger migration and", 2000, "go on about the reconciliation.") // reopened after the 1.7 s wait for an unfinished turn
        s.run(14_000)
        s.settle()
        assertEquals(1, s.e.memory.questions.size)
        assertTrue(s.e.memory.questions[0].original.contains("reconciliation"))
        assertEquals(0, s.e.metrics.followUpTurnsSplit)
    }

    @Test
    fun followUp_AfterTheAnswer_IsAnsweredOnce() = runBlocking {
        val s = Rc2EngineAcceptanceTests.startAsync()
        s.say("What did you build at Teroxx?")
        s.run(12_000)
        s.settle()
        s.say("Tell me more.")
        s.run(8000)
        s.settle()
        assertEquals(2, s.e.memory.questions.size)
        assertEquals(2, s.e.history.size)
        assertEquals(1, s.e.metrics.followUpsAccepted)
    }

    @Test
    fun panel_JohnTellMeMore_IsNotAnswered_ShervinTellMeMore_Is() = runBlocking {
        val s = Rc2EngineAcceptanceTests.startAsync()
        s.say("John, tell me more.")
        s.run(8000)
        s.settle()
        assertTrue(s.e.history.isEmpty())
        assertEquals(1, s.e.metrics.otherDirectedSuppressed)
        s.say("Shervin, tell me more.")
        s.run(8000)
        s.settle()
        assertEquals(1, s.e.history.size)
        assertEquals(1, s.e.memory.questions.size)
    }
}
