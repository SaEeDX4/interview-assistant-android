package com.interviewassistant.client

import com.interviewassistant.contracts.ContractJson
import com.interviewassistant.core.languages.Loc
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Direct tests for `CloudSession.cs` (P7 coverage closure): the idempotent start with its retry
 * rule (0/502/503 retried twice with the same key, `maintenance` and other errors never), the
 * heartbeat metering state (the -1 sentinels, the minutes-left warning window, end on
 * inactive/404/409/403, the 3-failure instability warning), the relay credential flag, and the
 * graceful stop. The heartbeat loop itself is left off (`runLoop = false`) — `heartbeatOnceAsync`
 * is the C#'s own deterministic test surface.
 */
class CloudSessionTests {

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

    companion object {
        private val SESSION_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        private val DEVICE_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

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

        private fun startJson(interval: Int = 30, remaining: Int = 1800, period: Int = -1, today: Int = -1): String =
            """{"sessionId":"$SESSION_ID","realtime":{"clientSecret":"","expiresAtUtc":"2026-01-01T01:00:00Z",""" +
                """"model":"gpt-4o-transcribe","webSocketUrl":"/api/v1/relay/$SESSION_ID","transcriptionPrompt":"","relay":true},""" +
                """"heartbeatIntervalSeconds":$interval,"maxDurationSeconds":7200,"remainingSeconds":$remaining,""" +
                """"periodRemainingSeconds":$period,"todayRemainingSeconds":$today}"""

        private fun heartbeatJson(remaining: Int, active: Boolean = true, endReason: String? = null, period: Int = -1, today: Int = -1): String =
            """{"remainingSeconds":$remaining,"active":$active,""" +
                """"endReason":${if (endReason == null) "null" else "\"$endReason\""},""" +
                """"periodRemainingSeconds":$period,"todayRemainingSeconds":$today}"""

        private fun apiError(code: String, message: String): String = """{"code":"$code","message":"$message"}"""

        /** A server whose /sessions start always succeeds with [startBody]; everything else is delegated. */
        private fun server(startBody: String = startJson(), rest: (Request) -> Response): FakeTransport =
            FakeTransport { req, _ ->
                if (req.url.encodedPath.endsWith("/sessions")) jsonResponse(req, 200, startBody) else rest(req)
            }

        private fun api(fake: FakeTransport, token: suspend (Boolean) -> String? = { "tok" }): BackendClient =
            BackendClient(http(fake), token, "https://api.example.test", "2.1.0")
    }

    private suspend fun start(fake: FakeTransport, language: String? = "en", targetId: UUID? = null): CloudSession =
        CloudSession.startAsync(api(fake), DEVICE_ID, targetId, language, runLoop = false)

    // ---- start ----

    @Test
    fun start_MapsTheResponse_AndSendsTheIdempotencyKeyAndRelayFlag() {
        runBlocking {
            val target = UUID.fromString("77777777-7777-7777-7777-777777777777")
            val fake = server(startJson(interval = 45, remaining = 1500, period = 9000, today = 3600)) { req ->
                jsonResponse(req, 500, apiError("unexpected", "extra call"))
            }
            val s = start(fake, language = "fa", targetId = target)
            assertEquals(SESSION_ID, s.id)
            assertEquals(45, s.heartbeatIntervalSeconds)
            assertEquals(1500, s.remainingSeconds)
            assertEquals(9000, s.periodRemainingSeconds)
            assertEquals(3600, s.todayRemainingSeconds)
            assertTrue(s.active)
            assertNull(s.endReason)
            assertEquals("fa", s.language)
            assertEquals(target, s.targetId)
            assertEquals("/api/v1/relay/$SESSION_ID", s.credential.webSocketUrl)
            assertTrue(s.credential.relay)

            val body = ContractJson.instance.parseToJsonElement(fake.calls.single().second).jsonObject
            assertEquals(DEVICE_ID.toString(), body["deviceId"]!!.jsonPrimitive.content)
            assertEquals(target.toString(), body["targetId"]!!.jsonPrimitive.content)
            assertEquals("fa", body["interviewLanguage"]!!.jsonPrimitive.content)
            assertEquals("true", body["relay"]!!.jsonPrimitive.content)
            val key = body["idempotencyKey"]!!.jsonPrimitive.content
            assertTrue(key.matches(Regex("[0-9a-f]{32}")))
            s.stopAsync()
        }
    }

