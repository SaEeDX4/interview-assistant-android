package com.interviewassistant.app.uistate

import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.BillingStates
import com.interviewassistant.contracts.Entitlements
import com.interviewassistant.contracts.SubscriptionDto
import com.interviewassistant.contracts.UsageSummary
import com.interviewassistant.core.languages.Loc
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `Rc5AccountTests.cs` — the JVM-portable cases (auth form state machine, header account
 * menu, dashboard plan view, delete-account rules and copy).
 * Deferred by name (WPF wiring/source scans; counterparts in P4-D/E): Header_HasOneAccountButtonAndMenu_NoBadgeOrSignOutButton,
 * AccountPage_HasNoSignOutButton_AndSwitchAccountIsNamedSo, SwitchAccount_ConfirmsThenSignsOutQuietly_NormalSignOutKeepsItsToast,
 * ForgotPassword_IsADedicatedState_WiredToTheStateMachine, DeleteAccount_DialogThenReauthenticationThenDeletion_ThenASeparateLocalDataQuestion
 * (and the DeleteAccountDialog.cs source tail of DeleteDialog_TextsExist_AndAreTranslated_InAllTenLanguages).
 */
class Rc5AccountTests {
    companion object {
        private val languages = listOf("en", "es", "fr", "de", "pt", "it", "ar", "fa", "zh", "hi")

        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()

        private fun reach(mode: AuthMode): AuthFormState {
            val s = AuthFormState()
            when (mode) {
                AuthMode.CreateAccount -> s.toCreateAccount()
                AuthMode.ForgotPassword -> s.toForgotPassword()
                AuthMode.ForgotSent -> {
                    s.toForgotPassword(); s.resetRequested(OffsetDateTime.now())
                }
                AuthMode.SwitchAccount -> s.startSwitch("a@b.test")
                else -> {}
            }
            return s
        }
    }

    // ---------------- auth form state machine ----------------

    @Test
    fun authForm_StartsAtSignIn_WithEmailAndPassword() {
        val s = AuthFormState()
        assertEquals(AuthMode.SignIn, s.mode)
        assertEquals(listOf(AuthField.Email, AuthField.Password), s.visibleFields)
        assertEquals("sign_in", s.primaryKey)
        assertEquals("auth_signin_title", s.headingKey)
        assertTrue(s.showsForgotLink && s.showsCreateLink && s.showsProviders)
        assertFalse(s.showsSignInLink || s.showsBackLink || s.showsResend || s.showsLegal)
    }

    @Test
    fun authForm_EveryState_HasItsFieldsAndOnePrimaryButton() {
        // (mode, fields, primary)
        val cases = listOf(
            Triple(AuthMode.SignIn, listOf(AuthField.Email, AuthField.Password), "sign_in"),
            Triple(AuthMode.CreateAccount, listOf(AuthField.Email, AuthField.Password, AuthField.ConfirmPassword), "create_account"),
            Triple(AuthMode.ForgotPassword, listOf(AuthField.Email), "auth_send_reset_link"),
            Triple(AuthMode.ForgotSent, emptyList<AuthField>(), "auth_back_to_sign_in"),
            Triple(AuthMode.SwitchAccount, listOf(AuthField.Email, AuthField.Password), "sign_in"),
        )
        for ((mode, fields, primary) in cases) {
            val s = reach(mode)
            assertEquals(mode, s.mode)
            assertEquals(fields, s.visibleFields)
            assertEquals(primary, s.primaryKey)
            assertTrue(s.headingKey, Loc.has(s.headingKey))
            s.subtitleKey?.let { k -> assertTrue(k, Loc.has(k)) }
            s.busyKey?.let { k -> assertTrue(k, Loc.has(k)) }
            assertTrue(Loc.has(s.primaryKey))
        }
    }

    @Test
    fun authForm_ForgotPassword_HasOnlyTheEmailField_AndABackLink() {
        val s = reach(AuthMode.ForgotPassword)
        assertEquals(listOf(AuthField.Email), s.visibleFields)
        assertFalse(s.shows(AuthField.Password) || s.shows(AuthField.ConfirmPassword))
        assertTrue(s.showsBackLink)
        assertFalse(s.showsProviders || s.showsCreateLink || s.showsForgotLink)
        assertEquals("auth_sending_reset", s.busyKey)
    }

    @Test
    fun authForm_Transitions_FollowTheLinks() {
        val s = AuthFormState()
        assertFalse(s.toSignIn()) // already signed-in state: nothing to go back to
        assertTrue(s.toCreateAccount()); assertEquals(AuthMode.CreateAccount, s.mode)
        assertFalse(s.toForgotPassword()) // no forgot link while creating an account
        assertTrue(s.toSignIn()); assertEquals(AuthMode.SignIn, s.mode)
        assertTrue(s.toForgotPassword()); assertEquals(AuthMode.ForgotPassword, s.mode)
        assertFalse(s.toCreateAccount())
        assertTrue(s.toSignIn()); assertEquals(AuthMode.SignIn, s.mode)
        assertFalse(s.resetRequested(OffsetDateTime.now())) // only from the forgot-password state
    }

