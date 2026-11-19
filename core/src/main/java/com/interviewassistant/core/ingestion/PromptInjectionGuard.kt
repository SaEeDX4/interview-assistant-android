package com.interviewassistant.core.ingestion

import java.util.regex.Pattern

/**
 * Mirror of `InterviewAssistant.Core/Ingestion/PromptInjectionGuard.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Uploaded documents are untrusted content. This guard removes instruction-like lines that try to steer the
 * model ("ignore previous instructions", "you are now…", "system prompt") in the supported languages and
 * records a flag. Defence in depth: prompts also wrap documents as quoted data and state they cannot change rules.
 *
 * This file also hosts [dotNetRegex], the shared .NET-regex factory for `:core`: .NET patterns are
 * Unicode-aware by default (`\w`, `\b`, `\d` cover all scripts) and `IgnoreCase | CultureInvariant` folds
 * case invariantly; the JVM needs UNICODE_CHARACTER_CLASS / UNICODE_CASE to mean the same thing.
 */
internal fun dotNetRegex(pattern: String, ignoreCase: Boolean = false): Regex {
    var flags = Pattern.UNICODE_CHARACTER_CLASS
    if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    return Regex(Pattern.compile(pattern, flags))
}

object PromptInjectionGuard {
    private val pattern: Regex = dotNetRegex(
        "(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier|system)\\s+(instructions|prompts?|rules)" +
            "|\\byou are now\\b|\\bact as (an?|the)\\b.*\\b(ai|assistant|model)\\b|\\bsystem prompt\\b|\\bdeveloper mode\\b|\\bjailbreak\\b" +
            "|reveal (your|the) (instructions|prompt|api key|secret)|\\bnew instructions\\s*:" +
            "|ignora (las|todas las) instrucciones|ignorez (les|toutes les) instructions|ignoriere (alle|die) (vorherigen )?anweisungen" +
            "|ignore (as|todas as) instruções|ignora (le|tutte le) istruzioni|دستورالعمل‌های قبلی را نادیده|تجاهل (جميع )?التعليمات|忽略(之前|以上)的?指令|पिछले निर्देशों को अनदेखा",
        ignoreCase = true,
    )

    data class Result(val text: String, val flags: List<String>)

    fun sanitize(text: String): Result {
        val flags = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (pattern.containsMatchIn(line)) {
                flags.add("prompt-injection-removed: " + (if (line.length > 80) line.substring(0, 80) + "…" else line).trim())
                continue
            }
            kept.add(line)
        }
        return Result(kept.joinToString("\n"), flags)
    }

    fun looksMalicious(text: String): Boolean = pattern.containsMatchIn(text)
}