    @Test
    fun start_ClampsTheHeartbeatIntervalTo5To120() {
        runBlocking {
            for ((given, expected) in listOf(1 to 5, 5 to 5, 30 to 30, 120 to 120, 999 to 120)) {
                val fake = server(startJson(interval = given)) { req -> jsonResponse(req, 204, "") }
                val s = start(fake)
                assertEquals("interval $given", expected, s.heartbeatIntervalSeconds)
                s.stopAsync()
            }
        }
    }

    @Test
    fun start_RetriesTransientFailures_WithTheSameIdempotencyKey() {
        runBlocking {
            var starts = 0
            val fake = FakeTransport { req, _ ->
                if (req.url.encodedPath.endsWith("/sessions")) {
                    starts++
                    if (starts < 3) jsonResponse(req, 503, apiError("unavailable", "try later"))
                    else jsonResponse(req, 200, startJson())
                } else {
                    jsonResponse(req, 204, "")
                }
            }
            val s = start(fake)
            assertTrue(s.active)
            assertEquals(3, starts)
            val keys = fake.calls.filter { it.first.url.encodedPath.endsWith("/sessions") }
                .map { ContractJson.instance.parseToJsonElement(it.second).jsonObject["idempotencyKey"]!!.jsonPrimitive.content }
            assertEquals(3, keys.size)
            assertEquals(keys[0], keys[1])
            assertEquals(keys[1], keys[2])
            s.stopAsync()
        }
    }

    @Test
    fun start_RetriesOfflineAndBadGateway_ButNotMaintenanceOrClientErrors() {
        runBlocking {
            // status 0 (transport failure) is retried
            var n0 = 0
            val offline = FakeTransport { req, _ ->
                if (req.url.encodedPath.endsWith("/sessions")) {
                    n0++
                    if (n0 == 1) throw IOException("dns")
                    jsonResponse(req, 200, startJson())
                } else {
                    jsonResponse(req, 204, "")
                }
            }
            start(offline).stopAsync()
            assertEquals(2, n0)

            // 502 is retried
            var n502 = 0
            val badGateway = FakeTransport { req, _ ->
                if (req.url.encodedPath.endsWith("/sessions")) {
                    n502++
                    if (n502 == 1) jsonResponse(req, 502, apiError("bad_gateway", "x")) else jsonResponse(req, 200, startJson())
                } else {
                    jsonResponse(req, 204, "")
                }
            }
            start(badGateway).stopAsync()
            assertEquals(2, n502)

            // 503 with code "maintenance" is NOT retried (the C#'s explicit carve-out)
            var nM = 0
            val maintenance = FakeTransport { req, _ ->
                nM++
                jsonResponse(req, 503, apiError("maintenance", "down for maintenance"))
            }
            val exM = expectStartError(maintenance)
            assertEquals("maintenance", exM.code)
            assertEquals(1, nM)

            // a 402 entitlement failure is NOT retried
            var n402 = 0
            val entitlement = FakeTransport { req, _ ->
                n402++
                jsonResponse(req, 402, apiError("allowance_exhausted", "none left"))
            }
            val ex402 = expectStartError(entitlement)
            assertEquals(402, ex402.status)
            assertEquals(1, n402)
        }
    }

    @Test
    fun start_GivesUpAfterThreeAttempts() {
        runBlocking {
            var n = 0
            val fake = FakeTransport { req, _ ->
                n++
                jsonResponse(req, 503, apiError("unavailable", "still down"))
            }
            val ex = expectStartError(fake)
            assertEquals(503, ex.status)
            assertEquals(3, n) // the initial attempt plus the C#'s two retries (attempt < 2)
        }
    }

    private suspend fun expectStartError(fake: FakeTransport): BackendException {
        try {
            start(fake)
        } catch (e: BackendException) {
            return e
        }
        fail("expected BackendException")
        throw AssertionError("unreachable")
    }

    // ---- relay surface ----

    @Test
    fun relayEndpointAndHeaders_ComeFromTheCredentialAndClientVersion() {
        runBlocking {
            val fake = server { req -> jsonResponse(req, 204, "") }
            val s = start(fake, language = "fa")
            val endpoint = s.relayEndpoint
            assertEquals("wss", endpoint.scheme)
            assertEquals("api.example.test", endpoint.host)
            assertEquals("/api/v1/relay/$SESSION_ID", endpoint.path)
            assertEquals("language=fa", endpoint.query)
            assertEquals(mapOf("X-Client-Version" to "2.1.0"), s.relayHeaders)
            s.stopAsync()
        }
    }

