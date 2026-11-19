package com.interviewassistant.app.services

import com.interviewassistant.app.platform.AppLinkRedirectReceiver
import com.interviewassistant.app.platform.KeystoreDataProtector
import com.interviewassistant.app.platform.OAuthRedirectBus
import com.interviewassistant.app.uistate.ErrorCopy
import com.interviewassistant.client.AuthException
import com.interviewassistant.client.BackendClient
import com.interviewassistant.client.BackendException
import com.interviewassistant.client.CloudOptions
import com.interviewassistant.client.ITokenStore
import com.interviewassistant.client.InstallationIdentity
import com.interviewassistant.client.OAuthRedirectReceiver
import com.interviewassistant.client.ProtectedFileTokenStore
import com.interviewassistant.client.SupabaseAuth
import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.BillingStates
import com.interviewassistant.contracts.PlanDto
import com.interviewassistant.contracts.RegisterDeviceRequest
import com.interviewassistant.contracts.RuntimeConfig
import com.interviewassistant.core.languages.Loc
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Mirror of `InterviewAssistant.App/Services/CloudService.cs`. */

enum class CloudState { NotConfigured, Connecting, ServiceUnavailable, UpdateRequired, SignedOut, SessionExpired, AccountDisabled, SignedIn }

/**
 * Desktop side of the commercial service: account state, e-mail/password (and optional OAuth) sign-in through Supabase,
 * entitlements, device registration, upgrade and billing links. Only the API base URL ships with the app (cloud.json);
 * the publishable sign-in configuration comes from the service and is cached for offline start. Tokens are
 * Keystore-encrypted (the P2 [ProtectedFileTokenStore] + [KeystoreDataProtector], the DPAPI counterpart).
 *
 * The C# constructor builds its two HTTP clients, the token store and the redirect surface itself;
 * here those are constructor seams with the production wiring as defaults (DC-85/DC-86), so the
 * facade's logic runs on the JVM against fakes: [authHttp] (the C#'s static 20 s-timeout auth
 * client), [apiHttp] (pooled 10 min, 8 s connect, no overall timeout — streaming), [tokenStore]
 * (roaming `session.dpapi` behind the Keystore protector), [bundledCloudJson] (the shipped
 * cloud.json text — the "next to the EXE" file, read from [AppPaths.baseDirectory] by default),
 * [redirectFactory] (one App Link receiver per interactive flow, registered in [OAuthRedirectBus]
 * and closed by the flow that created it — the C#'s per-call loopback listener), [deviceNameProvider]
 * (the `Environment.MachineName` counterpart) and the upgrade poll's [upgradeDelay]/[nowMillis]
 * (the C#'s 5 s delay and its 15-minute cancellation source, DC-88).
 */
