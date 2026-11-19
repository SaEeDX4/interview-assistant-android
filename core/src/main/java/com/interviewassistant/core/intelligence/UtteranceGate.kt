package com.interviewassistant.core.intelligence

import com.interviewassistant.core.audio.SpeechEvidence
import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.KnowledgeBase

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/UtteranceGate.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** What a finalized interviewer turn is. Only [UtteranceKind.Question] and [UtteranceKind.ImplicitPrompt] get answers. */
enum class UtteranceKind {
    /** A question or request to the candidate ("Why?", "Tell me about yourself."). */
    Question,

    /** A statement that invites the candidate to talk ("I see you led growth at Arzif."). */
    ImplicitPrompt,

    /** Background before the actual ask: kept and attached to the next question. */
    Context,

    /** Meeting logistics ("Can you hear me?", "Let me share my screen"). */
    Logistics,

    /** Addressed to another interviewer ("John, what do you think?"). */
    OtherDirected,

    /** The recognizer reproduced its own vocabulary prompt (typical after a beep). */
    PromptEcho,

    /** The audio for this turn contained no human speech. */
    NonSpeech,

    /** A short non-speech sound (beep, chime, click, screenshot sound). */
    ShortAcousticEvent,

    /** A candidate-directed follow-up request: an imperative ("Tell me more.", "Go on.") or an elliptical ask ("Why?"). */
    FollowUp,

    /** Recognizer artefacts ("Thanks for watching", "♪", "..."). */
    Noise,

    /** A voice was heard, but far too little of it to have said these words (recognizer filled in text). */
    ImplausibleTranscript,

    /** An interview-flavoured sentence built from the interview's own vocabulary over too little speech. */
    ContextHallucination,
}

data class UtteranceVerdict(val kind: UtteranceKind, val reason: String, val support: AcousticSupport = AcousticSupport.Unknown) {
    val answerable: Boolean
        get() = kind == UtteranceKind.Question || kind == UtteranceKind.ImplicitPrompt || kind == UtteranceKind.FollowUp

    /** Rejected because the audio or its consistency with the text says it was not interviewer speech. */
    val notSpeech: Boolean
        get() = kind == UtteranceKind.Noise || kind == UtteranceKind.PromptEcho || kind == UtteranceKind.NonSpeech || kind == UtteranceKind.ShortAcousticEvent ||
            kind == UtteranceKind.ImplausibleTranscript || kind == UtteranceKind.ContextHallucination
}

/**
 * Utterance validity layer between the turn detector and the answer engine (defense in depth against answering
 * things that are not interview questions for this candidate). Text alone can never prove speech — a recognizer given a
 * chime may return a perfectly formed interview question — so the audio is judged first and the text must fit it:
 *  1. recognizer artefacts → Noise;
 *  2. the audio is not a voice (no voiced sound; narrow-band, steadily held or jumping pitch) → ShortAcousticEvent /
 *     NonSpeech, whatever the text says (no exception for long, well-formed questions);
 *  3. the voice heard could not have said these words (syllables vs voiced time and duration) → ImplausibleTranscript;
 *  4. weak support and a sentence built from the interview's own vocabulary prompt → ContextHallucination;
 *  5. prompt echo;  6. per sentence: logistics, addressed to someone else, question/request, implicit prompt, context.
 * Short real follow-ups ("Why?", "How so?") pass: they need little voiced time, and a real voice provides it. When no audio
 * was analysed (tests, capture not running) the acoustic steps abstain. A question does not need a question mark, and
 * mentioning target terms with a clearly heard voice is normal; only the combination with weak audio is suspicious.
 */
