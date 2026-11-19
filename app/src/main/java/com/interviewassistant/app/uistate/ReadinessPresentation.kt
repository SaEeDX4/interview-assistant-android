package com.interviewassistant.app.uistate

/** Mirror of `InterviewAssistant.App/UiState/ReadinessPresentation.cs`. */

/** The (format key, arguments) of the one-line readiness summary every surface shows. */
data class ReadinessSummary(val key: String, val args: List<Any>)

/**
 * How every readiness surface presents the one [ReadinessSnapshot]: labels, icons, fixes and their routes,
 * the summary line and the log line of a state change. No Android types (unit-tested); the pages only map keys to text.
 */
object ReadinessPresentation {
    /** The state whose colour and icon a single check outcome shares (no result yet = not checked). */
    fun stateOf(outcome: CheckOutcome?): ReadinessState = when (outcome) {
        CheckOutcome.Pass -> ReadinessState.Ready
        CheckOutcome.Warning -> ReadinessState.ReadyWithWarnings
        CheckOutcome.Fail -> ReadinessState.NotReady
        CheckOutcome.Pending -> ReadinessState.Checking
        else -> ReadinessState.NotChecked // no result yet, or an optional test not run (neutral grey)
    }

    /** Badge text of one check (PASS / WARNING / FAIL are the words the page subtitle explains). */
    fun statusKey(outcome: CheckOutcome?): String = when (outcome) {
        CheckOutcome.Pass -> "pf_pass"
        CheckOutcome.Warning -> "pf_warning"
        CheckOutcome.Fail -> "pf_fail"
        CheckOutcome.Pending -> "pf_checking"
        CheckOutcome.NotTested -> "rd_status_not_tested"
        else -> "rd_state_not_checked"
    }

    /** Segoe Fluent glyph that goes with a state: the badge is never colour alone. (Kept 1:1 as codepoints; the Compose layer maps states to Material icons — P4-PORT-NOTES DC-67.) */
    fun icon(s: ReadinessState): String = when (s) {
        ReadinessState.Ready -> "\uE73E"              // check mark
        ReadinessState.ReadyWithWarnings -> "\uE7BA"  // warning
        ReadinessState.NotReady -> "\uE711"           // cross
        ReadinessState.Checking -> "\uE895"           // sync
        ReadinessState.Outdated -> "\uE823"           // clock
        else -> "\uE9CE"                                  // unknown
    }

    /** Row label. Developer builds without the service check the own OpenAI key instead of the account. */
    fun titleKey(id: ReadinessCheckId, usingCloud: Boolean): String = when (id) {
        ReadinessCheckId.PlaybackDevice -> "pf_check_device"
        ReadinessCheckId.AudioSignal -> "pf_check_signal"
        ReadinessCheckId.AccountPlan -> if (usingCloud) "pf_check_account" else "pf_check_auth_dev"
        ReadinessCheckId.Transcription -> "pf_check_stt"
        ReadinessCheckId.AnswerEngine -> "pf_check_answer"
        ReadinessCheckId.InterviewPrepared -> "pf_check_prepared"
        ReadinessCheckId.PreparedAnswers -> "pf_check_bank"
        else -> "pf_check_topmost"
    }

    /** The plain "how to fix it" sentence listed under "What blocks the interview". */
    fun fixHintKey(id: ReadinessCheckId, usingCloud: Boolean): String = when (id) {
        ReadinessCheckId.PlaybackDevice -> "pf_fix_device"
        ReadinessCheckId.AudioSignal -> "pf_fix_signal"
        ReadinessCheckId.AccountPlan -> if (usingCloud) "pf_fix_account" else "pf_fix_auth_dev"
        ReadinessCheckId.Transcription -> if (usingCloud) "pf_fix_stt" else "pf_fix_stt_dev"
        ReadinessCheckId.AnswerEngine -> if (usingCloud) "pf_fix_answer" else "pf_fix_answer_dev"
        ReadinessCheckId.InterviewPrepared -> "pf_fix_prepared"
        ReadinessCheckId.PreparedAnswers -> "pf_fix_bank"
        else -> "pf_fix_topmost"
    }

    /** Label of a check's action button; null = the check offers no action. */
    fun fixLabelKey(fix: ReadinessFix): String? = when (fix) {
        ReadinessFix.OpenAudioSettings -> "rd_fix_audio_settings"
        ReadinessFix.TestAudio -> "pf_test_audio"
        ReadinessFix.SignIn -> "gs_action_sign_in"
        ReadinessFix.OpenAccount -> "rd_fix_open_account"
        ReadinessFix.TestTranscription -> "pf_test_stt"
        ReadinessFix.TestAi -> "pf_test_ai"
        ReadinessFix.OpenInterviews -> "rd_fix_open_interviews"
        ReadinessFix.PrepareInterview -> "gs_action_prepare"
        ReadinessFix.OpenWindowSettings -> "rd_fix_window_settings"
        else -> null
    }

