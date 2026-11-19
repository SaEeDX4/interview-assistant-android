package com.interviewassistant.core.reports

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Mirror of `InterviewAssistant.Core/Reports/HtmlReportRenderer.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations: `WebUtility.HtmlEncode` is the shared [ExportLabels.htmlEncode];
 * `Math.Round` (banker's rounding) is mirrored with [BigDecimal] HALF_EVEN; `StringBuilder.AppendLine`
 * appends '\n' (the .NET newline on every non-Windows runtime, and Android).
 */
object HtmlReportRenderer {

    /** "Shervin" + "Shervin · Product Owner · Teroxx" → "Shervin · Product Owner · Teroxx" (no duplicated name). */
    fun header(profileLabel: String, targetLabel: String): String {
        val parts = mutableListOf<String>()
        for (p in listOf(profileLabel, targetLabel).flatMap { x -> x.split('·') }.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (parts.none { e ->
                    e.equals(p, ignoreCase = true) || e.startsWith(p + " ", ignoreCase = true) ||
                        (p.startsWith(e + " ", ignoreCase = true) && firstWord(e) == firstWord(p))
                }
            ) {
                parts.add(p)
            }
        }
        return parts.joinToString(" · ")
    }

    private fun firstWord(s: String): String = s.split(' ')[0].lowercase()

    private val headerTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** System.Math.Round (round-half-even) for the interruption line's pre-rounded values. */
    private fun round(x: Double, digits: Int): Double =
        BigDecimal.valueOf(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    fun render(r: InterviewReport, productName: String = "Interview Assistant"): String {
        fun l(k: String): String = ReportLabels.get(r.reportLanguage, k)
        fun e(s: String): String = ExportLabels.htmlEncode(s)
        val rtl = LanguageRegistry.isRtl(r.reportLanguage)
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"${e(r.reportLanguage)}\" dir=\"${if (rtl) "rtl" else "ltr"}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>${e(l("title"))} — ${e(r.targetLabel)}</title><style>")
        sb.append("body{font-family:'Segoe UI',system-ui,sans-serif;max-width:860px;margin:32px auto;padding:0 20px;color:#1b1f24;line-height:1.5}")
        sb.append("h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 8px;border-bottom:1px solid #e3e6ea;padding-bottom:4px}")
        sb.append(".meta{color:#5b6470;font-size:13px}.note{background:#fff7e6;border:1px solid #f2d091;border-radius:8px;padding:10px 14px;font-size:13px;margin:16px 0}")
        sb.append(".q{border:1px solid #e3e6ea;border-radius:8px;padding:10px 14px;margin:8px 0;break-inside:avoid}.q h3{font-size:15px;margin:0 0 2px}.q.unc{border-style:dashed;background:#fafbfc}.q .labels,.q .ctx{font-size:12px;color:#5b6470;margin:2px 0 6px}")
        sb.append(".inf{font-size:11px;font-weight:400;color:#8a5a00}ul{margin:6px 0;padding-inline-start:22px}bdi{unicode-bidi:isolate}</style></head><body>")
        sb.append("<h1>${e(l("title"))}</h1><p class=\"meta\"><bdi>${e(header(r.profileLabel, r.targetLabel))}</bdi> · ${headerTime.format(r.startedUtc)} UTC · ${e(l("duration"))}: ${Duration.between(r.startedUtc, r.endedUtc).toMinutes()} min · ${e(l("language"))}: ${e(r.languagesUsed.joinToString(", ") { LanguageRegistry.englishName(it) })}</p>")
        if (!r.candidateMicrophoneEnabled) sb.append("<p class=\"note\">${e(l("honesty"))}</p>")

        sb.append("<h2>${e(l("questions"))} (${r.questions.size})</h2>")
        for (q in r.questions) {
            sb.append("<section class=\"q\">")
            sb.append("<h3>Q${q.index}. <bdi dir=\"auto\">${e(q.originalWording)}</bdi></h3>")
            sb.append("<p class=\"labels\">${e(l("category"))}: ${e(q.category.lowercase().replace('_', ' '))} · ${e(l("mode"))}: ${e(q.mode.lowercase())} · ${e(l("language"))}: ${e(q.detectedLanguage)}${if (q.typed) " · " + e(l("typed")) else ""}</p>")
            if (q.context.isNotEmpty()) sb.append("<p class=\"ctx\">${e(l("context"))}: <bdi dir=\"auto\">${e(q.context)}</bdi></p>")
            if (q.suggestionsShown.isNotEmpty()) {
                sb.append("<ul>")
                for (s in q.suggestionsShown) sb.append("<li><bdi dir=\"auto\">${e(s)}</bdi></li>")
                sb.append("</ul>")
            }
            sb.append("</section>")
        }
        if (r.uncertainQuestions.isNotEmpty()) {
            sb.append("<h2>${e(l("uncertain"))} (${r.uncertainQuestions.size})</h2><p class=\"note\">${e(l("uncertain_note"))}</p>")
            for (q in r.uncertainQuestions) {
                sb.append("<section class=\"q unc\"><h3>Q${q.index}. <bdi dir=\"auto\">${e(q.originalWording)}</bdi></h3><p class=\"labels\">${e(q.uncertainReason)}</p></section>")
            }
        }
        if (r.excludedTurns > 0) sb.append("<p class=\"meta\">${e(l("excluded"))}: ${r.excludedTurns}</p>")

        fun list(key: String, items: Iterable<String>, inference: Boolean = false) {
            val itemsList = items.toList()
            sb.append("<h2>${e(l(key))}${if (inference) " <span class=\"inf\">(${e(l("inference"))})</span>" else ""}</h2>")
            if (itemsList.isEmpty()) {
                sb.append("<p class=\"meta\">${e(l("none"))}</p>")
                return
            }
            sb.append("<ul>")
            for (i in itemsList) sb.append("<li><bdi dir=\"auto\">${e(i)}</bdi></li>")
            sb.append("</ul>")
        }
        list("difficulty", r.difficultyAreas, true)
        list("stories", r.storiesRecommended.map { s -> "${s.item1} ×${s.item2}" })
        list("metrics", r.metricsRecommended.map { m -> "${m.item1} ×${m.item2}" })
        list("repeated", r.repeatedTopics)
        list("notcovered", r.topicsNotCovered, true)
        list("gaps", r.gapQuestions)
        list(
            "interruptions",
            r.interruptions.map { i -> Loc.format(r.reportLanguage, "rpt_interruption", i.afterQuestionIndex, round(i.confidence, 2), round(i.elapsedMs / 1000.0, 1), round(i.expectedMs / 1000.0, 1)) },
            true,
        )
        list("latency", if (r.latencyP50Ms >= 0) listOf(Loc.format(r.reportLanguage, "rpt_latency", r.latencyP50Ms, r.latencyP95Ms)) else listOf())
        list("transcription", r.transcriptionIssues)
        list("strengths", r.strengthsOfPreparation, true)
        list("weaknesses", r.preparationWeaknesses, true)
        list("followup", r.followUpPreparation, true)
        list("thankyou", r.thankYouThemes, true)
        list("unused", r.unusedRelevantStories)
        sb.append("<p class=\"meta\">${e(productName)} · ${e(r.sessionId)}</p></body></html>")
        return sb.toString()
    }

    /** Plain-text version (copy to e-mail, notes, a chat assistant). Same content and honesty note as the HTML. */
    fun renderText(r: InterviewReport, productName: String = "Interview Assistant"): String {
        fun l(k: String): String = ReportLabels.get(r.reportLanguage, k)
        val sb = StringBuilder()
        sb.appendLine(l("title"))
        sb.appendLine("${header(r.profileLabel, r.targetLabel)} · ${headerTime.format(r.startedUtc)} UTC · ${l("duration")}: ${Duration.between(r.startedUtc, r.endedUtc).toMinutes()} min")
        if (!r.candidateMicrophoneEnabled) {
            sb.appendLine()
            sb.appendLine(l("honesty"))
        }
        sb.appendLine()
        sb.appendLine("${l("questions")} (${r.questions.size})")
        for (q in r.questions) {
            sb.appendLine()
            sb.appendLine("Q${q.index}. ${q.originalWording}")
            sb.appendLine("   ${l("category")}: ${q.category.lowercase().replace('_', ' ')} · ${l("mode")}: ${q.mode.lowercase()}${if (q.typed) " · " + l("typed") else ""}")
            if (q.context.isNotEmpty()) sb.appendLine("   ${l("context")}: ${q.context}")
            for (s in q.suggestionsShown) sb.appendLine("   - " + s)
        }
        if (r.uncertainQuestions.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("${l("uncertain")} (${r.uncertainQuestions.size})")
            sb.appendLine(l("uncertain_note"))
            for (q in r.uncertainQuestions) {
                sb.appendLine()
                sb.appendLine("Q${q.index}. ${q.originalWording}")
                sb.appendLine("   ${q.uncertainReason}")
            }
        }
        fun list(key: String, items: Iterable<String>, inference: Boolean = false) {
            val itemsList = items.toList()
            sb.appendLine()
            sb.appendLine(l(key) + if (inference) " (${l("inference")})" else "")
            if (itemsList.isEmpty()) sb.appendLine("   " + l("none")) else for (i in itemsList) sb.appendLine("   - " + i)
        }
        list("difficulty", r.difficultyAreas, true)
        list("gaps", r.gapQuestions)
        list("followup", r.followUpPreparation, true)
        list("thankyou", r.thankYouThemes, true)
        sb.appendLine()
        sb.appendLine("$productName · ${r.sessionId}")
        return sb.toString()
    }
}
