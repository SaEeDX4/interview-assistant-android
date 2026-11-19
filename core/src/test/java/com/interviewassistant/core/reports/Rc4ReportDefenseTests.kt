package com.interviewassistant.core.reports

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.intelligence.SpeechPlausibility
import com.interviewassistant.core.live.HeardEvidence
import com.interviewassistant.core.live.InterruptionEvent
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.live.SessionMemory
import com.interviewassistant.core.orchestration.EngineOptions
import com.interviewassistant.core.orchestration.InterviewSimulator
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/Rc4ReportDefenseTests.cs`.
 *
 * RC4 report defence in depth: the engine is the first line (non-speech never becomes a question), the report is the
 * second — a heard question whose recorded audio evidence fails the acoustic checks is listed separately for review and
 * excluded from every statistic. Never silently removed, and real questions are never touched.
 */
class Rc4ReportDefenseTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        // What the RC3 failure looked like: a low alert tone (steady pitch) heard as an interview question.
        private val AlertTone = SpeechEvidence(18, 1800, 600, 400, 0, pitchPairs = 17, steadyPairs = 14, jumpPairs = 2, windowMs = 1800)
        private val RealQuestion = SpeechEvidence(34, 3400, 2100, 1600, 0, pitchPairs = 48, steadyPairs = 14, jumpPairs = 15, windowMs = 3400)
    }

    private fun build(m: SessionMemory): InterviewReport =
        ReportBuilder.build(
            m, Fixtures.testKnowledge(), null, "s",
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20), OffsetDateTime.now(ZoneOffset.UTC), "en",
        )

    private fun heard(text: String, e: SpeechEvidence, category: String = "BEHAVIORAL"): QuestionRecord =
        QuestionRecord(
            original = text, category = category, mode = "Verified", source = "Llm", firstBulletLatencyMs = 900,
            heard = HeardEvidence.from(e, SpeechPlausibility.assess(text, e)),
        )

    @Test
    fun heardQuestionWithoutAVoice_IsListedSeparately_AndExcludedFromStatistics() {
        val m = SessionMemory()
        m.add(heard("What would you do in your first ninety days?", RealQuestion, "PLANNING"))
        m.add(heard("What is your experience with FinTech products?", AlertTone, "FINTECH"))
        m.add(heard("How do you prioritise a backlog?", RealQuestion, "PRIORITIZATION"))
        val r = build(m)

        assertEquals(2, r.questions.size)
        assertFalse(r.questions.any { q -> q.originalWording.contains("FinTech") })
        val u = r.uncertainQuestions.single()
        assertEquals(2, u.index) // keeps its live number: the gap in Q1, Q3 is explained
        assertEquals("the audio did not behave like a voice", u.uncertainReason)
        assertFalse(r.categoryCounts.keys.contains("FINTECH")) // analytics are not contaminated
        assertEquals(2, r.categoryCounts.values.sum())

        val html = HtmlReportRenderer.render(r)
        assertTrue(html.contains("Possibly not interviewer speech (not counted) (1)"))
        assertTrue(html.contains("Q2. <bdi dir=\"auto\">What is your experience with FinTech products?</bdi>"))
        assertTrue(html.contains("Questions asked (2)"))
        val text = HtmlReportRenderer.renderText(r)
        assertTrue(text.contains("Possibly not interviewer speech (not counted) (1)"))
    }

    @Test
    fun anInterruptionMeasuredAtADoubtfulQuestion_IsNotReported() {
        val m = SessionMemory()
        m.add(heard("What would you do in your first ninety days?", RealQuestion, "PLANNING"))
        m.add(heard("What is your experience with FinTech products?", AlertTone, "FINTECH"))
        m.interruptions.add(InterruptionEvent(afterQuestionIndex = 1, confidence = 0.8, elapsedMs = 2000, expectedMs = 20000))
        val r = build(m)
        assertTrue(r.interruptions.isEmpty())
    }

    @Test
    fun contextHallucinationOverAShortVoice_IsListedAsSuch() {
        // A voice-like knock (360 ms voiced) cannot have said a 13-syllable interview question.
        val knock = SpeechEvidence(19, 1900, 620, 360, 40, pitchPairs = 11, steadyPairs = 2, jumpPairs = 6, windowMs = 1900)
        val m = SessionMemory()
        m.add(heard("What is your experience with FinTech products?", knock, "FINTECH"))
        val r = build(m)
        assertTrue(r.questions.isEmpty())
        assertEquals(1, r.uncertainQuestions.size)
        assertNotEquals("", r.uncertainQuestions[0].uncertainReason)
    }

    @Test
    fun shortRealFollowUp_StaysAQuestion() {
        for (text in listOf("Why?", "Why.", "How so?", "Metrics?")) {
            val why = SpeechEvidence(15, 1500, 400, 200, 0, pitchPairs = 4, steadyPairs = 1, windowMs = 1500)
            val m = SessionMemory()
            m.add(heard(text, why))
            val r = build(m)
            assertEquals(text, 1, r.questions.size)
            assertTrue(text, r.uncertainQuestions.isEmpty())
        }
    }

    @Test
    fun typedAndLegacyRecords_AreNeverReclassified() {
        val m = SessionMemory()
        m.add(QuestionRecord(original = "What is your experience with FinTech products?", category = "FINTECH", typed = true))
        m.add(QuestionRecord(original = "Tell me about a conflict in your team.", category = "BEHAVIORAL")) // rc.3 record: no evidence kept
        val r = build(m)
        assertEquals(2, r.questions.size)
        assertTrue(r.uncertainQuestions.isEmpty())
    }

    @Test
    fun unheardSegment_AbstainsRatherThanFlagging() {
        // Evidence lost in a reconnect is "unheard": the acoustic checks abstain and the question stays.
        val m = SessionMemory()
        m.add(heard("How would you design a closed-loop internal token ledger?", SpeechEvidence.Unheard))
        assertEquals(1, build(m).questions.size)
    }

    @Test
    fun heardEvidence_RoundTripsThroughJson() {
        val m = SessionMemory()
        m.add(heard("What would you do in your first ninety days?", RealQuestion))
        val json = CoreJson.instance.encodeToString(m.questions[0])
        assertFalse(json.replace("What would you do in your first ninety days?", "").contains("What would you do")) // only the wording itself, no audio
        val back = CoreJson.instance.decodeFromString<QuestionRecord>(json)
        assertEquals(RealQuestion, back.heard!!.toEvidence())
        assertEquals("Strong", back.heard!!.support)
    }

    @Test
    fun engine_RecordsAcousticProvenance_ForHeardQuestionsOnly() = runBlocking {
        val s = InterviewSimulator(options = EngineOptions().apply { autoTick = false })
        s.start()
        s.say("What would you do in your first ninety days?")
        s.run(6000)
        s.settle()
        s.e.submitManualQuestion("How do you handle conflicting stakeholder priorities?")
        s.settle()
        val heard = s.e.memory.questions.single { q -> !q.typed }
        assertNotNull(heard.heard)
        assertTrue(heard.heard!!.voicedMs > 0)
        assertEquals("Strong", heard.heard!!.support)
        assertNull(s.e.memory.questions.single { q -> q.typed }.heard)
        val r = ReportBuilder.build(
            s.e.memory, Fixtures.testKnowledge(), s.e.metrics, "s",
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5), OffsetDateTime.now(ZoneOffset.UTC), "en",
        )
        assertEquals(2, r.questions.size)
        assertTrue(r.uncertainQuestions.isEmpty())
    }
}
