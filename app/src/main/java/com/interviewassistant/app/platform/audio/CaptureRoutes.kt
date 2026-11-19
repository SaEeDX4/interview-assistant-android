package com.interviewassistant.app.platform.audio

import com.interviewassistant.app.services.PlaybackDevice
import com.interviewassistant.core.languages.Loc

/**
 * The Android capture-route model behind `AudioCaptureService.ListDevices()` / `Start(deviceId)`
 * (TRANSFER-MAP §3.2 row 11, §8 D1; P5-PORT-NOTES DC-169).
 *
 * The C# lists the machine's *playback* (render) endpoints, because WASAPI loopback hears what the
 * headset plays. Android has no loopback over arbitrary render endpoints, so the picker lists the
 * device's *capture* routes instead — the phone-as-second-device sources of D1 — plus the one
 * same-device source, AudioPlaybackCapture (OD-4 Mode 2). The list's shape mirrors the C# exactly:
 * the first entry is the system default (id `null`, the shared `audio_system_default` string),
 * followed by one entry per route actually present, in a fixed order.
 *
 * Route ids are Android-private strings with an `android:` prefix: a settings.json written by the
 * Windows app carries a WASAPI endpoint GUID, which can never collide with these — and, exactly as
 * in the C#, an id that does not name a capture source is ignored in favour of the default (the C#
 * ignores a stored *capture*-device id the same way; an unknown render id cannot be produced by
 * either app's own picker).
 */
object CaptureRoutes {
    /** Stored id of the AudioPlaybackCapture source (OD-4 Mode 2: the interview runs on this phone). */
    const val ID_PLAYBACK: String = "android:playback"

    const val ID_BUILTIN: String = "android:builtin"
    const val ID_WIRED: String = "android:wired"
    const val ID_BLUETOOTH: String = "android:bluetooth"

    /** The capture routes, in picker order (after the system-default entry). */
    enum class RouteKind(val id: String) {
        Builtin(ID_BUILTIN),
        Wired(ID_WIRED),
        Bluetooth(ID_BLUETOOTH),
    }

    /** One route's presence and display name, as the platform layer reports it (pure input data). */
    data class RouteInfo(val kind: RouteKind, val name: String)

    /**
     * The picker list, mirroring `ListDevices()`: system default first, then each present route in
     * [RouteKind] order, then the playback-capture source (always offered: minSdk 29 guarantees
     * AudioPlaybackCapture exists; whether a given app permits capture is a runtime fact the
     * service surfaces honestly, never a reason to hide the entry).
     *
     * The playback entry's name borrows the `toast_listening` string ("Listening to system audio"):
     * the shared i18n asset — byte-identical with Windows, one key set for all 10 languages — has
     * no key naming this Android-only source, and adding one is a Windows-side lockstep change,
     * not an Android edit (DC-170).
     */
    fun buildList(routes: List<RouteInfo>): List<PlaybackDevice> {
        val list = mutableListOf(PlaybackDevice(null, Loc.t("audio_system_default")))
        for (kind in RouteKind.entries) {
            val info = routes.firstOrNull { it.kind == kind } ?: continue
            list += PlaybackDevice(kind.id, info.name)
        }
        list += PlaybackDevice(ID_PLAYBACK, Loc.t("toast_listening"))
        return list
    }

    /** What a stored/requested device id resolves to against the routes present right now. */
    sealed interface Resolved {
        /** Follow the system default input route (id `null`, or a foreign id the C# would ignore). */
        object Default : Resolved

        /** Capture from this specific present route. */
        data class Route(val kind: RouteKind) : Resolved

        /** Capture the phone's own playback via AudioPlaybackCapture (OD-4 Mode 2). */
        object Playback : Resolved

        /**
         * A known Android route id whose route is not present right now. The C#'s `GetDevice`
         * fails the same way for an unplugged device (HRESULT 0x80070490, "element not found"),
         * which the service reports as NO PLAYBACK DEVICE and retries with backoff until the
         * device returns.
         */
        data class Absent(val id: String) : Resolved
    }

    fun resolve(deviceId: String?, present: Set<RouteKind>): Resolved = when {
        deviceId == null -> Resolved.Default
        deviceId == ID_PLAYBACK -> Resolved.Playback
        else -> {
            val kind = RouteKind.entries.firstOrNull { it.id == deviceId }
            when {
                kind == null -> Resolved.Default // foreign id (e.g. a Windows endpoint GUID): ignored, as in the C#
                kind in present -> Resolved.Route(kind)
                else -> Resolved.Absent(deviceId)
            }
        }
    }
}
