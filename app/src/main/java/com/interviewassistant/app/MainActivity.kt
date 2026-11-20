package com.interviewassistant.app

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.interviewassistant.app.platform.BatteryExemption
import com.interviewassistant.app.platform.CustomTabsUrlLauncher
import com.interviewassistant.app.platform.FocusPlatform
import com.interviewassistant.app.platform.FocusRestoreRecord
import com.interviewassistant.app.platform.LiveFocusRequests
import com.interviewassistant.app.platform.OAuthRedirectBus
import com.interviewassistant.app.platform.ShortcutAction
import com.interviewassistant.app.platform.ShortcutService
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.ui.components.AppDialog
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.home.FocusRegistry
import com.interviewassistant.app.ui.home.HomeFileBridge
import com.interviewassistant.app.ui.home.HomeScreen
import com.interviewassistant.app.ui.home.ShellHomeClipboard
import com.interviewassistant.app.ui.home.ShellHomeDialogs
import com.interviewassistant.app.ui.home.ShellHomeFiles
import com.interviewassistant.app.ui.home.ShellHomeShell
import com.interviewassistant.app.ui.live.LiveScreen
import com.interviewassistant.app.ui.preinterview.PreInterviewPageControllerImpl
import com.interviewassistant.app.ui.settings.SettingsPageControllerImpl
import com.interviewassistant.app.ui.theme.InterviewAssistantTheme
import com.interviewassistant.app.uistate.FocusPreset
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.app.viewmodels.MainViewModel
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mirror target of `InterviewAssistant.App/Program.cs` entry point (TRANSFER-MAP §2, layer: Platform).
 * Single-activity host for the Compose destination graph of Phase P4.
 *
 * P2 slice: OAuth redirect intents (the §8 D5 substitute for the Windows loopback listener) are routed
 * to the waiting sign-in flow through [OAuthRedirectBus], on cold start and on re-delivery alike.
 *
 * P4-C2: the activity obtains the composition root (and, through it, the main view model) from the
 * Application — the C#'s `new MainWindow()` reading the app-wide state.
 *
 * P4-E: the activity hosts the real Home shell ([HomeScreen]). It builds the Home view model with
 * the shell environment (dialog host, toast host, file pickers, clipboard, window shell) the same
 * way the C#'s HomeWindow is constructed by its owner, and resolves the initial route from the
 * launching intent before the first composition (DC-147).
 *
 * P4-F: the pre-interview page controller is real now — one instance serves both the Home view
 * model (through the seam) and the page composable (through [HomeScreen]).
 *
 * P4-G: the destination host is complete (DC-143): the activity owns a Live/Home destination
 * switch — the app opens on Live like the C# opens on its MainWindow, except a first run that
 * still owes the authorized-use acknowledgement or the first-run flow opens on Home, and a route
 * extra opens Home on that page. The Settings page controller is real (the DC-136 throwing
 * factory is gone); its ApplySettings callback is the one point the C#'s SettingsApplied event
 * fans out from, so the pre-interview re-check subscribes there (DC-152, DC-160). The Live view
 * model's notifications and authorized-use confirmation bind to the Live destination's own hosts.
 * (At P4-G the composition's capture/update providers were still the DC-91 throwing stand-ins;
 * P5 landed capture and P6 updates — DC-91 is fully resolved, DC-189.)
 *
 * P5: the activity owns the platform launchers and window acts the live-audio phase needs —
 * the RECORD_AUDIO / POST_NOTIFICATIONS permission flows and the MediaProjection consent for
 * the capture service (which asks only at a start, never at app start), the D2 hardware-keyboard
 * shortcut dispatch, the Interview Focus PiP handlers behind [FocusPlatform] (D9), and
 * keep-screen-on while a session is active (the D8 substitute's second half).
 *
 * P6: the activity hosts the billing & account-completion platform acts — it installs the
 * Custom Tabs launcher behind `CloudService.openExternal` (the Paddle checkout/portal path,
 * DC-186), starts the Play Billing coordinator and provides its purchase-flow activity
 * (OD-1; the launch entry is [launchPlayBillingUpgrade], DC-187), and owns the D12
 * battery-optimisation exemption prompt, asked once when a session first becomes active
 * (DC-188). The composition's update provider is real now (DC-189), so the lazy view-model
 * construction no longer throws.
 */
class MainActivity : ComponentActivity() {

    /** The two destinations of the single activity (DC-143). */
    private enum class Destination { Live, Home }

    /** The composition root built by [InterviewAssistantApplication.onCreate]. */
    val composition: AppComposition
        get() = (application as InterviewAssistantApplication).composition

    /** The main view model from the composition (lazy — see [AppComposition]). */
    val viewModel: MainViewModel by lazy { composition.mainViewModel }

    // The Home shell's environment: one dialog host and one toast host for the window, the focus
    // registry the view model's focus requests route through, and the file-picker bridge.
    private val homeDialogs = DialogController()
    private val homeToasts = ToastController()
    private val focusRegistry = FocusRegistry()
    private val fileBridge = HomeFileBridge()

    // The Live destination's own hosts (MainWindow's Toasts + DialogHost in the C#).
    private val liveDialogs = DialogController()
    private val liveToasts = ToastController()

    private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The shown destination; snapshot state so the shell callbacks below recompose the content. */
    private var destination by mutableStateOf(Destination.Live)

    private val pickDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileBridge.deliverPick(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileBridge.deliverSave(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    // P5 launchers: the capture service asks for these only at a capture start (never at app
    // start); the activity owns the system dialogs and routes the verdicts back.
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        composition.audioCaptureService.onAudioPermissionResult(granted)
        maybeRequestNotificationsPermission()
    }

    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        composition.audioCaptureService.deliverProjectionResult(result.resultCode, result.data)
    }

    private val homeViewModelDelegate = lazy { buildHomeViewModel() }

    /** The Home view model, built with this activity's shell environment on first use. */
    val homeViewModel: HomeViewModel by homeViewModelDelegate

    /**
     * The window shell shared by both destinations: closing Home (or starting the interview)
     * returns to the already-open Live destination (DC-143), and its file opener serves the Live
     * More menu's Last-report row as well.
     */
    private val homeShell: ShellHomeShell by lazy {
        ShellHomeShell(
            context = this,
            toasts = homeToasts,
            onCloseHome = { destination = Destination.Live },
            onShowLive = { destination = Destination.Live },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OAuthRedirectBus.deliver(intent)
        fileBridge.launchPick = { mimeTypes -> launchPick(mimeTypes) }
        fileBridge.launchSave = { fileName, mimeType -> launchSave(fileName, mimeType) }
        // The C#'s startup: the app opens on the Live window, unless onboarding is still owed
        // (authorized use not acknowledged, or the first run not completed) or a Home route was
        // delivered with the intent — then Home opens first (MainWindow.OnLoaded's OpenHome).
        val settings = composition.mainViewModel.settings
        destination = when {
            intent?.getStringExtra(EXTRA_HOME_ROUTE) != null -> Destination.Home
            !settings.authorizedUseAcknowledged || !settings.firstRunCompleted -> Destination.Home
            else -> Destination.Live
        }
        installLiveAudioWiring()
        installBillingWiring()
        if (intent?.getBooleanExtra(EXTRA_FOCUS_MANUAL, false) == true) {
            destination = Destination.Live
            mainHandler.post { LiveFocusRequests.requestManualInput() }
        }
        setContent {
            InterviewAssistantTheme {
                when (destination) {
                    Destination.Home -> HomeScreen(
                        homeViewModel,
                        homeDialogs,
                        homeToasts,
                        focusRegistry,
                        preInterviewController,
                        settingsController,
                    )
                    Destination.Live -> LiveScreen(
                        vm = viewModel,
                        dialogs = liveDialogs,
                        toasts = liveToasts,
                        onOpenHome = { route -> openHome(route) },
                        onOpenFile = homeShell::openFile,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OAuthRedirectBus.deliver(intent)
        // A route delivered to the already-running activity (DC-147): show Home and navigate it.
        val route = intent.getStringExtra(EXTRA_HOME_ROUTE)
        if (route != null) {
            destination = Destination.Home
            if (homeViewModelDelegate.isInitialized()) {
                lifecycleScope.launch { homeViewModel.navigateAsync(HomeRoute.parse(route)) }
            }
        }
        // A ManualInput shortcut that had to bring the activity forward (D2): land on Live and
        // focus the manual question field once it is composed.
        if (intent.getBooleanExtra(EXTRA_FOCUS_MANUAL, false)) {
            destination = Destination.Live
            mainHandler.post { LiveFocusRequests.requestManualInput() }
        }
    }

    override fun onDestroy() {
        ShortcutService.windowHandler = null
        FocusPlatform.applyHandler = null
        FocusPlatform.restoreHandler = null
        composition.playBillingClient.activityProvider = null
        super.onDestroy()
    }

    /**
     * Hardware-keyboard shortcuts (D2): a key-down from a real keyboard device is matched
     * against the stored combinations; a match is consumed. Soft-keyboard and remote-control
     * events (no keyboard device, or a non-keyboard type) never reach the matcher.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val device = event.device
            if (device != null && device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE &&
                ShortcutService.onKeyEvent(composition.settings, event.metaState, event.keyCode)
            ) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Leaving PiP is the restore (D9): the full Live screen returns at its prior size, so the
     * Focus restore record the Live screen reads is cleared here.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) FocusRestoreRecord.clear(composition.settings)
    }

    /**
     * The P5 wiring this activity owns: the capture service's permission/consent launchers, the
     * Interview Focus PiP handlers, the D2 window-action handler, and keep-screen-on tracking
     * the session state (D8).
     */
    private fun installLiveAudioWiring() {
        val capture = composition.audioCaptureService
        capture.audioPermissionRequester = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
        capture.projectionConsentRequester = { launchProjectionConsent() }
        FocusPlatform.applyHandler = { preset -> applyInterviewFocus(preset) }
        FocusPlatform.restoreHandler = { restoreInterviewFocus() }
        ShortcutService.windowHandler = { action -> onShortcutWindowAction(action) }
        viewModel.addPropertyChangedListener { name ->
            if (name == "isListening" || name == "isPaused") {
                mainHandler.post {
                    updateKeepScreenOn()
                    // D12: a session starting is the honest point to ask for the battery
                    // exemption (once per process, only when missing — BatteryExemption decides).
                    if (viewModel.sessionActive) BatteryExemption.requestIfNeeded()
                }
            }
        }
        updateKeepScreenOn()
    }

    /**
     * The P6 wiring this activity owns: the Custom Tabs launcher behind every gated external
     * open (Paddle checkout/portal, legal pages — the gate itself stays in CloudService), the
     * Play Billing coordinator's start (connect + restore + product query) and its purchase
     * flow's activity, and the battery-exemption platform halves (probe + system prompt).
     */
    private fun installBillingWiring() {
        CloudService.urlLauncher = { url -> CustomTabsUrlLauncher.open(this, url) }
        composition.playBillingClient.activityProvider = { this }
        lifecycleScope.launch { composition.playBilling.start() }
        val powerManager = getSystemService(PowerManager::class.java)
        BatteryExemption.probe = { powerManager.isIgnoringBatteryOptimizations(packageName) }
        BatteryExemption.requester = {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /**
     * The Account Upgrade action's Play Billing entry (OD-1): launches the Pro purchase flow
     * through the composition's coordinator. Hosted here because the flow needs this
     * activity; the account layer's verified dispatch (Paddle + poll) is unchanged in P6 —
     * see P6-PORT-NOTES DC-187 for the boundary and the reroute that lands with the
     * server-side Play bridge.
     */
    fun launchPlayBillingUpgrade(): Boolean = composition.playBilling.launchPurchase()

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    /** POST_NOTIFICATIONS rides along with the first capture start (API 33+): the FGS notification must be visible. */
    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** The D2 window actions: ToggleWindow flips the destination; ManualInput lands on Live's field. */
    private fun onShortcutWindowAction(action: ShortcutAction) {
        when (action) {
            ShortcutAction.ToggleWindow -> {
                destination = if (destination == Destination.Live) Destination.Home else Destination.Live
            }
            ShortcutAction.ManualInput -> {
                destination = Destination.Live
                mainHandler.post { LiveFocusRequests.requestManualInput() }
            }
            else -> Unit
        }
    }

    /**
     * Interview Focus (D9): with a session active, record the activity's actual pre-PiP state
     * and enter PiP at the live aspect ratio. Without a session there is nothing to present —
     * the dialog has already persisted the preset, exactly as far as the platform act goes.
     */
    private fun applyInterviewFocus(preset: FocusPreset) {
        if (!viewModel.sessionActive) return
        val decor = window.decorView
        val density = resources.displayMetrics.density
        if (decor.width > 0 && decor.height > 0) {
            FocusRestoreRecord.write(
                composition.settings,
                left = 0.0,
                top = 0.0,
                width = decor.width / density.toDouble(),
                height = decor.height / density.toDouble(),
            )
        }
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(FocusPlatform.pipAspectRatio())
                .build(),
        )
    }

    /** Restore from Focus: expand PiP if it is showing (the system's own restore), and clear the record. */
    private fun restoreInterviewFocus() {
        if (isInPictureInPictureMode) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        FocusRestoreRecord.clear(composition.settings)
    }

    /** Keep the screen on while a session runs (D8); the flag follows isListening/isPaused. */
    private fun updateKeepScreenOn() {
        if (viewModel.sessionActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Live's "open Home" (a page route, or Home itself for null): switch, then navigate. */
    private fun openHome(route: String?) {
        destination = Destination.Home
        if (route != null) {
            lifecycleScope.launch { homeViewModel.navigateAsync(HomeRoute.parse(route)) }
        }
    }

    private fun launchPick(mimeTypes: Array<String>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (mimeTypes.size == 1) mimeTypes[0] else "*/*")
        if (mimeTypes.size != 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        pickDocument.launch(intent)
    }

    private fun launchSave(fileName: String, mimeType: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, fileName)
        createDocument.launch(intent)
    }

    /**
     * The pre-interview page controller (P4-F): built once from the composition's main view model,
     * handed to the Home view model through its seam and to the shell for the page composable. Its
     * probes run on the Home scope; its store renders marshal to the main thread, like the C#
     * page's Dispatcher marshaling.
     */
    val preInterviewController: PreInterviewPageControllerImpl by lazy {
        PreInterviewPageControllerImpl(
            vm = composition.mainViewModel,
            scope = homeScope,
            postToUi = { action -> mainHandler.post { action() } },
        )
    }

    /**
     * The Settings page controller (P4-G, replacing the DC-136 throwing factory): the editing
     * model over the app settings, built on the composition's main view model and Home's dialog
     * seam. Its [SettingsPageControllerImpl] ApplySettings callback mirrors the C#'s
     * `MainWindow.ApplySettings` — appearance/audio/engine follow the saved settings, and the
     * pre-interview controller's settings-applied re-check hangs off the same point, because the
     * C# raises SettingsApplied for a save, a reset and a key removal alike (DC-152, DC-160).
     * Hotkey registration inside ApplySettings is the P5 platform service's half (DC-158).
     */
    val settingsController: SettingsPageControllerImpl by lazy {
        val vm = composition.mainViewModel
        SettingsPageControllerImpl(
            vm = vm,
            dialogs = ShellHomeDialogs(homeDialogs),
            scope = homeScope,
            postToUi = { action -> mainHandler.post { action() } },
            applySettings = { engineChanged, modelChanged ->
                vm.refreshLanguage()
                vm.restartAudio()
                if (engineChanged) {
                    if (vm.sessionActive) {
                        vm.applyLiveSettings()
                        if (modelChanged) vm.rebuildAfterSession()
                    } else {
                        vm.buildEngine()
                    }
                    vm.invalidateReadiness()
                }
                preInterviewController.onSettingsApplied()
            },
        )
    }

    private fun buildHomeViewModel(): HomeViewModel {
        val home = HomeViewModel(
            vm = composition.mainViewModel,
            scope = homeScope,
            postToUi = { action -> mainHandler.post { action() } },
            dialogs = ShellHomeDialogs(homeDialogs),
            files = ShellHomeFiles(this, fileBridge, homeScope),
            clipboard = ShellHomeClipboard(this),
            shell = homeShell,
            settingsPage = settingsController,
            preInterview = preInterviewController,
            initial = HomeRoute.parse(intent?.getStringExtra(EXTRA_HOME_ROUTE)),
            osVersion = { Build.VERSION.RELEASE ?: "" },
        )
        // The shell wiring deferred from P4-D: the view model's notifications become toasts on
        // Home's toast host, and its focus requests route to the composed pages. Both listeners
        // marshal to the main thread — the view model notifies from its own threads.
        home.notifyListeners += { text, kind, detail ->
            mainHandler.post { homeToasts.show(text, kind, detail, nowMs = System.currentTimeMillis()) }
        }
        home.focusListeners += { target ->
            mainHandler.post { focusRegistry.request(target) }
        }
        // The Live view model's own wiring (MainWindow's constructor in the C#): its notifications
        // toast on the Live destination's host, and its authorized-use confirmation is the Live
        // dialog the C# shows from the Live window.
        val mainVm = composition.mainViewModel
        mainVm.notifyListeners += { text, kind, detail ->
            mainHandler.post { liveToasts.show(text, kind, detail, nowMs = System.currentTimeMillis()) }
        }
        mainVm.confirmAuthorizedUseAsync = { confirmAuthorizedUse(mainVm) }
        return home
    }

    /**
     * The C#'s `ConfirmAuthorizedUseAsync`: the one-time authorized-use acknowledgement, asked on
     * the Live destination's dialog host; Continue records the acknowledgement in the settings.
     */
    private suspend fun confirmAuthorizedUse(vm: MainViewModel): Boolean {
        if (vm.settings.authorizedUseAcknowledged) return true
        val confirmed = AppDialog.confirm(
            controller = liveDialogs,
            message = Loc.t("authorized_use") + "\n\n" + Loc.t("start_step1_hint"),
            confirmText = Loc.t("dlg_continue"),
            title = Loc.t("start_step1"),
        )
        if (confirmed) {
            vm.settings.authorizedUseAcknowledged = true
            vm.settings.save()
        }
        return confirmed
    }

    companion object {
        /** Intent extra carrying a Home route ("settings/audio") for the shell to open (DC-147). */
        const val EXTRA_HOME_ROUTE: String = "com.interviewassistant.app.extra.HOME_ROUTE"

        /** Intent extra: land on Live and focus the manual question field (the D2 ManualInput shortcut). */
        const val EXTRA_FOCUS_MANUAL: String = "com.interviewassistant.app.extra.FOCUS_MANUAL"
    }
}
