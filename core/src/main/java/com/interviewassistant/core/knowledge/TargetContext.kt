package com.interviewassistant.core.knowledge

import com.interviewassistant.core.ingestion.dotNetRegex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Mirror of `InterviewAssistant.Core/Knowledge/TargetContext.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Also hosts [regexEscape], the module-internal mirror of .NET `Regex.Escape` used across `:core`
 * (the JVM has no built-in equivalent; the escape set and whitespace handling follow the .NET
 * implementation, with `\v` written as `\x0B` because the JVM pattern grammar has no `\v` escape —
 * both denote the same character).
 */
internal fun regexEscape(value: String): String {
    val sb = StringBuilder(value.length)
    for (c in value) {
        when (c) {
            '\\', '*', '+', '?', '|', '{', '[', '(', ')', '^', '$', '.', '#' -> sb.append('\\').append(c)
            ' ' -> sb.append("\\ ")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\u000C' -> sb.append("\\f")
            '\u000B' -> sb.append("\\x0B")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** Spoken-form variants (ASR misrecognitions) mapped to the canonical term, e.g. ["terox","ter ox"] → "Teroxx". */
@Serializable
class TermAlias(
    @SerialName("canonical") val canonical: String = "",
    @SerialName("variants") val variants: List<String> = listOf(),
) {
    @Transient
    private var regexCache: Regex? = null

    val regex: Regex
        get() = regexCache ?: dotNetRegex(
            "\\b(" + variants.filter { it.isNotEmpty() }.joinToString("|") { regexEscape(it) } + ")\\b",
            ignoreCase = true,
        ).also { regexCache = it }
}

/**
 * Everything the live engine needs to know about WHO is interviewing for WHAT. Produced per interview target by the
 * preparation pipeline (or loaded from target_context.json for fixtures). The engine contains no hard-coded
 * candidate, company or product names.
 */
@Serializable
class TargetContext(
    @SerialName("candidate_name") var candidateName: String = "the candidate",
    @SerialName("role_title") var roleTitle: String = "the role",
    @SerialName("company_name") var companyName: String = "",
    @SerialName("products") var products: List<String> = listOf(),
    /** Domain terms that bias transcription and are preserved verbatim across languages. */
    @SerialName("vocabulary") var vocabulary: List<String> = listOf(),
    @SerialName("aliases") var aliases: List<TermAlias> = listOf(),
    /** Numbers that are domain/company knowledge rather than candidate claims (e.g. token supply). */
    @SerialName("domain_numbers") var domainNumbers: List<String> = listOf(),
    @SerialName("extra_entities") var extraEntities: List<String> = listOf(),
    /** Truthful one-paragraph positioning of the candidate for this role (from confirmed facts). */
    @SerialName("positioning") var positioning: String = "",
    /** Role/domain-specific answering guidance (from JD analysis and playbooks). */
    @SerialName("domain_guidance") var domainGuidance: String = "",
    /** BCP-47-ish code or "auto". */
    @SerialName("interview_language") var interviewLanguage: String = "auto",
    /** "same" = answer in the interviewer's language, otherwise a language code. */
    @SerialName("answer_language") var answerLanguage: String = "same",
    @SerialName("profile_id") var profileId: String = "",
    @SerialName("target_id") var targetId: String = "",
) {
    @Transient
    var employers: List<String> = listOf()

    @Transient
    private var termRules: List<Pair<Regex, String>>? = null

    /**
     * Conservative, target-scoped normalization of recognised terms: an acronym-style vocabulary term (e.g. "MiCAR")
     * also replaces its one-letter-shorter recognition ("MiCA"/"mica") — only when the shorter form is not itself in
     * this target's vocabulary. Never global, never for short terms (so "XAB" can never replace "app"), and only for
     * mixed-case terms: all-capitals acronyms (USDT, GDPR, AMLD, EMIR) never rewrite their shorter forms (USD, GDP,
     * AML, EMI are words in their own right).
     */
    fun normalizeTerms(text: String): String {
        var result = text
        val rules = termRules ?: buildTermRules().also { termRules = it }
        for ((rx, canonical) in rules) result = rx.replace(result, canonical)
        return result
    }

    private fun buildTermRules(): List<Pair<Regex, String>> {
        val vocab = (listOf(companyName) + products + vocabulary + aliases.map { it.canonical })
            .filter { it.isNotBlank() }.map { it.trim() }.distinct()
        val lower = HashSet(vocab.map { it.lowercase() })
        val rules = mutableListOf<Pair<Regex, String>>()
        for (v in vocab) {
            val acronymLike = v.length >= 4 && v.length <= 12 && !v.contains(' ') &&
                v.count { it.isUpperCase() } >= 2 && v.any { it.isLowerCase() } && v.all { it.isLetterOrDigit() }
            if (!acronymLike) continue
            val shorter = v.dropLast(1)
            if (shorter.length < 3 || lower.contains(shorter.lowercase()) || !v.last().isUpperCase()) continue
            rules.add(dotNetRegex("(?<![\\p{L}\\p{N}])" + regexEscape(shorter) + "(?![\\p{L}\\p{N}])", ignoreCase = true) to v)
        }
        return rules
    }

    /** Transcription bias prompt built from the target (no hard-coded names). */
    fun buildTranscriptionPrompt(): String {
        val who = if (companyName.isBlank()) "Job interview for a $roleTitle role." else "Job interview for a $roleTitle role at $companyName."
        return who + " Terms: " + transcriptionTerms().joinToString(", ") + "."
    }

    /**
     * Names and terms the interviewer is likely to say for THIS interview: company, products, domain vocabulary,
     * canonical forms of misrecognised terms, and the candidate's confirmed employers. No sentences, no résumé text.
     */
    fun transcriptionTerms(): List<String> =
        (listOf(companyName) + products + vocabulary + aliases.map { it.canonical } + employers)
            .map { it.trim() }
            .filter { it.length > 1 && it.length <= 48 && it != "the candidate" }
            .distinctBy { it.lowercase() }
            .take(80)
}
