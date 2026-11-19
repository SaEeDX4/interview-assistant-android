package com.interviewassistant.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.interviewassistant.app.platform.AndroidNetworkAvailability
import com.interviewassistant.app.platform.AndroidProcessStats
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
 * The view model is built lazily: its capture and update dependencies are the P5/P6 platform services,
 * supplied here by providers that throw a clear [IllegalStateException] until those phases land
 * (P4-PORT-NOTES DC-91). Nothing else waits on them: settings, cloud and the workspace factory are fully
 * live, and the Live UI that first touches the view model arrives in P4-G, after P5/P6.
 */
class AppComposition(context: Context) {
    private val appContext: Context = context.applicationContext

    /** The C#'s `AppSettings.Load()` (first statement of the MainViewModel constructor). */
    val settings: AppSettings = AppSettings.load()

    /** The C#'s `new CloudService(Settings)`. */
    val cloud: CloudService = CloudService(settings)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private fun captureService(): IAudioCaptureService =
        throw IllegalStateException(
            "Audio capture is not available yet: the IAudioCaptureService platform implementation lands in P5.",
        )

    private fun updateService(): IUpdateService =
        throw IllegalStateException(
            "Updates are not available yet: the IUpdateService platform implementation lands in P6.",
        )
}
