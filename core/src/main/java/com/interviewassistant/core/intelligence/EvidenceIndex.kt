package com.interviewassistant.core.intelligence

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.KnowledgeBase
import kotlin.math.ceil
import kotlin.math.max

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/EvidenceIndex.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * The candidate's confirmed history (profile roles/achievements/summary/skills and verified stories) as searchable
 * evidence. It is the truth boundary for first-person past claims: a claim is "supported" only when one confirmed
 * evidence item covers most of its content words. Built per knowledge base — never shared between users.
 */
class EvidenceIndex(profile: CandidateProfile, stories: Iterable<CandidateStory>) {
    private val items = mutableListOf<Set<String>>()

    init {
        for (e in profile.experience) {
            add(e.title + " " + e.company)
            for (a in e.achievements) add(e.company + " " + a)
        }
        if (profile.summary.isNotBlank()) add(profile.summary)
        for (c in profile.coreCompetencies) add(c)
        for (s in stories) add(s.company + " " + s.situation + " " + s.action + " " + s.result + " " + s.skills.joinToString(" "))
    }

    val count: Int get() = items.size

    private fun add(text: String) {
        val t = topic(text)
        if (t.isNotEmpty()) items.add(t)
    }

    /**
     * True when one evidence item covers at least [coverage] of the topic words (min. one word,
     * two when the topic has three or more words). Empty topics are never "supported".
     */
    fun supports(text: String, coverage: Double = 0.6): Boolean {
        val topic = topic(text)
        if (topic.isEmpty()) return false
        val need = max(if (topic.size >= 3) 2 else 1, ceil(topic.size * coverage).toInt())
        return items.any { item -> topic.count { item.contains(it) } >= need }
    }

    companion object {
        // Words that carry no topic on their own in claims/questions ("built a system", "led the project").
        private val weak: Set<String> = setOf(
            "built", "build", "implement", "lead", "led", "manag", "manage", "deliver", "launch", "design", "own", "creat", "create", "develop",
            "ran", "run", "responsible", "personally", "actually", "ever", "before", "previously", "experience", "work", "worked", "done",
            "system", "project", "team", "company", "role", "process", "part", "thing", "way", "end", "full", "whole", "directly", "hands-on",
            "practical", "direct", "used", "use", "one", "new", "exact", "own", "set", "up", "past", "previou", "job", "here", "time",
        )

        fun `for`(kb: KnowledgeBase): EvidenceIndex = EvidenceIndex(kb.profile, kb.stories)

        /** Content words of a question/claim (stemmed, without question/claim scaffolding). */
        fun topic(text: String): Set<String> =
            TextNormalizer.tokenize(text).filter { w -> w.length > 2 && !weak.contains(w) && !numberWord.containsMatchIn(w) }.toSet()

        // Numbers (digits or words) are checked separately by the validator's verified-number rule, not as topic words.
        private val numberWord: Regex = dotNetRegex("^(\\d[\\d.,]*%?|percent|thousand|million|billion|hundred|(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|eighteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(-\\w+)?)$")
    }
}
