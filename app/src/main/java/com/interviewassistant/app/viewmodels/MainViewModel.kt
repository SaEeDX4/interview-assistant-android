package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.App
import com.interviewassistant.app.platform.KeystoreDataProtector
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.AppPaths
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.Branding
import com.interviewassistant.app.services.BuildFlavor
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.services.IAudioCaptureService
import com.interviewassistant.app.services.INetworkAvailability
import com.interviewassistant.app.services.IUpdateService
import com.interviewassistant.app.services.ProcessStatsProvider
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.services.Workspace
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.ConnectionToast
import com.interviewassistant.app.uistate.ConnectionToastGate
import com.interviewassistant.app.uistate.HistoryCounter
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.uistate.ReadinessStore
import com.interviewassistant.app.uistate.RelayConnection
import com.interviewassistant.client.BackendException
import com.interviewassistant.client.CloudAnswerProvider
import com.interviewassistant.client.CloudSession
import com.interviewassistant.client.CloudState
import com.interviewassistant.contracts.AccountDto
import com.interviewassistant.contracts.AnswerPurposes
import com.interviewassistant.contracts.BillingStates
import com.interviewassistant.contracts.TranscriptionContextDocument
import com.interviewassistant.core.diagnostics.LatencySample
import com.interviewassistant.core.diagnostics.SessionMetrics
import com.interviewassistant.core.intelligence.AnswerStyle
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.live.AdaptiveLevel
import com.interviewassistant.core.live.Presentation
import com.interviewassistant.core.orchestration.AnswerKind
import com.interviewassistant.core.orchestration.AnswerSource
import com.interviewassistant.core.orchestration.EngineOptions
import com.interviewassistant.core.orchestration.AnswerView
import com.interviewassistant.core.orchestration.EngineStatus
import com.interviewassistant.core.orchestration.InterviewEngine
import com.interviewassistant.core.persistence.IDataProtector
import com.interviewassistant.core.persistence.StoredSession
import com.interviewassistant.core.persistence.WorkspaceLocator
import com.interviewassistant.core.providers.ChatProviderOptions
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.IAnswerProvider
import com.interviewassistant.core.providers.OpenAiChatAnswerProvider
import com.interviewassistant.core.providers.OpenAiRealtimeTranscriber
import com.interviewassistant.core.providers.RealtimeTranscriberOptions
import com.interviewassistant.core.reports.HtmlReportRenderer
import com.interviewassistant.core.reports.ReportBuilder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** Mirror of `InterviewAssistant.App/ViewModels/MainViewModel.cs` (1,201 lines), member by member, in C# order. */

/** Mirror of the C#'s `BulletVm`: one rendered answer bullet (a paragraph in the Full style). */
data class BulletVm(val text: String, val isParagraph: Boolean = false)

/** Mirror of `System.Windows.FlowDirection` (the two values the app uses). */
enum class FlowDirection { LeftToRight, RightToLeft }