    /** Home deep link of a fix; null for the test tools, which run on the page itself. */
    fun fixRoute(fix: ReadinessFix): String? = when (fix) {
        ReadinessFix.OpenAudioSettings -> "settings/audio"
        ReadinessFix.SignIn, ReadinessFix.OpenAccount -> "account"
        ReadinessFix.OpenInterviews, ReadinessFix.PrepareInterview -> "interviews"
        ReadinessFix.OpenWindowSettings -> "settings/window"
        else -> null
    }

    /** Start interview is offered only when nothing blocks. */
    fun canStart(s: ReadinessState): Boolean = s == ReadinessState.Ready || s == ReadinessState.ReadyWithWarnings

    /** Opening the page runs the checks when there is no current result. */
    fun shouldAutoRun(s: ReadinessState): Boolean = s == ReadinessState.NotChecked || s == ReadinessState.Outdated

    /**
     * Passed checks to show for [currentContext]: a result for another account, interview or device
     * (out of date) or no result at all shows 0 of 8 — never numbers that belong to something else.
     */
    fun shownPassed(s: ReadinessSnapshot, currentContext: String): Int =
        if (s.checks.isNotEmpty() && (s.isRunning || s.context == currentContext)) s.passed else 0

    /**
     * The summary line every surface shows ("7 of 8 checks passed"; with an optional test not run yet:
     * "7 of 7 checks passed · 1 optional test to run"): key and arguments, one helper for the page, Home and Live.
     */
    fun summary(s: ReadinessSnapshot, currentContext: String): ReadinessSummary {
        val passed = shownPassed(s, currentContext)
        val current = s.checks.isNotEmpty() && (s.isRunning || s.context == currentContext)
        return if (current && s.notTested > 0) ReadinessSummary("rd_summary_passed_untested", listOf(passed, s.required, s.notTested))
        else ReadinessSummary("rd_summary_passed", listOf(passed, s.total))
    }

    /** Optional tests still to run (shown under "Ready" as a gentle to-do, never as a warning). */
    fun notTestedYet(s: ReadinessSnapshot): List<ReadinessCheckResult> =
        s.checks.filter { it.outcome == CheckOutcome.NotTested }.sortedBy { it.id.ordinal }

    /** The checks that block the interview (FAIL), in page order. */
    fun blockers(s: ReadinessSnapshot): List<ReadinessCheckResult> =
        s.checks.filter { it.outcome == CheckOutcome.Fail }.sortedBy { it.id.ordinal }

    /** Optional improvements (WARNING), in page order. */
    fun warnings(s: ReadinessSnapshot): List<ReadinessCheckResult> =
        s.checks.filter { it.outcome == CheckOutcome.Warning }.sortedBy { it.id.ordinal }

    /**
     * True when only connection-dependent checks fail: typed questions with prepared answers still work then
     * (the page says so under the blockers).
     */
    fun typedQuestionsStillWork(s: ReadinessSnapshot): Boolean {
        val failed = blockers(s)
        return failed.isNotEmpty() && failed.all {
            it.id == ReadinessCheckId.AudioSignal || it.id == ReadinessCheckId.Transcription ||
                it.id == ReadinessCheckId.AnswerEngine || it.id == ReadinessCheckId.AccountPlan
        }
    }

    /** Hint under the summary for a state (Not ready lists its blockers separately). */
    fun hintKey(s: ReadinessState): String = when (s) {
        ReadinessState.Ready -> "rd_hint_ready"
        ReadinessState.ReadyWithWarnings -> "rd_hint_warnings"
        ReadinessState.NotReady -> "rd_hint_not_ready"
        ReadinessState.Checking -> "rd_hint_checking"
        ReadinessState.Outdated -> "rd_hint_outdated"
        else -> "rd_hint_not_checked"
    }

    /**
     * The log line of a readiness transition, or null when the state did not change. Only states and counts — never
     * device names, e-mail addresses or interview names.
     */
    fun transitionLog(from: ReadinessState, to: ReadinessState, s: ReadinessSnapshot): String? =
        if (from == to) null else "Readiness: $from -> $to (${s.passed}/${s.total} passed)"
}
