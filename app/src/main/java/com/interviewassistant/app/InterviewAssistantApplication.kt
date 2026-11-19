package com.interviewassistant.app

import android.app.Application
import android.os.Build
import com.interviewassistant.app.platform.installLocTables
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.AppPaths
import com.interviewassistant.app.services.Branding
import com.interviewassistant.app.services.BuildInfo
import com.interviewassistant.app.services.extractBundledKnowledge

/**
 * Mirror of `InterviewAssistant.App/App.xaml.cs` (TRANSFER-MAP §2, layer: Platform): process startup.
 *
 * In the C#'s order: the global exception handlers are registered first (a live-interview tool must
 * never die silently — log, surface, continue), then branding is loaded, the start line is logged and
 * old logs are pruned, and the main window is created — here, the composition root is built instead;
 * the first window (MainActivity) reads it. The Velopack bootstrap of `Program.cs` (install/update
 * hooks before any UI) has no counterpart until the P6 update service lands.
 *
 * Android difference (P4-PORT-NOTES DC-98): an uncaught exception cannot be "handled and continued"
 * the way WPF's DispatcherUnhandledException can — the handler logs through [AppLog] and then delegates
 * to the previous handler, so the platform's crash behaviour is unchanged.
 */
class InterviewAssistantApplication : Application() {

    /** The composition root, built at startup in the C# order (settings → cloud → view model). */
    lateinit var composition: AppComposition
        private set

    override fun onCreate() {
        super.onCreate()
        installLocTables(this)
        AppPaths.install(this)
        installExceptionLogging()
        BuildInfo.install(this)
        extractBundledKnowledge(this)
        Branding.load(AppPaths.baseDirectory)
        AppLog.info("${Branding.productName} ${BuildInfo.summary} starting on Android ${Build.VERSION.RELEASE}")
        val pruned = AppLog.prune()
        if (pruned > 0) AppLog.info("Removed $pruned log file(s) older than 14 days")
        composition = AppComposition(this)
    }

    /**
     * The C#'s three handlers (DispatcherUnhandledException, UnobservedTaskException,
     * AppDomain.UnhandledException) as the one Android counterpart: log, then delegate.
     * Coroutine exceptions are not uncaught-thread exceptions; the view models' scopes use
     * SupervisorJob, so a failing child never reaches this handler either.
     */
    private fun installExceptionLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.error(
                    "Fatal exception on ${thread.name}",
                    throwable as? Exception ?: Exception(throwable),
                )
            } catch (ignored: Exception) {
                // Logging must never mask the original failure.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
