package com.interviewassistant.core.reports

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.chrono.Chronology
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirror of `InterviewAssistant.Core/Reports/ExportLabels.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations (DC-41): `CultureInfo` becomes a JVM [Locale] plus an explicit chronology —
 * the pattern source is `DateTimeFormatterBuilder.getLocalizedDateTimePattern(LONG, …)` (the CLDR long
 * date pattern, the counterpart of `DateTimeFormatInfo.LongDatePattern`); the weekday field in a JVM
 * pattern is `E`, not `d`, so [stripWeekday] applies the C# algorithm to both run letters (the `d` form
 * is kept verbatim for the .NET-pattern cases). The JVM has no Persian chronology, so fa formats on the
 * ISO chronology where .NET uses the Solar Hijri calendar. `Local` takes an [OffsetDateTime] (UTC) and
 * a [ZoneId] where C# takes a UTC `DateTime` and a `TimeZoneInfo`.
 */
object ExportLabels {

    val keys: Array<String> = arrayOf(
        "exp_acct_title", "exp_acct_intro", "exp_no_secrets", "exp_section_account", "exp_email", "exp_account_status", "exp_member_since",
        "exp_status_active", "exp_status_disabled", "exp_status_deleted", "exp_status_unknown",
        "exp_section_plan", "exp_plan", "exp_billing_state", "exp_period_end", "exp_renews_on", "exp_ends_on",
        "exp_sub_none", "exp_sub_trialing", "exp_sub_active", "exp_sub_past_due", "exp_sub_paused", "exp_sub_canceled", "exp_sub_unknown",
        "exp_section_usage", "exp_usage_live", "exp_usage_answers", "exp_usage_prep",
        "exp_section_devices", "exp_device_name", "exp_device_platform", "exp_device_added", "exp_device_last_seen", "exp_device_state",
        "exp_device_active", "exp_device_removed",
        "exp_section_sessions", "exp_col_date", "exp_col_duration", "exp_col_metered", "exp_col_status",
        "exp_end_active", "exp_end_client_stop", "exp_end_heartbeat_timeout", "exp_end_allowance_exhausted", "exp_end_daily_cap",
        "exp_end_global_cap", "exp_end_max_duration", "exp_end_device_revoked", "exp_end_replaced_on_device", "exp_end_other",
        "exp_section_documents", "exp_docs_profile", "exp_docs_target", "exp_docs_report", "exp_docs_other", "exp_last_updated",
        "exp_none", "exp_technical", "exp_technical_note", "exp_tech_item", "exp_tech_id", "exp_tech_format", "exp_tech_account",
        "exp_tech_session", "exp_tech_document", "exp_footer",
        "exp_dur_hr", "exp_dur_min", "exp_dur_sec",
        "exp_prof_title", "exp_prof_intro", "exp_location", "exp_preferred_name", "exp_prof_counts",
        "exp_section_experience", "exp_prof_other_experience", "exp_section_answers", "exp_section_review", "exp_review_note",
        "exp_badge_to_review", "exp_source", "exp_confirmed_on", "exp_section_source_docs", "exp_doc_added",
        "exp_tech_profile", "exp_tech_fact",
    )

    fun get(lang: String?, key: String): String = Loc.get(lang, key)
    fun format(lang: String?, key: String, vararg args: Any?): String = Loc.format(lang, key, *args)

    /** True when every export label has a non-empty text in [lang] (not the English fallback). */
    fun isComplete(lang: String): Boolean {
        val t = Loc.tableFor(lang)
        return LanguageRegistry.find(lang)?.code == lang && keys.all { k -> !t[k].isNullOrBlank() }
    }

    fun code(lang: String?): String = LanguageRegistry.find(lang)?.code ?: "en"
    fun isRtl(lang: String?): Boolean = LanguageRegistry.isRtl(code(lang))

    /**
     * Culture for numbers and dates ([LanguageRegistry.formattingLocale]): Persian keeps its own (Solar
     * Hijri) calendar; Arabic uses the Gregorian calendar so dates match payment receipts.
     */
    fun culture(lang: String?): Locale = LanguageRegistry.formattingLocale(code(lang))

    private fun chronology(lang: String?, locale: Locale): Chronology =
        if (LanguageRegistry.usesGregorianCalendar(code(lang))) IsoChronology.INSTANCE else Chronology.ofLocale(locale)

    /** "0 sec", "59 sec", "1 hr 20 min 43 sec" (localized units; zero parts left out). */
    fun duration(lang: String?, secondsIn: Long): String {
        var seconds = secondsIn
        if (seconds < 0) seconds = 0
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add(format(lang, "exp_dur_hr", h))
        if (m > 0) parts.add(format(lang, "exp_dur_min", m))
        if (s > 0 || parts.isEmpty()) parts.add(format(lang, "exp_dur_sec", s))
        return parts.joinToString(" ")
    }

    /**
     * Calendar date in the user's culture and time zone (the stored value is UTC): the culture's long date without the
     * weekday ("18 ottobre 2026", not "domenica 18 ottobre 2026"), so it reads inside sentences like "Si rinnova il {0}".
     */
    fun date(lang: String?, utc: OffsetDateTime, zone: ZoneId? = null): String {
        val locale = culture(lang)
        val chronology = chronology(lang, locale)
        val pattern = longDateWithoutWeekday(locale, chronology)
        val formatter = DateTimeFormatter.ofPattern(pattern, locale).withChronology(chronology)
        return formatter.format(local(utc, zone))
    }

    private val longDatePatterns = ConcurrentHashMap<String, String>()

    /**
     * The locale's long date pattern with the weekday and the separator after it (or before it, at the end) removed:
     * "dddd, MMMM d, yyyy" → "MMMM d, yyyy", "dddd, d 'de' MMMM 'de' yyyy" → "d 'de' MMMM 'de' yyyy",
     * "yyyy年M月d日dddd" → "yyyy年M月d日". Quoted literals are kept as they are. Falls back to
     * "d MMMM yyyy" when what is left has no day, month and year.
     */
    fun longDateWithoutWeekday(culture: Locale): String = longDateWithoutWeekday(culture, chronology(null, culture))

    private fun longDateWithoutWeekday(locale: Locale, chronology: Chronology): String {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.LONG, null, chronology, locale)
        return longDatePatterns.getOrPut(pattern) { stripWeekday(pattern) }
    }

    internal fun stripWeekday(pattern: String): String {
        // Split into quoted literals (kept) and pattern runs; drop each run of 3+ 'd' with its adjacent separator.
        val parts = mutableListOf<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'' || c == '"') {
                if (sb.isNotEmpty()) {
                    parts.add(sb.toString() to false)
                    sb.setLength(0)
                }
                var end = pattern.indexOf(c, i + 1)
                if (end < 0) end = pattern.length - 1
                parts.add(pattern.substring(i, end + 1) to true)
                i = end + 1
                continue
            }
            if (c == '\\' && i + 1 < pattern.length) {
                sb.append(c).append(pattern[i + 1])
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) parts.add(sb.toString() to false)

        var removed = false
        for (idx in parts.indices) {
            val (text, literal) = parts[idx]
            if (literal) continue
            var replaced = weekdayRun.replace(text, "")
            replaced = weekdayRunJvm.replace(replaced, "")
            if (replaced != text) {
                parts[idx] = replaced to false
                removed = true
            }
        }
        if (!removed) return pattern
        // A weekday at the end leaves its separator before it ("d MMMM yyyy, dddd"): trim it.
        val result = parts.joinToString("") { it.first }.trim().trimEnd(',', '،', ' ', ' ').trim()
        val unquoted = parts.filter { !it.second }.joinToString("") { it.first }
        return if (unquoted.contains('d') && unquoted.contains('M') && unquoted.contains('y')) result else "d MMMM yyyy"
    }

    /** A weekday field ("ddd" or "dddd", not part of a longer run) and the separator that follows it. */
    private val weekdayRun: Regex = dotNetRegex("(?<!d)d{3,4}(?!d)[\\s\\u00A0,\\u060C.]*")

    /** The JVM weekday field ("EEE"/"EEEE") — the CLDR counterpart of the .NET "ddd"/"dddd" run above. */
    private val weekdayRunJvm: Regex = dotNetRegex("(?<!E)E{3,4}(?!E)[\\s\\u00A0,\\u060C.]*")

    /** Date and time in the user's culture and time zone. */
    fun dateAndTime(lang: String?, utc: OffsetDateTime, zone: ZoneId? = null): String {
        val locale = culture(lang)
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
            .withLocale(locale).withChronology(chronology(lang, locale))
        return formatter.format(local(utc, zone))
    }

    /** Short numeric date (tables). */
    fun shortDate(lang: String?, utc: OffsetDateTime, zone: ZoneId? = null): String {
        val locale = culture(lang)
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale).withChronology(chronology(lang, locale))
        return formatter.format(local(utc, zone))
    }

    fun number(lang: String?, n: Long): String {
        val format = NumberFormat.getNumberInstance(culture(lang))
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 0
        return format.format(n)
    }

    private fun local(utc: OffsetDateTime, zone: ZoneId?): java.time.ZonedDateTime =
        utc.atZoneSameInstant(zone ?: ZoneId.systemDefault())

    /** Shared page skeleton: self-contained, printable, no scripts or remote content, mirrored for RTL languages. */
    internal fun head(sb: StringBuilder, lang: String, title: String) {
        fun e(s: String): String = htmlEncode(s)
        val code = code(lang)
        sb.append("<!doctype html><html lang=\"${e(code)}\" dir=\"${if (isRtl(code)) "rtl" else "ltr"}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>${e(title)}</title><style>")
        sb.append("body{font-family:'Segoe UI',system-ui,sans-serif;max-width:860px;margin:32px auto;padding:0 20px;color:#1b1f24;line-height:1.5}")
        sb.append("h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 8px;border-bottom:1px solid #e3e6ea;padding-bottom:4px}h3{font-size:14px;margin:16px 0 4px}")
        sb.append(".meta{color:#5b6470;font-size:13px}.note{background:#f3f6fb;border:1px solid #d5deeb;border-radius:8px;padding:10px 14px;font-size:13px;margin:16px 0}")
        sb.append("table{border-collapse:collapse;width:100%;font-size:13px;margin:6px 0}th,td{text-align:start;padding:6px 8px;border-bottom:1px solid #e3e6ea;vertical-align:top}th{color:#5b6470;font-weight:600}")
        sb.append("dl{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:6px 0;font-size:14px}dt{color:#5b6470}dd{margin:0}")
        sb.append("ul{margin:6px 0;padding-inline-start:22px}li{margin:3px 0}.src{color:#5b6470;font-size:12px}.badge{display:inline-block;font-size:11px;font-weight:600;color:#8a5a00;background:#fff7e6;border:1px solid #f2d091;border-radius:6px;padding:0 6px;margin-inline-start:6px}")
        sb.append("details{margin:28px 0 8px;font-size:12px;color:#5b6470}summary{cursor:pointer;font-size:13px}code,.ltr{font-family:Consolas,monospace;direction:ltr;unicode-bidi:isolate}bdi{unicode-bidi:isolate}")
        sb.append("@media print{body{margin:0}}</style></head><body>")
    }

    /**
     * The shared HTML encoder of the report renderers: `System.Net.WebUtility.HtmlEncode` — encodes
     * `& < > " '` (`'` as `&#39;`) and control characters as numeric references; everything else
     * (including `·`, accented letters and all non-Latin scripts) stays literal, as the export tests pin.
     */
    internal fun htmlEncode(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> if (c.code < 0x20 || c.code in 0x7F..0x9F) {
                    sb.append("&#").append(c.code).append(';')
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
