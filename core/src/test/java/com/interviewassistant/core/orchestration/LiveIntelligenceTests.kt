package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.diagnostics.ManualClock
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.live.AdaptiveLevel
import com.interviewassistant.core.live.CoachOutput
import com.interviewassistant.core.live.InterruptionTracker
import com.interviewassistant.core.live.Presentation
import com.interviewassistant.core.live.QuestionRecord
import com.interviewassistant.core.live.SessionMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/LiveIntelligenceTests.cs` — the engine cases deferred BY NAME
 * from Package B (P3-PORT-NOTES.md); the live-layer unit cases of that file live in live/.
 */
class LiveIntelligenceTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    private data class Rig(val e: InterviewEngine, val p: FakeProvider, val c: ManualClock)

    private fun create(provider: Boolean = true): Rig {
        val c = ManualClock()
        val p = FakeProvider()
        val e = InterviewEngine(Fixtures.testKnowledge(), null, if (provider) p else null, EngineOptions().apply { autoTick = false }, c)
        return Rig(e, p, c)
    }

    // ---------------- Coach Mode ----------------

    @Test
    fun coachModeFromCacheIsImmediateAndHonoursContract() = runBlocking {
        val (e, p, _) = create()
        e.presentation = Presentation.Coach
        e.submitManualQuestion("How would you prioritize improvements to the XAB rewards ecosystem?")
        val a = e.current!!
        assertNotNull(a.coach)
        assertTrue(a.coach!!.isValid)
        assertEquals(3, a.coach!!.keywords.size)
        assertTrue(a.coach!!.structure.contains("→"))
        assertEquals(2, a.bullets.size) // keyword line + structure line only
        assertEquals(0, p.requests.get()) // no network
        assertTrue(e.metrics.samples.last().finalizedToFirstBulletMs in 0..5)
    }

    @Test
    fun coachModeViaAiParsesContractAndFallsBackWhenMalformed() = runBlocking {
        val (e, p, _) = create()
        e.presentation = Presentation.Coach
        p.responder = {
            arrayOf(
                "KEYWORDS: PRICING · BUNDLE · RETENTION\n",
                "STRUCTURE: Direct answer -> pricing logic -> measure\n",
                "REMINDER: keep it under 20 seconds",
            )
        }
        e.submitManualQuestion("How would you price a premium tier bundled with partner benefits?")
        assertEquals(listOf("PRICING", "BUNDLE", "RETENTION"), e.current!!.coach!!.keywords)
        assertEquals("Direct answer → pricing logic → measure", e.current!!.coach!!.structure)
        assertEquals("keep it under 20 seconds", e.current!!.coach!!.reminder)
        assertTrue(p.calls[0][0].content.contains("KEYWORDS"))

        p.responder = { arrayOf("Sure! Here are some ideas about pricing.") }
        e.submitManualQuestion("How would you design onboarding for enterprise customers?")
        assertTrue(e.current!!.coach!!.isValid)
        assertTrue(e.current!!.validationFlags.any { f -> f.startsWith("COACH_FORMAT") })
    }

    @Test
    fun coachToggleSwitchesModes() {
        val (e, _, _) = create()
        assertEquals(Presentation.Coach, e.togglePresentation())
        assertEquals(Presentation.Answer, e.togglePresentation())
    }

    @Test
    fun coachParserWorksForAnyScript() {
        val cases = listOf(
            "KEYWORDS: کاربرد · ماندگاری مشتری · شاخص‌ها\nSTRUCTURE: پاسخ مستقیم → رویکرد → اندازه‌گیری" to "کاربرد",
            "KEYWORDS: utilité · rétention · métriques\nSTRUCTURE: réponse directe → approche → mesure" to "UTILITÉ",
            "KEYWORDS: 效用 · 留存 · 指标\nSTRUCTURE: 直接回答 → 方法 → 衡量" to "效用",
        )
        for ((text, firstKeyword) in cases) {
            val c = CoachOutput.parse(text)!!
            assertTrue(c.isValid)
            assertEquals(firstKeyword, c.keywords[0])
        }
    }

    // ---------------- Memory, follow-ups, repetition ----------------

    @Test
    fun followUpsAreLinkedAndMemoryReachesThePrompt() = runBlocking {
        val (e, p, _) = create()
        e.submitManualQuestion("Tell me about your crypto experience.")
        e.submitManualQuestion("Why?")
        val q2 = e.memory.questions[1]
        assertEquals(1, q2.followUpOf)
        val prompt = p.calls.last()[1].content
        assertTrue(prompt.contains("INTERVIEW MEMORY"))
        assertTrue(prompt.contains("crypto experience"))
        assertTrue(prompt.contains("FOLLOW-UP"))
    }

    @Test
    fun repeatedStoryIsAvoidedAndAlreadyMentionedIsRecognised() = runBlocking {
        val (e, p, _) = create()
        e.submitManualQuestion("How did you grow Arzif to 60,000 active traders?")
        assertEquals(AnswerSource.Cache, e.current!!.source)
        assertTrue(e.memory.storyUse.isNotEmpty())
        // Same stories again → engine prefers a fresh AI answer and tells the model to avoid them.
        e.submitManualQuestion("Tell me about your crypto experience.")
        assertEquals(AnswerSource.Llm, e.current!!.source)
        assertTrue(p.calls.last()[1].content.contains("Avoid re-using the stories"))
        assertTrue(p.calls.last()[1].content.contains("Stories already used"))
        e.submitManualQuestion("You already mentioned the 60,000 traders — give me another example of growth.")
        assertTrue(e.memory.last!!.referencesEarlierAnswer)
        assertTrue(p.calls.last()[1].content.contains("do NOT repeat it"))
        assertTrue(p.calls.last()[1].content.contains("Metrics already mentioned"))
    }

    @Test
    fun storyPenaltyRanksLeastUsedFirst() {
        val m = SessionMemory()
        m.add(QuestionRecord(original = "q1").apply { storyIds.add("a") })
        m.add(QuestionRecord(original = "q2").apply { storyIds.add("a") })
        m.add(QuestionRecord(original = "q3").apply { storyIds.add("b") })
        assertTrue(m.storyPenalty("a") > m.storyPenalty("b"))
        assertEquals(0.0, m.storyPenalty("c"), 0.0)
        assertTrue(m.recentlyUsedStories().contains("a"))
    }

    @Test
    fun memoryStaysBoundedOverLongInterviewAndSerializes() {
        val m = SessionMemory()
        for (i in 0 until 1000) {
            m.add(
                QuestionRecord(original = "Question number $i about topic ${i % 7}", category = "CAT" + (i % 5))
                    .apply { answerBullets.addAll(listOf("x".repeat(200), "b", "c")) },
            )
        }
        assertTrue(m.questions.size <= m.maxRecords)
        assertTrue(m.questions.count { q -> !q.truncated } <= m.maxDetailedRecords)
        assertTrue(m.rollingSummary.contains("earlier questions"))
        val ctx = m.buildPromptContext()
        assertTrue("prompt memory ${ctx.length} chars", ctx.length < 3000)
        val back = SessionMemory.fromJson(m.toJson())
        assertEquals(m.questions.size, back.questions.size)
        assertEquals(m.rollingSummary, back.rollingSummary)
    }

    @Test
    fun memorySurvivesRestoreAfterDisconnect() = runBlocking {
        val (e1, _, _) = create()
        e1.submitManualQuestion("How did you grow Arzif to 60,000 active traders?")
        val json = e1.memory.toJson()
        val (e2, p2, _) = create()
        e2.restoreMemory(SessionMemory.fromJson(json))
        e2.submitManualQuestion("And what happened next?")
        assertEquals(2, e2.memory.last!!.index)
        assertTrue(p2.calls.last()[1].content.contains("60,000"))
    }

    // ---------------- Interruption adaptation ----------------

    /** Asks a heard question and lets the answer appear (realistic VAD timing + speech audio). */
    private suspend fun askAsync(s: InterviewSimulator, q: String) {
        s.say(q)
        s.run(q.split(" ").size * s.msPerWord + 4500) // beyond the 2.5 s continuation window
        s.settle()
    }

    @Test
    fun interruptionsShortenAnswersAndResetRestoresNormal() = runBlocking {
        // RC2: pace adapts to HEARD interviewer questions that cut in on a long answer (not to typed questions or beeps).
        val s = InterviewSimulator()
        s.start()
        val levels = mutableListOf<AdaptiveLevel>()
        s.e.adaptiveChangedListeners.add { l -> levels.add(l) }
        askAsync(s, "How would you price a premium tier bundled with partner benefits?")
        askAsync(s, "How would you design onboarding for enterprise customers?") // asked ~1 s after a ~20 s answer appeared
        assertEquals(AdaptiveLevel.Concise, s.e.adaptive)
        assertEquals(1, s.e.memory.interruptions.size)
        assertTrue(s.e.memory.interruptions[0].confidence >= 0.7)
        assertEquals(AnswerStyle.Concise, s.e.current!!.style)
        assertTrue(s.p.calls.last()[0].content.contains("moving fast"))

        askAsync(s, "How would you measure partner channel profitability?")
        assertEquals(AdaptiveLevel.Rapid, s.e.adaptive)
        askAsync(s, "How would you decide which partner benefits to retire first?")
        assertEquals(AnswerStyle.Rapid, s.e.current!!.style)
        assertTrue(s.p.calls.last()[0].content.contains("bullet 1 must directly answer"))

        s.e.resetAdaptive()
        assertEquals(AdaptiveLevel.Normal, s.e.adaptive)
        assertEquals(listOf(AdaptiveLevel.Concise, AdaptiveLevel.Rapid, AdaptiveLevel.Normal), levels)
    }

    @Test
    fun typedQuestionsAndBareSpeechStartsNeverChangeThePace() = runBlocking {
        // RC1 bug: repeated manual tests (typed questions) and any speech_started (even a beep) switched to Concise/Rapid.
        val (e, _, c) = create()
        for (i in 0 until 4) {
            e.submitManualQuestion("How would you price premium tier number $i for partners?")
            c.advance(800)
            e.onInterviewerSpeechStarted()
        }
        assertEquals(AdaptiveLevel.Normal, e.adaptive)
        assertTrue(e.memory.interruptions.isEmpty())
    }

    @Test
    fun beepsAndVariantsNeverChangeThePace() = runBlocking {
        val s = InterviewSimulator()
        s.start()
        askAsync(s, "How would you price a premium tier bundled with partner benefits?")
        // A notification beep right after the answer appears, "transcribed" as the vocabulary prompt.
        s.sound(InterviewSimulator.SoundKind.Beep, "Job interview for a Product Owner role at Teroxx. Terms: XAB, Abloxx, Jira, CRM, KPIs.")
        s.run(2500)
        s.settle()
        s.e.requestVariant(s.e.current!!.id, AnswerStyle.Technical)
        s.e.requestVariant(s.e.current!!.id, AnswerStyle.VeryShort)
        assertEquals(AdaptiveLevel.Normal, s.e.adaptive)
        assertTrue(s.e.memory.interruptions.isEmpty())
        assertEquals(1, s.e.memory.questions.size)
    }

    @Test
    fun calmPaceIsNotAnInterruptionAndAdaptiveDecays() {
        val t = InterruptionTracker()
        t.answerShown(0, 50, 1)
        assertNull(t.interviewerSpeechStarted(25_000)) // waited long enough
        t.answerShown(30_000, 50, 2)
        assertNotNull(t.interviewerSpeechStarted(31_000)) // fast follow
        assertEquals(AdaptiveLevel.Concise, t.level)
        for (i in 0 until 3) {
            t.answerShown(100_000 + i * 60_000L, 40, 3 + i)
            t.interviewerSpeechStarted(100_000 + i * 60_000L + 40_000)
        }
        assertEquals(AdaptiveLevel.Normal, t.level)
    }

    @Test
    fun rapidModeTrimsCachedAnswersToTwoBullets() = runBlocking {
        val s = InterviewSimulator(withProvider = false)
        s.start()
        askAsync(s, "How would you increase XAB adoption?")
        askAsync(s, "How would you design VIP tier governance?")
        askAsync(s, "How would you prioritize improvements to the XAB rewards ecosystem?")
        assertEquals(AdaptiveLevel.Rapid, s.e.adaptive)
        askAsync(s, "What would you do in your first 90 days?")
        assertEquals(2, s.e.current!!.bullets.size)
    }

    // ---------------- Multilingual ----------------

    @Test
    fun answersFollowTheInterviewerLanguageAndMemorySurvivesSwitches() = runBlocking {
        val (e, p, _) = create()
        e.submitManualQuestion("How would you prioritize the product backlog?")
        assertEquals("en", e.current!!.answerLanguage)
        assertEquals(AnswerSource.Cache, e.current!!.source)
        e.submitManualQuestion("Und wie würden Sie das mit den Stakeholdern abstimmen?")
        assertEquals("de", e.current!!.detectedLanguage)
        assertEquals("de", e.current!!.answerLanguage)
        assertEquals(AnswerSource.Llm, e.current!!.source) // English prepared answers are never shown for a German question
        val call = p.calls.last()
        assertTrue(call[0].content.contains("German"))
        assertTrue(call[1].content.contains("prioritize the product backlog")) // English context retained
        assertEquals(2, e.memory.questions.size)
    }

    @Test
    fun crossLanguageAnswerOverride() = runBlocking {
        val (e, p, _) = create()
        e.answerLanguageOverride = "fa"
        e.submitManualQuestion("How would you prioritize the product backlog?")
        assertEquals("en", e.current!!.detectedLanguage)
        assertEquals("fa", e.current!!.answerLanguage)
        assertEquals(AnswerSource.Llm, e.current!!.source)
        assertTrue(p.calls.last()[0].content.contains("Persian"))
    }

    @Test
    fun alreadyMentionedDetectedAcrossLanguages() {
        for (q in listOf(
            "Usted ya mencionó ese ejemplo, ¿tiene otro?",
            "Vous avez déjà mentionné cela.",
            "Sie haben bereits erwähnt, dass Sie das Team geführt haben.",
            "你已经提到过这个例子了",
            "قبلاً گفتید که تیم را رهبری کردید",
        )) assertTrue(q, SessionMemory.referencesEarlier(q))
    }
}
