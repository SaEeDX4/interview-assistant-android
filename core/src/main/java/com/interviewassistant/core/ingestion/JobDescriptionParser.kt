package com.interviewassistant.core.ingestion

import com.interviewassistant.core.languages.LanguageDetector

/**
 * Mirror of `InterviewAssistant.Core/Ingestion/JobDescriptionParser.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

class JobAnalysis {
    var title: String = ""
    var company: String = ""
    var language: String = "und"
    val responsibilities: MutableList<String> = mutableListOf()
    val requirements: MutableList<String> = mutableListOf()
    val niceToHave: MutableList<String> = mutableListOf()
    val about: MutableList<String> = mutableListOf()
    val terminology: MutableList<String> = mutableListOf()
    val securityFlags: MutableList<String> = mutableListOf()
}

/** Extracts a language-neutral structure from a job description (any supported language). Untrusted input. */
object JobDescriptionParser {
    private enum class S { Unknown, About, Responsibilities, Requirements, Nice, Benefits }

    private fun r(b: String): Regex = dotNetRegex("^\\s*(" + b + ")\\s*:?\\s*$", ignoreCase = true)

    private val headings: List<Pair<S, Regex>> = listOf(
        S.Responsibilities to r("(key )?responsibilities|what you('| wi)ll do|your (role|mission|tasks)|the role|duties|aufgaben|ihre aufgaben|deine aufgaben|missions?|vos missions|responsabilit[ée]s|responsabilidades|funciones|atribui[çc][õo]es|responsabilit[àa]|mansioni|مسئولیت(‌|\\s)?ها|شرح وظایف|المسؤوليات|المهام|岗位职责|工作职责|职责|जिम्मेदारियां|ज़िम्मेदारियाँ"),
        S.Requirements to r("requirements|qualifications|what you('| wi)ll bring|what we('| a)re looking for|who you are|must[- ]have|your profile|profile|anforderungen|ihr profil|dein profil|qualifikationen|profil recherch[ée]|pr[ée]requis|requisitos|perfil|requisiti|competenze richieste|الزامات|شرایط احراز|المتطلبات|المؤهلات|任职要求|岗位要求|要求|आवश्यकताएं|योग्यता"),
        S.Nice to r("nice[- ]to[- ]have|bonus( points)?|preferred( qualifications)?|plus|good to have|w[üu]nschenswert|von vorteil|atouts?|serait un plus|deseable|valorable|diferencial|desej[aá]vel|gradit[oa]|costituisce un plus|مزیت|امتیاز|ميزة إضافية|يفضل|加分项|优先|अच्छा होगा"),
        S.Benefits to r("benefits|what we offer|perks|wir bieten|avantages|beneficios|benef[ií]cios|benefici|مزایا|المزايا|福利|लाभ"),
        S.About to r("about (us|the company|the team)|who we are|[üu]ber uns|qui sommes-nous|[àa] propos|sobre n[óo]s(otros)?|chi siamo|درباره ما|من نحن|关于我们|हमारे बारे में"),
    )

    private val bullet: Regex = dotNetRegex("^\\s*([•·▪■◦●\\-\\*–]|\\d+[.)])\\s+")
    private val notTerms: Set<String> = setOf(
        "The", "We", "You", "Our", "Your", "This", "As", "In", "If", "For", "And", "With", "Join", "About", "What", "Who", "Ideally", "Strong", "Experience", "Proven", "Ability", "Excellent", "Bonus", "Nice",
    ).map { it.lowercase() }.toSet()

    private fun isNotTerm(t: String): Boolean = t.lowercase() in notTerms

