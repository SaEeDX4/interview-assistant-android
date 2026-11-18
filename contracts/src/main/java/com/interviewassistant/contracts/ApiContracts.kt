package com.interviewassistant.contracts

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Mirror of `InterviewAssistant.Contracts/ApiContracts.cs` (TRANSFER-MAP §2, layer: Shared).
 * Full 1:1 port: every DTO, field, default and constant of the C# file, in the same order.
 *
 * Wire format — verified from the Windows source, not assumed: both the desktop client
 * (`BackendClient.Json = JsonSerializerDefaults.Web`) and the backend
 * (`ConfigureHttpJsonOptions` + `JsonNamingPolicy.CamelCase`) serialize with the System.Text.Json Web
 * defaults. Property names are therefore **camelCase** (pinned per-field with @SerialName below), nulls
 * and defaults ARE written (no ignore-condition is configured anywhere in the Windows source), unknown
 * properties are ignored on read, and property matching on read is case-insensitive. Dates are ISO-8601
 * (`DateTime` UTC values carry the `Z` suffix), GUIDs are lowercase `d`-format. [ContractJson] is the
 * shared Json instance with exactly those semantics. (TRANSFER-MAP §5.4 says "PascalCase names"; the code
 * on both sides of the wire says camelCase — the code wins, see P2-PORT-NOTES.md.)
 */

// ---- shared: /api/v1 contracts used by the desktop client and the backend ----

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val correlationId: String? = null,
)

/** Server-authoritative entitlements. Every field is enforced (server-side, or locally for data that never leaves the PC). */
@Serializable
data class Entitlements(
    val planCode: String,
    val planName: String,
    val status: String,
    val maxProfiles: Int,
    val prepJobsPerMonth: Int,
    val liveMinutesPerPeriod: Int,
    val maxConcurrentSessions: Int,
    val maxDevices: Int,
    val reportRetentionDays: Int,
    val coachMode: Boolean,
    val trialMinutes: Int,
)

/** Customer-facing billing state, computed by the server (the desktop only maps it to text). */
object BillingStates {
    const val Trial: String = "trial"
    const val TrialExhausted: String = "trial_exhausted"
    const val Active: String = "active"
    const val Canceling: String = "canceling"
    const val PastDue: String = "past_due"
    const val Paused: String = "paused"
    const val Expired: String = "expired"

    /** The payment for the current period was refunded or charged back: paid access ended. */
    const val Revoked: String = "revoked"
}

/**
 * [liveSecondsRemaining] is the plan allowance left in the billing period. [liveSecondsAvailableToday]
 * additionally applies the per-account daily safety cap (what can actually be used today); -1 when unknown.
 */
@Serializable
data class UsageSummary(
    @Serializable(with = UtcDateTimeSerializer::class) val periodStartUtc: OffsetDateTime,
    @Serializable(with = UtcDateTimeSerializer::class) val periodEndUtc: OffsetDateTime,
    val liveSecondsUsed: Int,
    val liveSecondsRemaining: Int,
    val prepJobsUsed: Int,
    val prepJobsRemaining: Int,
    val answerRequestsToday: Int,
    val liveSecondsAvailableToday: Int = -1,
    val dailyLiveMinutesCap: Int = 0,
)

@Serializable
data class AccountDto(
    @Serializable(with = UuidSerializer::class) val userId: UUID,
    val email: String,
    val status: String,
    val entitlements: Entitlements,
    val usage: UsageSummary,
    val subscription: SubscriptionDto?,
    @Serializable(with = UtcDateTimeSerializer::class) val createdUtc: OffsetDateTime,
    val billingState: String,
)

@Serializable
data class SubscriptionDto(
    val planCode: String,
    val status: String,
    @Serializable(with = UtcDateTimeNullableSerializer::class) val currentPeriodEndUtc: OffsetDateTime?,
    val cancelAtPeriodEnd: Boolean,
    @Serializable(with = UtcDateTimeNullableSerializer::class) val graceUntilUtc: OffsetDateTime?,
)

/** @param identity "deleted": the sign-in identity was removed too; "pending": it is queued and removed automatically. */
@Serializable
data class DeleteAccountResponse(val identity: String)

