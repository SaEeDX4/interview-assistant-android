package com.interviewassistant.client

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.IDataProtector
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of `InterviewAssistant.Tests/CloudAuthTests.cs` (TRANSFER-MAP §5.2: same test names, same
 * inputs, same expected values). Adaptations (P2-PORT-NOTES.md): the C# `HttpMessageHandler` stub is
 * an OkHttp interceptor (the same seam); the C# `LoopbackRedirect` is a test implementation of the D5
 * [OAuthRedirectReceiver] over a real loopback socket with the C# listener's exact semantics; xUnit
 * theories run as case loops inside one test; `App.UiState.PasswordResetCopy` is asserted through its
 * source string, `Loc.t("acct_reset_requested")`.
 */
/** Minimal GET for the loopback tests (java.net.http does not exist on the Android classpath). */
private fun httpGet(url: String): Pair<Int, String?> {
    val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
    conn.instanceFollowRedirects = false
    return try {
        conn.responseCode to conn.getHeaderField("Cache-Control")
    } finally {
        conn.disconnect()
    }
}

class CloudAuthTests {

    // ---- test doubles (the C# file's nested Stub / MemoryStore / ManualTime / XorProtector) ----

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

    private class ManualTime(var now: OffsetDateTime) : TimeProvider {
        override fun utcNow(): OffsetDateTime = now
    }

    private class XorProtector : IDataProtector {
        override fun protect(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unprotect(cipher: ByteArray): ByteArray {
            val r = protect(cipher)
            Json.parseToJsonElement(r.toString(Charsets.UTF_8)) // throws on garbage, like the C#'s JsonDocument.Parse
            return r
        }
    }

    /**
     * The C#'s `LoopbackRedirect` as test infrastructure: a one-shot loopback HTTP receiver with the
     * same accept rules (16 KB cap, 10 s read bound, only GET /callback with the expected state,
     * error parameter aborts, everything else 404/400 and the wait continues), built on the shared
     * [RedirectUriParser] the Android receiver uses.
     */
    private class TestLoopbackReceiver(override val state: String = Pkce.createState()) : OAuthRedirectReceiver {
        private val server = ServerSocket()

        /** Terminal outcome recorded by the accept thread; null = closed before any outcome. */
        private val signal = CompletableDeferred<RedirectOutcome?>()

        @Volatile
        private var closedFlag = false

        init {
            // Bound in the constructor, like the C#'s TcpListener.Start: the redirect URI and port
            // are known before the wait. The C#'s accepts are async I/O that keep progressing while
            // the test's own thread blocks on synchronous socket calls; a daemon accept thread is
            // the JVM equivalent (an accept loop inside waitForCode starved on the runBlocking
            // event loop and hung CI run #78 in loopbackAcceptsOnlyMatchingStateOnCallbackPath).
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
            Thread({ acceptLoop() }, "test-loopback").apply { isDaemon = true }.start()
        }

        val port: Int get() = server.localPort

        override val redirectUri: String
            get() = "http://127.0.0.1:$port/callback?s=${UriEscape.escapeDataString(state)}"

        /**
         * Waits for the accept thread's terminal outcome. The outcome travels as a VALUE and the
         * [AuthException] is created and thrown here, in the waiter — never stored in a deferred
         * (CI run #75: an AuthException stored via `completeExceptionally` surfaced as the failure
         * of loopbackRejectsMalformedAndOversizedRequestsAndKeepsWaiting despite the test's catch
         * around `await()`; the value-carrying code path in the same run passed).
         */
        override suspend fun waitForCode(timeout: Duration): String {
            val outcome = try {
                withTimeout(timeout) { signal.await() }
            } catch (e: TimeoutCancellationException) {
                throw TimeoutException("Sign-in was not completed in the browser.")
            }
            return when (outcome) {
                is RedirectOutcome.Code -> outcome.code
                is RedirectOutcome.Error -> throw AuthException("browser_error", outcome.error)
                else -> throw TimeoutException("Sign-in was cancelled.") // closed first: the C#'s ObjectDisposedException path
            }
        }

        override fun close() {
            closedFlag = true
            signal.complete(null)
            try {
                server.close()
            } catch (_: IOException) {
            }
        }

        private fun acceptLoop() {
            while (!closedFlag) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    return // listener closed or broken: the wait resolves via close()/timeout
                }
                try {
                    val outcome = handle(socket)
                    if (outcome != null) {
                        signal.complete(outcome)
                        return // one-shot, like the C#: a terminal outcome ends the wait
                    }
                } catch (e: Exception) {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }

