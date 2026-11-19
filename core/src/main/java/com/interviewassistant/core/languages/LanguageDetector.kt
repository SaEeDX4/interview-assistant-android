package com.interviewassistant.core.languages

import kotlin.math.min

/**
 * Mirror of `InterviewAssistant.Core/Languages/LanguageDetector.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

data class DetectedLanguage(val code: String, val confidence: Double)

/**
 * Lightweight, dependency-free language identification for the 10 launch languages.
 * Non-Latin scripts are identified by Unicode block (Persian vs Arabic by Persian-only letters);
 * Latin-script languages by stop-word and diacritic profiles. Returns "und" when unsure — callers then
 * fall back to the user's locked/expected language. Good for paragraphs and full questions; short
 * fragments (< 3 words) are reported with low confidence.
 */
object LanguageDetector {
    private val stopWords: Map<String, List<String>> = linkedMapOf(
        "en" to "the and you your what how with for would about have this that are is of to in me tell why can did do does was were has been an a it on at by from which who".split(' '),
        "es" to "el la los las de del que y en por para con cómo qué usted tu su sus una un es son experiencia sobre nos puede ha han hay lo le se fue cuál cuénteme hábleme nunca también muy este esta alguna vez ya".split(' '),
        "fr" to "le la les des de du et vous votre vos que qui pour avec est une un dans comment pourquoi sur nous pouvez quelle quel avez êtes été ce cette au aux parlez jamais déjà aussi très".split(' '),
        "de" to "der die das und sie ihre ihr wie was mit für ist ein eine einen nicht auf warum würden über uns können haben hat von zu im den dem des bei erzählen nie schon auch sehr".split(' '),
        "pt" to "o a os as de do da que e em para com você sua seu uma um como por não sobre nos pode já foi são está na no dos das conte fale nunca também este esta alguma vez".split(' '),
        "it" to "il lo la gli le di del della che e per con lei sua suo una un come perché non sulla ci può ha ho sono è nel nella dei degli mi parli mai già anche questo questa cosa quale".split(' '),
    )

    // Morphology: frequent derivational suffixes, weighted lower than function words.
    private val suffixes: Map<String, List<String>> = mapOf(
        "en" to listOf("tion", "ing", "ness", "ship", "ly"),
        "es" to listOf("ción", "dad", "ado", "ido", "ías"),
        "fr" to listOf("tion", "ment", "eux", "aire", "ais", "ez"),
        "de" to listOf("ung", "keit", "heit", "lich", "isch", "chen"),
        "pt" to listOf("ção", "ções", "dade", "ões", "ado"),
        "it" to listOf("zione", "ario", "ito", "ato", "ale", "ità", "ebbe"),
    )

    private val diacritics: Map<String, String> = mapOf(
        "es" to "ñ¿¡áéíóú", "fr" to "çàâèéêëîïôœùûÿ", "de" to "äöüß", "pt" to "ãõçáâêéíóôú", "it" to "àèéìíòù",
    )

    private const val persianOnly = "پچژگکی" // letters absent from standard Arabic orthography
    private const val arabicOnly = "ةيكىأإؤئ" // typical Arabic-only forms (ي/ك differ from Persian ی/ک)

    fun detect(text: String?): DetectedLanguage {
        if (text.isNullOrBlank()) return DetectedLanguage("und", 0.0)
        var arabicScript = 0
        var han = 0
        var devanagari = 0
        var latin = 0
        var persianMarks = 0
        var arabicMarks = 0
        var letters = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            if (ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ﭐ'..'﻿') {
                arabicScript++
                if (persianOnly.contains(ch)) persianMarks++
                if (arabicOnly.contains(ch)) arabicMarks++
            } else if (ch in '一'..'鿿' || ch in '㐀'..'䶿') han++
            else if (ch in 'ऀ'..'ॿ') devanagari++
            else if (ch < 'ɐ') latin++
        }
        if (letters == 0) return DetectedLanguage("und", 0.0)
        fun share(n: Int): Double = n.toDouble() / letters
        if (share(han) > 0.3) return DetectedLanguage("zh", min(1.0, 0.6 + share(han)))
        if (share(devanagari) > 0.3) return DetectedLanguage("hi", min(1.0, 0.6 + share(devanagari)))
        if (share(arabicScript) > 0.3) {
            if (persianMarks > arabicMarks) return DetectedLanguage("fa", if (persianMarks >= 2) 0.9 else 0.6)
            if (arabicMarks > persianMarks) return DetectedLanguage("ar", if (arabicMarks >= 2) 0.9 else 0.6)
            return DetectedLanguage("ar", 0.4)
        }
        if (share(latin) < 0.5) return DetectedLanguage("und", 0.2)

        val lowered = text.lowercase()
        val words = lowered.split(' ', '\n', '\r', '\t', ',', '.', '?', '!', ';', ':', '(', ')', '\"', '\'', '’', '-', '¿', '¡').filter { it.isNotEmpty() }
        val scores = LinkedHashMap<String, Double>()
        for ((lang, list) in stopWords) {
            val set = list.toSet()
            var s = words.count { w -> set.contains(w) }.toDouble()
            s += lowered.count { c -> diacritics[lang]?.contains(c) == true } * 0.6
            s += words.count { w -> w.length > 4 && suffixes.getValue(lang).any { x -> w.endsWith(x) } } * 0.3
            scores[lang] = s
        }
        val ordered = scores.entries.sortedByDescending { it.value }
        val best = ordered[0]
        if (best.value < 1) return DetectedLanguage("und", 0.1)
        val margin = best.value - ordered[1].value
        val conf = minOf(0.95, 0.35 + margin / maxOf(1, words.size) * 2 + (if (words.size >= 5) 0.2 else 0.0))
        return DetectedLanguage(best.key, if (words.size < 3) minOf(conf, 0.4) else conf)
    }

    /** Detect, but only trust results above the threshold; otherwise return the fallback (e.g. locked language). */
    fun detectOr(text: String?, fallback: String, threshold: Double = 0.5): String {
        val d = detect(text)
        return if (d.confidence >= threshold && d.code != "und") d.code else fallback
    }
}