@Serializable
data class RegisterDeviceRequest(
    val installationId: String,
    val name: String,
    val platform: String,
    val clientVersion: String,
)

@Serializable
data class DeviceDto(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val name: String,
    val platform: String,
    val clientVersion: String,
    @Serializable(with = UtcDateTimeSerializer::class) val lastSeenUtc: OffsetDateTime,
    val revoked: Boolean,
    val isCurrent: Boolean,
)

/** Opaque client-owned JSON payload (profile/target/report) stored per user with optimistic concurrency. */
@Serializable
data class SyncDocument(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val kind: String,
    @Serializable(with = UuidNullableSerializer::class) val parentId: UUID?,
    val data: JsonElement,
    val version: Long,
    @Serializable(with = UtcDateTimeSerializer::class) val updatedUtc: OffsetDateTime,
)

@Serializable
data class UpsertDocumentRequest(
    @Serializable(with = UuidNullableSerializer::class) val parentId: UUID?,
    val data: JsonElement,
    val expectedVersion: Long?,
)

/** @param relay The client streams audio through the service's relay (required since 2.1.0-rc.3; older clients are asked to update). */
@Serializable
data class StartSessionRequest(
    @Serializable(with = UuidSerializer::class) val deviceId: UUID,
    @Serializable(with = UuidNullableSerializer::class) val targetId: UUID?,
    val idempotencyKey: String,
    val interviewLanguage: String?,
    val relay: Boolean = false,
)

/**
 * Live transcription endpoint. For cloud sessions [relay] is true, [clientSecret] is empty and
 * [webSocketUrl] is a path on the service (the relay); the provider credential never leaves the server.
 */
@Serializable
data class RealtimeCredential(
    val clientSecret: String,
    @Serializable(with = UtcDateTimeSerializer::class) val expiresAtUtc: OffsetDateTime,
    val model: String,
    val webSocketUrl: String,
    val transcriptionPrompt: String,
    val relay: Boolean = false,
)

/** [remainingSeconds] = what this session may still use (min of plan period, today's cap, operator cap). */
@Serializable
data class StartSessionResponse(
    @Serializable(with = UuidSerializer::class) val sessionId: UUID,
    val realtime: RealtimeCredential,
    val heartbeatIntervalSeconds: Int,
    val maxDurationSeconds: Int,
    val remainingSeconds: Int,
    val periodRemainingSeconds: Int = -1,
    val todayRemainingSeconds: Int = -1,
)

@Serializable
data class HeartbeatRequest(val sequence: Long)

@Serializable
data class HeartbeatResponse(
    val remainingSeconds: Int,
    val active: Boolean,
    val endReason: String?,
    val periodRemainingSeconds: Int = -1,
    val todayRemainingSeconds: Int = -1,
)

@Serializable
data class AnswerMessage(val role: String, val content: String)

/**
 * Purpose: "live" (requires an active session lease), "prep" (requires a preparation job) or "manual" (typed question;
 * no lease, never bills live minutes, entitlement- and rate-limited). DeviceId lets the server reject revoked devices.
 */
@Serializable
data class AnswerStreamRequest(
    @Serializable(with = UuidNullableSerializer::class) val sessionId: UUID?,
    @Serializable(with = UuidNullableSerializer::class) val prepJobId: UUID?,
    val messages: List<AnswerMessage>,
    val maxTokens: Int,
    val purpose: String,
    @Serializable(with = UuidNullableSerializer::class) val deviceId: UUID? = null,
)

object AnswerPurposes {
    const val Live: String = "live"
    const val Prep: String = "prep"
    const val Manual: String = "manual"
}

/**
 * Minimal per-interview transcription context synced as the user's "target" document (no résumé or job-description
 * text): it biases speech recognition toward the interview's names and terms.
 */
@Serializable
data class TranscriptionContextDocument(
    val jobTitle: String,
    val company: String,
    val vocabulary: List<String>,
    val language: String,
)

@Serializable
data class PrepJobResponse(
    @Serializable(with = UuidSerializer::class) val jobId: UUID,
    val prepJobsRemaining: Int,
)

