package com.interviewassistant.app.services

import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.providers.ChatProviderOptions
import com.interviewassistant.core.providers.OpenAiChatAnswerProvider
import com.interviewassistant.core.providers.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/** Mirror of `InterviewAssistant.App/Services/AnswerServiceProbe.cs`. */

/** Minimal round-trip to the answer model to validate key + measure first-token latency. */
object AnswerServiceProbe {
    data class Result(val ok: Boolean, val firstTokenMs: Long, val error: String?)

    suspend fun runAsync(key: () -> String?, model: String): Result = runAsync(key, model, null)

    /** [http] is the test seam for the provider's client (the C# probe uses the provider's default). */
    internal suspend fun runAsync(key: () -> String?, model: String, http: OkHttpClient?): Result {
        val options = ChatProviderOptions()
        options.model = if (model.isBlank()) "gpt-5.4-mini" else model
        val provider = OpenAiChatAnswerProvider(key, options, http)
        val started = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000
        return provider.use { p ->
            try {
                val first = withTimeout(15.seconds) {
                    p.streamAsync(listOf(ChatMessage("user", "Reply with the single word: ready")), 5).firstOrNull()
                }
                if (first != null) Result(true, elapsedMs(), null)
                else Result(false, -1, Loc.t("set_key_no_reply"))
            } catch (ex: ProviderException) {
                Result(false, -1, ex.message)
            } catch (ex: TimeoutCancellationException) {
                // The C#'s OperationCanceledException from its own 15 s CancellationTokenSource:
                // withTimeout's timeout exception is the same signal (DC-87 applies).
                Result(false, -1, Loc.t("set_key_timeout"))
            }
        }
    }
}
