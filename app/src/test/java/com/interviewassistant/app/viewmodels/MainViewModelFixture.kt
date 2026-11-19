package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.AppPaths
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.ConnectionToastGate
import com.interviewassistant.client.AuthSession
import com.interviewassistant.client.ITokenStore
import com.interviewassistant.client.OAuthRedirectReceiver
import com.interviewassistant.core.persistence.IDataProtector
import com.interviewassistant.core.persistence.NoProtection
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The MainViewModel test fixture: a real [CloudService] over fake HTTP transports (the P4-C1
 * CloudServiceTests pattern) + the real view model over fake platform seams, an immediate UI poster
 * (the C# self-test's pumped dispatcher) and a fake clock for the connection gate.
 */

fun isApi(req: Request, path: String): Boolean =
    req.url.host == "api.test" && req.url.encodedPath == "/api/v1/$path"

fun isApiPrefix(req: Request, prefix: String): Boolean =
    req.url.host == "api.test" && req.url.encodedPath.startsWith("/api/v1/$prefix")

fun isAuth(req: Request, path: String): Boolean =
    req.url.host == "auth.test" && req.url.encodedPath == "/auth/v1/$path"

class FakeTransport : Interceptor {
    val calls = mutableListOf<Request>()
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
}

class MemoryStore(initial: AuthSession?) : ITokenStore {
    var session: AuthSession? = initial
    override fun load(): AuthSession? = session
    override fun save(session: AuthSession) {
        this.session = session
    }
    override fun clear() {
        session = null
    }
}

private class FakeReceiver : OAuthRedirectReceiver {
    override val state: String = "state-1"
    override val redirectUri: String = "interviewassistant://oauth/callback?s=state-1"
    override suspend fun waitForCode(timeout: Duration): String = "code-123"
    override fun close() {}
}

const val VM_USER_A = "11111111-1111-1111-1111-111111111111"
const val VM_DEVICE_ID = "22222222-2222-2222-2222-222222222222"
const val VM_SESSION_ID = "44444444-4444-4444-4444-444444444444"

fun vmSession(
    userId: String = VM_USER_A,
    email: String = "a@x.test",
    accessToken: String = "at-0",
    expiresAt: OffsetDateTime = OffsetDateTime.now().plusHours(1),
) = AuthSession(accessToken, "rt-0", expiresAt, userId, email)

fun vmConfigJson(): String = """
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
    "available": true,
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

fun vmAccountJson(
    billingState: String = "trial",
    liveSecondsRemaining: Int = 1800,
    liveSecondsAvailableToday: Int = -1,
    dailyLiveMinutesCap: Int = 0,
    planName: String = "Trial",
    trialMinutes: Int = 30,
    liveMinutesPerPeriod: Int = 30,
): String = """
{
  "userId": "$VM_USER_A",
  "email": "a@x.test",
  "status": "active",
  "entitlements": {"planCode": "trial", "planName": "$planName", "status": "active", "maxProfiles": 1,
    "prepJobsPerMonth": 3, "liveMinutesPerPeriod": $liveMinutesPerPeriod, "maxConcurrentSessions": 1, "maxDevices": 2,
    "reportRetentionDays": 30, "coachMode": true, "trialMinutes": $trialMinutes},
  "usage": {"periodStartUtc": "2026-10-01T00:00:00Z", "periodEndUtc": "2026-11-01T00:00:00Z",
    "liveSecondsUsed": 0, "liveSecondsRemaining": $liveSecondsRemaining, "prepJobsUsed": 0, "prepJobsRemaining": 3,
    "answerRequestsToday": 0, "liveSecondsAvailableToday": $liveSecondsAvailableToday, "dailyLiveMinutesCap": $dailyLiveMinutesCap},
  "subscription": null,
  "createdUtc": "2026-10-01T00:00:00Z",
  "billingState": "$billingState"
}
""".trimIndent()

private val vmPlansJson: String = """
[{"code": "pro", "name": "Pro",
  "entitlements": {"planCode": "pro", "planName": "Pro", "status": "active", "maxProfiles": 3,
    "prepJobsPerMonth": 30, "liveMinutesPerPeriod": 600, "maxConcurrentSessions": 1, "maxDevices": 3,
    "reportRetentionDays": 365, "coachMode": true, "trialMinutes": 0},
  "purchasable": true, "priceDisplay": "${'$'}20"}]
""".trimIndent()

val vmDeviceJson: String = """
{"id": "$VM_DEVICE_ID", "name": "Test Phone", "platform": "android", "clientVersion": "2.1.0-rc.5",
 "lastSeenUtc": "2026-10-16T00:00:00Z", "revoked": false, "isCurrent": true}
""".trimIndent()

val vmSessionStartJson: String = """
{"sessionId": "$VM_SESSION_ID",
 "realtime": {"clientSecret": "cs-1", "expiresAtUtc": "2027-01-01T00:00:00Z", "model": "gpt-4o-transcribe",
   "webSocketUrl": "wss://127.0.0.1:9/relay", "transcriptionPrompt": "Job interview.", "relay": true},
 "heartbeatIntervalSeconds": 120, "maxDurationSeconds": 7200,
 "remainingSeconds": 1800, "periodRemainingSeconds": 1800, "todayRemainingSeconds": -1}
""".trimIndent()

/** A CloudService over fake transports, with the happy-path API routes the VM flows need. */
class VmCloudFixture(
    bundledJson: String? = """{"apiBaseUrl":"https://api.test"}""",
    session: AuthSession? = null,
) {
    val dir: File = Files.createTempDirectory("vm-cloud-test").toFile()
    val apiFake = FakeTransport()
    val authFake = FakeTransport()
    val store = MemoryStore(session)
    val settings = AppSettings()
    val service: CloudService

    /** Test-controlled API behaviour for `me` (defaults to the trial account). */
    var meBody: () -> String = { vmAccountJson() }

    /** Test-controlled session-start behaviour (defaults to a successful lease). */
    var sessionStart: (Request, String) -> Pair<Int, String> = { _, _ -> 200 to vmSessionStartJson }

    init {
        AppPaths.installBaseDirs(dir.path, dir.path, dir.path)
        authFake.handler = { req, _ ->
            when {
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        apiFake.handler = { req, body ->
            when {
                isApi(req, "config") -> 200 to vmConfigJson()
                isApi(req, "billing/plans") -> 200 to vmPlansJson
                isApi(req, "me") -> 200 to meBody()
                isApi(req, "devices") && req.method == "POST" -> 200 to vmDeviceJson
                isApi(req, "sessions") && req.method == "POST" -> sessionStart(req, body)
                isApiPrefix(req, "sessions") -> 200 to "{}"
                else -> 404 to """{"code":"not_found","message":"no route"}"""
            }
        }
        service = CloudService(
            settings = settings,
            authHttp = OkHttpClient.Builder().addInterceptor(authFake).build(),
            apiHttp = OkHttpClient.Builder().addInterceptor(apiFake).build(),
            tokenStore = store,
            bundledCloudJson = { bundledJson },
            redirectFactory = { FakeReceiver() },
            deviceNameProvider = { "Test Phone" },
        )
    }

    fun close() {
        dir.deleteRecursively()
    }
}

/** The view model under test, with everything it shows recorded. */
class VmFixture(val cloud: VmCloudFixture) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val capture = FakeCaptureService()
    val updates = FakeUpdateService()
    val network = FakeNetworkAvailability()
    val processStats = FakeProcessStats()

    /** The connection gate's clock; tests advance it explicitly. */
    var gateNow: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    val gate = ConnectionToastGate { gateNow }

    val toasts = mutableListOf<Triple<String, ToastKind, String?>>()
    val reportSaved = mutableListOf<String>()

    private val previousProtector: IDataProtector = SecretStore.protector

    /** The view model's meter clock; tests advance it explicitly. */
    var millis: Long = 0L

    init {
        // Before the view model exists: its dev-path provider reads the (absent) key through this.
        SecretStore.protector = NoProtection()
    }

    val vm: MainViewModel = MainViewModel(
        settings = cloud.settings,
        cloud = cloud.service,
        updates = updates,
        capture = capture,
        scope = scope,
        postToUi = { it() },
        network = network,
        processStats = processStats,
        protectorFactory = { NoProtection() },
        connectionGate = gate,
        nowMillis = { millis },
    )

    init {
        vm.notifyListeners += { text, kind, detail -> toasts += Triple(text, kind, detail) }
        vm.reportSavedListeners += { id -> reportSaved += id }
    }

    fun close() {
        scope.cancel()
        SecretStore.protector = previousProtector
        cloud.close()
    }
}
