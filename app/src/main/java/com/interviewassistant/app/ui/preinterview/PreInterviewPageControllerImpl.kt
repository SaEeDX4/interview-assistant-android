package com.interviewassistant.app.ui.preinterview

import com.interviewassistant.app.services.AnswerServiceProbe
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.uistate.CheckOutcome
import com.interviewassistant.app.uistate.ErrorCopy
import com.interviewassistant.app.uistate.ReadinessCheckId
import com.interviewassistant.app.uistate.ReadinessCheckResult
import com.interviewassistant.app.uistate.ReadinessFix
import com.interviewassistant.app.uistate.ReadinessPresentation
import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.viewmodels.MainViewModel
import com.interviewassistant.app.viewmodels.ObservableObject
import com.interviewassistant.app.viewmodels.PreInterviewPageController
import com.interviewassistant.app.viewmodels.formatShortTime
import com.interviewassistant.client.BackendException
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.orchestration.AnswerSource
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.OpenAiAuthProbe
import com.interviewassistant.core.providers.OpenAiRealtimeTranscriber
import com.interviewassistant.core.providers.ProviderErrorKind
import com.interviewassistant.core.providers.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One check row's render state. Everything it shows comes from the readiness snapshot; the row only
 * maps it to text and brush keys (the C#'s `ReadinessRow` in PreInterviewPage.xaml.cs).
 */
class ReadinessRowState(val id: ReadinessCheckId, val first: Boolean) : ObservableObject() {
    var title: String by bindable("")
    var detail: String by bindable("")
    var status: String by bindable("")
    var icon: String by bindable("")
    var brushKey: String by bindable("B.TextFaint")
    var fix: ReadinessFix by bindable(ReadinessFix.None)
    var fixLabel: String by bindable("")

    val detailVisible: Boolean get() = detail.isNotEmpty()
    val fixVisible: Boolean get() = fix != ReadinessFix.None

    /** Screen readers hear the whole row: "Audio signal, PASS, Peak -18 dBFS on Headset". */
    val accessibleName: String
        get() = listOf(title, status, detail).filter { it.isNotEmpty() }.joinToString(Loc.t("fmt_list_sep"))

    val fixAccessibleName: String
        get() = if (fixLabel.isEmpty()) "" else Loc.f("fmt_label_value", fixLabel, title)
}

/** A line under "What blocks the interview" (or the optional improvements). */
data class ReadinessLineState(val icon: String, val brushKey: String, val text: String)

/**
 * Mirror of `InterviewAssistant.App/Views/PreInterviewPage.xaml.cs`: the pre-interview check as a
 * Home page. Runs the production probes (capture, account, transcription, answer engine) and writes
 * every result to the one [com.interviewassistant.app.uistate.ReadinessStore] (vm.readiness); the
 * page renders FROM the store, so Get started, the Interviews summary and the indicators always
 * show the same thing. A run is bounded (about 15 s) and finishes even when the user leaves the
 * page (e.g. through a row's own fix button); closing Home cancels it, and a run cut short is
 * forgotten (Not checked) rather than left "Checking". Every failure — offline, an expired sign-in
 * that cannot be renewed, a captive Wi-Fi page — ends the pending checks with a reason. No check
 * runs while an interview is live (the AI and transcription tests would compete with the session).
 *
 * Platform mappings (P4-PORT-NOTES DC-152…DC-155): the C#'s CancellationTokenSources are standalone
 * [Job] tokens the probes poll; the "window on top" check reads the saved always-on-top setting
 * until the Live destination exists (P4-G); the settings-applied re-run is exposed as
 * [onSettingsApplied] and wired by the shell in P4-G, where the Settings controller lands.
 */
class PreInterviewPageControllerImpl(
    private val vm: MainViewModel,
    private val scope: CoroutineScope,
    private val postToUi: (() -> Unit) -> Unit,
) : ObservableObject(), PreInterviewPageController {

    companion object {
        /** Sample question for the AI test, in the UI language (it is sent to the AI as typed text). */
        val testQuestion: String get() = Loc.t("pf_ai_test_question")

        /** Time the capture gets to start (device open, first buffers) before the listening window counts. */
        private const val SIGNAL_GRACE_MS: Long = 3_000

        /** Listening window of the meeting-audio check. */
        private const val SIGNAL_WINDOW_MS: Long = 10_000

        /** After "nothing heard yet", the check keeps listening this long and passes as soon as audio plays. */
        private const val SIGNAL_FOLLOW_UP_MS: Long = 120_000
    }

    override val navigateRequestedListeners: MutableList<(String) -> Unit> = mutableListOf()
    override val startRequestedListeners: MutableList<() -> Unit> = mutableListOf()
    override val stateChangedListeners: MutableList<() -> Unit> = mutableListOf()

    /** The eight check rows, in the order the page shows them. */
    val rows: List<ReadinessRowState> =
        ReadinessCheckId.entries.mapIndexed { i, id -> ReadinessRowState(id, i == 0) }

    // ---- summary card render state ----
    var summaryIcon: String by bindable("")
    var summaryBrushKey: String by bindable("B.TextFaint")
    var summaryStateText: String by bindable("")
    var summaryLine: String by bindable("")
    var summaryCheckedAt: String by bindable("")
    var summaryHint: String by bindable("")
    var blockersVisible: Boolean by bindable(false)
    var blockersTitle: String by bindable("")
    var blockerLines: List<ReadinessLineState> by bindable(emptyList())
    var typedStillWorkVisible: Boolean by bindable(false)
    var summaryAccessibleName: String by bindable("")

    // ---- test-tool render state ----
    var toolTitle: String by bindable("")
    var toolResultText: String by bindable("")
    var toolResultBrushKey: String by bindable("B.Listening")
    var toolResultVisible: Boolean by bindable(false)
    var toolOutput: String by bindable("")
    var toolDiagnosticsText: String by bindable("")
    var toolDiagnosticsVisible: Boolean by bindable(false)

    private var toolToken: Job? = null
    private var signalToken: Job? = null
    private var runToken: Job? = null
    private var generation: Int = 0
    private var runContext: String = ""
    private var running: Boolean = false
    private var shown: Boolean = false
    private var loggedState: ReadinessState = ReadinessState.NotChecked

    private val formatted = mutableMapOf<String, Pair<String, Array<out Any?>>>()

    private val storeListener: (ReadinessSnapshot) -> Unit = { s ->
        postToUi {
            val now = vm.readinessState
            ReadinessPresentation.transitionLog(loggedState, now, s)?.let { line -> AppLog.info(line) }
            loggedState = now
            render()
        }
    }

    init {
        loggedState = vm.readinessState
        vm.readiness.addChangedListener(storeListener)
        toolTitle = t("pf_pick_test")
        render()
    }

    /** The state every surface shows right now. */
    val state: ReadinessState get() = vm.readinessState

    override val isRunning: Boolean get() = running
    override val canStart: Boolean get() = ReadinessPresentation.canStart(state)

    private fun t(key: String): String = Loc.t(key)

    /** The C#'s `:0.0` format in the UI culture (the MainViewModel diagnostics precedent). */
    private fun format1(v: Double): String {
        val nf = LanguageRegistry.numberFormat(Loc.language)
        nf.minimumFractionDigits = 1
        nf.maximumFractionDigits = 1
        nf.isGroupingUsed = false
        return nf.format(v)
    }

    /**
     * Formatted text for a check detail or tool line. The key and arguments are remembered, so a UI
     * language switch re-formats the finished results in the new language (Loc.relocalize only
     * knows texts without placeholders).
     */
    private fun f(key: String, vararg args: Any?): String {
        val text = Loc.f(key, *args)
        synchronized(formatted) {
            if (formatted.size > 400) formatted.clear() // bounded: a few dozen lines per run
            formatted[text] = key to args
        }
        return text
    }

    /** The same text in the current UI language (formatted lines, plain keys, service messages). */
    private fun relocal(text: String): String {
        synchronized(formatted) {
            formatted[text]?.let { entry ->
                val now = Loc.f(entry.first, *entry.second)
                formatted[now] = entry
                return now
            }
        }
        return Loc.relocalize(text) ?: text
    }

    // ================= page lifecycle =================

    /** The page became visible: run the checks when there is no current result (none yet, or out of date). */
    override fun onShown() {
        shown = true
        render()
        if (!running && !vm.sessionActive && ReadinessPresentation.shouldAutoRun(state)) {
            scope.launch { runAllAsync() }
        }
    }

    /**
     * The user went to another page (often through a row's own fix button): a running test tool
     * stops, the bounded run finishes into the store so every surface still gets its result.
     */
    override fun onHidden() {
        shown = false
        val token = toolToken
        if (token != null && !token.isCancelled) {
            token.cancel()
            showResult(false, t("rd_tool_stopped"))
        }
    }

    /**
     * Saved settings make the last result "Out of date" (see the Settings save path); an open page
     * checks again at once. The C# page subscribes to the main window's SettingsApplied event; on
     * Android the signal is the Settings controller's saved notification, wired in P4-G (DC-152).
     */
    fun onSettingsApplied() {
        postToUi {
            if (shown && !running && !vm.sessionActive) scope.launch { runAllAsync() }
        }
    }

    /** Home is closing: stop every probe and listener. */
    override fun detach() {
        cancel()
        vm.readiness.removeChangedListener(storeListener)
    }

    /** Cancels tools, the signal listener and an unfinished run (whose results are dropped). */
    private fun cancel() {
        toolToken?.cancel()
        signalToken?.cancel()
        runToken?.cancel()
        if (running) {
            generation++
            running = false
            val s = vm.readiness.snapshot
            if (s.isRunning && s.context == runContext) vm.readiness.clear() // unknown, not "checking" forever
        }
        render()
    }

    // ================= store =================

    /** Writes one result of the run [gen] (a cancelled or replaced run writes nothing). */
    private fun report(gen: Int, id: ReadinessCheckId, outcome: CheckOutcome, detail: String, fix: ReadinessFix = ReadinessFix.None) {
        // A cancelled or replaced run, or a result the store has forgotten meanwhile (sign-out), writes nothing.
        val s = vm.readiness.snapshot
        if (gen != generation || s.checks.isEmpty() || s.context != runContext) return
        vm.readiness.report(ReadinessCheckResult(id, outcome, detail, fix))
    }

    private fun pass(gen: Int, id: ReadinessCheckId, detail: String) = report(gen, id, CheckOutcome.Pass, detail)
    private fun warn(gen: Int, id: ReadinessCheckId, detail: String, fix: ReadinessFix) = report(gen, id, CheckOutcome.Warning, detail, fix)
    private fun fail(gen: Int, id: ReadinessCheckId, detail: String, fix: ReadinessFix) = report(gen, id, CheckOutcome.Fail, detail, fix)
    private fun busy(gen: Int, id: ReadinessCheckId, detail: String) = report(gen, id, CheckOutcome.Pending, detail)

    /**
     * A test tool's result updates the matching check only when a result for the current account,
     * interview and device exists (otherwise the tool output alone shows it — a lone result is not
     * a readiness check).
     */
    private val hasCurrentResult: Boolean
        get() {
            val s = vm.readiness.snapshot
            return s.checks.isNotEmpty() && s.context == vm.readinessContext
        }

    private fun outcomeOf(id: ReadinessCheckId): CheckOutcome? = vm.readiness.snapshot.get(id)?.outcome

    // ================= rendering (from the snapshot only) =================

    /** Re-renders texts from the store (also after a language switch: nothing is re-run). */
    override fun relocalize() = render()

    /** Re-renders after something the result depends on changed (account, active interview, audio device). */
    override fun refresh() = render()

    /** "7 of 8 checks passed" for what is selected now (shared by every readiness surface). */
    fun summaryText(): String {
        val s = ReadinessPresentation.summary(vm.readiness.snapshot, vm.readinessContext)
        return if (s.args.isEmpty()) t(s.key) else Loc.f(s.key, *s.args.toTypedArray())
    }

    private fun render() {
        val snap = vm.readiness.snapshot
        val state = vm.readinessState
        val cloud = vm.usingCloud
        val current = snap.checks.isNotEmpty() && snap.context == vm.readinessContext
        for (row in rows) {
            val r = if (current || snap.isRunning) snap.get(row.id) else null
            val rs = ReadinessPresentation.stateOf(r?.outcome)
            row.title = t(ReadinessPresentation.titleKey(row.id, cloud))
            row.detail = if (r == null) "" else relocal(r.detail)
            row.status = t(ReadinessPresentation.statusKey(r?.outcome))
            row.icon = ReadinessPresentation.icon(rs)
            row.brushKey = ReadinessSnapshot.brushKey(rs)
            row.fix = when (r?.outcome) {
                CheckOutcome.Warning, CheckOutcome.Fail, CheckOutcome.NotTested -> r?.fix ?: ReadinessFix.None
                else -> ReadinessFix.None
            }
            row.fixLabel = ReadinessPresentation.fixLabelKey(row.fix)?.let { key -> t(key) } ?: ""
        }

        summaryIcon = ReadinessPresentation.icon(state)
        summaryBrushKey = ReadinessSnapshot.brushKey(state)
        summaryStateText = t(ReadinessSnapshot.stateKey(state))
        summaryLine = summaryText()
        summaryCheckedAt = if (current && snap.checkedAt != null) {
            f("rd_last_checked", formatShortTime(snap.checkedAt))
        } else {
            ""
        }
        summaryHint = t(ReadinessPresentation.hintKey(state))

        // Not ready: what blocks (and how to fix it). Ready with warnings: the optional improvements.
        // Ready (all required checks passed): an optional test not run yet is a gentle to-do, never a warning.
        val lines = when (state) {
            ReadinessState.NotReady -> ReadinessPresentation.blockers(snap)
            ReadinessState.ReadyWithWarnings ->
                ReadinessPresentation.warnings(snap) + ReadinessPresentation.notTestedYet(snap)
            ReadinessState.Ready -> ReadinessPresentation.notTestedYet(snap)
            else -> emptyList()
        }
        blockersVisible = lines.isNotEmpty()
        blockersTitle = t(if (state == ReadinessState.NotReady) "rd_blockers_title" else "pf_verdict_optional")
        blockerLines = lines.map { c ->
            val s = ReadinessPresentation.stateOf(c.outcome)
            ReadinessLineState(
                icon = ReadinessPresentation.icon(s),
                brushKey = ReadinessSnapshot.brushKey(s),
                text = Loc.f(
                    "fmt_label_value",
                    t(ReadinessPresentation.titleKey(c.id, cloud)),
                    t(ReadinessPresentation.fixHintKey(c.id, cloud)),
                ),
            )
        }
        typedStillWorkVisible =
            state == ReadinessState.NotReady && ReadinessPresentation.typedQuestionsStillWork(snap)
        summaryAccessibleName = listOf(summaryStateText, summaryLine, summaryCheckedAt, summaryHint)
            .filter { it.isNotEmpty() }.joinToString(". ")

        // Tool output already on screen follows a language switch too.
        toolTitle = relocal(toolTitle)
        toolResultText = relocal(toolResultText)
        toolOutput = relocal(toolOutput)
        for (listener in stateChangedListeners.toList()) listener()
    }

    // ================= checks =================

    /** Runs all eight checks for the current account, interview and audio device. */
    override suspend fun runAllAsync() {
        if (running) return
        if (vm.sessionActive) {
            beginTool(t("pf_title"))
            toolOutput = t("rd_session_active")
            return
        }
        running = true
        val gen = ++generation
        runToken?.cancel()
        val token = Job()
        runToken = token
        runContext = vm.readinessContext
        vm.readiness.begin(runContext)
        try {
            checkLocalAsync(gen)
            if (gen != generation) return // replaced or cancelled meanwhile: a stale run never touches the new one
            if (vm.usingCloud) {
                runCloudChecksAsync(gen, token)
                return
            }
            val key = SecretStore.getApiKey()
            val signalTask = scope.async { checkSignalAsync(gen, SIGNAL_WINDOW_MS, token) }
            busy(gen, ReadinessCheckId.AccountPlan, t("pf_busy_key"))
            val auth = OpenAiAuthProbe.check(key)
            if (gen != generation) return
            if (auth.ok) {
                pass(gen, ReadinessCheckId.AccountPlan, auth.detail)
                busy(gen, ReadinessCheckId.AnswerEngine, t("pf_busy_answer"))
                val sttTask = scope.async { checkTranscriptionConnectAsync(gen) }
                val r = AnswerServiceProbe.runAsync({ SecretStore.getApiKey() }, vm.settings.answerModel)
                if (r.ok) {
                    pass(gen, ReadinessCheckId.AnswerEngine, f("pf_answer_ok", vm.settings.answerModel, r.firstTokenMs))
                } else {
                    fail(gen, ReadinessCheckId.AnswerEngine, r.error ?: t("err_unknown"), ReadinessFix.TestAi)
                }
                sttTask.await()
            } else {
                fail(
                    gen, ReadinessCheckId.AccountPlan,
                    if (auth.kind == ProviderErrorKind.Network) t("svc_unreachable") else auth.detail,
                    ReadinessFix.None,
                )
                warn(gen, ReadinessCheckId.AnswerEngine, t("pf_skipped_key"), ReadinessFix.None)
                warn(gen, ReadinessCheckId.Transcription, t("pf_skipped_key"), ReadinessFix.None)
            }
            signalTask.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, a sign-in that cannot be renewed, a captive Wi-Fi page, a provider error: every check this run
            // still owes ends as a failure with a reason (never "Checking…" forever). Details go to the log only.
            AppLog.warn("Pre-interview check stopped: ${e.javaClass.simpleName}")
            failPending(gen, ErrorCopy.describe(e, includeReference = false))
        } finally {
            if (gen == generation) running = false
            render()
        }
    }

    /** Ends every still-pending check of run [gen] as a failure with [reason]. */
    private fun failPending(gen: Int, reason: String) {
        for (c in vm.readiness.snapshot.checks.filter { it.outcome == CheckOutcome.Pending }) {
            fail(
                gen, c.id, reason,
                when (c.id) {
                    ReadinessCheckId.AccountPlan -> ReadinessFix.OpenAccount
                    ReadinessCheckId.Transcription -> ReadinessFix.TestTranscription
                    ReadinessCheckId.AnswerEngine -> ReadinessFix.TestAi
                    ReadinessCheckId.AudioSignal -> ReadinessFix.TestAudio
                    else -> ReadinessFix.None
                },
            )
        }
    }

    /** Account mode: entitlements from the server, answer test through the server proxy (typed-question path, no live minutes). */
    private suspend fun runCloudChecksAsync(gen: Int, token: Job) {
        val signalTask = scope.async { checkSignalAsync(gen, SIGNAL_WINDOW_MS, token) }
        val acct = ReadinessCheckId.AccountPlan
        val answer = ReadinessCheckId.AnswerEngine
        val stt = ReadinessCheckId.Transcription
        busy(gen, acct, t("pf_busy_account"))
        val refreshed = vm.cloud.isSignedIn && vm.cloud.refreshAsync()
        if (gen != generation) return
        val cloud = vm.cloud
        if (!cloud.isConfigured) {
            fail(gen, acct, t("acct_not_configured_long"), ReadinessFix.OpenAccount)
            warn(gen, answer, t("pf_skipped_service"), ReadinessFix.None)
            warn(gen, stt, t("pf_skipped_service"), ReadinessFix.None)
        } else if (!cloud.isSignedIn) {
            fail(gen, acct, Loc.relocalize(cloud.message) ?: t("note_sign_in_to_listen"), ReadinessFix.SignIn)
            warn(gen, answer, t("pf_skipped_sign_in"), ReadinessFix.SignIn)
            warn(gen, stt, t("pf_skipped_sign_in"), ReadinessFix.SignIn)
        } else if (!refreshed || cloud.account == null) {
            fail(gen, acct, Loc.relocalize(cloud.message) ?: t("svc_unreachable"), ReadinessFix.OpenAccount)
            warn(gen, answer, t("pf_skipped_unreachable"), ReadinessFix.None)
            warn(gen, stt, t("pf_skipped_unreachable"), ReadinessFix.None)
        } else {
            val a = cloud.account!!
            val mins = a.usage.liveSecondsRemaining / 60
            val today = if (a.usage.liveSecondsAvailableToday >= 0) {
                minOf(mins, a.usage.liveSecondsAvailableToday / 60)
            } else {
                mins
            }
            if (cloud.config?.maintenance == true) {
                fail(gen, acct, cloud.config?.maintenanceMessage ?: t("svc_maintenance"), ReadinessFix.OpenAccount)
            } else if (mins <= 0) {
                fail(gen, acct, f("pf_no_minutes", MainViewModel.billingLabel(a)), ReadinessFix.OpenAccount)
            } else {
                // The device must be registered (and not removed) for live sessions.
                var device = ""
                try {
                    cloud.ensureDeviceAsync()
                    device = t("pf_device_registered")
                } catch (ex: BackendException) {
                    fail(gen, acct, CloudService.describe(ex), ReadinessFix.OpenAccount)
                }
                if (device.isNotEmpty()) {
                    // Live listening is blocked for the rest of the day: that is a FAIL, not an optional warning.
                    if (today <= 0) {
                        fail(gen, acct, f("pf_account_today_used", a.email, MainViewModel.billingLabel(a)), ReadinessFix.OpenAccount)
                    } else {
                        pass(gen, acct, f("pf_account_ok", a.email, MainViewModel.billingLabel(a), mins, today, device))
                    }
                }
            }
            if (mins > 0) {
                busy(gen, answer, t("pf_busy_answer_service"))
                try {
                    val (answerView, sample, _) = vm.testAiAsync(testQuestion) { }
                    if (answerView.source == AnswerSource.Llm && answerView.bullets.size >= 2) {
                        pass(gen, answer, f("pf_answer_ok_service", sample?.finalizedToFirstBulletMs))
                    } else {
                        fail(gen, answer, Loc.relocalize(answerView.note) ?: t("err_unknown"), ReadinessFix.TestAi)
                    }
                } catch (e: Exception) {
                    if (e is ProviderException || e is IllegalStateException || e is BackendException) {
                        fail(gen, answer, CloudService.describe(e), ReadinessFix.TestAi)
                    } else {
                        throw e
                    }
                }
                // Live transcription is only proven with real meeting audio: an optional test the user runs (neither PASS nor WARNING).
                report(gen, stt, CheckOutcome.NotTested, t("pf_stt_run_test"), ReadinessFix.TestTranscription)
            } else {
                warn(gen, answer, t("pf_skipped_service"), ReadinessFix.OpenAccount)
                warn(gen, stt, t("pf_skipped_service"), ReadinessFix.OpenAccount)
            }
        }
        signalTask.await()
    }

    /**
     * Checks that need no network (device, prepared interview, prepared answers, window on top).
     * Starts a run for the current context when none is under way.
     */
    suspend fun checkLocalAsync() {
        if (!running && !hasCurrentResult) {
            scope.launch { runAllAsync() } // nothing to add to: a full run (its local part is below)
        }
        if (!running) {
            generation++ // re-check into the finished result
            runContext = vm.readinessContext
        }
        checkLocalAsync(generation)
    }

    private suspend fun checkLocalAsync(gen: Int) {
        vm.startAudioPreview()
        val cap = vm.capture
        // The capture was (re)started a moment ago (settings saved, page opened): give the device a moment to open.
        val until = System.currentTimeMillis() + 2_000
        while (cap.status != "ACTIVE" && System.currentTimeMillis() < until && gen == generation) delay(100)
        if (gen != generation) return
        if (cap.status == "ACTIVE") {
            pass(gen, ReadinessCheckId.PlaybackDevice, "${cap.currentDeviceName} · ${cap.sampleRate} Hz · ${cap.channels} ch")
        } else {
            fail(
                gen, ReadinessCheckId.PlaybackDevice,
                if (cap.currentDeviceName.isEmpty()) t("pf_no_device") else "${cap.currentDeviceName} · ${cap.statusLabel}",
                ReadinessFix.OpenAudioSettings,
            )
        }

        val k = vm.knowledge
        if (k != null) {
            if (k.questions.isNotEmpty()) {
                pass(
                    gen, ReadinessCheckId.InterviewPrepared,
                    f("pf_prepared_ok", vm.activeLabel, k.profile.experience.size, k.stories.size, k.context.transcriptionTerms().size),
                )
            } else {
                warn(gen, ReadinessCheckId.InterviewPrepared, t("pf_prepared_none"), ReadinessFix.OpenInterviews)
            }
            if (k.questions.size >= 50) {
                pass(gen, ReadinessCheckId.PreparedAnswers, f("pf_bank_ok", k.questions.size))
            } else if (k.questions.isEmpty()) {
                warn(gen, ReadinessCheckId.PreparedAnswers, t("pf_bank_none"), ReadinessFix.PrepareInterview)
            } else {
                fail(gen, ReadinessCheckId.PreparedAnswers, f("pf_bank_incomplete", k.questions.size), ReadinessFix.PrepareInterview)
            }
        } else {
            fail(gen, ReadinessCheckId.InterviewPrepared, t("pf_prepared_none"), ReadinessFix.OpenInterviews)
            fail(gen, ReadinessCheckId.PreparedAnswers, t("pf_bank_none"), ReadinessFix.PrepareInterview)
        }

        // The C# reads the Live window's Topmost; Android has no separate Live window in P4, so the
        // saved always-on-top setting is the check's input until P4-G's Live destination (DC-153).
        if (vm.settings.alwaysOnTop) {
            pass(gen, ReadinessCheckId.WindowOnTop, t("pf_topmost_on"))
        } else {
            warn(gen, ReadinessCheckId.WindowOnTop, t("pf_topmost_off"), ReadinessFix.OpenWindowSettings)
        }
    }

    /**
     * Meeting-audio check. A short grace period lets the capture start before anything is judged;
     * "nothing heard" is a WARNING (the meeting may simply be silent yet), and the check keeps
     * listening in the background so it turns PASS by itself once audio plays (or after a successful
     * audio or transcription test).
     */
    private suspend fun checkSignalAsync(gen: Int, windowMs: Long, runToken: Job) {
        // Linked to this run: a test tool that proves the signal stops it, and a cancelled run stops it too.
        signalToken?.cancel()
        val token = Job(runToken)
        signalToken = token
        busy(gen, ReadinessCheckId.AudioSignal, t("pf_busy_signal"))
        val cap = vm.capture
        val graceUntil = System.currentTimeMillis() + SIGNAL_GRACE_MS
        while (System.currentTimeMillis() < graceUntil && cap.status != "ACTIVE") {
            if (!token.isActive) return
            delay(100)
        }
        val start = cap.framesWithSignal
        fun heard(): Boolean = cap.framesWithSignal - start >= 5
        val until = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < until && !heard()) {
            if (!token.isActive) return
            delay(100)
        }
        if (heard()) {
            pass(gen, ReadinessCheckId.AudioSignal, f("pf_signal_ok", Math.rint(cap.lastPeakDb), cap.currentDeviceName))
            return
        }
        warn(gen, ReadinessCheckId.AudioSignal, f("pf_signal_none", windowMs / 1000.0, cap.currentDeviceName), ReadinessFix.TestAudio)
        scope.launch { followUpSignalAsync(start, token) }
    }

    private suspend fun followUpSignalAsync(start: Long, token: Job) {
        val until = System.currentTimeMillis() + SIGNAL_FOLLOW_UP_MS
        while (System.currentTimeMillis() < until && outcomeOf(ReadinessCheckId.AudioSignal) == CheckOutcome.Warning) {
            if (!token.isActive) return
            delay(250)
            if (vm.capture.framesWithSignal - start < 5 || !hasCurrentResult) continue
            vm.readiness.report(
                ReadinessCheckResult(
                    ReadinessCheckId.AudioSignal, CheckOutcome.Pass,
                    f("pf_signal_ok", Math.rint(vm.capture.lastPeakDb), vm.capture.currentDeviceName),
                ),
            )
            return
        }
    }

    private suspend fun checkTranscriptionConnectAsync(gen: Int) {
        busy(gen, ReadinessCheckId.Transcription, f("pf_busy_stt", vm.settings.transcriptionModel))
        val transcriber = OpenAiRealtimeTranscriber(apiKeyProvider = SecretStore::getApiKey, vm.transcriberOptions())
        try {
            val connected = CompletableDeferred<Pair<ConnectionStatus, String?>>()
            val listener: (ConnectionStatus, String?) -> Unit = { s, d ->
                if (s == ConnectionStatus.Connected || s == ConnectionStatus.Failed) connected.complete(s to d)
            }
            transcriber.statusChangedListeners += listener
            transcriber.startAsync()
            val result = withTimeoutOrNull(10_000) { connected.await() }
            if (result != null && result.first == ConnectionStatus.Connected) {
                delay(2_000) // give the server time to reject the session config / trigger model fallback
                if (transcriber.status == ConnectionStatus.Connected) {
                    pass(gen, ReadinessCheckId.Transcription, f("pf_stt_accepted", transcriber.activeModel))
                } else {
                    warn(gen, ReadinessCheckId.Transcription, t("pf_stt_unstable"), ReadinessFix.TestTranscription)
                }
            } else {
                fail(
                    gen, ReadinessCheckId.Transcription,
                    if (result != null) result.second ?: t("pf_stt_failed") else t("svc_timeout"),
                    ReadinessFix.TestTranscription,
                )
            }
        } finally {
            transcriber.disposeAsync()
        }
    }

    // ================= test tools =================

    private fun toolReady(titleKey: String): Boolean {
        if (if (vm.usingCloud) vm.cloud.isSignedIn else SecretStore.hasKey) return true
        beginTool(t(titleKey))
        toolOutput = t(if (vm.usingCloud) "pf_sign_in_first" else "pf_key_first")
        return false
    }

    private fun beginTool(title: String) {
        toolToken?.cancel()
        toolToken = Job()
        toolTitle = title
        toolOutput = ""
        toolResultVisible = false
        toolDiagnosticsVisible = false
        toolDiagnosticsText = ""
    }

    private fun showResult(ok: Boolean, text: String) {
        toolResultText = (if (ok) "✓ " else "✗ ") + text
        toolResultBrushKey = if (ok) "B.Listening" else "B.Thinking"
        toolResultVisible = true
    }

    private fun diagnostic(line: String) {
        toolDiagnosticsVisible = true
        toolDiagnosticsText = if (toolDiagnosticsText.isNotEmpty()) toolDiagnosticsText + "\n" + line else line
    }

    /** Records a tool's result on its check (only into a current result; see [hasCurrentResult]). */
    private fun toolReport(id: ReadinessCheckId, outcome: CheckOutcome, detail: String, fix: ReadinessFix = ReadinessFix.None) {
        if (hasCurrentResult) vm.readiness.report(ReadinessCheckResult(id, outcome, detail, fix))
    }

    /** The tool buttons of the page (the C#'s TestAudio_Click / TestStt_Click / TestAi_Click). */
    fun onTestAudioClick() {
        scope.launch { testAudioAsync() }
    }

    fun onTestTranscriptionClick() {
        scope.launch { testTranscriptionAsync() }
    }

    fun onTestAiClick() {
        scope.launch { testAiAsync() }
    }

    private suspend fun testAudioAsync() {
        beginTool(t("pf_audio_title"))
        val token = toolToken!!
        vm.startAudioPreview()
        val cap = vm.capture
        val start = cap.framesWithSignal
        var maxPeak = -100.0
        // The C#'s 150 ms DispatcherTimer and its 15 s wait are one polling loop here: the level
        // line follows the capture, and the wait ends early when the tool is cancelled.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && token.isActive) {
            maxPeak = maxOf(maxPeak, cap.lastPeakDb)
            val detected = cap.framesWithSignal - start >= 5
            toolOutput = buildString {
                appendLine(f("pf_audio_device", cap.currentDeviceName))
                appendLine(f("pf_audio_level", Math.rint(cap.lastPeakDb), Math.rint(maxPeak)))
                append(t(if (detected) "pf_audio_detected" else "pf_audio_waiting"))
            }
            delay(150)
        }
        if (!token.isActive) return // another test started, or the page was left
        val ok = cap.framesWithSignal - start >= 5
        showResult(ok, t(if (ok) "pf_audio_passed" else "pf_audio_failed"))
        diagnostic("${cap.sampleRate} Hz · ${cap.channels} ch · ${cap.encoding} → 24000 Hz mono PCM16 · ${cap.status} · peak ${format1(maxPeak)} dBFS")
        if (ok) {
            signalToken?.cancel()
            toolReport(ReadinessCheckId.AudioSignal, CheckOutcome.Pass, f("pf_signal_ok", Math.rint(maxPeak), cap.currentDeviceName))
        } else if (outcomeOf(ReadinessCheckId.AudioSignal) != CheckOutcome.Pass) {
            toolReport(ReadinessCheckId.AudioSignal, CheckOutcome.Warning, t("pf_audio_failed"), ReadinessFix.OpenAudioSettings)
        }
    }

    /**
     * Transcription test on the production path. The test connection is closed on purpose when it
     * ends, so the result reads "Test complete · Transcription test passed" — the technical
     * connection log is under Technical details.
     */
    private suspend fun testTranscriptionAsync() {
        if (!toolReady("pf_stt_title")) return
        beginTool(t("pf_stt_title"))
        val token = toolToken!!
        var transcript = ""
        fun renderTool() {
            toolOutput = if (transcript.isNotEmpty()) transcript else t("pf_stt_waiting")
        }
        renderTool()
        // The Kotlin test takes no cancellation token (DC-155): it runs in a child job the tool
        // abandons on cancellation, and a cancelled tool renders nothing — the C#'s token checks.
        val call = scope.async {
            try {
                Result.success(
                    vm.testTranscriptionAsync(
                        onTranscript = { text -> transcript = text; renderTool() },
                        onStatus = { s -> diagnostic(s) },
                        duration = 25.seconds,
                    ),
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        while (!call.isCompleted && token.isActive) delay(50)
        if (!token.isActive) {
            call.cancel()
            return
        }
        val outcome = call.await()
        val result = outcome.getOrElse { ex ->
            if (ex is ProviderException || ex is IllegalStateException || ex is BackendException) {
                AppLog.warn("Transcription test failed: ${ex.javaClass.simpleName}")
                showResult(false, CloudService.describe(ex))
                if (outcomeOf(ReadinessCheckId.Transcription) != CheckOutcome.Pass) {
                    toolReport(ReadinessCheckId.Transcription, CheckOutcome.Fail, CloudService.describe(ex), ReadinessFix.TestTranscription)
                }
                return
            }
            throw ex
        }
        val ok = transcript.split(' ').count { it.isNotEmpty() } >= 3
        showResult(ok, if (ok) t("pf_stt_passed") else t("pf_stt_no_text"))
        diagnostic(result)
        if (ok) {
            toolReport(ReadinessCheckId.Transcription, CheckOutcome.Pass, t("pf_stt_passed"))
            // Words were transcribed from the meeting audio, so the audio signal is proven too (re-evaluated at once).
            if (outcomeOf(ReadinessCheckId.AudioSignal) != CheckOutcome.Pass) {
                signalToken?.cancel()
                toolReport(ReadinessCheckId.AudioSignal, CheckOutcome.Pass, f("pf_signal_from_stt", vm.capture.currentDeviceName))
            }
        } else if (outcomeOf(ReadinessCheckId.Transcription) != CheckOutcome.Pass) {
            toolReport(ReadinessCheckId.Transcription, CheckOutcome.Warning, t("pf_stt_no_text"), ReadinessFix.TestAudio)
        }
    }

    private suspend fun testAiAsync() {
        if (!toolReady("pf_ai_title")) return
        beginTool(f("pf_ai_title_q", testQuestion))
        val token = toolToken!!
        val sb = StringBuilder()
        toolOutput = t("pf_ai_requesting")
        try {
            val (answer, sample, model) = vm.testAiAsync(testQuestion) { b ->
                if (token.isActive) {
                    sb.appendLine("• " + b)
                    toolOutput = sb.toString()
                }
            }
            if (!token.isActive) return
            if (sample != null) {
                diagnostic("$model · first token ${sample.finalizedToFirstTokenMs} ms · first bullet ${sample.finalizedToFirstBulletMs} ms · complete ${sample.finalizedToCompleteMs} ms")
            }
            val ok = answer.bullets.size in 2..4 && answer.source == AnswerSource.Llm
            showResult(ok, if (ok) t("pf_ai_passed") else Loc.relocalize(answer.note) ?: t("err_unknown"))
            if (ok) {
                toolReport(ReadinessCheckId.AnswerEngine, CheckOutcome.Pass, f("pf_answer_ok_service", sample?.finalizedToFirstBulletMs))
            } else {
                toolReport(ReadinessCheckId.AnswerEngine, CheckOutcome.Fail, Loc.relocalize(answer.note) ?: t("err_unknown"), ReadinessFix.TestAi)
            }
        } catch (e: Exception) {
            if (e is ProviderException || e is IllegalStateException || e is BackendException) {
                if (token.isActive) showResult(false, CloudService.describe(e))
            } else {
                throw e
            }
        }
    }

    // ================= actions =================

    /**
     * A row's action: test tools run here, everything else is a Home deep link. Returns true when
     * the fix ran a test tool in-page (the page then scrolls the tools into view, the C#'s
     * BringIntoView).
     */
    fun onFixClick(row: ReadinessRowState): Boolean = when (row.fix) {
        ReadinessFix.TestAudio -> {
            onTestAudioClick()
            true
        }
        ReadinessFix.TestTranscription -> {
            onTestTranscriptionClick()
            true
        }
        ReadinessFix.TestAi -> {
            onTestAiClick()
            true
        }
        else -> {
            ReadinessPresentation.fixRoute(row.fix)?.let { route ->
                for (listener in navigateRequestedListeners.toList()) listener(route)
            }
            false
        }
    }

    /** Start interview (sticky action bar, Get started): only when nothing blocks. */
    override fun requestStart() {
        if (canStart) {
            for (listener in startRequestedListeners.toList()) listener()
        }
    }
}
