package com.interviewassistant.core.intelligence

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.knowledge.AnswerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/ClassifierAndTextTests.cs` — same cases, same inputs, same expectations. */
class ClassifierAndTextTests {
    @Test
    fun modeIsClassified() {
        val cases = listOf(
            "Have you personally built a financial ledger before?" to AnswerMode.Bridge,
            "Do you have experience with closed-loop reward systems?" to AnswerMode.Bridge,
            "How would you design an internal ledger reconciliation process?" to AnswerMode.Hypothetical,
            "How would you prevent duplicate financial transactions?" to AnswerMode.Hypothetical,
            "Tell me about your crypto experience." to AnswerMode.Verified,
            "Tell me about yourself." to AnswerMode.Verified,
            "What is MiCA?" to AnswerMode.Hypothetical,
        )
        for ((q, expected) in cases) assertEquals(q, expected, QuestionClassifier.classify(q).mode)
    }

    @Test
    fun categoryIsClassified() {
        val cases = listOf(
            "How would you reconcile the XAB ledger?" to "LEDGER",
            "What do you know about MiCAR?" to "MICAR",
            "How would you design VIP tiers?" to "VIP",
            "Engineering disagrees with your priorities, what do you do?" to "PRIORITIZATION",
        )
        for ((q, cat) in cases) assertEquals(q, cat, QuestionClassifier.classify(q).category)
    }

    @Test
    fun followUpsDetected() {
        for (q in listOf("Why?", "And what metric specifically?", "Can you give an example?"))
            assertTrue(q, QuestionClassifier.classify(q).isFollowUp)
    }

    @Test
    fun transcriptCleanupFixesDomainTerms() {
        val cases = listOf(
            "um so how would you, uh, improve terox's ab locks token" to "How would you, improve Teroxx's Abloxx token",
            "what about mica r and cy sec" to "What about MiCAR and CySEC", // "MiCA R" is MiCAR (RC2: MiCAR is no longer rewritten to MiCA)
            "explain item potency" to "Explain idempotency",
        )
        for ((raw, expected) in cases)
            assertEquals(expected, TextNormalizer.cleanTranscript(raw, Fixtures.testKnowledge().context.aliases))
    }

    @Test
    fun withoutTargetAliasesNoCompanyNamesAreInjected() =
        assertEquals("Improve terox's ab locks token", TextNormalizer.cleanTranscript("improve terox's ab locks token"))

    @Test
    fun fingerprintIgnoresPunctuationAndCase() {
        assertEquals(TextNormalizer.fingerprint("How would you improve XAB?"), TextNormalizer.fingerprint("how would you improve xab"))
    }

    @Test
    fun bulletParserEmitsOnlyCompleteStableBullets() {
        val p = BulletStreamParser()
        val stream = "MODE: HYPO|THETICAL\n• I'd start| with utility.\n•| Then benefits|.\n• Finally| metrics."
        val emitted = mutableListOf<String>()
        for (chunk in stream.split('|')) emitted.addAll(p.push(chunk))
        assertEquals(listOf("I'd start with utility.", "Then benefits."), emitted)
        assertEquals("Finally metrics.", p.pending)
        emitted.addAll(p.complete())
        assertEquals(3, emitted.size)
        assertEquals(AnswerMode.Hypothetical, p.mode)
    }

    @Test
    fun validatorFlagsFabricatedNumbersAndClaims() {
        val v = FactValidator(Fixtures.testKnowledge().profile)
        val r = v.validate(
            listOf("I built a ledger that processed 47% more transactions for the team.", "I'd define clear rules with Engineering and Compliance."),
            AnswerMode.Hypothetical,
        )
        assertTrue(r.issues.any { i -> i.kind == "UNVERIFIED_NUMBER" && i.detail.contains("47") })
        assertTrue(r.issues.any { i -> i.kind == "UNSUPPORTED_CLAIM" })
        assertEquals("I'd build a ledger", FactValidator.softenClaim("I built a ledger"))
    }

    @Test
    fun validatorAcceptsVerifiedResumeNumbers() {
        val v = FactValidator(Fixtures.testKnowledge().profile)
        val r = v.validate(
            listOf(
                "At Arzif we grew to 60,000 active traders and revenue by 60% over three years.",
                "That raised satisfaction by twenty-five percent and cut downtime by 20%.",
                "We partnered with Binance, CoinEx and KuCoin.",
            ),
            AnswerMode.Verified,
        )
        assertTrue(r.issues.none { i -> i.kind == "UNVERIFIED_NUMBER" })
    }
}
