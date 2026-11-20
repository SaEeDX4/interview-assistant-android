package com.interviewassistant.client

import com.interviewassistant.contracts.ContractJson
import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Direct tests for `CloudAnswerProvider.cs` (P7 coverage closure): the purpose resolution
 * (auto/live/manual/prep and which ids each purpose sends), the `no_active_session` fallback
 * that re-asks as a typed question (auto + live only, exactly once), the mid-stream error
 * mapping, caller cancellation becoming `ProviderException(Cancelled)`, and the complete
 * [CloudAnswerProvider.map] taxonomy — every named code in the C#'s switch plus the status
 * fallbacks.
 */
class CloudAnswerProviderTests {

    private class FakeTransport(private val respond: (Request, String) -> Response) : Interceptor {
        val calls = mutableListOf<Pair<Request, String>>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let {
                val buffer = Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
            calls += request to body
            return respond(request, body)
        }
    }

    /** A body that delivers one chunk, then blocks until closed (a stalled upstream). */
    private class StallingSource(first: String) : Source {
        private val firstBytes: ByteArray = first.toByteArray(Charsets.UTF_8)
        private var sent: Boolean = false
        private val released = CountDownLatch(1)

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!sent) {
                sent = true
                sink.write(firstBytes)
                return firstBytes.size.toLong()
            }
            released.await()
            throw InterruptedIOException("closed")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            released.countDown()
        }
    }

    private class StallingBody(first: String) : ResponseBody() {
        private val source: BufferedSource = StallingSource(first).buffer()
        override fun contentType(): okhttp3.MediaType? = "text/event-stream".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    companion object {
        private val SESSION: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        private val PREP: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
        private val DEVICE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val MSGS = listOf(ChatMessage("system", "s"), ChatMessage("user", "q"))

        private fun http(fake: FakeTransport): OkHttpClient = OkHttpClient.Builder().addInterceptor(fake).build()

        private fun response(request: Request, code: Int, body: ResponseBody): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body)
                .build()

        private fun sse(request: Request, vararg deltas: String): Response {
            val sb = StringBuilder()
            for (d in deltas) {
                val escaped = kotlinx.serialization.json.JsonPrimitive(d).toString()
                sb.append("data: {\"delta\":").append(escaped).append("}\n\n")
            }
            sb.append("data: [DONE]\n\n")
            return response(request, 200, sb.toString().toResponseBody("text/event-stream".toMediaType()))
        }

        private fun error(request: Request, status: Int, code: String): Response =
            response(request, status, """{"code":"$code","message":"m-$code"}""".toResponseBody("application/json".toMediaType()))

        private fun api(fake: FakeTransport): BackendClient =
            BackendClient(http(fake), { "tok" }, "https://api.example.test", "2.1.0")

        private fun sentRequest(fake: FakeTransport, index: Int = 0): kotlinx.serialization.json.JsonObject =
            ContractJson.instance.parseToJsonElement(fake.calls[index].second).jsonObject

        private suspend fun collectError(p: CloudAnswerProvider): ProviderException {
            try {
                p.streamAsync(MSGS, 10).toList()
            } catch (e: ProviderException) {
                return e
            }
            fail("expected ProviderException")
            throw AssertionError("unreachable")
        }
    }

    // ---- purpose resolution ----

    @Test
    fun auto_WithAnActiveSession_StreamsAsLiveWithTheSessionId() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req, "a1", "a2") }
            val p = CloudAnswerProvider(api(fake), { SESSION }, deviceId = { DEVICE })
            assertEquals("Cloud", p.name)
            assertNull(p.lastPurpose)
            assertEquals(listOf("a1", "a2"), p.streamAsync(MSGS, 42).toList())
            assertEquals("live", p.lastPurpose)
            val sent = sentRequest(fake)
            assertEquals("live", sent["purpose"]!!.jsonPrimitive.content)
            assertEquals(SESSION.toString(), sent["sessionId"]!!.jsonPrimitive.content)
            assertEquals(DEVICE.toString(), sent["deviceId"]!!.jsonPrimitive.content)
            assertTrue(sent["prepJobId"] is JsonNull)
            assertEquals(42, sent["maxTokens"]!!.jsonPrimitive.content.toInt())
            val messages = sent["messages"]!!.toString()
            assertTrue(messages.contains("\"role\":\"system\""))
            assertTrue(messages.contains("\"content\":\"q\""))
        }
    }

    @Test
    fun auto_WithoutASession_StreamsAsManual() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req, "m1") }
            val p = CloudAnswerProvider(api(fake), { null })
            assertEquals(listOf("m1"), p.streamAsync(MSGS, 10).toList())
            assertEquals("manual", p.lastPurpose)
            val sent = sentRequest(fake)
            assertEquals("manual", sent["purpose"]!!.jsonPrimitive.content)
            assertTrue(sent["sessionId"] is JsonNull)
        }
    }

    @Test
    fun livePurpose_AlwaysUsesTheSession_EvenTheCallersNullCheck() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req, "l1") }
            val p = CloudAnswerProvider(api(fake), { SESSION }, purpose = "live")
            assertEquals(listOf("l1"), p.streamAsync(MSGS, 10).toList())
            assertEquals("live", p.lastPurpose)
            assertEquals(SESSION.toString(), sentRequest(fake)["sessionId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun manualPurpose_NeverConsultsTheSession_AndSendsNoIds() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req, "typed") }
            var consulted = false
            val p = CloudAnswerProvider(api(fake), { consulted = true; SESSION }, purpose = "manual")
            assertEquals(listOf("typed"), p.streamAsync(MSGS, 10).toList())
            assertEquals("manual", p.lastPurpose)
            assertEquals(false, consulted) // the C# reads the session id only for auto/live
            val sent = sentRequest(fake)
            assertTrue(sent["sessionId"] is JsonNull)
            // No deviceId lambda was supplied: the field carries its DTO default, so the
            // request writer omits it (or writes null) — either way no device id is sent.
            assertTrue(sent["deviceId"] == null || sent["deviceId"] is JsonNull)
        }
    }

    @Test
    fun prepPurpose_SendsThePrepJob_AndNoSession() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req, "p1") }
            var consulted = false
            val p = CloudAnswerProvider(api(fake), { consulted = true; SESSION }, prepJobId = { PREP }, purpose = "prep")
            assertEquals(listOf("p1"), p.streamAsync(MSGS, 10).toList())
            assertEquals("prep", p.lastPurpose)
            assertEquals(false, consulted)
            val sent = sentRequest(fake)
            assertEquals("prep", sent["purpose"]!!.jsonPrimitive.content)
            assertEquals(PREP.toString(), sent["prepJobId"]!!.jsonPrimitive.content)
            assertTrue(sent["sessionId"] is JsonNull)
        }
    }

    @Test
    fun emptyStream_EmitsNothing_ButStillRecordsThePurpose() {
        runBlocking {
            val fake = FakeTransport { req, _ -> sse(req) }
            val p = CloudAnswerProvider(api(fake), { null })
            assertEquals(emptyList<String>(), p.streamAsync(MSGS, 10).toList())
            assertEquals("manual", p.lastPurpose)
        }
    }

    // ---- the no_active_session fallback ----

    @Test
    fun autoLive_NoActiveSession_FallsBackToATypedQuestion() {
        runBlocking {
            var n = 0
            val fake = FakeTransport { req, _ ->
                n++
                if (n == 1) error(req, 409, "no_active_session") else sse(req, "fallback answer")
            }
            val p = CloudAnswerProvider(api(fake), { SESSION })
            assertEquals(listOf("fallback answer"), p.streamAsync(MSGS, 10).toList())
            assertEquals("manual", p.lastPurpose)
            assertEquals(2, fake.calls.size)
            assertEquals("live", sentRequest(fake, 0)["purpose"]!!.jsonPrimitive.content)
            val second = sentRequest(fake, 1)
            assertEquals("manual", second["purpose"]!!.jsonPrimitive.content)
            assertTrue(second["sessionId"] is JsonNull)
        }
    }

    @Test
    fun autoLive_FallbackFailure_IsMappedFromTheSecondError() {
        runBlocking {
            // The fallback stream itself fails (an SSE error event on the re-asked question):
            // the second failure is mapped like any other, per the C#'s inner catch.
            // (A non-2xx answer to the fallback REQUEST is a different stage — see DC-199.)
            var n = 0
            val fake = FakeTransport { req, _ ->
                n++
                if (n == 1) {
                    error(req, 409, "no_active_session")
                } else {
                    response(req, 200, "event: error\ndata: {\"error\":\"died\"}\n\n".toResponseBody("text/event-stream".toMediaType()))
                }
            }
            val p = CloudAnswerProvider(api(fake), { SESSION })
            val ex = collectError(p)
            assertEquals(ProviderErrorKind.ServiceUnavailable, ex.kind)
            assertEquals("ai_interrupted", ex.code)
            assertEquals(2, fake.calls.size)
        }
    }

    @Test
    fun livePurpose_NoActiveSession_DoesNotFallBack() {
        runBlocking {
            val fake = FakeTransport { req, _ -> error(req, 409, "no_active_session") }
            val p = CloudAnswerProvider(api(fake), { SESSION }, purpose = "live")
            val ex = collectError(p)
            assertEquals(ProviderErrorKind.SessionEnded, ex.kind)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun manualPurpose_NoActiveSession_DoesNotFallBack() {
        runBlocking {
            val fake = FakeTransport { req, _ -> error(req, 409, "no_active_session") }
            val p = CloudAnswerProvider(api(fake), { SESSION }, purpose = "manual")
            val ex = collectError(p)
            assertEquals(ProviderErrorKind.SessionEnded, ex.kind)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun autoLive_OtherErrors_DoNotFallBack() {
        runBlocking {
            val fake = FakeTransport { req, _ -> error(req, 403, "daily_cap") }
            val p = CloudAnswerProvider(api(fake), { SESSION })
            val ex = collectError(p)
            assertEquals(ProviderErrorKind.QuotaExceeded, ex.kind)
            assertEquals(1, fake.calls.size)
        }
    }

    // ---- mid-stream failures ----

    @Test
    fun midStreamErrorEvent_MapsToServiceUnavailable_AfterEmittingEarlierDeltas() {
        runBlocking {
            val body = "data: {\"delta\":\"first\"}\n\n" +
                "event: error\n" +
                "data: {\"error\":\"died\"}\n\n"
            val fake = FakeTransport { req, _ -> response(req, 200, body.toResponseBody("text/event-stream".toMediaType())) }
            val p = CloudAnswerProvider(api(fake), { null })
            val parts = mutableListOf<String>()
            val ex = try {
                p.streamAsync(MSGS, 10).collect { parts += it }
                null
            } catch (e: ProviderException) {
                e
            }
            assertEquals(listOf("first"), parts) // the delta before the error was still delivered
            assertEquals(ProviderErrorKind.ServiceUnavailable, ex!!.kind)
            assertEquals("ai_interrupted", ex.code)
        }
    }

    @Test
    fun truncatedStream_MapsToServiceUnavailable() {
        runBlocking {
            val body = "data: {\"delta\":\"half\"}\n\n" // no [DONE]
            val fake = FakeTransport { req, _ -> response(req, 200, body.toResponseBody("text/event-stream".toMediaType())) }
            val p = CloudAnswerProvider(api(fake), { null })
            val ex = collectError(p)
            assertEquals(ProviderErrorKind.ServiceUnavailable, ex.kind)
            assertEquals("stream_truncated", ex.code)
        }
    }

    @Test
    fun callerCancellation_BecomesAProviderCancelledError() {
        runBlocking {
            supervisorScope {
                val body = StallingBody("data: {\"delta\":\"first\"}\n\n")
                val fake = FakeTransport { req, _ -> response(req, 200, body) }
                val p = CloudAnswerProvider(api(fake), { null })
                val parts = mutableListOf<String>()
                var failure: ProviderException? = null
                // A launch (not an async): the outcome is read from the captured variable after
                // join(), because await() on a cancelled deferred would discard the very
                // ProviderException the body caught and rethrow JobCancellationException instead.
                val collecting = launch {
                    try {
                        p.streamAsync(MSGS, 10).collect { parts += it }
                    } catch (e: ProviderException) {
                        failure = e
                    }
                }
                // Wait until the first delta has actually been delivered.
                var i = 0
                while (i < 200 && parts.isEmpty()) {
                    kotlinx.coroutines.delay(10)
                    i++
                }
                assertEquals(listOf("first"), parts)
                collecting.cancel()
                body.close() // unblocks the stalled read; the session sees the cancelled context
                collecting.join()
                assertEquals(ProviderErrorKind.Cancelled, failure!!.kind)
                assertEquals("Cancelled", failure!!.message)
            }
        }
    }

    // ---- the Map taxonomy (the C#'s switch, exhaustively) ----

    @Test
    fun map_NamedCodes_KeepTheirMeaning() {
        val cases = listOf(
            Triple("no_active_session", 409, ProviderErrorKind.SessionEnded),
            Triple("session_ended", 409, ProviderErrorKind.SessionEnded),
            Triple("daily_cap", 403, ProviderErrorKind.QuotaExceeded),
            Triple("manual_daily_cap", 403, ProviderErrorKind.QuotaExceeded),
            Triple("prep_ai_limit", 403, ProviderErrorKind.QuotaExceeded),
            Triple("prep_limit", 403, ProviderErrorKind.QuotaExceeded),
            Triple("allowance_exhausted", 402, ProviderErrorKind.QuotaExceeded),
            Triple("plan_required", 402, ProviderErrorKind.PlanRequired),
            Triple("global_cap", 503, ProviderErrorKind.ServiceUnavailable),
            Triple("ai_unavailable", 503, ProviderErrorKind.ServiceUnavailable),
            Triple("ai_not_configured", 500, ProviderErrorKind.ServiceUnavailable),
            Triple("maintenance", 503, ProviderErrorKind.ServiceUnavailable),
            Triple("ai_interrupted", 502, ProviderErrorKind.ServiceUnavailable),
            Triple("stream_truncated", 502, ProviderErrorKind.ServiceUnavailable),
            Triple("device_revoked", 403, ProviderErrorKind.DeviceRevoked),
            Triple("device_unknown", 403, ProviderErrorKind.DeviceRevoked),
            Triple("device_limit", 403, ProviderErrorKind.DeviceRevoked),
            Triple("account_disabled", 403, ProviderErrorKind.AccountDisabled),
            Triple("account_deleted", 403, ProviderErrorKind.AccountDisabled),
            Triple("client_update_required", 426, ProviderErrorKind.UpdateRequired),
            Triple("signed_out", 401, ProviderErrorKind.NotSignedIn),
            Triple("invalid_token", 401, ProviderErrorKind.NotSignedIn),
            Triple("ai_rate_limited", 429, ProviderErrorKind.RateLimited),
            Triple("rate_limited", 429, ProviderErrorKind.RateLimited),
            Triple("invalid_messages", 400, ProviderErrorKind.BadRequest),
            Triple("invalid_role", 400, ProviderErrorKind.BadRequest),
            Triple("prompt_too_large", 413, ProviderErrorKind.BadRequest),
            Triple("invalid_purpose", 400, ProviderErrorKind.BadRequest),
        )
        for ((code, status, kind) in cases) {
            val backend = BackendException(status, code, "msg-$code", "cid-$code")
            val mapped = CloudAnswerProvider.map(backend)
            assertEquals("code $code", kind, mapped.kind)
            assertEquals(code, mapped.code)
            assertEquals("cid-$code", mapped.correlationId)
            assertEquals("msg-$code", mapped.message)
            assertSame(backend, mapped.cause)
        }
    }

    @Test
    fun map_UnknownCodes_FallBackToTheStatus() {
        val cases = listOf(
            0 to ProviderErrorKind.Network,
            401 to ProviderErrorKind.NotSignedIn,
            402 to ProviderErrorKind.PlanRequired,
            400 to ProviderErrorKind.BadRequest,
            413 to ProviderErrorKind.BadRequest,
            408 to ProviderErrorKind.Timeout,
            504 to ProviderErrorKind.Timeout,
            426 to ProviderErrorKind.UpdateRequired,
            429 to ProviderErrorKind.RateLimited,
            500 to ProviderErrorKind.ServiceUnavailable,
            503 to ProviderErrorKind.ServiceUnavailable,
            403 to ProviderErrorKind.Unknown,
            404 to ProviderErrorKind.Unknown,
            302 to ProviderErrorKind.Unknown,
        )
        for ((status, kind) in cases) {
            val mapped = CloudAnswerProvider.map(BackendException(status, "some_unlisted_code", "m", null))
            assertEquals("status $status", kind, mapped.kind)
            assertEquals("some_unlisted_code", mapped.code)
            assertNull(mapped.correlationId)
        }
    }
}
