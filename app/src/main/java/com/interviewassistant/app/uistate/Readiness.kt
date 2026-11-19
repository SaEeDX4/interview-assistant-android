package com.interviewassistant.app.uistate

import java.time.Duration
import java.time.OffsetDateTime

/** Mirror of `InterviewAssistant.App/UiState/Readiness.cs`. */

/** The eight pre-interview checks, in the order the page shows them. */
enum class ReadinessCheckId { PlaybackDevice, AudioSignal, AccountPlan, Transcription, AnswerEngine, InterviewPrepared, PreparedAnswers, WindowOnTop }

/** The result of one check: outcome, a plain detail line (already localized) and the fix it offers, if any. */
data class ReadinessCheckResult(val id: ReadinessCheckId, val outcome: CheckOutcome, val detail: String = "", val fix: ReadinessFix = ReadinessFix.None)

/** What the action button of a check does (the page maps it to a localized label and a route). */
enum class ReadinessFix { None, OpenAudioSettings, TestAudio, SignIn, OpenAccount, TestTranscription, TestAi, OpenInterviews, PrepareInterview, OpenWindowSettings }

/** Overall state shown by every readiness surface (page summary, Get started, Interviews, the global indicator). */
enum class ReadinessState { NotChecked, Checking, Ready, ReadyWithWarnings, NotReady, Outdated }

/**
 * One authoritative readiness snapshot. Every surface renders this record — none of them computes readiness on its
 * own, so they can never disagree. [context] is what the checks were run for (account, interview, audio
 * device); when that changes the snapshot is [ReadinessState.Outdated] until checked again.
 */
data class ReadinessSnapshot(val checks: List<ReadinessCheckResult>, val context: String, val checkedAt: OffsetDateTime?) {
    val total: Int get() = ReadinessCheckId.entries.size
    val passed: Int get() = checks.count { it.outcome == CheckOutcome.Pass }
    val warnings: Int get() = checks.count { it.outcome == CheckOutcome.Warning }
    val failed: Int get() = checks.count { it.outcome == CheckOutcome.Fail }
    /** Optional tests the user has not run yet (neither passed nor warning). */
    val notTested: Int get() = checks.count { it.outcome == CheckOutcome.NotTested }
    /** The checks that count for "n of m checks passed" (all of them minus the optional tests not run). */
    val required: Int get() = total - notTested
    val isRunning: Boolean get() = checks.any { it.outcome == CheckOutcome.Pending }

    fun get(id: ReadinessCheckId): ReadinessCheckResult? = checks.firstOrNull { it.id == id }

    /**
     * The state for [currentContext] (what is selected now). With [now], a finished
     * result older than [maxAge] is [ReadinessState.Outdated].
     */
    fun stateFor(currentContext: String, now: OffsetDateTime? = null): ReadinessState {
        if (checks.isEmpty()) return ReadinessState.NotChecked
        if (isRunning) return ReadinessState.Checking
        if (context != currentContext) return ReadinessState.Outdated
        if (now != null && checkedAt != null && Duration.between(checkedAt, now) > maxAge) return ReadinessState.Outdated
        if (checks.size < total) return ReadinessState.NotChecked
        return when (ReadinessVerdict.evaluate(checks.map { it.outcome })) {
            VerdictKind.Ready -> ReadinessState.Ready
            VerdictKind.ReadyWithWarnings -> ReadinessState.ReadyWithWarnings
            VerdictKind.NotReady -> ReadinessState.NotReady
            else -> ReadinessState.Checking
        }
    }

    companion object {
        val empty: ReadinessSnapshot = ReadinessSnapshot(emptyList(), "", null)

        /** A result older than this is "Out of date": the meeting, the network or the plan may have changed since. */
        val maxAge: Duration = Duration.ofMinutes(30)

        /** Localization key of the state's label ("Ready", "Ready with warnings", …). */
        fun stateKey(s: ReadinessState): String = when (s) {
            ReadinessState.Ready -> "rd_state_ready"
            ReadinessState.ReadyWithWarnings -> "rd_state_warnings"
            ReadinessState.NotReady -> "rd_state_not_ready"
            ReadinessState.Checking -> "rd_state_checking"
            ReadinessState.Outdated -> "rd_state_outdated"
            else -> "rd_state_not_checked"
        }

        /** The design-token brush key that colours the state (badge, indicator dot). */
        fun brushKey(s: ReadinessState): String = when (s) {
            ReadinessState.Ready -> "B.Listening"
            ReadinessState.ReadyWithWarnings -> "B.Thinking"
            ReadinessState.NotReady -> "B.Error"
            ReadinessState.Checking -> "B.Accent"
            else -> "B.TextFaint"
        }
    }
}

/**
 * Holds the latest readiness snapshot for the whole app (owned by the view model, one instance). The pre-interview
 * page writes check results; everything else only reads [snapshot] and listens to the changed listeners.
 */
class ReadinessStore(private val now: () -> OffsetDateTime = { OffsetDateTime.now() }) {
    private val gate = Any()
    private val publishGate = Any()
    private var snapshotField: ReadinessSnapshot = ReadinessSnapshot.empty

    val snapshot: ReadinessSnapshot get() = synchronized(gate) { snapshotField }

    private val changedListeners = mutableListOf<(ReadinessSnapshot) -> Unit>()
    /** Raised on the writer's thread after every change, with the new snapshot. */
    fun addChangedListener(listener: (ReadinessSnapshot) -> Unit) { synchronized(changedListeners) { changedListeners.add(listener) } }
    fun removeChangedListener(listener: (ReadinessSnapshot) -> Unit) { synchronized(changedListeners) { changedListeners.remove(listener) } }

