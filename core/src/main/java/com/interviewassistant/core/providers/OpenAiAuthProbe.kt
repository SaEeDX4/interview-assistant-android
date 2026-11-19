package com.interviewassistant.core.providers

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Mirror of `InterviewAssistant.Core/Providers/OpenAiAuthProbe.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * C#→Kotlin adaptations: the BCL `HttpMessageHandler` test seam becomes an injected [OkHttpClient]
 * (DC-38: OkHttp is the Android counterpart of the BCL HTTP stack); `TaskCanceledException` from the
 * 10 s `HttpClient.Timeout` becomes the coroutine timeout below, other transport failures are
 * [IOException]s where C# catches `HttpRequestException`.
 */
object OpenAiAuthProbe {

    data class Result(val ok: Boolean, val kind: ProviderErrorKind?, val detail: String, val ms: Long)

    /** Validates an API key with GET /v1/models — costs no tokens. */
    suspend fun check(key: String?, client: OkHttpClient? = null): Result {
        if (key.isNullOrBlank()) return Result(false, ProviderErrorKind.InvalidApiKey, "No API key saved — open Settings and paste it.", 0)
        val http = client ?: OkHttpClient()
        val started = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer " + key.trim())
            .get()
            .build()
        val call = http.newCall(request)
        return try {
            val response = withTimeout(10_000) {
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
            response.use { resp ->
                when (resp.code) {
                    200 -> Result(true, null, "Key accepted (${elapsedMs()} ms)", elapsedMs())
                    401 -> Result(false, ProviderErrorKind.InvalidApiKey, "Key rejected (401) — copy a fresh key from platform.openai.com.", elapsedMs())
                    403 -> Result(false, ProviderErrorKind.InvalidApiKey, "Key not permitted (403) — check project/organisation permissions.", elapsedMs())
                    429 -> Result(false, ProviderErrorKind.RateLimited, "Rate limited or out of quota (429) — check billing.", elapsedMs())
                    else -> Result(false, ProviderErrorKind.ServerError, "Unexpected HTTP ${resp.code}", elapsedMs())
                }
            }
        } catch (e: TimeoutCancellationException) {
            call.cancel()
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            Result(false, ProviderErrorKind.Timeout, "Timed out reaching api.openai.com", elapsedMs())
        } catch (e: IOException) {
            Result(false, ProviderErrorKind.Network, "Cannot reach api.openai.com: " + e.message, elapsedMs())
        }
    }
}
