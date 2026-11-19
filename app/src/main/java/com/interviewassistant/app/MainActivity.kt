package com.interviewassistant.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.interviewassistant.app.platform.OAuthRedirectBus
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
 * The composition's capture/update providers still land in P5/P6; until then the lazy
 * construction fails with their explicit IllegalStateException instead of any fake behavior
 * (DC-91).
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
    }
}