    @Test
    fun authForm_Busy_BlocksEveryTransition() {
        val s = AuthFormState()
        var changes = 0
        s.addChangedListener { changes++ }
        s.setBusy(true)
        assertFalse(s.toCreateAccount())
        assertFalse(s.toForgotPassword())
        assertEquals(AuthMode.SignIn, s.mode)
        s.setBusy(false)
        assertTrue(s.toCreateAccount())
        assertEquals(3, changes) // busy on, busy off, create account
    }

    @Test
    fun authForm_ResetSent_ResendAfterCooldown_BackToSignIn() {
        val t0 = OffsetDateTime.parse("2026-10-17T10:00:00Z")
        val s = reach(AuthMode.ForgotPassword)
        assertTrue(s.resetRequested(t0))
        assertEquals(AuthMode.ForgotSent, s.mode)
        assertTrue(s.visibleFields.isEmpty())
        assertFalse(s.primarySubmits)
        assertTrue(s.showsResend)
        assertFalse(s.canResend(t0.plusSeconds(5)))
        // The cooldown is 60 s (was 30 s): the auth server sends at most one recovery e-mail per address per 60 s, so an
        // earlier Resend would cancel the working link while the new request is silently rate-limited.
        assertTrue(AuthFormState.resendCooldown >= Duration.ofSeconds(60))
        assertEquals(55, s.resendSecondsLeft(t0.plusSeconds(5)))
        assertFalse(s.canResend(t0.plusSeconds(45))) // still inside the server's per-address interval
        assertTrue(s.canResend(t0 + AuthFormState.resendCooldown))
        assertEquals(0, s.resendSecondsLeft(t0 + AuthFormState.resendCooldown))
        s.setBusy(true)
        assertFalse(s.canResend(t0.plusMinutes(5))) // a resend is running
        s.setBusy(false)
        assertTrue(s.resetRequested(t0.plusMinutes(1))) // resent: the cooldown starts again
        assertFalse(s.canResend(t0.plusMinutes(1).plusSeconds(1)))
        assertTrue(s.resetFailed()) // link expired: back to the e-mail step
        assertEquals(AuthMode.ForgotPassword, s.mode)
        assertNull(s.resendAvailableAt)
        assertTrue(s.toSignIn())
        assertEquals(AuthMode.SignIn, s.mode)
    }

    @Test
    fun authForm_SwitchAccount_HasItsOwnHeadline_AndBackReturnsToIt() {
        val s = AuthFormState()
        s.startSwitch("old@example.test")
        assertEquals(AuthMode.SwitchAccount, s.mode)
        assertEquals("sign_in_another", s.headingKey)
        assertEquals("Sign in with another account", Loc.tableFor("en")[s.headingKey])
        assertEquals("auth_switch_subtitle", s.subtitleKey)
        assertEquals("old@example.test", s.previousEmail)
        assertEquals(listOf(AuthField.Email, AuthField.Password), s.visibleFields)
        assertTrue(s.toForgotPassword())
        assertTrue(s.toSignIn())
        assertEquals(AuthMode.SwitchAccount, s.mode) // "Back to sign in" keeps the switch-account context
        assertTrue(s.toCreateAccount())
        assertTrue(s.toSignIn())
        assertEquals(AuthMode.SwitchAccount, s.mode)
        s.reset() // signed in: a later sign-out starts at the plain sign-in
        assertEquals(AuthMode.SignIn, s.mode)
        assertNull(s.previousEmail)
        AuthFormState().startSwitch(null)
        val plain = AuthFormState(); plain.startSwitch("  ")
        assertEquals("auth_switch_subtitle_plain", plain.subtitleKey)
    }

    // ---------------- header account menu ----------------

