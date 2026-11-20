package com.interviewassistant.core.domain

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests for the domain model rules of `Models.cs` and `ProfileMigration.cs` (P7
 * coverage closure): the confirmation state machine on [ProfileFact], the single
 * [FactStats] derivation, [CandidateProfileRecord.confirmAll], the personal-category table,
 * and the RC1→current profile migration (provenance gain, timestamp backfill, confirmation
 * state never changed).
 */
class DomainModelTests {

    companion object {
        private val T0: OffsetDateTime = OffsetDateTime.parse("2026-01-01T09:00:00Z")
        private val T1: OffsetDateTime = OffsetDateTime.parse("2026-01-02T09:00:00Z")
        private val T2: OffsetDateTime = OffsetDateTime.parse("2026-01-03T09:00:00Z")
    }

    // ---- ProfileFact confirmation state machine ----

    @Test
    fun setConfirmed_TransitionsAndTimestamps() {
        val f = ProfileFact(kind = FactKind.Achievement, text = "x", status = FactStatus.Source)
        f.setConfirmed(true, T1)
        assertEquals(FactStatus.UserConfirmed, f.status)
        assertEquals(T1, f.confirmedUtc)
        assertEquals(T1, f.updatedUtc)

        // Confirming an already-confirmed fact changes nothing (the original time stands).
        f.setConfirmed(true, T2)
        assertEquals(T1, f.confirmedUtc)
        assertEquals(T1, f.updatedUtc)

        f.setConfirmed(false, T2)
        assertEquals(FactStatus.Source, f.status)
        assertNull(f.confirmedUtc)
        assertEquals(T2, f.updatedUtc)

        // Un-confirming a fact that is not confirmed changes nothing either.
        f.setConfirmed(false, T1)
        assertEquals(FactStatus.Source, f.status)
        assertEquals(T2, f.updatedUtc)
    }

    @Test
    fun factReviewStates_ByStatus() {
        fun fact(status: FactStatus) = ProfileFact(status = status)
        assertTrue(fact(FactStatus.UserConfirmed).isVerifiedClaim)
        assertFalse(fact(FactStatus.Source).isVerifiedClaim)

        assertTrue(fact(FactStatus.Source).isReviewable)
        assertTrue(fact(FactStatus.Source).awaitingReview)
        assertTrue(fact(FactStatus.AiInference).isReviewable)
        assertTrue(fact(FactStatus.AiInference).awaitingReview)
        assertTrue(fact(FactStatus.UserConfirmed).isReviewable)
        assertFalse(fact(FactStatus.UserConfirmed).awaitingReview)
        assertFalse(fact(FactStatus.Rejected).isReviewable)
        assertFalse(fact(FactStatus.Rejected).awaitingReview)
        assertFalse(fact(FactStatus.GeneratedKnowledge).isReviewable)
        assertFalse(fact(FactStatus.GeneratedKnowledge).awaitingReview)
    }

    // ---- FactStats: the one derivation ----

    @Test
    fun factStats_CountsReviewableOnly_AndSplitsProfileAnswers() {
        val facts = listOf(
            ProfileFact(kind = FactKind.Role, status = FactStatus.UserConfirmed),
            ProfileFact(kind = FactKind.Achievement, status = FactStatus.Source),
            ProfileFact(kind = FactKind.Skill, status = FactStatus.AiInference),
            ProfileFact(kind = FactKind.Personal, status = FactStatus.UserConfirmed),
            ProfileFact(kind = FactKind.Boundary, status = FactStatus.UserConfirmed),
            ProfileFact(kind = FactKind.Personal, status = FactStatus.Source),
            ProfileFact(kind = FactKind.Other, status = FactStatus.Rejected),
            ProfileFact(kind = FactKind.Other, status = FactStatus.GeneratedKnowledge),
        )
        val stats = FactStats.of(facts)
        assertEquals(6, stats.total) // rejected + generated are not reviewable
        assertEquals(3, stats.confirmed)
        assertEquals(3, stats.toReview)
        assertEquals(2, stats.confirmedProfileAnswers) // the confirmed Personal + Boundary
        assertEquals(1, stats.confirmedExperience)
        assertEquals(FactStats(0, 0, 0, 0), FactStats.of(emptyList()))
    }

    @Test
    fun profileRecord_ConfirmedFactsPendingCountAndConfirmAll() {
        val p = CandidateProfileRecord()
        val a = ProfileFact(kind = FactKind.Role, status = FactStatus.Source)
        val b = ProfileFact(kind = FactKind.Skill, status = FactStatus.AiInference)
        val c = ProfileFact(kind = FactKind.Other, status = FactStatus.Rejected)
        val d = ProfileFact(kind = FactKind.Personal, status = FactStatus.UserConfirmed)
        p.facts.addAll(listOf(a, b, c, d))
        assertEquals(listOf(d), p.confirmedFacts)
        assertEquals(2, p.pendingReviewCount)
        assertEquals(2, p.stats.toReview)

        p.confirmAll { it.kind == FactKind.Role }
        assertEquals(FactStatus.UserConfirmed, a.status)
        assertEquals(FactStatus.AiInference, b.status) // filtered out
        assertEquals(1, p.pendingReviewCount)

        p.confirmAll()
        assertEquals(FactStatus.UserConfirmed, b.status)
        assertEquals(FactStatus.Rejected, c.status) // never reviewable, never confirmed
        assertEquals(0, p.pendingReviewCount)
    }

