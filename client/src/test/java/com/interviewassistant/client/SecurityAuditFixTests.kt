package com.interviewassistant.client

import com.interviewassistant.core.languages.Loc
import java.io.File
import java.time.OffsetDateTime
import java.util.Collections
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the behavioural half of `InterviewAssistant.Tests/SecurityAuditFixTests.cs` (P7,
 * TEST-MAP closure): the password-reset wait never swaps the signed-in account (H1) and older
 * auth servers' per-address limit is recognised (L7). The C# file's remaining methods are
 * either ported with the UiState they test (`PendingReset`, `AuthFormState` — see
 * `app/uistate/SecurityAuditFixTests.kt`) or WPF source scans recorded as (c) in
 * parity/TEST-MAP.md.
 *
 * Adaptations, same as CloudAuthTests: the C# `HttpMessageHandler` stub is an OkHttp
 * interceptor; the C# loopback redirect (opened by an HTTP GET in the C# helper) is a fake
 * [OAuthRedirectReceiver] whose wait completes when the test "opens the link" by delivering
 * the code — the same terminal semantics (code or [TimeoutException]).
 */
class SecurityAuditFixTests {

    // ---- test doubles (the C# file's nested Stub / MemoryStore) ----

    private class FakeTransport(private val respond: (Request, String) -> Response) : Interceptor {
        val calls: MutableList<Pair<Request, String>> =
            Collections.synchronizedList(mutableListOf<Pair<Request, String>>())

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

    private class MemoryStore : ITokenStore {
        var saved: AuthSession? = null
        override fun load(): AuthSession? = saved
        override fun save(session: AuthSession) {
            saved = session
        }

        override fun clear() {
            saved = null
        }
    }

    /** A redirect receiver the test completes by "opening the link" with a code. */
    private class FakeRedirectReceiver : OAuthRedirectReceiver {
        override val state: String = "test-state"
        override val redirectUri: String = "https://app.example.test/callback?s=$state"
        private val code = CompletableDeferred<String>()

        fun deliver(c: String) {
            code.complete(c)
        }

        override suspend fun waitForCode(timeout: Duration): String =
            withTimeoutOrNull(timeout) { code.await() } ?: throw TimeoutException()

        override fun close() {
            code.cancel()
        }
    }

    companion object {
        private val opt = CloudOptions().apply {
            apiBaseUrl = "https://api.example.test"
            supabaseUrl = "https://proj.supabase.test"
            supabaseAnonKey = "anon-public"
            oAuthProviders = listOf("google")
        }

        @JvmStatic
        @BeforeClass
        fun installLoc() {
            // The real, byte-identical i18n assets from :core — the same tables the app loads.
            Loc.tableLoader = { code ->
                val candidates = listOf(
                    File("../core/src/main/assets/i18n/$code.json"),
                    File("core/src/main/assets/i18n/$code.json"),
                    File("../../core/src/main/assets/i18n/$code.json"),
                )
                val file = candidates.firstOrNull { it.isFile }
                    ?: throw IllegalStateException("i18n asset $code.json not found from ${File(".").absolutePath}")
                Loc.parseTable(file.readText(Charsets.UTF_8))
            }
        }

        private fun http(fake: FakeTransport): OkHttpClient = OkHttpClient.Builder().addInterceptor(fake).build()

        private fun jsonResponse(request: Request, code: Int, body: String): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()

        private fun tokens(request: Request, access: String, userId: String, email: String): Response =
            jsonResponse(
                request, 200,
                """{"access_token":"$access","refresh_token":"rt-$access","expires_in":3600,"user":{"id":"$userId","email":"$email"}}""",
            )
    }

    /**
     * The C# file's `ResetServer`: /recover records the redirect target and answers 200; every
     * other call goes to [token] or answers with user-b's tokens.
     */
    private class ResetServer(private val token: ((Request) -> Response)? = null) {
        @Volatile
        var redirect: String? = null

        val transport = FakeTransport { req, _ ->
            if (req.url.encodedPath.endsWith("/recover")) {
                redirect = req.url.queryParameter("redirect_to")
                jsonResponse(req, 200, "{}")
            } else {
                token?.invoke(req) ?: tokens(req, "at-b", "user-b", "b@x.test")
            }
        }
    }

    /** Starts a reset, opens its link with [code] and returns the outcome (the C#'s `OpenResetLink`). */
    private suspend fun openResetLink(
        auth: SupabaseAuth,
        server: ResetServer,
        receiver: FakeRedirectReceiver,
        email: String,
        code: String = "RESET",
    ): Exception? = kotlinx.coroutines.supervisorScope {
        // supervisorScope: a failed reset child must not cancel the test's runBlocking
        // (DC-9 — the C# observes the faulted Task; await() here is that observation).
        val recover = async { auth.recoverPasswordAsync(email, 20.seconds, receiver) }
        var i = 0
        while (i < 200 && server.redirect == null) {
            delay(20)
            i++
        }
        receiver.deliver(code)
        try {
            recover.await()
            null
        } catch (e: Exception) {
            e
        }
    }

