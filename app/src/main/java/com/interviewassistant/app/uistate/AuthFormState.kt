package com.interviewassistant.app.uistate

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.ceil

/** Mirror of `InterviewAssistant.App/UiState/AuthFormState.cs`. */

/** The distinct states of the signed-out Account page's form. */
enum class AuthMode {
    /** E-mail + password, "Sign in". */
    SignIn,
    /** E-mail + password + confirmation, password rules and the legal sentence, "Create account". */
    CreateAccount,
    /** E-mail only, "Send reset link". */
    ForgotPassword,
    /** The enumeration-safe notice, "Back to sign in" and "Resend" after a cooldown. */
    ForgotSent,
    /** Sign in after "Switch account": its own headline, the e-mail starts empty. */
    SwitchAccount,
}

enum class AuthField { Email, Password, ConfirmPassword }

/**
 * State machine of the shared auth form (one card, labels above fields, inline errors under the field, one primary
 * button, secondary links). Each state has its own heading, subtitle, visible fields and primary button; mode changes
 * happen through links ("New here? Create an account", "Already have an account? Sign in", "Back to sign in").
 * While a request runs ([busy]) the state does not change. Plain class (no WPF) so it is unit-tested.
 */
class AuthFormState {
    /** Which sign-in flavour "Back to sign in" returns to (plain sign-in, or the switch-account sign-in). */
    private var signInMode: AuthMode = AuthMode.SignIn

    var mode: AuthMode = AuthMode.SignIn
        private set
    /** The account that was left by "Switch account" (named in the subtitle; never pre-filled). */
    var previousEmail: String? = null
        private set
    /** When "Resend" may be used again (ForgotSent only). */
    var resendAvailableAt: OffsetDateTime? = null
        private set
    var busy: Boolean = false
        private set

    private val changedListeners = mutableListOf<() -> Unit>()
    fun addChangedListener(listener: () -> Unit) { changedListeners.add(listener) }
    fun removeChangedListener(listener: () -> Unit) { changedListeners.remove(listener) }
    private fun fireChanged() = changedListeners.toList().forEach { it() }

    val visibleFields: List<AuthField> get() = fieldsFor(mode)

    fun shows(field: AuthField): Boolean = field in visibleFields

    val headingKey: String get() = when (mode) {
        AuthMode.CreateAccount -> "auth_create_title"
        AuthMode.ForgotPassword -> "auth_forgot_title"
        AuthMode.ForgotSent -> "auth_forgot_sent_title"
        AuthMode.SwitchAccount -> "sign_in_another"
        else -> "auth_signin_title"
    }

    /** Subtitle key; null = no subtitle (ForgotSent shows the neutral notice instead). */
    val subtitleKey: String? get() = when (mode) {
        AuthMode.CreateAccount -> "auth_create_subtitle"
        AuthMode.ForgotPassword -> "auth_forgot_subtitle"
        AuthMode.ForgotSent -> null
        AuthMode.SwitchAccount -> if (!previousEmail.isNullOrEmpty()) "auth_switch_subtitle" else "auth_switch_subtitle_plain"
        else -> "auth_signin_subtitle"
    }

    /** The one primary button of the state. */
    val primaryKey: String get() = when (mode) {
        AuthMode.CreateAccount -> "create_account"
        AuthMode.ForgotPassword -> "auth_send_reset_link"
        AuthMode.ForgotSent -> "auth_back_to_sign_in"
        else -> "sign_in"
    }

    /** Text of the primary button while its request runs; null = the state has no request. */
    val busyKey: String? get() = when (mode) {
        AuthMode.SignIn, AuthMode.SwitchAccount -> "acct_signing_in"
        AuthMode.CreateAccount -> "acct_creating"
        AuthMode.ForgotPassword -> "auth_sending_reset"
        else -> null
    }

    /** The primary button submits the form (ForgotSent's primary only goes back to sign-in). */
    val primarySubmits: Boolean get() = mode != AuthMode.ForgotSent