    fun parse(rawText: String, titleHint: String? = null, companyHint: String? = null): JobAnalysis {
        val injection = PromptInjectionGuard.sanitize(rawText)
        val text = injection.text
        val a = JobAnalysis()
        a.language = LanguageDetector.detectOr(text, "und", 0.4)
        a.title = titleHint ?: ""
        a.company = companyHint ?: ""
        a.securityFlags.addAll(injection.flags)
        var section = S.Unknown
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            val h = headings.firstOrNull { it.second.containsMatchIn(line) }
            if (h != null && line.length < 60) {
                section = h.first
                continue
            }
            val kv = dotNetRegex("^(job title|title|position|role|company|employer)\\s*:\\s*(.+)$", ignoreCase = true).find(line)
            if (kv != null) {
                if (kv.groupValues[1].lowercase() == "company" || kv.groupValues[1].lowercase() == "employer") {
                    if (a.company.isEmpty()) a.company = kv.groupValues[2].trim()
                } else if (a.title.isEmpty()) a.title = kv.groupValues[2].trim()
                continue
            }
            if (i == 0 && a.title.isEmpty() && line.length < 100 && !bullet.containsMatchIn(line) && !line.endsWith('.')) {
                val at = dotNetRegex("^(?<t>.+?)\\s+(?:at|@|bei|chez|en|presso|na|\\|)\\s+(?<c>[^|]+)$", ignoreCase = true).find(line)
                if (at != null) {
                    a.title = at.groups["t"]!!.value.trim()
                    if (a.company.isEmpty()) a.company = at.groups["c"]!!.value.trim()
                } else a.title = line.trim()
                continue
            }
            val item = bullet.replace(line, "").trim()
            if (item.length < 3) continue
            when (section) {
                S.Responsibilities -> a.responsibilities.add(item)
                S.Requirements -> a.requirements.add(item)
                S.Nice -> a.niceToHave.add(item)
                S.About -> a.about.add(item)
                S.Benefits -> {}
                else ->
                    if (dotNetRegex("\\b(experience|knowledge|years|degree|proficien|familiar|understanding|skills?)\\b", ignoreCase = true).containsMatchIn(item)) a.requirements.add(item)
                    else if (bullet.containsMatchIn(line)) a.responsibilities.add(item)
                    else a.about.add(item)
            }
        }
        if (a.company.isEmpty()) {
            val m = dotNetRegex("\\b(?:at|join|about)\\s+([A-Z][\\w&.-]+(?:\\s[A-Z][\\w&.-]+)?)").find(text)
            if (m != null && !isNotTerm(m.groupValues[1])) a.company = m.groupValues[1]
        }
        a.terminology.addAll(extractTerminology(text, a.company))
        return a
    }

    /** Acronyms, CamelCase/brand tokens and repeated capitalised phrases — preserved verbatim across languages. */
    fun extractTerminology(text: String, company: String = ""): List<String> {
        val counts = LinkedHashMap<String, Int>()
        fun add(raw: String) {
            val t = raw.trim().trim('.', ',', ':', ';', '(', ')')
            if (t.length < 2 || isNotTerm(t)) return
            counts[t] = (counts[t] ?: 0) + 1
        }
        for (m in dotNetRegex("\\b[A-Z][A-Z0-9]{1,}(?:[-/][A-Z0-9]+)*s?\\b").findAll(text)) add(m.value) // KPI, MiCA-like acronyms, ERC-20
        for (m in dotNetRegex("\\b[A-Z][a-z]+[A-Z][A-Za-z]+\\b").findAll(text)) add(m.value) // CamelCase (FinTech, HubSpot)
        for (m in dotNetRegex("(?<=[a-z,;]\\s)[A-Z][a-z]{2,}(?:\\s[A-Z][a-z]{2,})?\\b").findAll(text)) add(m.value) // mid-sentence capitalised (Jira, Product Owner)
        for (m in dotNetRegex("\\b(MiCA|MiCAR|KYC|AML|API|SaaS|B2B|B2C|OKR|KPI|SQL|Jira|Scrum|Agile|Kanban|Figma)\\b", ignoreCase = true).findAll(text)) add(m.value)
        if (company.isNotEmpty()) counts.remove(company)
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(40)
    }
}
