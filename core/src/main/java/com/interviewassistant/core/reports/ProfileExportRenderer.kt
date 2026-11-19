package com.interviewassistant.core.reports

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactSource
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.PersonalCategories
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.persistence.SecretFilter
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Mirror of `InterviewAssistant.Core/Reports/ProfileExportRenderer.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * Readable, printable HTML of one candidate profile: name and headline, confirmed experience grouped by role, the
 * interview-profile answers, the facts still to review (clearly marked — the assistant does not use them as claims),
 * where each fact came from in words, and the source documents by name (never their text). Localized, mirrored for
 * Arabic/Persian, every value HTML-encoded and passed through [SecretFilter].
 */
object ProfileExportRenderer {

    fun render(
        p: CandidateProfileRecord,
        langIn: String,
        productName: String = "Interview Assistant",
        zone: ZoneId? = null,
        nowUtc: OffsetDateTime? = null,
    ): String {
        val lang = ExportLabels.code(langIn)
        fun l(k: String): String = ExportLabels.get(lang, k)
        fun f(k: String, vararg a: Any?): String = ExportLabels.format(lang, k, *a)
        fun e(s: String?): String = ExportLabels.htmlEncode(SecretFilter.redact(s ?: ""))
        val now = nowUtc ?: OffsetDateTime.now(ZoneOffset.UTC)

        val facts = p.facts.filter { it.isReviewable }
        fun isAnswer(fact: ProfileFact): Boolean = fact.kind == FactKind.Personal || fact.kind == FactKind.Boundary
        val confirmed = facts.filter { it.isVerifiedClaim && !isAnswer(it) }
        val answers = facts.filter { it.isVerifiedClaim && isAnswer(it) }
        val toReview = facts.filter { it.awaitingReview }
        val roles = p.facts.filter { it.kind == FactKind.Role }.groupBy { it.id }.mapValues { it.value.first() }

        fun source(fact: ProfileFact): String {
            var source = when (fact.source) {
                FactSource.ResumeImport -> f("fact_source_resume", if (fact.sourceName.isNotEmpty()) fact.sourceName else docName(p, fact) ?: l("fact_source_document"))
                FactSource.Manual -> l("fact_source_manual")
                FactSource.Questionnaire -> l("fact_source_questionnaire")
                FactSource.Document -> if (fact.sourceName.isNotEmpty() || docName(p, fact) != null) {
                    f("fact_source_resume", if (fact.sourceName.isNotEmpty()) fact.sourceName else docName(p, fact))
                } else {
                    l("fact_source_document")
                }
                else -> if (fact.provenance != null) {
                    val n = docName(p, fact)
                    if (n != null) f("fact_source_resume", n) else l("fact_source_document")
                } else {
                    l("fact_source_unknown")
                }
            }
            val pr = fact.provenance
            if (pr != null && pr.lineStart > 0 && fact.source != FactSource.Manual) source += " · " + f("fact_line", pr.lineStart)
            val c = fact.confirmedUtc
            if (fact.status == FactStatus.UserConfirmed && c != null) source += " · " + f("exp_confirmed_on", ExportLabels.shortDate(lang, c, zone))
            return f("exp_source", source)
        }

        fun kind(fact: ProfileFact): String {
            val cat = if (isAnswer(fact)) PersonalCategories.find(fact.category) else null
            return if (cat != null) l("pc_${cat.key}_label") else l("fact_kind_" + fact.kind.name.lowercase())
        }

        fun item(sb: StringBuilder, fact: ProfileFact, showKind: Boolean, badge: Boolean) {
            sb.append("<li>")
            if (showKind) sb.append("<strong>${e(kind(fact))}:</strong> ")
            sb.append("<bdi dir=\"auto\">${e(factText(fact))}</bdi>")
            if (fact.metrics.isNotEmpty()) sb.append(" <span class=\"src\">(${e(fact.metrics.joinToString(", "))})</span>")
            if (badge) sb.append("<span class=\"badge\">${e(l("exp_badge_to_review"))}</span>")
            sb.append("<br><span class=\"src\">${e(source(fact))}</span></li>")
        }

        val name = if (p.name.trim().isNotEmpty()) p.name.trim() else p.preferredName.trim()
        val sb = StringBuilder()
        ExportLabels.head(sb, lang, "${l("exp_prof_title")} — $name")
        sb.append("<p class=\"meta\">${e(l("exp_prof_title"))}</p><h1><bdi dir=\"auto\">${e(name)}</bdi></h1>")
        if (p.headline.trim().isNotEmpty()) sb.append("<p><bdi dir=\"auto\">${e(p.headline.trim())}</bdi></p>")
        sb.append("<dl>")
        if (p.preferredName.trim().isNotEmpty() && p.preferredName.trim() != name) {
            sb.append("<dt>${e(l("exp_preferred_name"))}</dt><dd><bdi dir=\"auto\">${e(p.preferredName.trim())}</bdi></dd>")
        }
        if (p.location.trim().isNotEmpty()) {
            sb.append("<dt>${e(l("exp_location"))}</dt><dd><bdi dir=\"auto\">${e(p.location.trim())}</bdi></dd>")
        }
        sb.append("</dl>")
        val stats = p.stats
        sb.append("<p class=\"meta\">${e(f("exp_prof_intro", ExportLabels.dateAndTime(lang, now, zone)))} ${e(f("exp_prof_counts", stats.confirmedExperience, stats.confirmedProfileAnswers, stats.toReview))}</p>")

        // ---- confirmed experience, grouped by role (in résumé order)
        sb.append("<h2>${e(l("exp_section_experience"))}</h2>")
        if (confirmed.isEmpty()) sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        for (summary in confirmed.filter { it.kind == FactKind.Summary }) {
            sb.append("<p><bdi dir=\"auto\">${e(summary.text)}</bdi></p>")
        }
        val byRole = confirmed.filter { it.kind != FactKind.Summary && it.kind != FactKind.Role && it.parentId != null && roles.containsKey(it.parentId) }
            .groupBy { it.parentId!! }
        for (role in p.facts.filter { it.kind == FactKind.Role && (it.isVerifiedClaim || byRole.containsKey(it.id)) }) {
            sb.append("<h3><bdi dir=\"auto\">${e(factText(role))}</bdi>${if (role.awaitingReview) "<span class=\"badge\">${e(l("exp_badge_to_review"))}</span>" else ""}</h3>")
            if (role.kind == FactKind.Role && role.text.trim().isNotEmpty() && role.text.trim() != factText(role)) {
                sb.append("<p><bdi dir=\"auto\">${e(role.text)}</bdi></p>")
            }
            sb.append("<p class=\"src\">${e(source(role))}</p>")
            val items = byRole[role.id]
            if (items != null) {
                sb.append("<ul>")
                for (fact in items) item(sb, fact, showKind = fact.kind != FactKind.Achievement, badge = false)
                sb.append("</ul>")
            }
        }
        val other = confirmed.filter { it.kind != FactKind.Summary && it.kind != FactKind.Role && !(it.parentId != null && roles.containsKey(it.parentId)) }
        if (other.isNotEmpty()) {
            sb.append("<h3>${e(l("exp_prof_other_experience"))}</h3><ul>")
            for (fact in other.sortedBy { it.kind }) item(sb, fact, showKind = true, badge = false)
            sb.append("</ul>")
        }

        // ---- interview-profile answers (personal topics and boundaries), in questionnaire order
        sb.append("<h2>${e(l("exp_section_answers"))}</h2>")
        if (answers.isEmpty()) {
            sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        } else {
            sb.append("<ul>")
            for (fact in answers.sortedBy { order(it.category) }) item(sb, fact, showKind = true, badge = false)
            sb.append("</ul>")
        }

        // ---- still to review: listed, clearly marked, never presented as confirmed
        sb.append("<h2>${e(l("exp_section_review"))} (${toReview.size})</h2>")
        if (toReview.isEmpty()) {
            sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        } else {
            sb.append("<p class=\"note\">${e(l("exp_review_note"))}</p><ul>")
            for (fact in toReview) item(sb, fact, showKind = true, badge = true)
            sb.append("</ul>")
        }

        // ---- source documents by name only
        if (p.documents.isNotEmpty()) {
            sb.append("<h2>${e(l("exp_section_source_docs"))}</h2><ul>")
            for (doc in p.documents) {
                sb.append("<li><bdi dir=\"auto\">${e(doc.fileName)}</bdi> <span class=\"src\">· ${e(f("exp_doc_added", ExportLabels.date(lang, doc.addedUtc, zone)))}</span></li>")
            }
            sb.append("</ul>")
        }

        sb.append("<details><summary>${e(l("exp_technical"))}</summary><p>${e(l("exp_technical_note"))}</p><table><thead><tr><th>${e(l("exp_tech_item"))}</th><th>${e(l("exp_tech_id"))}</th></tr></thead><tbody>")
        sb.append("<tr><td>${e(l("exp_tech_profile"))}</td><td><code>${e(p.id)}</code></td></tr>")
        for (fact in facts) sb.append("<tr><td>${e(l("exp_tech_fact"))} · ${e(kind(fact))}</td><td><code>${e(fact.id)}</code></td></tr>")
        for (doc in p.documents) {
            sb.append("<tr><td>${e(l("exp_tech_document"))} · <bdi>${e(doc.fileName)}</bdi></td><td><code>${e(if (doc.sha256.isNotEmpty()) doc.sha256 else doc.id)}</code></td></tr>")
        }
        sb.append("</tbody></table></details>")
        sb.append("<p class=\"meta\">${e(f("exp_footer", productName, ExportLabels.dateAndTime(lang, now, zone)))}</p></body></html>")
        return sb.toString()
    }

    /** The fact as one line: a role reads "Title — Company (Period)". */
    fun factText(f: ProfileFact): String =
        if (f.kind == FactKind.Role) {
            "${f.title} — ${f.company} (${f.period})".replace(" ()", "").replace(" —  (", " (").trim(' ', '—')
        } else {
            f.text
        }

    private fun docName(p: CandidateProfileRecord, f: ProfileFact): String? {
        val pr = f.provenance ?: return null
        val n = p.documents.firstOrNull { it.id == pr.documentId }?.fileName
        return if (!n.isNullOrEmpty()) n else null
    }

    private fun order(category: String): Int {
        for (i in PersonalCategories.all.indices) if (PersonalCategories.all[i].key == category) return i
        return Int.MAX_VALUE
    }
}
