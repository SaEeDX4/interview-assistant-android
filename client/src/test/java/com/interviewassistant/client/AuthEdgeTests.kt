package com.interviewassistant.client

import com.interviewassistant.core.languages.Loc
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * The `Auth.cs` branches the ported CloudAuthTests do not reach (P7 coverage closure): the
 * interactive OAuth (browser) path and its default provider, the magic-link failure, malformed
 * token responses (`bad_response` in its three shapes), the 60-second expiry floor, forced
 * refresh, the not-yet-configured refresh that returns the stale token, non-sign-out refresh
 * failures, the `user_banned` → "disabled" reason, sign-out's logout call and its offline
 * tolerance, password-update failures, the error-body parsing fallbacks of `Fail`, listener
 * removal, and the token store's missing-file / nested-directory behaviour.
 */
class AuthEdgeTests {

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
            oAuthProviders = listOf("google", "azure")
        }

        @JvmStatic
        @BeforeClass
        fun installLoc() {
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

        private suspend fun expectAuthError(block: suspend () -> Unit): AuthException {
            try {
                block()
            } catch (e: AuthException) {
                return e
            }
            fail("expected AuthException")
            throw AssertionError("unreachable")
        }
    }

    // ---- interactive flow: the browser (OAuth) path ----

    @Test
    fun interactiveOAuthFlow_OpensTheAuthorizeUrlForTheChosenProvider_AndExchangesTheCode() {
        runBlocking {
            val fake = FakeTransport { req, _ -> tokens(req, "at-oauth", "rt-oauth") }
            val store = MemoryStore()
            val auth = SupabaseAuth(http(fake), opt, store)
            val receiver = FakeRedirectReceiver()
            var opened: String? = null
            val session = auth.signInInteractiveAsync(
                { url -> opened = url; receiver.deliver("OAUTH-CODE") },
                "azure", null, 5.seconds, receiver,
            )
            assertEquals("at-oauth", session.accessToken)
            val url = opened!!
            assertTrue(url.startsWith("https://proj.supabase.test/auth/v1/authorize?provider=azure"))
            assertTrue(url.contains("code_challenge_method=s256"))
            assertTrue(url.contains("code_challenge="))
            assertTrue(url.contains("redirect_to=https%3A%2F%2Fapp.example.test%2Fcallback%3Fs%3Dtest-state"))
            // The code from the redirect is what gets exchanged.
            assertTrue(fake.calls.single().second.contains("\"auth_code\":\"OAUTH-CODE\""))
            assertEquals("rt-oauth", store.saved!!.refreshToken)
        }
    }

    @Test
    fun interactiveOAuthFlow_WithoutAProvider_UsesTheFirstConfiguredOne() {
        runBlocking {
            val fake = FakeTransport { req, _ -> tokens(req, "at", "rt") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val receiver = FakeRedirectReceiver()
            var opened: String? = null
            auth.signInInteractiveAsync({ url -> opened = url; receiver.deliver("C") }, null, null, 5.seconds, receiver)
            assertTrue(opened!!.contains("provider=google"))
        }
    }

    @Test
    fun authorizeUrl_ProviderMatchIsCaseInsensitive_AndParametersAreEscaped() {
        val auth = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, "{}") }), opt, MemoryStore())
        val url = auth.buildAuthorizeUrl("GOOGLE", "ch al", "http://x.test/cb?s=1&y=2")
        assertTrue(url.contains("provider=GOOGLE"))
        assertTrue(url.contains("code_challenge=ch%20al"))
        assertTrue(url.contains("redirect_to=http%3A%2F%2Fx.test%2Fcb%3Fs%3D1%26y%3D2"))
        val noProviders = SupabaseAuth(
            http(FakeTransport { req, _ -> jsonResponse(req, 200, "{}") }),
            CloudOptions().apply { supabaseUrl = opt.supabaseUrl; supabaseAnonKey = "k" },
            MemoryStore(),
        )
        try {
            noProviders.buildAuthorizeUrl("google", "c", "r")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("provider_not_enabled", e.code)
        }
    }

    // ---- magic link + token response shapes ----

    @Test
    fun magicLinkFailure_ThrowsTheParsedError_AndTheRequestCarriesThePkceFields() {
        runBlocking {
            val fake = FakeTransport { req, _ -> err(req, 400, "validation_failed", "raw") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val ex = expectAuthError { auth.sendMagicLinkAsync("a@b.test", "CHAL", "https://app.example.test/callback?s=t") }
            assertEquals("validation_failed", ex.code)
            val (req, body) = fake.calls.single()
            assertEquals("/auth/v1/otp", req.url.encodedPath)
            assertEquals("https://app.example.test/callback?s=t", req.url.queryParameter("redirect_to"))
            assertTrue(body.contains("\"email\":\"a@b.test\""))
            assertTrue(body.contains("\"create_user\":true"))
            assertTrue(body.contains("\"code_challenge\":\"CHAL\""))
            assertTrue(body.contains("\"code_challenge_method\":\"s256\""))
            assertEquals("anon-public", req.header("apikey"))
        }
    }

    @Test
    fun tokenResponses_MalformedBodies_AreBadResponseErrors() {
        runBlocking {
            // The literal JSON null: "Empty token response."
            val nullBody = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, "null") }), opt, MemoryStore())
            val e1 = expectAuthError { nullBody.exchangeCodeAsync("c", "v") }
            assertEquals("bad_response", e1.code)
            assertEquals("Empty token response.", e1.message)

            // Not JSON at all: "Unexpected response from the sign-in service."
            val garbage = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, "this is not json") }), opt, MemoryStore())
            val e2 = expectAuthError { garbage.exchangeCodeAsync("c", "v") }
            assertEquals("bad_response", e2.code)
            assertEquals("Unexpected response from the sign-in service.", e2.message)

            // Tokens missing: "Incomplete token response."
            val empty = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, """{"access_token":"","refresh_token":""}""") }), opt, MemoryStore())
            val e3 = expectAuthError { empty.exchangeCodeAsync("c", "v") }
            assertEquals("bad_response", e3.code)
            assertEquals("Incomplete token response.", e3.message)

            // No user object: the session carries empty id/e-mail, as the C#'s `?? ""` does.
            val noUser = SupabaseAuth(http(FakeTransport { req, _ -> jsonResponse(req, 200, """{"access_token":"a","refresh_token":"r","expires_in":3600}""") }), opt, MemoryStore())
            val s = noUser.exchangeCodeAsync("c", "v")
            assertEquals("", s.userId)
            assertEquals("", s.email)
        }
    }

    @Test
    fun storedExpiry_HasAFloorOfSixtySeconds_AndHonoursLongerOnes() {
        runBlocking {
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val short = SupabaseAuth(http(FakeTransport { req, _ -> tokens(req, "a", "r", expiresIn = 10) }), opt, MemoryStore(), time)
            assertEquals(time.now.plusSeconds(60), short.exchangeCodeAsync("c", "v").expiresAt)
            val long = SupabaseAuth(http(FakeTransport { req, _ -> tokens(req, "a", "r", expiresIn = 7200) }), opt, MemoryStore(), time)
            assertEquals(time.now.plusSeconds(7200), long.exchangeCodeAsync("c", "v").expiresAt)
        }
    }

    // ---- getAccessTokenAsync branches ----

    @Test
    fun forcedRefresh_BypassesTheFreshnessWindow_AndPublishesTheNewSession() {
        runBlocking {
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val store = MemoryStore().apply { saved = AuthSession("old", "rt-old", time.now.plusHours(1), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> tokens(req, "fresh", "rt-fresh") }
            val auth = SupabaseAuth(http(fake), opt, store, time)
            val events = mutableListOf<AuthSession?>()
            auth.addSessionChangedListener { events += it }
            assertEquals("old", auth.getAccessTokenAsync()) // fresh enough: no network
            assertEquals(0, fake.calls.size)
            assertEquals("fresh", auth.getAccessTokenAsync(forceRefresh = true))
            assertEquals(1, fake.calls.size)
            assertTrue(fake.calls[0].second.contains("\"refresh_token\":\"rt-old\""))
            assertEquals("rt-fresh", store.saved!!.refreshToken)
            assertEquals(listOf("fresh"), events.map { it?.accessToken })
            assertNull(auth.signedOutReason)
        }
    }

    @Test
    fun refresh_WhenTheServiceConfigIsNotLoadedYet_ReturnsTheStaleTokenWithoutNetwork() {
        runBlocking {
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val store = MemoryStore().apply { saved = AuthSession("stale", "rt", time.now.minusSeconds(5), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> tokens(req, "new", "rt2") }
            val notReady = CloudOptions().apply { apiBaseUrl = "https://api.example.test" } // no Supabase config yet
            val auth = SupabaseAuth(http(fake), notReady, store, time)
            assertEquals("stale", auth.getAccessTokenAsync())
            assertEquals(0, fake.calls.size)
            assertTrue(auth.isSignedIn)
        }
    }

    @Test
    fun refreshFailure_ThatIsNotASignOutCode_RethrowsAndKeepsTheSession() {
        runBlocking {
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val store = MemoryStore().apply { saved = AuthSession("old", "rt", time.now.minusSeconds(5), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> err(req, 429, "over_request_rate_limit", "slow down") }
            val auth = SupabaseAuth(http(fake), opt, store, time)
            val ex = expectAuthError { auth.getAccessTokenAsync() }
            assertEquals("over_request_rate_limit", ex.code)
            assertTrue(auth.isSignedIn)
            assertEquals("old", auth.session!!.accessToken)
            assertNull(auth.signedOutReason)
        }
    }

    @Test
    fun refreshFailure_UserBanned_MarksTheSignOutAsDisabled() {
        runBlocking {
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val store = MemoryStore().apply { saved = AuthSession("old", "rt", time.now.minusSeconds(5), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> err(req, 400, "user_banned", "banned") }
            val auth = SupabaseAuth(http(fake), opt, store, time)
            assertNull(auth.getAccessTokenAsync())
            assertEquals("disabled", auth.signedOutReason)
            assertFalse(auth.isSignedIn)
            assertNull(store.saved)
        }
    }

    @Test
    fun refreshFailure_Http400WithoutACode_AlsoSignsOutAsExpired() {
        runBlocking {
            // Supabase answers a dead refresh token with a bare 400 on older versions: the C#'s
            // sign-out set includes the synthetic "http_400" code Fail() produces for it.
            val time = ManualTime(OffsetDateTime.parse("2026-10-07T09:00:00Z"))
            val store = MemoryStore().apply { saved = AuthSession("old", "rt", time.now.minusSeconds(5), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> jsonResponse(req, 400, "{}") }
            val auth = SupabaseAuth(http(fake), opt, store, time)
            assertNull(auth.getAccessTokenAsync())
            assertEquals("expired", auth.signedOutReason)
        }
    }

    // ---- sign-out ----

    @Test
    fun signOut_CallsLogoutWithTheBearer_AndClearsEverything() {
        runBlocking {
            val store = MemoryStore().apply { saved = AuthSession("at", "rt", OffsetDateTime.now().plusHours(1), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> jsonResponse(req, 204, "") }
            val auth = SupabaseAuth(http(fake), opt, store)
            val events = mutableListOf<AuthSession?>()
            auth.addSessionChangedListener { events += it }
            auth.signOutAsync()
            assertEquals(1, fake.calls.size)
            val (req, _) = fake.calls.single()
            assertEquals("/auth/v1/logout", req.url.encodedPath)
            assertEquals("Bearer at", req.header("Authorization"))
            assertFalse(auth.isSignedIn)
            assertNull(store.saved)
            assertEquals("user", auth.signedOutReason)
            assertEquals(listOf<AuthSession?>(null), events)
        }
    }

    @Test
    fun signOut_WhenTheNetworkIsDown_StillClearsLocally() {
        runBlocking {
            val store = MemoryStore().apply { saved = AuthSession("at", "rt", OffsetDateTime.now().plusHours(1), "u1", "a@b.test") }
            val auth = SupabaseAuth(http(FakeTransport { _, _ -> throw IOException("offline") }), opt, store)
            auth.signOutAsync()
            assertFalse(auth.isSignedIn)
            assertNull(store.saved)
            assertEquals("user", auth.signedOutReason)
        }
    }

    @Test
    fun signOut_WhenAlreadySignedOut_MakesNoRequest_ButStillPublishes() {
        runBlocking {
            val fake = FakeTransport { req, _ -> jsonResponse(req, 200, "{}") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val events = mutableListOf<AuthSession?>()
            auth.addSessionChangedListener { events += it }
            auth.signOutAsync()
            assertEquals(0, fake.calls.size)
            assertEquals(listOf<AuthSession?>(null), events)
            assertEquals("user", auth.signedOutReason)
        }
    }

    // ---- update password / sign-up failures ----

    @Test
    fun updatePassword_SignedOut_ThrowsWithoutARequest() {
        runBlocking {
            val fake = FakeTransport { req, _ -> jsonResponse(req, 200, "{}") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val ex = expectAuthError { auth.updatePasswordAsync("NewSecret123") }
            assertEquals("signed_out", ex.code)
            assertEquals(0, fake.calls.size)
        }
    }

    @Test
    fun updatePassword_Failure_UsesTheParsedErrorCode() {
        runBlocking {
            val store = MemoryStore().apply { saved = AuthSession("at", "rt", OffsetDateTime.now().plusHours(1), "u1", "a@b.test") }
            val fake = FakeTransport { req, _ -> err(req, 422, "weak_password", "raw") }
            val auth = SupabaseAuth(http(fake), opt, store)
            val ex = expectAuthError { auth.updatePasswordAsync("x") }
            assertEquals("weak_password", ex.code)
        }
    }

    @Test
    fun signUp_Failure_ThrowsTheParsedError() {
        runBlocking {
            val fake = FakeTransport { req, _ -> err(req, 400, "email_exists", "raw") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val ex = expectAuthError { auth.signUpAsync("a@b.test", "Secret123!") }
            assertEquals("email_exists", ex.code)
            assertFalse(auth.isSignedIn)
        }
    }

    // ---- Fail() parsing fallbacks ----

    @Test
    fun errorParsing_UsesErrorAndErrorDescription_WhenTheNewerFieldsAreAbsent() {
        runBlocking {
            val fake = FakeTransport { req, _ ->
                jsonResponse(req, 400, """{"error":"some_legacy_code","error_description":"Legacy description text"}""")
            }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val ex = expectAuthError { auth.signInWithPasswordAsync("a@b.test", "x") }
            assertEquals("some_legacy_code", ex.code)
            assertEquals("Legacy description text", ex.message)
        }
    }

    @Test
    fun errorParsing_NonStringFieldsAndNonJsonBodies_FallBackToHttpCodeAndTheFallbackMessage() {
        runBlocking {
            val numeric = SupabaseAuth(
                http(FakeTransport { req, _ -> jsonResponse(req, 400, """{"error_code":123,"msg":42}""") }),
                opt, MemoryStore(),
            )
            val e1 = expectAuthError { numeric.signInWithPasswordAsync("a@b.test", "x") }
            assertEquals("http_400", e1.code)
            assertEquals(Loc.t("err_sign_in_failed"), e1.message)

            val notJson = SupabaseAuth(
                http(FakeTransport { req, _ -> jsonResponse(req, 500, "<html>oops</html>") }),
                opt, MemoryStore(),
            )
            val e2 = expectAuthError { notJson.signInWithPasswordAsync("a@b.test", "x") }
            assertEquals("http_500", e2.code)
            assertEquals(Loc.t("err_sign_in_failed"), e2.message)
        }
    }

    @Test
    fun errorParsing_ErrorCodeWinsOverError_AndMsgWinsOverDescription() {
        runBlocking {
            val fake = FakeTransport { req, _ ->
                jsonResponse(req, 400, """{"error_code":"new_code","error":"old_code","msg":"New message","error_description":"Old description"}""")
            }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val ex = expectAuthError { auth.signInWithPasswordAsync("a@b.test", "x") }
            assertEquals("new_code", ex.code)
            assertEquals("New message", ex.message)
        }
    }

    // ---- listeners ----

    @Test
    fun sessionChanged_RemovedListenersAreNotCalled() {
        runBlocking {
            val fake = FakeTransport { req, _ -> tokens(req, "at", "rt") }
            val auth = SupabaseAuth(http(fake), opt, MemoryStore())
            val removed = mutableListOf<AuthSession?>()
            val kept = mutableListOf<AuthSession?>()
            val removedListener: (AuthSession?) -> Unit = { removed += it }
            auth.addSessionChangedListener(removedListener)
            auth.addSessionChangedListener { kept += it }
            auth.removeSessionChangedListener(removedListener)
            auth.signInWithPasswordAsync("a@b.test", "x")
            assertTrue(removed.isEmpty())
            assertEquals(1, kept.size)
            assertEquals("at", kept[0]!!.accessToken)
        }
    }

    // ---- the file token store ----

    @Test
    fun tokenStore_MissingFileLoadsNull_SaveCreatesParentDirectories_ClearToleratesAbsence() {
        val dir = Files.createTempDirectory("ia-tok-edge-").toFile()
        try {
            val path = File(dir, "nested/deeper/session.bin").absolutePath
            val protector = object : com.interviewassistant.core.persistence.IDataProtector {
                override fun protect(plain: ByteArray): ByteArray = plain.reversedArray()
                override fun unprotect(cipher: ByteArray): ByteArray = cipher.reversedArray()
            }
            val store = ProtectedFileTokenStore(path, protector)
            assertNull(store.load())
            store.clear() // deleting a file that does not exist is not an error
            val session = AuthSession("a", "r", OffsetDateTime.parse("2026-10-07T09:00:00Z"), "u", "e")
            store.save(session)
            assertTrue(File(path).isFile)
            assertEquals(session, store.load())
            store.clear()
            assertFalse(File(path).exists())
            assertNull(store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
