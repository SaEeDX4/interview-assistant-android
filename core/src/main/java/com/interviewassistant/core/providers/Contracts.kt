package com.interviewassistant.core.providers

import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.flow.Flow

/**
 * Mirror of `InterviewAssistant.Core/Providers/Contracts.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port: the provider contracts the `:client` mirror consumes ([IAnswerProvider], [ProviderException],
 * [ProviderErrorKind]) plus the transcriber contract and [ConnectionStatus], so this file is complete —
 * nothing in it is deferred. P3 adds the direct-provider implementations behind these contracts.
 *
 * C#→Kotlin adaptations (P2-PORT-NOTES.md): C# `event`s on [ITranscriber] become listener lists (Kotlin has
 * no events); `IAsyncDisposable` becomes [ITranscriber.disposeAsync]; `IAsyncEnumerable<string>` becomes
 * [Flow]; cancellation tokens are the caller's coroutine scope, so async members take no token parameter.
 */

enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Failed }

/**
 * Why an answer could not be generated. Service-specific kinds keep the server's meaning so the user sees an
 * actionable message (never "Unknown" for a known condition).
 */
enum class ProviderErrorKind {
    InvalidApiKey, RateLimited, Network, Timeout, ServerError, BadRequest, Cancelled, Unknown,

    /** Sign-in expired or missing (service). */
    NotSignedIn,

    /** The live session ended (lease gone) — press Resume. */
    SessionEnded,

    /** Daily answer limit / plan allowance reached. */
    QuotaExceeded,

    /** Plan does not include this (trial used up, subscription ended or paused). */
    PlanRequired,

    /** This PC was removed from the account. */
    DeviceRevoked,
    AccountDisabled,

    /** The AI service is temporarily unavailable or at capacity. */
    ServiceUnavailable,

    /** The app version is too old for the service. */
    UpdateRequired,
}

class ProviderException(
    val kind: ProviderErrorKind,
    message: String,
    cause: Throwable? = null,
    /** Server error code (e.g. "no_active_session"), when the failure came from the service. */
    var code: String? = null,
    /** Server correlation id for support/diagnostics (never shown in the answer area). */
    var correlationId: String? = null,
) : Exception(message, cause) {

    companion object {
        /** Localized, actionable message for the current UI language (never "Unknown"). */
        fun userMessage(kind: ProviderErrorKind, developerKeyMode: Boolean = false): String = Loc.t(
            when (kind) {
                ProviderErrorKind.InvalidApiKey -> if (developerKeyMode) "err_auth_dev" else "err_auth"
                ProviderErrorKind.NotSignedIn -> "err_not_signed_in"
                ProviderErrorKind.SessionEnded -> "err_session_ended"
                ProviderErrorKind.QuotaExceeded -> "err_quota"
                ProviderErrorKind.PlanRequired -> "err_plan_required"
                ProviderErrorKind.DeviceRevoked -> "err_device_revoked"
                ProviderErrorKind.AccountDisabled -> "err_account_disabled"
                ProviderErrorKind.UpdateRequired -> "err_update_required"
                ProviderErrorKind.RateLimited -> "err_rate_limited"
                ProviderErrorKind.Network -> "err_network"
                ProviderErrorKind.Timeout -> "err_timeout"
                ProviderErrorKind.ServiceUnavailable, ProviderErrorKind.ServerError -> "err_service_unavailable"
                ProviderErrorKind.BadRequest -> "err_bad_request"
                ProviderErrorKind.Cancelled -> "err_cancelled"
                else -> "err_unknown"
            },
        )
    }
}

/** Streaming speech-to-text. Listeners may be raised from a background thread. */
interface ITranscriber {
    val speechStartedListeners: MutableList<() -> Unit>
    val speechStoppedListeners: MutableList<() -> Unit>

    /** (itemId, delta) */
    val partialTranscriptListeners: MutableList<(String, String) -> Unit>

    /** (itemId, transcript) */
    val segmentCompletedListeners: MutableList<(String, String) -> Unit>

    /** Item id of the speech segment that the most recent speech-stopped ended (null when unknown). */
    val lastStoppedItemId: String? get() = null
    val statusChangedListeners: MutableList<(ConnectionStatus, String?) -> Unit>
    suspend fun startAsync()
    suspend fun stopAsync()

    /** Queues 24 kHz mono PCM16 audio. Never blocks; drops oldest audio under backpressure. */
    fun sendAudio(pcm16Frame: ByteArray)
    val status: ConnectionStatus
    suspend fun disposeAsync()
}

/** Streaming answer generation. Yields raw text deltas. */
interface IAnswerProvider {
    val name: String
    fun streamAsync(messages: List<ChatMessage>, maxTokens: Int): Flow<String>
}
