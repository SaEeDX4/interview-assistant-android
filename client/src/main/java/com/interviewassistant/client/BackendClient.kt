package com.interviewassistant.client

import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.ApiError
import com.interviewassistant.contracts.AnswerStreamRequest
import com.interviewassistant.contracts.CheckoutRequest
import com.interviewassistant.contracts.ContractJson
import com.interviewassistant.contracts.DeleteAccountResponse
import com.interviewassistant.contracts.DeviceDto
import com.interviewassistant.contracts.HeartbeatRequest
import com.interviewassistant.contracts.HeartbeatResponse
import com.interviewassistant.contracts.PlanDto
import com.interviewassistant.contracts.PrepJobResponse
import com.interviewassistant.contracts.RealtimeCredential
import com.interviewassistant.contracts.RegisterDeviceRequest
import com.interviewassistant.contracts.RuntimeConfig
import com.interviewassistant.contracts.StartSessionRequest
import com.interviewassistant.contracts.StartSessionResponse
import com.interviewassistant.contracts.SyncDocument
import com.interviewassistant.contracts.TranscriptionContextDocument
import com.interviewassistant.contracts.UpsertDocumentRequest
import com.interviewassistant.contracts.UrlResponse
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Mirror of `InterviewAssistant.Client/BackendClient.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port: every endpoint, the 401-refresh retry, the per-request timeout, the correlation-id
 * discipline and the SSE answer-stream parser.
 *
 * C#→Kotlin adaptations (P2-PORT-NOTES.md): `HttpClient` becomes the injected [OkHttpClient] plus an
 * explicit [baseUrl] (OkHttp has no `BaseAddress`; the composition root passes
 * `CloudOptions.apiBaseUrl`, normalized exactly like the C# composition root's
 * `new Uri(ApiBaseUrl.TrimEnd('/') + "/")`). `IAsyncEnumerable<string>` becomes [Flow]; the pull-based
 * enumerator the C# hands to `CloudAnswerProvider` is [AnswerStreamSession]. The shared OkHttpClient
 * must be configured without read/call timeouts (the C#'s HttpClient likewise has none) so streams can
 * run; every request here carries its own [requestTimeout] instead — for buffered requests the timeout
 * covers the body read, exactly like the C#'s `ResponseContentRead` under its timeout token.
 */

class BackendException(
    val status: Int,
    val code: String,
    message: String,
    val correlationId: String?,
) : Exception(message) {
    /** The diagnostic the C# carries in `Exception.Source` (the transport exception's message). */
    var source: String? = null

    val isUpdateRequired: Boolean get() = status == 426
    val isEntitlement: Boolean
        get() = status == 402 || code == "device_limit" || code == "concurrent_limit" || code == "allowance_exhausted"
}

/** Typed client for /api/v1. Adds bearer token, client version and correlation id; retries once after a token refresh on 401. */
class BackendClient(
    private val http: OkHttpClient,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    baseUrl: String,
    private val version: String,
) {
    private val baseUrl: String = baseUrl.trimEnd('/') + "/"

    /** Longest wait for a response (for streams: for the response headers). */
    var requestTimeout: Duration = 30.seconds

    var lastCorrelationId: String? = null
        private set

    val clientVersion: String get() = version

    /** The signed-in user's access token (used to authenticate the live transcription relay). */
    suspend fun accessTokenAsync(forceRefresh: Boolean = false): String? = tokenProvider(forceRefresh)

    /** Absolute ws(s):// address of a relay path returned by the service (relative to the API base address). */
    fun webSocketUri(relativePath: String, language: String?): URI {
        val baseUri = URI(baseUrl)
        val resolved = baseUri.resolve(relativePath)
        val scheme = if (baseUri.scheme == "https") "wss" else "ws"
        val query = if (!language.isNullOrBlank()) "language=" + UriEscape.escapeDataString(language) else resolved.query
        return URI(scheme, resolved.userInfo, resolved.host, resolved.port, resolved.path, query, null)
    }

    // ---- public ----
    suspend fun configAsync(): RuntimeConfig = send("GET", "config", null, auth = false)
    suspend fun plansAsync(): List<PlanDto> = send("GET", "billing/plans", null, auth = false)

    // ---- account ----
    suspend fun meAsync(): AccountDto = send("GET", "me", null)
    suspend fun exportAsync(): JsonElement = send("GET", "me/export", null)

    /** Deletes the account, its data and its sign-in identity (null if an older service answered without details). */
    suspend fun deleteAccountAsync(): DeleteAccountResponse? = sendNullable("DELETE", "me", null)
    suspend fun checkoutAsync(planCode: String): UrlResponse = send("POST", "billing/checkout", jsonBody(CheckoutRequest(planCode)))
    suspend fun portalAsync(): UrlResponse = send("POST", "billing/portal", null)

    // ---- devices ----
    suspend fun registerDeviceAsync(r: RegisterDeviceRequest): DeviceDto = send("POST", "devices", jsonBody(r))
    suspend fun devicesAsync(): List<DeviceDto> = send("GET", "devices", null)
    suspend fun revokeDeviceAsync(id: UUID) {
        sendNullable<JsonElement>("DELETE", "devices/$id", null)
    }

    // ---- synced documents: kind = profiles | targets | reports ----
    suspend fun listAsync(kind: String, parentId: UUID?): List<SyncDocument> =
        send("GET", if (parentId == null) kind else "$kind?parentId=$parentId", null)

    suspend fun putAsync(kind: String, id: UUID, r: UpsertDocumentRequest): SyncDocument =
        send("PUT", "$kind/$id", jsonBody(r))

    suspend fun deleteDocumentAsync(kind: String, id: UUID) {
        sendNullable<JsonElement>("DELETE", "$kind/$id", null)
    }

    // ---- preparation + sessions ----
    suspend fun startPreparationAsync(): PrepJobResponse = send("POST", "preparation/jobs", null)
    suspend fun startSessionAsync(r: StartSessionRequest): StartSessionResponse = send("POST", "sessions", jsonBody(r))
    suspend fun heartbeatAsync(id: UUID, seq: Long): HeartbeatResponse =
        send("POST", "sessions/$id/heartbeat", jsonBody(HeartbeatRequest(seq)))

    suspend fun renewRealtimeSecretAsync(id: UUID, language: String? = null): RealtimeCredential =
        send("POST", "sessions/$id/realtime-secret" + (if (language.isNullOrBlank()) "" else "?language=" + UriEscape.escapeDataString(language)), null)

    /** Publishes the interview's transcription context (job title, company, vocabulary) as the user's own target document. */
    suspend fun putTranscriptionContextAsync(targetId: UUID, doc: TranscriptionContextDocument): SyncDocument =
        putAsync(
            "targets", targetId,
            UpsertDocumentRequest(null, Json.encodeToJsonElement(TranscriptionContextDocument.serializer(), doc), null),
        )

    suspend fun stopSessionAsync(id: UUID) {
        sendNullable<JsonElement>("POST", "sessions/$id/stop", null)
    }

    /** Streams answer deltas from the server-side model (SSE). */
    fun streamAnswer(request: AnswerStreamRequest): Flow<String> = flow {
        val session = openAnswerStream(request)
        try {
            var delta = session.nextDelta()
            while (delta != null) {
                emit(delta)
                delta = session.nextDelta()
            }
        } finally {
            session.close()
        }
    }

    /**
     * Opens the answer stream and returns its pull-based session (the C#'s async enumerator):
     * [AnswerStreamSession.nextDelta] yields deltas until the stream's `[DONE]`, throws
     * [BackendException] `ai_interrupted` on an SSE `error` event and `stream_truncated` when the
     * stream ends without `[DONE]`.
     */
    suspend fun openAnswerStream(request: AnswerStreamRequest): AnswerStreamSession {
        val raw = sendRaw("POST", "answers/stream", jsonBody(request), auth = true, buffered = false)
        val resp = raw.response!!
        if (!resp.isSuccessful) {
            val error = toError(resp.code, resp.body.string())
            resp.close()
            throw error
        }
        return AnswerStreamSession(resp, lastCorrelationId)
    }

    private inline fun <reified T> jsonBody(value: T): String = Json.encodeToString(value)

    private suspend inline fun <reified T> send(method: String, path: String, bodyJson: String?, auth: Boolean = true): T {
        val result = sendNullable<T>(method, path, bodyJson, auth)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private suspend inline fun <reified T> sendNullable(method: String, path: String, bodyJson: String?, auth: Boolean = true): T? {
        val raw = sendRaw(method, path, bodyJson, auth, buffered = true)
        if (raw.code !in 200..299) throw toError(raw.code, raw.bodyText ?: "")
        if (raw.code == 204 || raw.bodyText.isNullOrEmpty()) return null
        return Json.decodeFromString<T>(raw.bodyText)
    }

    private class RawResult(val code: Int, val bodyText: String?, val response: Response?)

    private suspend fun sendRaw(method: String, path: String, bodyJson: String?, auth: Boolean, buffered: Boolean): RawResult {
        var attempt = 0
        while (true) {
            val cid = UUID.randomUUID().toString().replace("-", "")
            lastCorrelationId = cid
            val builder = Request.Builder()
                .url(baseUrl + "api/v1/" + path)
                .header("X-Client-Version", version)
                .header("X-Correlation-Id", cid)
            // OkHttp requires a body for POST/PUT/PATCH where HttpClient sends none: an empty body
            // (Content-Length: 0) is the wire equivalent of the C#'s content-less request.
            val requestBody = bodyJson?.toRequestBody(JSON_MEDIA)
                ?: if (method == "POST" || method == "PUT" || method == "PATCH") EMPTY_BODY else null
            builder.method(method, requestBody)
            if (auth) {
                val token = tokenProvider(attempt > 0) ?: throw BackendException(401, "signed_out", "Please sign in.", null)
                builder.header("Authorization", "Bearer $token")
            }
            val call = http.newCall(builder.build())
            try {
                if (buffered) {
                    // ResponseContentRead in the C#: headers + body, all inside the timeout.
                    val (code, text) = withTimeout(requestTimeout) {
                        call.executeAsync().use { r -> r.code to r.body.string() }
                    }
                    if (code == 401 && auth && attempt == 0) {
                        attempt++
                        continue
                    }
                    return RawResult(code, text, null)
                }
                // Streams: the timeout bounds the wait for the response headers only.
                val resp = withTimeout(requestTimeout) { call.executeAsync() }
                if (resp.code == 401 && auth && attempt == 0) {
                    resp.close()
                    attempt++
                    continue
                }
                return RawResult(resp.code, null, resp)
            } catch (e: IOException) {
                throw BackendException(0, "offline", "Cannot reach the service. Check your connection.", cid).apply { source = e.message }
            } catch (e: TimeoutCancellationException) {
                throw BackendException(504, "timeout", "The service did not answer in time.", cid)
            }
        }
    }

    private fun toError(status: Int, bodyText: String): BackendException {
        var e: ApiError? = null
        try {
            e = Json.decodeFromString(ApiError.serializer(), bodyText)
        } catch (ex: SerializationException) {
            // not an ApiError body — fall back below, as in the C#
        } catch (ex: IllegalArgumentException) {
            // malformed JSON — fall back below
        }
        return BackendException(
            status, e?.code ?: ("http_" + status),
            e?.message ?: "The service returned an error.", e?.correlationId ?: lastCorrelationId,
        )
    }

    companion object {
        /** The C#'s `BackendClient.Json` (`JsonSerializerDefaults.Web`): see [ContractJson]. */
        val Json: Json = ContractJson.instance

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** [tokenProvider] wired to a [SupabaseAuth] (the C#'s `BackendClient.ForAuth`). */
        fun forAuth(http: OkHttpClient, auth: SupabaseAuth, baseUrl: String, clientVersion: String): BackendClient =
            BackendClient(http, { force -> auth.getAccessTokenAsync(force) }, baseUrl, clientVersion)
    }
}

/**
 * Pull-based reader over one open answer stream — the mirror of the async enumerator the C#'s
 * `StreamAnswerAsync` hands to its callers. Line reads run on [Dispatchers.IO]; cancelling the reading
 * coroutine closes the stream (see [close] from the collector), as the C#'s CancellationToken does.
 */
class AnswerStreamSession internal constructor(
    private val response: Response,
    private val correlationId: String?,
) : AutoCloseable {
    private val reader = response.body.byteStream().bufferedReader()
    private var event: String? = null
    private var finished = false

    /** Next delta, or null at the stream's `[DONE]`. Throws [BackendException] on stream errors. */
    suspend fun nextDelta(): String? {
        if (finished) return null
        return withContext(Dispatchers.IO) {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                    throw e
                } ?: throw BackendException(502, "stream_truncated", "The answer stream ended unexpectedly.", correlationId)
                if (line.isEmpty()) {
                    event = null
                    continue
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim()
                    continue
                }
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") {
                    finished = true
                    return@withContext null
                }
                if (event == "error") throw BackendException(502, "ai_interrupted", "The answer stream was interrupted.", correlationId)
                val delta = ContractJson.instance.parseToJsonElement(data).jsonObject["delta"]?.jsonPrimitive?.content
                if (!delta.isNullOrEmpty()) return@withContext delta
            }
        }
    }

    override fun close() {
        try {
            reader.close()
        } catch (_: IOException) {
            // already closed
        }
        response.close()
    }
}