    @Test
    fun nextConnectionCredential_UsesTheRefreshFlagExactlyOnce_AndStopsWhenTheLeaseEnds() {
        runBlocking {
            val forces = mutableListOf<Boolean>()
            val fake = server { req ->
                if (req.url.encodedPath.endsWith("/heartbeat")) jsonResponse(req, 200, heartbeatJson(0, active = false, endReason = "allowance"))
                else jsonResponse(req, 204, "")
            }
            val s = CloudSession.startAsync(api(fake) { force -> forces += force; "tok-$force" }, DEVICE_ID, null, "en", runLoop = false)
            forces.clear() // the start itself fetched one token (force = false); the flag rule is about what follows
            assertEquals("tok-false", s.nextConnectionCredentialAsync())
            s.forceTokenRefresh()
            assertEquals("tok-true", s.nextConnectionCredentialAsync())
            assertEquals("tok-false", s.nextConnectionCredentialAsync()) // the flag was consumed
            assertEquals(listOf(false, true, false), forces)
            s.heartbeatOnceAsync() // the server ends the lease
            assertFalse(s.active)
            val callsBefore = forces.size
            assertNull(s.nextConnectionCredentialAsync())
            assertEquals(callsBefore, forces.size) // no token is fetched for a dead lease
            s.stopAsync()
        }
    }

    // ---- heartbeat ----

    @Test
    fun heartbeat_UpdatesMetering_KeepsMinusOneSentinels_AndRaisesRemainingChanged() {
        runBlocking {
            var step = 0
            val fake = server(startJson(remaining = 1800, period = 9000, today = 3600)) { req ->
                step++
                if (step == 1) jsonResponse(req, 200, heartbeatJson(1700, period = -1, today = -1))
                else jsonResponse(req, 200, heartbeatJson(1600, period = 8000, today = 3000))
            }
            val s = start(fake)
            val seen = mutableListOf<Int>()
            s.addRemainingChangedListener { seen += it }
            s.heartbeatOnceAsync()
            assertEquals(1700, s.remainingSeconds)
            assertEquals(9000, s.periodRemainingSeconds) // -1 from the server keeps the previous value
            assertEquals(3600, s.todayRemainingSeconds)
            s.heartbeatOnceAsync()
            assertEquals(1600, s.remainingSeconds)
            assertEquals(8000, s.periodRemainingSeconds)
            assertEquals(3000, s.todayRemainingSeconds)
            assertEquals(listOf(1700, 1600), seen)
            // The sequence numbers increment from 1.
            val seqs = fake.calls.filter { it.first.url.encodedPath.endsWith("/heartbeat") }
                .map { ContractJson.instance.parseToJsonElement(it.second).jsonObject["sequence"]!!.jsonPrimitive.content.toInt() }
            assertEquals(listOf(1, 2), seqs)
            s.stopAsync()
        }
    }

    @Test
    fun heartbeat_WarnsOnlyInsideTheLastFiveMinutes() {
        runBlocking {
            val script = mutableListOf(301, 300, 61, 60, 1, 0)
            val fake = server { req ->
                if (req.url.encodedPath.endsWith("/heartbeat")) jsonResponse(req, 200, heartbeatJson(script.removeAt(0)))
                else jsonResponse(req, 204, "")
            }
            val s = start(fake)
            val warnings = mutableListOf<String>()
            s.addWarningListener { warnings += it }
            repeat(6) { s.heartbeatOnceAsync() }
            // remaining/60 + 1 minutes, per the C#'s Loc.F call: 300 -> 6, 61 -> 2, 60 -> 2, 1 -> 1; 301 and 0 stay silent.
            assertEquals(
                listOf("6 live minutes left on your plan.", "2 live minutes left on your plan.", "2 live minutes left on your plan.", "1 live minutes left on your plan."),
                warnings,
            )
            s.stopAsync()
        }
    }

    @Test
    fun heartbeat_ServerEndingTheLease_FiresEndedOnce_WithTheServerReason() {
        runBlocking {
            var step = 0
            val fake = server { req ->
                step++
                if (step == 1) jsonResponse(req, 200, heartbeatJson(0, active = false, endReason = "max_duration"))
                else jsonResponse(req, 200, heartbeatJson(0, active = false, endReason = "other"))
            }
            val s = start(fake)
            val ended = mutableListOf<String>()
            s.addEndedListener { ended += it }
            s.heartbeatOnceAsync()
            assertFalse(s.active)
            assertEquals("max_duration", s.endReason)
            s.heartbeatOnceAsync() // no-op on an ended lease: no request, no second event
            assertEquals(listOf("max_duration"), ended)
            assertEquals(1, fake.calls.count { it.first.url.encodedPath.endsWith("/heartbeat") })
            s.stopAsync()
        }
    }

