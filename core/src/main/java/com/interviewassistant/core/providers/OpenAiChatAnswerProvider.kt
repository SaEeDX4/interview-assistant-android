package com.interviewassistant.core.providers

import com.interviewassistant.core.ingestion.dotNetRegex
import com.interviewassistant.core.intelligence.ChatMessage
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.AsyncTimeout
import okio.buffer

/**
 * Mirror of `InterviewAssistant.Core/Providers/OpenAiChatAnswerProvider.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations (DC-38/DC-39): the BCL `HttpClient` + `HttpMessageHandler` seam becomes OkHttp
 * (an injected [OkHttpClient] — e.g. one carrying a test interceptor — replaces the handler). The C#
 * linked-token timeouts become: a coroutine [withTimeout] around the enqueued call for the first-token
 * budget, and an Okio [AsyncTimeout] wrapped around the response source, re-armed before every read
 * (first-token budget until the first delta, then the stream-idle budget per chunk), whose `timedOut`
 * cancels the call and closes the source — the analog of the C# cancellation tokens cancelling
 * `StreamReader.ReadLineAsync`.
 */
class ChatProviderOptions {
    var endpoint: String = "https://api.openai.com/v1/chat/completions"

    /** Default: gpt-5.4-mini with reasoning off (OpenAI, Mar 2026: ~0.7 s first token, ~175 tok/s). Configurable in Settings. */
    var model: String = "gpt-5.4-mini"

    /** Used automatically if the configured model is rejected as unknown/unavailable for this account. */
    var fallbackModel: String = "gpt-4.1-mini"
    var temperature: Double = 0.4
    var firstTokenTimeout: Duration = Duration.ofSeconds(8)

    /** Longest silence between streamed chunks after the first token before the stream is treated as stalled. */
    var streamIdleTimeout: Duration = Duration.ofSeconds(20)

    /** Upper bound for one streamed answer (bullets are short; this only guards against a stuck upstream). */
    var maxStreamDuration: Duration = Duration.ofSeconds(90)

    /** "auto" picks the lowest-latency effort the model family accepts (gpt-5.x: none; gpt-5/5-mini/o*: minimal). */
    var reasoningEffort: String = "auto"
}

/**
 * Streaming Chat Completions client (server-sent events). One HTTP client for the app lifetime (connection
 * reuse keeps TLS warm, which matters for first-token latency). Maps HTTP failures to ProviderErrorKind.
 */
