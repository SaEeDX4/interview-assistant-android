package com.interviewassistant.core.reports

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.diagnostics.ManualClock
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.orchestration.EngineOptions
import com.interviewassistant.core.orchestration.FakeProvider
import com.interviewassistant.core.orchestration.InterviewEngine
import com.interviewassistant.core.orchestration.InterviewSimulator
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/ReportTests.cs`. */
class ReportTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    private fun runSession(reportLang: String = "en"): Pair<InterviewEngine, InterviewReport> = runBlocking {
        val pack = Fixtures.shervinPack()
        val s = InterviewSimulator(pack.toKnowledgeBase())
        val e = s.e
        e.startListening()
        // Heard questions; the second cuts in right after the first answer appeared (a likely interruption).
        s.say("Tell me about yourself.")
        s.run(6000)
        s.settle()
        s.say("Tell me about your experience at Arzif Crypto Exchange.")
        s.run(7000)
        s.settle()
        e.submitManualQuestion("Have you personally worked with building or owning financial ledgers?")
        e.submitManualQuestion("Und wie würden Sie das mit den Stakeholdern abstimmen?")
        e.submitManualQuestion("<script>alert('x')</script> Why?")
        val r = ReportBuilder.build(
            e.memory, e.knowledge, e.metrics, "s1",
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30), OffsetDateTime.now(ZoneOffset.UTC),
            reportLang, pack, reconnects = 1, profileLabel = "Shervin", targetLabel = "PO · Teroxx",
        )
        Pair(e, r)
    }

    @Test
    fun reportContainsRequiredSectionsAndHonestyLabel() {
        val (_, r) = runSession()
        assertEquals(5, r.questions.size)
        assertFalse(r.candidateMicrophoneEnabled)
        assertTrue(r.gapQuestions.any { g -> g.contains("ledgers") })
        assertTrue(r.difficultyAreas.any { d -> d.startsWith("Experience gap") })
        assertEquals(1, r.interruptions.size)
        assertTrue(r.languagesUsed.contains("de"))
        assertTrue(r.storiesRecommended.isNotEmpty())
        assertTrue(r.topicsNotCovered.isNotEmpty())
        assertTrue(r.transcriptionIssues.any { t -> t.contains("reconnect") })
        assertTrue(r.latencyP50Ms >= 0)
        assertTrue(r.thankYouThemes.isNotEmpty())
        val html = HtmlReportRenderer.render(r)
        assertTrue(html.contains("microphone was OFF"))
        assertTrue(html.contains("not an assessment"))
        assertFalse(html.lowercase().contains("success probability"))
        assertTrue(html.contains("(Inference)"))
    }

    @Test
    fun htmlEscapesInterviewerContent() {
        val (_, r) = runSession()
        val html = HtmlReportRenderer.render(r)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test
    fun reportCanBeGeneratedInAnotherLanguageWithRtl() {
        val cases = listOf(
            Triple("fa", "rtl", "گزارش مصاحبه"),
            Triple("ar", "rtl", "تقرير المقابلة"),
            Triple("de", "ltr", "Interviewbericht"),
            Triple("zh", "ltr", "面试报告"),
        )
        for ((lang, dir, title) in cases) {
            val (_, r) = runSession(lang)
            val html = HtmlReportRenderer.render(r)
            assertTrue(lang, html.contains("dir=\"$dir\""))
            assertTrue(lang, html.contains(title))
            assertTrue(lang, html.contains("Tell me about yourself.")) // original wording preserved
            assertTrue(lang, html.contains("<bdi")) // mixed-direction content isolated
        }
    }

    @Test
    fun reportLabelsAreCompleteForAllTenLanguages() {
        for (l in LanguageRegistry.all) assertTrue(l.code, ReportLabels.isComplete(l.code))
    }

    @Test
    fun reportSerializesForStorageAndExport() {
        val (_, r) = runSession()
        val json = r.toJson()
        assertTrue(json.contains("\"Questions\""))
        assertTrue(json.contains("CandidateMicrophoneEnabled"))
    }

    @Test
    fun storedReportRoundTripsToIdenticalHtml() {
        val (_, r) = runSession("fa")
        val back = ReportSerializer.fromJson(r.toJson())
        assertNotNull(back)
        assertEquals(r.questions.size, back!!.questions.size)
        assertEquals(HtmlReportRenderer.render(r), HtmlReportRenderer.render(back))
        assertNull(ReportSerializer.fromJson("{not json"))
        assertNull(ReportSerializer.fromJson(""))
    }
}

class Rc2ReportTests {

    @Test
    fun headerNeverRepeatsTheCandidateName() {
        assertEquals("Shervin · Product Owner · Teroxx", HtmlReportRenderer.header("Shervin", "Shervin · Product Owner · Teroxx"))
        assertEquals("Shervin Fallahdoust · PO · Teroxx", HtmlReportRenderer.header("Shervin Fallahdoust", "Shervin · PO · Teroxx"))
        assertEquals("Maria · PO", HtmlReportRenderer.header("", "Maria · PO"))
    }

    @Test
    fun echoRecordsNeverCountAndCopyTextHasSeparators() = runBlocking {
        val kb = Fixtures.testKnowledge()
        val e = InterviewEngine(kb, null, FakeProvider(), EngineOptions().apply { autoTick = false }, ManualClock())
        e.submitManualQuestion("How would you design a closed-loop internal token ledger?")
        // A record that slipped in from an older build (RC1 stored prompt echoes as questions): never counted.
        e.memory.add(QuestionRecord(original = kb.context.buildTranscriptionPrompt(), category = "MICAR", mode = "Verified"))
        val r = ReportBuilder.build(
            e.memory, kb, e.metrics, "s",
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5), OffsetDateTime.now(ZoneOffset.UTC),
            "en", null, profileLabel = "Shervin", targetLabel = "Shervin · PO · Teroxx",
        )
        assertEquals(1, r.questions.size)
        assertFalse(r.categoryCounts.keys.contains("MICAR"))
        assertEquals(1, r.excludedTurns)
        val html = HtmlReportRenderer.render(r)
        assertTrue(html.contains("<h3>Q1. "))
        assertTrue(html.contains("Category: "))
        assertTrue(html.contains("Difficulty areas <span"))
        assertFalse(html.contains("Shervin · Shervin"))
        val text = HtmlReportRenderer.renderText(r)
        assertTrue(text.contains("Q1. How would you design a closed-loop internal token ledger?"))
        assertTrue(text.contains("microphone was OFF"))
    }

    @Test
    fun allReportLanguagesHaveEveryLabel() {
        for (l in ReportLabels.languages) assertTrue(l, ReportLabels.isComplete(l))
    }
}