        /** Handles one connection; returns the terminal outcome, or null while the wait continues. */
        private fun handle(socket: Socket): RedirectOutcome? {
            socket.use {
                val target = readTarget(it)
                if (readFailed) return null // the C# returns without a response when the read itself fails
                if (target == null) {
                    respond(it, 400, Loc.t("browser_bad_request"))
                    return null
                }
                return when (val outcome = RedirectUriParser.evaluate("http://127.0.0.1$target", state)) {
                    RedirectOutcome.NotOurs -> {
                        respond(it, 404, "Not found.")
                        null
                    }
                    RedirectOutcome.MissingCode -> {
                        respond(it, 400, Loc.t("browser_missing_code"))
                        null
                    }
                    is RedirectOutcome.Error -> {
                        respond(it, 400, Loc.t("browser_sign_in_failed"))
                        outcome
                    }
                    is RedirectOutcome.Code -> {
                        respond(it, 200, Loc.t("browser_signed_in"))
                        outcome
                    }
                }
            }
        }

        @Volatile
        private var readFailed = false

        private fun readTarget(socket: Socket): String? {
            readFailed = false
            val head = try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val buf = ByteArray(16 * 1024)
                var len = 0
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                    if (String(buf, 0, len, Charsets.US_ASCII).contains("\r\n\r\n")) break
                }
                String(buf, 0, len, Charsets.US_ASCII)
            } catch (e: Exception) {
                readFailed = true
                return null
            }
            val first = head.split("\r\n", limit = 2)[0].split(' ')
            return if (first.size == 3 && first[0] == "GET" && first[2].startsWith("HTTP/1.")) first[1] else null
        }

