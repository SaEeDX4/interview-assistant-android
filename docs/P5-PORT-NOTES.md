# P5 Port Notes — Live audio

Phase P5 of the Android port (TRANSFER-MAP §7 row P5): the real `IAudioCaptureService`
(AudioRecord microphone routes + AudioPlaybackCapture secondary), the foreground service,
silence injection and restart/backoff, the D2 shortcut substitutes, and Interview Focus via
PiP (D9). Windows originals read for this phase: `Services/AudioCaptureService.cs` (223 lines)
and `Services/HotkeyService.cs`; spec: TRANSFER-MAP §6.2 (audio row), §7 (P5 row), §8 D1/D2/D9,
audit-app §10 (W1/W2).

**Honesty boundary (read first):** the §7 P5 exit gate is an end-to-end live session against
the shared backend **on a real device** — transcription through the relay, heartbeat metering
vs the server ledger, pause/stop billing behaviour, the 60-minute soak mirror, D1 behaviour in
both modes. None of that is CI-verifiable and **none of it is claimed here**. This package is
the complete implementation plus JVM tests for every pure decision; the device gate stays
OPEN. A second, independent blocker also remains: the composition's update provider still
throws until P6 (DC-181), so the app is not yet runnable end-to-end on a device at all.

## What was built

Behaviour lives in a member-for-member port of the C# state machine; Android classes are thin
platform adapters over it.

