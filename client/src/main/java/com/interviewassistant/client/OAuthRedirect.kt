package com.interviewassistant.client

import java.net.URI
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Android equivalent of `InterviewAssistant.Client/LoopbackRedirect.cs` (TRANSFER-MAP §2/§8 D5).
 *
 * On Windows a one-shot loopback HTTP listener on 127.0.0.1 (RFC 8252 §7.3) receives the PKCE code.
 * Android has no viable loopback listener for a browser redirect, so the platform layer (see `:app`
 * Platform: App Link / custom-scheme receiver) delivers the redirect URI to an [OAuthRedirectReceiver]
 * instead. Everything the C# enforces around that delivery is preserved here, platform-independently:
 *
 * - only the `/callback` path with the expected `s` state is accepted ([RedirectUriParser.evaluate]);
 *   anything else is ignored and the wait continues (the C# answers 404 and keeps listening);
 * - an `error`/`error_description` parameter aborts the wait with `AuthException("browser_error", …)`;
 * - a matching callback without a `code` is ignored and the wait continues (the C# answers 400);
 * - the wait is time-bounded: on timeout it throws [TimeoutException] with the C#'s message, and a
 *   closed/cancelled receiver throws [TimeoutException] "Sign-in was cancelled." (the C#'s
 *   ObjectDisposedException path);
 * - caller cancellation propagates as coroutine cancellation (the C#'s CancellationToken path).
 *
 * The query parsing is a direct port of the C#'s `ParseQuery` (first value wins, `+` means space,
 * percent-decoding afterwards), and the page texts the C# serves to the browser after a callback
 * ([RedirectOutcome.browserMessageKey]) are the same localization keys — on Android the platform layer
 * shows them after delivering the redirect.
 */
interface OAuthRedirectReceiver : AutoCloseable {
    /** The `s` value this receiver waits for (the C#'s `State`). */
    val state: String

    /** The full redirect URI to register with the auth server (carries `?s=<state>`, as on Windows). */
    val redirectUri: String

    /**
     * Waits for the browser to deliver the redirect; returns the authorization code.
     * Throws [TimeoutException] on timeout or when the receiver is closed first, and
     * [AuthException] with code `browser_error` when the redirect carries an error.
     */
    suspend fun waitForCode(timeout: Duration): String

    override fun close()
}

/** What a delivered redirect URI means, per the C#'s `HandleAsync` decision table. */
sealed interface RedirectOutcome {
    /** Not our callback (wrong path or state): the C# answers 404 and keeps waiting. */
    data object NotOurs : RedirectOutcome

    /** Our callback but no code and no error: the C# answers 400 and keeps waiting. */
    data object MissingCode : RedirectOutcome

    /** Our callback with an authorization code. */
    data class Code(val code: String) : RedirectOutcome

    /** Our callback with an error: the wait aborts with `AuthException("browser_error", error)`. */
    data class Error(val error: String) : RedirectOutcome

    /** Localization key of the page text the C# serves the browser for this outcome (null = no page). */
    val browserMessageKey: String?
        get() = when (this) {
            is Code -> "browser_signed_in"
            is Error -> "browser_sign_in_failed"
            MissingCode -> "browser_missing_code"
            NotOurs -> null
        }
}

/** The platform-independent core of `LoopbackRedirect.HandleAsync` + `ParseQuery`. */
object RedirectUriParser {
    /** Port of `LoopbackRedirect.ParseQuery`: first value wins; `+` decodes to space before percent-decoding. */
    fun parseQuery(query: String): Map<String, String> {
        val d = LinkedHashMap<String, String>()
        for (part in query.trimStart('?').split('&')) {
            if (part.isEmpty()) continue
            val kv = part.split('=', limit = 2)
            val k = UriEscape.unescapeDataString(kv[0].replace('+', ' '))
            if (!d.containsKey(k)) {
                d[k] = if (kv.size > 1) UriEscape.unescapeDataString(kv[1].replace('+', ' ')) else ""
            }
        }
        return d
    }

    /**
     * Decides what a delivered redirect [uri] means for the receiver waiting on [expectedState] —
     * the C#'s checks in the C#'s order: parseable absolute URI, path `/callback` (trailing slash
     * trimmed), state match, error, code.
     */
    fun evaluate(uri: String, expectedState: String): RedirectOutcome {
        val parsed = try {
            URI(uri)
        } catch (e: Exception) {
            return RedirectOutcome.NotOurs
        }
        if (parsed.path == null || parsed.path.trimEnd('/') != "/callback") return RedirectOutcome.NotOurs
        val q = parseQuery(parsed.rawQuery ?: "")
        if (q["s"] != expectedState) return RedirectOutcome.NotOurs
        val error = q["error_description"] ?: q["error"]
        if (!error.isNullOrEmpty()) return RedirectOutcome.Error(error)
        val code = q["code"]
        if (code.isNullOrEmpty()) return RedirectOutcome.MissingCode
        return RedirectOutcome.Code(code)
    }
}
