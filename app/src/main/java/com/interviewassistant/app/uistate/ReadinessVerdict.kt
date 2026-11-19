package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.Loc

/** Mirror of `InterviewAssistant.App/UiState/ReadinessVerdict.cs`. */

/**
 * One check's result. [CheckOutcome.NotTested] is a test only the user can run with real meeting audio (the
 * transcription test through the service): it is offered with its action but neither passes nor warns, so a fully
 * working setup can still reach "Ready".
 */
enum class CheckOutcome { Pending, Pass, Warning, Fail, NotTested }

enum class VerdictKind { Checking, Ready, ReadyWithWarnings, NotReady }

/**
 * Overall pre-interview verdict. The rule the window states is the rule it applies: only FAIL blocks (NOT READY);
 * warnings are optional improvements (READY with warnings); a check that truly blocks an interview reports FAIL.
 */
object ReadinessVerdict {
    fun evaluate(outcomes: List<CheckOutcome>): VerdictKind {
        if (outcomes.any { it == CheckOutcome.Pending }) return VerdictKind.Checking
        if (outcomes.any { it == CheckOutcome.Fail }) return VerdictKind.NotReady
        return if (outcomes.any { it == CheckOutcome.Warning }) VerdictKind.ReadyWithWarnings else VerdictKind.Ready
    }
}

/** Copy for the enumeration-safe password reset (same text whether or not an account exists). */
object PasswordResetCopy {
    /** Shown after the reset request was accepted — for existing and unknown e-mail addresses alike. */
    val requested: String get() = Loc.t("acct_reset_requested")

    /** The same notice in a given UI language (tests check every language without switching the app's language). */
    fun requestedIn(language: String): String = Loc.format(language, "acct_reset_requested")
}