class CloudService(
    private val settings: AppSettings,
    private val authHttp: OkHttpClient = defaultAuthHttp(),
    private val apiHttp: OkHttpClient = defaultApiHttp(),
    tokenStore: ITokenStore = ProtectedFileTokenStore(
        File(AppPaths.roaming, "session.dpapi").path, KeystoreDataProtector(),
    ),
    private val bundledCloudJson: () -> String? = { readTextOrNull(File(AppPaths.baseDirectory, "cloud.json")) },
    private val redirectFactory: () -> OAuthRedirectReceiver = { createPlatformReceiver() },
    private val deviceNameProvider: () -> String = { defaultDeviceName() },
    private val upgradeDelay: suspend (Duration) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val options: CloudOptions
    val auth: SupabaseAuth?
    val api: BackendClient?

    var account: AccountDto? = null
        private set
    var config: RuntimeConfig? = null
        private set
    var plans: List<PlanDto> = emptyList()
        private set
    var state: CloudState = CloudState.NotConfigured
        private set

    /** Plain-language explanation of the current state (never a raw exception). */
    var message: String? = null
        private set
    val isConfigured: Boolean get() = options.isConfigured
    val isSignedIn: Boolean get() = auth?.isSignedIn == true
    var upgradePending: Boolean = false
        private set

    /** The failure of the last [refreshAsync] (null after a success): lets a page show which request failed. */
    var lastRefreshError: Exception? = null
        private set

    /** The exception of the last failed sign-in action (the UI picks contextual copy, e.g. a reset link that timed out). */
    var lastAuthError: Exception? = null
        private set

    private val changedListeners = mutableListOf<() -> Unit>()

    /** The C#'s `event Action? Changed`. */
    fun addChangedListener(listener: () -> Unit) {
        synchronized(changedListeners) { changedListeners.add(listener) }
    }

    fun removeChangedListener(listener: () -> Unit) {
        synchronized(changedListeners) { changedListeners.remove(listener) }
    }

    private fun fireChanged() {
        val snapshot = synchronized(changedListeners) { changedListeners.toList() }
        for (listener in snapshot) listener()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var upgradeJob: Job? = null

    private val publicConfigCache: String get() = File(AppPaths.roaming, "service-config.json").path

    init {
        options = loadOptions(BuildFlavor.Developer)
        // Full semantic version incl. pre-release ("2.1.0-rc.5"): devices, support and minimum-version checks see the real build.
        options.clientVersion = BuildInfo.version
        if (!options.isConfigured) {
            auth = null
            api = null
            state = CloudState.NotConfigured
            message = Loc.t("acct_not_configured_long")
        } else {
            loadCachedPublicConfig()
            val a = SupabaseAuth(authHttp, options, tokenStore)
            a.addSessionChangedListener {
                if (!a.isSignedIn) {
                    account = null
                    setState(stateWhenSignedOut(), signedOutMessage())
                }
            }
            auth = a
            api = BackendClient.forAuth(apiHttp, a, options.apiBaseUrl, options.clientVersion)
            state = if (isSignedIn) CloudState.Connecting else CloudState.SignedOut
        }
    }

    /** cloud.json next to the executable (ships per release channel). Developer builds may override it from the roaming folder. */
    internal fun loadOptions(developer: Boolean): CloudOptions {
        val sources = mutableListOf<() -> String?>()
        if (developer) sources += { readTextOrNull(File(AppPaths.roaming, "cloud.json")) }
        sources += bundledCloudJson
        var o = CloudOptions()
        for (source in sources) {
            val text = try {
                source()
            } catch (ex: Exception) {
                if (ex is IOException || ex is SecurityException) {
                    AppLog.warn("cloud.json unreadable: " + ex.javaClass.simpleName)
                    continue
                }
                throw ex
            } ?: continue
            try {
                o = CloudOptions.fromJson(text)
                break
            } catch (ex: Exception) {
                if (ex is IOException || ex is SerializationException || ex is SecurityException) {
                    AppLog.warn("cloud.json unreadable: " + ex.javaClass.simpleName)
                } else {
                    throw ex
                }
            }
        }
        if (forcedApiBaseUrl != null) o.apiBaseUrl = forcedApiBaseUrl!!
        return o
    }

    private fun applyPublicConfig(cfg: RuntimeConfig) {
        config = cfg
        val a = cfg.auth ?: return
        options.supabaseUrl = if (a.available) a.supabaseUrl else ""
        options.supabaseAnonKey = if (a.available) a.publishableKey else ""
        options.oAuthProviders = a.oAuthProviders
        options.emailPassword = a.emailPassword
        options.emailConfirmedRedirectUrl = a.emailConfirmedRedirectUrl
    }

    private fun loadCachedPublicConfig() {
        try {
            val file = File(publicConfigCache)
            if (file.isFile) {
                val cfg = BackendClient.Json.decodeFromString(
                    RuntimeConfig.serializer().nullable, file.readText(Charsets.UTF_8),
                )
                if (cfg != null) applyPublicConfig(cfg)
            }
        } catch (ex: Exception) {
            if (ex is IOException || ex is SerializationException || ex is SecurityException) {
                AppLog.warn("Cached service config ignored: " + ex.javaClass.simpleName)
            } else {
                throw ex
            }
        }
    }

    private fun cachePublicConfig(cfg: RuntimeConfig) {
        try {
            File(publicConfigCache).writeText(
                BackendClient.Json.encodeToString(RuntimeConfig.serializer(), cfg), Charsets.UTF_8,
            )
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) {
                AppLog.warn("Could not cache service config: " + ex.javaClass.simpleName)
            } else {
                throw ex
            }
        }
    }

    private fun stateWhenSignedOut(): CloudState = when (auth?.signedOutReason) {
        "expired" -> CloudState.SessionExpired
        "disabled" -> CloudState.AccountDisabled
        else -> CloudState.SignedOut
    }

    private fun signedOutMessage(): String = Loc.t(
        when (auth?.signedOutReason) {
            "expired" -> "acct_msg_expired"
            "disabled" -> "acct_msg_disabled"
            else -> "acct_msg_sign_in"
        },
    )

    private fun setState(s: CloudState, msg: String?) {
        state = s
        message = msg
        fireChanged()
    }

    /** Refreshes service config, account and device registration. Never throws (except caller cancellation, DC-87); updates [state]. */
    suspend fun refreshAsync(): Boolean {
        val a = api ?: return false
        val stateBefore = state
        val messageBefore = message
        if ((state == CloudState.ServiceUnavailable || state == CloudState.SignedOut || state == CloudState.SessionExpired) && isSignedIn) {
            setState(CloudState.Connecting, Loc.t("acct_connecting"))
        }
        try {
            val cfg = a.configAsync()
            applyPublicConfig(cfg)
            cachePublicConfig(cfg)
            if (plans.isEmpty()) {
                try {
                    plans = a.plansAsync()
                } catch (ex: BackendException) {
                    // plans are decorative here: a failure leaves the list empty and is retried next refresh, as in the C#
                }
            }
            if (!isSignedIn) {
                setState(stateWhenSignedOut(), if (options.authReady) signedOutMessage() else Loc.t("acct_msg_auth_unavailable"))
                return true
            }
            account = a.meAsync()
            ensureDeviceAsync()
            lastRefreshError = null
            setState(CloudState.SignedIn, null)
            return true
        } catch (ex: BackendException) {
            lastRefreshError = ex
            AppLog.warn("Cloud refresh failed: ${ex.code} (ref ${ex.correlationId ?: ""})")
            val (s, m) = classify(ex)
            if (s == CloudState.SignedOut && auth != null) {
                auth.signOutLocal()
                account = null
            }
            setState(s, m)
            return false
        } catch (ex: CancellationException) {
            // The caller gave up (page left, health check limit): nothing was learned — never leave "Connecting…" behind.
            // (The C# returns false here; the port rethrows so the caller's coroutine stays cancelled — DC-87.)
            if (state == CloudState.Connecting) setState(stateBefore, messageBefore)
            throw ex
        } catch (ex: Exception) {
            // The sign-in token could not be renewed (network, sign-in service down) or the answer was not the service's
            // (a captive Wi-Fi login page): the service is unreachable for now, the account stays signed in.
            if (ex is AuthException || ex is SerializationException || ex is UnsupportedOperationException ||
                ex is IOException || ex is TimeoutException
            ) {
                lastRefreshError = ex
                AppLog.warn("Cloud refresh failed: ${(ex as? AuthException)?.code ?: ex.javaClass.simpleName}")
                setState(CloudState.ServiceUnavailable, describe(ex))
                return false
            }
            throw ex
        }
    }

    private fun classify(ex: BackendException): Pair<CloudState, String> = when {
        ex.isUpdateRequired -> CloudState.UpdateRequired to Loc.t("err_update_required_long")
        ex.code == "account_disabled" -> CloudState.AccountDisabled to Loc.t("acct_msg_disabled")
        ex.code == "account_deleted" -> CloudState.SignedOut to Loc.t("acct_msg_deleted")
        ex.status == 401 -> (if (isSignedIn) CloudState.SessionExpired else CloudState.SignedOut) to Loc.t("acct_msg_expired")
        else -> CloudState.ServiceUnavailable to describe(ex)
    }

    // ---------------- authentication ----------------

    private suspend fun authAction(action: suspend () -> Unit, log: String): Pair<Boolean, String?> {
        if (auth == null) return Pair(false, message)
        try {
            if (!options.authReady) refreshAsync() // fetch sign-in config on first use
            lastAuthError = null
            action()
            AppLog.info(log)
            return Pair(true, null)
        } catch (ex: Exception) {
            if (ex is AuthException || ex is TimeoutException || ex is BackendException) {
                lastAuthError = ex
                AppLog.warn("$log failed: ${(ex as? AuthException)?.code ?: (ex as? BackendException)?.code ?: ex.javaClass.simpleName}")
                return Pair(false, describe(ex))
            }
            throw ex
        }
    }

    suspend fun signInAsync(email: String, password: String): Pair<Boolean, String?> {
        val r = authAction({ auth!!.signInWithPasswordAsync(email, password) }, "Signed in (e-mail)")
        if (r.first) refreshAsync()
        return r
    }

    /** Returns ConfirmationRequired=true when Supabase sent a confirmation e-mail. */
    suspend fun signUpAsync(email: String, password: String): Triple<Boolean, Boolean, String?> {
        var result: SupabaseAuth.SignUpResult? = null
        val r = authAction({ result = auth!!.signUpAsync(email, password) }, "Account created")
        if (r.first && result?.signedIn == true) refreshAsync()
        return Triple(r.first, result?.confirmationRequired == true, r.second)
    }

    suspend fun signInWithProviderAsync(provider: String): Pair<Boolean, String?> {
        val receiver = redirectFactory()
        val r = try {
            authAction(
                {
                    auth!!.signInInteractiveAsync(
                        openBrowser = { url -> openExternal(url) },
                        provider = provider,
                        email = null,
                        timeout = 5.minutes,
                        redirect = receiver,
                    )
                },
                "Signed in (" + provider + ")",
            )
        } finally {
            receiver.close()
        }
        if (r.first) refreshAsync()
        return r
    }

    /**
     * Password reset: the e-mailed link returns to this device; then [askNewPassword] collects the new password.
     *
     * Enumeration-safe: [requested] runs once the request is accepted — for an existing and for an
     * unknown address alike — so the UI shows the same neutral notice either way. Never switches accounts: while another
     * account is signed in, nothing is requested, and a link opened after someone signed in is refused by
     * [SupabaseAuth.recoverPasswordAsync] (the signed-in session stays as it is).
     */
    suspend fun resetPasswordAsync(
        email: String,
        askNewPassword: suspend () -> String?,
        requested: (() -> Unit)? = null,
    ): Pair<Boolean, String?> {
        val current = auth?.session
        if (current != null && !(current.email.trim().equals(email.trim(), ignoreCase = true))) {
            AppLog.warn("Password reset refused: another account is signed in")
            return Pair(false, Loc.t("auth_reset_signed_in_other"))
        }
        val receiver = redirectFactory()
        val r = try {
            authAction(
                {
                    auth!!.recoverPasswordAsync(email, 15.minutes, receiver, requested)
                    currentCoroutineContext().ensureActive() // the reset screen was left: no new-password prompt
                    val pw = askNewPassword() ?: throw AuthException("cancelled", Loc.t("acct_password_not_changed"))
                    auth!!.updatePasswordAsync(pw)
                },
                "Password reset",
            )
        } finally {
            receiver.close()
        }
        // Also when the new password was not set: the link signed this account in, so its data is loaded like any sign-in.
        if (r.first || isSignedIn) refreshAsync()
        return r
    }

    suspend fun changePasswordAsync(newPassword: String): Pair<Boolean, String?> =
        authAction({ auth!!.updatePasswordAsync(newPassword) }, "Password changed")

    /**
     * The sign-in provider of the current session ("email" for e-mail/password, otherwise e.g. "google", "azure",
     * "github", "apple"), read from the access token's `app_metadata.provider`. Null when unknown.
     */
    val signInProvider: String?
        get() {
            val token = auth?.session?.accessToken ?: return null
            val parts = token.split('.')
            if (parts.size != 3) return null
            try {
                var p = parts[1].replace('-', '+').replace('_', '/')
                p = p.padEnd(p.length + (4 - p.length % 4) % 4, '=')
                val doc = Json.parseToJsonElement(Base64.getDecoder().decode(p).toString(Charsets.UTF_8)).jsonObject
                val meta = doc["app_metadata"]?.jsonObject ?: return null
                val prov = meta["provider"]
                return if (prov is JsonPrimitive && prov.isString) prov.content else null
            } catch (ex: Exception) {
                // FormatException (bad base64) or JsonException in the C#; here also the
                // IllegalStateException of a payload that is not a JSON object (TryGetProperty
                // simply finds nothing). No suspension happens in this getter, so no
                // CancellationException can be caught here.
                if (ex is IllegalArgumentException || ex is IllegalStateException || ex is SerializationException) {
                    return null
                }
                throw ex
            }
        }

    /** Re-authentication for accounts that sign in with a provider (no password): a fresh provider sign-in for the SAME account. */
    suspend fun reauthenticateWithProviderAsync(provider: String): Pair<Boolean, String?> {
        val before = auth?.session?.userId
        if (before == null) return Pair(false, Loc.t("err_reauth_unavailable"))
        val (ok, error) = signInWithProviderAsync(provider)
        if (!ok) return Pair(false, error ?: Loc.t("err_reauth_failed"))
        if (auth?.session?.userId != before) {
            AppLog.warn("Provider re-authentication returned a different account; signing out")
            signOutAsync()
            return Pair(false, Loc.t("err_reauth_failed"))
        }
        AppLog.info("Re-authenticated with the sign-in provider for a sensitive action")
        return Pair(true, null)
    }

    /**
     * Re-authentication before sensitive actions (password change, data export, account deletion): the current password
     * must be correct for the SAME account. Accounts that only use a sign-in provider cannot confirm a password and are
     * asked to sign in again with that provider instead (see [reauthenticateWithProviderAsync]).
     */
    suspend fun reauthenticateAsync(password: String): Pair<Boolean, String?> {
        val s = auth?.session
        if (s == null || s.email.isBlank()) return Pair(false, Loc.t("err_reauth_unavailable"))
        try {
            val fresh = auth!!.signInWithPasswordAsync(s.email, password)
            if (fresh.userId != s.userId) {
                AppLog.warn("Re-authentication returned a different account; signing out")
                signOutAsync()
                return Pair(false, Loc.t("err_reauth_failed"))
            }
            AppLog.info("Re-authenticated for a sensitive action")
            return Pair(true, null)
        } catch (ex: AuthException) {
            AppLog.warn("Re-authentication failed: " + ex.code)
            return Pair(false, Loc.t(if (ex.code == "invalid_credentials" || ex.code == "invalid_grant") "err_reauth_wrong_password" else "err_reauth_failed"))
        } catch (ex: Exception) {
            if (ex is TimeoutException || ex is IOException) return Pair(false, describe(ex))
            throw ex
        }
    }

    suspend fun signOutAsync() {
        val a = auth ?: return
        upgradeJob?.cancel()
        a.signOutAsync()
        account = null
        plans = emptyList()
        setState(CloudState.SignedOut, Loc.t("acct_msg_signed_out"))
    }

    suspend fun ensureDeviceAsync(): UUID {
        val installation = InstallationIdentity.getOrCreate(File(AppPaths.local, "installation.id").path)
        val m = deviceNameProvider()
        val name = if (m.length in 1..60) m else "Android device"
        val dev = api!!.registerDeviceAsync(RegisterDeviceRequest(installation, name, "android", options.clientVersion))
        if (settings.cloudDeviceId != dev.id) {
            settings.cloudDeviceId = dev.id
            settings.save()
        }
        return dev.id
    }

    // ---------------- billing ----------------

    /**
     * Upgrade: server creates a Paddle checkout for the signed-in user → browser → Paddle webhook updates the account.
     * The app then polls the account (every 5 s, up to 15 min) until the paid plan is active.
     */
    suspend fun startUpgradeAsync(planCode: String): Pair<Boolean, String?> {
        if (api == null || !isSignedIn) return Pair(false, Loc.t("err_sign_in_first"))
        try {
            val url = api.checkoutAsync(planCode).url
            if (!openExternal(url)) return Pair(false, Loc.t("err_checkout_link"))
            AppLog.info("Checkout opened")
            upgradeJob?.cancel()
            upgradePending = true
            fireChanged()
            upgradeJob = scope.launch { waitForUpgradeAsync() }
            return Pair(true, null)
        } catch (ex: BackendException) {
            return Pair(false, describe(ex))
        }
    }

    private suspend fun waitForUpgradeAsync() {
        try {
            val deadline = nowMillis() + 15.minutes.inWholeMilliseconds
            while (currentCoroutineContext().isActive && nowMillis() < deadline) {
                upgradeDelay(5.seconds)
                refreshAsync()
                if (isPaidState(account?.billingState)) {
                    AppLog.info("Upgrade confirmed by the service")
                    break
                }
            }
        } catch (ex: CancellationException) {
            // cancelled (sign-out, or a newer upgrade replaced this poll): the finally still clears the flag, as in the C#
        } finally {
            upgradePending = false
            fireChanged()
        }
    }

    suspend fun openPortalAsync(): Pair<Boolean, String?> {
        if (api == null || !isSignedIn) return Pair(false, Loc.t("err_sign_in_first"))
        try {
            return if (openExternal(api.portalAsync().url)) Pair(true, null) else Pair(false, Loc.t("err_billing_link"))
        } catch (ex: BackendException) {
            return Pair(false, describe(ex))
        }
    }

    companion object {
        /** Self-test: point the client at an unreachable endpoint to exercise the offline UI without touching production. */
        var forcedApiBaseUrl: String? = null

        /**
         * The platform's browser launcher, installed by the composition root (the C#'s `Process.Start`):
         * [openExternal] hands it only URLs that passed the https/DNS gate; it returns whether the
         * browser opened. Null until installed — [openExternal] then refuses, as it does for any
         * URL it cannot hand to a browser.
         */
        var urlLauncher: ((String) -> Boolean)? = null

        /** User-facing text for any service error (see [ErrorCopy]). */
        fun describe(ex: Exception): String = ErrorCopy.describe(ex, includeReference = true)

        /** [includeReference] = false for inline section messages (the correlation id is logged only). */
        fun describe(ex: Exception, includeReference: Boolean): String = ErrorCopy.describe(ex, includeReference)

        fun isPaidState(s: String?): Boolean =
            s == BillingStates.Active || s == BillingStates.Canceling || s == BillingStates.PastDue

        /** The pure gate of [openExternal]: an absolute https URL whose host is a DNS name, never an IP literal. */
        fun isOpenableExternalUrl(url: String): Boolean {
            val u = try {
                URI(url)
            } catch (ex: Exception) {
                return false
            }
            if (!u.isAbsolute || u.scheme?.lowercase() != "https") return false
            val host = u.host ?: return false
            return isDnsHostName(host)
        }

        /** Opens https URLs in the default browser (checkout, portal, sign-in, legal pages). Anything else is refused. */
        fun openExternal(url: String): Boolean {
            if (!isOpenableExternalUrl(url)) {
                AppLog.warn("Refused to open a non-https or IP-literal link")
                return false
            }
            val launcher = urlLauncher ?: return false
            return launcher(url)
        }

        private fun isDnsHostName(host: String): Boolean {
            if (host.contains(':')) return false // IPv6 literal (URI.host strips the brackets)
            if (host.all { it.isDigit() || it == '.' }) return false // IPv4 literal or a bare number (UriHostNameType is not Dns)
            return true
        }

        /** The C#'s static auth client: `HttpClient { Timeout = 20 s }` (DC-85). */
        private fun defaultAuthHttp(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /** The C#'s API client: pooled connections (10 min lifetime), 8 s connect, no overall timeout (streaming answers; per-call cancellation instead) (DC-85). */
        private fun defaultApiHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private fun readTextOrNull(file: File): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

        /**
         * One App Link redirect receiver for an interactive flow (the C#'s per-call loopback listener):
         * created fresh, registered in [OAuthRedirectBus] for the activity to deliver to, and
         * unregistered when the owning flow closes it.
         */
        private fun createPlatformReceiver(): OAuthRedirectReceiver {
            val receiver = AppLinkRedirectReceiver.create { state -> AppLinkRedirectReceiver.defaultRedirectUri(state) }
            OAuthRedirectBus.register(receiver)
            return object : OAuthRedirectReceiver {
                override val state: String get() = receiver.state
                override val redirectUri: String get() = receiver.redirectUri
                override suspend fun waitForCode(timeout: Duration): String = receiver.waitForCode(timeout)
                override fun close() {
                    OAuthRedirectBus.unregister(receiver)
                    receiver.close()
                }
            }
        }

        /** The `Environment.MachineName` counterpart: manufacturer + model ("Pixel 8", "samsung SM-S918B"). */
        private fun defaultDeviceName(): String {
            val model = android.os.Build.MODEL ?: ""
            val manufacturer = android.os.Build.MANUFACTURER ?: ""
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
        }
    }
}
