package com.interviewassistant.core.reports

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.SecretFilter
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Reports-package cases of `InterviewAssistant.Tests/Rc5DataExportTests.cs`, deferred BY NAME from
 * Package A (P3-PORT-NOTES.md §2): durations, export labels, and the account/profile HTML renderers.
 * The HTML assertion of `NoSecretsInAnyProfileExport` lives here as [profileHtml_HasNoSecrets] /
 * the secret checks inside the account tests; the A-side export test itself is in persistence/.
 */
class Rc5DataExportReportTests {

    companion object {
        private const val FakeApiKey = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWx1234" // fake-credential: test fixture
        private const val FakeJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        private val Languages = listOf("en", "es", "fr", "de", "pt", "it", "ar", "fa", "zh", "hi")

        @JvmStatic
        @BeforeClass
        fun installLoc() {
            Fixtures.installLocTables()
        }
    }

    // ------------------------------------------------------------------ human formatting and labels

    @Test
    fun durationsReadLikeAPerson() {
        val cases = listOf(
            0L to "0 sec", 59L to "59 sec", 60L to "1 min", 4843L to "1 hr 20 min 43 sec",
            3600L to "1 hr", 3605L to "1 hr 5 sec", -5L to "0 sec",
        )
        for ((seconds, expected) in cases) assertEquals(expected, ExportLabels.duration("en", seconds))
    }

    @Test
    fun durationsAreLocalized() {
        assertEquals("1 Std. 20 Min. 43 Sek.", ExportLabels.duration("de", 4843))
        assertEquals("1 小时 20 分钟 43 秒", ExportLabels.duration("zh", 4843))
        assertEquals("0 ثانیه", ExportLabels.duration("fa", 0))
    }

    @Test
    fun exportLabelsExistAndAreTranslatedInAllTenLanguages() {
        for (k in ExportLabels.keys) assertTrue(k, Loc.has(k))
        for (lang in Languages) {
            assertTrue(lang, ExportLabels.isComplete(lang))
            if (lang == "en") continue
            val t = Loc.tableFor(lang)
            // the titles and section headings are real translations, not the English fallback
            for (k in listOf("exp_acct_title", "exp_prof_title", "exp_section_devices", "exp_section_sessions", "exp_technical", "exp_section_review")) {
                assertNotEquals(k, Loc.get("en", k), t[k])
            }
        }
        assertEquals(ExportLabels.keys.size, ExportLabels.keys.distinct().size)
    }

    // ------------------------------------------------------------------ account export (readable HTML)

    private fun sampleAccountExport(): JsonElement = Json.parseToJsonElement(
        """
        {
          "format": "interview-assistant-export/v1", "exportedUtc": "2026-10-17T09:30:00Z",
          "account": { "id": "6f1c2a7e-0000-4000-8000-000000000001", "email": "jane@example.com", "status": "active", "createdUtc": "2026-01-05T08:00:00Z", "password": "hunter2" },
          "subscription": { "planCode": "pro_monthly", "status": "past_due", "currentPeriodEndUtc": "2026-11-01T00:00:00Z", "providerSecret": "whsec_x" },
          "devices": [
            { "name": "<b>Jane's laptop</b>", "platform": "Windows 11", "createdUtc": "2026-01-05T08:00:00Z", "lastSeenUtc": "2026-10-16T18:00:00Z", "revokedUtc": null },
            { "name": "Old PC", "platform": "Windows 10", "createdUtc": "2025-12-01T08:00:00Z", "lastSeenUtc": "2026-02-01T18:00:00Z", "revokedUtc": "2026-03-01T10:00:00Z" }
          ],
          "documents": [ { "id": "d1", "kind": "profile", "parentId": null, "data": { "Name": "Jane", "accessToken": "$FakeJwt" }, "updatedUtc": "2026-10-01T10:00:00Z" },
                         { "id": "d2", "kind": "report", "parentId": "t1", "data": { "note": "$FakeApiKey" }, "updatedUtc": "2026-10-02T10:00:00Z" } ],
          "usage": [ { "kind": "live_seconds", "total": 4843 }, { "kind": "answer_request", "total": 37 }, { "kind": "prep_job", "total": 2 } ],
          "sessions": [
            { "id": "11111111-2222-3333-4444-555555555555", "startedUtc": "2026-10-10T14:00:00Z", "endedUtc": "2026-10-10T14:45:30Z", "meteredSeconds": 2730, "endReason": "client_stop" },
            { "id": "66666666-7777-8888-9999-000000000000", "startedUtc": "2026-10-12T14:00:00Z", "endedUtc": null, "meteredSeconds": 59, "endReason": null }
          ]
        }
        """,
    )

