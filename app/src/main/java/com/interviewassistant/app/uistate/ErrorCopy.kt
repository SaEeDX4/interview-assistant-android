package com.interviewassistant.app.uistate

import com.interviewassistant.client.AuthException
import com.interviewassistant.client.BackendException
import com.interviewassistant.core.languages.Loc
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/** Mirror of `InterviewAssistant.App/UiState/ErrorCopy.cs`. */

/**
 * One mapping from a failed service call to the words the user sees. Plain class (no WPF) so every case — offline,
 * timeout, one endpoint failing, 5xx, cancellation, plan and quota states — is unit-tested.
 */
object ErrorCopy {
    /**
     * User-facing text for any service error, mapped by error code and HTTP status (401/403/404/409/429/5xx, network,
     * timeout, device, plan, quota, maintenance, minimum version) — never a raw exception, never "Unknown". A short
     * reference id is appended for support (the full correlation id is in the log).
     * [includeReference] = false for inline section messages: the correlation id then goes to the log
     * only (the caller logs it), the user sees plain copy.
     *
     * Exception-type mapping (P4-PORT-NOTES DC-64): C# HttpRequestException → [IOException];
     * TaskCanceledException → [TimeoutCancellationException]; System.Text.Json.JsonException →
     * kotlinx.serialization.SerializationException (matched directly via [isSerializationFailure] —
     * :app declares the serialization dependency itself, P4-PORT-NOTES DC-64 as parent-resolved);
     * NotSupportedException → [UnsupportedOperationException].
     */
    fun describe(ex: Exception, includeReference: Boolean): String =
        if (includeReference) describeWithReference(ex) else stripReference(describeWithReference(ex), ex)

    private fun stripReference(text: String, ex: Exception): String =
        if (ex is BackendException) {
            val r = ref(ex)
            if (r.isNotEmpty() && text.endsWith(r)) text.substring(0, text.length - r.length) else text
        } else text

    private fun describeWithReference(ex: Exception): String = when {
        ex is BackendException && ex.status == 0 -> Loc.t("svc_unreachable")
        ex is BackendException && ex.isUpdateRequired -> Loc.t("err_update_required_long")
        ex is BackendException && ex.code == "maintenance" ->
            (if (!ex.message.isNullOrEmpty()) ex.message!! else Loc.t("svc_maintenance")) + ref(ex)
        ex is BackendException && (ex.code == "device_revoked" || ex.code == "device_unknown") -> Loc.t("svc_device_revoked") + ref(ex)
        ex is BackendException && ex.code == "device_limit" -> Loc.t("svc_device_limit") + ref(ex)
        ex is BackendException && (ex.code == "plan_required" || ex.code == "trial_exhausted" || ex.code == "subscription_expired" || ex.code == "subscription_paused") ->
            Loc.t("svc_plan_required") + ref(ex)
        ex is BackendException && ex.code == "allowance_exhausted" -> Loc.t("lease_allowance") + ref(ex)
        ex is BackendException && ex.code == "daily_live_cap" -> Loc.t("lease_daily_cap") + ref(ex)
        ex is BackendException && ex.code == "manual_daily_cap" -> Loc.t("svc_manual_cap") + ref(ex)
        ex is BackendException && ex.code == "reauth_required" -> Loc.t("err_reauth_unavailable") + ref(ex)
        ex is BackendException && ex.code == "timeout" -> Loc.t("svc_timeout") + ref(ex)
        ex is BackendException && ex.code == "session_start_rate" -> Loc.t("svc_session_start_rate") + ref(ex)
        ex is BackendException && ex.code == "subscription_active" -> Loc.t("svc_subscription_active") + ref(ex)
        ex is BackendException && ex.code == "prep_limit" -> Loc.t("warn_prep_limit") + ref(ex)
        ex is BackendException && ex.code == "document_limit" -> Loc.t("svc_document_limit") + ref(ex)
        ex is BackendException && ex.code == "account_disabled" -> Loc.t("acct_msg_disabled") + ref(ex)
        ex is BackendException && ex.status == 401 -> Loc.t("acct_msg_expired")
        ex is BackendException && ex.status == 403 -> Loc.t("svc_forbidden") + ref(ex)
        ex is BackendException && ex.status == 404 -> Loc.t("svc_not_found") + ref(ex)
        ex is BackendException && ex.status == 409 -> Loc.t("svc_conflict") + ref(ex)
        ex is BackendException && ex.status == 429 -> Loc.t("svc_rate_limited") + ref(ex)
        ex is BackendException && ex.status >= 500 -> Loc.t("svc_unavailable") + ref(ex)
        ex is BackendException -> (if (!ex.message.isNullOrEmpty()) ex.message!! else Loc.t("err_generic")) + ref(ex)
        ex is AuthException -> ex.message ?: ""
        ex is TimeoutException || ex is TimeoutCancellationException -> Loc.t("svc_timeout")
        // not the service's answer (a captive Wi-Fi login page, a proxy error page): the service is not reachable from here
        isSerializationFailure(ex) || ex is UnsupportedOperationException -> Loc.t("svc_unreachable")
        ex is IOException -> Loc.t("svc_unreachable")
        else -> Loc.t("err_generic")
    }

    /** The JsonException counterpart: kotlinx.serialization.SerializationException and its subclasses (see DC-64). */
    private fun isSerializationFailure(ex: Exception): Boolean = ex is SerializationException

    private fun ref(b: BackendException): String =
        b.correlationId?.takeIf { it.length >= 8 }?.let { c -> " " + Loc.f("svc_ref", c.substring(0, 8)) } ?: ""

    /** A call the user (or a page change) cancelled is not a failure: nothing is shown for it. (The CancellationToken counterpart is the call's [Job].) */
    fun isUserCancellation(ex: Throwable, token: Job?): Boolean =
        ex is kotlinx.coroutines.CancellationException && token?.isCancelled == true
}
