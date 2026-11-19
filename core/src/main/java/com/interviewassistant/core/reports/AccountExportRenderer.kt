package com.interviewassistant.core.reports

import com.interviewassistant.core.persistence.SecretFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Plan and billing state the desktop already shows in words (from the signed-in account); optional. */
data class AccountExportContext(val planName: String? = null, val billingSummary: String? = null)

/**
 * Mirror of `InterviewAssistant.Core/Reports/AccountExportRenderer.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations: the service document is a kotlinx [JsonElement] (the tolerant accessors below
 * mirror the C# ones, including case-insensitive property names); `DateTime` values become
 * [OffsetDateTime] (UTC), a `TimeZoneInfo` becomes a [ZoneId]; the C# `SecretFilter.Clean` is
 * [SecretFilter.clean] here.
 */
object AccountExportRenderer {

    fun render(
        export: JsonElement,
        langIn: String,
        productName: String = "Interview Assistant",
        context: AccountExportContext? = null,
        zone: ZoneId? = null,
        nowUtc: OffsetDateTime? = null,
    ): String {
        val lang = ExportLabels.code(langIn)
        val root = SecretFilter.clean(export).node ?: JsonObject(emptyMap())
        fun l(k: String): String = ExportLabels.get(lang, k)
        fun f(k: String, vararg a: Any?): String = ExportLabels.format(lang, k, *a)
        fun e(s: String?): String = ExportLabels.htmlEncode(s ?: "")
        fun d(date: OffsetDateTime?): String = if (date != null) ExportLabels.date(lang, date, zone) else l("exp_none")
        fun dt(date: OffsetDateTime?): String = if (date != null) ExportLabels.dateAndTime(lang, date, zone) else l("exp_none")

        val exported = time(root, "exportedUtc") ?: nowUtc ?: OffsetDateTime.now(ZoneOffset.UTC)
        val sb = StringBuilder()
        ExportLabels.head(sb, lang, l("exp_acct_title"))
        sb.append("<h1>${e(l("exp_acct_title"))}</h1><p class=\"meta\">${e(f("exp_acct_intro", productName, dt(exported)))}</p>")
        sb.append("<p class=\"note\">${e(l("exp_no_secrets"))}</p>")

        // ---- account
        val account = obj(root, "account")
        sb.append("<h2>${e(l("exp_section_account"))}</h2><dl>")
        row(sb, l("exp_email"), "<span class=\"ltr\">${e(str(account, "email"))}</span>")
        row(sb, l("exp_account_status"), e(accountStatus(lang, str(account, "status"))))
        row(sb, l("exp_member_since"), e(d(time(account, "createdUtc"))))
        sb.append("</dl>")

        // ---- plan and billing
        val sub = obj(root, "subscription")
        val subStatus = str(sub, "status")
        sb.append("<h2>${e(l("exp_section_plan"))}</h2><dl>")
        val plan = if (!context?.planName.isNullOrBlank()) context!!.planName!! else str(sub, "planCode")
        row(sb, l("exp_plan"), if (plan.isNotEmpty()) "<bdi>${e(plan)}</bdi>" else e(l("exp_sub_none")))
        row(sb, l("exp_billing_state"), e(if (!context?.billingSummary.isNullOrBlank()) context!!.billingSummary!! else subscriptionState(lang, if (sub == null) null else subStatus)))
        val periodEnd = time(sub, "currentPeriodEndUtc")
        if (periodEnd != null) {
            row(sb, l("exp_period_end"), e(f(if (subStatus == "active" || subStatus == "trialing" || subStatus == "past_due") "exp_renews_on" else "exp_ends_on", d(periodEnd))))
        }
        sb.append("</dl>")

        // ---- usage
        val usage = LinkedHashMap<String, Pair<String, Long>>() // lowercase kind -> (first-seen kind, total)
        for (u in arr(root, "usage")) {
            val kind = str(u, "kind")
            val key = kind.lowercase()
            val prev = usage[key]
            usage[key] = (prev?.first ?: kind) to ((prev?.second ?: 0L) + (longOf(u, "total") ?: 0L))
        }
        fun usageTotal(kind: String): Long = usage[kind]?.second ?: 0L
        sb.append("<h2>${e(l("exp_section_usage"))}</h2><dl>")
        row(sb, l("exp_usage_live"), e(ExportLabels.duration(lang, usageTotal("live_seconds"))))
        row(sb, l("exp_usage_answers"), e(ExportLabels.number(lang, usageTotal("answer_request"))))
        row(sb, l("exp_usage_prep"), e(ExportLabels.number(lang, usageTotal("prep_job"))))
        sb.append("</dl>")

        // ---- devices
        val devices = arr(root, "devices")
        sb.append("<h2>${e(l("exp_section_devices"))} (${devices.size})</h2>")
        if (devices.isEmpty()) {
            sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        } else {
            sb.append("<table><thead><tr><th>${e(l("exp_device_name"))}</th><th>${e(l("exp_device_platform"))}</th><th>${e(l("exp_device_added"))}</th><th>${e(l("exp_device_last_seen"))}</th><th>${e(l("exp_device_state"))}</th></tr></thead><tbody>")
            for (dev in devices) {
                val revoked = time(dev, "revokedUtc")
                sb.append("<tr><td><bdi>${e(str(dev, "name"))}</bdi></td><td><bdi>${e(str(dev, "platform"))}</bdi></td><td>${e(d(time(dev, "createdUtc")))}</td><td>${e(dt(time(dev, "lastSeenUtc")))}</td>")
                sb.append("<td>${e(if (revoked != null) f("exp_device_removed", d(revoked)) else l("exp_device_active"))}</td></tr>")
            }
            sb.append("</tbody></table>")
        }

        // ---- live sessions (newest first)
        val sessions = arr(root, "sessions").sortedByDescending { time(it, "startedUtc") ?: OffsetDateTime.MIN }
        sb.append("<h2>${e(l("exp_section_sessions"))} (${sessions.size})</h2>")
        if (sessions.isEmpty()) {
            sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        } else {
            sb.append("<table><thead><tr><th>${e(l("exp_col_date"))}</th><th>${e(l("exp_col_duration"))}</th><th>${e(l("exp_col_metered"))}</th><th>${e(l("exp_col_status"))}</th></tr></thead><tbody>")
            for (s in sessions) {
                val started = time(s, "startedUtc")
                val ended = time(s, "endedUtc")
                val duration = if (started != null && ended != null) {
                    ExportLabels.duration(lang, maxOf(0L, java.time.Duration.between(started, ended).seconds))
                } else {
                    "—"
                }
                sb.append("<tr><td>${e(dt(started))}</td><td>${e(duration)}</td><td>${e(ExportLabels.duration(lang, longOf(s, "meteredSeconds") ?: 0))}</td><td>${e(endReason(lang, ended, str(s, "endReason")))}</td></tr>")
            }
            sb.append("</tbody></table>")
        }

        // ---- items saved in the account (counts; the content itself is in the raw data export)
        val documents = arr(root, "documents")
        sb.append("<h2>${e(l("exp_section_documents"))}</h2>")
        if (documents.isEmpty()) {
            sb.append("<p class=\"meta\">${e(l("exp_none"))}</p>")
        } else {
            sb.append("<dl>")
            val groups = documents.groupBy { doc ->
                when (str(doc, "kind")) {
                    "profile" -> "exp_docs_profile"
                    "target" -> "exp_docs_target"
                    "report" -> "exp_docs_report"
                    else -> "exp_docs_other"
                }
            }.entries.sortedBy { documentOrder.indexOf(it.key) }
            for ((key, g) in groups) {
                val last = g.mapNotNull { time(it, "updatedUtc") }.maxOrNull()
                row(sb, l(key), e(ExportLabels.number(lang, g.size.toLong())) + (if (last != null) " <span class=\"src\">· ${e(f("exp_last_updated", d(last)))}</span>" else ""))
            }
            sb.append("</dl>")
        }

        // ---- technical details (identifiers only; collapsed)
        sb.append("<details><summary>${e(l("exp_technical"))}</summary><p>${e(l("exp_technical_note"))}</p><table><thead><tr><th>${e(l("exp_tech_item"))}</th><th>${e(l("exp_tech_id"))}</th></tr></thead><tbody>")
        fun tech(label: String, value: String) {
            if (value.isNotEmpty()) sb.append("<tr><td>${e(label)}</td><td><code>${e(value)}</code></td></tr>")
        }
        tech(l("exp_tech_format"), str(root, "format"))
        tech(l("exp_tech_account"), str(account, "id"))
        for (s in sessions) tech(l("exp_tech_session") + " · " + dt(time(s, "startedUtc")), str(s, "id"))
        for (doc in documents) tech(l("exp_tech_document") + " · " + str(doc, "kind"), str(doc, "id"))
        for ((_, pair) in usage) {
            val (kind, total) = pair
            if (kind != "live_seconds" && kind != "answer_request" && kind != "prep_job") tech(kind, total.toString())
        }
        sb.append("</tbody></table></details>")
        sb.append("<p class=\"meta\">${e(f("exp_footer", productName, dt(nowUtc ?: exported)))}</p></body></html>")
        return sb.toString()
    }

