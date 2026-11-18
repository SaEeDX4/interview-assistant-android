package com.interviewassistant.contracts

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract fixtures for the `:contracts` mirror (TRANSFER-MAP §5.4): the exact wire JSON of the
 * Windows contracts under `JsonSerializerDefaults.Web` (camelCase names, defaults and nulls written,
 * `-1` sentinels, ISO-8601 UTC dates, lowercase GUIDs). A serialization drift on either side fails here.
 */
class ApiContractsJsonTest {
    private val json = ContractJson.instance

    @Test
    fun apiErrorWritesNullCorrelationId() {
        val text = json.encodeToString(ApiError.serializer(), ApiError("device_limit", "Too many devices."))
        assertEquals("""{"code":"device_limit","message":"Too many devices.","correlationId":null}""", text)
        val back = json.decodeFromString(ApiError.serializer(), """{"code":"http_404","message":"Not found","correlationId":"abc123"}""")
        assertEquals("abc123", back.correlationId)
    }

    @Test
    fun usageSummaryDefaultsAreTheMinusOneSentinels() {
        val text = """{"periodStartUtc":"2026-10-01T00:00:00Z","periodEndUtc":"2026-11-01T00:00:00Z","liveSecondsUsed":600,"liveSecondsRemaining":35400,"prepJobsUsed":1,"prepJobsRemaining":19,"answerRequestsToday":42}"""
        val usage = json.decodeFromString(UsageSummary.serializer(), text)
        assertEquals(-1, usage.liveSecondsAvailableToday)
        assertEquals(0, usage.dailyLiveMinutesCap)
        assertEquals(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC), usage.periodStartUtc)
        assertEquals(
            text.replace("}", ",\"liveSecondsAvailableToday\":-1,\"dailyLiveMinutesCap\":0}"),
            json.encodeToString(UsageSummary.serializer(), usage),
        )
    }

    @Test
    fun startSessionResponseDefaultsAndRoundTrip() {
        val text = """{"sessionId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","realtime":{"clientSecret":"","expiresAtUtc":"2026-10-07T11:00:00Z","model":"gpt-4o-transcribe","webSocketUrl":"relay/sessions/3fa85f64","transcriptionPrompt":"prompt","relay":true},"heartbeatIntervalSeconds":30,"maxDurationSeconds":7200,"remainingSeconds":1800,"periodRemainingSeconds":-1,"todayRemainingSeconds":-1}"""
        val r = json.decodeFromString(StartSessionResponse.serializer(), text)
        assertEquals(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), r.sessionId)
        assertTrue(r.realtime.relay)
        assertEquals(text, json.encodeToString(StartSessionResponse.serializer(), r))
        // Omitted optional fields take the C# defaults (-1), and serialize back explicitly.
        val minimal = """{"sessionId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","realtime":{"clientSecret":"s","expiresAtUtc":"2026-10-07T11:00:00Z","model":"m","webSocketUrl":"w","transcriptionPrompt":"p"},"heartbeatIntervalSeconds":30,"maxDurationSeconds":7200,"remainingSeconds":1800}"""
        val r2 = json.decodeFromString(StartSessionResponse.serializer(), minimal)
        assertEquals(-1, r2.periodRemainingSeconds)
        assertEquals(-1, r2.todayRemainingSeconds)
        assertEquals(false, r2.realtime.relay)
    }

    @Test
    fun heartbeatResponseDefaults() {
        val r = json.decodeFromString(HeartbeatResponse.serializer(), """{"remainingSeconds":1700,"active":true,"endReason":null}""")
        assertEquals(-1, r.periodRemainingSeconds)
        assertEquals(-1, r.todayRemainingSeconds)
        assertNull(r.endReason)
        assertEquals(
            """{"remainingSeconds":1700,"active":false,"endReason":"allowance_exhausted","periodRemainingSeconds":0,"todayRemainingSeconds":-1}""",
            json.encodeToString(HeartbeatResponse.serializer(), HeartbeatResponse(1700, false, "allowance_exhausted", 0, -1)),
        )
    }

    @Test
    fun answerStreamRequestWritesNullIds() {
        val req = AnswerStreamRequest(null, null, listOf(AnswerMessage("user", "Tell me about yourself")), 300, AnswerPurposes.Manual)
        assertEquals(
            """{"sessionId":null,"prepJobId":null,"messages":[{"role":"user","content":"Tell me about yourself"}],"maxTokens":300,"purpose":"manual","deviceId":null}""",
            json.encodeToString(AnswerStreamRequest.serializer(), req),
        )
    }

    @Test
    fun startSessionRequestDefaultsRelayFalse() {
        val req = StartSessionRequest(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), null, "key123", "en")
        assertEquals(
            """{"deviceId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","targetId":null,"idempotencyKey":"key123","interviewLanguage":"en","relay":false}""",
            json.encodeToString(StartSessionRequest.serializer(), req),
        )
    }

    @Test
    fun fractionalSecondsRenderLikeSystemTextJson() {
        val cred = json.decodeFromString(
            RealtimeCredential.serializer(),
            """{"clientSecret":"s","expiresAtUtc":"2026-10-07T09:00:00.5Z","model":"m","webSocketUrl":"w","transcriptionPrompt":"p","relay":false}""",
        )
        assertEquals(500_000_000, cred.expiresAtUtc.nano)
        assertTrue(json.encodeToString(RealtimeCredential.serializer(), cred).contains("\"expiresAtUtc\":\"2026-10-07T09:00:00.5Z\""))
    }

    @Test
    fun accountDtoParsesAFullMePayload() {
        val text = """{"userId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","email":"a@b.test","status":"active","entitlements":{"planCode":"pro","planName":"Pro","status":"active","maxProfiles":5,"prepJobsPerMonth":20,"liveMinutesPerPeriod":600,"maxConcurrentSessions":1,"maxDevices":3,"reportRetentionDays":365,"coachMode":true,"trialMinutes":30},"usage":{"periodStartUtc":"2026-10-01T00:00:00Z","periodEndUtc":"2026-11-01T00:00:00Z","liveSecondsUsed":0,"liveSecondsRemaining":36000,"prepJobsUsed":0,"prepJobsRemaining":20,"answerRequestsToday":0,"liveSecondsAvailableToday":14400,"dailyLiveMinutesCap":240},"subscription":{"planCode":"pro","status":"active","currentPeriodEndUtc":"2026-11-01T00:00:00Z","cancelAtPeriodEnd":false,"graceUntilUtc":null},"createdUtc":"2026-09-01T12:30:00Z","billingState":"active"}"""
        val account = json.decodeFromString(AccountDto.serializer(), text)
        assertEquals("pro", account.entitlements.planCode)
        assertEquals(36000, account.usage.liveSecondsRemaining)
        assertEquals(text, json.encodeToString(AccountDto.serializer(), account))
    }

    @Test
    fun runtimeConfigParsesThePublicConfigShape() {
        val text = """{"transcriptionModel":"gpt-4o-transcribe","transcriptionFallbacks":["gpt-4o-mini-transcribe"],"answerModel":"gpt-4o","answerFallbacks":[],"preparationModel":"gpt-4o","answerMaxTokens":600,"firstTokenTimeoutMs":8000,"features":{"coach":true},"maintenance":false,"maintenanceMessage":null,"minimumClientVersion":"2.0.0","offlineGraceHours":72,"auth":{"available":true,"supabaseUrl":"https://proj.supabase.co","publishableKey":"pk","emailPassword":true,"oAuthProviders":["google"],"emailConfirmedRedirectUrl":null},"links":null,"billingEnvironment":"sandbox"}"""
        val cfg = json.decodeFromString(RuntimeConfig.serializer(), text)
        assertEquals(listOf("google"), cfg.auth!!.oAuthProviders)
        assertNull(cfg.links)
        assertEquals("sandbox", cfg.billingEnvironment)
        // Defaults when the optional tail is absent entirely.
        val minimal = text.substringBefore(",\"auth\"") + "}"
        val cfg2 = json.decodeFromString(RuntimeConfig.serializer(), minimal)
        assertNull(cfg2.auth)
        assertEquals("sandbox", cfg2.billingEnvironment)
    }

    @Test
    fun syncDocumentCarriesOpaqueJson() {
        val doc = SyncDocument(
            UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "profiles", null,
            buildJsonObject { put("name", "Default") }, 3,
            OffsetDateTime.of(2026, 10, 7, 9, 0, 0, 0, ZoneOffset.UTC),
        )
        val text = json.encodeToString(SyncDocument.serializer(), doc)
        assertEquals(
            """{"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","kind":"profiles","parentId":null,"data":{"name":"Default"},"version":3,"updatedUtc":"2026-10-07T09:00:00Z"}""",
            text,
        )
        val back = json.decodeFromString(SyncDocument.serializer(), text)
        assertEquals(doc.data, back.data)
        val upsert = json.decodeFromString(UpsertDocumentRequest.serializer(), """{"parentId":null,"data":{"a":1},"expectedVersion":2}""")
        assertEquals(2L, upsert.expectedVersion)
        assertTrue(upsert.data is JsonElement)
    }

    @Test
    fun billingStatesAreTheServerStrings() {
        assertEquals(
            setOf("trial", "trial_exhausted", "active", "canceling", "past_due", "paused", "expired", "revoked"),
            setOf(
                BillingStates.Trial, BillingStates.TrialExhausted, BillingStates.Active, BillingStates.Canceling,
                BillingStates.PastDue, BillingStates.Paused, BillingStates.Expired, BillingStates.Revoked,
            ),
        )
        assertEquals("live", AnswerPurposes.Live)
        assertEquals("prep", AnswerPurposes.Prep)
        assertEquals("manual", AnswerPurposes.Manual)
        assertTrue(JsonPrimitive(true).content == "true")
    }
}