/** Price comes from Paddle (authoritative); PriceDisplay is null when Paddle could not be reached. */
@Serializable
data class PlanDto(
    val code: String,
    val name: String,
    val entitlements: Entitlements,
    val purchasable: Boolean,
    val priceDisplay: String?,
)

@Serializable
data class CheckoutRequest(val planCode: String)

@Serializable
data class UrlResponse(val url: String)

@Serializable
data class RuntimeConfig(
    val transcriptionModel: String,
    val transcriptionFallbacks: List<String>,
    val answerModel: String,
    val answerFallbacks: List<String>,
    val preparationModel: String,
    val answerMaxTokens: Int,
    val firstTokenTimeoutMs: Int,
    val features: Map<String, Boolean>,
    val maintenance: Boolean,
    val maintenanceMessage: String?,
    val minimumClientVersion: String,
    val offlineGraceHours: Int,
    val auth: AuthClientConfig? = null,
    val links: ProductLinks? = null,
    val billingEnvironment: String = "sandbox",
)

/** Public sign-in configuration for the desktop (publishable values only — never a secret). */
@Serializable
data class AuthClientConfig(
    val available: Boolean,
    val supabaseUrl: String,
    val publishableKey: String,
    val emailPassword: Boolean,
    val oAuthProviders: List<String>,
    val emailConfirmedRedirectUrl: String?,
)

/** Customer-facing links; null when the operator has not configured them (never invented). */
@Serializable
data class ProductLinks(
    val privacyPolicy: String?,
    val terms: String?,
    val billingTerms: String?,
    val support: String?,
    val supportEmail: String?,
)

// ---- wire-format primitives (System.Text.Json Web semantics) ----

/**
 * The shared JSON configuration mirroring `JsonSerializerDefaults.Web` (the C# `BackendClient.Json`):
 * camelCase names (pinned by @SerialName-free property names above — Kotlin property names are already the
 * camelCase wire names), defaults and nulls written, unknown properties ignored on read.
 */
object ContractJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/** System.Guid: lowercase "d" format on write; any standard GUID text on read. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object UuidNullableSerializer : KSerializer<UUID?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.UUID?", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID? =
        if (decoder.decodeNotNullMark()) UUID.fromString(decoder.decodeString()) else decoder.decodeNull()
}

/**
 * System.DateTime (UTC): ISO-8601 with the `Z` suffix; the fractional part appears only when non-zero,
 * with up to 7 digits (100 ns ticks) and no trailing zeros — exactly System.Text.Json's rendering.
 * Reading accepts any ISO offset and normalizes to UTC, like System.Text.Json into a UTC DateTime.
 */
object UtcDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("System.DateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(formatUtc(value))
    override fun deserialize(decoder: Decoder): OffsetDateTime = parseUtc(decoder.decodeString())

    internal fun formatUtc(value: OffsetDateTime): String {
        val utc = value.withOffsetSameInstant(ZoneOffset.UTC)
        val base = utc.format(BASE)
        if (utc.nano == 0) return base + "Z"
        // Ticks are 100 ns units: 7 digits taken from the 9-digit nanosecond fraction, trailing zeros trimmed.
        val ticks = (utc.nano / 100).toString().padStart(7, '0').trimEnd('0')
        return "$base.$ticks" + "Z"
    }

    internal fun parseUtc(text: String): OffsetDateTime {
        val trimmed = text.trim()
        return try {
            OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withOffsetSameInstant(ZoneOffset.UTC)
        } catch (e: RuntimeException) {
            try {
                // No offset (System.Text.Json would read it as an unspecified DateTime): treated as UTC.
                LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC)
            } catch (e2: RuntimeException) {
                OffsetDateTime.parse(trimmed.replace("Z", "+00:00"), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
            }
        }
    }

    private val BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
}

object UtcDateTimeNullableSerializer : KSerializer<OffsetDateTime?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("System.DateTime?", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(UtcDateTimeSerializer.formatUtc(value))
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime? =
        if (decoder.decodeNotNullMark()) UtcDateTimeSerializer.parseUtc(decoder.decodeString()) else decoder.decodeNull()
}
