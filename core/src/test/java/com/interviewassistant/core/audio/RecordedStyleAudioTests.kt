package com.interviewassistant.core.audio

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.PromptEchoDetector
import com.interviewassistant.core.intelligence.UtteranceGate
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of the `AudioFixture` record in `InterviewAssistant.Tests/RecordedStyleAudioTests.cs`. */
data class AudioFixture(
    val file: String,
    val cls: String,
    val kind: String,
    val text: String?,
    val durationMs: Int,
    val bytes: Long,
    val sha256: String,
) {
    val isSpeech: Boolean get() = cls == "speech"
    override fun toString(): String = file
}

/** Mirror of the `AudioFixtures` helper in `InterviewAssistant.Tests/RecordedStyleAudioTests.cs`. */
object AudioFixtures {
    val dir: File get() = Fixtures.audioFixtureDir()

    val all: List<AudioFixture> by lazy {
        val root = Json.parseToJsonElement(File(dir, "fixtures.json").readText(Charsets.UTF_8)).jsonObject
        root["fixtures"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            AudioFixture(
                file = o["file"]!!.jsonPrimitive.content,
                cls = o["cls"]!!.jsonPrimitive.content,
                kind = o["kind"]!!.jsonPrimitive.content,
                text = if (o["text"] is JsonNull) null else o["text"]!!.jsonPrimitive.content,
                durationMs = o["duration_ms"]!!.jsonPrimitive.content.toInt(),
                bytes = o["bytes"]!!.jsonPrimitive.long,
                sha256 = o["sha256"]!!.jsonPrimitive.content,
            )
        }
    }

    fun get(file: String): AudioFixture = all.single { it.file == file }

    /** The PCM16 data chunk of a fixture; asserts the format the engine analyses (24 kHz, mono, 16-bit). */
    fun pcm(f: AudioFixture): ByteArray {
        val bytes = File(dir, f.file).readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        var pos = 12
        var data: ByteArray? = null
        fun int16Le(o: Int): Int = (bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)
        fun int32Le(o: Int): Int = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or (bytes[o + 3].toInt() shl 24)
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = int32Le(pos + 4)
            if (id == "fmt ") {
                assertEquals(1, int16Le(pos + 8)) // PCM
                assertEquals(1, int16Le(pos + 10)) // mono
                assertEquals(AcousticAnalyzer.SampleRate, int32Le(pos + 12))
                assertEquals(16, int16Le(pos + 22)) // bits per sample
            } else if (id == "data") {
                data = bytes.copyOfRange(pos + 8, pos + 8 + minOf(size, bytes.size - pos - 8))
            }
            pos += 8 + size + (size and 1)
        }
        assertTrue("no data chunk in ${f.file}", data != null)
        return data!!
    }

    fun nonSpeech(): List<String> = all.filter { !it.isSpeech }.map { it.file }
    fun speech(): List<String> = all.filter { it.isSpeech }.map { it.file }
}

/**
 * Mirror of `InterviewAssistant.Tests/RecordedStyleAudioTests.cs` — the cases that depend only on the acoustic
 * layer and the Package B text gate. The five `Engine_*` cases run on the InterviewSimulator (Orchestration,
 * Package D) and are deferred by name in P3-PORT-NOTES.md (Package C).
 */
class RecordedStyleAudioTests {

    companion object {
        /**
         * Non-speech fixtures whose audio still BEHAVES like a voice to the acoustic layer (voiced, not narrow-band, pitch not
         * held steady) — the documented residual of a frame-local analysis. Every system sound (notification chimes, alert and
         * error tones, shutter, clicks) and the instrumental music are NOT in this list: they are vetoed acoustically whatever
         * the recognizer makes of them. Kept honest in both directions: the classification test fails if a listed fixture
         * stops looking like a voice, or an unlisted one starts to.
         */
        val KnownVoiceLookalikes: Map<String, String> = mapOf(
            "knock_door__callq.wav" to "quiet knock: 95-130 Hz panel resonance with a wobbling period (pitch jumps are not used to reject — creaky voices jump too)",
            "music_sung__callq.wav" to "HARD CASE: sung vowels (glottal pulses, formants, vibrato) are voiced speech acoustically",
            "music_sung__calln.wav" to "as callq",
        )

        /** Recognizer output that is pure invention built from the interview's own vocabulary (the RC3 manual failure). */
        val ContextHallucinations: List<String> = listOf(
            "What is your experience with FinTech products?",
            "How would you increase adoption of the XAB token at Teroxx?",
            "Tell me about your experience as a Product Owner with blockchain products.",
        )

        /** Streams the fixture as 100 ms capture frames (last one padded with silence) and sums the evidence as the
         * engine's timeline does. */
        fun evidence(pcm: ByteArray): SpeechEvidence {
            val framer = AudioFramer()
            val padded = ByteArray((pcm.size + 4799) / 4800 * 4800)
            pcm.copyInto(padded, 0)
            var total = FrameAcoustics(0, 0, 0, 0)
            var frames = 0
            val runs = PitchRunTracker()
            for (frame in framer.push(padded)) {
                assertEquals(4800, frame.size)
                total += AcousticAnalyzer.analyze(frame, runs)
                frames++
            }
            total += AcousticAnalyzer.analyze(ByteArray(4800), runs) // the quiet after the sound closes the last pitch run
            return SpeechEvidence(
                frames, total.durationMs - 100, total.activeMs, total.voicedMs, total.tonalMs, total.pitchPairs, total.steadyPairs, total.jumpPairs,
                0, total.trackedHops, total.runHops, total.flatRunHops, total.stretchHops,
            )
        }

        private fun percent0(v: Double): String = "${Math.rint(v * 100).toLong()}%"

        private fun classification(e: SpeechEvidence): String =
            if (e.voiceLike) "voice"
            else if (e.activeMs <= SpeechEvidence.ShortEventMs) "short non-speech event (" + e.notVoiceReason + ")"
            else "non-speech (" + e.notVoiceReason + ")"
    }