    // ------------------------------------------------------------------ H1: the reset link never swaps the signed-in account

    @Test
    fun resetLink_OpenedWhileAnotherAccountIsSignedIn_IsRefused_AndTheSessionStays() = runBlocking {
        val server = ResetServer()
        val a = AuthSession("at-a", "rt-a", OffsetDateTime.now().plusHours(1), "user-a", "a@x.test")
        val store = MemoryStore().apply { saved = a }
        val auth = SupabaseAuth(http(server.transport), opt, store)

        val ex = openResetLink(auth, server, FakeRedirectReceiver(), "b@x.test")

        assertEquals("signed_in_other_account", (ex as? AuthException)?.code)
        assertEquals(a, auth.session) // A is still signed in
        assertEquals(a, store.saved)
        // The code was never exchanged.
        assertTrue(server.transport.calls.none { it.first.url.query?.contains("grant_type=pkce") == true })
    }

    @Test
    fun resetLink_ForTheSignedInAccount_StillWorks() = runBlocking {
        val server = ResetServer()
        val store = MemoryStore().apply {
            saved = AuthSession("at-old", "rt-old", OffsetDateTime.now().plusHours(1), "user-b", "B@x.test")
        }
        val auth = SupabaseAuth(http(server.transport), opt, store)

        assertNull(openResetLink(auth, server, FakeRedirectReceiver(), "b@x.test"))
        assertEquals("at-b", auth.session!!.accessToken)
        assertEquals("user-b", auth.session!!.userId)
    }

    @Test
    fun resetLink_ExchangedWhileAnotherAccountSignsIn_DoesNotReplaceThatSession() = runBlocking {
        lateinit var auth: SupabaseAuth
        val server = ResetServer { req ->
            if (req.url.query?.contains("grant_type=password") == true) {
                tokens(req, "at-a", "user-a", "a@x.test")
            } else {
                // While the recovery code is being exchanged, A signs in on this PC.
                runBlocking { auth.signInWithPasswordAsync("a@x.test", "pw") }
                tokens(req, "at-b", "user-b", "b@x.test")
            }
        }
        val store = MemoryStore()
        auth = SupabaseAuth(http(server.transport), opt, store)

        val ex = openResetLink(auth, server, FakeRedirectReceiver(), "b@x.test")

        assertEquals("signed_in_other_account", (ex as? AuthException)?.code)
        assertEquals("user-a", auth.session!!.userId)
        assertEquals("user-a", store.saved!!.userId)
    }

    @Test
    fun resetLink_WhenSignedOut_SignsInTheResetAccount() = runBlocking {
        val server = ResetServer()
        val auth = SupabaseAuth(http(server.transport), opt, MemoryStore())

        assertNull(openResetLink(auth, server, FakeRedirectReceiver(), "b@x.test"))
        assertEquals("user-b", auth.session!!.userId)
    }

    // ------------------------------------------------------------------ L7: older auth servers' per-address limit

    @Test
    fun perAddressLimitOfOlderAuthServers_IsRecognised() {
        // The C# [Theory] with 5 InlineData cases, run as a case loop (P2 precedent).
        data class Case(val status: Int, val code: String, val message: String, val expected: Boolean)

        val cases = listOf(
            Case(429, "http_429", "For security purposes, you can only request this after 42 seconds.", true),
            Case(429, "http_429", "for security purposes, you can only request this once every 60 seconds", true),
            // The general limit is still reported.
            Case(429, "over_request_rate_limit", "For security purposes, slow down", false),
            Case(429, "http_429", "Too many requests", false),
            Case(400, "http_400", "For security purposes, you can only request this after 42 seconds.", false),
        )
        for (c in cases) {
            assertEquals(
                "status=${c.status} code=${c.code} message=${c.message}",
                c.expected,
                SupabaseAuth.isPerAddressRateLimit(c.status, c.code, c.message),
            )
        }
    }

    @Test
    fun passwordReset_OlderServersPerAddressLimit_IsTreatedAsAccepted_NotShownRaw() = runBlocking {
        val fake = FakeTransport { req, _ ->
            jsonResponse(
                req, 429,
                """{"code":429,"msg":"For security purposes, you can only request this after 42 seconds."}""",
            )
        }
        val auth = SupabaseAuth(http(fake), opt, MemoryStore())
        var accepted = false
        // Accepted like any request; the receiver then waits for a link (which this test never opens).
        try {
            auth.recoverPasswordAsync("a@b.test", 200.milliseconds, FakeRedirectReceiver()) { accepted = true }
            fail("expected TimeoutException")
        } catch (e: TimeoutException) {
            // expected: the wait for the link timed out, exactly as in the C#
        }
        assertTrue(accepted)
    }
}
