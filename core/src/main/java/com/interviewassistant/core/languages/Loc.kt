package com.interviewassistant.core.languages

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mirror of `InterviewAssistant.Core/Languages/Loc.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * The single localization system for every user-visible string: one JSON table per supported UI language
 * (`i18n/{code}.json`, byte-identical assets in `:core`). English is the reference: every key exists there.
 * A key missing in another language falls back to English and is reported by [missingKeys] (the coverage
 * tests fail on it). Product names, hotkeys and technical terms inside the strings are kept verbatim.
 *
 * Adaptation: the C# tables ship as embedded assembly resources; on Android they ship as `:core` assets, so
 * the platform layer installs [tableLoader] once at startup (see `:app` Platform). Until a loader is
 * installed, tables are empty and [t] returns the key — the same fallback the C# gives for a missing
 * resource. Formatting mirrors .NET `string.Format` ({0}, {1} placeholders, culture-aware numbers).
 */
object Loc {
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var currentLanguage: String = "en"

    /** Loads the string table for a language code (e.g. from `:core` assets); null = no table. */
    @Volatile
    var tableLoader: ((String) -> Map<String, String>?)? = null

    private val languageChangedListeners = mutableListOf<() -> Unit>()

    /** Current UI language (process-wide). Changing it notifies [languageChangedListeners]. */
    var language: String
        get() = currentLanguage
        set(value) {
            val code = LanguageRegistry.find(value)?.code ?: "en"
            if (code == currentLanguage) return
            currentLanguage = code
            synchronized(languageChangedListeners) { languageChangedListeners.toList() }.forEach { it() }
        }

    fun addLanguageChangedListener(listener: () -> Unit) {
        synchronized(languageChangedListeners) { languageChangedListeners.add(listener) }
    }

    fun removeLanguageChangedListener(listener: () -> Unit) {
        synchronized(languageChangedListeners) { languageChangedListeners.remove(listener) }
    }

    val isRtl: Boolean get() = LanguageRegistry.isRtl(currentLanguage)

    /** Numbers and dates locale of the UI language (Arabic on the Gregorian calendar, see [LanguageRegistry]). */
    val locale: java.util.Locale get() = LanguageRegistry.formattingLocale(currentLanguage)

    /** Translated text for the current UI language. */
    fun t(key: String): String = get(currentLanguage, key)

    /** Translated, formatted text ({0}, {1} placeholders) for the current UI language. */
    fun f(key: String, vararg args: Any?): String = format(currentLanguage, key, *args)

    fun get(language: String?, key: String): String {
        val code = LanguageRegistry.find(language)?.code ?: "en"
        val v = table(code)[key]
        if (v != null && v.isNotEmpty()) return v
        return table("en")[key] ?: key
    }

    fun format(language: String?, key: String, vararg args: Any?): String {
        val code = LanguageRegistry.find(language)?.code ?: "en"
        return try {
            dotNetFormat(get(code, key), LanguageRegistry.numberFormat(code), args)
        } catch (e: IllegalArgumentException) {
            get("en", key)
        }
    }

    fun has(key: String): Boolean = table("en").containsKey(key)
    val keys: Collection<String> get() = table("en").keys
    fun tableFor(language: String): Map<String, String> = table(LanguageRegistry.find(language)?.code ?: "en")

    /** Keys present in English but missing (or empty) in [language]. */
    fun missingKeys(language: String): List<String> {
        val t = tableFor(language)
        return table("en").keys.filter { k -> t[k].isNullOrBlank() }.sorted()
    }

    private var reverse: Map<String, String>? = null
    private val refSuffix = Regex("""\s(\(\S{1,24}\s[0-9a-fA-F]{8}\))$""")

    /**
     * Re-renders a message that was already localized (in any supported language) in the current UI language — for
     * status notes, banners and notifications that were on screen when the user switched languages. A trailing support
     * reference "(ref 1a2b3c4d)" is kept. Text that is not a complete table string (user data, formatted values) is
     * returned unchanged.
     */
    fun relocalize(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val reverseMap = reverse ?: buildReverse().also { reverse = it }
        var body = text
        var suffix = ""
        val m = refSuffix.find(text)
        if (m != null) {
            body = text.substring(0, m.range.first)
            suffix = " " + m.groupValues[1]
        }
        val key = reverseMap[body] ?: return text
        if (suffix.isNotEmpty()) {
            val hex = m!!.groupValues[1].let { it.substring(it.length - 9, it.length - 1) }
            suffix = " " + format(currentLanguage, "svc_ref", hex)
        }
        return t(key) + suffix
    }

    private fun buildReverse(): Map<String, String> {
        val d = LinkedHashMap<String, String>()
        val codes = (listOf("en") + LanguageRegistry.all.map { it.code }).distinct()
        for (code in codes) {
            for ((k, v) in table(code)) {
                if (v.isNotEmpty() && !v.contains('{')) d.putIfAbsent(v, k) // complete strings only (no placeholders)
            }
        }
        return d
    }

    private fun table(code: String): Map<String, String> = tables.getOrPut(code) { load(code) }

    private fun load(code: String): Map<String, String> = tableLoader?.invoke(code) ?: emptyMap()

    /** Parses one `i18n/{code}.json` document into a table (used by the platform loader and by tests). */
    fun parseTable(jsonText: String): Map<String, String> {
        val obj = Json.parseToJsonElement(jsonText).jsonObject
        val out = LinkedHashMap<String, String>(obj.size)
        for ((k, v) in obj) out[k] = v.jsonPrimitive.content
        return out
    }

    /**
     * .NET `string.Format` subset used by the string tables: `{n}` placeholders (with `{{`/`}}` escapes),
     * numbers rendered through the culture's number format, everything else via `toString`
     * (null → empty, as in .NET). A malformed template throws [IllegalArgumentException], mirroring
     * .NET's FormatException (which [format] catches exactly like the C#).
     */
    internal fun dotNetFormat(template: String, numberFormat: java.text.NumberFormat, args: Array<out Any?>): String {
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> {
                    sb.append('{'); i += 2
                }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> {
                    sb.append('}'); i += 2
                }
                c == '{' -> {
                    var j = i + 1
                    var index = 0
                    var digits = 0
                    while (j < template.length && template[j].isDigit()) {
                        index = index * 10 + (template[j] - '0'); j++; digits++
                    }
                    // Alignment/format components ({0,5}, {0:X}) do not occur in the tables; only a bare {n} is valid.
                    if (digits == 0 || j >= template.length || template[j] != '}') {
                        throw IllegalArgumentException("Input string was not in a correct format.")
                    }
                    if (index >= args.size) throw IllegalArgumentException("Index (zero based) must be greater than or equal to zero and less than the size of the argument list.")
                    val arg = args[index]
                    sb.append(if (arg is Number) numberFormat.format(arg) else arg?.toString() ?: "")
                    i = j + 1
                }
                c == '}' -> throw IllegalArgumentException("Input string was not in a correct format.")
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }
}
