package com.interviewassistant.core.orchestration

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.ConversationTurn
import com.interviewassistant.core.intelligence.EvidenceIndex
import com.interviewassistant.core.intelligence.PromptBuilder
import com.interviewassistant.core.intelligence.QuestionClassifier
import com.interviewassistant.core.intelligence.RetrievedContext
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.knowledge.PromptTemplates
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/EngineAcceptanceTests.cs`.
 *
 * Release-candidate acceptance tests (A–I) at engine level. Service-side parts of B/G/H (no lease for typed questions,
 * no billing while paused, new lease on resume, context on reconnect) are in the backend LiveContextAndManualAnswerTests.
 */
class EngineAcceptanceTests {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }

        private val PastOwnership = dotNetRegex(
            "\\bI(?:'ve|\\s+have)?\\s+(built|implemented|led|owned|designed|launched|created|developed|ran)\\b",
            ignoreCase = true,
        )
        private const val QuestionB =
            "Imagine XAB suddenly has strong token distribution but very low real utility and users mostly sell the token immediately. What would you prioritize in your first 30 days as Product Owner, and how would you measure whether your changes are working?"
        private const val QuestionE = "Have you personally built a closed-loop internal token ledger?"
    }

    private suspend fun sim(o: EngineOptions? = null): InterviewSimulator {
        val s = InterviewSimulator(options = o)
        s.start()
        return s
    }

    /** A: one correct question, one 3-bullet answer, no duplicate. */
    @Test
    fun a_CryptoBackgroundQuestionFromAudio() = runBlocking {
        val s = sim()
        s.say("Can you tell me about your experience in cryptocurrency and how it prepared you for this Product Owner role?")
        s.run(9000)
        s.settle()
        val a = s.visible.single()
        assertEquals(1, s.e.history.size)
        assertEquals("Can you tell me about your experience in cryptocurrency and how it prepared you for this Product Owner role?", a.question)
        assertEquals(3, a.snapshotBullets().size)
        assertEquals(0, s.e.metrics.duplicatesSuppressed)
    }

    /** B (engine): a novel typed question while paused is answered by AI, in hypothetical language. */
    @Test
    fun b_NovelTypedQuestionWhilePausedUsesAiWithHypotheticalLanguage() = runBlocking {
        val s = sim()
        s.p.responder = {
            arrayOf(
                "MODE: HYPOTHETICAL\n",
                "• I'd first find out why holders sell, by cohort and by reward source.\n",
                "• Then I'd add real spending utility, like fee discounts and VIP benefits, before more distribution.\n",
                "• I'd measure 30-day holding rate, utility usage per holder and net sell pressure.\n",
            )
        }
        s.e.pause()
        assertEquals(EngineStatus.Paused, s.e.status)
        s.e.submitManualQuestion(QuestionB)
        val a = s.e.current!!
        assertTrue(a.isComplete)
        assertEquals(AnswerSource.Llm, a.source)
        assertEquals(1, s.p.requests.get())
        assertEquals(3, a.snapshotBullets().size)
        for (b in a.snapshotBullets()) assertFalse(b, PastOwnership.containsMatchIn(b))
        assertTrue(a.snapshotBullets()[0].startsWith("I'd"))
        assertEquals(AnswerMode.Hypothetical, a.mode)
        assertEquals(EngineStatus.Paused, s.e.status) // still paused (no live session was started)
        assertFalse(s.e.isListening)
    }

    /** E: direct honesty — a fabricated "yes" from the model is never shown as history. */
    @Test
    fun e_DirectExperienceQuestionNeverClaimsUnsupportedHistory() = runBlocking {
        val s = sim(EngineOptions().apply { autoTick = false; useFastCache = false }) // force the AI path
        s.p.responder = {
            arrayOf(
                "MODE: VERIFIED\n",
                "• I built a closed-loop internal token ledger at Teroxx in 2021.\n",
                "• I led the reconciliation design with Engineering and Finance.\n",
                "• It reduced settlement errors by 40% within six months.\n",
            )
        }
        s.e.submitManualQuestion(QuestionE)
        val a = s.e.current!!
        val bullets = a.snapshotBullets()
        assertEquals(AnswerMode.Bridge, a.mode)
        assertEquals(InterviewEngine.HonestBridge, bullets[0]) // honest bridge first, always
        for (b in bullets) assertFalse(b, PastOwnership.containsMatchIn(b))
        assertFalse(bullets.any { b -> b.contains("40%") || b.contains("2021") })
        val prompt = s.p.calls.single().last().content
        assertTrue(prompt.contains("REQUIRED MODE: BRIDGE"))
    }

    @Test
    fun e_WellBehavedBridgeAnswerIsShownAsIs() = runBlocking {
        val s = sim(EngineOptions().apply { autoTick = false; useFastCache = false })
        val model = listOf(
            "I haven't owned a closed-loop token ledger end-to-end, but my exchange operations work is closely related.",
            "I'd start with double-entry accounts, idempotent transaction states and daily reconciliation.",
            "I'd measure success by zero unexplained balance differences and fast, audited corrections.",
        )
        s.p.responder = { arrayOf("MODE: BRIDGE\n") + model.map { m -> "• $m\n" }.toTypedArray() }
        s.e.submitManualQuestion(QuestionE)
        assertEquals(model, s.e.current!!.snapshotBullets())
    }

    @Test
    fun e_PreparedAnswerPathIsHonestToo() = runBlocking {
        val s = sim()
        s.e.submitManualQuestion(QuestionE)
        val a = s.e.current!!
        assertEquals(AnswerSource.Cache, a.source)
        assertTrue(Regex("^I haven't").containsMatchIn(a.snapshotBullets()[0]))
        for (b in a.snapshotBullets()) assertFalse(b, PastOwnership.containsMatchIn(b))
        assertEquals(0, s.p.requests.get())
    }

    /** F: a hypothetical design question gets a confident expert answer — no unnecessary disclaimer. */
    @Test
    fun f_HypotheticalDesignQuestionIsConfidentWithoutDisclaimer() = runBlocking {
        val s = sim(EngineOptions().apply { autoTick = false; useFastCache = false })
        s.p.responder = {
            arrayOf(
                "MODE: HYPOTHETICAL\n",
                "• I'd design it as a double-entry ledger where every token movement has a matching debit and credit.\n",
                "• I'd make every transaction idempotent with clear states, so retries never double-credit.\n",
                "• I'd reconcile daily against supply and treasury, with alerts on any mismatch.\n",
            )
        }
        s.e.submitManualQuestion("How would you design a closed-loop internal token ledger?")
        val a = s.e.current!!
        assertEquals(AnswerMode.Hypothetical, a.mode)
        for (b in a.snapshotBullets()) assertTrue(b, b.startsWith("I'd"))
        assertFalse(a.snapshotBullets().any { b -> b.contains("haven't", ignoreCase = true) })
        val prompt = s.p.calls.single().last().content
        assertFalse(prompt.contains("REQUIRED MODE: BRIDGE"))
        assertTrue(prompt.contains("SUGGESTED MODE: HYPOTHETICAL"))
    }

    /** The direct-experience decision is made from the user's confirmed evidence, not a fixed topic list. */
    @Test
    fun directExperienceIsVerifiedOnlyWhenEvidenceSupportsIt() {
        val kb = Fixtures.testKnowledge()
        val ev = EvidenceIndex.`for`(kb)
        assertEquals(AnswerMode.Bridge, QuestionClassifier.classify(QuestionE, kb.context, ev).mode)
        assertEquals(AnswerMode.Verified, QuestionClassifier.classify("Have you worked with Jira?", kb.context, ev).mode) // confirmed skill
        assertEquals(AnswerMode.Verified, QuestionClassifier.classify("Did you grow Arzif's active traders?", kb.context, ev).mode)
        assertEquals(AnswerMode.Hypothetical, QuestionClassifier.classify("How would you design a closed-loop internal token ledger?", kb.context, ev).mode)
        // A user with no confirmed history is never "verified".
        val empty = EvidenceIndex(CandidateProfile(), listOf())
        assertEquals(AnswerMode.Bridge, QuestionClassifier.classify("Have you worked with Jira?", kb.context, empty).mode)
    }

    /** Prompt for one user contains only that user's confirmed facts (no other candidate's education/languages). */
    @Test
    fun promptContainsOnlyThisUsersConfirmedFacts() {
        val other = KnowledgeBase(
            profile = CandidateProfile(name = "Jane Example", headline = "Product Manager"),
            stories = listOf(),
            questions = listOf(),
            snippets = listOf(),
            systemPromptTemplate = PromptTemplates.LiveAnswer,
        )
        val msgs = PromptBuilder(other).build(
            "Tell me about your education.", QuestionClassifier.classify("Tell me about your education."),
            RetrievedContext(listOf(), listOf(), null), AnswerStyle.Balanced, listOf<ConversationTurn>(),
        )
        val all = msgs.joinToString("\n") { m -> m.content }
        assertFalse(all.contains("Tehran"))
        assertFalse(all.contains("MBA"))
        assertFalse(all.contains("Lithuanian"))
        assertTrue(all.contains("No confirmed work history"))
    }

    /** H: pause, then resume — same interview context, no stale text, no duplicate turn. */
    @Test
    fun h_ResumeAfterPauseContinuesCleanly() = runBlocking {
        val s = sim()
        s.say("How do you prioritize the backlog?")
        s.run(6000)
        s.settle()
        s.e.pause()
        // Interviewer audio during the pause is ignored (not listening, nothing billed).
        s.t.start()
        s.t.partial("x1", "this should be ignored")
        s.t.stop()
        s.t.complete("This should be ignored while paused?", "x1")
        s.run(3000)
        assertEquals(1, s.e.history.size)
        s.e.startListening()
        assertEquals("", s.e.turn.currentText()) // no stale partial text
        s.say("How do you balance technical debt against new features?")
        s.run(7000)
        s.settle()
        assertEquals(2, s.e.history.size)
        assertEquals(2, s.visible.size)
        assertEquals(s.e.knowledge.context.companyName, Fixtures.testKnowledge().context.companyName) // same interview pack
        assertEquals(0, s.e.metrics.duplicatesSuppressed)
    }

    /** I: backend AI failure — safe prepared answer when available, otherwise an actionable message (never "Unknown"). */
    @Test
    fun i_AiFailureUsesSafePreparedAnswerOrActionableMessage() = runBlocking {
        val s = sim(EngineOptions().apply { autoTick = false; useFastCache = false; developerKeyMode = false })
        s.p.throwEx = ProviderException(ProviderErrorKind.ServiceUnavailable, "503").apply { code = "ai_unavailable" }
        s.e.submitManualQuestion("How would you increase XAB adoption?") // prepared answer exists
        val known = s.e.current!!
        assertEquals(AnswerSource.CacheFallback, known.source)
        assertEquals(3, known.snapshotBullets().size)
        assertTrue(known.note!!.startsWith("Prepared answer shown"))
        assertFalse(known.note!!.contains("Unknown"))

        s.e.submitManualQuestion("How would you design a quantum-safe custody roadmap for 2030?") // nothing prepared
        val novel = s.e.current!!
        assertEquals(AnswerSource.Error, novel.source)
        assertEquals(ProviderErrorKind.ServiceUnavailable, novel.error)
        assertEquals("Service temporarily unavailable. Prepared answers still work.", novel.note)
    }

    @Test
    fun knownServiceErrorsShowSpecificText() = runBlocking {
        val cases = listOf(
            ProviderErrorKind.SessionEnded to "Your live session has ended. Press Resume.",
            ProviderErrorKind.QuotaExceeded to "Daily answer limit reached. Prepared answers still work.",
            ProviderErrorKind.NotSignedIn to "Please sign in again (Home → Account).",
            ProviderErrorKind.DeviceRevoked to "This device is no longer registered. Sign in again in Home → Account.",
            ProviderErrorKind.PlanRequired to "AI answers need an active plan. Upgrade in Home → Account. Prepared answers still work.",
        )
        for ((kind, expected) in cases) {
            val s = sim(EngineOptions().apply { autoTick = false; useFastCache = false; developerKeyMode = false })
            s.p.throwEx = ProviderException(kind, "x")
            s.e.submitManualQuestion("How would you design a quantum-safe custody roadmap for 2030?")
            assertEquals(kind.name, expected, s.e.current!!.note)
        }
    }

    /** D (client side): the interview's names reach the transcription context; aliases fix known misrecognitions. */
    @Test
    fun d_InterviewTermsAreSentForTranscriptionAndAliasesNormalizeText() {
        val kb = Fixtures.testKnowledge()
        val terms = kb.context.transcriptionTerms()
        for (t in listOf("Teroxx", "XAB", "Abloxx")) assertTrue(t, terms.contains(t))
        assertTrue(terms.size <= 80)
        for (t in terms) assertTrue(t, t.length <= 48)
        val cleaned = TextNormalizer.cleanTranscript("how would you improve terox's ab locks token", kb.context.aliases)
        assertTrue(cleaned.contains("Teroxx"))
        assertTrue(cleaned.contains("Abloxx"))
    }
}
