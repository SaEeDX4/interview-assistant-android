package com.interviewassistant.app.uistate

import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.BillingStates
import com.interviewassistant.contracts.SubscriptionDto
import com.interviewassistant.core.languages.Loc
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.math.min

/** Mirror of `InterviewAssistant.App/UiState/AccountView.cs`. */

/** Responsive layout of the Account dashboard (two columns on wide content, one column below the breakpoint). */
object AccountLayout {
    /**
     * Content width (device-independent pixels) from which Plan/Devices and Security/Privacy sit side by side. 720 puts
     * the default 1040 px window (about 770 px of content) and 1366×768 at 125 % in two columns (rc.5 UX audit M3).
     */
    const val twoColumnMinWidth: Double = 720.0
    /** Content width from which the signed-out page shows the product column next to the form. */
    const val authTwoColumnMinWidth: Double = 760.0
    /** Maximum width of the signed-out page (product column + form), and of its single centred column. */
    const val authMaxWidth: Double = 940.0
    const val authNarrowMaxWidth: Double = 460.0

    fun dashboardColumns(contentWidth: Double): Int = if (contentWidth >= twoColumnMinWidth) 2 else 1
    fun authColumns(contentWidth: Double): Int = if (contentWidth >= authTwoColumnMinWidth) 2 else 1
}

enum class PlanTone { Neutral, Accent, Positive, Warning, Problem }

/** One metric card of "Plan and usage": label, value, optional detail line and optional usage bar (0..1). */
data class UsageMetric(val id: String, val label: String, val value: String, val detail: String, val progress: Double?)

/**
 * What the "Plan and usage" card shows for an account summary. Plain class (no WPF): the server computes the billing
 * state and the numbers, this only maps them to words, badges and bars.
 */
object PlanView {
    /** Badge text key of the server's billing state. */
    fun badgeKey(billingState: String?): String = when (billingState) {
        BillingStates.Trial -> "acct_state_trial"
        BillingStates.TrialExhausted -> "acct_state_trial_used"
        BillingStates.Active -> "acct_state_active"
        BillingStates.Canceling -> "acct_state_canceling"
        BillingStates.PastDue -> "acct_state_past_due"
        BillingStates.Paused -> "acct_state_paused"
        BillingStates.Expired -> "acct_state_expired"
        BillingStates.Revoked -> "acct_state_revoked"
        else -> "acct_state_active"
    }

    fun badgeTone(billingState: String?): PlanTone = when (billingState) {
        BillingStates.Active -> PlanTone.Positive
        BillingStates.Trial -> PlanTone.Accent
        BillingStates.Canceling, BillingStates.PastDue, BillingStates.Paused -> PlanTone.Warning
        BillingStates.TrialExhausted, BillingStates.Expired, BillingStates.Revoked -> PlanTone.Problem
        else -> PlanTone.Neutral
    }

    /** A paid plan (or a paused one that can be resumed): Upgrade is not offered as the primary action. */
    fun isPaid(billingState: String?): Boolean =
        billingState == BillingStates.Active || billingState == BillingStates.Canceling ||
            billingState == BillingStates.PastDue || billingState == BillingStates.Paused

    /** Metric cards: minutes used and remaining this period, available today, preparations left, plan limits. */
    fun metrics(a: AccountDto, formatDate: (OffsetDateTime) -> String): List<UsageMetric> {
        val e = a.entitlements
        val u = a.usage
        val trial = a.billingState == BillingStates.Trial || a.billingState == BillingStates.TrialExhausted
        val plan = max(0, if (trial) e.trialMinutes else e.liveMinutesPerPeriod)
        val used = max(0, u.liveSecondsUsed / 60)
        val remaining = max(0, u.liveSecondsRemaining / 60)
        val list = mutableListOf(
            UsageMetric("minutes_used", Loc.t("usage_minutes_used"), Loc.f("usage_minutes_used_value", used, plan), "", fraction(used, plan)),
            UsageMetric("minutes_remaining", Loc.t("usage_remaining_period"), Loc.f("acct_metric_minutes", remaining), Loc.f("acct_metric_until", formatDate(u.periodEndUtc)), null),
        )
        if (u.liveSecondsAvailableToday >= 0)
            list.add(
                UsageMetric(
                    "available_today", Loc.t("usage_available_today"), Loc.f("acct_metric_minutes", min(u.liveSecondsAvailableToday / 60, remaining)),
                    Loc.f("acct_metric_daily_cap", u.dailyLiveMinutesCap), null,
                ),
            )
        val prepTotal = u.prepJobsUsed + u.prepJobsRemaining
        list.add(
            UsageMetric(
                "preparations", Loc.t("usage_preparations"), Loc.f("usage_preparations_value", max(0, u.prepJobsRemaining)),
                if (prepTotal > 0) Loc.f("acct_metric_prep_used", u.prepJobsUsed, prepTotal) else "", if (prepTotal > 0) fraction(u.prepJobsUsed, prepTotal) else null,
            ),
        )
        list.add(UsageMetric("limits", Loc.t("usage_limits"), Loc.f("usage_limits_value", e.maxDevices, e.maxProfiles), "", null))
        return list
    }

    /** 0..1, never NaN (a plan without minutes shows an empty bar). */
    fun fraction(part: Int, total: Int): Double = if (total <= 0) 0.0 else (part.toDouble() / total).coerceIn(0.0, 1.0)
}

/** Account deletion rules the dialog explains (mirrors the service: a subscription that can still charge blocks deletion). */
object AccountDeletion {
    /**
     * True when the service refuses deletion because the subscription can still charge or resume: active without a
     * scheduled cancellation, trialing, past due or paused. A subscription already set to end does not block.
     */
    fun subscriptionBlocks(s: SubscriptionDto?): Boolean = s != null &&
        ((s.status == "active" && !s.cancelAtPeriodEnd) || s.status == "trialing" || s.status == "past_due" || s.status == "paused")

    /** Every text of the delete dialog and the follow-up local-data question (checked in all languages). */
    val dialogKeys: List<String> = listOf(
        "del_title", "del_irreversible", "del_will_be_deleted", "del_item_account", "del_item_plan", "del_item_devices", "del_item_service_data",
        "del_note_subscription", "del_note_subscription_blocks", "del_note_paddle", "del_note_providers", "del_note_local", "del_keep", "del_confirm",
        "del_local_title", "del_local_message", "del_local_keep", "del_local_delete",
    )
}
