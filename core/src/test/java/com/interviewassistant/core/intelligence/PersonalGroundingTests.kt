package com.interviewassistant.core.intelligence

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/PersonalGroundingTests.cs` — RC4: a personal answer says what the
 * candidate confirmed and nothing more. With the hobby "Hiking and photography" the answer may say "In my
 * free time I enjoy hiking and photography" — never that it keeps them grounded, taught them patience,
 * happens every weekend or makes them a better product owner.
 *
 * The three Engine_* cases (InterviewSimulator) are deferred to the Orchestration package — see P3-PORT-NOTES.md.
 */
class PersonalGroundingTests {
    companion object {
        private val hobby: List<String> = listOf("Hiking and photography")
        private const val q = "What are your hobbies?"
    }

    @Test
    fun rephrasingTheConfirmedFactIsGrounded() {
        val cases = listOf(
            "In my free time I enjoy hiking and photography.",
            "I enjoy hiking and photography.",
            "Hiking and photography.",
            "Outside work, I like to hike and take photos.",
            "My hobbies are hiking and photography.",
        )
        for (bullet in cases)
            assertTrue(PersonalGrounding.unsupported(bullet, hobby, q).joinToString(", "), PersonalGrounding.isGrounded(bullet, hobby, q))
    }

    @Test
    fun addedTraitsFeelingsBenefitsOrDetailsAreNotGrounded() {
        val cases = listOf(
            "Hiking keeps me grounded and helps me recharge.",
            "Photography taught me patience and an eye for detail.",
            "I go hiking every weekend in the mountains.",
            "It reflects my curiosity, which I bring to product work.",
            "I'm passionate about capturing landscapes.",
            "Being outdoors gives me energy and a fresh perspective.",
        )
        for (bullet in cases)
            assertFalse(bullet, PersonalGrounding.isGrounded(bullet, hobby, q))
    }

    @Test
    fun motivationMayBeRephrasedNotEmbellished() {
        val facts = listOf("Building products people use every day")
        val question = "What motivates you?"
        assertTrue(PersonalGrounding.isGrounded("What motivates me is building products people use every day.", facts, question))
        assertFalse(PersonalGrounding.isGrounded("I find it deeply rewarding to see my impact on millions of users.", facts, question))
    }
}

/** RC4: confirming a hobby must not make one count say 44 and another 45 without saying why. */
class FactCountSemanticsTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() = Fixtures.installLocTables()
    }

    @Test
    fun addingAHobbyIsCountedAsAnInterviewProfileAnswerNotAsAnExperienceFact() {
        val p = CandidateProfileRecord()
        for (i in 0 until 44) {
            val f = ProfileFact(kind = FactKind.Achievement, text = "Fact $i")
            f.setConfirmed(true, OffsetDateTime.now(ZoneOffset.UTC))
            p.facts.add(f)
        }
        assertEquals(44, p.stats.confirmedExperience)
        ProfileService.setPersonal(p, "hobby", "Hiking and photography")
        assertEquals(45, p.stats.confirmed)
        assertEquals(44, p.stats.confirmedExperience)
        assertEquals(1, p.stats.confirmedProfileAnswers)
        val text = Loc.format("en", "toast_profile_saved_detail", p.stats.confirmedExperience, p.stats.confirmedProfileAnswers)
        assertTrue(text, text.startsWith("44 confirmed experience facts + 1 interview-profile answers."))
    }

    @Test
    fun splitCountKeysHaveTheirPlaceholdersInEveryLanguage() {
        for (l in LanguageRegistry.all) {
            val start = Loc.format(l.code, "start_profile_status", 1, 44, 1, 3)
            assertTrue(l.code, start.contains("44"))
            assertTrue(l.code, start.contains("3"))
            val row = Loc.format(l.code, "profile_confirmed_count", 44, 1)
            assertTrue(l.code, row.contains("44"))
            assertTrue(l.code, row.contains("1"))
        }
    }
}