    private val documentOrder: Array<String> = arrayOf("exp_docs_profile", "exp_docs_target", "exp_docs_report", "exp_docs_other")

    private fun row(sb: StringBuilder, label: String, htmlValue: String) {
        sb.append("<dt>${ExportLabels.htmlEncode(label)}</dt><dd>$htmlValue</dd>")
    }

    fun accountStatus(lang: String, status: String): String = ExportLabels.get(
        lang,
        when (status) {
            "active" -> "exp_status_active"
            "disabled" -> "exp_status_disabled"
            "deleted" -> "exp_status_deleted"
            else -> "exp_status_unknown"
        },
    )

    /** Subscription row status in words; null = no subscription row at all. */
    fun subscriptionState(lang: String, status: String?): String = ExportLabels.get(
        lang,
        when (status) {
            null, "", "none" -> "exp_sub_none"
            "trialing" -> "exp_sub_trialing"
            "active" -> "exp_sub_active"
            "past_due" -> "exp_sub_past_due"
            "paused" -> "exp_sub_paused"
            "canceled", "cancelled" -> "exp_sub_canceled"
            else -> "exp_sub_unknown"
        },
    )

    /** Why a live session ended, in words. */
    fun endReason(lang: String, endedUtc: OffsetDateTime?, reason: String): String = ExportLabels.get(
        lang,
        if (endedUtc == null && reason.isEmpty()) {
            "exp_end_active"
        } else {
            when (reason) {
                "client_stop" -> "exp_end_client_stop"
                "heartbeat_timeout" -> "exp_end_heartbeat_timeout"
                "allowance_exhausted" -> "exp_end_allowance_exhausted"
                "daily_cap" -> "exp_end_daily_cap"
                "global_cap" -> "exp_end_global_cap"
                "max_duration" -> "exp_end_max_duration"
                "device_revoked" -> "exp_end_device_revoked"
                "replaced_on_device" -> "exp_end_replaced_on_device"
                else -> "exp_end_other"
            }
        },
    )