    @Test
    fun accountHtml_IsHuman_Escaped_AndHasNoSecrets() {
        val html = AccountExportRenderer.render(sampleAccountExport(), "en", "Interview Assistant", zone = ZoneId.of("UTC"))
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("default-src 'none'")) // self-contained: no scripts or remote content
        assertFalse(html.lowercase().contains("<script"))
        assertTrue(html.contains("dir=\"ltr\""))
        assertTrue(html.contains("Your account data"))
        assertTrue(html.contains("Live interview usage"))
        assertTrue(html.contains("1 hr 20 min 43 sec"))
        assertTrue(html.contains("45 min 30 sec")) // session duration
        assertTrue(html.contains("59 sec")) // metered time of the running session
        assertTrue(html.contains("Payment overdue")) // billing state in words
        assertTrue(html.contains("Renews on"))
        assertTrue(html.contains("Stopped by you"))
        assertTrue(html.contains("Still running"))
        assertTrue(html.contains("Removed on"))
        assertTrue(html.contains("&lt;b&gt;Jane&#39;s laptop&lt;/b&gt;")) // every value HTML-encoded
        assertFalse(html.contains("<b>Jane"))
        assertTrue(html.contains("<details><summary>Show technical details</summary>"))
        assertTrue(html.contains("6f1c2a7e-0000-4000-8000-000000000001")) // ids only in the technical section
        assertTrue(html.indexOf("<details>") < html.indexOf("6f1c2a7e"))
        assertTrue(html.contains("January 5, 2026")) // dates in the user's culture (member since)
        for (secret in listOf("hunter2", "whsec_x", FakeJwt, FakeApiKey, "accessToken", "providerSecret")) {
            assertFalse(secret, html.contains(secret))
        }
        assertFalse(SecretFilter.looksSecret(html))
    }

    @Test
    fun accountHtml_UsesTheDesktopPlanWordsWhenGiven() {
        val html = AccountExportRenderer.render(
            sampleAccountExport(), "en",
            context = AccountExportContext("Pro", "Pro · payment problem"), zone = ZoneId.of("UTC"),
        )
        assertTrue(html.contains("<bdi>Pro</bdi>"))
        assertTrue(html.contains(ExportLabels.htmlEncode("Pro · payment problem")))
        assertFalse(html.contains("pro_monthly"))
    }

    @Test
    fun accountHtml_EmptyExportStillRendersEverySection() {
        val html = AccountExportRenderer.render(
            Json.parseToJsonElement("{}"), "en",
            zone = ZoneId.of("UTC"), nowUtc = OffsetDateTime.of(2026, 10, 17, 9, 0, 0, 0, ZoneOffset.UTC),
        )
        assertTrue(html.contains("No paid subscription"))
        assertTrue(html.contains("0 sec"))
        assertTrue(html.contains("Devices (0)"))
    }

    @Test
    fun accountHtml_IsLocalizedAndMirroredForRtl() {
        val cases = listOf(
            "en" to "ltr", "es" to "ltr", "fr" to "ltr", "de" to "ltr", "pt" to "ltr",
            "it" to "ltr", "ar" to "rtl", "fa" to "rtl", "zh" to "ltr", "hi" to "ltr",
        )
        for ((lang, dir) in cases) {
            val html = AccountExportRenderer.render(sampleAccountExport(), lang, zone = ZoneId.of("UTC"))
            assertTrue(lang, html.contains("lang=\"$lang\" dir=\"$dir\""))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(Loc.get(lang, "exp_acct_title"))))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(ExportLabels.duration(lang, 4843))))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(Loc.get(lang, "exp_sub_past_due"))))
            assertTrue(lang, html.contains("<span class=\"ltr\">jane@example.com</span>")) // e-mail stays left-to-right in RTL pages
            assertFalse(lang, html.contains("hunter2"))
        }
    }

    // ------------------------------------------------------------------ profile export (readable HTML)

    private fun fixtureProfile(): CandidateProfileRecord {
        val p = Fixtures.shervinProfile(confirm = false)
        p.name = "Shervin <Fallahdoust>"
        p.headline = "Product Owner & payments"
        // confirm the roles and their first achievement, leave the rest to review
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        for (role in p.facts.filter { f -> f.kind == FactKind.Role }) role.setConfirmed(true, now)
        val achievement = p.facts.first { f -> f.kind == FactKind.Achievement && f.parentId != null }
        achievement.setConfirmed(true, now)
        ProfileService.setPersonal(p, "hobby", "Sailing <img src=x onerror=alert(1)>")
        ProfileService.setPersonal(p, "avoid_topic", "My previous salary")
        return p
    }

    @Test
    fun profileHtml_GroupsConfirmedExperienceByRole_MarksFactsToReview_AndEscapes() {
        val p = fixtureProfile()
        val html = ProfileExportRenderer.render(p, "en", zone = ZoneId.of("UTC"))
        assertTrue(html.contains("Shervin &lt;Fallahdoust&gt;"))
        assertTrue(html.contains("Product Owner &amp; payments"))
        assertTrue(html.contains("Confirmed experience"))
        val role = p.facts.first { f -> f.kind == FactKind.Role && f.isVerifiedClaim }
        assertTrue(html.contains("<h3><bdi dir=\"auto\">" + ExportLabels.htmlEncode(ProfileExportRenderer.factText(role)) + "</bdi>"))
        val achievement = p.facts.first { f -> f.kind == FactKind.Achievement && f.isVerifiedClaim }
        val confirmedAt = html.indexOf(ExportLabels.htmlEncode(achievement.text))
        val reviewAt = html.indexOf("<h2>Still to review")
        assertTrue("confirmed facts are listed under their role, before the review section", confirmedAt > 0 && confirmedAt < reviewAt)
        val pending = p.facts.first { f -> f.awaitingReview && f.kind == FactKind.Achievement }
        assertTrue("unconfirmed facts only in the review section", html.indexOf(ExportLabels.htmlEncode(pending.text)) > reviewAt)
        assertTrue(html.contains("<span class=\"badge\">To review</span>"))
        assertTrue(html.contains("Still to review (${p.stats.toReview})"))
        assertTrue(html.contains("Interview profile answers"))
        assertTrue(html.contains("<strong>Hobbies:</strong>"))
        assertTrue(html.contains("<strong>Never mention or speculate about:</strong>"))
        assertTrue(html.contains("Sailing &lt;img src=x onerror=alert(1)&gt;"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("Source: from shervin.txt")) // provenance in words
        assertTrue(html.contains("Source: interview profile"))
        assertTrue(html.contains("Source documents"))
        assertTrue(html.contains("<details><summary>Show technical details</summary>"))
        assertFalse(html.contains(ExportLabels.htmlEncode(p.documents[0].text))) // document text is never exported in the readable profile
    }

    @Test
    fun profileHtml_IsLocalized() {
        val cases = listOf("fa" to "rtl", "ar" to "rtl", "de" to "ltr", "hi" to "ltr")
        for ((lang, dir) in cases) {
            val html = ProfileExportRenderer.render(fixtureProfile(), lang, zone = ZoneId.of("UTC"))
            assertTrue(lang, html.contains("lang=\"$lang\" dir=\"$dir\""))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(Loc.get(lang, "exp_prof_title"))))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(Loc.get(lang, "exp_section_review"))))
            assertTrue(lang, html.contains(ExportLabels.htmlEncode(Loc.get(lang, "pc_hobby_label"))))
        }
    }

    @Test
    fun profileHtml_HasNoSecrets() {
        val p = fixtureProfile()
        ProfileService.setPersonal(p, "answer_style", "Simple English. My key: " + FakeApiKey)
        val html = ProfileExportRenderer.render(p, "en")
        assertFalse(html.contains(FakeApiKey))
        assertFalse(SecretFilter.looksSecret(html))
    }
}
