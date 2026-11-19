package com.interviewassistant.app.services

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The AnswerServiceProbe mirror (P4-C1) against a fake provider transport (the provider's injected
 * OkHttpClient seam): first delta = Ok + latency, an empty stream = `set_key_no_reply`, a provider
 * failure = the ProviderException's message verbatim. (The 15 s timeout arm needs a 15 s stall and
 * is not run here; it is the same withTimeout the C# implements with its 15 s token source.)
 */
class AnswerServiceProbeTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private class FakeTransport(private val code: Int, private val body: String) : Interceptor {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            bodies += request.body?.let {
                val buffer = Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }
    }

    private fun client(transport: FakeTransport): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(transport).build()

    @Test
    fun probe_FirstDelta_Ok_WithTheExactPromptAndDefaultModel() {
        val transport = FakeTransport(
            200,
            "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\ndata: [DONE]\n\n",
        )
        val r = runBlocking { AnswerServiceProbe.runAsync({ "sk-test" }, "", client(transport)) }
        assertTrue(r.ok)
        assertTrue(r.firstTokenMs >= 0)
        assertEquals(null, r.error)
        val sent = transport.bodies.single()
        assertTrue(sent.contains("Reply with the single word: ready"))
        assertTrue(sent.contains("\"model\":\"gpt-5.4-mini\"")) // blank model → the C#'s default
    }

    @Test
    fun probe_ExplicitModel_IsUsed() {
        val transport = FakeTransport(
            200,
            "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\ndata: [DONE]\n\n",
        )
        val r = runBlocking { AnswerServiceProbe.runAsync({ "sk-test" }, "gpt-4.1-mini", client(transport)) }
        assertTrue(r.ok)
        assertTrue(transport.bodies.single().contains("\"model\":\"gpt-4.1-mini\""))
    }

    @Test
    fun probe_EmptyStream_NoReply() {
        val transport = FakeTransport(200, "data: [DONE]\n\n")
        val r = runBlocking { AnswerServiceProbe.runAsync({ "sk-test" }, "", client(transport)) }
        assertFalse(r.ok)
        assertEquals(-1L, r.firstTokenMs)
        assertEquals(Loc.t("set_key_no_reply"), r.error)
    }

    @Test
    fun probe_ProviderRejection_ReturnsTheProviderMessage() {
        val transport = FakeTransport(401, """{"error":{"message":"Incorrect API key provided"}}""")
        val r = runBlocking { AnswerServiceProbe.runAsync({ "sk-bad" }, "", client(transport)) }
        assertFalse(r.ok)
        assertEquals(-1L, r.firstTokenMs)
        assertEquals("API key rejected: Incorrect API key provided", r.error)
    }
}