    @Test
    fun heartbeat_ServerEndingWithoutAReason_UsesEnded() {
        runBlocking {
            val fake = server { req -> jsonResponse(req, 200, heartbeatJson(0, active = false, endReason = null)) }
            val s = start(fake)
            s.heartbeatOnceAsync()
            assertFalse(s.active)
            assertEquals("ended", s.endReason)
            s.stopAsync()
        }
    }

    @Test
    fun heartbeat_GoneConflictAndForbidden_EndTheLeaseWithTheErrorCode() {
        runBlocking {
            for ((status, code) in listOf(404 to "session_not_found", 409 to "session_ended", 403 to "device_revoked")) {
                val fake = server { req -> jsonResponse(req, status, apiError(code, "x")) }
                val s = start(fake)
                val ended = mutableListOf<String>()
                s.addEndedListener { ended += it }
                s.heartbeatOnceAsync()
                assertFalse("status $status", s.active)
                assertEquals(code, s.endReason)
                assertEquals(listOf(code), ended)
                s.stopAsync()
            }
        }
    }

    @Test
    fun heartbeat_OtherFailures_CountUpToTheInstabilityWarning_AndSuccessResetsTheCount() {
        runBlocking {
            val script = mutableListOf(
                500 to "server_error", 500 to "server_error", 500 to "server_error",
                200 to "", // success resets
                500 to "server_error", 500 to "server_error",
            )
            val fake = server { req ->
                if (!req.url.encodedPath.endsWith("/heartbeat")) {
                    jsonResponse(req, 204, "")
                } else {
                    val (status, code) = script.removeAt(0)
                    if (status == 200) jsonResponse(req, 200, heartbeatJson(1500))
                    else jsonResponse(req, status, apiError(code, "x"))
                }
            }
            val s = start(fake)
            val warnings = mutableListOf<String>()
            s.addWarningListener { warnings += it }
            repeat(6) { s.heartbeatOnceAsync() }
            assertTrue(s.active) // transient failures never end the lease locally
            assertEquals(
                listOf("The connection to the service is unstable. Live help continues while it recovers."),
                warnings,
            )
            s.stopAsync()
        }
    }

    // ---- stop ----

    @Test
    fun stop_NotifiesTheServer_AndEndsAsStopped() {
        runBlocking {
            val fake = server { req -> jsonResponse(req, 204, "") }
            val s = start(fake)
            val ended = mutableListOf<String>()
            s.addEndedListener { ended += it }
            s.stopAsync()
            assertFalse(s.active)
            assertEquals("stopped", s.endReason)
            assertEquals(listOf("stopped"), ended)
            assertEquals(1, fake.calls.count { it.first.url.encodedPath.endsWith("/stop") })
            // A second stop changes nothing.
            s.stopAsync()
            assertEquals(1, fake.calls.count { it.first.url.encodedPath.endsWith("/stop") })
            assertEquals(listOf("stopped"), ended)
        }
    }

    @Test
    fun stop_ServerFailureIsSwallowed_AndTheLeaseStillEnds() {
        runBlocking {
            val fake = server { req ->
                if (req.url.encodedPath.endsWith("/stop")) jsonResponse(req, 500, apiError("boom", "x"))
                else jsonResponse(req, 204, "")
            }
            val s = start(fake)
            s.stopAsync() // the C# swallows the stop failure: the server sweeper expires the lease
            assertFalse(s.active)
            assertEquals("stopped", s.endReason)
        }
    }

    @Test
    fun stop_OnAServerEndedLease_DoesNotCallStopAgain() {
        runBlocking {
            val fake = server { req ->
                if (req.url.encodedPath.endsWith("/heartbeat")) jsonResponse(req, 200, heartbeatJson(0, active = false, endReason = "revoked"))
                else jsonResponse(req, 204, "")
            }
            val s = start(fake)
            s.heartbeatOnceAsync()
            assertFalse(s.active)
            s.stopAsync()
            assertEquals(0, fake.calls.count { it.first.url.encodedPath.endsWith("/stop") })
            assertEquals("revoked", s.endReason) // the server's reason is not overwritten by "stopped"
        }
    }

    @Test
    fun disposeAsync_StopsTheLease() {
        runBlocking {
            val fake = server { req -> jsonResponse(req, 204, "") }
            val s = start(fake)
            s.disposeAsync()
            assertFalse(s.active)
            assertEquals("stopped", s.endReason)
            assertEquals(1, fake.calls.count { it.first.url.encodedPath.endsWith("/stop") })
        }
    }
}