| File | Lines | Content |
|---|---|---|
| `app/.../platform/audio/CaptureCore.kt` | 280 | The C# `AudioCaptureService` state machine: start/stop/dispose, the five status codes, silence injection (100 ms timer, frame when ≥ 200 ms quiet, lastData reset to now−100), restart with backoff, default-device following, device-return recovery, per-frame RMS/peak/`FramesWithSignal` accounting. Platform facts injected (source factory, route resolver, scheduler, clock, loggers). |
| `app/.../platform/audio/FramePipeline.kt` | 59 | Raw buffers → :core `PcmConverter`/`AudioFramer` → 100 ms frames with the C#'s levels; the silence frame (4 800 zero bytes). |
| `app/.../platform/audio/CaptureRoutes.kt` | 94 | The picker model: system default first (id `null`), present capture routes in fixed order, AudioPlaybackCapture last; id resolution incl. the foreign-id rule (DC-169). |
| `app/.../platform/audio/RestartBackoff.kt` | 28 | `ScheduleRestart`'s formula verbatim: 1 s, 2 s, 4 s, 8 s, 8 s… (post-increment attempt, shift clamped at 3). |
| `app/.../platform/audio/AndroidCaptureSources.kt` | 245 | The two `AudioRecord` sources: microphone (rate negotiation 48/44.1/24/16 kHz PCM16 mono, preferred-device routing, communication-device for wired/BT) and AudioPlaybackCapture (48 kHz PCM16 stereo, media/voice/unknown/game usages). Read loops report their own end as the C#'s `RecordingStopped`. |
| `app/.../platform/audio/HandlerCaptureScheduler.kt` | 52 | `CaptureScheduler` on a HandlerThread; a failing task logs and never kills the scheduler. |
| `app/.../platform/audio/AudioCaptureService.kt` | 344 | The seam implementation: route enumeration from `AudioManager`, the RECORD_AUDIO gate with a pended start, the MediaProjection token (one consent = one projection) and consent request, the playback→microphone fallback routing, the `AudioDeviceCallback` halves of `IMMNotificationClient`, FGS step control. Every seam member delegates to `CaptureCore`. |
| `app/.../platform/CaptureForegroundService.kt` | 168 | The D12 foreground service (microphone type; mediaProjection type added while playback capture runs) + `CaptureForegroundControl` (start/stop in step with capture; stop debounced 600 ms across `restartAudio`). Persistent notification with Pause/Resume, Coach and Stop actions → `ShortcutService`. |
| `app/.../platform/ShortcutService.kt` | 142 | D2: the five C# actions (+ notification-only Stop). `ShortcutMatcher` (pure: stored strings via `HotkeyParser` vs fsModifiers+vk), `KeyEventMapping` (metaState/keyCode → the C#'s coordinates), two-tier dispatch (process-wide command handler; activity window handler; activity-starter fallback). |
| `app/.../platform/InterviewFocus.kt` | 94 | D9: `FocusPlatform` (the seam the P4-G dialog calls), `FocusRestoreRecord` (write/has/clear over the C#'s `FocusRestore*` settings, the Live screen's own existence rule), `LiveFocusRequests` (the ManualInput focus dispatch). PiP aspect = the C#'s recommended Focus size 560×360. |

Edited (sanctioned wiring points + DC-180):

| File | Change |
|---|---|
| `app/.../AppComposition.kt` (92 lines) | The capture provider returns the real service (exposed as `audioCaptureService` for the activity's launcher installs); the init installs `ShortcutService.commandHandler` (the main VM's commands) and `activityStarter`. The update provider still throws (P6). |
| `app/.../MainActivity.kt` (478 lines) | RECORD_AUDIO / POST_NOTIFICATIONS / MediaProjection launchers installed into the capture service; `FocusPlatform` PiP handlers (record written from the activity's actual pre-PiP size; PiP only with a session active); `onPictureInPictureModeChanged` clears the restore record; `ShortcutService.windowHandler` (ToggleWindow flips the destination, ManualInput lands on Live + focuses the field); `dispatchKeyEvent` forwards keyboard-device key-downs (no repeats) to the matcher; keep-screen-on follows `sessionActive` (D8). |
| `app/.../ui/live/LiveScreen.kt` | DC-180 minimal wiring: `applyFocus`/`restoreFocusPlacement` call through `FocusPlatform` (preset persistence unchanged); the manual bar registers its `FocusRequester` with `LiveFocusRequests`. |
| `app/src/main/AndroidManifest.xml` | RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MEDIA_PROJECTION; the service declaration (`microphone|mediaProjection`); MainActivity PiP support + configChanges (DC-178). |

## Tests (JVM only — CI has no emulator; no androidTest written)

37 @Test in 6 files under `app/src/test/java/com/interviewassistant/app/platform/`:

- `audio/CaptureCoreTest.kt` — 14: activation + frame flow with levels, stop, both start-failure
  statuses, absent route, unexpected-stop reconnect, backoff progression through the core,
  silence injection timing/continuation/resume, no silence while stopped, source switch on
  re-start, device-return revival, default-following rule, permission-blocked verdict, dispose.
- `audio/FramePipelineTest.kt` — 6: both source shapes → ten exact 4 800-byte frames per
  second, odd-chunk continuity (byte-identical to single-push), quiet/silent frames under the
  −50 dB signal rule, empty input, silence-frame shape/freshness. Feeds the pipeline through
  the real :core `PcmConverter`/`AudioFramer`, the same pair the :core audio fixtures pin.
- `audio/CaptureRoutesTest.kt` — 5: list shape/order, default+playback always offered,
  null/foreign ids → default, present route/playback resolution, absent route.
- `audio/RestartBackoffTest.kt` — 2: the delay sequence and reset.
- `ShortcutMatcherTest.kt` — 6: the five defaults, non-matches, custom stored strings,
  unparseable strings, the key-event mapping, dispatch through `ShortcutService.onKeyEvent`.
- `FocusRestoreRecordTest.kt` — 4: existence rule (both dimensions positive), write+persist,
  partial/zero dimensions, clear.

## Divergences (continuing the global sequence from P4's DC-168)

- **DC-169 — Device model.** The C# picker lists *playback* endpoints (loopback sources) and
  the service never opens a microphone. Android's picker lists *capture routes* (built-in mic,
  wired headset, Bluetooth — first entry still the system default, id `null`) plus the
  AudioPlaybackCapture source last, because those are the sources that exist (D1). Route ids
  are `android:`-prefixed strings; an id naming no Android route (e.g. a WASAPI GUID in a
  copied settings.json) resolves to the default, mirroring the C#'s rule that an id not naming
  a render endpoint is ignored in favour of the default. A known route that is currently
  absent resolves to the C#'s missing-device path (NO PLAYBACK DEVICE + backoff).
- **DC-170 — Playback entry name.** The picker's playback-capture entry borrows the existing
  `toast_listening` string ("Listening to system audio"). The shared i18n asset is
  byte-identical with Windows with one key set across 10 languages and contains no key naming
  this Android-only source; adding one is a Windows-side lockstep change, not an Android edit.
  Route names otherwise come from the OS (`AudioDeviceInfo.getProductName()`), as the C#'s
  names come from the OS (`FriendlyName`).
- **DC-171 — CaptureCore seams.** The C# state machine is ported as `CaptureCore` with four
  injected platform facts (source factory, route resolver, scheduler, monotonic clock) and
  injected loggers, so the behaviour is JVM-testable. No behaviour differs: statuses, timings,
  the backoff formula, the silence rule and the device-following rules are the C#'s.
- **DC-172 — Permission gate.** Android needs RECORD_AUDIO where WASAPI loopback needs no
  permission. `AudioCaptureService.start` checks the grant first: ungranted → the start pends
  and the installed requester asks (only ever at a start/preview, never at app start); granted
  → the pended start runs; denied → `CaptureCore.noteStartBlockedByPermission()` sets
  DEVICE ERROR through the existing status path and schedules **no** restart (a restart cannot
  grant a permission; the user's next Start is the retry). Playback capture is gated by the
  same permission (the platform requires it for AudioPlaybackCapture too).
- **DC-173 — Route naming details.** If the OS reports no product name for a route, the picker
  falls back to a plain English platform name for that kind ("Built-in Microphone" etc.)
  instead of an empty row. The system-default entry's *current device name* (shown while
  capturing) is the communication device's name on API 31+ when one is set, else the built-in
  microphone's — Android exposes no "default input device" below 31; the name is a display
  string only and never steers capture (the default route is captured unpinned, as the C#
  follows the default endpoint).
- **DC-174 — Interview Focus geometry (D9 residual, stated plainly).** Applying a preset
  persists the preset exactly as the C# does and, **while a session is active**, enters PiP at
  the 560×360 aspect ratio. PiP's size and position are chosen by Android: TopCentre/TopLeft/
  TopRight placement is **not** honoured — no preset can be, and none is faked. Without an
  active session no PiP is entered (PiP is the session presentation; the C# moves an idle
  window, Android has no idle floating window to move). The restore record is written from the
  activity's actual pre-PiP size; leaving PiP *is* the restore, so `MainActivity` clears the
  record when PiP exits, and the dialog's Restore action expands PiP when it is showing.
- **DC-175 — Playback-capture fallback policy (OD-4).** Choosing the playback source without
  a consent token starts the microphone fallback on the default route and requests consent;
  granting it switches the running capture to playback capture (foreground service first gains
  the mediaProjection type, as Android 14 requires); denial leaves the microphone running.
  A playback source that fails to start falls back to the microphone after the first failure
  (an explicit new `start` resets the allowance), instead of looping DEVICE ERROR forever.
  An app that forbids capture (`ALLOW_CAPTURE_BY_NONE`) is silent-by-absence in the mix —
  indistinguishable from nothing playing, exactly the WASAPI loopback property — so no
  "blocked" state is claimed or detected; the silence injector keeps the timeline continuous
  and the on-screen device name always names the source actually capturing.
- **DC-176 — Foreground-service lifecycle.** The FGS starts in step with capture (from the
  capture service, via `CaptureForegroundControl`) and stops 600 ms after capture stops,
  debounced so `restartAudio` (device switch / settings save) does not churn the service or
  flicker the notification mid-session. Notification actions are Pause/Resume (label follows
  the session state at start time), Coach, and Stop; Stop's label borrows `tt_stop` (the
  shared asset has no bare "Stop" action label — the Live UI's own Stop is icon + that
  tooltip) and Coach's is the shared `coach` key. POST_NOTIFICATIONS is requested once,
  alongside the first capture start's permission flow (API 33+), never at app start.