    @Test
    fun accountMenu_Items_Order_Labels_AndKeyboard() {
        assertEquals(
            listOf(AccountMenuItem.ManageAccount, AccountMenuItem.Settings, AccountMenuItem.SwitchAccount, AccountMenuItem.SignOut),
            AccountMenu.items,
        )
        for (lang in languages)
            for (item in AccountMenu.items)
                assertFalse(Loc.tableFor(lang)[AccountMenu.labelKey(item)].isNullOrBlank())
        assertEquals("account", AccountMenu.route(AccountMenuItem.ManageAccount))
        assertEquals("settings", AccountMenu.route(AccountMenuItem.Settings))
        assertNull(AccountMenu.route(AccountMenuItem.SignOut))
        assertEquals(1, AccountMenu.move(0, 4, down = true))
        assertEquals(0, AccountMenu.move(3, 4, down = true)) // wraps
        assertEquals(3, AccountMenu.move(0, 4, down = false))
        assertEquals(0, AccountMenu.move(-1, 4, down = true)) // nothing focused yet
        assertEquals(3, AccountMenu.move(-1, 4, down = false))
        assertTrue(AccountMenu.opensMenu(signedIn = true))
        assertFalse(AccountMenu.opensMenu(signedIn = false)) // signed out: "Sign in" goes to the Account page
        assertEquals("S", AccountMenu.initial("sam@example.test"))
        assertEquals("7", AccountMenu.initial("_7x@example.test"))
        assertEquals("?", AccountMenu.initial(""))
    }

    // ---------------- dashboard ----------------

    @Test
    fun dashboard_StacksBelowTheBreakpoint() {
        // rc.5 UX audit M3: the breakpoint moved from 900 to 720 so the default window shows two columns
        val cases = listOf(719.0 to 1, 720.0 to 2, 772.0 to 2, 1600.0 to 2, 400.0 to 1)
        for ((width, columns) in cases) assertEquals(columns, AccountLayout.dashboardColumns(width))
    }

    @Test
    fun planView_BadgesAndMetrics() {
        for (state in listOf(
            BillingStates.Trial, BillingStates.TrialExhausted, BillingStates.Active, BillingStates.Canceling,
            BillingStates.PastDue, BillingStates.Paused, BillingStates.Expired, BillingStates.Revoked,
        )) {
            for (lang in languages)
                assertTrue(state, Loc.tableFor(lang).containsKey(PlanView.badgeKey(state)))
        }
        assertTrue(PlanView.isPaid(BillingStates.Paused))
        assertFalse(PlanView.isPaid(BillingStates.Trial))
        assertEquals(PlanTone.Warning, PlanView.badgeTone(BillingStates.PastDue))
        val before = Loc.language
        Loc.language = "en"
        try {
            val e = Entitlements("pro", "Pro", "active", 5, 20, 600, 1, 3, 90, true, 30)
            val u = UsageSummary(
                OffsetDateTime.now(), OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                150 * 60, 450 * 60, 4, 16, 0, 120 * 60, 180,
            )
            val a = AccountDto(UUID.randomUUID(), "a@b.test", "active", e, u, null, OffsetDateTime.now(), BillingStates.Active)
            val m = PlanView.metrics(a) { d -> d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }
            assertEquals(listOf("minutes_used", "minutes_remaining", "available_today", "preparations", "limits"), m.map { x -> x.id })
            assertEquals("150 of 600 min", m[0].value)
            assertEquals(0.25, m[0].progress!!, 0.0)
            assertEquals("120 min", m[2].value)
            assertEquals(0.2, m[3].progress!!, 0.0)
            assertNull(m[4].progress)
        } finally {
            Loc.language = before
        }
        assertEquals(0.0, PlanView.fraction(5, 0), 0.0)
        assertEquals(1.0, PlanView.fraction(9, 3), 0.0)
    }

    // ---------------- delete account ----------------

    @Test
    fun deletion_SubscriptionRule_MirrorsTheService() {
        // (status, cancelAtPeriodEnd, blocks)
        val cases = listOf(
            Triple("active", false, true),
            Triple("active", true, false), // already set to end: the service lets the deletion through
            Triple("trialing", false, true),
            Triple("past_due", false, true),
            Triple("paused", false, true),
            Triple("canceled", false, false),
        )
        for ((status, cancelAtPeriodEnd, blocks) in cases)
            assertEquals(blocks, AccountDeletion.subscriptionBlocks(SubscriptionDto("pro", status, null, cancelAtPeriodEnd, null)))
    }

    @Test
    fun deleteDialog_TextsExist_AndAreTranslated_InAllTenLanguages() {
        assertFalse(AccountDeletion.subscriptionBlocks(null))
        val en = Loc.tableFor("en")
        assertEquals("Permanently delete your account?", en["del_title"])
        assertEquals("This action cannot be undone.", en["del_irreversible"])
        assertEquals("Keep account", en["del_keep"])
        assertEquals("Delete permanently", en["del_confirm"])
        assertEquals("Paddle keeps the billing records it is legally required to keep.", en["del_note_paddle"])
        for (lang in languages) {
            val t = Loc.tableFor(lang)
            for (k in AccountDeletion.dialogKeys) {
                val v = t[k]
                assertTrue("$lang: $k", v != null && v.trim().isNotEmpty())
                if (lang != "en") assertTrue("$lang: $k not translated", en[k] != v)
            }
        }
    }
}
