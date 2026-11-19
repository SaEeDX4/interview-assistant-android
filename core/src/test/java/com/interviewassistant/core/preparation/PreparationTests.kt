package com.interviewassistant.core.preparation

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.DocumentKind
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparationState
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.intelligence.FactValidator
import com.interviewassistant.core.intelligence.TextNormalizer
import com.interviewassistant.core.knowledge.AnswerMode
import com.interviewassistant.core.persistence.NoProtection
import com.interviewassistant.core.persistence.WorkspaceStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/PreparationTests.cs` — the pipeline cases.
 * `PackDrivesTheLiveEngineEndToEnd` and `LowConfidenceTemplateAnswersAreReferenceNotInstant` need
 * InterviewEngine (Orchestration) and are deferred by name to Package D in P3-PORT-NOTES.md.
 */
class PreparationTests {

    @Test
    fun preparationRunsAllStagesAndBuildsAdaptiveBank() {
        val p = Fixtures.shervinProfile()
        val t = Fixtures.teroxxTarget(p.id)
        val progress = mutableListOf<PreparationProgress>()
        val pack = runBlocking { PreparationPipeline(Fixtures.assets()).runAsync(p, t, progress = { progress.add(it) }) }
        for (l in pack.stageLog) println(l)
        assertEquals(PreparationPipeline.stages.size, progress.map { it.stage }.distinct().size)
        assertTrue("bank size ${pack.questions.size}", pack.questions.size in 50..150)
        assertEquals("Product Owner - Abloxx (XAB)", t.jobTitle)
        assertEquals("Teroxx", t.company)
        assertEquals(PreparationState.Prepared, t.preparation)
        assertTrue(pack.gaps.any { it.contains("ledger", ignoreCase = true) })
        assertTrue(pack.terminology.any { it == "MiCAR" || it == "MiCA" })
        assertTrue(pack.questionsToAsk.size >= 10)
        for (q in pack.questions) {
            assertEquals(q.canonicalQuestion, 3, q.coachKeywords.size)
            assertTrue(q.canonicalQuestion, q.answerStructure.contains("→"))
            assertTrue(q.canonicalQuestion, q.shortBullets.size in 2..4)
        }
        assertTrue(pack.questions.any { it.mode == AnswerMode.Bridge && it.canonicalQuestion.contains("ledger", ignoreCase = true) })
        assertTrue(pack.questions.any { it.canonicalQuestion == "Tell me about your experience at Arzif Crypto Exchange." })
    }

    @Test
    fun verifiedPreparedAnswersOnlyUseConfirmedFactsAndPassValidator() {
        val pack = Fixtures.shervinPack()
        val v = FactValidator(pack.profile, pack.context)
        val problems = pack.questions.map { q -> q to v.validate(q.shortBullets, q.mode, AnswerStyle.Technical) }
            .filter { it.second.hasFactualIssues }
        for ((q, r) in problems) println("${q.canonicalQuestion}: ${r.issues.joinToString("; ") { "${it.kind} ${it.detail}" }}")
        assertTrue(problems.toString(), problems.isEmpty())
        val intro = pack.questions.single { it.canonicalQuestion == "Tell me about yourself." }
        for (b in intro.shortBullets) println("• $b")
        assertTrue(intro.shortBullets.any { it.contains("Gabrielyte UAB") })
    }

    @Test
    fun partiallyConfirmedProfileNeverLeaksUnconfirmedMetrics() {
        val p = Fixtures.shervinProfile(confirm = false)
        p.confirmAll { f -> f.kind == FactKind.Role || f.kind == FactKind.Skill || f.company == "Arzif Crypto Exchange" }
        val pack = runBlocking { PreparationPipeline(Fixtures.assets()).runAsync(p, Fixtures.teroxxTarget(p.id)) }
        val all = pack.questions.flatMap { q -> q.shortBullets + q.optionalFullAnswer }.joinToString(" ")
        assertFalse(all.contains("128")) // Epson metric (unconfirmed)
        assertFalse(all.contains("12%")) // Philips metric (unconfirmed)
        assertTrue(all.contains("60,000")) // Arzif metric (confirmed)
        assertTrue(pack.warnings.any { it.contains("not confirmed") })
    }

    @Test
    fun preparationRequiresConfirmedFacts() {
        val p = Fixtures.shervinProfile(confirm = false)
        // C# InvalidOperationException → Kotlin IllegalStateException (registered divergence, P3-PORT-NOTES Package C).
        assertThrows(IllegalStateException::class.java) {
            runBlocking { PreparationPipeline(Fixtures.assets()).runAsync(p, Fixtures.teroxxTarget(p.id)) }
        }
    }

    @Test
    fun multipleProfilesAndTargetsAreIsolated() {
        val root = Files.createTempDirectory("ia-ws-").toFile().absolutePath
        val store = WorkspaceStore(root, NoProtection())
        val shervin = Fixtures.shervinProfile()
        val maria = CandidateProfileRecord()
        ProfileService.addResume(maria, ProfileService.createDocument("m.txt", DocumentKind.Resume, Fixtures.text("resumes", "maria_keller_de.txt")))
        maria.confirmAll()
        store.saveProfile(shervin)
        store.saveProfile(maria)
        val t1 = Fixtures.teroxxTarget(shervin.id)
        val t2 = InterviewTarget(
            profileId = shervin.id, jobTitle = "Head of Growth", company = "Contoso",
            jobDescriptionText = "Responsibilities\n- Drive user acquisition and retention.\nRequirements\n- Growth marketing experience.",
        )
        val t3 = InterviewTarget(profileId = maria.id, jobDescriptionText = Fixtures.text("jobs", "backend_engineer_de.txt"), expectedLanguage = "de")
        val pipe = PreparationPipeline(Fixtures.assets())
        for ((p, t) in listOf(shervin to t1, shervin to t2, maria to t3)) {
            store.savePack(runBlocking { pipe.runAsync(p, t) })
            store.saveTarget(t)
        }

        assertEquals(2, store.profiles().size)
        assertEquals(2, store.targets(shervin.id).size)
        assertEquals(1, store.targets(maria.id).size)
        val mariaPack = store.getPack(t3.id)!!
        val mariaText = mariaPack.questions.flatMap { it.shortBullets }.joinToString(" ") + mariaPack.context.positioning
        assertFalse(mariaText.contains("Arzif"))
        assertFalse(mariaText.contains("Teroxx"))
        assertEquals("de", mariaPack.context.interviewLanguage)
        assertTrue(mariaPack.profile.experience.map { it.company }.contains("PayFlow GmbH"))
        assertEquals("Contoso", store.getPack(t2.id)!!.context.companyName)

        store.deleteProfile(shervin.id)
        assertEquals(null, store.getPack(t1.id))
        assertTrue(store.targets(shervin.id).isEmpty())
        assertEquals(1, store.profiles().size)
        File(root).deleteRecursively()
    }

    /**
     * GOLDEN test (TRANSFER-MAP §5.2 rule 5 / §5.4): the pipeline over the `samples/shervin-teroxx` inputs against
     * the golden 97-question bank. The golden bank is a CURATED knowledge bank, not the deterministic pipeline's
     * output (its structures are only the three generator defaults and one mode differs — see the pinned exception
     * below), so no full-bank equality can hold even on Windows; the pinned comparison is over canonical
     * fingerprints, modes and structures for the questions the pipeline sources from the generic library, plus the
     * unconditional Core() questions present in both banks. The analysis is recorded in P3-PORT-NOTES.md (Package C).
     */
    @Test
    fun goldenBank_PipelineMatchesLibrarySourcedQuestionsAndCoreModes() {
        val golden = Fixtures.testKnowledge().questions
        assertEquals(97, golden.size)
        val pack = Fixtures.shervinPack()
        fun fp(q: String): String = TextNormalizer.fingerprint(q)
        val goldenByFp = golden.associateBy { fp(it.canonicalQuestion) }
        val pipeByFp = pack.questions.associateBy { fp(it.canonicalQuestion) }
        val libraryFps = Fixtures.assets().library.map { fp(it.canonicalQuestion) }.toSet()

        // Library-sourced overlap (golden ∩ library ∩ pipeline): mode and structure must match the golden entry.
        val overlap = goldenByFp.keys.intersect(libraryFps).intersect(pipeByFp.keys)
        assertTrue("library-sourced overlap ${overlap.size}", overlap.size >= 30)
        for (f in overlap) {
            val g = goldenByFp.getValue(f)
            val q = pipeByFp.getValue(f)
            assertEquals("${g.canonicalQuestion}: mode", g.mode, q.mode)
            assertEquals("${g.canonicalQuestion}: structure", g.answerStructure, q.answerStructure)
        }

        // Unconditional Core() questions present in both banks, with equal modes.
        for ((question, mode) in listOf(
            "Tell me about yourself." to AnswerMode.Verified,
            "Why do you want to work at Teroxx?" to AnswerMode.Hypothetical,
            "Why should we hire you?" to AnswerMode.Verified,
            "What are your strengths?" to AnswerMode.Verified,
            "What would you do in your first 90 days?" to AnswerMode.Hypothetical,
            "Do you have any questions for us?" to AnswerMode.Hypothetical,
            "Where do you see yourself in five years?" to AnswerMode.Hypothetical,
        )) {
            val g = goldenByFp[fp(question)]
            val q = pipeByFp[fp(question)]
            assertTrue("$question: in golden bank", g != null)
            assertTrue("$question: in pipeline bank", q != null)
            assertEquals("$question: golden mode", mode, g!!.mode)
            assertEquals("$question: pipeline mode", mode, q!!.mode)
        }

        // The one pinned exception (documented like KnownVoiceLookalikes): the curated golden bank marks
        // "What is your biggest weakness?" VERIFIED; the current factory generates it as a Bridge question.
        val weaknessFp = fp("What is your biggest weakness?")
        assertEquals(AnswerMode.Verified, goldenByFp[weaknessFp]?.mode)
        assertEquals(AnswerMode.Bridge, pipeByFp[weaknessFp]?.mode)
    }
}
