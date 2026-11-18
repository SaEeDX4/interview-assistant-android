package com.interviewassistant.client

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.IDataProtector
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Mirror of `InterviewAssistant.Client/Auth.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port: [SupabaseAuth] (PKCE browser flow, magic link, e-mail+password, sign-up, enumeration-safe
 * password recovery, transparent refresh with the same sign-out codes), [ProtectedFileTokenStore] and
 * the [ITokenStore] contract.
 *
 * C#→Kotlin adaptations (P2-PORT-NOTES.md): `HttpClient` becomes the injected [OkHttpClient] (tests fake
 * it with an OkHttp interceptor, the exact analogue of the C# tests' `HttpMessageHandler` stub);
 * `CancellationToken` parameters become coroutine cancellation; C# events become listener lists; the
 * loopback listener the C# constructs internally is supplied by the caller as an [OAuthRedirectReceiver]
 * (§8 D5 — the platform layer owns the redirect surface on Android).
 */

class AuthException(val code: String, message: String) : Exception(message)

@Serializable
data class AuthSession(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("ExpiresAt") @Serializable(with = OffsetDateTimeIsoSerializer::class) val expiresAt: OffsetDateTime,
    @SerialName("UserId") val userId: String,
    @SerialName("Email") val email: String,
)

/**
 * The token store's on-disk format: the C# serializes [AuthSession] with the *default* System.Text.Json
 * options (PascalCase names via @SerialName above, `DateTimeOffset` in ISO-8601 with its offset).
 */
internal object OffsetDateTimeIsoSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("System.DateTimeOffset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime) =
        encoder.encodeString(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

    override fun deserialize(decoder: Decoder): OffsetDateTime =
        OffsetDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

interface ITokenStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    fun clear()
}

/** Refresh/access tokens encrypted with the supplied protector (DPAPI on Windows, Keystore on Android). Never logged. */
class ProtectedFileTokenStore(private val path: String, private val protector: IDataProtector) : ITokenStore {
    override fun load(): AuthSession? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            storeJson.decodeFromString(AuthSession.serializer(), protector.unprotect(file.readBytes()).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // IOException, SerializationException, cryptographic failure, access denied — all mean "no usable session".
            if (e is IOException || e is SerializationException || e is java.security.GeneralSecurityException ||
                e is SecurityException || e is IllegalArgumentException
            ) {
                null
            } else {
                throw e
            }
        }
    }

    override fun save(session: AuthSession) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File(path + ".tmp")
        tmp.writeBytes(protector.protect(storeJson.encodeToString(AuthSession.serializer(), session).toByteArray(Charsets.UTF_8)))
        moveOverwrite(tmp, file)
    }

    override fun clear() {
        try {
            File(path).delete()
        } catch (e: SecurityException) {
            // mirrors the C#'s swallowed IOException on delete
        }
    }

    private fun moveOverwrite(from: File, to: File) {
        try {
            java.nio.file.Files.move(
                from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val storeJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Supabase Auth with PKCE through the system browser (OAuth providers or e-mail magic link). The app never sees a
 * password for those flows. Tokens are refreshed transparently and stored only through [ITokenStore].
 */
class SupabaseAuth(
    private val http: OkHttpClient,
    private val opt: CloudOptions,
    private val store: ITokenStore,
    private val time: TimeProvider = TimeProvider.System,
) {
    private val refreshLock = Mutex()
    private var currentSession: AuthSession? = store.load()

    private val sessionChangedListeners = mutableListOf<(AuthSession?) -> Unit>()

    /** Why the last sign-out happened: "expired" (refresh token rejected), "disabled", or "user". */
    var signedOutReason: String? = null
        private set

    val session: AuthSession? get() = currentSession
    val isSignedIn: Boolean get() = currentSession != null

    fun addSessionChangedListener(listener: (AuthSession?) -> Unit) {
        synchronized(sessionChangedListeners) { sessionChangedListeners.add(listener) }
    }

    fun removeSessionChangedListener(listener: (AuthSession?) -> Unit) {
        synchronized(sessionChangedListeners) { sessionChangedListeners.remove(listener) }
    }

    private fun fireSessionChanged(session: AuthSession?) {
        synchronized(sessionChangedListeners) { sessionChangedListeners.toList() }.forEach { it(session) }
    }

    private val authBase: String get() = opt.supabaseUrl.trimEnd('/') + "/auth/v1"

    /** URL to open in the system browser for an OAuth provider. */
    fun buildAuthorizeUrl(provider: String, codeChallenge: String, redirectUri: String): String {
        if (opt.oAuthProviders.none { it.equals(provider, ignoreCase = true) }) {
            throw AuthException("provider_not_enabled", "This sign-in provider is not enabled.")
        }
        return "$authBase/authorize?provider=${UriEscape.escapeDataString(provider)}&redirect_to=${UriEscape.escapeDataString(redirectUri)}" +
            "&code_challenge=${UriEscape.escapeDataString(codeChallenge)}&code_challenge_method=s256"
    }

    /** Sends a magic link whose redirect returns to the redirect receiver with a PKCE code. */
    suspend fun sendMagicLinkAsync(email: String, codeChallenge: String, redirectUri: String) {
        val req = request(
            "POST", "$authBase/otp?redirect_to=${UriEscape.escapeDataString(redirectUri)}",
            buildJsonObject {
                put("email", email)
                put("create_user", true)
                put("code_challenge", codeChallenge)
                put("code_challenge_method", "s256")
            },
        )
        send(req).use { resp ->
            if (!resp.isSuccessful) throw fail(resp, Loc.t("auth_email_send_failed"))
        }
    }

    suspend fun exchangeCodeAsync(authCode: String, verifier: String): AuthSession {
        val req = request(
            "POST", "$authBase/token?grant_type=pkce",
            buildJsonObject {
                put("auth_code", authCode)
                put("code_verifier", verifier)
            },
        )
        return tokenRequest(req)
    }

    /** Result of a sign-up: either signed in immediately, or the user must confirm the e-mail first. */
    data class SignUpResult(val signedIn: Boolean, val confirmationRequired: Boolean)

    /** Creates an account with e-mail + password. Supabase may require e-mail confirmation before sign-in. */
    suspend fun signUpAsync(email: String, password: String): SignUpResult {
        requireReady()
        val redirect = opt.emailConfirmedRedirectUrl?.takeIf { it.isNotEmpty() }
            ?.let { "?redirect_to=" + UriEscape.escapeDataString(it) } ?: ""
        val req = request(
            "POST", "$authBase/signup$redirect",
            buildJsonObject {
                put("email", email.trim())
                put("password", password)
            },
        )
        val body = send(req).use { resp ->
            if (!resp.isSuccessful) throw fail(resp, Loc.t("auth_create_failed"))
            requireNotNull(resp.body).string()
        }
        if (body.contains("\"access_token\"")) {
            storeTokens(body)
            return SignUpResult(true, false)
        }
        return SignUpResult(false, true)
    }

    /** E-mail + password sign-in (no browser needed). */
    suspend fun signInWithPasswordAsync(email: String, password: String): AuthSession {
        requireReady()
        val req = request(
            "POST", "$authBase/token?grant_type=password",
            buildJsonObject {
                put("email", email.trim())
                put("password", password)
            },
        )
        return tokenRequest(req)
    }

    /**
     * Password reset with PKCE: Supabase e-mails a link that returns to this device's redirect receiver with a
     * one-time code; the code becomes a short session in which [updatePasswordAsync] sets the new password.
     * Enumeration-safe: an address without an account behaves exactly like an existing one (the request is reported as
     * accepted through [requestAccepted] and the receiver waits for a link that never comes), so the
     * app can never reveal whether an account exists. Rate limits, invalid addresses and network errors are still reported.
     * The recovery code is never exchanged while ANOTHER account is signed in on this device (a link opened after the user
     * signed in elsewhere would otherwise silently replace that session): it fails with `signed_in_other_account`
     * and the current session stays as it is.
     */
    suspend fun recoverPasswordAsync(
        email: String,
        timeout: Duration,
        redirect: OAuthRedirectReceiver,
        requestAccepted: (() -> Unit)? = null,
    ): AuthSession {
        requireReady()
        val verifier = Pkce.createVerifier()
        val req = request(
            "POST", "$authBase/recover?redirect_to=${UriEscape.escapeDataString(redirect.redirectUri)}",
            buildJsonObject {
                put("email", email.trim())
                put("code_challenge", Pkce.challenge(verifier))
                put("code_challenge_method", "s256")
            },
        )
        send(req).use { resp ->
            if (!resp.isSuccessful) {
                val error = fail(resp, Loc.t("auth_reset_send_failed"))
                if (!revealsAccountExistence(error.code) && !isPerAddressRateLimit(resp.code, error.code, error.message)) throw error
            }
        }
        requestAccepted?.invoke()
        val code = redirect.waitForCode(timeout)
        // (caller cancellation throws here, as ct.ThrowIfCancellationRequested does in the C#)
        currentSession?.let { current ->
            if (!sameAddress(current.email, email)) throw signedInElsewhere()
        }
        // Checked again when the tokens arrive: a sign-in that completed during the exchange is not replaced either.
        val exchange = request(
            "POST", "$authBase/token?grant_type=pkce",
            buildJsonObject {
                put("auth_code", code)
                put("code_verifier", verifier)
            },
        )
        return tokenRequest(exchange) { s -> currentSession?.let { now -> now.userId == s.userId } ?: true }
    }

    /** Sets a new password for the signed-in user (used right after recovery, or from Account). */
    suspend fun updatePasswordAsync(newPassword: String) {
        val token = getAccessTokenAsync() ?: throw AuthException("signed_out", "Please sign in again.")
        val req = request(
            "PUT", "$authBase/user",
            buildJsonObject { put("password", newPassword) },
            bearer = token,
        )
        send(req).use { resp ->
            if (!resp.isSuccessful) throw fail(resp, Loc.t("auth_password_change_failed"))
        }
    }

    private fun requireReady() {
        if (!opt.authReady) throw AuthException("auth_unavailable", "Sign-in is temporarily unavailable. Please try again in a moment.")
    }

    private suspend fun send(req: Request): Response {
        try {
            return http.newCall(req).executeAsync()
        } catch (e: IOException) {
            throw AuthException("network", "Cannot reach the sign-in service. Check your internet connection.")
        } catch (e: TimeoutCancellationException) {
            throw AuthException("network", "Cannot reach the sign-in service. Check your internet connection.")
        }
    }

    /** Full interactive flow: receiver → browser → code → tokens. */
    suspend fun signInInteractiveAsync(
        openBrowser: suspend (String) -> Unit,
        provider: String?,
        email: String?,
        timeout: Duration,
        redirect: OAuthRedirectReceiver,
    ): AuthSession {
        val verifier = Pkce.createVerifier()
        val challenge = Pkce.challenge(verifier)
        if (!email.isNullOrBlank()) sendMagicLinkAsync(email, challenge, redirect.redirectUri)
        else openBrowser(buildAuthorizeUrl(provider ?: opt.oAuthProviders.first(), challenge, redirect.redirectUri))
        val code = redirect.waitForCode(timeout)
        return exchangeCodeAsync(code, verifier)
    }

    /** Valid access token, refreshing when it expires within 60 s. Null when signed out. */
    suspend fun getAccessTokenAsync(forceRefresh: Boolean = false): String? {
        var s = currentSession ?: return null
        if (!forceRefresh && java.time.Duration.between(time.utcNow(), s.expiresAt) > java.time.Duration.ofSeconds(60)) return s.accessToken
        return refreshLock.withLock {
            s = currentSession ?: return null
            if (!forceRefresh && java.time.Duration.between(time.utcNow(), s.expiresAt) > java.time.Duration.ofSeconds(60)) return s.accessToken
            if (!opt.authReady) return s.accessToken // service config not loaded yet: the API decides whether it is still valid
            val req = request(
                "POST", "$authBase/token?grant_type=refresh_token",
                buildJsonObject { put("refresh_token", s.refreshToken) },
            )
            try {
                tokenRequest(req).accessToken
            } catch (ex: AuthException) {
                if (ex.code in REFRESH_SIGN_OUT_CODES) {
                    signedOutReason = if (ex.code == "user_banned") "disabled" else "expired"
                    signOutLocal(keepReason = true) // refresh token revoked/expired → user must sign in again
                    null
                } else {
                    throw ex
                }
            }
        }
    }

    suspend fun signOutAsync() {
        val s = currentSession
        try {
            if (s != null) {
                val req = request("POST", "$authBase/logout", buildJsonObject { }, bearer = s.accessToken)
                http.newCall(req).executeAsync().use { }
            }
        } catch (e: Exception) {
            // offline or slow sign-out still clears local tokens (IOException, timeouts, cancellation)
            if (e !is IOException && e !is TimeoutCancellationException && e !is CancellationException &&
                e !is java.util.concurrent.TimeoutException
            ) {
                throw e
            }
        } finally {
            signOutLocal()
        }
    }

    fun signOutLocal(keepReason: Boolean = false) {
        if (!keepReason) signedOutReason = "user"
        currentSession = null
        store.clear()
        fireSessionChanged(null)
    }

    private fun request(method: String, url: String, body: JsonObject, bearer: String? = null): Request {
        val builder = Request.Builder()
            .url(url)
            .method(method, body.toString().toRequestBody(JSON_MEDIA))
            .header("apikey", opt.supabaseAnonKey)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return builder.build()
    }

    /** @param accept Checked on the new session before it is stored; false = it is discarded (signed_in_other_account). */
    private suspend fun tokenRequest(req: Request, accept: ((AuthSession) -> Boolean)? = null): AuthSession {
        val body = send(req).use { resp ->
            if (!resp.isSuccessful) throw fail(resp, Loc.t("err_sign_in_failed"))
            requireNotNull(resp.body).string()
        }
        return storeTokens(body, accept)
    }

    private fun storeTokens(json: String, accept: ((AuthSession) -> Boolean)? = null): AuthSession {
        if (json.trim() == "null") throw AuthException("bad_response", "Empty token response.")
        val t = try {
            tokenJson.decodeFromString(TokenResponse.serializer(), json)
        } catch (e: SerializationException) {
            throw AuthException("bad_response", "Unexpected response from the sign-in service.")
        }
        if (t.accessToken.isEmpty() || t.refreshToken.isEmpty()) throw AuthException("bad_response", "Incomplete token response.")
        val session = AuthSession(
            t.accessToken, t.refreshToken,
            time.utcNow().plusSeconds(maxOf(60, t.expiresIn).toLong()),
            t.user?.id ?: "", t.user?.email ?: "",
        )
        if (accept != null && !accept(session)) throw signedInElsewhere()
        currentSession = session
        signedOutReason = null
        store.save(session)
        fireSessionChanged(session)
        return session
    }

    private fun fail(resp: Response, fallback: String): AuthException {
        var code = "http_" + resp.code
        var msg = fallback
        try {
            val root = tokenJson.parseToJsonElement(resp.body?.string() ?: "").jsonObject
            (root["error_code"] ?: root["error"])?.let { c ->
                if (c is kotlinx.serialization.json.JsonPrimitive && c.isString) code = c.content
            }
            (root["msg"] ?: root["error_description"])?.let { m ->
                if (m is kotlinx.serialization.json.JsonPrimitive && m.isString) msg = m.content
            }
        } catch (e: SerializationException) {
            // keep the fallback code/message, as in the C#
        } catch (e: IllegalArgumentException) {
            // not a JSON object — keep the fallback code/message
        }
        return AuthException(code, friendly(code, msg))
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Int = 0,
        @SerialName("user") val user: TokenUser? = null,
    )

    @Serializable
    private data class TokenUser(
        @SerialName("id") val id: String = "",
        @SerialName("email") val email: String? = null,
    )

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val tokenJson = Json { ignoreUnknownKeys = true }

        private val REFRESH_SIGN_OUT_CODES = setOf(
            "invalid_grant", "refresh_token_not_found", "refresh_token_already_used", "session_expired",
            "session_not_found", "user_banned", "http_400", "http_401",
        )

        private fun sameAddress(a: String?, b: String?): Boolean =
            !a.isNullOrBlank() && a.trim().equals(b?.trim(), ignoreCase = true)

        private fun signedInElsewhere(): AuthException =
            AuthException("signed_in_other_account", Loc.t("auth_reset_signed_in_other"))

        /**
         * Older auth servers report the per-address recovery e-mail limit as a bare 429 "For security purposes, you can only
         * request this after N seconds" (no error code). Like `over_email_send_rate_limit` it applies only to existing
         * accounts, so it is treated as accepted; the general request limit (`over_request_rate_limit`) is still reported.
         */
        fun isPerAddressRateLimit(status: Int, code: String?, message: String?): Boolean =
            status == 429 && code != "over_request_rate_limit" &&
                (message ?: "").trimStart().startsWith("For security purposes", ignoreCase = true)

        /** Error codes that would tell the caller that no account exists for an address (treated as "accepted"). */
        fun revealsAccountExistence(code: String?): Boolean =
            code == "user_not_found" || code == "email_not_found" || code == "identity_not_found" ||
                code == "http_404" || code == "over_email_send_rate_limit"

        /** Plain-language messages for the codes users actually hit (raw provider text otherwise). */
        fun friendly(code: String, fallback: String): String = when (code) {
            "invalid_credentials", "invalid_grant" -> Loc.t("auth_invalid_credentials")
            "email_not_confirmed" -> Loc.t("auth_email_not_confirmed")
            "user_already_exists", "email_exists" -> Loc.t("auth_user_exists")
            "weak_password" -> Loc.t("auth_weak_password")
            "over_email_send_rate_limit", "over_request_rate_limit" -> Loc.t("auth_rate_limited")
            "user_banned" -> Loc.t("acct_msg_disabled")
            "signup_disabled" -> Loc.t("auth_signup_disabled")
            "validation_failed" -> Loc.t("err_email_invalid")
            else -> fallback
        }
    }
}

/** Suspends an OkHttp [okhttp3.Call] to completion; cancelling the coroutine cancels the call. */
internal suspend fun okhttp3.Call.executeAsync(): Response =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
        })
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
                // already completed
            }
        }
    }
