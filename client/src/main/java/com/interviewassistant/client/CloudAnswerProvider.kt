package com.interviewassistant.client

import com.interviewassistant.contracts.AnswerMessage
import com.interviewassistant.contracts.AnswerPurposes
import com.interviewassistant.contracts.AnswerStreamRequest
import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.providers.IAnswerProvider
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mirror of `InterviewAssistant.Client/CloudAnswerProvider.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port: the purpose resolution (auto/live/manual/prep), the `no_active_session` fallback to a
 * typed question, and the complete [map] error taxonomy.
 *
 * C#→Kotlin adaptations (P2-PORT-NOTES.md): the async enumerator the C# drives by hand is
 * [AnswerStreamSession]; caller cancellation surfaces as [CancellationException] and is converted to
 * `ProviderException(Cancelled)` exactly where the C#'s `MoveAsync` converts it.
 */

/**
 * IAnswerProvider backed by the server proxy: the model and the provider key live on the server.
 * Purpose "auto" (default for the live window) uses the live lease while one is active and the lease-free
 * "manual" purpose otherwise — typed questions work while paused without opening (or billing) a live session.
 */
class CloudAnswerProvider(
    private val api: BackendClient,
    private val sessionId: () -> UUID?,
    private val prepJobId: (() -> UUID?)? = null,
    private val deviceId: (() -> UUID?)? = null,
    /** "auto", "live", "manual" or "prep". */
    val purpose: String = "auto",
) : IAnswerProvider {
    override val name: String get() = "Cloud"

    /** Purpose actually used by the most recent request (diagnostics/tests). */
    var lastPurpose: String? = null
        private set

    override fun streamAsync(messages: List<ChatMessage>, maxTokens: Int): Flow<String> = flow {
        val session = if (purpose == "auto" || purpose == AnswerPurposes.Live) sessionId() else null
        var resolvedPurpose = when (purpose) {
            AnswerPurposes.Prep -> AnswerPurposes.Prep
            AnswerPurposes.Manual -> AnswerPurposes.Manual
            AnswerPurposes.Live -> AnswerPurposes.Live
            else -> if (session != null) AnswerPurposes.Live else AnswerPurposes.Manual
        }
        val body = messages.map { AnswerMessage(it.role, it.content) }
        var stream: AnswerStreamSession? = null
        var first: String?
        try {
            stream = open(resolvedPurpose, session, body, maxTokens)
            first = moveNext(stream)
        } catch (ex: BackendException) {
            if (purpose == "auto" && resolvedPurpose == AnswerPurposes.Live && ex.code == "no_active_session") {
                // The lease ended between our check and the request (pause, timeout): answer as a typed question instead.
                stream?.close()
                resolvedPurpose = AnswerPurposes.Manual
                stream = open(resolvedPurpose, null, body, maxTokens)
                try {
                    first = moveNext(stream)
                } catch (ex2: BackendException) {
                    stream.close()
                    throw map(ex2)
                }
            } else {
                stream?.close()
                throw map(ex)
            }
        }
        lastPurpose = resolvedPurpose
        val active = stream!!
        try {
            var current = first
            while (current != null) {
                emit(current)
                current = try {
                    moveNext(active)
                } catch (ex: BackendException) {
                    throw map(ex)
                }
            }
        } finally {
            active.close()
        }
    }

    private suspend fun open(purpose: String, session: UUID?, body: List<AnswerMessage>, maxTokens: Int): AnswerStreamSession =
        api.openAnswerStream(
            AnswerStreamRequest(
                if (purpose == AnswerPurposes.Live) session else null,
                if (purpose == AnswerPurposes.Prep) prepJobId?.invoke() else null,
                body, maxTokens, purpose, deviceId?.invoke(),
            ),
        )

    /** The C#'s `MoveAsync`: caller cancellation becomes `ProviderException(Cancelled)`. */
    private suspend fun moveNext(stream: AnswerStreamSession): String? {
        try {
            return stream.nextDelta()
        } catch (e: CancellationException) {
            throw ProviderException(ProviderErrorKind.Cancelled, "Cancelled")
        }
    }

    companion object {
        /** Server error → provider error kind. Known API codes keep their meaning; the code and correlation id travel along. */
        fun map(ex: BackendException): ProviderException {
            val kind = when (ex.code) {
                "no_active_session", "session_ended" -> ProviderErrorKind.SessionEnded
                "daily_cap", "manual_daily_cap", "prep_ai_limit", "prep_limit", "allowance_exhausted" -> ProviderErrorKind.QuotaExceeded
                "plan_required" -> ProviderErrorKind.PlanRequired
                "global_cap", "ai_unavailable", "ai_not_configured", "maintenance", "ai_interrupted", "stream_truncated" -> ProviderErrorKind.ServiceUnavailable
                "device_revoked", "device_unknown", "device_limit" -> ProviderErrorKind.DeviceRevoked
                "account_disabled", "account_deleted" -> ProviderErrorKind.AccountDisabled
                "client_update_required" -> ProviderErrorKind.UpdateRequired
                "signed_out", "invalid_token" -> ProviderErrorKind.NotSignedIn
                "ai_rate_limited", "rate_limited" -> ProviderErrorKind.RateLimited
                "invalid_messages", "invalid_role", "prompt_too_large", "invalid_purpose" -> ProviderErrorKind.BadRequest
                else -> when (ex.status) {
                    0 -> ProviderErrorKind.Network
                    401 -> ProviderErrorKind.NotSignedIn
                    402 -> ProviderErrorKind.PlanRequired
                    400, 413 -> ProviderErrorKind.BadRequest
                    408, 504 -> ProviderErrorKind.Timeout
                    426 -> ProviderErrorKind.UpdateRequired
                    429 -> ProviderErrorKind.RateLimited
                    else -> if (ex.status >= 500) ProviderErrorKind.ServiceUnavailable else ProviderErrorKind.Unknown
                }
            }
            return ProviderException(kind, ex.message ?: "", ex).apply {
                code = ex.code
                correlationId = ex.correlationId
            }
        }
    }
}