    /** A run begins for [context]: every check is pending. */
    fun begin(context: String) {
        val checks = ReadinessCheckId.entries.map { id -> ReadinessCheckResult(id, CheckOutcome.Pending) }
        publish(ReadinessSnapshot(checks, context, null))
    }

    /** Records one check result (a later result for the same check replaces the earlier one). */
    fun report(result: ReadinessCheckResult) {
        synchronized(publishGate) { // read-modify-write as one step: two writers never lose each other's result
            val next: ReadinessSnapshot
            synchronized(gate) {
                val list = (snapshotField.checks.filter { it.id != result.id } + result).sortedBy { it.id.ordinal }
                val done = list.size == snapshotField.total && list.all { it.outcome != CheckOutcome.Pending }
                next = snapshotField.copy(checks = list, checkedAt = if (done) now() else snapshotField.checkedAt)
            }
            publishLocked(next)
        }
    }

    /** Forgets every result (sign-out: readiness belonged to the previous account). */
    fun clear() = publish(ReadinessSnapshot.empty)

    /**
     * What the checks depend on changed (settings saved, interview re-prepared, profile saved): every surface
     * re-renders, and a result for the old revision now reads "Out of date".
     */
    fun touch() = publish(snapshot)

    private fun publish(s: ReadinessSnapshot) {
        // Writers are serialized and listeners receive the snapshots in the order they were stored.
        synchronized(publishGate) { publishLocked(s) }
    }

    private fun publishLocked(s: ReadinessSnapshot) {
        synchronized(gate) { snapshotField = s }
        val listeners = synchronized(changedListeners) { changedListeners.toList() }
        listeners.forEach { it(s) }
    }

    companion object {
        /**
         * The context string the checks are bound to: who, which interview, which audio device, and the revision of what
         * else they depend on (prepared answers, settings) — a change of any part makes a result "Out of date".
         * (C# string interpolation renders null as ""; the ?: "" below is that rule, not a change.)
         */
        fun contextOf(accountId: String?, targetId: String?, deviceId: String?, revision: Int = 0): String =
            "${accountId ?: ""}|${targetId ?: ""}|${deviceId ?: ""}|$revision"
    }
}

/** Steps of the Get started dashboard. */
enum class SetupStep { Account, Profile, InterviewSetup, PreInterviewCheck }

enum class StepStatus { Done, Current, Upcoming }

/** The one primary action Get started offers, from the first unfinished step. */
enum class SetupAction { SignIn, CompleteProfile, CreateInterview, PrepareInterview, RunPreInterviewCheck, StartInterview }

/** What the dashboard knows about the user's setup (no UI types: unit-tested). */
data class SetupFacts(
    val accountReady: Boolean,
    val hasProfile: Boolean,
    val hasConfirmedFacts: Boolean,
    val hasInterview: Boolean,
    val interviewPrepared: Boolean,
    val readiness: ReadinessState,
)

data class SetupStepView(val step: SetupStep, val status: StepStatus)

/**
 * Get started as a readiness dashboard: four steps, each Done / Current / Upcoming, and exactly one primary action —
 * the next thing to do. A step is Done only when everything before it is usable (a profile without an account is not
 * "done": nothing can be prepared without signing in).
 */
object SetupPlanner {
    fun isDone(step: SetupStep, f: SetupFacts): Boolean = when (step) {
        SetupStep.Account -> f.accountReady
        SetupStep.Profile -> f.accountReady && f.hasProfile && profileUsable(f)
        SetupStep.InterviewSetup -> f.accountReady && f.hasInterview && f.interviewPrepared
        // a check result counts only for a usable setup (signed in, an interview prepared)
        SetupStep.PreInterviewCheck -> isDone(SetupStep.Account, f) && isDone(SetupStep.InterviewSetup, f) &&
            (f.readiness == ReadinessState.Ready || f.readiness == ReadinessState.ReadyWithWarnings)
        else -> false
    }

    /**
     * Confirmed facts are asked for until the user knowingly prepares an interview without them (Prepare allows it
     * after a warning); from then on the step detail keeps the reminder, but the next action moves on (rc.5 UX audit M10).
     */
    private fun profileUsable(f: SetupFacts): Boolean = f.hasConfirmedFacts || f.interviewPrepared

    fun steps(f: SetupFacts): List<SetupStepView> {
        val current = SetupStep.entries.firstOrNull { s -> !isDone(s, f) }
        return SetupStep.entries.map { s ->
            SetupStepView(s, if (isDone(s, f)) StepStatus.Done else if (s == current) StepStatus.Current else StepStatus.Upcoming)
        }
    }

    fun primaryAction(f: SetupFacts): SetupAction {
        if (!f.accountReady) return SetupAction.SignIn
        if (!f.hasProfile || !profileUsable(f)) return SetupAction.CompleteProfile
        if (!f.hasInterview) return SetupAction.CreateInterview
        if (!f.interviewPrepared) return SetupAction.PrepareInterview
        if (f.readiness != ReadinessState.Ready && f.readiness != ReadinessState.ReadyWithWarnings) return SetupAction.RunPreInterviewCheck
        return SetupAction.StartInterview
    }

    /** Localization key of the primary button. */
    fun actionKey(a: SetupAction): String = when (a) {
        SetupAction.SignIn -> "gs_action_sign_in"
        SetupAction.CompleteProfile -> "gs_action_profile"
        SetupAction.CreateInterview -> "gs_action_create_interview"
        SetupAction.PrepareInterview -> "gs_action_prepare"
        SetupAction.RunPreInterviewCheck -> "gs_action_check"
        else -> "gs_action_start"
    }
}