- **DC-177 — Shortcut dispatch tiers.** The C#'s hotkeys fire process-wide; Android's fire
  where a surface exists: notification actions dispatch process-wide to the composition's
  command handler (works with no activity), hardware-keyboard events dispatch through the
  activity (keyboard-type devices only, key-down, no key-repeat — the C#'s `MOD_NOREPEAT`
  intent), and a window action with no live activity brings `MainActivity` forward
  (ManualInput lands on Live and focuses the field via the intent extra). `Stop` exists only
  as a notification action — it is not a sixth hotkey and has no stored combination.
- **DC-178 — Activity configChanges.** MainActivity now declares
  `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden` (required for sane
  PiP transitions), so rotation/fold posture changes recompose in place instead of recreating
  the activity — which is what §3.3 already promises (posture changes never change state,
  selection, drafts or session status).
- **DC-179 — Route pinning.** A chosen wired/Bluetooth route is pinned two ways: the
  `AudioRecord`'s preferred device, and the platform communication device (API 31+
  `setCommunicationDevice`; pre-31 Bluetooth SCO start/stop for the Bluetooth route) —
  Android's sanctioned route switch for capture devices. The default route is never pinned.
  Pinning is released when the source stops.
- **DC-180 — Wiring edits in P4-verified files.** Beyond the sanctioned AppComposition /
  MainActivity / AndroidManifest points, P5 touched exactly two spots in `ui/live/LiveScreen.kt`
  (the DC-161-deferred Focus bodies now call `FocusPlatform`; the manual bar registers its
  focus requester with `LiveFocusRequests`) and updated that file's header comment. Nothing
  else in any verified package was modified.
- **DC-181 — DC-91 half-resolved.** The capture provider is real; the **update provider still
  throws until P6**, so `MainViewModel` still cannot be constructed and the app is not yet
  runnable end-to-end on a device. P5's device gate therefore cannot be exercised until P6
  lands the update service (or a temporary internal substitute is authorised — none was).

## Deferred by name

- **The §7 P5 exit gate — OPEN:** end-to-end live session on a real device (relay
  transcription, metering vs server ledger, pause bills nothing, stop/expiry bill the tail),
  the 60-minute soak mirror, D1 verification in mic mode and (where permitted) playback
  mode. Blocked by DC-181 as well as by the absence of a device in CI.
- **Update service → P6** (unchanged; DC-181).
- **P2's open items → P6 device testing** (unchanged): real-device sign-in + device
  registration against the live backend; Backend.Tests re-run.
- **Battery-optimisation exemption flow** (D12 mentions the readiness check surfacing it):
  not built in P5 — the readiness surface for it belongs with the P6 device pass; the FGS +
  lease design already bound the damage of an OEM kill, as D12 records.
