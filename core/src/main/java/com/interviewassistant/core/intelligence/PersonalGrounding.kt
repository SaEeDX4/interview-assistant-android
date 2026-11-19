package com.interviewassistant.core.intelligence

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/PersonalGrounding.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Grounding check for answers to personal questions (hobbies, motivation, team preference…). The candidate confirmed
 * facts such as "Hiking and photography"; an answer may rephrase them ("In my free time I enjoy hiking and
 * photography") but must not add traits, feelings, benefits, frequencies, places or links the candidate never stated
 * ("it keeps me grounded", "it taught me patience", "every weekend"). A bullet passes only when every content word comes
 * from the facts, the question, or a small set of neutral framing words.
 * English only: other answer languages rely on the prompt rule (their wording cannot be compared word for word).
 */
object PersonalGrounding {
    // Neutral framing that states a fact without adding to it. "enjoy"/"like" are the minimal verbs for a pastime.
    private val framing: Set<String> = TextNormalizer.tokenize(
        "enjoy like spend free time outside work hobby hobbies interest interests personally mostly mainly both thing things " +
            "prefer preferred best ideal motivate motivates drive drives team teams style way approach most lot currently answer " +
            "am not no but all its these those being get go take out up here other more such each own same too only well one",
    ).toSet()

    /** Content words of [bullet] that none of the facts, the question or neutral framing supports. */
    fun unsupported(bullet: String, facts: Iterable<String>, question: String): List<String> {
        val allowed = HashSet(framing)
        for (f in facts) allowed.addAll(TextNormalizer.tokenize(f))
        allowed.addAll(TextNormalizer.tokenize(question))
        return TextNormalizer.tokenize(bullet).filter { !supported(it, allowed) }.distinct()
    }

    fun isGrounded(bullet: String, facts: Iterable<String>, question: String): Boolean = unsupported(bullet, facts, question).isEmpty()

    // Light stems differ across word forms (hike/hiking → "hike"/"hik", photos/photography): a shared prefix of at least
    // four letters (or the whole shorter stem) counts as the same word.
    private fun supported(token: String, allowed: Set<String>): Boolean {
        if (allowed.contains(token)) return true
        for (a in allowed) {
            val s = if (a.length <= token.length) a else token
            val l = if (a.length <= token.length) token else a
            if (s.length >= 3 && l.startsWith(s) && (s.length >= 4 || l.length - s.length <= 1)) return true
        }
        return false
    }
}
