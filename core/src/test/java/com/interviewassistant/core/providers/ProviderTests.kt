package com.interviewassistant.core.providers

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.intelligence.ChatMessage
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import okio.buffer
import okio.Source
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/ProviderTests.cs`.
 * The C# `HttpMessageHandler` stub seam becomes an OkHttp application interceptor (DC-38);
 * the stalling `Stream` becomes a blocking Okio [Source] released by close (DC-39).
 */
class ProviderTests {

    private class StubInterceptor(private val f: (Request) -> Response) : Interceptor {
        @Volatile
        var lastBody: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            lastBody = body
            return f(request)
        }
    }

    private fun stubClient(stub: StubInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(stub).build()

    private fun response(request: Request, code: Int, body: ResponseBody): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("stub").body(body).build()

    private fun jsonResponse(code: Int, text: String): (Request) -> Response =
        { request -> response(request, code, text.toResponseBody("application/json".toMediaType())) }

    private fun sse(vararg deltas: String): String {
        val sb = StringBuilder()
        for (d in deltas) {
            val escaped = kotlinx.serialization.json.JsonPrimitive(d).toString()
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":").append(escaped).append("}}]}\n\n")
        }
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }

    /** A body that delivers one chunk, then never sends anything again (a stalled upstream). */
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

    private val msgs: List<ChatMessage> = listOf(ChatMessage("system", "s"), ChatMessage("user", "u"))

    private suspend fun collectExpectingError(p: OpenAiChatAnswerProvider): ProviderException {
        try {
            p.streamAsync(msgs, 10).collect { }
        } catch (e: ProviderException) {
            return e
        }
        fail("expected ProviderException")
        throw AssertionError("unreachable")
    }

    @Test
    fun stalledStreamAfterTheFirstToken_TimesOutInsteadOfHanging() = runBlocking {
        val first = "data: {\"choices\":[{\"delta\":{\"content\":\"• I'd start\"}}]}\n\n"
        var lastBody: String? = null
        // Build a client whose interceptor returns the stalling body bound to the real request.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            lastBody = buffer.readUtf8()
            response(request, 200, StallingBody(first))
        }.build()
        val p = OpenAiChatAnswerProvider({ "k" }, ChatProviderOptions().apply { streamIdleTimeout = Duration.ofMillis(300) }, client)
        val parts = mutableListOf<String>()
        val started = System.nanoTime()
        val ex = try {
            p.streamAsync(msgs, 100).collect { d -> parts.add(d) }
            null
        } catch (e: ProviderException) {
            e
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(ProviderErrorKind.Timeout, ex?.kind)
        assertEquals(listOf("• I'd start"), parts)
        assertTrue("$elapsedMs ms", elapsedMs < 5000)
        assertFalse(Json.parseToJsonElement(lastBody!!).jsonObject["store"]!!.jsonPrimitive.boolean) // provider-side retention off
        p.close()
        Unit
    }

    @Test
    fun streamsDeltasFromSse() = runBlocking {
        val stub = StubInterceptor(jsonResponse(200, sse("MODE: HYPOTHETICAL\n• I'd", " start.\n")))
        val p = OpenAiChatAnswerProvider({ "sk-test" }, null, stubClient(stub))
        val parts = mutableListOf<String>()
        p.streamAsync(msgs, 100).collect { d -> parts.add(d) }
        assertEquals("MODE: HYPOTHETICAL\n• I'd start.\n", parts.joinToString(""))
        val body = Json.parseToJsonElement(stub.lastBody!!).jsonObject
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("gpt-5.4-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
        p.close()
    }

    @Test
    fun mapsHttpErrors() = runBlocking {
        val cases = listOf(
            401 to ProviderErrorKind.InvalidApiKey,
            429 to ProviderErrorKind.RateLimited,
            500 to ProviderErrorKind.ServerError,
            400 to ProviderErrorKind.BadRequest,
        )
        for ((code, kind) in cases) {
            val stub = StubInterceptor(jsonResponse(code, "{\"error\":{\"message\":\"boom\"}}"))
            val p = OpenAiChatAnswerProvider({ "sk-test" }, null, stubClient(stub))
            val ex = collectExpectingError(p)
            assertEquals("HTTP $code", kind, ex.kind)
            p.close()
        }
    }

    @Test
    fun missingKeyFailsFastWithoutNetwork() = runBlocking {
        val stub = StubInterceptor { _ -> throw IOException("should not call") }
        val p = OpenAiChatAnswerProvider({ null }, null, stubClient(stub))
        val ex = collectExpectingError(p)
        assertEquals(ProviderErrorKind.InvalidApiKey, ex.kind)
        p.close()
    }

    @Test
    fun networkFailureIsMapped() = runBlocking {
        val stub = StubInterceptor { _ -> throw IOException("dns") }
        val p = OpenAiChatAnswerProvider({ "k" }, null, stubClient(stub))
        val ex = collectExpectingError(p)
        assertEquals(ProviderErrorKind.Network, ex.kind)
        p.close()
    }

    @Test
    fun reasoningModelsGetReasoningEffortAndNoTemperature() {
        val p = OpenAiChatAnswerProvider({ "k" }, ChatProviderOptions().apply { model = "gpt-5-mini" })
        val body = Json.parseToJsonElement(p.buildBody(msgs, 100)).jsonObject
        assertNull(body["temperature"])
        assertEquals("minimal", body["reasoning_effort"]!!.jsonPrimitive.content)
        p.close()
        val p2 = OpenAiChatAnswerProvider({ "k" }, ChatProviderOptions().apply { model = "gpt-4.1-mini" })
        val b2 = Json.parseToJsonElement(p2.buildBody(msgs, 100)).jsonObject
        assertNull(b2["reasoning_effort"])
        assertTrue(b2["temperature"] != null)
        p2.close()
    }

    @Test
    fun unsupportedReasoningEffortIsRetriedWithoutIt() = runBlocking {
        var calls = 0
        // The retry decision depends on the request body, so use a chain-aware interceptor.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val bodyText = buffer.readUtf8()
            if (bodyText.contains("reasoning_effort")) {
                response(request, 400, "{\"error\":{\"message\":\"Unsupported value for reasoning_effort\"}}".toResponseBody("application/json".toMediaType()))
            } else {
                response(request, 200, sse("ok\n").toResponseBody("text/event-stream".toMediaType()))
            }
        }.build()
        val p = OpenAiChatAnswerProvider({ "k" }, null, client)
        val parts = mutableListOf<String>()
        p.streamAsync(msgs, 10).collect { d -> parts.add(d) }
        assertEquals(2, calls)
        assertEquals("ok\n", parts.joinToString(""))
        p.close()
    }

    @Test
    fun unknownModelFallsBackAutomatically() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val bodyText = buffer.readUtf8()
            if (bodyText.contains("gpt-5.4-mini")) {
                response(request, 404, "{\"error\":{\"message\":\"The model gpt-5.4-mini does not exist\"}}".toResponseBody("application/json".toMediaType()))
            } else {
                response(request, 200, sse("hi").toResponseBody("text/event-stream".toMediaType()))
            }
        }.build()
        val p = OpenAiChatAnswerProvider({ "k" }, null, client)
        p.streamAsync(msgs, 10).collect { }
        assertEquals("gpt-4.1-mini", p.activeModel)
        p.close()
    }

    @Test
    fun autoEffortPerFamily() {
        val cases = listOf(
            "gpt-5.4-mini" to "none",
            "gpt-5-mini" to "minimal",
            "o4-mini" to "low",
        )
        for ((model, effort) in cases) assertEquals(effort, OpenAiChatAnswerProvider.autoEffort(model))
    }

    @Test
    fun transcriberFallsBackToNextModelWhenRejected() {
        val t = OpenAiRealtimeTranscriber(apiKeyProvider = { "k" })
        assertEquals("gpt-4o-transcribe", t.activeModel)
        t.handleEvent(
            "{\"type\":\"error\",\"error\":{\"code\":\"model_not_found\",\"message\":\"unknown model\"}}".toByteArray(Charsets.UTF_8),
            Instant.now(),
        )
        assertEquals("gpt-4o-mini-transcribe", t.activeModel)
        assertTrue(t.buildSessionUpdate().contains("gpt-4o-mini-transcribe"))
    }

    @Test
    fun realtimeSessionUpdateMatchesDocumentedShape() {
        val t = OpenAiRealtimeTranscriber(
            apiKeyProvider = { "k" },
            options = RealtimeTranscriberOptions().apply { prompt = Fixtures.testKnowledge().context.buildTranscriptionPrompt() },
        )
        val ga = Json.parseToJsonElement(t.buildSessionUpdate()).jsonObject
        assertFalse(OpenAiRealtimeTranscriber(apiKeyProvider = { "k" }).buildSessionUpdate().contains("Teroxx")) // generic default
        val session = ga["session"]!!.jsonObject
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertNull(input["transcription"]!!.jsonObject["language"]) // auto-detect by default
        assertEquals("session.update", ga["type"]!!.jsonPrimitive.content)
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.int)
        assertEquals("server_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(input["transcription"]!!.jsonObject["prompt"]!!.jsonPrimitive.content.contains("Teroxx"))

        val beta = Json.parseToJsonElement(
            OpenAiRealtimeTranscriber(apiKeyProvider = { "k" }, options = RealtimeTranscriberOptions().apply { protocol = "beta" }).buildSessionUpdate(),
        ).jsonObject
        assertEquals("transcription_session.update", beta["type"]!!.jsonPrimitive.content)
        assertEquals("pcm16", beta["session"]!!.jsonObject["input_audio_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun realtimeEventsAreDispatched() {
        val t = OpenAiRealtimeTranscriber(apiKeyProvider = { "k" })
        val log = mutableListOf<String>()
        t.speechStartedListeners.add { log.add("start") }
        t.speechStoppedListeners.add { log.add("stop") }
        t.partialTranscriptListeners.add { id, d -> log.add("delta:$id:$d") }
        t.segmentCompletedListeners.add { id, s -> log.add("done:$id:$s") }
        fun send(j: String) = t.handleEvent(j.toByteArray(Charsets.UTF_8), Instant.now())
        send("{\"type\":\"input_audio_buffer.speech_started\"}")
        send("{\"type\":\"input_audio_buffer.speech_stopped\"}")
        send("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"i1\",\"delta\":\"How\"}")
        send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"i1\",\"transcript\":\"How would you?\"}")
        send("not json")
        assertEquals(listOf("start", "stop", "delta:i1:How", "done:i1:How would you?"), log)
    }
}

class ServerConfiguredTranscriptionTests {

    @Test
    fun serverConfiguredSessionNeverFallsBackClientSideOrSwitchesProtocol() {
        val t = OpenAiRealtimeTranscriber(
            credentialProvider = { "ek_x" },
            options = RealtimeTranscriberOptions().apply {
                serverConfiguredSession = true
                protocol = "beta"
            },
        )
        t.handleEvent(
            "{\"type\":\"error\",\"error\":{\"code\":\"model_not_found\",\"message\":\"unknown model\"}}".toByteArray(Charsets.UTF_8),
            Instant.now(),
        )
        assertEquals("gpt-4o-transcribe", t.activeModel) // the server's routing decides models
        assertFalse(t.buildSessionUpdate().contains("transcription_session.update")) // never the beta protocol
    }

    @Test
    fun credentialIsFetchedPerConnectionAndMissingLeaseStopsCleanly() = runBlocking {
        var calls = 0
        val t = OpenAiRealtimeTranscriber(
            credentialProvider = { calls++; null },
            options = RealtimeTranscriberOptions().apply { serverConfiguredSession = true },
        )
        val statuses = mutableListOf<String>()
        t.statusChangedListeners.add { s, d -> synchronized(statuses) { statuses.add("$s:$d") } }
        t.startAsync()
        var i = 0
        while (i < 50 && synchronized(statuses) { statuses.none { s -> s.startsWith("Failed") } }) {
            kotlinx.coroutines.delay(20)
            i++
        }
        assertEquals(1, calls)
        assertTrue(synchronized(statuses) { statuses.any { s -> s.contains("Live session not active") } })
        t.disposeAsync()
    }
}
