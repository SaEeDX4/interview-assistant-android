package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.uistate.AuthMode
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.uistate.SectionStatus
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Account page: the auth form's states and submit pipeline per mode (real CloudService over
 * fake transports), the reset pipeline, section loaders with tickets, billing with last-action
 * retry, device removal, the delete-account flow's ordering, sign-out/switch guards (the C#'s
 * HomeWindow.Account.cs).
 */
class HomeAccountTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private var fixture: HomeFixture? = null

    @Before
    fun setUp() {
        Loc.language = "en"
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        CloudService.urlLauncher = null
        Loc.language = "en"
    }

    private fun grant(req: Request, kind: String): Boolean =
        isAuth(req, "token") && req.url.queryParameter("grant_type") == kind

    private fun tokenJson(userId: String = VM_USER_A, email: String = "a@x.test"): String =
        """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"bearer","user":{"id":"$userId","email":"$email"}}"""

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    // ---------------- the form's states ----------------

    @Test
    fun form_SignInState_RendersHeadingFieldsLinks() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("account"))
        assertTrue(f.home.signedOutPanelVisible)
        assertEquals(Loc.t(f.home.auth.headingKey), f.home.authHeadingText)
        assertTrue(f.home.emailFieldVisible)
        assertTrue(f.home.passwordFieldVisible)
        assertFalse(f.home.confirmFieldVisible)
        assertTrue(f.home.forgotVisible) // the service offers e-mail + password
        assertTrue(f.home.providersVisible) // and Google (from the service's options)
        assertEquals(Loc.t("auth_create_link"), f.home.modeLinkText)
        assertEquals(Loc.t("auth_new_here"), f.home.modePromptText)
        assertEquals("Google", f.home.providerButtons.single().label.substringAfterLast(" "))
    }

    @Test
    fun form_ModeTransitions_CreateAndForgot() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("account"))
        f.home.onModeLinkClick()
        assertEquals(AuthMode.CreateAccount, f.home.auth.mode)
        assertTrue(f.home.confirmFieldVisible)
        assertEquals(Loc.t("sign_in"), f.home.modeLinkText)
        f.home.onModeLinkClick()
        assertEquals(AuthMode.SignIn, f.home.auth.mode)
        f.home.onForgotClick()
        assertEquals(AuthMode.ForgotPassword, f.home.auth.mode)
        assertFalse(f.home.passwordFieldVisible)
        assertFalse(f.home.forgotVisible)
    }

    @Test
    fun form_FieldEdit_ClearsItsOwnError() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("account"))
        f.home.authEmail = "not-an-email"
        f.home.submitAuthAsync()
        assertTrue(f.home.emailHasError)
        f.home.authEmail = "a@x.test" // typing clears the field's error, as in the C#
        assertFalse(f.home.emailHasError)
        assertEquals("", f.home.emailError.text)
    }

    // ---------------- submit pipeline ----------------

    @Test
    fun submit_InvalidEmail_FieldError() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.authEmail = "nope"
        f.home.authPassword = "password-1"
        f.home.submitAuthAsync()
        assertEquals(Loc.t("err_email_invalid"), f.home.emailError.text)
        assertFalse(f.vm.cloud.isSignedIn)
    }

    @Test
    fun submit_EmptyPassword_FieldError() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.authEmail = "a@x.test"
        f.home.authPassword = ""
        f.home.submitAuthAsync()
        assertEquals(Loc.t("err_password_empty"), f.home.passwordError.text)
    }

    @Test
    fun submit_CreateAccount_ShortPassword_AndMismatch() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.auth.toCreateAccount()
        f.home.applyAuthState()
        f.home.authEmail = "a@x.test"
        f.home.authPassword = "short"
        f.home.authConfirmPassword = "short"
        f.home.submitAuthAsync()
        assertEquals(Loc.t("err_password_short"), f.home.passwordError.text)
        f.home.authPassword = "password-1"
        f.home.authConfirmPassword = "password-2"
        f.home.submitAuthAsync()
        assertEquals(Loc.t("err_password_mismatch"), f.home.confirmError.text)
        assertTrue(f.home.confirmHasError)
    }

    @Test
    fun submit_SignIn_Success_OpensTheWorkspace() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.cloud.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson()
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.home.authEmail = "a@x.test"
        f.home.authPassword = "password-1"
        f.home.submitAuthAsync()
        assertTrue(f.vm.cloud.isSignedIn)
        assertEquals(AuthMode.SignIn, f.home.auth.mode) // the form reset for a later sign-out
        assertTrue(f.home.sections.summary.hasData)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_signed_in") })
        assertTrue(f.vm.hasWorkspace)
    }

    @Test
    fun submit_SignIn_WrongPassword_ShowsTheServiceError() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.cloud.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 400 to """{"error":"invalid_grant","error_description":"Invalid login credentials"}"""
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.home.authEmail = "a@x.test"
        f.home.authPassword = "wrong-password"
        f.home.submitAuthAsync()
        assertFalse(f.vm.cloud.isSignedIn)
        assertTrue(f.home.authMessage.isVisible)
        assertEquals(MessageKind.Error, f.home.authMessage.kind)
        assertTrue(f.home.authMessage.text.isNotEmpty())
    }

    @Test
    fun submit_CreateAccount_ConfirmationSent_GoesBackToSignIn() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.cloud.authFake.handler = { req, _ ->
            when {
                isAuth(req, "signup") -> 200 to """{"id":"$VM_USER_A","email":"a@x.test"}"""
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.home.auth.toCreateAccount()
        f.home.authEmail = "a@x.test"
        f.home.authPassword = "password-1"
        f.home.authConfirmPassword = "password-1"
        f.home.submitAuthAsync()
        assertFalse(f.vm.cloud.isSignedIn)
        assertEquals(AuthMode.SignIn, f.home.auth.mode)
        assertEquals(
            Loc.f("acct_confirm_email_sent", "\u2066a@x.test\u2069"),
            f.home.authMessage.text,
        )
        assertEquals(MessageKind.Success, f.home.authMessage.kind)
    }

    // ---------------- password reset ----------------

    @Test
    fun reset_FullFlow_NewPassword_SignsIn() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.cloud.authFake.handler = { req, _ ->
            when {
                isAuth(req, "recover") -> 200 to "{}"
                grant(req, "pkce") -> 200 to tokenJson()
                isAuth(req, "user") && req.method == "PUT" -> 200 to "{}"
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.home.authEmail = "a@x.test"
        f.home.onForgotClick()
        assertEquals(AuthMode.ForgotPassword, f.home.auth.mode)
        f.home.submitAuthAsync()
        assertTrue(f.vm.cloud.isSignedIn) // the link signed this account in and set the password
        assertEquals(AuthMode.SignIn, f.home.auth.mode)
        assertTrue(f.toasts.any { it.first == Loc.t("acct_password_reset_done") })
    }

    @Test
    fun resend_Cooldown_CountdownShown() = runBlocking {
        val f = signedOutHomeFixture().also { fixture = it }
        f.home.navigateAsync(HomeRoute("account"))
        f.home.auth.toForgotPassword()
        f.home.auth.resetRequested(f.nowLocal)
        f.home.applyAuthState()
        assertTrue(f.home.resendVisible)
        assertFalse(f.home.resendEnabled)
        assertEquals(Loc.f("auth_resend_in", 60), f.home.resendText)
        assertFalse(f.home.auth.canResend(f.nowLocal))
        assertTrue(f.home.auth.canResend(f.nowLocal.plusSeconds(61)))
    }

    // ---------------- sections ----------------

    @Test
    fun sections_SummaryFailure_ReportsInItsSection() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "me") && req.method == "GET") 500 to """{"code":"boom","message":"boom"}"""
            else base(req, body)
        }
        f.home.loadSummaryAsync()
        assertEquals(SectionStatus.Failed, f.home.sections.summary.status)
        assertEquals(Loc.t("acct_summary_failed"), f.home.summaryMessage.text)
    }

    @Test
    fun sections_Devices_LoadThenFailureKeepsTheList() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        var fail = false
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "devices") && req.method == "GET") {
                if (fail) 500 to """{"code":"boom","message":"boom"}""" else 200 to "[$vmDeviceJson]"
            } else {
                base(req, body)
            }
        }
        f.home.loadDevicesAsync()
        assertEquals(1, f.home.deviceRows.size)
        assertEquals("Test Phone", f.home.deviceRows.single().name)
        assertTrue(f.home.deviceRows.single().thisPcVisible)
        assertFalse(f.home.devicesEmptyVisible)
        fail = true
        f.home.loadDevicesAsync()
        assertEquals(SectionStatus.Failed, f.home.sections.devices.status)
        assertEquals(Loc.t("acct_devices_failed"), f.home.devicesMessage.text)
        assertEquals(1, f.home.deviceRows.size) // the saved list stays, marked stale by the section
    }

    @Test
    fun removeDevice_Confirm_RevokesAndReloads() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            when {
                isApi(req, "devices") && req.method == "GET" -> 200 to "[$vmDeviceJson]"
                isApiPrefix(req, "devices/") && req.method == "DELETE" -> 200 to "{}"
                else -> base(req, body)
            }
        }
        f.home.loadDevicesAsync()
        f.dialogs.confirmAnswer = true
        f.home.removeDeviceAsync(f.home.deviceRows.single())
        assertTrue(f.toasts.any { it.first == Loc.t("toast_device_removed") })
    }

    // ---------------- billing ----------------

    @Test
    fun billing_FailureThenRetry_RepeatsTheSameAction() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        CloudService.urlLauncher = { true }
        var portalCalls = 0
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "billing/portal") && req.method == "POST") {
                portalCalls++
                if (portalCalls == 1) 500 to """{"code":"boom","message":"boom"}"""
                else 200 to """{"url":"https://billing.test/portal"}"""
            } else {
                base(req, body)
            }
        }
        f.home.runBillingAsync { f.vm.cloud.openPortalAsync() }
        assertEquals(SectionStatus.Failed, f.home.sections.billing.status)
        assertEquals(Loc.t("acct_billing_failed"), f.home.billingMessage.text)
        f.home.onBillingMessageActionClick() // "Try again" repeats the portal action
        eventually("billing retry to succeed") { f.home.sections.billing.status == SectionStatus.Ready }
        assertEquals(2, portalCalls)
    }

    @Test
    fun billing_Upgrade_VisibleOnTrial_AndCheckoutRuns() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        CloudService.urlLauncher = { true }
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "billing/checkout") && req.method == "POST") {
                200 to """{"url":"https://checkout.test/pay"}"""
            } else {
                base(req, body)
            }
        }
        f.home.refreshAccount()
        assertTrue(f.home.upgradeVisible)
        assertEquals(Loc.f("upgrade_to_price", "Pro", "\$20"), f.home.upgradeText)
        f.home.upgradeAsync()
        assertEquals(SectionStatus.Ready, f.home.sections.billing.status)
    }

    // ---------------- delete account ----------------

    private fun accountWithSubscriptionJson(): String = """
    {
      "userId": "$VM_USER_A",
      "email": "a@x.test",
      "status": "active",
      "entitlements": {"planCode": "pro", "planName": "Pro", "status": "active", "maxProfiles": 3,
        "prepJobsPerMonth": 30, "liveMinutesPerPeriod": 600, "maxConcurrentSessions": 1, "maxDevices": 3,
        "reportRetentionDays": 365, "coachMode": true, "trialMinutes": 0},
      "usage": {"periodStartUtc": "2026-10-01T00:00:00Z", "periodEndUtc": "2026-11-01T00:00:00Z",
        "liveSecondsUsed": 0, "liveSecondsRemaining": 36000, "prepJobsUsed": 0, "prepJobsRemaining": 30,
        "answerRequestsToday": 0, "liveSecondsAvailableToday": -1, "dailyLiveMinutesCap": 0},
      "subscription": {"planCode": "pro", "status": "active", "currentPeriodEndUtc": "2026-12-01T00:00:00Z",
        "cancelAtPeriodEnd": false, "graceUntilUtc": null},
      "createdUtc": "2026-10-01T00:00:00Z",
      "billingState": "active"
    }
    """.trimIndent()

    @Test
    fun deleteAccount_FlowOrdering_AndCompletion() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = HomeFixture(cloud).also { fixture = it }
        runBlocking { cloud.service.refreshAsync() }
        f.vm.openWorkspaceForCurrentAccount()
        f.home.refreshAll()
        val base = f.cloud.apiFake.handler
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "me") && req.method == "DELETE") 200 to """{"identity":"deleted"}"""
            else base(req, body)
        }
        f.cloud.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson()
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.dialogs.deleteChoice = DeleteAccountChoice.Delete
        f.dialogs.deleteLocalAnswer = false
        f.home.deleteAccountAsync()
        // Order: the delete dialog first, then re-authentication, then the local-data question.
        val calls = f.dialogs.calls
        assertTrue(calls.indexOf("confirmDeleteAccount") in 0 until calls.indexOf("currentPassword"))
        assertTrue(calls.contains("askDeleteLocalData"))
        assertEquals(1, f.cloud.apiFake.countCalls { isApi(it, "me") && it.method == "DELETE" })
        assertFalse(f.vm.cloud.isSignedIn)
        assertEquals(Loc.t("acct_deleted"), f.home.authMessage.text)
    }

    @Test
    fun deleteAccount_BlockedSubscription_ManageBillingInstead() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.meBody = { accountWithSubscriptionJson() }
        val f = HomeFixture(cloud).also { fixture = it }
        runBlocking { cloud.service.refreshAsync() }
        f.vm.openWorkspaceForCurrentAccount()
        f.home.refreshAll()
        CloudService.urlLauncher = { true }
        val base = f.cloud.apiFake.handler
        var portalCalls = 0
        f.cloud.apiFake.handler = { req, body ->
            if (isApi(req, "billing/portal") && req.method == "POST") {
                portalCalls++
                200 to """{"url":"https://billing.test/portal"}"""
            } else {
                base(req, body)
            }
        }
        f.dialogs.deleteChoice = DeleteAccountChoice.ManageBilling
        f.home.deleteAccountAsync()
        assertEquals(0, f.cloud.apiFake.countCalls { isApi(it, "me") && it.method == "DELETE" })
        assertEquals(1, portalCalls) // sent to billing instead of deleting
        assertTrue(f.vm.cloud.isSignedIn)
    }

    @Test
    fun deleteAccount_KeepChoice_DoesNothing() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.dialogs.deleteChoice = DeleteAccountChoice.Keep
        f.home.deleteAccountAsync()
        assertEquals(0, f.cloud.apiFake.countCalls { isApi(it, "me") && it.method == "DELETE" })
        assertTrue(f.vm.cloud.isSignedIn)
    }

    // ---------------- sign out / switch ----------------

    @Test
    fun signOut_DirtyDraft_StayThenDiscard() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.addProfile()
        f.home.navigateAsync(HomeRoute("profiles"))
        f.home.profileName = "Edited Name"
        f.dialogs.unsavedAnswer = LeaveDecision.Stay
        f.home.signOutAsync()
        assertTrue(f.vm.cloud.isSignedIn) // stayed: still signed in
        f.dialogs.unsavedAnswer = LeaveDecision.Discard
        f.home.signOutAsync()
        assertFalse(f.vm.cloud.isSignedIn)
        assertEquals("account", f.home.currentPage)
    }

    @Test
    fun switchAccount_GoesToTheSwitchState() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.dialogs.confirmAnswer = true
        f.home.switchAccountAsync()
        assertFalse(f.vm.cloud.isSignedIn)
        assertEquals(AuthMode.SwitchAccount, f.home.auth.mode)
        assertEquals("a@x.test", f.home.auth.previousEmail)
        assertEquals("", f.home.authEmail)
        assertEquals("account", f.home.currentPage)
    }

    @Test
    fun changePassword_ReauthThenNewPassword() = runBlocking {
        val f = signedInHomeFixture().also { fixture = it }
        f.cloud.authFake.handler = { req, _ ->
            when {
                grant(req, "password") -> 200 to tokenJson()
                isAuth(req, "user") && req.method == "PUT" -> 200 to "{}"
                isAuth(req, "logout") -> 200 to "{}"
                else -> 404 to """{"error":"not_found","error_description":"no route"}"""
            }
        }
        f.dialogs.passwordToEnter = "correct-password"
        f.dialogs.newPasswordAnswer = "brand-new-password"
        f.home.changePasswordAsync()
        assertEquals(Loc.t("acct_password_changed"), f.home.securityMessage.text)
        assertTrue(f.toasts.any { it.first == Loc.t("acct_password_changed") })
    }
}
