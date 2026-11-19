package com.interviewassistant.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.interviewassistant.app.platform.AndroidNetworkAvailability
import com.interviewassistant.app.platform.AndroidProcessStats
import com.interviewassistant.app.platform.ShortcutAction
import com.interviewassistant.app.platform.ShortcutService
import com.interviewassistant.app.platform.audio.AudioCaptureService
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.services.IAudioCaptureService
import com.interviewassistant.app.services.IUpdateService
import com.interviewassistant.app.viewmodels.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The composition root (the C#'s `App.OnStartup` + `MainViewModel` constructor wiring, TRANSFER-MAP §2):
 * builds the app-wide objects in the C# startup order — settings, then the cloud facade, then the main
 * view model — with the platform seams injected.
 *
 * The view model is built lazily. Its capture dependency is the real P5 platform service
 * ([audioCaptureService]); its update dependency is the P6 platform service, supplied until then by
 * a provider that throws a clear [IllegalStateException] (P4-PORT-NOTES DC-91) — nothing fakes it.
 * Settings, cloud and the workspace factory are fully live.
 *
 * The composition also installs the process-wide half of the D2 shortcut surface
 * ([ShortcutService]): the view-model actions execute the main view model's commands (the
 * foreground-service notification fires them with no activity alive), and a window action with
 * no activity installed brings [MainActivity] forward instead.
 */
class AppComposition(context: Context) {
    private val appContext: Context = context.applicationContext

    /** The C#'s `AppSettings.Load()` (first statement of the MainViewModel constructor). */
    val settings: AppSettings = AppSettings.load()

    /** The C#'s `new CloudService(Settings)`. */
    val cloud: CloudService = CloudService(settings)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The P5 audio capture service (the C#'s `new AudioCaptureService()`), built once. */
    val audioCaptureService: AudioCaptureService by lazy { AudioCaptureService(appContext) }

    /** The main view model; first access constructs it (see the class doc for why it is lazy). */
    val mainViewModel: MainViewModel by lazy {
        MainViewModel(
            settings = settings,
            cloud = cloud,
            updates = updateService(),
            capture = captureService(),
            scope = scope,
            postToUi = { action -> mainHandler.post { action() } },
            network = AndroidNetworkAvailability(appContext),
            processStats = AndroidProcessStats(),
        )
    }

    init {
        ShortcutService.commandHandler = { action ->
            val vm = mainViewModel
            when (action) {
                ShortcutAction.StartPause -> vm.startPauseCommand.execute()
                ShortcutAction.Stop -> vm.stopCommand.execute()
                ShortcutAction.Coach -> vm.toggleCoachCommand.execute()
                ShortcutAction.ResetAdaptive -> vm.resetAdaptiveCommand.execute()
                else -> Unit // window actions are not the command handler's tier
            }
        }
        ShortcutService.activityStarter = { action ->
            val intent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action == ShortcutAction.ManualInput) {
                intent.putExtra(MainActivity.EXTRA_FOCUS_MANUAL, true)
            }
            appContext.startActivity(intent)
        }
    }

    private fun captureService(): IAudioCaptureService = audioCaptureService

    private fun updateService(): IUpdateService =
        throw IllegalStateException(
            "Updates are not available yet: the IUpdateService platform implementation lands in P6.",
        )
}