class OpenAiChatAnswerProvider(
    private val apiKeyProvider: () -> String?,
    options: ChatProviderOptions? = null,
    client: OkHttpClient? = null,
) : IAnswerProvider, Closeable {

    val options: ChatProviderOptions = options ?: ChatProviderOptions()

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS) // SocketsHttpHandler.ConnectTimeout; pooled connections kept 10 min (PooledConnectionLifetime)
        .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
        .readTimeout(0, TimeUnit.MILLISECONDS) // no socket read timeout: the per-request budgets below govern (HttpClient.Timeout = Infinite)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override val name: String get() = "OpenAI " + activeModel
    var requestCount: Int = 0
        private set
    var approxInputChars: Long = 0
        private set
    var approxOutputChars: Long = 0
        private set

    private var activeModelField: String? = null
    private var omitReasoning: Boolean = false

    /** The model actually in use (differs from Options.Model after an automatic fallback). */
    val activeModel: String get() = activeModelField ?: options.model

    internal fun buildBody(messages: List<ChatMessage>, maxTokens: Int): String {
        val model = activeModel
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("store", false) // never retain interview content for the provider's dashboards/evals
            putJsonArray("messages") {
                for (m in messages) addJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                }
            }
            if (isReasoningModel(model)) {
                if (!omitReasoning) put("reasoning_effort", if (options.reasoningEffort == "auto") autoEffort(model) else options.reasoningEffort)
                put("max_completion_tokens", maxTokens + 600) // reasoning tokens count toward the limit
            } else {
                put("temperature", options.temperature)
                put("max_completion_tokens", maxTokens)
            }
        }
        return body.toString()
    }

    override fun streamAsync(messages: List<ChatMessage>, maxTokens: Int): Flow<String> = flow {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) throw ProviderException(ProviderErrorKind.InvalidApiKey, "No API key configured")

        val requestStarted = System.nanoTime()

        // Up to 3 attempts, only for configuration rejections that are fixable automatically (never mid-stream):
        //  1) unsupported reasoning_effort -> resend without it; 2) unknown/unavailable model -> fallback model.
        var response: Response
        var attempt = 0
        while (true) {
            val json = buildBody(messages, maxTokens)
            approxInputChars += json.length
            requestCount++
            val request = Request.Builder()
                .url(options.endpoint)
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = http.newCall(request)
            val resp: Response
            try {
                resp = withTimeout(options.firstTokenTimeout.toMillis()) {
                    suspendCancellableCoroutine { cont ->
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (cont.isActive) cont.resumeWith(Result.failure(e))
                            }

                            override fun onResponse(call: Call, response: Response) {
                                if (cont.isActive) cont.resume(response, null) else response.close()
                            }
                        })
                        cont.invokeOnCancellation { call.cancel() }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                call.cancel()
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                throw ProviderException(ProviderErrorKind.Timeout, "Answer service timed out")
            } catch (e: IOException) {
                throw ProviderException(ProviderErrorKind.Network, "Network error: " + e.message, e)
            }
            if (resp.isSuccessful) {
                response = resp
                break
            }

            val code = resp.code
            val err = safeRead(resp)
            val mapped = mapError(code, err)
            resp.close()
            attempt++
            if (attempt < 3 && mapped.kind == ProviderErrorKind.BadRequest) {
                if (!omitReasoning && err.contains("reasoning", ignoreCase = true)) {
                    omitReasoning = true
                    continue
                }
                if (activeModel != options.fallbackModel && options.fallbackModel.isNotEmpty() &&
                    (err.contains("model", ignoreCase = true) || code == 404)
                ) {
                    activeModelField = options.fallbackModel
                    omitReasoning = false
                    continue
                }
            }
            throw mapped
        }

        response.use { resp ->
            val rawSource = resp.body!!.source()
            var timedOut = false
            val timeout = object : AsyncTimeout() {
                override fun timedOut() {
                    timedOut = true
                    // Close the RAW source, never the timeout-wrapped one: the wrapper's
                    // close() re-enters this AsyncTimeout and throws "Unbalanced enter/exit"
                    // while a read is in flight, which would leave the stalled read blocked
                    // forever (DC-58, reproduced locally against Okio 3.6). Closing the raw
                    // source breaks a real socket read and releases a stalled one alike.
                    try {
                        rawSource.close()
                    } catch (_: Exception) {
                    }
                }
            }
            val source = timeout.source(rawSource)
            val reader = source.buffer()
            val streamStarted = System.nanoTime()
            var gotFirst = false
            while (true) {
                val line: String?
                try {
                    if (gotFirst) {
                        if (Duration.ofNanos(System.nanoTime() - streamStarted) > options.maxStreamDuration) {
                            throw ProviderException(ProviderErrorKind.Timeout, "The answer stream took too long")
                        }
                        timeout.deadline(options.streamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS) // re-armed for every chunk
                    } else {
                        val elapsedMs = (System.nanoTime() - requestStarted) / 1_000_000
                        val remaining = options.firstTokenTimeout.toMillis() - elapsedMs
                        timeout.deadline(if (remaining < 1) 1 else remaining, TimeUnit.MILLISECONDS)
                    }
                    line = reader.readUtf8Line()
                } catch (e: InterruptedIOException) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                    throw ProviderException(ProviderErrorKind.Timeout, if (gotFirst) "The answer stream stalled" else "No answer tokens received in time")
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                    if (timedOut) {
                        throw ProviderException(ProviderErrorKind.Timeout, if (gotFirst) "The answer stream stalled" else "No answer tokens received in time")
                    }
                    throw ProviderException(ProviderErrorKind.Network, "Stream interrupted: " + e.message, e)
                }
                if (line == null) break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val delta = parseDelta(data)
                if (delta.isNullOrEmpty()) continue
                gotFirst = true
                approxOutputChars += delta.length
                emit(delta)
            }
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        fun isReasoningModel(model: String): Boolean =
            model.startsWith("gpt-5", ignoreCase = true) || model.startsWith("o1", ignoreCase = true) ||
                model.startsWith("o3", ignoreCase = true) || model.startsWith("o4", ignoreCase = true)

        /** Lowest-latency reasoning effort accepted by the model family. */
        fun autoEffort(model: String): String {
            val m = model.lowercase()
            // gpt-5.1 and later (gpt-5.4-mini, ...) accept "none"; the original gpt-5 family and o-series use "minimal"/"low".
            if (dotNetRegex("^gpt-5\\.\\d").containsMatchIn(m)) return "none"
            if (m.startsWith("gpt-5")) return "minimal"
            return "low"
        }

        internal fun parseDelta(data: String): String? {
            return try {
                val node = Json.parseToJsonElement(data)
                node.jsonObject["choices"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalStateException) {
                null
            } catch (e: IndexOutOfBoundsException) {
                null
            }
        }

        internal fun mapError(code: Int, body: String): ProviderException {
            var message = body
            try {
                val parsed = Json.parseToJsonElement(body)
                if (parsed is JsonObject) {
                    val m = parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    if (m != null) message = m
                }
            } catch (e: SerializationException) {
            }
            if (message.length > 300) message = message.substring(0, 300)
            return when {
                code == 401 || code == 403 -> ProviderException(ProviderErrorKind.InvalidApiKey, "API key rejected: " + message)
                code == 429 -> ProviderException(ProviderErrorKind.RateLimited, "Rate limited: " + message)
                code == 400 || code == 404 -> ProviderException(ProviderErrorKind.BadRequest, "Request rejected: " + message)
                code >= 500 -> ProviderException(ProviderErrorKind.ServerError, "Server error $code")
                else -> ProviderException(ProviderErrorKind.Unknown, "HTTP $code: $message")
            }
        }

        private fun safeRead(resp: Response): String {
            return try {
                resp.body?.string() ?: ""
            } catch (e: Exception) {
                "" // error body is diagnostic only
            }
        }
    }
}