    private val echoPrompt: String get() = Fixtures.testKnowledge().context.buildTranscriptionPrompt()

    @Test
    fun fixtures_AreReproducibleDocumentedAndWithinBudget() {
        val all = AudioFixtures.all
        assertTrue("${all.size} fixtures", all.size >= 60)
        val onDisk = AudioFixtures.dir.listFiles { f -> f.name.endsWith(".wav") }!!.map { it.name }.sorted()
        assertEquals(all.map { it.file }.sorted(), onDisk)
        val manifest = File(AudioFixtures.dir, "MANIFEST.md").readText(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256")
        for (f in all) {
            val bytes = File(AudioFixtures.dir, f.file).readBytes()
            assertEquals(f.bytes, bytes.size.toLong())
            assertEquals(f.sha256, sha256.digest(bytes).joinToString("") { "%02x".format(it) })
            assertTrue(f.file, manifest.contains(f.file))
            assertTrue("${f.file}: pcm ${AudioFixtures.pcm(f).size}", AudioFixtures.pcm(f).size in (2 * 24 * 300)..(2 * 24 * 3000)) // 0.3–3 s
        }
        // rc.5: +14 flat-intonation follow-ups (one call-path variant each).
        assertTrue("${all.sumOf { it.bytes }} bytes of audio fixtures", all.sumOf { it.bytes } < 4_200_000)
        for (kind in listOf("chime", "error", "shutter", "click", "knock", "music", "music-sung", "speech")) {
            assertTrue(kind, all.any { it.kind == kind })
        }
        assertTrue(all.any { it.isSpeech && it.text!!.contains('¿') }) // Spanish
        assertTrue(all.any { it.isSpeech && it.text!!.startsWith("Warum") }) // German
        for (k in KnownVoiceLookalikes.keys) {
            assertTrue(k, all.any { it.file == k && !it.isSpeech })
        }
    }

    @Test
    fun acousticAnalyzer_ClassifiesEveryFixture() {
        val failures = mutableListOf<String>()
        val table = StringBuilder()
        table.append("| Fixture | Class | Kind | Voiced ms | Tonal ms | Active ms | Duration ms | Pitch pairs | Steady | Jumps | Run windows | Flat runs | Voice-like | Classification | Expected |\n")
        table.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|\n")
        for (f in AudioFixtures.all) {
            val e = evidence(AudioFixtures.pcm(f))
            val known = KnownVoiceLookalikes.containsKey(f.file)
            val expected = if (f.isSpeech) "voice" else if (known) "known look-alike (voice-like)" else "not a voice"
            table.append(
                "| ${f.file} | ${f.cls} | ${f.kind} | ${e.voicedMs} | ${e.tonalMs} | ${e.activeMs} | ${e.spanMs} | ${e.pitchPairs} | ${percent0(e.pitchSteadiness)} | ${percent0(e.pitchJumpShare)} | ${e.runHops} | ${percent0(e.flatRunShare)} | ${if (e.voiceLike) "yes" else "no: " + e.notVoiceReason} | ${classification(e)} | $expected |\n",
            )
            if (f.isSpeech && !e.voiceLike) failures.add("${f.file}: speech not recognised as a voice ($e; ${e.notVoiceReason})")
            if (!f.isSpeech && !known && e.voiceLike) failures.add("${f.file}: non-speech behaves like a voice ($e)")
            if (known && !e.voiceLike) failures.add("${f.file}: no longer voice-like ($e) — remove it from KnownVoiceLookalikes and MANIFEST.md")
        }
        println(table.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun shortNonSpeechSounds_AreShortAcousticEvents() {
        // Clicks, the shutter and the chimes that pass the gate are not merely "not speech": they are short events, the
        // verdict the gate reports as ShortAcousticEvent.
        for (f in AudioFixtures.all.filter { it.kind == "shutter" || it.kind == "click" || it.kind == "chime" || it.kind == "error" }) {
            val e = evidence(AudioFixtures.pcm(f))
            assertFalse("${f.file}: $e", e.voiceLike)
            assertTrue("${f.file}: $e", e.activeMs <= SpeechEvidence.ShortEventMs)
        }
    }

    @Test
    fun sungMelody_LooksVoiced_ButItsTypicalTranscriptsAreStillDropped() {
        // Documented behaviour, not a goal: a sung vowel melody is voiced, non-tonal, moving-pitch audio, i.e. a voice to
        // the acoustic layer. The text layer still drops what recognizers produce for music.
        for (file in listOf("music_sung__callq.wav", "music_sung__calln.wav")) {
            val f = AudioFixtures.get(file)
            val e = evidence(AudioFixtures.pcm(f))
            println("$file: $e")
            assertTrue(e.toString(), e.voiceLike)
            val gate = UtteranceGate(PromptEchoDetector(echoPrompt))
            for (text in listOf("♪ ♪", "Thanks for watching!", "(music)", echoPrompt)) {
                assertFalse(text, gate.evaluate(text, e).answerable)
            }
        }
    }
}
