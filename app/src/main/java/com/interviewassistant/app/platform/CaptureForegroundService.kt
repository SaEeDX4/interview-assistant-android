package com.interviewassistant.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.interviewassistant.app.R
import com.interviewassistant.app.services.Branding
import com.interviewassistant.core.languages.Loc

/**
 * The foreground service that keeps capture + the live session alive while the interview runs
 * in another app (TRANSFER-MAP §6.2 audio row, §8 D2/D12). It owns no capture state — capture
 * lives in `platform/audio/AudioCaptureService` in the same process — it only carries the
 * persistent notification and its actions, which are the D2 substitute surface for the C#'s
 * global hotkeys: Pause/Resume, Coach and Stop, dispatched through [ShortcutService] to the
 * very commands the Live screen's buttons execute.
 *
 * The service runs with the microphone type from the first capture start; while
 * AudioPlaybackCapture is in play the mediaProjection type is added (Android 14 requires the
 * typed service to be running before a projection is used).
 */
class CaptureForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, Branding.productName, NotificationManager.IMPORTANCE_LOW).apply {
                    description = Loc.t("toast_listening")
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHORTCUT -> {
                val action = intent.getStringExtra(EXTRA_SHORTCUT_ACTION)
                    ?.let { name -> ShortcutAction.entries.firstOrNull { it.name == name } }
                if (action != null) ShortcutService.dispatch(action)
                // fall through: a shortcut tap also re-asserts the foreground state
            }
        }
        val needsProjection = intent?.getBooleanExtra(EXTRA_NEEDS_PROJECTION, false) ?: false
        val paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (needsProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(paused), types)
        return START_STICKY
    }

    private fun buildNotification(paused: Boolean): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.interviewassistant.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glyph_play)
            .setContentTitle(Branding.productName)
            .setContentText(Loc.t(if (paused) "pause" else "toast_listening"))
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder.addAction(
            R.drawable.ic_glyph_pause,
            Loc.t(if (paused) "resume" else "pause"),
            shortcutIntent(ShortcutAction.StartPause),
        )
        builder.addAction(
            R.drawable.ic_glyph_compact,
            Loc.t("coach"),
            shortcutIntent(ShortcutAction.Coach),
        )
        builder.addAction(
            R.drawable.ic_glyph_stop,
            Loc.t("tt_stop"),
            shortcutIntent(ShortcutAction.Stop),
        )
        return builder.build()
    }

    private fun shortcutIntent(action: ShortcutAction): PendingIntent {
        val intent = Intent(this, CaptureForegroundService::class.java)
            .setAction(ACTION_SHORTCUT)
            .putExtra(EXTRA_SHORTCUT_ACTION, action.name)
        return PendingIntent.getService(
            this,
            action.ordinal + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID: String = "live_session"
        private const val NOTIFICATION_ID: Int = 1001
        internal const val ACTION_STOP_SERVICE: String = "com.interviewassistant.app.fgs.STOP"
        internal const val ACTION_SHORTCUT: String = "com.interviewassistant.app.fgs.SHORTCUT"
        internal const val EXTRA_SHORTCUT_ACTION: String = "com.interviewassistant.app.fgs.SHORTCUT_ACTION"
        internal const val EXTRA_NEEDS_PROJECTION: String = "com.interviewassistant.app.fgs.NEEDS_PROJECTION"
        internal const val EXTRA_PAUSED: String = "com.interviewassistant.app.fgs.PAUSED"
    }
}

/**
 * Starts/stops [CaptureForegroundService] in step with capture. Stopping is debounced on the
 * main thread: `MainViewModel.restartAudio` (a device switch, a settings save) stops and starts
 * capture back-to-back, and the notification must not flicker or the service churn for what is
 * one continuous session.
 */
object CaptureForegroundControl {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    fun start(context: Context, needsProjection: Boolean, paused: Boolean = false) {
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        val intent = Intent(context, CaptureForegroundService::class.java)
            .putExtra(CaptureForegroundService.EXTRA_NEEDS_PROJECTION, needsProjection)
            .putExtra(CaptureForegroundService.EXTRA_PAUSED, paused)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        val stop = Runnable {
            pendingStop = null
            context.startService(
                Intent(context, CaptureForegroundService::class.java)
                    .setAction(CaptureForegroundService.ACTION_STOP_SERVICE),
            )
        }
        pendingStop = stop
        mainHandler.postDelayed(stop, STOP_DEBOUNCE_MS)
    }

    /** Stops without debounce (app teardown paths). */
    fun stopNow(context: Context) {
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
        context.stopService(Intent(context, CaptureForegroundService::class.java))
    }

    private const val STOP_DEBOUNCE_MS: Long = 600
}
