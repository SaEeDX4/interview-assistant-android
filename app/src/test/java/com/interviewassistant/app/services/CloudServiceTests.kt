package com.interviewassistant.app.services

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.client.AuthException
import com.interviewassistant.client.AuthSession
import com.interviewassistant.client.BackendException
import com.interviewassistant.client.ITokenStore
import com.interviewassistant.client.OAuthRedirectReceiver
import com.interviewassistant.core.languages.Loc
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Routing helpers for the fake transports: host + `/api/v1/…` resp. `/auth/v1/…` path (+ grant type). */
private fun isApi(req: Request, path: String): Boolean =
    req.url.host == "api.test" && req.url.encodedPath == "/api/v1/$path"

private fun isAuth(req: Request, path: String): Boolean =
    req.url.host == "auth.test" && req.url.encodedPath == "/auth/v1/$path"

private fun grant(req: Request, kind: String): Boolean =
    isAuth(req, "token") && req.url.queryParameter("grant_type") == kind

/**
 * The CloudService facade mirror (P4-C1) against fake :client-level transports — the OkHttp
 * interceptor seam the P2 CloudAuthTests use for the C#'s `HttpMessageHandler` stub. Expectations
 * mirror the Windows behaviour (CloudService.cs + audit §7): state classification, options
 * loading order, the re-auth same-user rule, the upgrade poll's termination conditions.
 */