    // ---- tolerant JSON access (camelCase from the service; any casing accepted)
    private fun prop(e: JsonElement?, name: String): JsonElement? {
        if (e !is JsonObject) return null
        for ((key, value) in e) {
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    private fun obj(e: JsonElement?, name: String): JsonElement? = prop(e, name) as? JsonObject

    private fun arr(e: JsonElement?, name: String): List<JsonElement> =
        (prop(e, name) as? JsonArray)?.filter { it is JsonObject } ?: emptyList()

    private fun str(e: JsonElement?, name: String): String = when (val v = prop(e, name)) {
        is JsonPrimitive -> when {
            v is JsonNull -> "" // C# Str: a JSON null reads as "" (DC-59); kotlinx JsonNull is a JsonPrimitive whose toString() is "null"
            v.isString -> v.content
            else -> v.toString() // numbers keep their raw text; booleans render "true"/"false"
        }
        else -> ""
    }

    private fun longOf(e: JsonElement?, name: String): Long? {
        val v = prop(e, name) as? JsonPrimitive ?: return null
        if (v.isString) return null
        return try {
            v.content.toLong()
        } catch (ex: NumberFormatException) {
            try {
                v.content.toDouble().toLong()
            } catch (ex2: NumberFormatException) {
                null
            }
        }
    }

    private fun time(e: JsonElement?, name: String): OffsetDateTime? {
        val v = prop(e, name) as? JsonPrimitive ?: return null
        if (!v.isString) return null
        val s = v.content
        return try {
            OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC)
        } catch (ex: Exception) {
            try {
                LocalDateTime.parse(s).atOffset(ZoneOffset.UTC) // AssumeUniversal
            } catch (ex2: Exception) {
                try {
                    LocalDate.parse(s).atStartOfDay().atOffset(ZoneOffset.UTC)
                } catch (ex3: Exception) {
                    null
                }
            }
        }
    }
}
