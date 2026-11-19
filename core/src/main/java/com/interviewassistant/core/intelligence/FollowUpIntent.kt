package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/FollowUpIntent.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** What kind of follow-up a sentence is (none = not a follow-up request). */
enum class FollowUpKind {
    None,

    /** An imperative that asks the candidate to say (more): "Tell me more.", "Walk me through that.", "Go on." */
    Imperative,

    /** An elliptical question: "Why?", "Why.", "How so?", "And then?", "What happened?", "Really?" */
    Elliptical,
}

/**
 * Candidate-directed follow-ups recognized by their grammar, not by a list of transcripts. A real interviewer turn does
 * not need a question mark, an interrogative word or question grammar: "Tell me more.", "Go on.", "Continue.",
 * "Give me an example." and "Take me through your thinking." are complete asks. They are recognized as an ELICITATION
 * VERB in imperative position — the start of the sentence after discourse markers ("so", "okay"), optionally behind a
 * politeness frame ("please", "can you", "could you", "I'd like you to") — followed by what is asked for. The same verb
 * later in a statement ("…hard to explain in the app.") is not an ask.
 * Complement constraints keep instructions that are not asks to speak out:
 *  - the movement verbs ("go on", "continue", "carry on", "keep going") are an ask only with nothing after them, an
 *    addressee, "please"/"then", a separate clause after a comma ("Go on, I'm listening."), or a speaking complement
 *    ("…with your answer", "…about the migration"). "Go on mute.", "Continue to the next slide.", "Keep going with the
 *    exercise.", "Carry on with your code." and "Continue coding." are not;
 *  - "show me/us" asks only for an explanation ("Show me how you would do it."), not for a screen or a document;
 *  - an ask deferred to later or to another channel ("Clarify that in the follow-up email.") is not an ask for now.
 * English only: other languages' imperative follow-ups are not recognized by this grammar (a "?" form still is).
 * Purely textual: whether the audio carried a voice is decided separately (acoustic evidence) and both must agree.
 */
object FollowUpIntent {
    // Discourse markers that may precede the ask ("Okay, so tell me more.").
    // A leading vocative ("Shervin, go on.") is allowed here; WHO is addressed is decided by UtteranceGate.AddressedTo.
    private const val lead = "^((and|so|okay|ok|right|now|well|alright|great|thanks|hm+|mm+)[,.]?\\s+)*(\\p{Lu}[\\p{L}'’-]+,\\s+)?((and|so|okay|ok|now)[,.]?\\s+)*"

    // Politeness frames in front of the imperative.
    private const val frame = "((please|kindly|just)\\s+|(can|could|would|will) you\\s+(please\\s+)?|(i|we)( would|'d|’d) (like|love) (you )?to\\s+|(i|we) want you to\\s+)?"

    // Elicitation verbs and the object they take (what the candidate should say). Each needs its complement where the
    // verb alone would be too vague ("tell" needs "me/us"; "give" needs "me/us/an example"; "show me" a how/what clause).
    private const val verb =
        "(tell (me|us)\\b|walk (me|us) through\\b|talk (me|us) through\\b|take (me|us) through\\b|run (me|us) through\\b|" +
            "talk (about|more)\\b|say (more|a bit more|a little more)\\b|go deeper\\b|dig deeper\\b|" +
            "explain\\b|elaborate\\b|expand\\b|describe\\b|clarify\\b|unpack\\b|illustrate\\b|spell (it|that|this) out\\b|" +
            "break (it|that|this) down\\b|give (me|us)\\b|give (an|another|some|a) (example|instance|detail|details)\\b|" +
            "show (me|us) (how|what|why|where|which|an? example|that|this|it)\\b|" +
            "help (me|us) understand\\b|paint (me|us) a picture\\b|be (more )?specific\\b|go into (more )?detail\\b)"

    // "Go on" / "continue" / "carry on" / "keep going": an ask to keep talking, so the complement must be about talking.
    private const val movement = "(go on|carry on|keep going|continue)"

    // What may follow a movement verb: nothing, please/then, an addressee (who is addressed is decided elsewhere), a separate
    // clause after a comma or dash, or a speaking complement. Anything else ("to the next slide", "with the exercise",
    // "mute", "coding") makes it an instruction about the meeting, not an ask to speak.
    private const val movementBare = "(\\s*,?\\s*(please|then)\\b)?(\\s*,?\\s+(?-i:\\p{Lu})[\\p{L}'’-]+)?[\\s.!?？…]*$|\\s*[,;:—–]\\s*\\S"
    private const val movementSpeaking =
        "\\s+(about\\b|explaining\\b|telling\\b|talking\\b|from there\\b|where you (left off|were)\\b|" +
            "with (your|that|this|the) (answer|story|example|explanation|thought|thoughts|point|thinking|response|description|reply|reasoning)\\b)"

