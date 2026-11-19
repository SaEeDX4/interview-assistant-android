package com.interviewassistant.app.platform.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.interviewassistant.app.platform.CaptureForegroundControl
import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.IAudioCaptureService
import com.interviewassistant.app.services.PlaybackDevice
import com.interviewassistant.core.languages.Loc

/**
 * The Android `AudioCaptureService` (TRANSFER-MAP §2 row 23: `platform/audio/AudioCaptureService.kt`;
 * §8 D1/D12). Behaviour — statuses, silence injection, restart/backoff, device following — is
 * [CaptureCore]'s, a member-for-member port of the C# service; this class supplies the platform
 * facts: the capture routes of [CaptureRoutes] from [AudioManager], the [AudioRecord] sources,
 * the runtime-permission gate, the MediaProjection consent token (OD-4), and the foreground
 * service that keeps capture alive (D12).
 *
 * Device model (DC-169): the C# captures a *playback* endpoint and never opens the microphone;
 * Android's primary mode is the reverse — the phone's microphone hears the interviewer from the
 * meeting device (D1) — and the picker therefore lists capture routes. The same-device source,
 * AudioPlaybackCapture, is the picker's last entry; starting it without a consent token starts
 * the microphone fallback on the default route and asks for consent, and granting consent
 * switches the running capture to playback capture. Denial leaves the microphone running: the
 * device name on screen always names the source actually capturing, never the one hoped for.
 */
class AudioCaptureService(context: Context) : IAudioCaptureService {
    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---- seams MainActivity installs (permission + consent launchers live in the activity) ----

    /** Called when a start needs RECORD_AUDIO and it is not granted yet. */
    var audioPermissionRequester: (() -> Unit)? = null

    /** Called when playback capture is chosen but no consent token is held. */
    var projectionConsentRequester: (() -> Unit)? = null

    /** Test/embedding seam for the permission check; production reads the real grant state. */
    internal var hasAudioPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private var pendingDeviceId: String? = null
    private var pendingStart: Boolean = false

