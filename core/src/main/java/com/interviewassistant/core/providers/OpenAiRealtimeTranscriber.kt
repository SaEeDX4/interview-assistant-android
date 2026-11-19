package com.interviewassistant.core.providers

import com.interviewassistant.core.diagnostics.ExponentialBackoff
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Mirror of `InterviewAssistant.Core/Providers/OpenAiRealtimeTranscriber.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations (DC-38/DC-40): `ClientWebSocket` becomes an OkHttp WebSocket (ping interval =
 * the C# `KeepAliveInterval`); the bounded `Channel<byte[]>` with DropOldest becomes a kotlinx channel
 * with the same capacity and overflow policy; connect failure details (HTTP status) come from the
 * listener's `onFailure` response where C# reads `WebSocketException`/`HttpStatusCode`. The interface's
 * `startAsync()` takes no caller token (P2 contract), so the loop runs until [stopAsync] where C# also
 * links the caller's token.
 */
class RealtimeTranscriberOptions {
    var endpoint: String = "wss://api.openai.com/v1/realtime?intent=transcription"

    /**
     * Realtime transcription model (OpenAI realtime transcription sessions accept gpt-4o-transcribe / gpt-4o-mini-transcribe).
     * If the account/session rejects it, the transcriber falls back to FallbackModels in order.
     */
    var model: String = "gpt-4o-transcribe"
    var fallbackModels: Array<String> = arrayOf("gpt-4o-mini-transcribe")

    /** ISO-639-1 code, or empty/"auto" to let the model detect the spoken language. */
    var language: String = ""

    /** Vocabulary prompt that biases recognition toward domain terms. */
    var prompt: String = "Job interview. Professional business and technology vocabulary."
    var vadThreshold: Double = 0.5
    var vadPrefixPaddingMs: Int = 300

    /** Server VAD segment silence. Short so segments arrive quickly; TurnDetector merges segments into a turn. */
    var vadSilenceMs: Int = 400

    /** "ga" (current Realtime API) or "beta" (legacy transcription_session.update). "auto" tries GA then falls back. */
    var protocol: String = "auto"

    /** Proactively renew the session when idle after this age (sessions have a maximum lifetime). */
    var sessionRotateAfter: Duration = Duration.ofMinutes(25)

    /**
     * The credential is an ephemeral client secret minted by the server with the full session configuration (model,
     * prompt, VAD) baked in: the client sends no session.update, always uses the GA protocol, and treats a model
     * problem as fatal (the server's fallback routing decides the model, not the client).
     */
    var serverConfiguredSession: Boolean = false

    /** Extra request headers for the WebSocket handshake (e.g. client version and correlation id for the relay). */
    val headers: MutableMap<String, String> = mutableMapOf()

    /** Server-configured sessions: the bearer <redacted> rejected (401) once; the next credential should be refreshed. */
    var credentialRejected: (() -> Unit)? = null

    /** Server-configured sessions: the service refused the connection with this HTTP status (402/403/404/409/426/429). */
    var connectionRefused: ((Int) -> Unit)? = null
}

/**
 * OpenAI Realtime transcription over WebSocket. Owns its connection lifecycle: connect, configure session,
 * stream audio from a bounded queue (drop-oldest backpressure), parse events, and reconnect with bounded
 * exponential backoff. Never throws into callers after startAsync; problems surface as status changes.
 */
class OpenAiRealtimeTranscriber(
    private val credentialProvider: suspend () -> String?,
    options: RealtimeTranscriberOptions? = null,
) : ITranscriber {

    /** The credential is fetched synchronously per (re)connection from a plain API-key provider. */
    constructor(apiKeyProvider: () -> String?, options: RealtimeTranscriberOptions? = null) :
        this({ apiKeyProvider() }, options)

    private val opt: RealtimeTranscriberOptions = options ?: RealtimeTranscriberOptions()
    private val audio = Channel<ByteArray>(30, BufferOverflow.DROP_OLDEST)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // ClientWebSocket KeepAliveInterval
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var useBeta: Boolean = !opt.serverConfiguredSession && opt.protocol.equals("beta", ignoreCase = true)
    private var modelIndex: Int = 0 // 0 = Options.Model, then FallbackModels

    val activeModel: String
        get() = if (modelIndex == 0) opt.model else opt.fallbackModels[minOf(modelIndex - 1, opt.fallbackModels.size - 1)]

    @Volatile
    private var inSpeech: Boolean = false

    override val speechStartedListeners: MutableList<() -> Unit> = mutableListOf()
    override val speechStoppedListeners: MutableList<() -> Unit> = mutableListOf()
    override var lastStoppedItemId: String? = null
        private set
    override val partialTranscriptListeners: MutableList<(String, String) -> Unit> = mutableListOf()
    override val segmentCompletedListeners: MutableList<(String, String) -> Unit> = mutableListOf()
    override val statusChangedListeners: MutableList<(ConnectionStatus, String?) -> Unit> = mutableListOf()

    /** The C# `Diagnostic` event (not part of the ITranscriber contract). */
    val diagnosticListeners: MutableList<(String) -> Unit> = mutableListOf()

    override var status: ConnectionStatus = ConnectionStatus.Disconnected
        private set
    var reconnectCount: Int = 0
        private set
    var audioBytesSent: Long = 0
        private set

    /**
     * Starts the connection loop, or restarts it when the previous one has ended (its job was cancelled, e.g. by a
     * resume, or it stopped because the live session ended). A running loop is left alone.
     */
    override suspend fun startAsync() {
        val existing = loopJob
        if (existing != null && existing.isActive) return
        if (existing != null) {
            existing.cancel()
            existing.join()
        }
        loopJob = scope.launch { runLoop() }
    }

    override suspend fun stopAsync() {
        val job = loopJob
        loopJob = null
        job?.cancel()
        job?.join()
        while (audio.tryReceive().isSuccess) {
            // drop buffered audio (privacy + no stale audio on restart)
        }
        setStatus(ConnectionStatus.Disconnected, null)
    }

    override fun sendAudio(pcm16Frame: ByteArray) {
        audio.trySend(pcm16Frame)
    }

    private var authRetried: Boolean = false
    private var lastServerError: String? = null
    private var fatal: Boolean = false
    private var rotateRequested: Boolean = false
    private var switchProtocol: Boolean = false

    private suspend fun runLoop() {
        val backoff = ExponentialBackoff()
        while (currentCoroutineContext().isActive) {
            val key: String?
            try {
                key = credentialProvider()
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                // Credential service temporarily unreachable: back off and retry (never spin).
                reconnectCount++
                val wait = backoff.next()
                setStatus(ConnectionStatus.Reconnecting, "${e.message} — retry in ${formatSeconds(wait)}s")
                try {
                    delay(wait.toMillis())
                } catch (e2: CancellationException) {
                    return
                }
                continue
            }
            if (key.isNullOrBlank()) {
                setStatus(ConnectionStatus.Failed, if (opt.serverConfiguredSession) "Live session not active" else "No API key configured")
                return
            }
            setStatus(if (reconnectCount == 0 && backoff.attempt == 0) ConnectionStatus.Connecting else ConnectionStatus.Reconnecting, null)

            val session = Session()
            val requestBuilder = Request.Builder().url(opt.endpoint)
                .header("Authorization", "Bearer $key")
            if (useBeta) requestBuilder.header("OpenAI-Beta", "realtime=v1")
            for ((name, value) in opt.headers) requestBuilder.header(name, value)
            val ws = client.newWebSocket(requestBuilder.build(), SessionListener(session))
            session.webSocket = ws

            var failure: String? = null
            var fatalNow = false
            val opened = withTimeoutOrNull(10_000) { session.connected.await() } == true
            if (!opened && session.failure == null && session.httpStatus == 0) {
                // The 10 s connect watchdog fired (the C# connect token): a generic connect failure.
                session.failure = ConnectFailure(Exception("The operation was canceled."), 0)
                ws.cancel()
            }
            if (opened) {
                backoff.reset()
                authRetried = false
                val started = Instant.now()
                val sendJob = scope.launch { sendLoop(ws, started, session) }
                session.done.await()
                sendJob.cancel()
                sendJob.join()
                if (session.open) ws.close(1000, "bye") // closing best-effort
                failure = session.failure?.error?.message ?: lastServerError
                fatalNow = fatal
            } else {
                // Connect failure (the C# WebSocketException path).
                val connectFailure = session.failure ?: ConnectFailure(Exception("connection failed"), session.httpStatus)
                failure = connectFailure.message
                fatalNow = (failure ?: "").contains("401") || (failure ?: "").contains("403")
                val status = session.httpStatus
                if (opt.serverConfiguredSession && status != 0) {
                    if (status == 401 && !authRetried) {
                        // An access token that expired a moment ago: refresh it once before giving up.
                        authRetried = true
                        fatalNow = false
                        failure = "401 — refreshing the sign-in"
                        opt.credentialRejected?.invoke()
                    } else if (status == 402 || status == 403 || status == 404 || status == 409 || status == 426 || status == 429) {
                        // The service refused the live session (ended, out of minutes, device removed, update required,
                        // too many connections): the app decides; never retry blindly while the lease is billed.
                        failure = "$status — live session refused"
                        fatalNow = status == 403 || status == 426 || status == 429
                        opt.connectionRefused?.invoke(status)
                    }
                }
                ws.cancel()
            }
            if (!currentCoroutineContext().isActive) return
            if (fatalNow) {
                setStatus(ConnectionStatus.Failed, failure ?: "Authentication failed")
                return
            }
            if (rotateRequested) {
                rotateRequested = false
                continue // planned renewal, reconnect immediately
            }
            reconnectCount++
            val wait = backoff.next()
            setStatus(ConnectionStatus.Reconnecting, "${failure ?: "connection closed"} — retry in ${formatSeconds(wait)}s")
            try {
                delay(wait.toMillis())
            } catch (e: CancellationException) {
                return
            }
        }
    }

    private class ConnectFailure(val error: Exception, val status: Int) {
        val message: String = describe(error, status)
    }

    private suspend fun sendLoop(ws: WebSocket, started: Instant, session: Session) {
        val sb = StringBuilder(16 * 1024)
        while (currentCoroutineContext().isActive && session.open) {
            val frame = withTimeoutOrNull(5000) { audio.receive() } ?: ByteArray(0)
            if (frame.isNotEmpty()) {
                sb.setLength(0)
                sb.append("{\"type\":\"input_audio_buffer.append\",\"audio\":\"")
                    .append(Base64.getEncoder().encodeToString(frame)).append("\"}")
                if (!ws.send(sb.toString())) return
                audioBytesSent += frame.size
            }
            if (!inSpeech && Duration.between(started, Instant.now()) > opt.sessionRotateAfter) {
                rotateRequested = true
                fireDiagnostic("Rotating realtime session (age limit) during silence")
                return
            }
        }
    }

    private inner class Session {
        val connected = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Unit>()

        @Volatile
        var open: Boolean = false

        @Volatile
        var failure: ConnectFailure? = null

        @Volatile
        var httpStatus: Int = 0
        var webSocket: WebSocket? = null
        var connectedAt: Instant = Instant.now()
    }

    private inner class SessionListener(private val session: Session) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            session.open = true
            session.connectedAt = Instant.now()
            lastServerError = null
            fatal = false
            if (!opt.serverConfiguredSession) webSocket.send(buildSessionUpdate())
            setStatus(ConnectionStatus.Connected, activeModel + if (useBeta) " (beta protocol)" else "")
            session.connected.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onBytes(text.toByteArray(Charsets.UTF_8))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onBytes(bytes.toByteArray())
        }

        private fun onBytes(bytes: ByteArray) {
            if (bytes.size > 4 * 1024 * 1024) {
                session.failure = ConnectFailure(Exception("Oversized realtime message"), 0)
                session.done.complete(Unit)
                return
            }
            handleEvent(bytes, session.connectedAt)
            if (fatal || switchProtocol) {
                switchProtocol = false
                session.done.complete(Unit)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            session.open = false
            if (lastServerError == null) lastServerError = "server closed: $code $reason"
            if (!session.connected.isCompleted) session.connected.complete(false)
            session.done.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            session.open = false
            val status = response?.code ?: 0
            session.httpStatus = status
            session.failure = ConnectFailure(t as? Exception ?: Exception(t.message, t), status)
            if (!session.connected.isCompleted) session.connected.complete(false)
            session.done.complete(Unit)
        }
    }

    internal fun handleEvent(json: ByteArray, connectedAt: Instant) {
        val node: JsonObject
        try {
            node = Json.parseToJsonElement(json.toString(Charsets.UTF_8)).jsonObject
        } catch (e: SerializationException) {
            return
        } catch (e: IllegalStateException) {
            return
        }
        fun str(key: String): String? = node[key]?.jsonPrimitive?.contentOrNull
        when (str("type") ?: "") {
            "input_audio_buffer.speech_started" -> {
                inSpeech = true
                for (l in speechStartedListeners.toList()) l()
            }
            "input_audio_buffer.speech_stopped" -> {
                inSpeech = false
                lastStoppedItemId = str("item_id")
                for (l in speechStoppedListeners.toList()) l()
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val id = str("item_id") ?: ""
                val delta = str("delta") ?: ""
                for (l in partialTranscriptListeners.toList()) l(id, delta)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val id = str("item_id") ?: ""
                val transcript = str("transcript") ?: ""
                for (l in segmentCompletedListeners.toList()) l(id, transcript)
            }
            "conversation.item.input_audio_transcription.failed" -> {
                fireDiagnostic("Segment transcription failed: " + (node["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: ""))
                val id = str("item_id") ?: ""
                for (l in segmentCompletedListeners.toList()) l(id, "")
            }
            "error" -> {
                val error = node["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown error"
                lastServerError = if (code.isEmpty()) message else "$code: $message"
                fireDiagnostic("Realtime error: " + lastServerError)
                val early = Duration.between(connectedAt, Instant.now()).toMillis() < 5000
                val modelProblem = code == "model_not_found" || (early && message.contains("model", ignoreCase = true))
                if (code == "invalid_api_key" || code == "insufficient_quota") {
                    fatal = true
                } else if (modelProblem && !opt.serverConfiguredSession && modelIndex < opt.fallbackModels.size) {
                    modelIndex++
                    switchProtocol = true
                    rotateRequested = true
                    fireDiagnostic("Transcription model rejected; falling back to $activeModel")
                } else if (modelProblem) {
                    fatal = true
                } else if (!useBeta && !opt.serverConfiguredSession && opt.protocol == "auto" &&
                    Duration.between(connectedAt, Instant.now()).toMillis() < 5000 &&
                    (message.contains("session", ignoreCase = true) || code.contains("param", ignoreCase = true) || code.contains("unknown", ignoreCase = true))
                ) {
                    // Session config rejected shortly after connect on GA protocol -> try legacy beta protocol once.
                    useBeta = true
                    switchProtocol = true
                    rotateRequested = true
                    fireDiagnostic("Switching to legacy realtime transcription protocol")
                }
            }
        }
    }

    internal fun buildSessionUpdate(): String {
        val turnDetection = buildJsonObject {
            put("type", "server_vad")
            put("threshold", opt.vadThreshold)
            put("prefix_padding_ms", opt.vadPrefixPaddingMs)
            put("silence_duration_ms", opt.vadSilenceMs)
        }
        val transcription = buildJsonObject {
            put("model", activeModel)
            put("prompt", opt.prompt)
            if (opt.language.isNotBlank() && opt.language != "auto") put("language", opt.language)
        }
        val msg = if (useBeta) {
            buildJsonObject {
                put("type", "transcription_session.update")
                putJsonObject("session") {
                    put("input_audio_format", "pcm16")
                    put("input_audio_transcription", transcription)
                    put("turn_detection", turnDetection)
                }
            }
        } else {
            buildJsonObject {
                put("type", "session.update")
                putJsonObject("session") {
                    put("type", "transcription")
                    putJsonObject("audio") {
                        putJsonObject("input") {
                            putJsonObject("format") {
                                put("type", "audio/pcm")
                                put("rate", 24000)
                            }
                            put("transcription", transcription)
                            put("turn_detection", turnDetection)
                        }
                    }
                }
            }
        }
        return msg.toString()
    }

    private fun setStatus(s: ConnectionStatus, detail: String?) {
        status = s
        for (l in statusChangedListeners.toList()) l(s, detail)
    }

    private fun fireDiagnostic(message: String) {
        for (l in diagnosticListeners.toList()) l(message)
    }

    override suspend fun disposeAsync() {
        stopAsync()
    }

    companion object {
        /** The C# `DescribeConnectError`: known HTTP refusals get an actionable text. */
        private fun describe(ex: Exception, status: Int): String {
            if (status == 401 || (ex.message ?: "").contains("401")) return "401 Unauthorized — check the API key"
            if (status == 403 || (ex.message ?: "").contains("403")) return "403 Forbidden — key lacks realtime access"
            if (status == 429 || (ex.message ?: "").contains("429")) return "429 rate limited"
            return ex.message ?: "connection failed"
        }

        /** C# `{wait.TotalSeconds:0}` — whole seconds, no decimals. */
        private fun formatSeconds(wait: Duration): String = Math.round(wait.toMillis() / 1000.0).toString()
    }
}
