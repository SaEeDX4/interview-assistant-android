package com.interviewassistant.core.ingestion

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.DocumentKind
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.ProfileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of `InterviewAssistant.Tests/ProfileIngestionTests.cs` — same names, inputs and expected values. */
class ProfileIngestionTests {

    @Test
    fun resumeParserExtractsAllFiveRolesWithExactFacts() {
        val p = Fixtures.shervinProfile(confirm = false)
        assertEquals("Shervin Fallahdoust", p.name)
        assertEquals("Business Management | Marketing & Operations Leader", p.headline)
        val roles = p.facts.filter { it.kind == FactKind.Role }
        assertEquals(
            listOf("Gabrielyte UAB", "Arzif Crypto Exchange", "LG Electronics", "Philips", "Epson"),
            roles.map { it.company },
        )
        assertEquals(
            listOf("Executive Director", "Business Management Lead", "Senior Product Marketing Manager", "Director of Sales and Marketing", "Sales Manager"),
            roles.map { it.title },
        )
        assertEquals("2019 – 2023", roles[1].period)
        assertEquals("2023 – Present", roles[0].period)
        val arzif = p.facts.filter { it.parentId == roles[1].id }
        assertEquals(6, arzif.size)
        assertTrue(arzif.any { it.text.contains("60,000 active traders") && it.metrics.contains("60,000") })
        assertTrue(arzif.any { it.text.contains("Binance, CoinEx, and KuCoin") })
        val epson = p.facts.filter { it.parentId == roles[4].id }
        assertTrue(epson.any { it.metrics.contains("128") && it.metrics.contains("254") })
        assertEquals(28, p.facts.count { it.kind == FactKind.Achievement }) // 6+6+6+5+5 bullets on the CV
        assertEquals(2, p.facts.count { it.kind == FactKind.Education })
        assertTrue(p.facts.any { it.kind == FactKind.Education && it.text.contains("Computer Software Engineering") && it.company == "University of Tehran" })
        assertEquals(3, p.facts.count { it.kind == FactKind.Language })
        assertEquals(10, p.facts.count { it.kind == FactKind.Skill })
        assertEquals("en", p.documents[0].language)
    }

    @Test
    fun everyFactHasProvenanceAndStartsUnverified() {
        val p = Fixtures.shervinProfile(confirm = false)
        val lines = p.documents[0].text.split('\n')
        for (f in p.facts) {
            assertEquals(FactStatus.Source, f.status)
            assertEquals(false, f.isVerifiedClaim)
            assertTrue(f.provenance != null)
            assertEquals(p.documents[0].id, f.provenance!!.documentId)
            assertTrue(f.provenance!!.lineStart in 1..lines.size)
        }
        val traders = p.facts.first { it.text.contains("60,000") }
        assertTrue(lines[traders.provenance!!.lineStart - 1].contains("60,000 active traders"))
    }

    @Test
    fun unconfirmedFactsNeverReachTheEngineProfile() {
        val p = Fixtures.shervinProfile(confirm = false)
        assertTrue(ProfileService.toEngineProfile(p).experience.isEmpty())
        p.confirmAll { it.kind == FactKind.Role || it.text.contains("Arzif") || it.company == "Arzif Crypto Exchange" }
        val engine = ProfileService.toEngineProfile(p)
        assertEquals(5, engine.experience.size)
        for (e in engine.experience.filter { it.company != "Arzif Crypto Exchange" }) assertTrue(e.achievements.isEmpty())
        assertTrue(engine.verifiedNumbers.contains("60,000"))
        assertEquals(false, engine.verifiedNumbers.contains("128")) // Epson achievement not confirmed
        val rejected = p.facts.first { it.text.contains("60,000") }
        rejected.status = FactStatus.Rejected
        assertTrue(ProfileService.toEngineProfile(p).experience.flatMap { it.achievements }.none { it.contains("60,000") })
    }

    @Test
    fun ambiguousRolesAreFlaggedNotGuessed() {
        val doc = ProfileService.createDocument("x.txt", DocumentKind.Resume, "Jane Doe\nAnalyst\n\nEXPERIENCE\nAcme Corp | 2024 – 2019\n• Did things that grew sales by 10%.\n")
        val parsed = ResumeParser.parse(doc)
        val role = parsed.facts.single { it.kind == FactKind.Role }
        assertEquals("Acme Corp", role.company)
        assertTrue(role.flags.contains("missing-title"))
        assertTrue(role.flags.contains("end-before-start"))
        assertTrue(role.confidence < 0.6)
    }

    @Test
    fun germanResumeParsesWithLocalizedHeadings() {
        val p = CandidateProfileRecord()
        ProfileService.addResume(p, ProfileService.createDocument("maria.txt", DocumentKind.Resume, Fixtures.text("resumes", "maria_keller_de.txt")))
        assertEquals("de", p.documents[0].language)
        assertEquals("Maria Keller", p.name)
        val roles = p.facts.filter { it.kind == FactKind.Role }
        assertEquals(listOf("PayFlow GmbH", "DataWerk AG"), roles.map { it.company })
        assertEquals("2020 – heute", roles[0].period)
        assertTrue(p.facts.any { it.kind == FactKind.Achievement && it.metrics.contains("40%") })
        assertTrue(p.facts.any { it.kind == FactKind.Skill && it.text == "Kafka" })
    }

    @Test
    fun docxIsExtracted() {
        val bytes = Fixtures.buildDocx(
            listOf("JOHN SMITH", "Product Manager", "EXPERIENCE", "Product Manager", "Contoso | Seattle | 2018 – 2022", "• Grew activation by 35% through onboarding redesign."),
            bulletLast = true,
        )
        val (doc, err) = ProfileService.upload("john.docx", bytes, DocumentKind.Resume)
        assertNull(err)
        val parsed = ResumeParser.parse(doc!!)
        assertEquals("John Smith", parsed.name)
        val ach = parsed.facts.single { it.kind == FactKind.Achievement }
        assertEquals("Contoso", ach.company)
        assertTrue(ach.metrics.contains("35%"))
    }

    @Test
    fun pdfIsExtracted() {
        val lines = listOf("JANE DOE", "Engineering Manager", "EXPERIENCE", "Engineering Manager", "Fabrikam | Berlin | 2019 - 2024", "- Reduced incidents by 45% across 12 services.")
        val (doc, err) = ProfileService.upload("jane.pdf", Fixtures.buildPdf(lines), DocumentKind.Resume)
        assertNull(err)
        val parsed = ResumeParser.parse(doc!!)
        assertEquals("Jane Doe", parsed.name)
        assertEquals("Fabrikam", parsed.facts.single { it.kind == FactKind.Role }.company)
        assertTrue(parsed.facts.any { it.kind == FactKind.Achievement && it.metrics.contains("45%") })
    }

    @Test
    fun imageOnlyPdfAsksForTextInsteadOfReturningNothing() {
        val (doc, err) = ProfileService.upload("scan.pdf", Fixtures.buildPdf(null), DocumentKind.Resume)
        assertNull(doc)
        assertTrue(err!!.contains("images"))
    }

    @Test
    fun multipleCvVersionsDoNotDuplicateFacts() {
        val p = Fixtures.shervinProfile(confirm = false)
        val before = p.facts.size
        ProfileService.addResume(p, ProfileService.createDocument("v2.txt", DocumentKind.Resume, Fixtures.text("resumes", "shervin_fallahdoust.txt")))
        assertEquals(before, p.facts.size)
        assertEquals(2, p.documents.size)
    }
}