class CloudServiceTests {
    companion object {
        private const val USER_A = "11111111-1111-1111-1111-111111111111"
        private const val USER_B = "33333333-3333-3333-3333-333333333333"
        private const val DEVICE_ID = "22222222-2222-2222-2222-222222222222"

        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    // ---------------- test doubles ----------------

    private class FakeTransport : Interceptor {
        val calls = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        var handler: (Request, String) -> Pair<Int, String> =
            { _, _ -> 404 to """{"code":"not_found","message":"no route"}""" }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let {
                val buffer = Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
            calls += request
            bodies += body
            val (code, text) = handler(request, body)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }

        fun countCalls(predicate: (Request) -> Boolean): Int = calls.count(predicate)
        fun bodyOf(predicate: (Request) -> Boolean): String? =
            calls.indices.firstOrNull { predicate(calls[it]) }?.let { bodies[it] }
    }

    private class MemoryStore(initial: AuthSession?) : ITokenStore {
        var session: AuthSession? = initial
        override fun load(): AuthSession? = session
        override fun save(session: AuthSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }

    private class FakeReceiver(private val code: String = "code-123") : OAuthRedirectReceiver {
        override val state: String = "state-1"
        override val redirectUri: String = "interviewassistant://oauth/callback?s=state-1"
        var closed: Boolean = false
        var waits: Int = 0
        override suspend fun waitForCode(timeout: Duration): String {
            waits++
            return code
        }
        override fun close() {
            closed = true
        }
    }

    private class Fixture(
        bundledJson: String? = """{"apiBaseUrl":"https://api.test"}""",
        session: AuthSession? = null,
        deviceName: String = "Test Phone",
    ) {
        val dir: File = Files.createTempDirectory("cloudsvc-test").toFile()
        val apiFake = FakeTransport()
        val authFake = FakeTransport()
        val store = MemoryStore(session)
        val settings = AppSettings()
        var now: Long = 1_000_000L
        val receiver = FakeReceiver()
        val service: CloudService

        init {
            AppPaths.installBaseDirs(dir.path, dir.path, dir.path)
            authFake.handler = { req, _ ->
                when {
                    isAuth(req, "logout") -> 200 to "{}"
                    else -> 404 to """{"error":"not_found","error_description":"no route"}"""
                }
            }
            service = CloudService(
                settings = settings,
                authHttp = OkHttpClient.Builder().addInterceptor(authFake).build(),
                apiHttp = OkHttpClient.Builder().addInterceptor(apiFake).build(),
                tokenStore = store,
                bundledCloudJson = { bundledJson },
                redirectFactory = { receiver },
                deviceNameProvider = { deviceName },
                upgradeDelay = { now += 5 * 60_000L }, // each poll step ages the fake clock 5 minutes
                nowMillis = { now },
            )
        }

        fun close() {
            dir.deleteRecursively()
        }
    }

    private var fixture: Fixture? = null

    @Before
    fun setUp() {
        CloudService.urlLauncher = null
        CloudService.forcedApiBaseUrl = null
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        CloudService.urlLauncher = null
        CloudService.forcedApiBaseUrl = null
    }

    private fun fixture(
        bundledJson: String? = """{"apiBaseUrl":"https://api.test"}""",
        session: AuthSession? = null,
        deviceName: String = "Test Phone",
    ): Fixture = Fixture(bundledJson, session, deviceName).also { fixture = it }

    // ---------------- payload + routing helpers ----------------

    private fun session(
        userId: String = USER_A,
        email: String = "a@x.test",
        accessToken: String = "at-0",
        expiresAt: OffsetDateTime = OffsetDateTime.now().plusHours(1),
    ) = AuthSession(accessToken, "rt-0", expiresAt, userId, email)

    private fun tokenJson(userId: String, email: String, accessToken: String = "at-1"): String =
        """{"access_token":"$accessToken","refresh_token":"rt-1","expires_in":3600,"token_type":"bearer","user":{"id":"$userId","email":"$email"}}"""

    private fun configJson(authAvailable: Boolean = true): String = """
    {
      "transcriptionModel": "gpt-4o-transcribe",
      "transcriptionFallbacks": [],
      "answerModel": "gpt-5.4-mini",
      "answerFallbacks": [],
      "preparationModel": "gpt-5.4-mini",
      "answerMaxTokens": 800,
      "firstTokenTimeoutMs": 8000,
      "features": {},
      "maintenance": false,
      "maintenanceMessage": null,
      "minimumClientVersion": "1.0.0",
      "offlineGraceHours": 24,
      "auth": {
        "available": $authAvailable,
        "supabaseUrl": "https://auth.test",
        "publishableKey": "anon-key",
        "emailPassword": true,
        "oAuthProviders": ["google"],
        "emailConfirmedRedirectUrl": null
      },
      "links": null,
      "billingEnvironment": "sandbox"
    }
    """.trimIndent()

    private fun accountJson(billingState: String): String = """
    {
      "userId": "$USER_A",
      "email": "a@x.test",
      "status": "active",
      "entitlements": {"planCode": "trial", "planName": "Trial", "status": "active", "maxProfiles": 1,
        "prepJobsPerMonth": 3, "liveMinutesPerPeriod": 30, "maxConcurrentSessions": 1, "maxDevices": 2,
        "reportRetentionDays": 30, "coachMode": true, "trialMinutes": 30},
      "usage": {"periodStartUtc": "2026-10-01T00:00:00Z", "periodEndUtc": "2026-11-01T00:00:00Z",
        "liveSecondsUsed": 0, "liveSecondsRemaining": 1800, "prepJobsUsed": 0, "prepJobsRemaining": 3,
        "answerRequestsToday": 0, "liveSecondsAvailableToday": -1, "dailyLiveMinutesCap": 0},
      "subscription": null,
      "createdUtc": "2026-10-01T00:00:00Z",
      "billingState": "$billingState"
    }
    """.trimIndent()

    private val plansJson: String = """
    [{"code": "pro", "name": "Pro",
      "entitlements": {"planCode": "pro", "planName": "Pro", "status": "active", "maxProfiles": 3,
        "prepJobsPerMonth": 30, "liveMinutesPerPeriod": 600, "maxConcurrentSessions": 1, "maxDevices": 3,
        "reportRetentionDays": 365, "coachMode": true, "trialMinutes": 0},
      "purchasable": true, "priceDisplay": "${'$'}20"}]
    """.trimIndent()

    private val deviceJson: String = """
    {"id": "$DEVICE_ID", "name": "Test Phone", "platform": "android", "clientVersion": "2.1.0-rc.5",
     "lastSeenUtc": "2026-10-16T00:00:00Z", "revoked": false, "isCurrent": true}
    """.trimIndent()

    private fun happyApi(meBody: () -> String = { accountJson("trial") }): (Request, String) -> Pair<Int, String> =
        { req, _ ->
            when {
                isApi(req, "config") -> 200 to configJson()
                isApi(req, "billing/plans") -> 200 to plansJson
                isApi(req, "me") -> 200 to meBody()
                isApi(req, "devices") && req.method == "POST" -> 200 to deviceJson
                isApi(req, "billing/checkout") -> 200 to """{"url":"https://pay.test/checkout"}"""
                isApi(req, "billing/portal") -> 200 to """{"url":"https://pay.test/portal"}"""
                else -> 404 to """{"code":"not_found","message":"no route"}"""
            }
        }

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val payload = enc.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.sig"
    }

    // ---------------- options loading ----------------

    @Test
    fun options_NoApiUrl_NotConfigured() {
        val f = fixture(bundledJson = "{}")
        assertFalse(f.service.isConfigured)
        assertNull(f.service.auth)
        assertNull(f.service.api)
        assertEquals(CloudState.NotConfigured, f.service.state)
        assertEquals(Loc.t("acct_not_configured_long"), f.service.message)
        assertFalse(runBlocking { f.service.refreshAsync() })
    }

    @Test
    fun options_BadBundledJson_FallsBackToDefaults() {
        val f = fixture(bundledJson = "{not json")
        assertEquals(CloudState.NotConfigured, f.service.state)
        assertEquals("", f.service.options.apiBaseUrl)
    }

    @Test
    fun options_ClientVersionComesFromBuildInfo() {
        val f = fixture()
        assertEquals(BuildInfo.version, f.service.options.clientVersion)
    }

    @Test
    fun options_DeveloperOverrideWins_BadOverrideFallsThroughToBundled() {
        val f = fixture()
        val roamingFile = File(AppPaths.roaming, "cloud.json")
        roamingFile.writeText("""{"apiBaseUrl":"https://override.test"}""")
        assertEquals("https://override.test", f.service.loadOptions(developer = true).apiBaseUrl)
        assertEquals("https://api.test", f.service.loadOptions(developer = false).apiBaseUrl)
        roamingFile.writeText("{oops")
        assertEquals("https://api.test", f.service.loadOptions(developer = true).apiBaseUrl)
    }

    @Test
    fun options_ForcedApiBaseUrl_OverridesEverything() {
        CloudService.forcedApiBaseUrl = "https://forced.test"
        val f = fixture()
        assertEquals("https://forced.test", f.service.options.apiBaseUrl)
    }

    // ---------------- refresh: classification ----------------

    @Test
    fun refresh_SignedOut_ConfigOk_SignInMessage_ConfigAppliedAndCached_PlansLoaded() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        assertTrue(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.SignedOut, f.service.state)
        assertEquals(Loc.t("acct_msg_sign_in"), f.service.message)
        assertEquals("https://auth.test", f.service.options.supabaseUrl)
        assertEquals("anon-key", f.service.options.supabaseAnonKey)
        assertEquals(listOf("google"), f.service.options.oAuthProviders)
        assertEquals(1, f.service.plans.size)
        val cache = File(AppPaths.roaming, "service-config.json")
        assertTrue(cache.isFile)
        assertTrue(cache.readText().contains("auth.test"))
        // the cached copy is applied by the next service instance before any refresh (offline
        // start) — built directly here because AppPaths still points at this fixture's folders
        val second = CloudService(
            settings = AppSettings(),
            authHttp = OkHttpClient.Builder().addInterceptor(FakeTransport()).build(),
            apiHttp = OkHttpClient.Builder().addInterceptor(FakeTransport()).build(),
            tokenStore = MemoryStore(null),
            bundledCloudJson = { """{"apiBaseUrl":"https://api.test"}""" },
            redirectFactory = { FakeReceiver() },
            deviceNameProvider = { "Test Phone" },
            upgradeDelay = {},
            nowMillis = { 0L },
        )
        assertEquals("https://auth.test", second.options.supabaseUrl)
        assertEquals("anon-key", second.options.supabaseAnonKey)
    }

