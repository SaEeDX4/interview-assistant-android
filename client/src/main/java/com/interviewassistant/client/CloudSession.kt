package com.interviewassistant.client

import com.interviewassistant.contracts.RealtimeCredential
import com.interviewassistant.contracts.StartSessionRequest
import com.interviewassistant.contracts.StartSessionResponse
import com.interviewassistant.core.languages.Loc
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mirror of `InterviewAssistant.Client/CloudSession.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port: idempotent start with the C#'s retry rule, the heartbeat loop on the injected
 * [TimeProvider], the metering state (-1 sentinels preserved), the warning/end events and the
 * graceful stop with its 5 s bound.
 *
 * C#→Kotlin adaptations (P2-PORT-NOTES.md): C# events become listener lists; the background loop
 * runs on an internal supervisor scope instead of a `Task`.
 */

/**
 * Client side of a server lease: heartbeats, the live transcription relay endpoint and graceful end. Audio goes
 * through the service's relay (authenticated with the user's own access token); the client never holds a provider
 * credential, and the relay closes as soon as the lease ends.
 */
class CloudSession private constructor(
    private val api: BackendClient,
    response: StartSessionResponse,
    private val time: TimeProvider,
    val language: String?,
    val targetId: UUID?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val seq = AtomicLong(0)
    private var cred: RealtimeCredential = response.realtime
    private val consecutiveFailures = AtomicInteger(0)
    private val refreshTokenFlag = AtomicInteger(0)

    val id: UUID = response.sessionId
    val heartbeatIntervalSeconds: Int = response.heartbeatIntervalSeconds.coerceIn(5, 120)

    var remainingSeconds: Int = response.remainingSeconds
        private set

    /** Plan allowance left in the billing period (-1 if the server did not say). */
    var periodRemainingSeconds: Int = response.periodRemainingSeconds
        private set

    /** Live time still available today under the daily safety cap (-1 if unknown). */
    var todayRemainingSeconds: Int = response.todayRemainingSeconds
        private set

    var active: Boolean = true
        private set

    var endReason: String? = null
        private set

    val credential: RealtimeCredential get() = cred

    /** Absolute address of this lease's transcription relay (with the interview language). */
    val relayEndpoint: URI get() = api.webSocketUri(cred.webSocketUrl, language)

    /** Handshake headers for the relay connection. */
    val relayHeaders: Map<String, String> get() = mapOf("X-Client-Version" to api.clientVersion)

    private val endedListeners = mutableListOf<(String) -> Unit>()
    private val remainingChangedListeners = mutableListOf<(Int) -> Unit>()
    private val warningListeners = mutableListOf<(String) -> Unit>()

    /** Raised once when the server ends the lease (allowance, max duration, revoked) or it is stopped. */
    fun addEndedListener(listener: (String) -> Unit) {
        synchronized(endedListeners) { endedListeners.add(listener) }
    }

    fun addRemainingChangedListener(listener: (Int) -> Unit) {
        synchronized(remainingChangedListeners) { remainingChangedListeners.add(listener) }
    }

    fun addWarningListener(listener: (String) -> Unit) {
        synchronized(warningListeners) { warningListeners.add(listener) }
    }

    private fun fireEnded(reason: String) = synchronized(endedListeners) { endedListeners.toList() }.forEach { it(reason) }
    private fun fireRemainingChanged(v: Int) = synchronized(remainingChangedListeners) { remainingChangedListeners.toList() }.forEach { it(v) }
    private fun fireWarning(w: String) = synchronized(warningListeners) { warningListeners.toList() }.forEach { it(w) }

    private fun startLoop() {
        loop = scope.launch {
            try {
                while (active) {
                    time.delay(heartbeatIntervalSeconds.seconds)
                    heartbeatOnceAsync()
                }
            } catch (e: CancellationException) {
                // stopped
            }
        }
    }

    /**
     * Bearer for the next relay connection: the user's access token while the lease is active, null once it ended
     * (the transcriber then stops instead of reconnecting). The relay itself re-checks the lease on every connection.
     */
    suspend fun nextConnectionCredentialAsync(): String? {
        if (!active) return null
        val force = refreshTokenFlag.getAndSet(0) == 1
        return api.accessTokenAsync(force)
    }

    /** The relay rejected the token (expired): the next connection uses a refreshed one. */
    fun forceTokenRefresh() {
        refreshTokenFlag.set(1)
    }

    /** One heartbeat (metering + lease state). Public for deterministic tests. */
    suspend fun heartbeatOnceAsync() {
        if (!active) return
        try {
            val hb = api.heartbeatAsync(id, seq.incrementAndGet())
            consecutiveFailures.set(0)
            remainingSeconds = hb.remainingSeconds
            if (hb.periodRemainingSeconds >= 0) periodRemainingSeconds = hb.periodRemainingSeconds
            if (hb.todayRemainingSeconds >= 0) todayRemainingSeconds = hb.todayRemainingSeconds
            fireRemainingChanged(hb.remainingSeconds)
            if (!hb.active) {
                end(hb.endReason ?: "ended")
                return
            }
            if (hb.remainingSeconds in 1..300) fireWarning(Loc.f("svc_minutes_left_warning", hb.remainingSeconds / 60 + 1))
        } catch (ex: BackendException) {
            if (ex.status == 404 || ex.status == 409 || ex.status == 403) {
                end(ex.code)
            } else {
                // Network blips: the server bills at most 2 intervals per heartbeat and expires the lease after 90 s.
                if (consecutiveFailures.incrementAndGet() >= 3) fireWarning(Loc.t("svc_unstable"))
            }
        }
    }

    private fun end(reason: String) {
        if (!active) return
        active = false
        endReason = reason
        fireEnded(reason)
    }

    suspend fun stopAsync() {
        scope.cancel()
        loop?.let { l ->
            try {
                l.join()
            } catch (e: CancellationException) {
                // already cancelled
            }
        }
        if (active) {
            try {
                kotlinx.coroutines.withTimeout(5.seconds) { api.stopSessionAsync(id) }
            } catch (e: Exception) {
                // server sweeper expires it (BackendException, timeout or cancellation are all swallowed, as in the C#)
                if (e !is BackendException && e !is CancellationException) throw e
            }
            end("stopped")
        }
    }

    /** The C#'s `DisposeAsync`: stops the lease (see [stopAsync]). */
    suspend fun disposeAsync() = stopAsync()

    companion object {
        suspend fun startAsync(
            api: BackendClient,
            deviceId: UUID,
            targetId: UUID?,
            language: String?,
            time: TimeProvider = TimeProvider.System,
            runLoop: Boolean = true,
        ): CloudSession {
            val key = UUID.randomUUID().toString().replace("-", "") // idempotency: the same key is reused across retries of this start
            var r: StartSessionResponse? = null
            var attempt = 0
            while (r == null) {
                try {
                    r = api.startSessionAsync(StartSessionRequest(deviceId, targetId, key, language, relay = true))
                } catch (ex: BackendException) {
                    if ((ex.status == 0 || ex.status == 502 || ex.status == 503) && ex.code != "maintenance" && attempt < 2) {
                        delay((500 * (attempt + 1)).toLong())
                        attempt++
                    } else {
                        throw ex
                    }
                }
            }
            val s = CloudSession(api, r, time, language, targetId)
            if (runLoop) s.startLoop()
            return s
        }
    }
}