    val showsForgotLink: Boolean get() = mode == AuthMode.SignIn || mode == AuthMode.SwitchAccount
    /** "New here? Create an account". */
    val showsCreateLink: Boolean get() = mode == AuthMode.SignIn || mode == AuthMode.SwitchAccount
    /** "Already have an account? Sign in". */
    val showsSignInLink: Boolean get() = mode == AuthMode.CreateAccount
    /** "Back to sign in" as a link (in ForgotSent it is the primary button). */
    val showsBackLink: Boolean get() = mode == AuthMode.ForgotPassword
    val showsProviders: Boolean get() = mode == AuthMode.SignIn || mode == AuthMode.CreateAccount || mode == AuthMode.SwitchAccount
    val showsPasswordRules: Boolean get() = mode == AuthMode.CreateAccount
    val showsLegal: Boolean get() = mode == AuthMode.CreateAccount
    val showsResend: Boolean get() = mode == AuthMode.ForgotSent
    /** The password-reset screens (e-mail step or link sent). Leaving them ends the wait for the reset link. */
    val inResetFlow: Boolean get() = mode == AuthMode.ForgotPassword || mode == AuthMode.ForgotSent

    fun canResend(now: OffsetDateTime): Boolean =
        mode == AuthMode.ForgotSent && !busy && (resendAvailableAt?.let { at -> now >= at } ?: true)

    /** Whole seconds until "Resend" is available (0 = now). */
    fun resendSecondsLeft(now: OffsetDateTime): Int =
        if (mode == AuthMode.ForgotSent && resendAvailableAt?.let { at -> at > now } == true)
            ceil(Duration.between(now, resendAvailableAt!!).toNanos() / 1_000_000_000.0).toInt()
        else 0

    // ---------------- transitions ----------------

    /** "New here? Create an account" (from either sign-in flavour). */
    fun toCreateAccount(): Boolean = go(AuthMode.CreateAccount, mode == AuthMode.SignIn || mode == AuthMode.SwitchAccount)

    /** "Forgot password?" — the e-mail typed so far stays in the field. */
    fun toForgotPassword(): Boolean = go(AuthMode.ForgotPassword, mode == AuthMode.SignIn || mode == AuthMode.SwitchAccount)

    /** "Already have an account? Sign in" / "Back to sign in": the sign-in flavour the user came from. */
    fun toSignIn(): Boolean = go(signInMode, mode == AuthMode.CreateAccount || mode == AuthMode.ForgotPassword || mode == AuthMode.ForgotSent)

    /** The reset request was accepted (the same for known and unknown addresses), or "Resend" sent it again. */
    fun resetRequested(now: OffsetDateTime): Boolean {
        if (mode != AuthMode.ForgotPassword && mode != AuthMode.ForgotSent) return false
        resendAvailableAt = now + resendCooldown
        mode = AuthMode.ForgotSent
        fireChanged()
        return true
    }

    /** The reset failed after it was sent (link not opened in time, new password not set): send again from the e-mail step. */
    fun resetFailed(): Boolean = go(AuthMode.ForgotPassword, mode == AuthMode.ForgotSent || mode == AuthMode.ForgotPassword)

    /** "Switch account": the previous account was signed out quietly; sign in with another one. */
    fun startSwitch(previousEmail: String?) {
        this.previousEmail = if (previousEmail.isNullOrBlank()) null else previousEmail.trim()
        signInMode = AuthMode.SwitchAccount
        busy = false
        resendAvailableAt = null
        mode = AuthMode.SwitchAccount
        fireChanged()
    }

    /** Signed in, signed out normally or account deleted: back to the plain sign-in state. */
    fun reset() {
        previousEmail = null
        signInMode = AuthMode.SignIn
        busy = false
        resendAvailableAt = null
        mode = AuthMode.SignIn
        fireChanged()
    }

    fun setBusy(busy: Boolean) {
        if (this.busy == busy) return
        this.busy = busy
        fireChanged()
    }

    private fun go(target: AuthMode, allowed: Boolean): Boolean {
        if (!allowed || busy) return false
        if (target != AuthMode.ForgotSent) resendAvailableAt = null
        mode = target
        fireChanged()
        return true
    }

    companion object {
        /**
         * "Resend" becomes available this long after a reset link was requested. At least the auth server's per-address
         * e-mail interval (60 s): a Resend cancels the first link's listener, and an earlier resend is silently rate-limited
         * (reported as accepted, for enumeration safety), which would leave the user with no working link.
         */
        val resendCooldown: Duration = Duration.ofSeconds(60)

        fun fieldsFor(mode: AuthMode): List<AuthField> = when (mode) {
            AuthMode.SignIn, AuthMode.SwitchAccount -> listOf(AuthField.Email, AuthField.Password)
            AuthMode.CreateAccount -> listOf(AuthField.Email, AuthField.Password, AuthField.ConfirmPassword)
            AuthMode.ForgotPassword -> listOf(AuthField.Email)
            else -> emptyList()
        }
    }
}