    // ---- MediaProjection token (one consent = one projection, kept until the system stops it) ----

    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            // The running playback source ends itself when its projection dies; the core's
            // restart path then rebuilds it — and the factory, tokenless now, falls back to the
            // microphone route rather than failing forever (see routingFactory).
        }
    }

    // ---- core + routing ----

    private val micFactory = MicrophoneSourceFactory(
        audioManager = audioManager,
        routes = { currentRouteInfos() },
        deviceFor = { kind -> deviceInfoFor(kind) },
        defaultDeviceName = { defaultRouteName() },
    )
    private val playbackFactory = PlaybackSourceFactory(
        projection = { projection },
        deviceName = { Loc.t("toast_listening") },
    )

    /**
     * Routes a resolved source to its factory. Playback capture whose start keeps failing (or
     * whose token vanished) falls back to the microphone on the default route after the first
     * failure, so a blocked/unavailable same-device capture degrades to the D1 primary mode
     * instead of an endless DEVICE ERROR loop; an explicit new `start` resets the allowance.
     */
    private val routingFactory = object : CaptureSourceFactory {
        private var playbackFailures: Int = 0

        fun resetFallback() {
            playbackFailures = 0
        }

        override fun create(resolved: CaptureRoutes.Resolved): CaptureSource {
            if (resolved is CaptureRoutes.Resolved.Playback) {
                if (projection != null && playbackFailures == 0) {
                    val inner = playbackFactory.create(resolved)
                    return object : CaptureSource {
                        override val format: com.interviewassistant.core.audio.SourceFormat get() = inner.format
                        override val deviceName: String get() = inner.deviceName
                        override fun start(onData: (ByteArray) -> Unit, onStopped: (Exception?) -> Unit) {
                            try {
                                inner.start(onData, onStopped)
                            } catch (ex: SourceStartException) {
                                playbackFailures++
                                throw ex
                            }
                        }

                        override fun stop() = inner.stop()
                    }
                }
                // No token (consent pending/denied) or playback capture already failed once:
                // the microphone on the default route is the running fallback.
                return micFactory.create(CaptureRoutes.Resolved.Default)
            }
            return micFactory.create(resolved)
        }
    }

    private val core = CaptureCore(
        factory = routingFactory,
        resolver = { id -> CaptureRoutes.resolve(id, presentKinds()) },
        scheduler = HandlerCaptureScheduler(),
        nowMs = { SystemClock.elapsedRealtime() },
        logInfo = { message -> AppLog.info(message) },
        logWarn = { message -> AppLog.warn(message) },
        logError = { message, ex -> AppLog.error(message, ex) },
    )

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged(addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged(removedDevices)
        }
    }
    private var deviceCallbackRegistered: Boolean = false

    // ---- IAudioCaptureService: everything delegates to the core ----

    override val frameReadyListeners: MutableList<(ByteArray, Double) -> Unit> get() = core.frameReadyListeners
    override val statusChangedListeners: MutableList<(String) -> Unit> get() = core.statusChangedListeners
    override val currentDeviceName: String get() = core.currentDeviceName
    override val status: String get() = core.status
    override val statusLabel: String get() = core.statusLabel
    override val sampleRate: Int get() = core.sampleRate
    override val channels: Int get() = core.channels
    override val encoding: String get() = core.encoding
    override val lastPeakDb: Double get() = core.lastPeakDb
    override val lastRmsDb: Double get() = core.lastRmsDb
    override val framesWithSignal: Long get() = core.framesWithSignal
    override val isRunning: Boolean get() = core.isRunning

    override fun listDevices(): List<PlaybackDevice> = CaptureRoutes.buildList(currentRouteInfos())

    override fun start(deviceId: String?) {
        routingFactory.resetFallback()
        if (!hasAudioPermission()) {
            // The honest point for the permission ask: the user pressed Start (or a preview
            // began). The start pends until the verdict arrives — never a dead end.
            pendingDeviceId = deviceId
            pendingStart = true
            val requester = audioPermissionRequester
            if (requester != null) {
                requester()
            } else {
                pendingStart = false
                core.noteStartBlockedByPermission()
            }
            return
        }
        beginStart(deviceId)
    }

    /** MainActivity's RECORD_AUDIO verdict: a grant runs the pended start; a denial surfaces it. */
    fun onAudioPermissionResult(granted: Boolean) {
        if (!pendingStart) return
        pendingStart = false
        if (granted) {
            beginStart(pendingDeviceId)
        } else {
            core.noteStartBlockedByPermission()
        }
        pendingDeviceId = null
    }

    /**
     * MainActivity's MediaProjection consent verdict. A grant creates the one projection (the
     * foreground service first gains the mediaProjection type, as Android 14 requires the type
     * to be running before the projection is used) and switches a running playback choice to
     * playback capture. A denial changes nothing: the microphone fallback is already the truth
     * on screen.
     */
    fun deliverProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return
        CaptureForegroundControl.start(appContext, needsProjection = true)
        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = try {
            manager.getMediaProjection(resultCode, data)
        } catch (ex: Exception) {
            AppLog.error("Could not create the media projection", ex)
            return
        }
        projection?.stop()
        projection = created
        created.registerCallback(projectionCallback, null)
        routingFactory.resetFallback()
        if (core.isRunning && core.requestedDeviceId == CaptureRoutes.ID_PLAYBACK) {
            core.start(CaptureRoutes.ID_PLAYBACK)
        }
    }

    override fun stop() {
        pendingStart = false
        core.stop()
        CaptureForegroundControl.stop(appContext)
        unregisterDeviceCallback()
    }

    override fun dispose() {
        stop()
        core.dispose()
        projection?.stop()
        projection = null
    }

    // ---- internals ----

    private fun beginStart(deviceId: String?) {
        val resolved = CaptureRoutes.resolve(deviceId, presentKinds())
        CaptureForegroundControl.start(
            appContext,
            needsProjection = resolved is CaptureRoutes.Resolved.Playback && projection != null,
        )
        registerDeviceCallback()
        core.start(deviceId)
        if (resolved is CaptureRoutes.Resolved.Playback && projection == null) {
            // Mode 2 chosen without a token: the microphone fallback is already capturing;
            // ask for consent — granting it switches the source (deliverProjectionResult).
            projectionConsentRequester?.invoke()
        }
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) return
        deviceCallbackRegistered = true
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) return
        deviceCallbackRegistered = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /**
     * The `IMMNotificationClient` halves, Android-shaped: any input-route change can move the
     * system default (the core restarts a default-following capture), and a route that (re)appears
     * revives a capture waiting on it (the core's device-available path). Removals need no direct
     * action: the running source ends itself, and the core's RECONNECTING path takes over.
     */
    private fun onDevicesChanged(changed: Array<out AudioDeviceInfo>) {
        if (!core.isRunning) return
        if (core.requestedDeviceId == null) core.onDefaultDeviceChanged()
        for (device in changed) {
            val kind = kindOf(device) ?: continue
            if (device.isSource && presentKinds().contains(kind)) {
                core.onDeviceBecameAvailable(kind.id)
            }
        }
    }

    private fun presentKinds(): Set<CaptureRoutes.RouteKind> =
        currentRouteInfos().map { it.kind }.toSet()

    private fun currentRouteInfos(): List<CaptureRoutes.RouteInfo> {
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (ex: Exception) {
            AppLog.error("Device enumeration failed", ex)
            return emptyList()
        }
        val infos = mutableListOf<CaptureRoutes.RouteInfo>()
        for (kind in CaptureRoutes.RouteKind.entries) {
            val device = devices.firstOrNull { it.isSource && kindOf(it) == kind } ?: continue
            infos += CaptureRoutes.RouteInfo(kind, routeName(device, kind))
        }
        return infos
    }

    private fun deviceInfoFor(kind: CaptureRoutes.RouteKind): AudioDeviceInfo? = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && kindOf(it) == kind }
    } catch (ex: Exception) {
        null
    }

    /**
     * The name of the route the system default currently lands on. API 31+ names the
     * communication device when one is set; below that (and when none is set) the built-in
     * microphone is the default input on phones — the name shown is that route's (DC-173).
     */
    private fun defaultRouteName(): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val comm = try {
                audioManager.communicationDevice
            } catch (ex: Exception) {
                null
            }
            if (comm != null && comm.isSource) {
                val kind = kindOf(comm)
                if (kind != null) return routeName(comm, kind)
            }
        }
        val infos = currentRouteInfos()
        return infos.firstOrNull { it.kind == CaptureRoutes.RouteKind.Builtin }?.name
            ?: infos.firstOrNull()?.name
            ?: ""
    }

    private fun routeName(device: AudioDeviceInfo, kind: CaptureRoutes.RouteKind): String {
        val product = device.productName?.toString().orEmpty()
        if (product.isNotBlank()) return product
        // The OS reported no name (rare): fall back to the kind's plain English platform name
        // rather than showing an empty picker row (DC-173).
        return when (kind) {
            CaptureRoutes.RouteKind.Builtin -> "Built-in Microphone"
            CaptureRoutes.RouteKind.Wired -> "Wired Headset"
            CaptureRoutes.RouteKind.Bluetooth -> "Bluetooth Headset"
        }
    }

    private fun kindOf(device: AudioDeviceInfo): CaptureRoutes.RouteKind? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> CaptureRoutes.RouteKind.Builtin
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> CaptureRoutes.RouteKind.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> CaptureRoutes.RouteKind.Bluetooth
        else -> null
    }
}