/** Mirror of `System.Windows.Thickness` (doubles, as in WPF; the Compose layer maps them to Dp). */
data class Thickness(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Composition root + presentation state for the live window. Engine/capture events arrive on background
 * threads and are marshalled with [post] (the C#'s `Dispatcher.BeginInvoke`; never blocking audio or
 * network threads). Answer bullets are append-only so text never shifts while the candidate is reading.
 *
 * Construction seams (P4-PORT-NOTES DC-90…DC-97): the C# constructor builds Settings/Cloud/Updates and
 * captures the UI `Dispatcher`; here the composition root builds them in the same order and passes them
 * in, together with the UI poster, the timer scope, and the platform seams — which is also what makes
 * every behaviour below unit-testable with fakes. The two `DispatcherTimer`s are coroutine loops on
 * [scope]; their tick bodies are `internal` so tests can drive them without waiting.
 */
class MainViewModel(
    val settings: AppSettings,
    val cloud: CloudService,
    val updates: IUpdateService,
    val capture: IAudioCaptureService,
    private val scope: CoroutineScope,
    private val postToUi: (() -> Unit) -> Unit,
    private val network: INetworkAvailability,
    private val processStats: ProcessStatsProvider,
    private val workspaceFactory: (String, IDataProtector) -> Workspace = { root, protector -> Workspace(root, protector) },
    private val protectorFactory: () -> IDataProtector = { KeystoreDataProtector() },
    private val connectionGate: ConnectionToastGate = ConnectionToastGate { OffsetDateTime.now(ZoneOffset.UTC) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ObservableObject() {

    var knowledge: KnowledgeBase? = null
        private set

    private var _engine: InterviewEngine? = null
    private var _provider: IAnswerProvider? = null
    private var _transcriber: OpenAiRealtimeTranscriber? = null
    private var _transcriberOptions: RealtimeTranscriberOptions? = null
    private var _cloudSession: CloudSession? = null
    private var _sessionStartedUtc: OffsetDateTime? = null

    private var _displayedAnswerId: Int = -1
    private val _lastMeterTick = AtomicLong(0)
    private var _peakDb: Double = -100.0
    private var _lastCpuMs: Long = 0
    private var _lastCpuAt: OffsetDateTime = OffsetDateTime.now()

    val bullets: MutableList<BulletVm> = mutableListOf()

    /** Meaningful action results for the in-app notification area (never per click). The C#'s `Notify` event. */
    val notifyListeners: MutableList<(String, ToastKind, String?) -> Unit> = mutableListOf()

    fun toast(text: String, kind: ToastKind = ToastKind.Info, detail: String? = null) {
        val snapshot = synchronized(notifyListeners) { notifyListeners.toList() }
        for (listener in snapshot) listener(text, kind, detail)
    }

    /** A report was saved (Home refreshes its Reports list immediately). The C#'s `ReportSaved` event. */
    val reportSavedListeners: MutableList<(String) -> Unit> = mutableListOf()

    /** The workspace was opened, switched or closed (sign-in, sign-out, account switch). The C#'s `WorkspaceChanged` event. */
    val workspaceChangedListeners: MutableList<() -> Unit> = mutableListOf()

    /** Raised after bullets/coach content change (the window re-fits text to avoid scrolling). The C#'s `BulletsChanged` event. */
    val bulletsChangedListeners: MutableList<() -> Unit> = mutableListOf()

    private fun fireWorkspaceChanged() {
        val snapshot = synchronized(workspaceChangedListeners) { workspaceChangedListeners.toList() }
        for (listener in snapshot) listener()
    }

    private fun fireBulletsChanged() {
        val snapshot = synchronized(bulletsChangedListeners) { bulletsChangedListeners.toList() }
        for (listener in snapshot) listener()
    }

    /** The Live Assistant's Quick help popover is open (rc.5: replaces the big introduction overlay). */
    var showLiveIntro: Boolean by bindable(false)

    /**
     * Quick help closed. It opens by itself only once (first use): [remember] records that in
     * [AppSettings.liveIntroDismissed]; afterwards it opens only with "?" (there is no "Don't show again").
     */
    fun dismissLiveIntro(remember: Boolean = true) {
        showLiveIntro = false
        if (remember && !settings.liveIntroDismissed) {
            settings.liveIntroDismissed = true
            settings.save()
        }
    }

    // The commands (declared after every bindable they read: RelayCommand evaluates its predicate once
    // at construction for the can-execute flow — the C# builds them in the constructor, after all fields).
    val startPauseCommand: RelayCommand
    val stopCommand: RelayCommand
    val shorterCommand: RelayCommand
    val technicalCommand: RelayCommand
    val exampleCommand: RelayCommand
    val fullCommand: RelayCommand
    val regenerateCommand: RelayCommand
    val previousCommand: RelayCommand
    val nextCommand: RelayCommand
    val toggleCoachCommand: RelayCommand
    val resetAdaptiveCommand: RelayCommand

    private val updateTimerJob: Job
    private val diagTimerJob: Job
    private var accountTimerJob: Job? = null

    init {
        Loc.language = uiLanguage

        // The C#'s cross-property raises, in one place: the bindable delegate notifies on a real change
        // (the C#'s Set returning true), and the dependent computed properties are raised here, exactly
        // where the C# raises them inside its setters.
        addPropertyChangedListener { name ->
            when (name) {
                "isCoachView", "isProfileGap" -> raise("lAnswer")
                "isListening", "isPaused" -> raise("startPauseLabel")
                "historyPosition" -> raise("historyLabel")
                "question" -> {
                    raise("hasQuestionText")
                    raise("questionFlowDirection")
                }
                "liveTranscript" -> raise("liveTranscriptFlowDirection")
                "compactSpacing" -> {
                    raise("bulletMargin")
                    raise("answerLineHeight")
                }
                "adaptive" -> raise("adaptiveLabel")
            }
        }

        updateTimerJob = scope.launch {
            while (isActive) {
                delay(60_000)
                onUpdateTick()
            }
        }
        cloud.addChangedListener {
            post {
                raise("accountLabel")
                raise("usingCloud")
                raiseMinutes()
                raise("serviceBanner")
                onConnectivity()
                openWorkspaceForCurrentAccount()
            }
        }
        Loc.addLanguageChangedListener { post { refreshLanguage() } }
        capture.frameReadyListeners += { frame, db -> onAudioFrame(frame, db) }
        capture.statusChangedListeners += { s -> post { onCaptureStatus(s) } }
        diagTimerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                onDiagTick()
            }
        }
        // The commands are built by the init block at the end of the class: RelayCommand evaluates its
        // can-execute predicate once at construction, so every bindable must already exist (in C# the
        // constructor runs after all field initializers; here the init blocks run in textual order).
    }

    /** The C#'s 1-minute update-timer tick (internal so tests can drive it; see the class doc). */
    internal fun onUpdateTick() {
        scope.launch { updates.tickAsync(sessionActive = isListening || isPaused || _cloudSession != null) }
    }

    /** The C#'s 1-second diagnostics-timer tick (internal so tests can drive it; see the class doc). */
    internal fun onDiagTick() {
        if (settings.showDiagnostics) refreshDiagnostics()
        pollConnection()
    }

    /** The C#'s `CommandManager.InvalidateRequerySuggested()`: every command re-evaluates (DC-84). */
    private fun invalidateCommands() {
        for (c in listOf(
            startPauseCommand, stopCommand, shorterCommand, technicalCommand, exampleCommand,
            fullCommand, regenerateCommand, previousCommand, nextCommand, toggleCoachCommand, resetAdaptiveCommand,
        )) c.raiseCanExecuteChanged()
    }

    // ---------------- localisation ----------------

    /** Effective UI language: setting, else the system display language if supported, else English. */
    val uiLanguage: String
        get() = LanguageRegistry.find(settings.uiLanguage)?.code
            ?: LanguageRegistry.find(Locale.getDefault().language)?.code
            ?: "en"

    fun l(key: String): String = Loc.t(key)

    val uiFlowDirection: FlowDirection get() = if (Loc.isRtl) FlowDirection.RightToLeft else FlowDirection.LeftToRight
    val lQuestion: String get() = l("question")
    val lAnswer: String get() = if (isProfileGap) l("live_gap_header") else if (isCoachView) l("coach") else l("answer")
    val lWaiting: String get() = l("waiting")
    val lTypeHint: String get() = l("type_hint")
    val lShorter: String get() = l("shorter")
    val lTechnical: String get() = l("technical")
    val lExample: String get() = l("example")
    val lFull: String get() = l("full")
    val lKeywords: String get() = l("keywords")
    val lStructure: String get() = l("structure")

    /** Re-renders every text that is computed in code (view-bound {Loc} texts refresh by themselves). */
    fun refreshLanguage() {
        Loc.language = uiLanguage
        for (n in listOf(
            "uiLanguage", "uiFlowDirection", "lQuestion", "lAnswer", "lWaiting", "lTypeHint", "lShorter",
            "lTechnical", "lExample", "lFull", "lKeywords", "lStructure", "startPauseLabel", "adaptiveLabel",
            "presentationLabel", "accountLabel", "serviceBanner", "historyLabel", "activeLabel",
        )) raise(n)
        raiseMinutes()
        if (_engine != null) applyStatus(_engine!!.status, _engine!!.statusDetail)
        _engine?.history?.firstOrNull { a -> a.id == _displayedAnswerId }?.let { shown -> answerMeta = meta(shown) }
        note = Loc.relocalize(note) // a status note already on screen follows the new language
        audioStatus = capture.statusLabel
    }

    // ---------------- coach / adaptive ----------------

    var isCoachView: Boolean by bindable(false)
        private set

    /**
     * The answer on screen is the engine's honest guidance for a personal question the profile has no data for
     * ([AnswerSource.Guidance]): shown as a distinct "Not in your profile yet" card, never as the candidate's answer.
     */
    var isProfileGap: Boolean by bindable(false)
        private set

    var coachKeywords: String by bindable("")
        private set
    var coachStructure: String by bindable("")
        private set
    var coachReminder: String? by bindable(null)
        private set

    var answerFlowDirection: FlowDirection by bindable(FlowDirection.LeftToRight)
        private set

    var adaptive: AdaptiveLevel by bindable(AdaptiveLevel.Normal)
        private set

    val adaptiveLabel: String
        get() = when (adaptive) {
            AdaptiveLevel.Concise -> l("adaptive_concise")
            AdaptiveLevel.Rapid -> l("adaptive_rapid")
            else -> ""
        }

    val presentation: Presentation get() = _engine?.presentation ?: Presentation.Answer
    val presentationLabel: String get() = if (presentation == Presentation.Coach) l("coach") else l("answer")

    /** Ctrl+Alt+C: next question is shown as Coach (3 keywords + structure) or full Answer bullets. */
    fun toggleCoach() {
        if (_engine == null) return
        _engine!!.togglePresentation()
        raise("presentation")
        raise("presentationLabel")
        AppLog.info("Presentation: " + _engine!!.presentation)
    }

    /** Ctrl+Alt+R: back to normal answer length after the interviewer slows down. */
    fun resetAdaptive() {
        _engine?.resetAdaptive()
        adaptive = AdaptiveLevel.Normal
    }

    // ---------------- account ----------------

    /** Customer builds always use the service; only developer builds can switch to the own-key prototype path. */
    val usingCloud: Boolean get() = !BuildFlavor.Developer || (settings.mode == "cloud" && cloud.isConfigured)

    val accountLabel: String
        get() = when (cloud.state) {
            CloudState.SignedIn -> cloud.account?.let { a -> Loc.f("acct_signed_in_as", a.email, billingLabel(a)) }
                ?: Loc.t("acct_signed_out")
            CloudState.NotConfigured -> Loc.t(if (BuildFlavor.Developer) "acct_developer_mode" else "acct_not_configured")
            CloudState.Connecting -> Loc.t("acct_connecting")
            CloudState.ServiceUnavailable -> Loc.t("acct_unavailable")
            CloudState.UpdateRequired -> Loc.t("acct_update_required")
            CloudState.SessionExpired -> Loc.t("acct_session_expired")
            CloudState.AccountDisabled -> Loc.t("acct_disabled")
            else -> Loc.t("acct_signed_out")
        }

    /** Persistent banner for states that block live help (empty when everything is fine). An outage stays visible until it recovers. */
    val serviceBanner: String
        get() = if (!usingCloud) "" else when (cloud.state) {
            CloudState.ServiceUnavailable -> Loc.t("banner_offline")
            CloudState.UpdateRequired, CloudState.AccountDisabled, CloudState.SessionExpired, CloudState.NotConfigured ->
                Loc.relocalize(cloud.message) ?: accountLabel
            CloudState.SignedIn ->
                if (cloud.account?.billingState in setOf(BillingStates.TrialExhausted, BillingStates.Expired, BillingStates.Paused, BillingStates.Revoked))
                    Loc.f("banner_upgrade", billingLabel(cloud.account!!))
                else ""
            else -> ""
        }

    // ---------------- connection notifications ----------------

    // Three independent sources: the service (account refresh), the live relay (transcriber reconnecting) and the
    // network availability. The gate debounces them: no toast for short blips, "restored" only after a shown "lost",
    // at most one lost/restored pair per ~10 s. The offline banner (ServiceBanner) stays for the whole outage.
    private var _serviceUp: Boolean = true
    private var _relayUp: Boolean = true
    private var _networkUp: Boolean = true

    /** Service state changed: an outage counts once the service reports it unavailable (a retry in progress changes nothing). */
    private fun onConnectivity() {
        if (!usingCloud) {
            _serviceUp = true
            reportConnection(silent = true)
            return
        }
        if (cloud.state == CloudState.Connecting) return // a retry in progress is not a new transition
        _serviceUp = cloud.state != CloudState.ServiceUnavailable
        // Back up but signed out / blocked (not a network matter): forget the outage without announcing a recovery.
        reportConnection(silent = _serviceUp && cloud.state != CloudState.SignedIn)
    }

    private fun reportConnection(silent: Boolean = false) =
        showConnectionToast(connectionGate.report(_serviceUp && _relayUp && _networkUp, silent))

    /**
     * The live relay's own state drives the connection notices — not the status line, which an answer ("Answering") or
     * an error overwrites while the connection is still down. Reconnecting = down; Connected = up ("restored" if a
     * "lost" was shown); Failed = the session ended: the outage is closed silently, never reported as restored.
     */
    private fun onRelayConnection(c: ConnectionStatus) {
        val t = RelayConnection.transition(_relayUp, c) ?: return
        _relayUp = t.up
        reportConnection(silent = t.silent)
    }

    private fun pollConnection() {
        if (connectionGate.needsPolling) showConnectionToast(connectionGate.poll())
    }

    private fun showConnectionToast(t: ConnectionToast) {
        if (t == ConnectionToast.Lost)
            toast(Loc.t("toast_connection_lost_reconnecting"), ToastKind.Warning, Loc.t("toast_connection_lost_detail"))
        else if (t == ConnectionToast.Restored)
            toast(Loc.t("toast_connection_restored"), ToastKind.Success, Loc.t("toast_connection_restored_detail"))
    }

    private var _periodRemainingSeconds: Int? = null
    private var _todayRemainingSeconds: Int? = null

    /** Plan minutes left this billing period (never confused with the per-day safety limit). */
    private val periodMinutesLeft: Int?
        get() = (_periodRemainingSeconds ?: cloud.account?.usage?.liveSecondsRemaining)?.let { it / 60 }

    private val todayMinutesLeft: Int?
        get() = (_todayRemainingSeconds
            ?: cloud.account?.usage?.liveSecondsAvailableToday?.takeIf { it >= 0 })?.let { it / 60 }

    private val planMinutes: Int
        get() = cloud.account?.let { a ->
            if (a.billingState == BillingStates.Trial || a.billingState == BillingStates.TrialExhausted)
                a.entitlements.trialMinutes
            else a.entitlements.liveMinutesPerPeriod
        } ?: 0

    /** Compact header label: plan minutes for the period; today's figure is added only when it is the real limit. */
    val minutesLeftLabel: String
        get() {
            if (!usingCloud) return ""
            val period = periodMinutesLeft ?: return ""
            val label = if (planMinutes > 0) Loc.f("min_label", period, planMinutes) else Loc.f("min_label_plain", period)
            val today = todayMinutesLeft
            return if (today != null && today < period) Loc.f("min_today", label, today) else label
        }

    val minutesTooltip: String
        get() {
            if (!usingCloud) return ""
            val period = periodMinutesLeft ?: return ""
            val sb = StringBuilder()
            sb.append(if (planMinutes > 0) Loc.f("min_tt_period", period, planMinutes) else Loc.f("min_tt_period_plain", period))
            val today = todayMinutesLeft
            if (today != null) {
                val cap = cloud.account?.usage?.dailyLiveMinutesCap ?: 0
                sb.append('\n').append(Loc.f("min_tt_today", today)).append(if (cap > 0) " " + Loc.f("min_tt_cap", cap) else ".")
            }
            sb.append('\n').append(Loc.t("min_tt_paused"))
            return sb.toString()
        }

    private fun raiseMinutes() {
        raise("minutesLeftLabel")
        raise("minutesTooltip")
    }

    /** Variants rework the DISPLAYED history item (never "the latest question" implicitly). */
    private fun canRework(): Boolean =
        _provider != null && _engine?.history?.any { a -> a.id == _displayedAnswerId } == true

    // ---------------- bindable state ----------------

    var statusText: String by bindable(Loc.t("status_ready"))
    var statusBrush: String by bindable("B.TextDim")
    var statusPulse: Boolean by bindable(false)
    var statusDetail: String? by bindable(null)

    var question: String by bindable("")

    val hasQuestionText: Boolean get() = question.isNotEmpty()

    /** The interviewer's words follow their own script (an Arabic/Persian question reads right-to-left even in an English UI). */
    val questionFlowDirection: FlowDirection get() = textFlow(question)
    val liveTranscriptFlowDirection: FlowDirection get() = textFlow(liveTranscript)

    var liveTranscript: String by bindable("")
    var pending: String by bindable("")
    var note: String? by bindable(null)
    var answerMeta: String by bindable("")
    var audioLevel: Double by bindable(0.0)
    var audioStatus: String by bindable("—")
    var isListening: Boolean by bindable(false)
    var isPaused: Boolean by bindable(false)

    val startPauseLabel: String get() = if (isListening) l("pause") else if (isPaused) l("resume") else l("start")

    var diagnostics: String by bindable("")
    var historyPosition: Int by bindable(-1)

    /** "n / total" of the answer on screen; empty (hidden) without history — never "0 / 1" (see [HistoryCounter]). */
    val historyLabel: String get() = HistoryCounter.text(historyPosition, _engine?.history?.size ?: 0)

    var contextSummary: String by bindable("")

    private var _fitFontSize: Double? = null

    /** Base size from the font preset, reduced by auto-fit (never below 70 %) so every bullet is visible without scrolling. */
    val answerFontSize: Double get() = _fitFontSize ?: settings.answerFontSize
    val coachFontSize: Double get() = Math.rint(answerFontSize * 1.18)
    val baseAnswerFontSize: Double get() = settings.answerFontSize
    val minAnswerFontSize: Double get() = max(13.0, Math.rint(settings.answerFontSize * 0.7))

    var compactSpacing: Boolean by bindable(false)

    /** First auto-fit step: tighter bullet spacing and line height (still readable) before any font reduction. */
    val bulletMargin: Thickness get() = Thickness(0.0, 0.0, 0.0, if (compactSpacing) 6.0 else 14.0)
    val answerLineHeight: Double get() = round1(answerFontSize * (if (compactSpacing) 1.24 else 1.42))

    fun setFitFontSize(size: Double?) {
        var v: Double? = if (size != null) (Math.rint(size * 2) / 2).coerceIn(minAnswerFontSize, baseAnswerFontSize) else null
        if (v == baseAnswerFontSize) v = null
        if (v == _fitFontSize) return
        _fitFontSize = v
        raise("answerFontSize")
        raise("coachFontSize")
        raise("answerLineHeight")
    }

    val questionFontSize: Double get() = settings.questionFontSize
    val isThinking: Boolean get() = _engine?.current?.let { !it.isComplete } == true && bullets.isEmpty()

    companion object {
        /** One-line plan text from the server's billing state (no entitlement logic here). */
        fun billingLabel(a: AccountDto): String = when (a.billingState) {
            BillingStates.Trial -> Loc.f("bill_trial", a.usage.liveSecondsRemaining / 60)
            BillingStates.TrialExhausted -> Loc.t("bill_trial_used")
            BillingStates.Active -> a.entitlements.planName
            BillingStates.Canceling -> Loc.f(
                "bill_ends", a.entitlements.planName,
                a.subscription?.currentPeriodEndUtc?.let { end ->
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Loc.locale)
                        .format(end.atZoneSameInstant(ZoneId.systemDefault()))
                },
            )
            BillingStates.PastDue -> Loc.f("bill_payment_problem", a.entitlements.planName)
            BillingStates.Paused -> Loc.t("bill_paused")
            BillingStates.Expired -> Loc.t("bill_ended")
            BillingStates.Revoked -> Loc.t("bill_revoked")
            else -> a.entitlements.planName
        }

        private fun textFlow(text: String): FlowDirection {
            var rtl = 0
            var ltr = 0
            for (ch in text) {
                if (!ch.isLetter()) continue
                if (ch in '\u0590'..'\u08FF' || ch in '\uFB1D'..'\uFDFF' || ch in '\uFE70'..'\uFEFF') rtl++ else ltr++
            }
            return if (rtl > ltr) FlowDirection.RightToLeft else FlowDirection.LeftToRight
        }

        /** The C#'s `Math.Round(x, 1)` — .NET rounding is half-to-even, `Math.rint` (the :core precedent). */
        private fun round1(v: Double): Double = Math.rint(v * 10) / 10.0

        /** The C#'s `:0.00` format in the UI culture (Latin digits, no grouping — see [LanguageRegistry.numberFormat]). */
        private fun format2(v: Double): String {
            val nf = LanguageRegistry.numberFormat(Loc.language)
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
            nf.isGroupingUsed = false
            return nf.format(v)
        }

        /** The C#'s `:0` / `:0.0` / `:0.000` formats in the UI culture, with [digits] fraction digits. */
        private fun formatFixed(v: Double, digits: Int): String {
            val nf = LanguageRegistry.numberFormat(Loc.language)
            nf.minimumFractionDigits = digits
            nf.maximumFractionDigits = digits
            nf.isGroupingUsed = false
            return nf.format(v)
        }
    }

    // ---------------- composition ----------------

    var workspace: Workspace? = null
        private set

    var activeLabel: String by bindable("")

    fun initialize(): String? {
        try {
            openWorkspaceForCurrentAccount()
            if (cloud.isConfigured) {
                scope.launch { cloud.refreshAsync() }
                // Keep plan/billing state current (e.g. after a renewal or a cancellation in the portal) and recover after network loss.
                accountTimerJob = scope.launch {
                    while (isActive) {
                        delay(10 * 60_000)
                        if (!_toggleBusy) cloud.refreshAsync()
                    }
                }
                network.addAvailabilityChangedListener { available ->
                    post {
                        _networkUp = available
                        reportConnection()
                        if (available) scope.launch { cloud.refreshAsync() }
                    }
                }
            }
        } catch (ex: Exception) {
            if (ex is IOException || ex is SerializationException || ex is SecurityException || ex is GeneralSecurityException) {
                AppLog.error("Workspace load failed", ex)
                useKnowledge(KnowledgeBase.empty(), Loc.t("no_interview_prepared"))
                return Loc.t("toast_workspace_failed") + " " + Loc.t("toast_workspace_failed_detail")
            }
            throw ex
        }
        return null
    }

    /** Switches the live engine to a profile/target pack (only while not listening). */
    fun useKnowledge(kb: KnowledgeBase, label: String) {
        knowledge = kb
        activeLabel = label
        buildEngine()
        invalidateReadiness() // the prepared answers the checks looked at were replaced
        AppLog.info(
            "Knowledge loaded: ${kb.questions.size} prepared questions, ${kb.stories.size} stories, ${kb.snippets.size} snippets " +
                "(${if (kb.questions.isEmpty()) "generic" else "target pack"})",
        )
    }

    /** Loads the built-in sample profile (fixture) — explicit user action or self-test only. */
    fun loadSample(dir: String?): Boolean {
        val s = Workspace.loadSample(dir) ?: return false
        useKnowledge(s.first, s.second)
        return true
    }

    // ---------------- account-scoped workspace ----------------

    private var _workspaceOwner: String? = null

    /**
     * The open workspace belongs to the account that is signed in right now. False only during the short deferred
     * switch (another account signed in while an interview was running): Home then shows and changes nothing.
     */
    val workspaceMatchesAccount: Boolean
        get() {
            if (workspace == null) return false
            val owner = currentOwner
            return owner != null && owner == _workspaceOwner
        }

    var activeProfileId: String? = null
        private set
    var activeTargetId: String? = null
        private set

    val hasWorkspace: Boolean get() = workspace != null

    /** The one readiness snapshot every surface shows (pre-interview page, Get started, Interviews, indicators). */
    val readiness: ReadinessStore = ReadinessStore()

    /** What a readiness result is valid for: this account, the active interview and the playback device. */
    val readinessContext: String
        get() = ReadinessStore.contextOf(currentOwner, activeTargetId, settings.playbackDeviceId, _readinessRevision.get())

    /** The state every surface shows: for what is selected now, and "Out of date" once a result is older than 30 minutes. */
    val readinessState: ReadinessState get() = readiness.snapshot.stateFor(readinessContext, OffsetDateTime.now())

    private val _readinessRevision = AtomicInteger(0)

    /**
     * Something a finished pre-interview result depends on changed — prepared answers loaded or re-prepared, the profile
     * saved, settings applied (window on top, device): the result becomes "Out of date" everywhere at once.
     */
    fun invalidateReadiness() {
        _readinessRevision.incrementAndGet()
        readiness.touch()
    }

    /** Selects the interview used by the Live Assistant (stored per account workspace). */
    fun setActive(profileId: String?, targetId: String?) {
        activeProfileId = profileId
        activeTargetId = targetId
        workspace?.store?.setActive(profileId, targetId)
    }

    /**
     * Opens the workspace of the signed-in account (or the local one in developer mode / self-test). Signed out:
     * no workspace at all — no profile, interview, report or prepared answer of anybody is loaded or visible.
     * Never switches during an interview session (sign-out stops the session first).
     */
    private val currentOwner: String?
        get() = if (App.selfTestOutput != null) "selftest" else if (!usingCloud) "local" else cloud.auth?.session?.userId

    fun openWorkspaceForCurrentAccount() {
        val owner = currentOwner
        // First call (startup) always initializes — signed out at startup must still get the empty knowledge base and an engine.
        if (_workspaceInitialized && owner == _workspaceOwner && (workspace != null || owner == null)) return
        if (sessionActive) {
            // A token that expires mid-interview (owner null) keeps the interview running. A DIFFERENT account signing
            // in must not inherit this interview: stop it first (its report is saved to the current account), then switch.
            if (owner != null && _workspaceOwner != null && owner != _workspaceOwner && !_switchPending) {
                _switchPending = true
                post {
                    scope.launch {
                        try {
                            stopAsync()
                        } catch (ex: Exception) {
                            AppLog.error("Stopping the interview before an account switch failed", ex)
                        } finally {
                            _switchPending = false
                            openWorkspaceForCurrentAccount()
                        }
                    }
                }
            }
            return
        }
        _workspaceInitialized = true
        val baseDir = File(AppPaths.roaming, "workspace").path
        // Close the previous account's workspace FIRST: whatever happens below, its data is no longer reachable.
        workspace?.clearViewerCopies()
        workspace = null
        _workspaceOwner = owner
        activeProfileId = null
        activeTargetId = null
        clearUserState()
        if (owner == null) {
            useKnowledge(KnowledgeBase.empty(), Loc.t("signed_out_no_data"))
            AppLog.info("Workspace closed (signed out)")
            fireWorkspaceChanged()
            return
        }
        try {
            val root: String = when (owner) {
                "selftest" -> File(AppPaths.local, "selftest-workspace-live").path
                "local" -> WorkspaceLocator.rootFor(baseDir, null)
                else -> {
                    if (WorkspaceLocator.adoptLegacy(baseDir, owner)) AppLog.info("Pre-RC2 local workspace adopted by the signed-in account")
                    WorkspaceLocator.rootFor(baseDir, owner)
                }
            }
            // Built and loaded locally; published only when everything succeeded (atomic switch).
            val ws = workspaceFactory(root, protectorFactory())
            ws.owner = if (owner == "local" || owner == "selftest") owner else WorkspaceLocator.accountKey(owner)
            ws.clearViewerCopies() // leftovers from a previous run
            ws.store.addFileQuarantinedListener {
                post { toast(Loc.t("toast_file_quarantined"), ToastKind.Warning, Loc.t("toast_file_quarantined_detail")) }
            }
            var (p, t) = ws.store.getActive()
            val legacyP = settings.activeProfileId
            if (p == null && legacyP != null && ws.store.getProfile(legacyP) != null) {
                p = legacyP
                t = settings.activeTargetId // pre-RC2 selection lived in settings.json
                ws.store.setActive(p, t)
                settings.activeProfileId = null
                settings.activeTargetId = null
                settings.save()
            }
            val (kb, label) = ws.loadActive(p, t)
            workspace = ws
            activeProfileId = p
            activeTargetId = t
            useKnowledge(kb, label)
            AppLog.info("Workspace opened for the current account")
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException || ex is SerializationException ||
                ex is GeneralSecurityException || ex is IllegalArgumentException
            ) {
                // Never fall back to another account's data: no workspace, generic knowledge, and a clear message.
                AppLog.error("Workspace open failed", ex)
                workspace = null
                activeProfileId = null
                activeTargetId = null
                useKnowledge(KnowledgeBase.empty(), Loc.t("no_interview_prepared"))
                toast(Loc.t("toast_workspace_failed"), ToastKind.Error, Loc.t("toast_workspace_failed_detail"))
            } else throw ex
        }
        fireWorkspaceChanged()
    }

    /** Removes everything user-specific from the screen and memory (answers, history, quota figures). */
    private fun clearUserState() {
        bullets.clear()
        question = ""
        contextSummary = ""
        note = null
        answerMeta = ""
        liveTranscript = ""
        pending = ""
        coachKeywords = ""
        coachStructure = ""
        coachReminder = null
        isCoachView = false
        isProfileGap = false
        lastReportPath = null
        historyPosition = -1
        _displayedAnswerId = -1
        _periodRemainingSeconds = null
        _todayRemainingSeconds = null
        _freshSessionOnStart = false
        raiseMinutes()
        raise("historyLabel")
    }

    /**
     * Sign out: the interview (if any) is stopped and its report saved to THIS account's workspace, the backend lease
     * ends, tokens are revoked/cleared, and the account's data leaves the screen and memory.
     *
     * [quiet] is true for "Switch account" (and after an account deletion): the sign-in page that follows says
     * what happened, so no "Signed out" notification is shown. A normal sign-out keeps it.
     */
    suspend fun signOutAsync(quiet: Boolean = false) {
        if (sessionActive) stopAsync()
        endLeaseAsync()
        cloud.signOutAsync()
        readiness.clear() // the checks belonged to the previous account
        openWorkspaceForCurrentAccount()
        if (!quiet) toast(Loc.t("toast_signed_out"), ToastKind.Info, Loc.t("toast_signed_out_detail"))
    }

    /** A live interview session exists (listening or paused) — it continues until Stop. */
    val sessionActive: Boolean get() = isListening || isPaused

    /**
     * (Re)creates providers + engine from current settings. Refused while a session is active: rebuilding would end
     * the interview (RC1 bug: changing a setting while paused replaced the session, history went 4/4 → 1/1).
     */
    fun buildEngine() {
        if (knowledge == null) return
        if (sessionActive && _engine != null) {
            AppLog.warn("Engine rebuild refused: an interview session is active")
            return
        }
        if (_engine != null) {
            val old = _engine!!
            scope.launch { old.disposeAsync() } // releases the previous transcriber connection
        }
        (_provider as? Closeable)?.close()
        _transcriberOptions = transcriberOptions()
        if (usingCloud && cloud.api != null) {
            // Cloud: the server picks the models and holds the provider key; audio goes through the service's relay, which
            // ends with the billable lease (the desktop never holds a provider credential).
            // Live lease while listening; the lease-free "manual" purpose for typed questions while paused or stopped.
            _provider = CloudAnswerProvider(
                cloud.api!!,
                sessionId = { _cloudSession?.takeIf { s -> s.active }?.id },
                deviceId = { settings.cloudDeviceId },
            )
            _transcriberOptions!!.serverConfiguredSession = true // the server configures the upstream session for each relay connection
            _transcriberOptions!!.credentialRejected = { _cloudSession?.forceTokenRefresh() }
            _transcriberOptions!!.connectionRefused = { status -> post { scope.launch { onRelayRefusedAsync(status) } } }
            _transcriber = OpenAiRealtimeTranscriber(::relayCredentialAsync, _transcriberOptions)
        } else {
            _provider = OpenAiChatAnswerProvider(SecretStore::getApiKey, chatOptions())
            _transcriber = OpenAiRealtimeTranscriber(SecretStore::getApiKey, _transcriberOptions)
        }
        _transcriber!!.diagnosticListeners += { m -> AppLog.info(m) }
        val engine = InterviewEngine(
            knowledge!!, _transcriber, _provider,
            EngineOptions().apply {
                useFastCache = settings.useFastCache
                developerKeyMode = !usingCloud
            },
        )
        _engine = engine
        if (settings.answerLanguage != "same" && LanguageRegistry.isSupported(settings.answerLanguage))
            engine.answerLanguageOverride = settings.answerLanguage
        if (settings.startInCoachMode) engine.presentation = Presentation.Coach
        engine.adaptiveChangedListeners += { lvl -> post { adaptive = lvl } }
        engine.turn.sensitivity = settings.vadSensitivity
        engine.statusChangedListeners += { s, d -> post { applyStatus(s, d) } }
        engine.transcriberConnectionChangedListeners += { c -> post { onRelayConnection(c) } }
        engine.liveTranscriptChangedListeners += { t -> post { liveTranscript = t } }
        engine.answerStartedListeners += { a -> post { onAnswerStarted(a) } }
        engine.bulletAddedListeners += { a, _ ->
            post {
                if (a.id == _displayedAnswerId) {
                    syncBullets(a)
                    pending = ""
                    raise("isThinking")
                }
            }
        }
        engine.pendingTextChangedListeners += { a, p -> post { if (a.id == _displayedAnswerId) pending = p } }
        engine.answerCompletedListeners += { a ->
            post {
                if (a.id == _displayedAnswerId) {
                    syncBullets(a)
                    pending = ""
                    note = Loc.relocalize(a.note)
                    answerMeta = meta(a)
                    raise("isThinking")
                }
                if (a.kind != AnswerKind.Variant) liveTranscript = ""
                invalidateCommands()
            }
        }
        engine.answerRestoredListeners += { failed, original ->
            post {
                if (failed.id == _displayedAnswerId) {
                    showAnswer(original, isNew = false)
                    note = Loc.t("note_variant_kept")
                    invalidateCommands()
                }
            }
        }
        engine.latencyMeasuredListeners += { s ->
            AppLog.info("Latency [${s.source}] finalize ${s.speechEndToFinalizedMs} ms, first bullet ${s.finalizedToFirstBulletMs} ms, total ${s.finalizedToCompleteMs} ms")
        }
        engine.interruptionDetectedListeners += { AppLog.info("Pace: likely interruption (answers shortened)") }
        engine.logListeners += { m -> AppLog.info(m) }
        applyStatus(EngineStatus.Ready, null)
        raise("presentation")
        raise("presentationLabel")
        // A new engine starts with an empty history: nothing on screen is a history position any more (the counter
        // hides instead of showing a stale "0 / n", and Previous/Next/rework stay disabled until the next answer).
        historyPosition = -1
        _displayedAnswerId = -1
        raise("historyLabel")
        invalidateCommands()
    }

    /** Applies the settings that may change during an interview without ending it (sensitivity, fast cache, coach default). */
    fun applyLiveSettings() {
        if (_engine == null) return
        _engine!!.turn.sensitivity = settings.vadSensitivity
        _engine!!.options.useFastCache = settings.useFastCache
        _engine!!.answerLanguageOverride =
            if (settings.answerLanguage != "same" && LanguageRegistry.isSupported(settings.answerLanguage)) settings.answerLanguage else null
    }

    private fun post(a: () -> Unit) = postToUi(a)

    // ---------------- audio ----------------

    /** Extra consumer of the exact production capture stream (used by Test Transcription). */
    private var _audioTap: ((ByteArray) -> Unit)? = null

    private fun onAudioFrame(frame: ByteArray, db: Double) {
        _engine?.onAudioFrame(frame, db)
        _audioTap?.invoke(frame)
        _peakDb = max(_peakDb, db)
        val now = nowMillis()
        if (now - _lastMeterTick.get() < 60) return // ~16 Hz UI updates, never per-packet
        _lastMeterTick.set(now)
        val level = ((_peakDb + 60) / 60.0).coerceIn(0.0, 1.0)
        _peakDb = -100.0
        post { audioLevel = level }
    }

    fun startAudioPreview() {
        if (!capture.isRunning) capture.start(settings.playbackDeviceId)
    }

    private var _captureTrouble: Boolean = false

    /** Device unplugged / Bluetooth reconnect / default-device change: one calm toast per transition, never a crash. */
    private fun onCaptureStatus(status: String) {
        audioStatus = capture.statusLabel
        val trouble = status == "DEVICE ERROR" || status == "NO PLAYBACK DEVICE" || status == "RECONNECTING DEVICE"
        if (trouble == _captureTrouble) return
        _captureTrouble = trouble
        if (!sessionActive) return
        if (trouble) toast(Loc.t("toast_audio_device_lost"), ToastKind.Warning, Loc.t("toast_audio_device_lost_detail"))
        else toast(Loc.t("toast_audio_device_back"), ToastKind.Success, capture.currentDeviceName)
    }

    fun restartAudio() {
        capture.stop()
        capture.start(settings.playbackDeviceId)
    }

    val currentDeviceName: String get() = capture.currentDeviceName

    fun transcriberOptions(): RealtimeTranscriberOptions = RealtimeTranscriberOptions().apply {
        model = settings.transcriptionModel
        protocol = settings.realtimeProtocol
        prompt = knowledge?.context?.buildTranscriptionPrompt() ?: "Job interview."
        language = knowledge?.context?.interviewLanguage?.takeIf { it != "auto" } ?: ""
    }

    fun chatOptions(): ChatProviderOptions = ChatProviderOptions().apply { model = settings.answerModel }

    /**
     * Live transcription test through the SAME capture service, converter, framer and OpenAiRealtimeTranscriber class
     * used in interviews (separate instance so it never triggers answers).
     */
    suspend fun testTranscriptionAsync(
        onTranscript: (String) -> Unit,
        onStatus: (String) -> Unit,
        duration: kotlin.time.Duration,
    ): String {
        startAudioPreview()
        val lease = if (usingCloud) startLeaseAsync() else null
        try {
            val opts = transcriberOptions()
            if (lease != null) useRelay(opts, lease)
            val t = if (lease != null)
                OpenAiRealtimeTranscriber({ lease.nextConnectionCredentialAsync() }, opts)
            else
                OpenAiRealtimeTranscriber(SecretStore::getApiKey, opts)
            try {
                val text = StringBuilder()
                val partials = mutableMapOf<String, String>()
                fun render() {
                    val all = text.toString() + if (partials.isNotEmpty()) " " + partials.values.joinToString(" ") else ""
                    post { onTranscript(all.trim()) }
                }
                t.partialTranscriptListeners += { id, d ->
                    synchronized(partials) {
                        partials[id] = (partials[id] ?: "") + d
                        render()
                    }
                }
                t.segmentCompletedListeners += { id, tr ->
                    synchronized(partials) {
                        partials.remove(id)
                        if (tr.isNotEmpty()) text.append(' ').append(tr.trim())
                        render()
                    }
                }
                t.speechStartedListeners += { post { onStatus("Speech detected…") } }
                t.statusChangedListeners += { st, d -> post { onStatus("$st ${d ?: ""}") } }
                t.diagnosticListeners += { m -> post { onStatus(m) } }
                _audioTap = { frame -> t.sendAudio(frame) }
                try {
                    t.startAsync()
                    try {
                        delay(duration)
                    } catch (ex: kotlinx.coroutines.CancellationException) {
                        // the C# swallows the delay's cancellation the same way
                    }
                } finally {
                    _audioTap = null
                }
                return "${t.activeModel}: ${t.status}"
            } finally {
                t.disposeAsync()
            }
        } finally {
            lease?.disposeAsync()
        }
    }

    /** AI test through the production answer pipeline (classify → retrieve → prompt → stream → validate). */
    suspend fun testAiAsync(question: String, onBullet: (String) -> Unit): Triple<AnswerView, LatencySample?, String> {
        if (knowledge == null) throw IllegalStateException("Knowledge not loaded")
        if (usingCloud) {
            // The real server path (proxy, entitlements, server model) as a typed question: no live lease, no live minutes.
            val cloudEngine = InterviewEngine(
                knowledge!!, null,
                CloudAnswerProvider(cloud.api!!, sessionId = { null }, deviceId = { settings.cloudDeviceId }, purpose = AnswerPurposes.Manual),
                EngineOptions().apply {
                    autoTick = false
                    useFastCache = false
                    developerKeyMode = false
                },
            )
            cloudEngine.bulletAddedListeners += { _, b -> post { onBullet(b) } }
            cloudEngine.submitManualQuestion(question)
            return Triple(cloudEngine.current!!, cloudEngine.metrics.samples.lastOrNull(), "cloud: " + (cloud.config?.answerModel ?: "server model"))
        }
        val provider = OpenAiChatAnswerProvider(SecretStore::getApiKey, chatOptions())
        try {
            val engine = InterviewEngine(
                knowledge!!, null, provider,
                EngineOptions().apply {
                    autoTick = false
                    useFastCache = false
                },
            )
            engine.bulletAddedListeners += { _, b -> post { onBullet(b) } }
            engine.submitManualQuestion(question)
            return Triple(engine.current!!, engine.metrics.samples.lastOrNull(), provider.activeModel)
        } finally {
            provider.close()
        }
    }

    // ---------------- controls ----------------

    private var _toggleBusy: Boolean = false

    /**
     * Asks for the authorized-use acknowledgement (set by the Live window). Every start path — Live Start, the hotkey,
     * Get started, the pre-interview page — goes through [toggleListeningAsync], so none can skip it.
     */
    var confirmAuthorizedUseAsync: (suspend () -> Boolean)? = null

    /** Start / pause. Re-entrancy guarded: a double click can never start two leases. */
    suspend fun toggleListeningAsync(startToast: String? = null) {
        if (_engine == null || _toggleBusy) return
        _toggleBusy = true
        try {
            toggleListeningCoreAsync(startToast)
        } finally {
            _toggleBusy = false
        }
    }

    private suspend fun toggleListeningCoreAsync(startToast: String?) {
        if (_engine == null) return
        if (isListening) {
            _engine!!.pause()
            isListening = false
            isPaused = true
            if (usingCloud) _engine!!.suspendTranscription() // the relay closes with the lease; Resume reconnects
            endLeaseAsync() // paused time is never billed
            if (usingCloud) toast(Loc.t("toast_paused"), ToastKind.Info, Loc.t("toast_paused_detail"))
            else toast(Loc.t("toast_paused_dev"), ToastKind.Info)
            return
        }
        // what the user must do first is said first (sign in, configure); the acknowledgement only when listening can start
        if (usingCloud) {
            if (!cloud.isConfigured) {
                showLocalNote(Loc.t("note_not_configured_listen"))
                return
            }
            if (!cloud.isSignedIn) {
                showLocalNote(Loc.t("note_sign_in_to_listen"))
                return
            }
        } else if (!SecretStore.hasKey) {
            showLocalNote(Loc.t("note_dev_key"))
            return
        }
        if (!settings.authorizedUseAcknowledged &&
            (confirmAuthorizedUseAsync == null || !confirmAuthorizedUseAsync!!.invoke())
        ) return
        // the first interview started (from Home or straight from the Live window): Home no longer opens at every launch
        if (!settings.firstRunCompleted) {
            settings.firstRunCompleted = true
            settings.save()
        }
        if (_freshSessionOnStart) {
            _freshSessionOnStart = false
            buildEngine()
            if (_engine == null) return
        }
        if (usingCloud && !beginLeaseAsync()) return
        startAudioPreview()
        if (_sessionStartedUtc == null) _sessionStartedUtc = OffsetDateTime.now(ZoneOffset.UTC)
        _engine!!.startListening()
        isListening = true
        isPaused = false
        toast(Loc.t(startToast ?: "toast_listening"), ToastKind.Success, Loc.t("toast_listening_detail"))
    }

    suspend fun stopAsync() {
        if (_engine == null) return
        val hadSession = sessionActive
        _engine!!.stopAsync()
        endLeaseAsync()
        isListening = false
        isPaused = false
        liveTranscript = ""
        if (settings.saveSessionTranscript) saveSession()
        val count = _engine!!.memory.questions.size
        lastReportPath = saveReport()
        if (hadSession) toast(
            if (lastReportPath != null) Loc.f("toast_report_saved", count) else Loc.t("toast_no_report"),
            if (lastReportPath != null) ToastKind.Success else ToastKind.Info,
        )
        // The next Start begins a NEW interview (fresh history, memory and numbering); until then the finished
        // interview stays browsable on screen.
        _freshSessionOnStart = count > 0 || _rebuildPending
        _rebuildPending = false
        // The sign-in ended during the interview (the session kept running): now that the report is saved to that
        // account, its answers and history leave the screen — signed out, the Live window shows no history.
        if (_workspaceOwner != null && currentOwner == null) openWorkspaceForCurrentAccount()
    }

    private var _freshSessionOnStart: Boolean = false

    /** Model/provider settings changed during a session: build a new engine at the next Start. */
    fun rebuildAfterSession() {
        _rebuildPending = true
    }

    private var _rebuildPending: Boolean = false

    /** Starts the server lease for live listening and wires its events. False (with a readable note) when refused. */
    private suspend fun beginLeaseAsync(): Boolean {
        try {
            _cloudSession = startLeaseAsync()
            useRelay(_transcriberOptions!!, _cloudSession!!)
            val session = _cloudSession!!
            fun sync() {
                _periodRemainingSeconds = if (session.periodRemainingSeconds >= 0) session.periodRemainingSeconds else null
                _todayRemainingSeconds = if (session.todayRemainingSeconds >= 0) session.todayRemainingSeconds else null
                raiseMinutes()
            }
            session.addRemainingChangedListener { post { sync() } }
            session.addWarningListener { w -> post { note = w } }
            session.addEndedListener { reason ->
                post {
                    scope.launch { if (session == _cloudSession) onLeaseEndedAsync(reason) }
                }
            }
            sync()
            return true
        } catch (ex: BackendException) {
            showLocalNote(
                when (ex.code) {
                    "allowance_exhausted" -> Loc.t("lease_allowance")
                    "daily_live_cap" -> Loc.t("lease_daily_cap")
                    "concurrent_limit" -> Loc.t("lease_concurrent")
                    "device_revoked", "device_limit" -> Loc.t("lease_device")
                    "maintenance" -> ex.message
                    else -> Loc.f("lease_typed_still_work", CloudService.describe(ex))
                },
            )
            if (ex.status == 0 || ex.status >= 500) scope.launch { cloud.refreshAsync() }
            return false
        }
    }

    /**
     * Deletes the server copy of deleted interviews' transcription context (names and terms uploaded for recognition).
     * Best-effort: offline or signed out, it stays until the account is deleted (privacy audit P7).
     */
    suspend fun forgetTranscriptionContextAsync(targetIds: Iterable<String>) {
        if (!usingCloud || cloud.api == null || !cloud.isSignedIn) return
        for (id in targetIds) {
            val g = try {
                UUID.fromString(id)
            } catch (ex: IllegalArgumentException) {
                continue
            }
            try {
                cloud.api!!.deleteDocumentAsync("targets", g)
            } catch (ex: BackendException) {
                if (ex.status != 404) AppLog.warn("Interview context not removed from the service (${ex.code})")
            }
        }
    }

    /**
     * The relay refused a (re)connection. Ended/expired/out of minutes: one heartbeat now lets the server say why, and
     * the normal lease-ended handling follows. Too many connections or an update is required: stop billing at once.
     */
    private suspend fun onRelayRefusedAsync(status: Int) {
        AppLog.warn("Live transcription connection refused ($status)")
        val lease = _cloudSession
        if (lease == null || !isListening) return
        if (status == 426 || status == 429) {
            if (_engine != null) {
                _engine!!.pause()
                isListening = false
                isPaused = true
                _engine!!.suspendTranscription()
            }
            endLeaseAsync()
            note = Loc.t(if (status == 426) "err_update_required_long" else "lease_end_generic")
            return
        }
        lease.heartbeatOnceAsync()
    }

    @Volatile
    private var _leaseRestarting: Boolean = false

    /**
     * Bearer for the next relay connection. While an ended lease is being replaced automatically, waits briefly for the
     * new one instead of giving up; null (transcriber stops) once no lease is active.
     */
    private suspend fun relayCredentialAsync(): String? {
        var i = 0
        while (i < 60 && _leaseRestarting && _cloudSession?.active != true) {
            delay(250)
            i++
        }
        return _cloudSession?.takeIf { it.active }?.nextConnectionCredentialAsync()
    }

    private fun useRelay(opts: RealtimeTranscriberOptions, lease: CloudSession) {
        opts.endpoint = lease.relayEndpoint.toString()
        opts.serverConfiguredSession = true
        opts.headers.clear()
        for ((k, v) in lease.relayHeaders) opts.headers[k] = v
    }

    private suspend fun startLeaseAsync(): CloudSession {
        var device = settings.cloudDeviceId ?: cloud.ensureDeviceAsync()
        val language = knowledge?.context?.interviewLanguage?.takeIf { it != "auto" }
        val target = publishTranscriptionContextAsync()
        try {
            return CloudSession.startAsync(cloud.api!!, device, target, language)
        } catch (ex: BackendException) {
            if (ex.code != "device_unknown") throw ex
            device = cloud.ensureDeviceAsync() // local id stale (e.g. reinstall) → re-register once
            return CloudSession.startAsync(cloud.api!!, device, target, language)
        }
    }

    /**
     * Sends the active interview's transcription context (job title, company, names and terms — no résumé or job
     * description text) as the user's own target document, so the server can bias recognition toward e.g. product
     * names. Returns the target id for the lease, or null when no interview is prepared or the upload failed.
     */
    private suspend fun publishTranscriptionContextAsync(): UUID? {
        if (knowledge == null) return null
        val targetId = activeTargetId?.let {
            try {
                UUID.fromString(it)
            } catch (ex: IllegalArgumentException) {
                null
            }
        } ?: return null
        val c = knowledge!!.context
        val doc = TranscriptionContextDocument(
            if (c.roleTitle == "the role") "" else c.roleTitle, c.companyName, c.transcriptionTerms(), c.interviewLanguage,
        )
        try {
            cloud.api!!.putTranscriptionContextAsync(targetId, doc)
            return targetId
        } catch (ex: BackendException) {
            if (ex.status != 0 && ex.status != 401) {
                AppLog.warn("Interview vocabulary could not be sent (" + ex.code + "); live transcription uses generic vocabulary.")
                return null
            }
            throw ex
        }
    }

    private suspend fun endLeaseAsync() {
        val s = _cloudSession
        _cloudSession = null
        if (s != null) {
            s.disposeAsync()
            _periodRemainingSeconds = null
            _todayRemainingSeconds = null // the account summary is authoritative again
            scope.launch { cloud.refreshAsync() }
            raiseMinutes()
        }
    }

    private suspend fun onLeaseEndedAsync(reason: String) {
        AppLog.info("Lease ended: $reason")
        // Network loss, sleep/wake or a crash-replaced lease: the user still wants help — start a new lease once, silently.
        val restart = isListening && reason in setOf("heartbeat_timeout", "session_ended", "expired", "not_found")
        _leaseRestarting = restart // set before the old lease disappears, so a reconnect in between waits for the new one
        _cloudSession = null
        if (restart) {
            val restarted: Boolean
            try {
                restarted = beginLeaseAsync()
            } finally {
                _leaseRestarting = false
            }
            if (restarted) {
                AppLog.info("Lease restarted automatically")
                if (_engine != null) _engine!!.ensureTranscription() // the old relay closed with the old lease
                return
            }
        }
        if (_engine != null && (isListening || isPaused)) {
            _engine!!.pause()
            isListening = false
            isPaused = true
            _engine!!.suspendTranscription()
        }
        note = Loc.t(
            when (reason) {
                "allowance_exhausted" -> "lease_end_allowance"
                "max_duration" -> "lease_end_max_duration"
                "daily_cap" -> "lease_end_daily_cap"
                "account_disabled" -> "lease_end_disabled"
                "device_revoked" -> "lease_end_device"
                else -> "lease_end_generic"
            },
        )
        cloud.refreshAsync()
    }

    var lastReportPath: String? by bindable(null)
        private set

    private var _switchPending: Boolean = false
    private var _workspaceInitialized: Boolean = false

    /**
     * Post-interview report (honesty-labelled), saved encrypted in the account's workspace; a plain HTML viewer copy is
     * written to the account's private view folder so "open last report" can show it in the browser.
     */
    fun saveReport(): String? {
        if (_engine == null || knowledge == null || _engine!!.memory.questions.isEmpty()) {
            _sessionStartedUtc = null
            return null
        }
        try {
            val id = UUID.randomUUID().toString().replace("-", "")
            val lang = LanguageRegistry.find(settings.reportLanguage)?.code ?: uiLanguage
            val started = _sessionStartedUtc ?: OffsetDateTime.now(ZoneOffset.UTC)
            val pack = if (activeTargetId != null) workspace?.store?.getPack(activeTargetId!!) else null
            val report = ReportBuilder.build(
                _engine!!.memory, knowledge!!, _engine!!.metrics, id, started, OffsetDateTime.now(ZoneOffset.UTC), lang, pack,
                _engine!!.metrics.reconnects, _engine!!.metrics.duplicatesSuppressed,
                knowledge!!.profile.preferredName.ifEmpty { knowledge!!.context.candidateName }, activeLabel,
            )
            if (workspace == null) return null // signed out: nothing is persisted anywhere
            workspace!!.store.saveSession(
                StoredSession(
                    id = id, profileId = activeProfileId ?: "", targetId = activeTargetId ?: "",
                    startedUtc = started, endedUtc = OffsetDateTime.now(ZoneOffset.UTC), reportJson = report.toJson(),
                ),
            )
            val path = workspace!!.writeViewerCopy(id, HtmlReportRenderer.render(report, Branding.productName))
            _sessionStartedUtc = null
            AppLog.info("Report saved (${report.questions.size} questions, ${report.excludedTurns} non-interview turns excluded)")
            val snapshot = synchronized(reportSavedListeners) { reportSavedListeners.toList() }
            for (listener in snapshot) listener(id)
            return path
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException || ex is SerializationException || ex is GeneralSecurityException) {
                AppLog.error("Report save failed", ex)
                return null
            }
            throw ex
        }
    }

    suspend fun submitManualAsync(text: String) {
        if (_engine == null || text.isBlank()) return
        _engine!!.submitManualQuestion(text)
    }

    /** Reworks the answer that is ON SCREEN (e.g. item 5 of 8): same history position, same question, no new entry. */
    private fun variant(style: AnswerStyle) {
        if (_engine == null || _displayedAnswerId < 0) return
        val engine = _engine!!
        val id = _displayedAnswerId
        scope.launch { engine.requestVariant(id, style) }
    }

    private fun navigate(delta: Int) {
        if (_engine == null) return
        val h = _engine!!.history
        if (h.isEmpty()) return
        val idx = (historyPosition + delta).coerceIn(0, h.size - 1)
        historyPosition = idx
        showAnswer(h[idx], isNew = false)
    }

    /**
     * New question → show it (latest position). Replacement of the item on screen (variant or continued question) →
     * update in place without moving. A replacement of an item the user is NOT looking at never pulls the view away.
     */
    private fun onAnswerStarted(a: AnswerView) {
        val replaced = a.replacesAnswerId
        if (replaced != null) {
            if (replaced == _displayedAnswerId) showAnswer(a, isNew = false)
            raise("historyLabel")
            return
        }
        showAnswer(a, isNew = true)
    }

    private fun showAnswer(a: AnswerView, isNew: Boolean) {
        _displayedAnswerId = a.id
        question = a.question
        contextSummary = a.contextSummary
        answerFlowDirection = if (LanguageRegistry.isRtl(a.answerLanguage)) FlowDirection.RightToLeft else FlowDirection.LeftToRight
        bullets.clear()
        syncBullets(a)
        pending = ""
        note = Loc.relocalize(a.note)
        answerMeta = meta(a)
        if (isNew && _engine != null) historyPosition = _engine!!.history.size - 1
        raise("historyLabel")
        raise("isThinking")
    }

    /**
     * Idempotent, append-only render: shows bullets of the answer not yet on screen. Safe however many times
     * AnswerStarted/BulletAdded notifications arrive (prevents duplicate bullets).
     */
    private fun syncBullets(a: AnswerView) {
        val coach = a.coach
        if (coach != null) {
            isProfileGap = false
            isCoachView = true
            coachKeywords = coach.keywordLine
            coachStructure = coach.structure
            coachReminder = coach.reminder
            fireBulletsChanged()
            return
        }
        // Profile-gap guidance is always its own card (also in Coach mode, which has no keywords for it).
        isProfileGap = a.source == AnswerSource.Guidance
        isCoachView = a.presentation == Presentation.Coach && !isProfileGap // coach card stays visible while keywords are generated
        coachKeywords = ""
        coachStructure = ""
        coachReminder = null
        val snapshot = a.snapshotBullets()
        for (i in bullets.size until snapshot.size) {
            bullets.add(BulletVm(snapshot[i], isParagraph = a.style == AnswerStyle.Full))
        }
        fireBulletsChanged()
    }

    private fun meta(a: AnswerView): String {
        val style = when (a.style) {
            AnswerStyle.VeryShort -> l("shorter")
            AnswerStyle.Technical -> l("technical")
            AnswerStyle.Example -> l("example")
            AnswerStyle.Full -> l("full")
            AnswerStyle.Concise -> l("adaptive_concise")
            AnswerStyle.Rapid -> l("adaptive_rapid")
            else -> ""
        }
        val src = when (a.source) {
            AnswerSource.Cache -> l("meta_prepared")
            AnswerSource.CacheFallback -> l("meta_prepared_fallback")
            AnswerSource.Llm -> l("meta_ai")
            AnswerSource.Guidance -> l("meta_guidance")
            else -> ""
        }
        return if (settings.showDiagnostics)
            "${if (style.isNotEmpty()) "$style · " else ""}$src · ${a.mode.name.lowercase(Locale.ROOT)} · " +
                "${a.category.lowercase(Locale.ROOT)} · ${a.confidence.name.lowercase(Locale.ROOT)} · match ${format2(a.matchScore)}"
        else style
    }

    private fun showLocalNote(text: String?) {
        note = text
    }

    private fun applyStatus(s: EngineStatus, detail: String?) {
        val (text, brush, pulse, sub) = when (s) {
            EngineStatus.Listening -> StatusLine(l("status_listening"), "B.Listening", true, l("detail_listening"))
            EngineStatus.Quiet -> StatusLine(l("status_quiet"), "B.Listening", false, l("detail_listening"))
            EngineStatus.HearingAudio -> StatusLine(l("status_hearing"), "B.Listening", true, null)
            EngineStatus.SpeechDetected -> StatusLine(l("status_speech"), "B.Listening", true, null)
            EngineStatus.Finalizing -> StatusLine(l("status_finalizing"), "B.Thinking", true, null)
            EngineStatus.Answering -> StatusLine(l("status_answering"), "B.Accent", true, null)
            EngineStatus.AnswerReady -> StatusLine(l("status_answer_ready"), "B.Listening", false, l("detail_listening"))
            EngineStatus.Paused -> StatusLine(l("status_paused"), "B.TextFaint", false, l("detail_paused"))
            EngineStatus.Reconnecting -> StatusLine(l("status_reconnecting"), "B.Thinking", true, null)
            EngineStatus.NoAudio -> StatusLine(l("status_no_audio"), "B.Thinking", false, null)
            EngineStatus.ApiError -> StatusLine(l("status_api_error"), "B.Error", false, null)
            EngineStatus.Stopped -> StatusLine(l("status_stopped"), "B.TextFaint", false, null)
            else -> StatusLine(l("status_ready"), "B.TextDim", false, null)
        }
        statusText = text
        statusBrush = brush
        statusPulse = pulse
        // Engine detail (e.g. which device to check) wins; otherwise a calm explanation of the state.
        statusDetail = Loc.relocalize(detail) ?: sub // engine messages re-rendered in the current UI language
        // Once listening ends the relay no longer matters: an outage is forgotten without a "restored" notification for a
        // connection that was not restored. (Up/down itself comes from the transcriber, see onRelayConnection.)
        if (s == EngineStatus.Stopped || s == EngineStatus.Paused || (s == EngineStatus.Ready && !_relayUp)) {
            _relayUp = true
            reportConnection(silent = true)
        }
        if (s == EngineStatus.ApiError && _engine != null && !_engine!!.isListening) isListening = false
    }

    /** One row of the C#'s ApplyStatus switch (text, brush key, pulse, calm sub-line). */
    private data class StatusLine(val text: String, val brush: String, val pulse: Boolean, val sub: String?)

    // ---------------- diagnostics ----------------

    /**
     * Technical lines for support (Help & about → Copy diagnostics; English on purpose, never shown as UI text): audio,
     * counters, latency, resources and the last log lines. Refreshed on demand (rc.5: the Live window has no panel any
     * more; the legacy ShowDiagnostics setting still refreshes it every second).
     */
    fun refreshDiagnostics() {
        if (_engine == null) return
        val m = _engine!!.metrics
        val stats = processStats.sample()
        val now = OffsetDateTime.now()
        val elapsedMs = max(1L, now.toInstant().toEpochMilli() - _lastCpuAt.toInstant().toEpochMilli())
        val cpu = (stats.cpuTimeMs - _lastCpuMs).toDouble() / elapsedMs / stats.processorCount * 100
        _lastCpuMs = stats.cpuTimeMs
        _lastCpuAt = now
        val s = m.samples
        fun p(f: (LatencySample) -> Long): String =
            "p50 ${SessionMetrics.percentile(s.map(f), 50.0)} / p95 ${SessionMetrics.percentile(s.map(f), 95.0)} ms"
        val last = s.lastOrNull()
        val sb = StringBuilder()
        sb.appendLine("Audio: ${capture.currentDeviceName} [$audioStatus]  sent ${formatFixed(m.audioMsSent / 1000.0, 0)}s  transcriber ${_transcriber?.status}  reconnects ${m.reconnects}")
        sb.appendLine("Questions ${m.questions}  prepared ${m.cacheAnswers}  AI ${m.llmAnswers}  fallback ${m.fallbackAnswers}  dupes suppressed ${m.duplicatesSuppressed}  API errors ${m.apiErrors}  LLM requests ${m.llmRequests}")
        if (last != null) sb.appendLine("Last: speech end→question ${last.speechEndToFinalizedMs} ms · match ${last.matchMs} ms · first token ${last.finalizedToFirstTokenMs} ms · first bullet ${last.finalizedToFirstBulletMs} ms · complete ${last.finalizedToCompleteMs} ms · speech end→first bullet ${last.speechEndToFirstBulletMs} ms")
        sb.appendLine(
            "Not answered: " + m.suppression.entries.filter { kv -> kv.value > 0 }
                .joinToString("  ") { kv -> "${kv.key} ${kv.value}" }.trimEnd() +
                "  variants ${m.variants}  guidance ${m.guidanceAnswers}",
        )
        if (s.size > 1) sb.appendLine("Finalize ${p { it.speechEndToFinalizedMs }} · first bullet ${p { it.finalizedToFirstBulletMs }} · end-to-answer ${p { it.speechEndToFirstBulletMs }}")
        sb.appendLine("CPU ${formatFixed(cpu, 1)}%  memory ${stats.workingSetBytes / 1048576} MB  managed ${stats.managedBytes / 1048576} MB  est. cost $${formatFixed(m.estimatedCostUsd(settings.priceTranscribePerMinute, settings.priceInputPerMTok, settings.priceOutputPerMTok), 3)}")
        for (line in AppLog.recent.takeLast(4)) sb.appendLine(line)
        diagnostics = sb.toString().trimEnd()
    }

    fun refreshFonts() {
        _fitFontSize = null
        raise("answerFontSize")
        raise("coachFontSize")
        raise("answerLineHeight")
        raise("questionFontSize")
        fireBulletsChanged()
    }

    private fun saveSession() {
        if (_engine == null) return
        try {
            val nowLocal = OffsetDateTime.now()
            val sb = StringBuilder("# Interview session ${nowLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\n\n")
            for (a in _engine!!.history) {
                sb.appendLine("## ${a.question}")
                for (b in a.bullets) sb.appendLine("- $b")
                sb.appendLine()
            }
            // Opt-in (Settings): the user chose a plain transcript in Documents. Unique per session, never overwritten.
            val name = "session-${nowLocal.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6) + ".md"
            File(AppPaths.sessions, name).writeText(sb.toString(), Charsets.UTF_8)
        } catch (ex: IOException) {
            AppLog.error("Session save failed", ex)
        }
    }

    val provider: IAnswerProvider? get() = _provider
    val engine: InterviewEngine? get() = _engine

    suspend fun disposeAsync() {
        diagTimerJob.cancel()
        updateTimerJob.cancel()
        accountTimerJob?.cancel()
        capture.dispose()
        // Closing the app during an interview still keeps its report (RC1 lost it). Saved locally FIRST: ending the
        // lease is a network call that may be slow or offline, and the window only waits a few seconds.
        if (sessionActive) {
            saveReport()
            if (settings.saveSessionTranscript) saveSession()
        }
        if (_engine != null) _engine!!.disposeAsync()
        endLeaseAsync()
        workspace?.clearViewerCopies() // plain viewer copies never outlive the app
        (_provider as? Closeable)?.close()
    }

    // The commands, built last (see the note in the first init block): the C# constructor's command wiring.
    init {
        startPauseCommand = RelayCommand({ scope.launch { toggleListeningAsync() } })
        stopCommand = RelayCommand({ scope.launch { stopAsync() } }, { isListening || isPaused })
        shorterCommand = RelayCommand({ variant(AnswerStyle.VeryShort) }, { canRework() })
        technicalCommand = RelayCommand({ variant(AnswerStyle.Technical) }, { canRework() })
        exampleCommand = RelayCommand({ variant(AnswerStyle.Example) }, { canRework() })
        fullCommand = RelayCommand({ variant(AnswerStyle.Full) }, { canRework() })
        regenerateCommand = RelayCommand({ variant(AnswerStyle.Balanced) }, { canRework() })
        previousCommand = RelayCommand({ navigate(-1) }, { historyPosition > 0 })
        nextCommand = RelayCommand({ navigate(+1) }, { _engine != null && historyPosition < (_engine?.history?.size ?: 0) - 1 })
        toggleCoachCommand = RelayCommand({ toggleCoach() })
        resetAdaptiveCommand = RelayCommand({ resetAdaptive() })
    }
}