    private val imperativeAsk: Regex = dotNetRegex(lead + frame + verb, ignoreCase = true)
    private val movementAsk: Regex = dotNetRegex(lead + frame + movement + "(" + movementBare + "|" + movementSpeaking + ")", ignoreCase = true)

    // A movement ask with no topic of its own ("Go on.", "Please continue, Shervin.", "Carry on with your answer.").
    private val bareMovementAsk: Regex = dotNetRegex(lead + frame + movement + "(" + movementBare + "|\\s+(from there\\b|where you (left off|were)\\b|with (your|that|this|the) \\w+[\\s.!?？…]*$))", ignoreCase = true)

    // An ask deferred to later or to another channel is not an ask to answer now.
    private val deferred: Regex = dotNetRegex(
        "\\b(later|afterwards|after the (call|interview|meeting)|in (the|an|your) (follow-?up )?(e-?mail|form|chat|writing)|by e-?mail|offline|next time)[\\s,.!?？…]*$",
        ignoreCase = true,
    )

    // "Example?", "Details?" — a bare request noun with question intonation is an ask too (handled by "?" elsewhere);
    // without "?" only these request nouns count, and only when they are the whole sentence. A lone "More." is not one
    // (too easily a recognizer's guess for a short sound).
    private val bareRequest: Regex = dotNetRegex("^(an? |another |more |some )?(example|examples|detail|details|specifics)[.!…]*$", ignoreCase = true)

    // Asks that point back at what the candidate just said ("that", "it", "more", "further", "another", "your thinking"):
    // a follow-up, never the continuation of the previous question.
    private val anaphoric: Regex = dotNetRegex(
        "\\b(that|this|it|more|further|another|again|deeper|why|how so|and then|then what|what happened)\\b|" +
            "\\b(your|the) (thinking|reasoning|thought process|logic|process|rationale|steps)[\\s.!?？…]*$",
        ignoreCase = true,
    )

    // …or end on the ask itself, with no new topic after it ("Elaborate.", "Give me an example.") — unlike "Give me an
    // example of a metric you moved.", which is a new question.
    private val endsOnTheAsk: Regex = dotNetRegex(
        "\\b(example|examples|detail|details|specifics|elaborate|expand|explain|clarify|unpack|illustrate|specific)[\\s.!?？…]*$",
        ignoreCase = true,
    )

    fun classify(sentence: String): FollowUpKind {
        val t = sentence.trim()
        if (t.isEmpty()) return FollowUpKind.None
        if ((imperativeAsk.containsMatchIn(t) || movementAsk.containsMatchIn(t) || bareRequest.containsMatchIn(t)) && !deferred.containsMatchIn(t)) return FollowUpKind.Imperative
        if (UtteranceGate.isEllipticalAsk(t)) return FollowUpKind.Elliptical
        return FollowUpKind.None
    }

    /** A candidate-directed imperative ask anywhere a sentence starts (after discourse markers / a politeness frame). */
    fun isImperativeAsk(sentence: String): Boolean = classify(sentence) == FollowUpKind.Imperative

    /**
     * A short, self-contained follow-up that refers back to the previous answer ("Tell me more.", "Why?", "Give me an
     * example."). Such a turn is a NEW ask when it comes after the candidate had time to reply (the turn detector's
     * timing rule) — merging it into that question would replace the earlier answer instead of answering the follow-up.
     */
    fun isStandaloneFollowUp(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || TextNormalizer.wordCount(t) > 12) return false
        if (leadingConjunction.containsMatchIn(t) && !UtteranceGate.isEllipticalAsk(t)) return false // "and which metrics…" continues
        val kind = classify(t)
        return kind == FollowUpKind.Elliptical ||
            (kind == FollowUpKind.Imperative && (anaphoric.containsMatchIn(t) || endsOnTheAsk.containsMatchIn(t) || bareMovementAsk.containsMatchIn(t)))
    }

    private val leadingConjunction: Regex = dotNetRegex("^(and|or|but|also|plus|as well as)\\b", ignoreCase = true)

    // Leading discourse markers and politeness words that carry no content of their own.
    private val filler: Regex = dotNetRegex("^((and|so|okay|ok|right|now|well|alright|great|thanks|please|just|hm+|mm+)[,.]?\\s+)+", ignoreCase = true)

    /**
     * A one- or two-word imperative follow-up ("Continue.", "Go on.", "Elaborate.", "Okay, keep going."): so little text
     * that a recognizer can produce it for almost any short sound, so the audio must clearly be speech (see
     * [UtteranceGate]). A form asked with "?" ("Example?", "Elaborate?") is not included: rc.4 already
     * answered those as questions, and they keep exactly that behaviour.
     */
    fun isVeryShortImperative(text: String): Boolean {
        val t = text.trim()
        if (TextNormalizer.endsWithQuestionMark(t) || classify(t) != FollowUpKind.Imperative) return false
        return TextNormalizer.wordCount(filler.replace(t, "")) <= 2
    }
}