class UtteranceGate(
    val echo: PromptEchoDetector,
    candidateNames: Iterable<String>? = null,
    knownTerms: Iterable<String>? = null,
) {
    enum class Addressee { Candidate, Other }

    // The C#'s OrdinalIgnoreCase sets are stored lowercase-normalized here; lookups lowercase too.
    private val candidateNameSet: Set<String> = (candidateNames ?: listOf())
        .flatMap { n -> n.split(' ', '-').filter { it.isNotEmpty() } }
        .filter { n -> n.length > 1 && !n.equals("candidate", ignoreCase = true) && !n.equals("the", ignoreCase = true) }
        .map { it.lowercase() }
        .toSet()
    private val knownTermSet: Set<String> = (knownTerms ?: listOf())
        .filter { t -> t.length > 1 }
        .map { it.lowercase() }
        .toSet()

    /**
     * Segment-level check (before a segment joins a turn): recognizer artefacts, prompt echoes and segments whose audio
     * held no speech are dropped, so a beep's echo can never be glued onto the real question that follows it.
     * Returns null when the segment is fine.
     */
    fun evaluateSegment(text: String, evidence: SpeechEvidence): UtteranceVerdict? {
        val v = evaluate(text, evidence)
        return if (v.notSpeech) v else null
    }

    fun evaluate(text: String, evidence: SpeechEvidence = SpeechEvidence(0, 0, 0, 0, 0)): UtteranceVerdict {
        val t = text.trim()
        if (t.isEmpty() || artefact.containsMatchIn(t)) return UtteranceVerdict(UtteranceKind.Noise, "recognizer artefact")
        val sentences = QuestionExtractor.sentences(t)
        val support = SpeechPlausibility.assess(t, evidence)
        val echoResult = echo.check(t, evidence)

        if (support == AcousticSupport.None) {
            if (echoResult.isEcho) return UtteranceVerdict(UtteranceKind.PromptEcho, "prompt echo without speech: " + echoResult.reason, support)
            return if (evidence.activeMs <= SpeechEvidence.ShortEventMs)
                UtteranceVerdict(UtteranceKind.ShortAcousticEvent, "short non-speech sound (${evidence.notVoiceReason}): $evidence", support)
            else
                UtteranceVerdict(UtteranceKind.NonSpeech, "no voice in audio (${evidence.notVoiceReason}): $evidence", support)
        }
        if (support == AcousticSupport.Implausible)
            return UtteranceVerdict(UtteranceKind.ImplausibleTranscript, "${SpeechPlausibility.syllables(t)} syllables cannot fit the speech heard: $evidence", support)
        if (support == AcousticSupport.Weak && echo.contextOverlap(t) > 0)
            return UtteranceVerdict(UtteranceKind.ContextHallucination, "interview-vocabulary sentence over little speech (${SpeechPlausibility.syllables(t)} syllables): $evidence", support)

        if (echoResult.isEcho) return UtteranceVerdict(UtteranceKind.PromptEcho, "prompt echo: " + echoResult.reason, support)
        if (FollowUpIntent.isVeryShortImperative(t)) {
            val why = notClearlySpeech(t, evidence, support)
            if (why != null)
                return if (evidence.activeMs <= SpeechEvidence.ShortEventMs)
                    UtteranceVerdict(UtteranceKind.ShortAcousticEvent, "one- or two-word follow-up over sound that is not clearly speech ($why): $evidence", support)
                else
                    UtteranceVerdict(UtteranceKind.NonSpeech, "one- or two-word follow-up over sound that is not clearly speech ($why): $evidence", support)
        }

        var anyQuestion = false
        var anyOther = false
        var anyLogistics = false
        var anyImplicit = false
        var onlyFollowUps = true
        var namesCandidate = false
        for (s in sentences) {
            if (logistics.containsMatchIn(s) && !hasRealAskBeyondLogistics(s)) {
                anyLogistics = true
                continue
            }
            val to = addressedTo(s)
            if (!isQuestionSentence(s)) {
                if (to == Addressee.Other) anyOther = true
                else if (implicit.containsMatchIn(s)) anyImplicit = true
                continue
            }
            if (to == Addressee.Other) {
                anyOther = true
                continue
            }
            anyQuestion = true
            if (namesTheCandidate(s)) namesCandidate = true
            // "Tell me more." / "Go on." refer back to the answer; "Why?" stays an (elliptical) question and "Describe a time…" a full one.
            if (!(FollowUpIntent.classify(s) == FollowUpKind.Imperative && FollowUpIntent.isStandaloneFollowUp(s))) onlyFollowUps = false
        }
        // A follow-up with no topic of its own takes the addressee of the turn it is in: "Over to you, Maria. Go on." and
        // "John, do you want to take this? Go on." ask a colleague to go on — unless the candidate is named for it.
        if (anyQuestion && onlyFollowUps && anyOther && !namesCandidate)
            return UtteranceVerdict(UtteranceKind.OtherDirected, "follow-up after handing over to another participant", support)
        if (anyQuestion) return if (onlyFollowUps)
            UtteranceVerdict(UtteranceKind.FollowUp, "candidate-directed follow-up", support)
        else
            UtteranceVerdict(UtteranceKind.Question, "question", support)
        if (anyImplicit) return UtteranceVerdict(UtteranceKind.ImplicitPrompt, "statement inviting the candidate to respond", support)
        if (anyOther) return UtteranceVerdict(UtteranceKind.OtherDirected, "addressed to another participant", support)
        if (anyLogistics) return UtteranceVerdict(UtteranceKind.Logistics, "meeting logistics", support)
        return UtteranceVerdict(UtteranceKind.Context, "statement without a question", support)
    }

    /** Who a sentence is addressed to. A name in vocative position that is not the candidate's ("John, what do you
     * think?", "What's your view, Sarah?") or a hand-over between interviewers means someone else. Without a name the
     * candidate is assumed (one-to-one interviews are the norm). */
    fun addressedTo(sentence: String): Addressee {
        var s = discoursePrefix.replace(sentence.trim(), "")
        if (s.isNotEmpty()) s = s[0].uppercaseChar() + s.substring(1)
        val h = handOver.find(s)
        if (h != null) {
            if (questionsForOther.containsMatchIn(h.value)) return Addressee.Other
            val hn = h.groups["name"]?.value ?: ""
            if (hn.isNotEmpty() && isOtherName(hn)) return Addressee.Other
        }
        val lead = leadingVocative.find(s)
        if (lead != null && isOtherName(lead.groups["name"]?.value ?: "") && isQuestionSentence(lead.groups["rest"]?.value ?: "")) return Addressee.Other
        val trail = trailingVocative.find(s)
        if (trail != null && isOtherName(trail.groups["name"]?.value ?: "")) return Addressee.Other
        val bare = bareTrailingName.find(s)
        if (bare != null && isOtherName(bare.groups["name"]?.value ?: "")) {
            val ask = bare.groups["ask"]?.value?.trimEnd(' ', ',') ?: ""
            if (completeBeforeName.containsMatchIn(ask) && FollowUpIntent.isStandaloneFollowUp(ask)) return Addressee.Other
        }
        if (candidateNameSet.isNotEmpty() && thirdPersonPronoun.containsMatchIn(s) && thirdPersonTopic.containsMatchIn(s))
            return Addressee.Other // discussing the candidate in the third person with a colleague
        return Addressee.Candidate
    }

    /** The sentence calls the candidate by name ("Shervin, go on.", "Go on, Shervin."). */
    private fun namesTheCandidate(sentence: String): Boolean {
        if (candidateNameSet.isEmpty()) return false
        val s = sentence.trim()
        val lead = leadingVocative.find(s)
        if (lead != null && candidateNameSet.contains((lead.groups["name"]?.value ?: "").lowercase())) return true
        val trail = trailingVocative.find(s)
        if (trail != null && candidateNameSet.contains((trail.groups["name"]?.value ?: "").lowercase())) return true
        val bare = bareTrailingName.find(s)
        return bare != null && candidateNameSet.contains((bare.groups["name"]?.value ?: "").lowercase())
    }

    private fun isOtherName(name: String): Boolean =
        name.length > 1 && !notNames.contains(name.lowercase()) && !candidateNameSet.contains(name.lowercase()) &&
            !knownTermSet.contains(name.lowercase()) && name[0].isUpperCase()

    companion object {
        /** The gate the live engine uses for this knowledge base (prompt echo, candidate names, known terms). */
        fun `for`(kb: KnowledgeBase): UtteranceGate {
            val names = listOf(kb.context.candidateName, kb.profile.name, kb.profile.preferredName)
            val terms = kb.context.transcriptionTerms() + kb.context.employers + kb.profile.experience.map { it.company }
            return UtteranceGate(PromptEchoDetector(kb.context.buildTranscriptionPrompt(), kb.context.transcriptionTerms()), names, terms)
        }

        /**
         * Elliptical follow-ups that are complete questions on their own even when a recognizer writes them with a falling
         * period ("Why.", "How so.", "What happened.", "And then."). A closed set of English interrogative forms — not a list
         * of transcripts — so a lone "Why" stays answerable while "What a day." does not.
         */
        fun isEllipticalFollowUp(sentence: String): Boolean = ellipticalFollowUp.containsMatchIn(sentence.trim())

        /**
         * The same elliptical follow-ups however they are punctuated ("Why?" and "Why." alike), plus "Really?" when asked:
         * what the turn detector and the classifier treat as a follow-up of the previous answer.
         */
        fun isEllipticalAsk(sentence: String): Boolean {
            val t = sentence.trim()
            return ellipticalFollowUp.containsMatchIn(t) || ellipticalAsked.containsMatchIn(t)
        }

        /**
         * Why a one- or two-word imperative follow-up is not carried by clear speech, or null when it is. So little text is
         * what a recognizer guesses for a knock or a sung note, so (when the audio was analysed) such a turn needs Strong
         * support, a continuous voiced stretch or a judged pitch track (a knock's ringing has neither), and no more voicing than
         * a slow speaker needs for its syllables (≤ 350 ms each — a sung melody or a held vowel has far more).
         */
        fun notClearlySpeech(text: String, evidence: SpeechEvidence, support: AcousticSupport): String? {
            if (evidence.unknown || evidence.incomplete || !evidence.pitchTracked) return null // abstain: no (complete, tracked) audio
            if (support != AcousticSupport.Strong) return "$support support"
            if (evidence.stretchMeasured && !evidence.sustainedVoicing && !evidence.pitchJudged) return "no sustained voicing"
            val syllables = maxOf(2, SpeechPlausibility.syllables(text))
            if (evidence.voicedMs > syllables * maxVoicedMsPerShortSyllable) return "${evidence.voicedMs} ms voiced for $syllables syllables"
            return null
        }

        /** Most voicing per syllable of a one- or two-word follow-up (slow, drawn-out speech stays well below). */
        const val maxVoicedMsPerShortSyllable = 350

        fun isQuestionSentence(s: String): Boolean {
            val t = s.trim()
            if (t.isEmpty()) return false
            if (TextNormalizer.endsWithQuestionMark(t)) return true
            if (ellipticalFollowUp.containsMatchIn(t)) return true
            if (FollowUpIntent.isImperativeAsk(t)) return true
            if (exclamative.containsMatchIn(t)) return false // "What a day." / "How interesting that is." are reactions, not asks
            return questionStart.containsMatchIn(t) && TextNormalizer.wordCount(t) >= 3 || request.containsMatchIn(t) || conditionalAsk.containsMatchIn(t)
        }

        /** "Sorry about that — so how would you prioritise…?" contains a real ask after the logistics phrase. */
        private fun hasRealAskBeyondLogistics(s: String): Boolean {
            val rest = logistics.replace(s, " ")
            return TextNormalizer.wordCount(rest) >= 4 && (questionStart.containsMatchIn(rest.trimStart(' ', ',', '.', '—', '-')) || request.containsMatchIn(rest) || TextNormalizer.questionMarkCount(rest) > 0 && TextNormalizer.wordCount(rest) >= 4)
        }

        private val artefact: Regex = dotNetRegex(
            "^\\W*$|^(thanks?|thank you) for watching|^(please )?(like and )?subscribe|^subtitles? (by|from)|^transcribed by|^\\[?\\(?(music|silence|applause|laughter|inaudible|noise|blank_audio)\\)?\\]?\\.?$|^[♪♫\\s.]+$|^(you|bye|bye-bye|\\.+)\\.?$",
            ignoreCase = true,
        )

        private val logistics: Regex = dotNetRegex(
            "\\b(can you hear me|can everyone hear|do you hear me|can you see (my|the) screen|are you able to see|is my (audio|video|screen|mic)|you('re| are) (on )?mute|you('re| are) muted|you('re| are) breaking up|my connection|your connection|(let me|i('ll| will)) share (my )?screen|(please |can you |could you )?(share|show) (me |us )?(your|the|my) screen|(keep|continue) sharing|stop sharing|(tell (me|us)|let (me|us) know) when (you('re| are)|you’re) (ready|done|finished)|(tell (me|us)|let (me|us) know) (when|if) you can (see|hear) (me|us|my|the)|shall we (start|begin|get started)|are you ready( to (start|begin))?|give (me|us) (a |one )?(second|sec|moment|minute)|one (second|sec|moment)|bear with me|sorry about that|sorry,? (you go|go ahead|after you|i interrupted)|no,? (you go|go ahead)|you first|after you|let's (move on|wrap up|get started|take a (short )?break)|we('ll| will) (move on|come back)|let me (check|find|pull up|open)|i('ll| will) (be )?tak(e|ing) notes|we have (\\d+|a few|about \\d+|around \\d+) minutes|(is|has) everyone (here|joined|ready)|let's wait for|we('re| are) waiting for|(i|we) can('t| not) hear (you|anything))\\b",
            ignoreCase = true,
        )

        private val questionStart: Regex = dotNetRegex(
            "^(and |but |so |okay,? |ok,? |now,? |then,? |also,? )?(how|what|what's|why|when|where|which|who|whom|whose|can|could|would|will|do|does|did|have|has|is|are|was|were|should|shall|may|might|any)\\b",
            ignoreCase = true,
        )

        // Asks that are asks wherever they appear ("…so tell me how you did it", "I'd love to hear about…").
        private val request: Regex = dotNetRegex(
            "\\b(tell (me|us)|walk (me|us)|talk (me|us) through|take (me|us) through|run (me|us) through|help (me|us) understand|let's (talk|hear) about|i'?d (like|love) (to )?(hear|know|understand|learn)|i('m| am) (curious|interested) (about|to|in)|i want to (hear|know|understand)|what about|how about|thoughts on|your (view|take|thoughts|opinion) on|convince (me|us)|pitch (me|us)|sell (me|us)|let's say|say you)\\b" +
                // Imperative verbs only count as an ask in imperative position: at the start of the sentence (after a discourse
                // marker), after "please", or after "can/could/would you" / "I want you to" — "hard to explain in the app" is not.
                "|(^|[,;:]\\s*|\\b(please|(can|could|would|will) you( please)?|(i|we)( want| need| would like|'d like|’d like) you to)\\s+)(please\\s+)?(describe|explain|give (me|us)|share|elaborate|expand on|go deeper|imagine|suppose)\\b" +
                "|^((so|okay|ok|now|well|right|and),?\\s+)+(please\\s+)?(describe|explain|give (me|us)|share|elaborate|expand on|go deeper|imagine|suppose)\\b",
            ignoreCase = true,
        )

        private const val ellipticalLead = "^((and|so|but|okay|ok|right|hm+|mm+)[,.]?\\s+)?"
        private const val ellipticalCore = "(why( not| so| exactly| is that| was that)?|how( so| come| exactly| do you mean)?|what happened( next| then| after that)?|what then|then what|and then( what)?|what else|anything else|like what|such as|for example|in what way|meaning|what do you mean|how did that go|what was the (result|outcome|impact))"

        private val ellipticalFollowUp: Regex = dotNetRegex(ellipticalLead + ellipticalCore + "[.!…]*$", ignoreCase = true)
        private val ellipticalAsked: Regex = dotNetRegex(ellipticalLead + "(" + ellipticalCore + "|really)\\s*[?？؟]+[.!…]*$", ignoreCase = true)

        private val exclamative: Regex = dotNetRegex(
            "^((and|so|oh|well|wow)[,.]?\\s+)?(what an? |how (very |really )?(nice|great|good|interesting|cool|lovely|wonderful|funny|true|kind|exciting|impressive|fascinating|amazing)\\b)",
            ignoreCase = true,
        )

        private val conditionalAsk: Regex = dotNetRegex("\\bif you\\b.*\\b(what|how|which|would you|will you|do you)\\b", ignoreCase = true)

        private val implicit: Regex = dotNetRegex(
            "\\b(i see (that )?you|i noticed (that )?you|i saw (that )?you|i read (that )?you|looking at your|according to your|your (cv|resume|résumé|profile|background|linkedin) (says|shows|mentions|lists)|it says (here )?(that )?you)\\b|^(so,? )?you('ve| have)? (were|worked|led|managed|built|spent|joined|moved|mentioned|said)\\b",
            ignoreCase = true,
        )

        private val handOver: Regex = dotNetRegex(
            "(?i:\\b(do|did) you have any (more |other |further |follow-?up )?questions for (him|her|them)\\b|\\b(over to you|your turn|want to (ask|jump in|add|take (this|that|over))|anything to add|hear from|hand (it |this |that )?over to|pass (it |this |that )?(over )?to))\\s*,?\\s*(?<name>[A-Z][\\p{L}'-]+)?",
        )

        private val questionsForOther: Regex = dotNetRegex("questions for (him|her|them)", ignoreCase = true)
        private val thirdPersonPronoun: Regex = dotNetRegex("\\b(he|she)('s| is| was| has| did| does)\\b", ignoreCase = true)
        private val thirdPersonTopic: Regex = dotNetRegex("\\b(what do you think|do you agree|anything to add)\\b", ignoreCase = true)
        private val discoursePrefix: Regex = dotNetRegex("^((okay|ok|so|right|and|well|now|alright|great|thanks|thank you|perfect)[,.]?\\s+)+", ignoreCase = true)

        // Capitalised sentence openers that are not names.
        private val notNames: Set<String> = setOf(
            "so", "okay", "ok", "now", "well", "right", "great", "thanks", "thank", "yes", "yeah", "no", "and", "but", "also", "actually", "sure", "perfect", "alright", "then", "next",
            "first", "second", "finally", "look", "listen", "sorry", "again", "imagine", "suppose", "honestly", "interesting", "excellent", "good", "cool", "fine", "hmm", "oh",
            "ah", "hey", "hi", "hello", "today", "here", "basically", "lastly", "overall", "ultimately", "additionally", "otherwise", "instead", "meanwhile", "personally",
            "practically", "technically", "realistically", "ideally", "generally", "typically", "usually", "normally", "currently", "recently", "previously", "specifically",
            "briefly", "quickly", "simply", "clearly", "obviously", "fortunately", "unfortunately", "interestingly", "importantly", "similarly", "however", "therefore",
            "anyway", "besides", "indeed", "nice", "wonderful", "understood", "got", "fair", "true", "exactly", "absolutely", "definitely", "totally", "agreed", "amazing",
            "brilliant", "lovely", "awesome", "sounds", "makes", "mm", "uh", "um", "please", "quick", "last", "one", "two", "three", "in", "on", "for", "with", "given", "based",
            "speaking", "talking", "regarding", "going", "moving", "coming", "switching", "shifting", "building", "thinking", "looking", "what", "how", "why", "which",
            // Topics that are often fronted ("Kubernetes, have you used it?") and are not names.
            "python", "java", "javascript", "typescript", "kubernetes", "docker", "react", "angular", "azure", "aws", "amazon", "google", "microsoft", "apple", "meta",
            "salesforce", "excel", "tableau", "figma", "confluence", "jira", "notion", "slack", "zoom", "teams", "asana", "linear", "stripe", "paddle", "ethereum",
            "bitcoin", "solana", "binance", "coinbase", "scrum", "agile", "kanban", "safe", "lean", "sql", "postgres", "kafka", "terraform", "linux", "windows", "android",
            "ios", "europe", "america", "asia", "london", "berlin", "dubai", "vilnius", "lithuania", "germany", "compliance", "engineering", "marketing", "sales", "finance",
            "legal", "product", "design", "security", "operations", "support", "data", "growth", "pricing", "retention", "onboarding", "blockchain", "crypto",
        )

        private val leadingVocative: Regex = dotNetRegex("^(?<name>[A-Z][\\p{L}'-]{1,20})(\\s+[A-Z][\\p{L}'-]{1,20})?\\s*[,—–:]\\s*(?<rest>.+)$")
        private val trailingVocative: Regex = dotNetRegex(",\\s*(?<name>[A-Z][\\p{L}'-]{1,20})\\s*[?.!]?\\s*$")
        // "Go on John." / "Tell me more Maria." — a name right after a complete follow-up, without the comma recognizers often drop.
        private val bareTrailingName: Regex = dotNetRegex("^(?<ask>.+?)\\s+(?<name>[A-Z][\\p{L}'-]{1,20})\\s*[?.!…]*\\s*$")
        // The follow-up is complete before that name: it ends on a word that takes no object ("more", "on", "going", "that",
        // "elaborate"…). "Explain Snowflake." ends on a transitive verb: the capitalised word is its topic, not an addressee.
        private val completeBeforeName: Regex = dotNetRegex("\\b(more|on|going|further|deeper|that|this|it|again|elaborate|continue|example|examples|detail|details|specifics|specific|please)$", ignoreCase = true)
    }
}