        private fun respond(socket: Socket, status: Int, message: String) {
            val html = "<!doctype html><meta charset=utf-8><title>Interview Assistant</title><body style=\"font-family:system-ui;padding:3em\"><p>" +
                message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>"
            val body = html.toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                else -> "Not Found"
            }
            val headers = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
            try {
                val out = socket.getOutputStream()
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
                socket.shutdownOutput()
            } catch (e: IOException) {
                // the peer went away — the C# swallows the same failures
            }
        }
    }

    companion object {
        private val opt = CloudOptions().apply {
            apiBaseUrl = "https://api.example.test"
            supabaseUrl = "https://proj.supabase.test"
            supabaseAnonKey = "anon-public"
            oAuthProviders = listOf("google", "azure")
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

        private fun tokens(request: Request, access: String, refresh: String, expiresIn: Int = 3600): Response =
            jsonResponse(
                request, 200,
                """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"user":{"id":"u1","email":"a@b.test"}}""",
            )

        private fun err(request: Request, code: Int, errorCode: String, msg: String): Response =
            jsonResponse(request, code, """{"code":$code,"error_code":"$errorCode","msg":"$msg"}""")
    }

    // ---- the ported tests ----

    @Test
    fun pkceMatchesRfc7636() {
        // RFC 7636 Appendix B test vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.createVerifier()
        assertTrue(Pkce.isValidVerifier(v))
        assertNotEquals(v, Pkce.createVerifier())
        assertNotEquals(Pkce.createState(), Pkce.createState())
    }

    @Test
    fun authorizeUrlUsesS256AndOnlyEnabledProviders() {
        val auth = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, "{}") }), opt, MemoryStore())
        val url = auth.buildAuthorizeUrl("google", "CHAL", "http://127.0.0.1:5555/callback?s=x")
        assertTrue(url.startsWith("https://proj.supabase.test/auth/v1/authorize?provider=google"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=s256"))
        assertTrue(url.contains("redirect_to=http%3A%2F%2F127.0.0.1%3A5555%2Fcallback%3Fs%3Dx"))
        try {
            auth.buildAuthorizeUrl("evil", "c", "r")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("provider_not_enabled", e.code)
        }
    }

    @Test
    fun codeExchangeStoresTokensAndRefreshRotatesThem() = runBlocking {
        val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
        val fake = FakeTransport { req, _ ->
            if (req.url.query!!.contains("grant_type=pkce")) tokens(req, "at1", "rt1") else tokens(req, "at2", "rt2")
        }
        val store = MemoryStore()
        val auth = SupabaseAuth(http(fake), opt, store, time)
        auth.exchangeCodeAsync("CODE", "VERIFIER")
        val (req, body) = fake.calls[0]
        assertEquals("anon-public", req.header("apikey"))
        assertTrue(body.contains("\"auth_code\":\"CODE\""))
        assertTrue(body.contains("\"code_verifier\":\"VERIFIER\""))
        assertEquals("rt1", store.saved!!.refreshToken)
        assertEquals("at1", auth.getAccessTokenAsync()) // still fresh → no network
        assertEquals(1, fake.calls.size)
        time.now = time.now.plusSeconds((59.5 * 60).toLong()) // within 60 s of expiry → refresh
        assertEquals("at2", auth.getAccessTokenAsync())
        assertTrue(fake.calls[1].second.contains("\"refresh_token\":\"rt1\""))
        assertEquals("rt2", store.saved!!.refreshToken)
    }

    @Test
    fun revokedRefreshTokenSignsOutLocally() = runBlocking {
        val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
        val store = MemoryStore().apply { saved = AuthSession("old", "revoked", time.now.minusSeconds(1), "u", "e") }
        val fake = FakeTransport { req, _ -> err(req, 400, "refresh_token_not_found", "Invalid Refresh Token") }
        val auth = SupabaseAuth(http(fake), opt, store, time)
        var changed: AuthSession? = AuthSession("x", "x", OffsetDateTime.now(), "", "")
        auth.addSessionChangedListener { s -> changed = s }
        assertNull(auth.getAccessTokenAsync())
        assertFalse(auth.isSignedIn)
        assertNull(store.saved)
        assertNull(changed)
    }

    @Test
    fun loopbackAcceptsOnlyMatchingStateOnCallbackPath() = runBlocking {
        val loop = TestLoopbackReceiver()
        try {
            assertTrue(loop.redirectUri.startsWith("http://127.0.0.1:"))
            val wait = async { loop.waitForCode(20.seconds) }
            val (wrongCode, _) = httpGet("http://127.0.0.1:${loop.port}/callback?s=forged&code=EVIL")
            assertEquals(404, wrongCode)
            assertFalse(wait.isCompleted)
            val (okCode, okCacheControl) = httpGet(loop.redirectUri + "&code=GOOD")
            assertEquals(200, okCode)
            assertEquals("no-store", okCacheControl)
            assertEquals("GOOD", wait.await())
        } finally {
            loop.close()
        }
    }

    @Test
    fun loopbackRejectsMalformedAndOversizedRequestsAndKeepsWaiting() = runBlocking {
        val loop = TestLoopbackReceiver()
        try {
            val wait = async { loop.waitForCode(20.seconds) }
            Socket().use { tcp ->
                tcp.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), loop.port))
                val s = tcp.getOutputStream()
                s.write("POST /callback HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.US_ASCII))
                s.flush()
                val resp = tcp.getInputStream().bufferedReader().readText()
                assertTrue(resp.startsWith("HTTP/1.1 400"))
            }
            Socket().use { tcp ->
                tcp.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), loop.port))
                tcp.getOutputStream().write(ByteArray(20_000)) // junk, no request line
                tcp.getOutputStream().flush()
            }
            assertFalse(wait.isCompleted)
            val (errCode, _) = httpGet(loop.redirectUri + "&error=access_denied&error_description=User%20cancelled")
            assertEquals(400, errCode)
            try {
                wait.await()
                fail("expected AuthException")
            } catch (e: AuthException) {
                assertEquals("User cancelled", e.message)
            }
        } finally {
            loop.close()
        }
    }

    @Test
    fun loopbackTimesOutCleanly() = runBlocking {
        val loop = TestLoopbackReceiver()
        try {
            loop.waitForCode(200.milliseconds)
            fail("expected TimeoutException")
        } catch (e: TimeoutException) {
            // expected
        } finally {
            loop.close()
        }
    }

    @Test
    fun interactiveMagicLinkFlowEndToEnd() = runBlocking {
        var redirect: String? = null
        var otpBody: String? = null
        val fake = FakeTransport { req, body ->
            if (req.url.encodedPath.endsWith("/otp")) {
                redirect = req.url.queryParameter("redirect_to")
                otpBody = body
                jsonResponse(req, 200, "{}")
            } else {
                tokens(req, "at", "rt")
            }
        }
        val auth = SupabaseAuth(http(fake), opt, MemoryStore())
        val loop = TestLoopbackReceiver()
        try {
            val signIn = async { auth.signInInteractiveAsync({ }, null, "a@b.test", 20.seconds, loop) }
            var i = 0
            while (i < 100 && redirect == null) {
                delay(20)
                i++
            }
            assertTrue(otpBody!!.contains("\"code_challenge_method\":\"s256\""))
            val (respCode, _) = httpGet(redirect!! + "&code=MAGIC")
            assertEquals(200, respCode) // the user clicking the e-mail link
            val session = signIn.await()
            assertEquals("at", session.accessToken)
            assertTrue(fake.calls.last().second.contains("\"auth_code\":\"MAGIC\""))
        } finally {
            loop.close()
        }
    }

    @Test
    fun tokenStoreEncryptsAndSurvivesCorruption() {
        val dir = Files.createTempDirectory("ia-tok-").toFile()
        val path = File(dir, "session.bin").absolutePath
        val store = ProtectedFileTokenStore(path, XorProtector())
        store.save(AuthSession("ACCESS-SECRET", "REFRESH-SECRET", OffsetDateTime.now(), "u", "e"))
        assertFalse(File(path).readBytes().toString(Charsets.UTF_8).contains("REFRESH-SECRET"))
        assertEquals("REFRESH-SECRET", store.load()!!.refreshToken)
        File(path).writeText("garbage")
        assertNull(store.load())
        store.clear()
        assertFalse(File(path).exists())
        dir.deleteRecursively()
    }

    @Test
    fun cloudOptionsRequireHttpsAndAnonKey() {
        assertTrue(opt.isConfigured)
        assertFalse(
            CloudOptions().apply {
                apiBaseUrl = "http://api.example.test"; supabaseUrl = opt.supabaseUrl; supabaseAnonKey = "k"
            }.isConfigured,
        )
        assertTrue(
            CloudOptions().apply {
                apiBaseUrl = "http://localhost:5149"; supabaseUrl = "http://127.0.0.1:54321"; supabaseAnonKey = "k"
            }.isConfigured,
        )
        assertFalse(CloudOptions().isConfigured)
    }

    @Test
    fun installationIdIsRandomAndNotHardwareDerived() {
        val a = File(Files.createTempDirectory("ia-i-").toFile(), "id").absolutePath
        val b = File(Files.createTempDirectory("ia-i-").toFile(), "id").absolutePath
        assertNotEquals(InstallationIdentity.getOrCreate(a), InstallationIdentity.getOrCreate(b))
        val machine = try {
            InetAddress.getLocalHost().hostName.lowercase()
        } catch (e: Exception) {
            null
        }
        if (machine != null) assertFalse(InstallationIdentity.getOrCreate(a).contains(machine))
        assertEquals(InstallationIdentity.getOrCreate(a), InstallationIdentity.getOrCreate(a))
    }

    @Test
    fun signUpReportsConfirmationRequiredWithoutStoringTokens() = runBlocking {
        val o = CloudOptions().apply {
            apiBaseUrl = opt.apiBaseUrl; supabaseUrl = opt.supabaseUrl; supabaseAnonKey = "anon"
            emailConfirmedRedirectUrl = "https://api.example.test/auth/confirmed"
        }
        val fake = FakeTransport { req, _ -> jsonResponse(req, 200, """{"id":"u1","email":"a@b.test","confirmation_sent_at":"2026-10-13T00:00:00Z"}""") }
        val store = MemoryStore()
        val auth = SupabaseAuth(http(fake), o, store)
        val r = auth.signUpAsync(" a@b.test ", "Secret123!")
        assertTrue(r.confirmationRequired)
        assertFalse(r.signedIn)
        assertNull(store.saved)
        val (req, body) = fake.calls.single()
        assertEquals("/auth/v1/signup", req.url.encodedPath)
        assertEquals("https://api.example.test/auth/confirmed", req.url.queryParameter("redirect_to"))
        assertTrue(body.contains("\"email\":\"a@b.test\"")) // trimmed
    }

    @Test
    fun signUpWithoutConfirmationSignsInImmediately() = runBlocking {
        val auth = SupabaseAuth(http(FakeTransport { req, _ -> tokens(req, "at", "rt") }), opt, MemoryStore())
        val r = auth.signUpAsync("a@b.test", "Secret123!")
        assertTrue(r.signedIn)
        assertTrue(auth.isSignedIn)
    }

    @Test
    fun authErrorsBecomePlainLanguage() = runBlocking {
        // xUnit [Theory] with 5 InlineData cases, run as a case loop (same inputs, same assertions).
        val cases = listOf(
            "invalid_credentials" to "not correct",
            "email_not_confirmed" to "confirm your e-mail",
            "user_already_exists" to "already exists",
            "weak_password" to "stronger password",
            "over_request_rate_limit" to "wait a minute",
        )
        for ((code, expected) in cases) {
            val auth = SupabaseAuth(http(FakeTransport { req, _ -> err(req, 400, code, "raw provider text") }), opt, MemoryStore())
            try {
                auth.signInWithPasswordAsync("a@b.test", "x")
                fail("expected AuthException for $code")
            } catch (e: AuthException) {
                assertEquals(code, e.code)
                assertTrue("case $code: '${e.message}' should contain '$expected'", e.message!!.contains(expected, ignoreCase = true))
            }
        }
    }

    @Test
    fun signInWithoutServiceConfigIsAReadableError() = runBlocking {
        val auth = SupabaseAuth(
            http(FakeTransport { req, _ -> tokens(req, "a", "b") }),
            CloudOptions().apply { apiBaseUrl = opt.apiBaseUrl },
            MemoryStore(),
        )
        try {
            auth.signInWithPasswordAsync("a@b.test", "x")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("auth_unavailable", e.code)
        }
    }

    @Test
    fun networkFailureIsAReadableError() = runBlocking {
        val auth = SupabaseAuth(http(FakeTransport { _, _ -> throw IOException("dns") }), opt, MemoryStore())
        try {
            auth.signInWithPasswordAsync("a@b.test", "x")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("network", e.code)
            assertTrue(e.message!!.contains("internet"))
        }
    }

    @Test
    fun passwordResetRunsThroughLoopbackAndUpdatesPassword() = runBlocking {
        var redirect: String? = null
        var recoverBody: String? = null
        var putAuth: String? = null
        var putBody: String? = null
        val fake = FakeTransport { req, body ->
            when {
                req.url.encodedPath.endsWith("/recover") -> {
                    redirect = req.url.queryParameter("redirect_to")
                    recoverBody = body
                    jsonResponse(req, 200, "{}")
                }
                req.method == "PUT" && req.url.encodedPath.endsWith("/user") -> {
                    putAuth = req.header("Authorization")
                    putBody = body
                    jsonResponse(req, 200, "{}")
                }
                else -> tokens(req, "at", "rt")
            }
        }
        val auth = SupabaseAuth(http(fake), opt, MemoryStore())
        val loop = TestLoopbackReceiver()
        try {
            val recover = async { auth.recoverPasswordAsync("a@b.test", 20.seconds, loop) }
            var i = 0
            while (i < 100 && redirect == null) {
                delay(20)
                i++
            }
            assertTrue(recoverBody!!.contains("code_challenge"))
            val (respCode, _) = httpGet(redirect!! + "&code=RESET")
            assertEquals(200, respCode)
            recover.await()
            auth.updatePasswordAsync("NewSecret123")
            assertEquals("Bearer at", putAuth)
            assertTrue(putBody!!.contains("NewSecret123"))
            assertTrue(fake.calls.any { it.second.contains("\"auth_code\":\"RESET\"") })
        } finally {
            loop.close()
        }
    }

    /**
     * Enumeration safety (rc.3 bug: an unknown address showed "We sent a reset link to …"): an existing and an unknown
     * address produce the same displayed notice and the same outcome; the notice never names the address or claims
     * delivery. Rate limits are still reported. xUnit [Theory] with 3 InlineData cases, run as a case loop.
     */
    @Test
    fun passwordResetShowsTheSameNeutralNoticeForKnownAndUnknownAddresses() = runBlocking {
        val cases = listOf<Pair<Int, String?>>(
            404 to "user_not_found",
            400 to "email_not_found",
            404 to null,
        )
        val before = Loc.language
        Loc.language = "en"
        try {
            for ((unknownStatus, unknownCode) in cases) {
                suspend fun reset(email: String): Pair<String?, String> {
                    val fake = FakeTransport { req, body ->
                        if (body.contains("known@example.test") && !body.contains("unknown@")) {
                            jsonResponse(req, 200, "{}")
                        } else if (unknownCode != null) {
                            err(req, unknownStatus, unknownCode, "User not found")
                        } else {
                            jsonResponse(req, unknownStatus, "{}")
                        }
                    }
                    val auth = SupabaseAuth(http(fake), opt, MemoryStore())
                    var shown: String? = null
                    val loop = TestLoopbackReceiver()
                    try {
                        // What the Account page shows when the request is accepted.
                        auth.recoverPasswordAsync(email, 300.milliseconds, loop) { shown = Loc.t("acct_reset_requested") }
                    } catch (e: Exception) {
                        return shown to e.javaClass.simpleName
                    } finally {
                        loop.close()
                    }
                    return shown to "completed"
                }
                val known = reset("known@example.test")
                val unknown = reset("unknown@example.test")
                assertEquals("If an account exists for this e-mail, a password reset link has been sent.", known.first)
                assertEquals(known, unknown) // same text, same outcome (no reset link opened within the wait)
                assertFalse(known.first!!.contains("example.test"))
            }
        } finally {
            Loc.language = before
        }
    }

    /** rc.5: the forgot-password state shows the same neutral notice for known and unknown addresses in every UI language. */
    @Test
    fun passwordResetNeutralNoticeIsIdenticalForKnownAndUnknownInEveryLanguage() = runBlocking {
        // The language is read from the table directly: switching the global UI language would race with parallel tests.
        for (lang in listOf("en", "es", "fr", "de", "pt", "it", "ar", "fa", "zh", "hi")) {
            suspend fun reset(email: String): Pair<String?, String> {
                val fake = FakeTransport { req, body ->
                    if (body.contains("known@example.test") && !body.contains("unknown@")) {
                        jsonResponse(req, 200, "{}")
                    } else {
                        err(req, 404, "user_not_found", "User not found")
                    }
                }
                val auth = SupabaseAuth(http(fake), opt, MemoryStore())
                var shown: String? = null
                val loop = TestLoopbackReceiver()
                try {
                    auth.recoverPasswordAsync(email, 200.milliseconds, loop) { shown = Loc.tableFor(lang)["acct_reset_requested"] }
                } catch (e: Exception) {
                    return shown to e.javaClass.simpleName
                } finally {
                    loop.close()
                }
                return shown to "completed"
            }
            val known = reset("known@example.test")
            val unknown = reset("unknown@example.test")
            assertEquals(Loc.tableFor(lang)["acct_reset_requested"], known.first)
            assertEquals(known, unknown)
            assertFalse(known.first!!.contains("example.test"))
            assertFalse(known.first!!.contains("{0}"))
            if (lang != "en") assertNotEquals(Loc.tableFor("en")["acct_reset_requested"], known.first)
        }
    }

    @Test
    fun passwordResetStillReportsTheGeneralRateLimit() = runBlocking {
        val auth = SupabaseAuth(http(FakeTransport { req, _ -> err(req, 429, "over_request_rate_limit", "x") }), opt, MemoryStore())
        var accepted = false
        val loop = TestLoopbackReceiver()
        try {
            auth.recoverPasswordAsync("a@b.test", 1.seconds, loop) { accepted = true }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("over_request_rate_limit", e.code)
            assertFalse(accepted)
        } finally {
            loop.close()
        }
    }

    @Test
    fun perAddressEmailRateLimitIsTreatedAsAcceptedBecauseOnlyExistingAccountsHitIt() {
        // Supabase limits reset e-mails per address only for addresses that have an account: surfacing it would tell a
        // second, quick request whether the account exists. The first link of that minute was sent.
        assertTrue(SupabaseAuth.revealsAccountExistence("over_email_send_rate_limit"))
        assertFalse(SupabaseAuth.revealsAccountExistence("over_request_rate_limit"))
        assertFalse(SupabaseAuth.revealsAccountExistence("validation_failed"))
    }

    @Test
    fun expiredRefreshMarksSessionExpired() = runBlocking {
        val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
        val store = MemoryStore().apply { saved = AuthSession("old", "gone", time.now.minusSeconds(1), "u", "e") }
        val auth = SupabaseAuth(http(FakeTransport { req, _ -> err(req, 400, "refresh_token_not_found", "x") }), opt, store, time)
        assertNull(auth.getAccessTokenAsync())
        assertEquals("expired", auth.signedOutReason)
        auth.signOutAsync()
        assertEquals("user", auth.signedOutReason)
    }
}
