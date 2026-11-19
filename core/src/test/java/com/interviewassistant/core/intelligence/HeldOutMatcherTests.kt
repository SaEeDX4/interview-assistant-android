package com.interviewassistant.core.intelligence

import com.interviewassistant.core.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/HeldOutMatcherTests.cs` — novel phrasings NOT present in the bank.
 * Measures honest generalisation and, critically, that wrong matches are rarely High-confidence
 * (which would show a wrong cached answer). Case table extracted verbatim from the C# source.
 */
class HeldOutMatcherTests {
    companion object {
        val cases: List<Pair<String, String>> = listOf(
        "Could you start by giving us a short overview of who you are professionally?" to "Tell me about yourself.",
        "What made you apply for this position with us?" to "Why do you want to work at Teroxx?",
        "What makes you a stronger candidate than the others we're interviewing?" to "Why should we hire you?",
        "You've mostly done sales and marketing, so why product now?" to "Why do you want to become a Product Owner?",
        "What exactly did you do at the crypto exchange?" to "Tell me about your crypto experience.",
        "How did you manage to get to sixty thousand traders?" to "How did you grow Arzif to 60,000 active traders?",
        "If you joined, what would you focus on in your first three months?" to "What would you do in your first 90 days?",
        "How would you get more of our clients to actually use the XAB token?" to "How would you increase XAB adoption?",
        "Which KPIs would tell you whether the token program is working?" to "What metrics would you use to measure success of XAB?",
        "How should we decide which reward features to build first?" to "How would you prioritize improvements to the XAB rewards ecosystem?",
        "How would you stop the same payout being credited twice?" to "How would you prevent duplicate financial transactions?",
        "What happens when the balances in two systems don't agree, how do you reconcile them automatically?" to "How would you design an automated reconciliation process?",
        "How would you make sure we never exceed the token cap?" to "How would you ensure token supply compliance?",
        "Walk me through how you'd own our internal token ledger." to "How would you approach an internal XAB ledger?",
        "How would you fix a wrong entry in the ledger?" to "How would you handle a reversal or correction in the ledger?",
        "What's your understanding of the MiCA regulation and its impact here?" to "What do you know about MiCA and how does it affect this product?",
        "Legal says your feature can't go live. What now?" to "What would you do if Compliance blocks a feature?",
        "How do you decide what goes to the top of the backlog?" to "How do you prioritize the backlog?",
        "The CEO walks in and wants a new feature by Friday. How do you react?" to "How do you handle an urgent request from the C-level?",
        "Your developers push back on a requirement. How do you handle that?" to "What do you do when Engineering disagrees with you?",
        "How do you think about paying down tech debt versus shipping new things?" to "How do you balance technical debt against new features?",
        "How do you use Jira in your daily work?" to "What is your experience with Jira and Agile?",
        "How would you structure the VIP levels and who qualifies?" to "How would you design VIP tier governance?",
        "What could go wrong with a token rewards program?" to "What risks do you see in a token rewards ecosystem?",
        "Tell me about a time you used analytics to drive a decision." to "Tell me about a time you used data to make a decision.",
        "Talk to me about a launch you were responsible for." to "Tell me about a product launch you led.",
        "How would you describe yourself as a manager of people?" to "Tell me about your leadership style.",
        "What would you like to ask us?" to "Do you have any questions for us?",
        "Explain what a closed loop token economy means." to "What is a closed-loop token economy?",
        "How do you write acceptance criteria for a payout story?" to "How would you write acceptance criteria for a reward payout feature?",
        )
    }

    @Test
    fun heldOutParaphraseAccuracy() {
        val m = QuestionMatcher(Fixtures.testKnowledge().questions)
        var ok = 0
        var okHigh = 0
        var wrongHigh = 0
        for ((spoken, exp) in cases) {
            val r = m.match(TextNormalizer.cleanTranscript(spoken, Fixtures.testKnowledge().context.aliases))
            val hit = r.question?.canonicalQuestion == exp
            if (hit) {
                ok++
                if (r.confidence == MatchConfidence.High) okHigh++
            } else if (r.confidence == MatchConfidence.High) wrongHigh++
            println("${if (hit) "OK  " else "MISS"} ${r.score} ${r.confidence} '$spoken' -> ${r.question?.canonicalQuestion}")
        }
        println("Held-out top-1 $ok/${cases.size} (${ok * 100.0 / cases.size}%), served-from-cache (High) correct $okHigh, wrong-but-High $wrongHigh")
        assertTrue("held-out accuracy too low: $ok/${cases.size}", ok >= cases.size * 0.7)
        assertTrue("wrong high-confidence cache hits: $wrongHigh", wrongHigh <= 1)
    }
}
