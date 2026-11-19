package com.interviewassistant.core.intelligence

import com.interviewassistant.core.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/MatcherEvaluationTests.cs` — larger held-out matcher evaluation
 * (phrasings not in the bank) plus NOVEL questions that must NOT be served from the cache. A false
 * high-confidence match shows a wrong prepared answer instantly, so it is the metric that matters most.
 * Case table extracted verbatim from the C# source.
 */
class MatcherEvaluationTests {
    data class Case(val spoken: String, val expected: String?, val area: String)

    companion object {
        val cases: List<Case> = listOf(
        Case("Give us a quick rundown of your career so far.", "Tell me about yourself.", "background"),
        Case("Why are you interested in joining Teroxx specifically?", "Why do you want to work at Teroxx?", "motivation"),
        Case("What drew you to product ownership after sales and marketing?", "Why do you want to become a Product Owner?", "background"),
        Case("Walk me through what you did in the crypto space.", "Tell me about your crypto experience.", "crypto"),
        Case("How did Arzif reach sixty thousand active traders?", "How did you grow Arzif to 60,000 active traders?", "behavioural"),
        Case("Share an example of when you improved customer retention.", "Tell me about a time you improved retention.", "behavioural"),
        Case("Describe a partnership deal you closed.", "Tell me about a partnership you negotiated.", "behavioural"),
        Case("What does your day look like in your current job?", "What do you do in your current role?", "background"),
        Case("What are your plans for the first three months here?", "What would you do in your first 90 days?", "XAB"),
        Case("What would you tackle first in this role?", "What would be your first priorities in this role?", "XAB"),
        Case("How can we make XAB more useful for our clients?", "How would you improve XAB utility?", "XAB"),
        Case("How would you get more customers to use XAB?", "How would you increase XAB adoption?", "XAB"),
        Case("Which numbers would show that XAB is succeeding?", "What metrics would you use to measure success of XAB?", "metrics"),
        Case("How would you build a booster for rewards?", "How would you design a reward booster?", "rewards"),
        Case("How do you keep a rewards program from becoming just a cost center?", "How do you make sure rewards are sustainable and not just a cost?", "rewards"),
        Case("How would you set up the rules for VIP levels?", "How would you design VIP tier governance?", "VIP"),
        Case("How would you get VIP clients more engaged?", "How would you increase engagement of VIP clients?", "VIP"),
        Case("What could go wrong with token rewards?", "What risks do you see in a token rewards ecosystem?", "rewards"),
        Case("How do you prevent double payouts in the system?", "How would you prevent duplicate financial transactions?", "ledger"),
        Case("How would you automate reconciliation between systems?", "How would you design an automated reconciliation process?", "ledger"),
        Case("Explain double-entry bookkeeping and why a ledger needs it.", "What is double-entry accounting and why does it matter for a ledger?", "ledger"),
        Case("How would you correct a wrong ledger entry?", "How would you handle a reversal or correction in the ledger?", "ledger"),
        Case("Which states should a ledger transaction go through?", "What are the transaction states you would define?", "ledger"),
        Case("What is a closed loop token economy?", "What is a closed-loop token economy?", "technical"),
        Case("Custodial versus non-custodial wallets, what's the difference?", "What is the difference between a custodial and non-custodial wallet?", "technical"),
        Case("How is staking different from your rewards scheme?", "What is staking and how is it different from your rewards program?", "technical"),
        Case("How does MiCA affect what we're building?", "What do you know about MiCA and how does it affect this product?", "compliance"),
        Case("Compliance says no to your feature. What next?", "What would you do if Compliance blocks a feature?", "compliance"),
        Case("How do KYC rules shape the product?", "How do KYC and AML affect product design?", "compliance"),
        Case("How do you decide the order of the backlog?", "How do you prioritize the backlog?", "prioritization"),
        Case("How do you balance tech debt with new features?", "How do you balance technical debt against new features?", "prioritization"),
        Case("What makes a user story good?", "How do you write a good user story?", "agile"),
        Case("What's your definition of done?", "What is your Definition of Ready and Definition of Done?", "agile"),
        Case("How do you run backlog refinement and sprint planning?", "How do you run sprint planning and refinement?", "agile"),
        Case("Engineers push back on your plan. How do you respond?", "What do you do when Engineering disagrees with you?", "stakeholder"),
        Case("Two departments want conflicting priorities. What do you do?", "Two stakeholders want opposite priorities. How do you decide?", "stakeholder"),
        Case("Marketing wants to ship before the developers are ready. How do you handle it?", "Marketing wants to launch before Engineering is ready. What do you do?", "stakeholder"),
        Case("How would you lower churn?", "How would you reduce churn?", "metrics"),
        Case("How would you test a new reward with an experiment?", "How would you run an experiment to test a new reward?", "metrics"),
        Case("Have you personally built a financial ledger before?", "Have you personally built or owned a financial ledger?", "direct-experience"),
        Case("Have you worked hands-on with MiCA?", "Have you worked directly with MiCA?", "direct-experience"),
        Case("What's your biggest weakness?", "What is your biggest weakness?", "background"),
        Case("Any questions for us before we wrap up?", "Do you have any questions for us?", "general"),
        Case("How would you price a premium subscription for institutional clients?", null, "novel"),
        Case("What do you think about gamification in banking apps?", null, "novel"),
        Case("How would you explain the ledger architecture to the board?", null, "novel"),
        Case("How would you evaluate a KYC vendor build versus buy decision?", null, "novel"),
        Case("How would you handle a security incident involving leaked API keys?", null, "novel"),
        Case("What would you do if a competitor copied our token program?", null, "novel"),
        Case("How would you design a referral bonus that does not attract fraud?", null, "novel"),
        Case("How would you localize the app for the Turkish market?", null, "novel"),
        Case("How would you run a customer advisory board?", null, "novel"),
        Case("What's your opinion on stablecoin regulation in the US?", null, "novel"),
        Case("How would you measure the success of a mobile push notification campaign?", null, "novel"),
        Case("How would you migrate users from one wallet provider to another?", null, "novel"),
        )
    }

    @Test
    fun heldOutParaphrasesAndNovelQuestions() {
        val kb = Fixtures.testKnowledge()
        val m = QuestionMatcher(kb.questions)
        var known = 0
        var top1 = 0
        var falseHigh = 0
        var novelTotal = 0
        var novelFallback = 0
        var knownFallback = 0
        for ((spoken, expected, area) in cases) {
            val r = m.match(TextNormalizer.cleanTranscript(spoken, kb.context.aliases))
            val high = r.confidence == MatchConfidence.High
            if (expected == null) {
                novelTotal++
                if (!high) novelFallback++ else falseHigh++
                println("${if (high) "FALSE-HIGH" else "ok-AI     "} ${r.score} ${r.confidence} [$area] '$spoken' -> ${r.question?.canonicalQuestion}")
                continue
            }
            known++
            val hit = r.question?.canonicalQuestion == expected
            if (hit) top1++
            else if (high) falseHigh++
            if (!high) knownFallback++
            println("${if (hit) "OK        " else if (high) "WRONG-HIGH" else "miss      "} ${r.score} ${r.confidence} [$area] '$spoken' -> ${r.question?.canonicalQuestion}")
        }
        println("Known: top-1 $top1/$known (${top1 * 100.0 / known}%), AI fallback rate ${knownFallback * 100.0 / known}%")
        println("Novel: correctly sent to AI $novelFallback/$novelTotal")
        println("False high-confidence matches (wrong cached answer would be shown): $falseHigh")

        assertTrue("false high-confidence matches: $falseHigh", falseHigh <= 1)
        assertEquals(novelTotal, novelFallback) // novel questions are never served a wrong prepared answer
        assertTrue("top-1 accuracy too low: $top1/$known", top1 >= known * 0.7)
    }
}
