package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.CloudState
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.Entitlements
import com.interviewassistant.contracts.SubscriptionDto
import com.interviewassistant.contracts.UsageSummary
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Account-surface checks: the BillingLabel table (the C#'s static, fed with constructed accounts —
 * same states/values as the Windows checks), AccountLabel/ServiceBanner by CloudState and the minutes
 * label/tooltip, driven through a real CloudService over fake transports (the P4-C1 pattern), plus the
 * SelfTest's signed-out start and sign-out quiet rules.
 */
class MainViewModelAccountTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private var fixture: VmFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
    }

    private fun fixture(cloud: VmCloudFixture): VmFixture = VmFixture(cloud).also { fixture = it }

    // ---------------- BillingLabel (static table) ----------------

    private fun account(
        billingState: String,
        planName: String = "Pro",
        liveSecondsRemaining: Int = 1800,
        periodEnd: OffsetDateTime? = null,
    ) = AccountDto(
        userId = UUID.fromString(VM_USER_A),
        email = "a@x.test",
        status = "active",
        entitlements = Entitlements("pro", planName, "active", 3, 30, 600, 1, 3, 365, true, 30),
        usage = UsageSummary(
            OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-11-01T00:00:00Z"),
            0, liveSecondsRemaining, 0, 3, 0, -1, 0,
        ),
        subscription = periodEnd?.let { SubscriptionDto("pro", "active", it, false, null) },
        createdUtc = OffsetDateTime.parse("2026-10-01T00:00:00Z"),
        billingState = billingState,
    )

    @Test
    fun billingLabel_Table() {
        assertEquals("Trial · 30 min left", MainViewModel.billingLabel(account("trial")))
        assertEquals("Trial · 0 min left", MainViewModel.billingLabel(account("trial", liveSecondsRemaining = 59)))
        assertEquals("Trial used up", MainViewModel.billingLabel(account("trial_exhausted")))
        assertEquals("Pro", MainViewModel.billingLabel(account("active")))
        assertEquals("Pro · payment problem", MainViewModel.billingLabel(account("past_due")))
        assertEquals("Subscription paused", MainViewModel.billingLabel(account("paused")))
        assertEquals("Subscription ended", MainViewModel.billingLabel(account("expired")))
        assertEquals("Pro ended — payment refunded", MainViewModel.billingLabel(account("revoked")))
        assertEquals("Pro", MainViewModel.billingLabel(account("something_else")))
    }

    @Test
    fun billingLabel_Canceling_ShowsPeriodEndDate() {
        val end = OffsetDateTime.parse("2026-11-01T00:00:00Z")
        val expectedDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(LanguageRegistry.formattingLocale("en"))
            .format(end.atZoneSameInstant(ZoneId.systemDefault()))
        assertEquals("Pro · ends $expectedDate", MainViewModel.billingLabel(account("canceling", periodEnd = end)))
        // No subscription on file: the date slot renders empty (the C#'s null-format rule).
        assertEquals("Pro · ends ", MainViewModel.billingLabel(account("canceling")))
    }

    // ---------------- AccountLabel / ServiceBanner by state ----------------

    @Test
    fun accountLabel_SignedIn_ShowsEmailAndBilling() = runBlocking {
        val f = fixture(VmCloudFixture(session = vmSession()))
        f.cloud.service.refreshAsync()
        assertEquals(CloudState.SignedIn, f.cloud.service.state)
        assertEquals("a@x.test · Trial · 30 min left", f.vm.accountLabel)
        assertEquals("", f.vm.serviceBanner)
    }

    @Test
    fun serviceBanner_TrialExhausted_UpgradePrompt() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.meBody = { vmAccountJson(billingState = "trial_exhausted", liveSecondsRemaining = 0) }
        val f = fixture(cloud)
        f.cloud.service.refreshAsync()
        assertEquals("a@x.test · Trial used up", f.vm.accountLabel)
        assertEquals("Trial used up — upgrade or manage billing in Home → Account.", f.vm.serviceBanner)
    }

    @Test
    fun accountLabel_SignedOut() = runBlocking {
        val f = fixture(VmCloudFixture(session = null))
        f.cloud.service.refreshAsync()
        assertEquals(CloudState.SignedOut, f.cloud.service.state)
        assertEquals("Not signed in", f.vm.accountLabel)
        assertEquals("", f.vm.serviceBanner)
    }

    @Test
    fun accountLabel_NotConfigured_BannerExplains() {
        val f = fixture(VmCloudFixture(bundledJson = "{}", session = null))
        assertEquals(CloudState.NotConfigured, f.cloud.service.state)
        assertEquals("Service not configured", f.vm.accountLabel)
        assertEquals(Loc.t("acct_not_configured_long"), f.vm.serviceBanner)
    }

    @Test
    fun accountLabel_ServiceUnavailable_BannerOffline() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = fixture(cloud)
        f.cloud.service.refreshAsync()
        assertEquals(CloudState.SignedIn, f.cloud.service.state)
        val base = cloud.apiFake.handler
        cloud.apiFake.handler = { req, body ->
            if (isApi(req, "config")) throw IOException("network down") else base(req, body)
        }
        f.cloud.service.refreshAsync()
        assertEquals(CloudState.ServiceUnavailable, f.cloud.service.state)
        assertEquals("Service unavailable", f.vm.accountLabel)
        assertEquals(
            "Offline — prepared answers and typed questions with prepared answers still work. Reconnecting automatically.",
            f.vm.serviceBanner,
        )
    }

    @Test
    fun accountLabel_UpdateRequired() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = fixture(cloud)
        val base = cloud.apiFake.handler
        cloud.apiFake.handler = { req, body ->
            if (isApi(req, "config")) 426 to """{"code":"update_required","message":"Please update"}""" else base(req, body)
        }
        f.cloud.service.refreshAsync()
        assertEquals(CloudState.UpdateRequired, f.cloud.service.state)
        assertEquals("Update required", f.vm.accountLabel)
    }

    // ---------------- minutes ----------------

    @Test
    fun minutes_PeriodOnly_LabelAndTooltip() = runBlocking {
        val f = fixture(VmCloudFixture(session = vmSession()))
        f.cloud.service.refreshAsync()
        assertEquals("30/30 min", f.vm.minutesLeftLabel)
        assertEquals(
            "30 of 30 live minutes remaining this billing period.\n" +
                "Paused time is never counted. Typed questions don't use live minutes.",
            f.vm.minutesTooltip,
        )
    }

    @Test
    fun minutes_TodayBelowPeriod_LabelAddsToday() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.meBody = { vmAccountJson(liveSecondsAvailableToday = 900) }
        val f = fixture(cloud)
        f.cloud.service.refreshAsync()
        assertEquals("30/30 min · 15 today", f.vm.minutesLeftLabel)
        assertTrue(f.vm.minutesTooltip.contains("15 minutes available today."))
    }

    @Test
    fun minutes_DailyCap_ShownInTooltip() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.meBody = { vmAccountJson(liveSecondsAvailableToday = 900, dailyLiveMinutesCap = 240) }
        val f = fixture(cloud)
        f.cloud.service.refreshAsync()
        assertTrue(f.vm.minutesTooltip.contains("(daily safety limit 240 min, resets 00:00 UTC)"))
    }

    @Test
    fun minutes_NoPlanFigure_PlainLabel() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.meBody = {
            vmAccountJson(billingState = "active", liveSecondsRemaining = 1500, planName = "Pro", trialMinutes = 0, liveMinutesPerPeriod = 0)
        }
        val f = fixture(cloud)
        f.cloud.service.refreshAsync()
        assertEquals("25 min left", f.vm.minutesLeftLabel)
        assertTrue(f.vm.minutesTooltip.startsWith("25 live minutes remaining this billing period."))
    }

    // ---------------- start gating + sign-out (SelfTest) ----------------

    // SelfTest: "Signed out: Start explains how to sign in and does not listen"
    @Test
    fun start_SignedOut_NoteSaysSignIn_NotListening() = runBlocking {
        val f = fixture(VmCloudFixture(session = null))
        f.vm.initialize()
        f.vm.note = null
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        assertEquals(Loc.t("note_sign_in_to_listen"), f.vm.note)
        assertTrue(f.vm.note!!.contains("Sign in"))
    }

    @Test
    fun start_NotConfigured_NoteExplains() = runBlocking {
        val f = fixture(VmCloudFixture(bundledJson = "{}", session = null))
        f.vm.initialize()
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        assertEquals(Loc.t("note_not_configured_listen"), f.vm.note)
    }

    // SignOut quiet: "Switch account" shows no toast; a normal sign-out does.
    @Test
    fun signOut_Quiet_NoToast_NormalSignOut_Toasts() = runBlocking {
        val f = fixture(VmCloudFixture(session = vmSession()))
        f.vm.initialize()
        f.cloud.service.refreshAsync()
        f.toasts.clear()
        f.vm.signOutAsync(quiet = true)
        assertTrue(f.toasts.none { it.first == Loc.t("toast_signed_out") })
        f.vm.signOutAsync(quiet = false)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_signed_out") })
    }
}
