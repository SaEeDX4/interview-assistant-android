package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.PcmConverter
import com.interviewassistant.core.audio.SyntheticAudio
import com.interviewassistant.core.diagnostics.ManualClock
import com.interviewassistant.core.intelligence.SpeechPlausibility
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.providers.IAnswerProvider
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap
import kotlinx.coroutines.delay

/**
 * Mirror of `tests/InterviewAssistant.Tests/InterviewSimulator.cs`.
 *
 * Deterministic live-interview simulator on a virtual clock with REALISTIC server-VAD timing:
 *  - speech_started is delivered [onsetMs] after the interviewer actually starts speaking;
 *  - a pause shorter than the VAD silence window ([vadSilenceMs]) does not split the segment;
 *  - speech_stopped is delivered vadSilenceMs after the real silence began, the transcript [transcriptMs] later.
 * This reproduces the production bug (a 0.5–1 s pause inside one question used to create two questions).
 * Background answer tasks get real time to run whenever an answer is in flight, so results are deterministic.
 */
class InterviewSimulator(
    kb: KnowledgeBase? = null,
    withProvider: Boolean = true,
    options: EngineOptions? = null,
    provider: ((ManualClock) -> IAnswerProvider)? = null,
) {
    val clock: ManualClock = ManualClock()
    val t: FakeTranscriber = FakeTranscriber()
    val p: FakeProvider = FakeProvider()
    val e: InterviewEngine
    var onsetMs: Int = 250
    var vadSilenceMs: Int = 400
    var transcriptMs: Int = 300
    var msPerWord: Int = 330

    /** Conversational speech is ~5 syllables/s: long words take longer than short ones. */
    var msPerSyllable: Int = 190
    var audioFrameEveryMs: Int = 100

    private val startedList: MutableList<AnswerView> = mutableListOf()
    private val completedList: MutableList<AnswerView> = mutableListOf()
    private val events: TreeMap<Long, MutableList<() -> Unit>> = TreeMap()
    private var item: Int = 0

    // What is audible at each moment (virtual ms → kind), so the engine receives realistic audio frames.
    private data class SoundSpan(val from: Long, val to: Long, val kind: SoundKind, val pcm: Array<ByteArray>?)

    private val sounds: MutableList<SoundSpan> = mutableListOf()

    enum class SoundKind { Speech, Beep, Shutter, Music, Pcm }

    /** False = the old behaviour (digital silence frames only), for tests about timing without acoustics. */
    var sendAudio: Boolean = true

    val started: List<AnswerView> get() = synchronized(startedList) { startedList.toList() }
    private val liveTextsList: MutableList<String> = mutableListOf()
    private val liveTimelineList: MutableList<Pair<Long, String>> = mutableListOf()
    private val statusesList: MutableList<EngineStatus> = mutableListOf()

    /** Every live-transcript text the UI was given, in order. */
    val liveTexts: List<String> get() = synchronized(liveTextsList) { liveTextsList.toList() }

    /** Every live-transcript text with the virtual time it was shown. */
    val liveTimeline: List<Pair<Long, String>> get() = synchronized(liveTextsList) { liveTimelineList.toList() }

    /** Every engine status the UI was given, in order. */
    val statuses: List<EngineStatus> get() = synchronized(statusesList) { statusesList.toList() }
    val completed: List<AnswerView> get() = synchronized(completedList) { completedList.toList() }

    /** Completed answers that are still on screen (not replaced by a continuation of the same question). */
    val visible: List<AnswerView> get() = completed.filter { a -> !a.superseded }

    init {
        val answerProvider = provider?.invoke(clock) ?: (if (withProvider) p else null)
        e = InterviewEngine(kb ?: Fixtures.testKnowledge(), t, answerProvider, options ?: EngineOptions().apply { autoTick = false }, clock)
        e.answerStartedListeners.add { a -> synchronized(startedList) { startedList.add(a) } }
        e.liveTranscriptChangedListeners.add { text ->
            synchronized(liveTextsList) {
                liveTextsList.add(text)
                liveTimelineList.add(clock.nowMs to text)
            }
        }
        e.statusChangedListeners.add { st, _ -> synchronized(statusesList) { statusesList.add(st) } }
        e.answerCompletedListeners.add { a -> synchronized(completedList) { completedList.add(a) } }
    }

    suspend fun start() = e.startListening()

    private val inFlight: Int
        get() = synchronized(startedList) {
            synchronized(completedList) { startedList.size - completedList.size }
        }

    private fun at(ms: Long, a: () -> Unit) {
        val l = events.getOrPut(ms) { mutableListOf() }
        l.add(a)
    }

    /**
     * Schedules an interviewer turn starting now: parts separated by real pauses (ms). Returns when all events are
     * scheduled (call [run] to play them).
     */
    fun say(vararg partsAndPauses: Any) {
        val parts = mutableListOf<Pair<String, Int>>()
        for (i in partsAndPauses.indices) {
            val part = partsAndPauses[i]
            if (part is String) {
                parts.add(part to (if (i + 1 < partsAndPauses.size && partsAndPauses[i + 1] is Int) partsAndPauses[i + 1] as Int else 0))
            }
        }
        var t = clock.nowMs
        var pending = ""
        for (i in parts.indices) {
            val (text, pause) = parts[i]
            if (pending.isEmpty()) {
                val startAt = t + onsetMs
                at(startAt) { this.t.start() }
            }
            pending = if (pending.isEmpty()) text else "$pending $text"
            val dur = maxOf(600, maxOf(text.split(' ').size * msPerWord, SpeechPlausibility.syllables(text) * msPerSyllable)).toLong()
            sounds.add(SoundSpan(t, t + dur, SoundKind.Speech, null))
            t += dur
            val last = i == parts.size - 1
            if (!last && pause < vadSilenceMs) {
                t += pause
                continue
            } // VAD does not split: same segment
            val segment = pending
            val id = "item" + (++item)
            val stopAt = t + vadSilenceMs
            at(stopAt) { this.t.stop() }
            at(t + vadSilenceMs + transcriptMs) { this.t.complete(segment, id) }
            pending = ""
            t += pause
        }
    }

    /**
     * Makes the interviewer's voice audible for [ms] from now without scheduling any recognizer events —
     * for tests that drive the fake transcriber by hand (partials, revised finals, reconnects) over real-sounding audio.
     */
    fun voice(ms: Int) {
        val t = clock.nowMs
        sounds.add(SoundSpan(t, t + ms, SoundKind.Speech, null))
    }

    /**
     * A non-speech sound (beep, chime, screenshot shutter, music) that the server VAD nevertheless segments and the
     * recognizer "transcribes" as [hallucination] (e.g. its own vocabulary prompt). Null = no text.
     */
    fun sound(kind: SoundKind, hallucination: String?, durationMs: Int = 600) {
        val t = clock.nowMs
        sounds.add(SoundSpan(t, t + durationMs, kind, null))
        val id = "item" + (++item)
        at(t + onsetMs) { this.t.start() }
        at(t + durationMs + vadSilenceMs) { this.t.stop() }
        at(t + durationMs + vadSilenceMs + transcriptMs) { this.t.complete(hallucination ?: "", id) }
    }

    /**
     * Plays recorded/generated audio (24 kHz mono PCM16, e.g. a WAV fixture's data chunk) as 100 ms capture frames, with
     * the same server-VAD timing as [sound]; the recognizer then returns [transcript] (the
     * real words for speech, a hallucination such as the vocabulary prompt or "Thank you." for non-speech; null = no text).
     */
    fun soundFromPcm(pcm: ByteArray, transcript: String?, partials: Boolean = false) {
        val frameBytes = Rate24kFrameBytes
        val frames = Array((pcm.size + frameBytes - 1) / frameBytes) { i ->
            val frame = ByteArray(frameBytes) // the last frame is padded with silence, as the capture framer would after it
            pcm.copyInto(frame, 0, i * frameBytes, minOf(i * frameBytes + frameBytes, pcm.size))
            frame
        }
        val durationMs = frames.size * 100
        val t = clock.nowMs
        sounds.add(SoundSpan(t, t + durationMs, SoundKind.Pcm, frames))
        for (f in frames) pcmFrames.add(f)
        val id = "item" + (++item)
        at(t + onsetMs) { this.t.start() }
        at(t + durationMs + vadSilenceMs) { this.t.stop() }
        at(t + durationMs + vadSilenceMs + transcriptMs) { this.t.complete(transcript ?: "", id) }
        if (partials && !transcript.isNullOrEmpty()) {
            // The recognizer streams the words while it "hears" them: deltas spread over the sound and the VAD tail.
            val words = transcript.split(' ')
            val from = t + onsetMs + 100
            val span = maxOf(100, durationMs + vadSilenceMs - onsetMs - 100).toLong()
            for (i in words.indices) {
                val delta = (if (i == 0) "" else " ") + words[i]
                val atMs = from + span * i / words.size
                at(atMs) { this.t.partial(id, delta) }
            }
        }
    }

    private val pcmFrames: MutableSet<ByteArray> = Collections.newSetFromMap(IdentityHashMap())

    private fun frameAt(now: Long): ByteArray {
        if (!sendAudio) return SilenceFrame
        for ((from, to, kind, pcm) in sounds) {
            // A frame delivered at `now` carries the audio of (now - 100, now]: every PCM frame is played, the first one
            // 100 ms after the sound starts.
            if (kind == SoundKind.Pcm) {
                if (now > from && now <= to) return pcm!![minOf(((now - from - 1) / 100).toInt(), pcm.size - 1)]
                continue
            }
            if (now < from || now >= to) continue
            val idx = ((now - from) / 100).toInt()
            return when (kind) {
                SoundKind.Speech -> SpeechFrames[idx % SpeechFrames.size]
                SoundKind.Beep -> BeepFrames[minOf(idx, BeepFrames.size - 1)]
                SoundKind.Shutter -> ShutterFrames[minOf(idx, ShutterFrames.size - 1)]
                else -> ChordFrames[idx % ChordFrames.size]
            }
        }
        return SilenceFrame
    }

    /** Plays scheduled events and advances virtual time by [ms]. */
    suspend fun run(ms: Int) {
        val end = clock.nowMs + ms
        while (clock.nowMs < end) {
            clock.advance(20)
            val now = clock.nowMs
            while (events.isNotEmpty() && events.firstKey() <= now) {
                val actions = events.remove(events.firstKey())!!
                for (a in actions) a()
            }
            if (now % audioFrameEveryMs == 0L) {
                val f = frameAt(now)
                // Synthetic sounds keep the fixed level they always had; PCM fixtures report their real RMS level.
                e.onAudioFrame(f, if (f === SilenceFrame) -100.0 else if (pcmFrames.contains(f)) PcmConverter.levelDb(f) else -30.0)
                sounds.removeAll { x -> x.to < now - 60_000 }
            }
            e.tick()
            // Let background answer work (hold release, streaming) run in real time while an answer is in flight.
            if (inFlight > 0) delay(1)
        }
    }

    /** Waits (virtual + real time) until no answer is in flight. */
    suspend fun settle(maxMs: Int = 10_000) {
        var i = 0
        while (i < maxMs / 20 && (inFlight > 0 || events.isNotEmpty())) {
            run(20)
            i++
        }
        var j = 0
        while (j < 200 && inFlight > 0) {
            delay(5)
            j++
        }
    }

    companion object {
        private val SpeechFrames: Array<ByteArray> = SyntheticAudio.frames(SyntheticAudio.speech(2000, 135.0)).toTypedArray()
        private val BeepFrames: Array<ByteArray> = SyntheticAudio.frames(SyntheticAudio.beep(600, 880.0)).toTypedArray()
        private val ShutterFrames: Array<ByteArray> =
            SyntheticAudio.frames(SyntheticAudio.concat(SyntheticAudio.shutter(), SyntheticAudio.silence(250))).toTypedArray()
        private val ChordFrames: Array<ByteArray> = SyntheticAudio.frames(SyntheticAudio.chord(2000)).toTypedArray()
        private val SilenceFrame: ByteArray = ByteArray(4800)
        private const val Rate24kFrameBytes: Int = 4800 // 100 ms of 24 kHz mono PCM16, the capture frame size
    }
}
