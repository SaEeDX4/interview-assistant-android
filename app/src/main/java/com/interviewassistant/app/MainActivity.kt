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
import androidx.lifecycle.lifecycleScope
import com.interviewassistant.app.platform.OAuthRedirectBus
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.home.FocusRegistry
import com.interviewassistant.app.ui.home.HomeFileBridge
import com.interviewassistant.app.ui.home.HomeScreen
import com.interviewassistant.app.ui.home.ShellHomeClipboard
import com.interviewassistant.app.ui.home.ShellHomeDialogs
import com.interviewassistant.app.ui.home.ShellHomeFiles
import com.interviewassistant.app.ui.home.ShellHomeShell
import com.interviewassistant.app.ui.theme.InterviewAssistantTheme
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.viewmodels.HomeViewModel
import com.interviewassistant.app.viewmodels.MainViewModel
import com.interviewassistant.app.viewmodels.PreInterviewPageController
import com.interviewassistant.app.viewmodels.SettingsPageController
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
 * launching intent before the first composition (DC-147). Two construction inputs still land in
 * later packages — the hosted Settings (P4-G) and PreInterview (P4-F) page controllers — and the
 * composition's capture/update providers land in P5/P6; until then the lazy construction fails
 * with their explicit IllegalStateException instead of any fake behavior (DC-91, DC-136).
 */
class MainActivity : ComponentActivity() {

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

    private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileBridge.deliverPick(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileBridge.deliverSave(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    private val homeViewModelDelegate = lazy { buildHomeViewModel() }

    /** The Home view model, built with this activity's shell environment on first use. */
    val homeViewModel: HomeViewModel by homeViewModelDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OAuthRedirectBus.deliver(intent)
        fileBridge.launchPick = { mimeTypes -> launchPick(mimeTypes) }
        fileBridge.launchSave = { fileName, mimeType -> launchSave(fileName, mimeType) }
        setContent {
            InterviewAssistantTheme {
                HomeScreen(homeViewModel, homeDialogs, homeToasts, focusRegistry)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OAuthRedirectBus.deliver(intent)
        // A route delivered to the already-running activity (DC-147): navigate the shown Home.
        val route = intent.getStringExtra(EXTRA_HOME_ROUTE)
        if (route != null && homeViewModelDelegate.isInitialized()) {
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

    private fun buildHomeViewModel(): HomeViewModel {
        val home = HomeViewModel(
            vm = composition.mainViewModel,
            scope = homeScope,
            postToUi = { action -> mainHandler.post { action() } },
            dialogs = ShellHomeDialogs(homeDialogs),
            files = ShellHomeFiles(this, fileBridge, homeScope),
            clipboard = ShellHomeClipboard(this),
            shell = ShellHomeShell(
                context = this,
                toasts = homeToasts,
                // Leaving Home (close, or Start interview handing over to Live): in P4-E the
                // activity hosts only the Home destination, so leaving it finishes the activity;
                // P4-G's destination host replaces this with the in-activity switch (DC-143).
                onCloseHome = { finish() },
                onShowLive = { finish() },
            ),
            settingsPage = unavailableSettingsController(),
            preInterview = unavailablePreInterviewController(),
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
        return home
    }

    /** DC-136: the Settings page controller is built by P4-G; until then its absence is explicit. */
    private fun unavailableSettingsController(): SettingsPageController =
        throw IllegalStateException("The Settings page controller lands in P4-G (P4-PORT-NOTES DC-136).")

    /** DC-136: the pre-interview page controller is built by P4-F; until then its absence is explicit. */
    private fun unavailablePreInterviewController(): PreInterviewPageController =
        throw IllegalStateException("The Pre-interview page controller lands in P4-F (P4-PORT-NOTES DC-136).")

    companion object {
        /** Intent extra carrying a Home route ("settings/audio") for the shell to open (DC-147). */
        const val EXTRA_HOME_ROUTE: String = "com.interviewassistant.app.extra.HOME_ROUTE"
    }
}
