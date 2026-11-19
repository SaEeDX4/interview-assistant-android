package com.interviewassistant.core.ingestion

import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.domain.Provenance
import com.interviewassistant.core.domain.SourceDocument
import com.interviewassistant.core.languages.LanguageDetector
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Mirror of `InterviewAssistant.Core/Ingestion/ResumeParser.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

class ParsedResume {
    var name: String = ""
    var headline: String = ""
    var location: String = ""
    var language: String = "und"
    val facts: MutableList<ProfileFact> = mutableListOf()
    val warnings: MutableList<String> = mutableListOf()
}

/**
 * Deterministic résumé structure extraction (no network, no AI). Every fact carries provenance (document id,
 * line numbers, snippet) and starts as [com.interviewassistant.core.domain.FactStatus.Source]: it is not a
 * verified claim until the user confirms it. Ambiguities are flagged rather than guessed.
 */
object ResumeParser {
    private enum class Section { Header, Summary, Experience, Education, Skills, Languages, Certifications, Projects, Other }

    private fun h(body: String): Regex = dotNetRegex("^\\s*(" + body + ")\\s*:?\\s*$", ignoreCase = true)

    private val headings: List<Pair<Section, Regex>> = listOf(
        Section.Experience to h("(professional |work |relevant )?experience|employment( history)?|career history|berufserfahrung|beruflicher werdegang|exp[ée]rience(s)? professionnelle(s)?|experiencia( laboral| profesional)?|experi[êe]ncia( profissional)?|esperienz[ae]( lavorativ[ae]| professional[ie])?|سوابق (کاری|شغلی)|تجربه کاری|الخبرات?( المهنية| العملية)?|工作经[历歷]|工作经验|कार्य अनुभव|अनुभव"),
        Section.Education to h("education|academic background|ausbildung|bildung|studium|formation|éducation|educaci[óo]n|formaci[óo]n|forma[çc][ãa]o|educa[çc][ãa]o|istruzione|formazione|تحصیلات|التعليم|المؤهلات العلمية|教育(背景|经历)?|शिक्षा"),
        Section.Skills to h("(core |key |technical )?(skills|competencies|competences)|kenntnisse|kompetenzen|f[äa]higkeiten|comp[ée]tences|habilidades|competencias|compet[êe]ncias|competenze|abilit[àa]|مهارت(‌|\\s)?ها|مهارت|المهارات|技能|专业技能|कौशल"),
        Section.Languages to h("languages?|sprachen|sprachkenntnisse|langues|idiomas|lingue|زبان(‌|\\s)?ها|زبان|اللغات|语言(能力)?|भाषाएँ|भाषा"),
        Section.Certifications to h("certifications?|certificates?|licen[cs]es|zertifikate|zertifizierungen|certificaciones|certifica[çc][õo]es|certificazioni|گواهینامه(‌|\\s)?ها|الشهادات|证书|प्रमाणपत्र"),
        Section.Summary to h("(professional )?summary|profile|about( me)?|objective|zusammenfassung|profil|kurzprofil|r[ée]sum[ée] professionnel|perfil( profesional| profissional)?|resumen|resumo|sommario|profilo|خلاصه|درباره من|الملخص|نبذة|个人简介|简介|सारांश|प्रोफ़ाइल"),
        Section.Projects to h("projects?|projekte|projets|proyectos|projetos|progetti|پروژه(‌|\\s)?ها|المشاريع|项目|परियोजनाएँ"),
    )

    private const val PresentWords = "present|current|now|today|heute|aktuell|actuel(lement)?|aujourd'hui|actualidad|presente|actual|atual|attuale|oggi|اکنون|تاکنون|حال|حتى الآن|الحالي|至今|现在|वर्तमान"
    private val periodRx: Regex = dotNetRegex("((?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+|\\d{1,2}[/.])?(?:19|20)\\d{2})\\s*(?:–|—|-|to|bis|à|a|al|até|fino a|تا|إلى|至|से)\\s*((?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+|\\d{1,2}[/.])?(?:19|20)\\d{2}|" + PresentWords + ")", ignoreCase = true)
    private val metricRx: Regex = dotNetRegex("[$€£]?\\d[\\d,.]*\\s?(%|percent|k|m|million|bn|billion|x)?\\+?(?=\\b|\\s|$|%)|(?:\\d[\\d,.]*)\\s?(?:to|→|->)\\s?\\d[\\d,.]*", ignoreCase = true)
    private val bulletRx: Regex = dotNetRegex("^\\s*([•·▪■◦●\\-\\*–]|\\d+[.)])\\s+")
    private val separators = charArrayOf('|', '·', '•', '—', '–', ',')

    fun parse(doc: SourceDocument): ParsedResume {
        val result = ParsedResume()
        result.language = LanguageDetector.detectOr(doc.text, "und", 0.4)
        val lines = doc.text.split('\n').map { it.trim() }
        var section = Section.Header
        var currentRole: ProfileFact? = null
        var pendingTitle: String? = null
        var pendingTitleLine = -1
        var headerLines = 0

        fun fact(kind: FactKind, text: String, start: Int, end: Int, conf: Double = 0.85): ProfileFact {
            val joined = lines.subList(start, end + 1).joinToString(" ").trim()
            val snip = if (joined.length > 240) joined.substring(0, 240) else joined
            return ProfileFact(
                kind = kind, text = text.trim(), confidence = conf, language = result.language,
                provenance = Provenance(doc.id, start + 1, end + 1, snip),
            )
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty()) {
                i++
                continue
            }
            val heading = headings.firstOrNull { it.second.containsMatchIn(line) }
            if (heading != null && line.length < 60) {
                section = heading.first
                pendingTitle = null
                i++
                continue
            }
            val isBullet = bulletRx.containsMatchIn(line)
            var content = bulletRx.replace(line, "").trim()

            when (section) {
                Section.Header -> {
                    headerLines++
                    when {
                        result.name.isEmpty() && looksLikeName(content) -> result.name = toTitle(content)
                        content.contains('@') || dotNetRegex("\\+?\\d[\\d\\s()-]{7,}").containsMatchIn(content) ||
                            content.contains("linkedin", ignoreCase = true) -> {}
                        result.headline.isEmpty() && result.name.isNotEmpty() -> result.headline = content
                        result.location.isEmpty() && content.contains(',') && content.length < 120 ->
                            result.location = content.split('|')[0].trim()
                        headerLines > 6 -> {
                            section = Section.Summary
                            result.facts.add(fact(FactKind.Summary, content, i, i, 0.9))
                        }
                    }
                }

                Section.Summary -> result.facts.add(fact(FactKind.Summary, content, i, i, 0.9))

                Section.Experience -> {
                    val period = periodRx.find(content)
                    if (!isBullet && period != null) {
                        // "Company | Location | 2019 – 2023"  or  "Title, Company, 2019-2023"  or  "Title at Company (2019-2023)"
                        val before = content.substring(0, period.range.first).trim().trimEnd('(', '|', ',', '·', '-', '–', '—').trim()
                        val parts = before.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }
                        var title = pendingTitle ?: ""
                        var company = ""
                        var location = ""
                        val startLine = if (pendingTitle != null) pendingTitleLine else i
                        val atMatch = dotNetRegex("^(?<t>.+?)\\s+(?:at|@|bei|chez|en|na|presso|در|في|在)\\s+(?<c>.+)$", ignoreCase = true).find(before)
                        if (pendingTitle == null && atMatch != null) {
                            title = atMatch.groups["t"]!!.value.trim()
                            company = atMatch.groups["c"]!!.value.trim()
                        } else if (pendingTitle != null) {
                            company = parts.getOrNull(0) ?: ""
                            location = parts.drop(1).joinToString(", ")
                        } else if (parts.size == 1) {
                            company = parts[0] // only one name: cannot know if it is title or company → company, flagged
                        } else {
                            title = parts.getOrNull(0) ?: ""
                            company = parts.getOrNull(1) ?: ""
                            location = parts.drop(2).joinToString(", ")
                        }
                        val role = fact(FactKind.Role, "${title} — ${company}".trim(' ', '—'), startLine, i, 0.9)
                        role.title = title
                        role.company = company
                        role.location = location
                        role.period = normalizePeriod(period.value)
                        if (title.isEmpty()) role.flags.add("missing-title")
                        if (company.isEmpty()) role.flags.add("missing-company")
                        if (role.flags.isNotEmpty()) role.confidence = 0.5
                        currentRole = role
                        result.facts.add(role)
                        pendingTitle = null
                    } else if (!isBullet && content.length < 90 && !content.endsWith('.')) {
                        pendingTitle = content
                        pendingTitleLine = i // title line preceding "Company | Location | Period"
                    } else {
                        // Achievement bullet (may wrap onto following non-bullet lines).
                        var end = i
                        while (end + 1 < lines.size && lines[end + 1].isNotEmpty() && !bulletRx.containsMatchIn(lines[end + 1]) &&
                            !periodRx.containsMatchIn(lines[end + 1]) && lines[end + 1][0].isLowerCase() &&
                            headings.all { !it.second.containsMatchIn(lines[end + 1]) }
                        ) {
                            end++
                            content += " " + lines[end]
                        }
                        val f = fact(FactKind.Achievement, content, i, end)
                        val role = currentRole
                        if (role != null) {
                            f.parentId = role.id
                            f.company = role.company
                            f.title = role.title
                            f.period = role.period
                        } else f.flags.add("unattached-to-role")
                        f.metrics = extractMetrics(content).toMutableList()
                        result.facts.add(f)
                        i = end
                    }
                }

                Section.Education -> {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.isNotEmpty() && headings.all { !it.second.containsMatchIn(next) } && !bulletRx.containsMatchIn(next) &&
                        dotNetRegex("universit|college|school|institut|hochschule|école|universidad|universidade|università|دانشگاه|جامعة|大学|विश्वविद्यालय", ignoreCase = true).containsMatchIn(next)
                    ) {
                        val ed = fact(FactKind.Education, "${content} — ${next}", i, i + 1, 0.9)
                        ed.title = content
                        ed.company = next
                        result.facts.add(ed)
                        i++
                    } else result.facts.add(fact(FactKind.Education, content, i, i, 0.8))
                }

                Section.Skills ->
                    for (skill in if (isBullet) listOf(content) else splitOutsideBrackets(content, ",;|•·"))
                        if (skill.length > 1) result.facts.add(fact(FactKind.Skill, skill, i, i, 0.9))

                Section.Languages ->
                    for (lang in content.split('|', ',', ';', '•', '·').map { it.trim() }.filter { it.isNotEmpty() })
                        if (lang.length > 1) result.facts.add(fact(FactKind.Language, lang, i, i, 0.9))

                Section.Certifications -> result.facts.add(fact(FactKind.Certification, content, i, i, 0.85))

                Section.Projects -> {
                    val pf = fact(FactKind.Project, content, i, i, 0.8)
                    pf.metrics = extractMetrics(content).toMutableList()
                    result.facts.add(pf)
                }

                else -> result.facts.add(fact(FactKind.Other, content, i, i, 0.5))
            }
            i++
        }

        // Consistency checks → flags (never silent fixes).
        val roles = result.facts.filter { it.kind == FactKind.Role }
        if (roles.isEmpty()) result.warnings.add("No employment history detected — please check the experience section or paste your roles.")
        for (r in roles) {
            val yrs = dotNetRegex("(19|20)\\d{2}").findAll(r.period).map { it.value.toInt() }.toList()
            if (yrs.size == 2 && yrs[1] < yrs[0]) {
                r.flags.add("end-before-start")
                r.confidence = 0.4
            }
            if (yrs.isNotEmpty() && yrs[0] > OffsetDateTime.now(ZoneOffset.UTC).year) {
                r.flags.add("future-date")
                r.confidence = 0.4
            }
        }
        if (result.name.isEmpty()) result.warnings.add("Name not detected.")
        return result
    }

    /** Splits on separators that are not inside (), [] — keeps "CRM (Salesforce, HubSpot)" intact. */
    fun splitOutsideBrackets(text: String, separators: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in text) {
            if (c == '(' || c == '[') depth++
            if (c == ')' || c == ']') depth = maxOf(0, depth - 1)
            if (depth == 0 && separators.contains(c)) {
                if (cur.toString().trim().isNotEmpty()) parts.add(cur.toString().trim())
                cur.clear()
            } else cur.append(c)
        }
        if (cur.toString().trim().isNotEmpty()) parts.add(cur.toString().trim())
        return parts
    }

    fun extractMetrics(text: String): List<String> {
        val list = mutableListOf<String>()
        for (m in metricRx.findAll(text)) {
            val v = m.value.trim().trimEnd('.', ',')
            if (v.isEmpty()) continue
            if (dotNetRegex("^(19|20)\\d{2}$").containsMatchIn(v)) continue // a year, not a metric
            if (!list.contains(v)) list.add(v)
        }
        return list
    }

    private fun normalizePeriod(p: String): String =
        dotNetRegex("\\s*(–|—|-|to|bis|à|a|al|até|fino a)\\s*", ignoreCase = true).replace(p, " – ").trim()

    private fun looksLikeName(s: String): Boolean {
        if (s.length < 3 || s.length > 60 || dotNetRegex("\\d|@|\\|").containsMatchIn(s)) return false
        val words = s.split(' ').filter { it.isNotEmpty() }
        return words.size in 1..5 && words.all { it[0].isLetter() }
    }

    private fun toTitle(s: String): String =
        if (s.any { it.isLowerCase() }) s
        else s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            if (w.length > 1 && w.all { c -> !c.isLetter() || c.isUpperCase() })
                w[0].uppercaseChar() + w.substring(1).lowercase()
            else w
        }
}