    // ---- PersonalCategories + InterviewTarget ----

    @Test
    fun personalCategories_TheTableAndLookup() {
        assertEquals(21, PersonalCategories.all.size)
        assertEquals(PersonalCategories.all.size, PersonalCategories.all.map { it.key }.distinct().size)
        assertEquals("hobby", PersonalCategories.find("hobby")!!.key)
        assertFalse(PersonalCategories.find("hobby")!!.boundary)
        assertTrue(PersonalCategories.find("avoid_topic")!!.boundary)
        assertTrue(PersonalCategories.find("disallowed_claim")!!.boundary)
        assertNull(PersonalCategories.find("not_a_category"))
        assertNull(PersonalCategories.find(null))
    }

    @Test
    fun interviewTarget_DisplayName() {
        assertEquals("Dev", InterviewTarget(jobTitle = "Dev", company = "").displayName)
        assertEquals("Dev · Acme", InterviewTarget(jobTitle = "Dev", company = "Acme").displayName)
    }

    // ---- ProfileMigration ----

    private fun legacyProfile(): CandidateProfileRecord =
        CandidateProfileRecord(schemaVersion = 0, createdUtc = T0, updatedUtc = T1)

    @Test
    fun migration_CurrentProfilesAreUntouched() {
        val p = CandidateProfileRecord(schemaVersion = CandidateProfileRecord.CurrentSchemaVersion)
        val f = ProfileFact(status = FactStatus.Source)
        p.facts.add(f)
        assertFalse(ProfileMigration.migrate(p))
        assertNull(f.createdUtc)
    }

    @Test
    fun migration_GainsProvenanceAndTimestamps_WithoutChangingConfirmation() {
        val p = legacyProfile()
        p.documents.add(SourceDocument(id = "doc-1", fileName = "cv.pdf"))
        val imported = ProfileFact(
            status = FactStatus.Source, source = FactSource.Unknown,
            provenance = Provenance("doc-1", 1, 2, "line"),
        )
        val confirmed = ProfileFact(status = FactStatus.UserConfirmed, confirmedUtc = null)
        val plain = ProfileFact(status = FactStatus.Source, createdUtc = T2)
        p.facts.addAll(listOf(imported, confirmed, plain))

        assertTrue(ProfileMigration.migrate(p))
        assertEquals(CandidateProfileRecord.CurrentSchemaVersion, p.schemaVersion)

        // Unknown + provenance -> ResumeImport named after the source document.
        assertEquals(FactSource.ResumeImport, imported.source)
        assertEquals("cv.pdf", imported.sourceName)
        // Missing timestamps are backfilled from the profile's own.
        assertEquals(T0, imported.createdUtc)
        assertEquals(T1, confirmed.confirmedUtc)
        assertEquals(T0, confirmed.createdUtc)
        // Existing values and confirmation states are never overwritten.
        assertEquals(T2, plain.createdUtc)
        assertEquals(FactStatus.Source, imported.status)
        assertEquals(FactStatus.UserConfirmed, confirmed.status)

        assertFalse(ProfileMigration.migrate(p)) // already current now
    }

    @Test
    fun migration_KeepsExistingSourceName_WhenTheDocumentIsGone_AndLeavesKnownSourcesAlone() {
        val p = legacyProfile()
        val orphan = ProfileFact(
            status = FactStatus.Source, source = FactSource.Unknown, sourceName = "old name",
            provenance = Provenance("missing-doc", 0, 0, ""),
        )
        val manual = ProfileFact(
            status = FactStatus.Source, source = FactSource.Manual,
            provenance = Provenance("doc-1", 0, 0, ""),
        )
        val noProvenance = ProfileFact(status = FactStatus.Source, source = FactSource.Unknown)
        p.facts.addAll(listOf(orphan, manual, noProvenance))
        assertTrue(ProfileMigration.migrate(p))
        assertEquals(FactSource.ResumeImport, orphan.source) // provenance exists: still an import
        assertEquals("old name", orphan.sourceName) // but its document is gone: the name stays
        assertEquals(FactSource.Manual, manual.source) // a known source is never rewritten
        assertEquals(FactSource.Unknown, noProvenance.source) // no provenance: nothing to infer
    }

    @Test
    fun migration_ToleratesDuplicateDocumentIds_FirstNameWins() {
        val p = legacyProfile()
        p.documents.add(SourceDocument(id = "dup", fileName = "first.pdf"))
        p.documents.add(SourceDocument(id = "dup", fileName = "second.pdf"))
        val f = ProfileFact(status = FactStatus.Source, source = FactSource.Unknown, provenance = Provenance("dup", 0, 0, ""))
        p.facts.add(f)
        assertTrue(ProfileMigration.migrate(p))
        assertEquals("first.pdf", f.sourceName)
    }
}