    @Test
    fun refresh_SignedOut_AuthUnavailable_MessageAndBlankedAuthOptions() {
        val f = fixture()
        f.apiFake.handler = { req, _ ->
            when {
                isApi(req, "config") -> 200 to configJson(authAvailable = false)
                isApi(req, "billing/plans") -> 200 to plansJson
                else -> 404 to """{"code":"not_found","message":"no route"}"""
            }
        }
        assertTrue(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.SignedOut, f.service.state)
        assertEquals(Loc.t("acct_msg_auth_unavailable"), f.service.message)
        assertEquals("", f.service.options.supabaseUrl)
        assertEquals("", f.service.options.supabaseAnonKey)
    }

    @Test
    fun refresh_PlansAreLoadedOnlyOnce() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        runBlocking {
            f.service.refreshAsync()
            f.service.refreshAsync()
        }
        assertEquals(1, f.apiFake.countCalls { isApi(it, "billing/plans") })
        assertEquals(1, f.service.plans.size)
    }

    @Test
    fun refresh_UpdateRequired426_Classified() {
        val f = fixture()
        f.apiFake.handler = { _, _ -> 426 to """{"code":"update_required","message":"old","correlationId":"abcdef1234567890"}""" }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.UpdateRequired, f.service.state)
        assertEquals(Loc.t("err_update_required_long"), f.service.message)
        val err = f.service.lastRefreshError
        assertTrue(err is BackendException && err.isUpdateRequired)
    }

    @Test
    fun refresh_AccountDisabledCode_Classified() {
        val f = fixture()
        f.apiFake.handler = { _, _ -> 403 to """{"code":"account_disabled","message":"x"}""" }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.AccountDisabled, f.service.state)
        assertEquals(Loc.t("acct_msg_disabled"), f.service.message)
    }

    @Test
    fun refresh_ServerError500_ServiceUnavailable_WithShortReference() {
        val f = fixture()
        f.apiFake.handler = { _, _ -> 500 to """{"code":"boom","message":"kaput","correlationId":"1234567890abcdef"}""" }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.ServiceUnavailable, f.service.state)
        assertEquals(Loc.t("svc_unavailable") + " " + Loc.f("svc_ref", "12345678"), f.service.message)
    }

    @Test
    fun refresh_Offline_ServiceUnavailable_WithUnreachableCopy() {
        val f = fixture()
        f.apiFake.handler = { _, _ -> throw IOException("no route to host") }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.ServiceUnavailable, f.service.state)
        assertEquals(Loc.t("svc_unreachable"), f.service.message)
    }

    @Test
    fun refresh_SignedIn_LoadsAccount_RegistersDeviceAsAndroid_SavesDeviceId() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        assertEquals(CloudState.Connecting, f.service.state) // signed in at construction: connecting until the first refresh
        assertTrue(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.SignedIn, f.service.state)
        assertNull(f.service.message)
        assertNull(f.service.lastRefreshError)
        assertEquals("a@x.test", f.service.account?.email)
        assertEquals(UUID.fromString(DEVICE_ID), f.settings.cloudDeviceId)
        val body = f.apiFake.bodyOf { isApi(it, "devices") && it.method == "POST" }
        assertNotNull(body)
        assertTrue(body!!.contains("\"platform\":\"android\""))
        assertTrue(body.contains("\"name\":\"Test Phone\""))
        assertTrue(body.contains("\"installationId\":\"inst-"))
        assertTrue(File(AppPaths.local, "installation.id").isFile)
    }

    @Test
    fun refresh_SignedIn_LongDeviceName_FallsBack() {
        val f = fixture(session = session(), deviceName = "x".repeat(61))
        f.apiFake.handler = happyApi()
        assertTrue(runBlocking { f.service.refreshAsync() })
        val body = f.apiFake.bodyOf { isApi(it, "devices") && it.method == "POST" }
        assertTrue(body!!.contains("\"name\":\"Android device\""))
    }

    @Test
    fun refresh_Me401AfterTokenRefresh_SessionExpired() {
        val f = fixture(session = session())
        f.apiFake.handler = { req, _ ->
            when {
                isApi(req, "config") -> 200 to configJson()
                isApi(req, "billing/plans") -> 200 to plansJson
                isApi(req, "me") -> 401 to """{"code":"unauthorized","message":"nope"}"""
                else -> 404 to """{"code":"not_found","message":"no route"}"""
            }
        }
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "refresh_token") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.SessionExpired, f.service.state)
        assertEquals(Loc.t("acct_msg_expired"), f.service.message)
        assertTrue(f.service.isSignedIn) // the session survives: only the service rejected it
    }

    @Test
    fun refresh_RefreshTokenRejected_SignsOutLocally_EndsSignedOut() {
        val f = fixture(session = session(expiresAt = OffsetDateTime.now().minusHours(1)))
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "refresh_token") -> 400 to """{"error":"invalid_grant","error_description":"Invalid Refresh Token"}"""
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.SignedOut, f.service.state)
        assertEquals(Loc.t("acct_msg_expired"), f.service.message)
        assertFalse(f.service.isSignedIn)
        assertNull(f.store.session)
    }

    @Test
    fun refresh_AuthServerErrorDuringTokenRefresh_ServiceUnavailable_SessionKept() {
        val f = fixture(session = session(expiresAt = OffsetDateTime.now().minusHours(1)))
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "refresh_token") -> 500 to """{"error":"internal","error_description":"boom"}"""
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        assertFalse(runBlocking { f.service.refreshAsync() })
        assertEquals(CloudState.ServiceUnavailable, f.service.state)
        assertTrue(f.service.lastRefreshError is AuthException)
        assertTrue(f.service.isSignedIn)
    }

    // ---------------- sign-in / sign-up / provider ----------------

    @Test
    fun signIn_FetchesAuthConfigFirst_ThenSignsInAndRefreshes() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking { f.service.signInAsync("a@x.test", "pw") }
        assertEquals(Pair(true, null), r)
        assertEquals(CloudState.SignedIn, f.service.state)
        assertEquals("a@x.test", f.service.account?.email)
    }

    @Test
    fun signIn_WrongPassword_FriendlyError_AndLastAuthError() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 400 to """{"error_code":"invalid_credentials","error_description":"Invalid login credentials"}"""
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking { f.service.signInAsync("a@x.test", "bad") }
        assertFalse(r.first)
        assertEquals(Loc.t("auth_invalid_credentials"), r.second)
        val err = f.service.lastAuthError
        assertTrue(err is AuthException && err.code == "invalid_credentials")
        assertEquals(CloudState.SignedOut, f.service.state)
    }

    @Test
    fun signUp_ConfirmationRequired_NoRefresh() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                isAuth(req, "signup") -> 200 to """{"id":"$USER_A","email":"a@x.test"}"""
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking { f.service.signUpAsync("a@x.test", "pw") }
        assertEquals(Triple(true, true, null), r)
        assertEquals(0, f.apiFake.countCalls { isApi(it, "me") })
        assertEquals(CloudState.SignedOut, f.service.state)
    }

    @Test
    fun signUp_SignedInImmediately_Refreshes() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                isAuth(req, "signup") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking { f.service.signUpAsync("a@x.test", "pw") }
        assertEquals(Triple(true, false, null), r)
        assertEquals(CloudState.SignedIn, f.service.state)
    }

    @Test
    fun signInWithProvider_Success_ReceiverClosedAfterwards() {
        val f = fixture()
        CloudService.urlLauncher = { true }
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "pkce") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking { f.service.signInWithProviderAsync("google") }
        assertEquals(Pair(true, null), r)
        assertEquals(CloudState.SignedIn, f.service.state)
        assertEquals(1, f.receiver.waits)
        assertTrue(f.receiver.closed)
    }

    // ---------------- password reset ----------------

    @Test
    fun resetPassword_RefusedWhileADifferentAccountIsSignedIn() {
        val f = fixture(session = session())
        val r = runBlocking { f.service.resetPasswordAsync("other@x.test", { null }) }
        assertEquals(Pair(false, Loc.t("auth_reset_signed_in_other")), r)
        assertTrue(f.authFake.calls.isEmpty())
        assertTrue(f.apiFake.calls.isEmpty())
    }

    @Test
    fun resetPassword_HappyPath_SetsTheNewPassword_AndRefreshes() {
        val f = fixture()
        f.apiFake.handler = happyApi()
        var requested = false
        f.authFake.handler = { req, _ ->
            when {
                isAuth(req, "recover") -> 200 to "{}"
                grant(req, "pkce") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "user") && req.method == "PUT" -> 200 to "{}"
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        val r = runBlocking {
            f.service.resetPasswordAsync("a@x.test", { "new-password-1" }, { requested = true })
        }
        assertEquals(Pair(true, null), r)
        assertTrue(requested)
        assertTrue(f.receiver.closed)
        val putBody = f.authFake.bodyOf { isAuth(it, "user") && it.method == "PUT" }
        assertTrue(putBody!!.contains("new-password-1"))
        assertEquals(CloudState.SignedIn, f.service.state)
    }

    // ---------------- re-authentication ----------------

    @Test
    fun reauthenticate_SameUser_Ok() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson(USER_A, "a@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        runBlocking { f.service.refreshAsync() } // installs the auth config, as the startup refresh does
        val r = runBlocking { f.service.reauthenticateAsync("pw") }
        assertEquals(Pair(true, null), r)
        assertTrue(f.service.isSignedIn)
    }

    @Test
    fun reauthenticate_DifferentUser_SignsOutAndFails() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson(USER_B, "b@x.test")
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        runBlocking { f.service.refreshAsync() }
        val r = runBlocking { f.service.reauthenticateAsync("pw") }
        assertEquals(Pair(false, Loc.t("err_reauth_failed")), r)
        assertFalse(f.service.isSignedIn)
    }

    @Test
    fun reauthenticate_WrongPassword_SpecificCopy() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        f.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 400 to """{"error_code":"invalid_credentials","error_description":"Invalid login credentials"}"""
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        runBlocking { f.service.refreshAsync() }
        val r = runBlocking { f.service.reauthenticateAsync("bad") }
        assertEquals(Pair(false, Loc.t("err_reauth_wrong_password")), r)
        assertTrue(f.service.isSignedIn) // a wrong password is not a sign-out
    }

    @Test
    fun reauthenticate_SignedOut_Unavailable() {
        val f = fixture()
        val r = runBlocking { f.service.reauthenticateAsync("pw") }
        assertEquals(Pair(false, Loc.t("err_reauth_unavailable")), r)
    }

    @Test
    fun signInProvider_ReadsTheJwtAppMetadata() {
        val google = fixture(session = session(accessToken = jwt("""{"app_metadata":{"provider":"google"}}""")))
        assertEquals("google", google.service.signInProvider)
        val none = fixture(session = session(accessToken = jwt("""{"sub":"x"}""")))
        assertNull(none.service.signInProvider)
        val garbage = fixture(session = session(accessToken = "not-a-jwt"))
        assertNull(garbage.service.signInProvider)
    }

    // ---------------- sign-out ----------------

    @Test
    fun signOut_ClearsAccountAndPlans_WithSignedOutMessage() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        runBlocking { f.service.refreshAsync() }
        assertEquals(1, f.service.plans.size)
        runBlocking { f.service.signOutAsync() }
        assertNull(f.service.account)
        assertTrue(f.service.plans.isEmpty())
        assertEquals(CloudState.SignedOut, f.service.state)
        assertEquals(Loc.t("acct_msg_signed_out"), f.service.message)
        assertNull(f.store.session)
    }

    // ---------------- billing: upgrade poll + portal ----------------

    @Test
    fun upgrade_PollsUntilPaid_ThenClearsPending() {
        val f = fixture(session = session())
        var meCalls = 0
        f.apiFake.handler = happyApi {
            meCalls++
            accountJson(if (meCalls <= 2) "trial" else "active")
        }
        val launched = mutableListOf<String>()
        CloudService.urlLauncher = { url -> launched += url; true }
        runBlocking { f.service.refreshAsync() }
        val r = runBlocking { f.service.startUpgradeAsync("pro") }
        assertEquals(Pair(true, null), r)
        assertEquals(listOf("https://pay.test/checkout"), launched)
        assertTrue(f.service.upgradePending)
        runBlocking {
            withTimeout(10_000) { while (f.service.upgradePending) delay(5) }
        }
        assertFalse(f.service.upgradePending)
        assertEquals("active", f.service.account?.billingState)
    }

    @Test
    fun upgrade_NeverPaid_TimesOutAndClearsPending() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        CloudService.urlLauncher = { true }
        runBlocking { f.service.refreshAsync() }
        val r = runBlocking { f.service.startUpgradeAsync("pro") }
        assertEquals(Pair(true, null), r)
        runBlocking {
            withTimeout(10_000) { while (f.service.upgradePending) delay(5) }
        }
        assertFalse(f.service.upgradePending)
        assertEquals("trial", f.service.account?.billingState)
        assertTrue(f.apiFake.countCalls { isApi(it, "me") } >= 3) // it really polled before giving up
    }

    @Test
    fun upgrade_NonHttpsCheckoutLink_Refused() {
        val f = fixture(session = session())
        f.apiFake.handler = { req, _ ->
            when {
                isApi(req, "billing/checkout") -> 200 to """{"url":"http://pay.test/checkout"}"""
                else -> happyApi()(req, "")
            }
        }
        CloudService.urlLauncher = { true }
        runBlocking { f.service.refreshAsync() }
        val r = runBlocking { f.service.startUpgradeAsync("pro") }
        assertEquals(Pair(false, Loc.t("err_checkout_link")), r)
        assertFalse(f.service.upgradePending)
    }

    @Test
    fun portal_SignedIn_OpensThePortalUrl_SignedOut_Refused() {
        val f = fixture(session = session())
        f.apiFake.handler = happyApi()
        val launched = mutableListOf<String>()
        CloudService.urlLauncher = { url -> launched += url; true }
        runBlocking { f.service.refreshAsync() }
        assertEquals(Pair(true, null), runBlocking { f.service.openPortalAsync() })
        assertEquals(listOf("https://pay.test/portal"), launched)

        val g = fixture()
        assertEquals(Pair(false, Loc.t("err_sign_in_first")), runBlocking { g.service.openPortalAsync() })
    }

    @Test
    fun isPaidState_Table() {
        assertTrue(CloudService.isPaidState("active"))
        assertTrue(CloudService.isPaidState("canceling"))
        assertTrue(CloudService.isPaidState("past_due"))
        assertFalse(CloudService.isPaidState("trial"))
        assertFalse(CloudService.isPaidState("expired"))
        assertFalse(CloudService.isPaidState("paused"))
        assertFalse(CloudService.isPaidState(null))
    }

    // ---------------- OpenExternal gate ----------------

    @Test
    fun openExternal_GateTable() {
        val launched = mutableListOf<String>()
        CloudService.urlLauncher = { url -> launched += url; true }
        assertTrue(CloudService.openExternal("https://example.com/path?q=1"))
        assertTrue(CloudService.openExternal("https://localhost:8443/cb"))
        assertFalse(CloudService.openExternal("http://example.com"))
        assertFalse(CloudService.openExternal("https://192.168.0.1/x"))
        assertFalse(CloudService.openExternal("https://[2001:db8::1]/x"))
        assertFalse(CloudService.openExternal("not a url"))
        assertFalse(CloudService.openExternal("example.com"))
        assertFalse(CloudService.openExternal(""))
        assertEquals(listOf("https://example.com/path?q=1", "https://localhost:8443/cb"), launched)

        CloudService.urlLauncher = null
        assertFalse(CloudService.openExternal("https://example.com")) // no launcher installed: refused
    }
}
