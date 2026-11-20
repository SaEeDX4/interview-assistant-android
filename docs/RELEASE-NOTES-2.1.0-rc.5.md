# Interview Assistant for Android — 2.1.0-rc.5

**Status: release candidate. Not yet published.** The artifacts for this version
are built by CI on every push (workflow artifact `app-release`); publication is an
owner-executed step (see `docs/P8-PORT-NOTES.md` for the runbook).

## What this release is

The native Android edition of Interview Assistant, built as a point-by-point copy
of the Windows application: same features, same behaviour, same prompts, same
question banks, same 10 languages (including Persian and Arabic with RTL layout),
same account, plans and metering on the shared backend. It is not a redesign and
not a reduced mobile edition — every Windows surface has an Android counterpart
(21 of 21 UI surfaces mapped in the transfer map).

Highlights of the mirrored product:

- Live interview assistance: the interviewer's voice is captured and answered
  with 3 short speakable answers; Coach mode gives 3 keywords plus one structure
  line.
- Two capture modes: microphone, and — where the meeting app permits it —
  capture of the call audio on the same phone (with an honest microphone
  fallback where an app blocks capture; blocked apps are never claimed to work).
- Preparation: a 50–150 question bank built per role from the mirrored
  preparation pipeline (the code enforces the 150 cap, exactly as on Windows).
- Account: sign-in with PKCE, devices, usage, export and account deletion,
  identical entitlements to Windows — one account across platforms.
- Updates on the direct-download channel arrive through the project's own
  release system and are accepted only from the same signer that installed the
  app, after a SHA-256 check, and never in the middle of a live session.

## Mirrored Windows baseline

- Windows version mirrored: **2.1.0-rc.5**
- Windows source commit: **4976e16f5379d38e9b265d3db979d2f8d7d7d7aa21**
  (repository `SaEeDX4/REAL-TIME-WINDOWS-INTERVIEW-ASSISTANT`, read-only
  throughout this project; the backend and contracts are that same code,
  unchanged and shared).
- Android `versionCode`: **20100005**, derived from the version name by the
  fixed lockstep scheme recorded as DC-203 in `docs/P8-PORT-NOTES.md`.

## Parity evidence (CI-verified, not asserted)

- **1,133 unit tests, all passing** (`:app` 607, `:core` 383, `:client` 132,
  `:contracts` 11) in GitHub Actions on the release head.
- **TEST-MAP closure: 674 / 674** Windows test methods accounted for — 120
  backend (shared code, unchanged), 495 mirrored in Kotlin, 59 recorded as
  WPF-mechanics-only with their Android state-logic counterparts named;
  0 unaccounted (`parity/TEST-MAP.md`).
- **Line coverage, measured fresh in CI on every run:** `:core` **90.3 %**,
  `:client` **94.1 %** — both at or above the 90 % floor the build enforces.
- All 80 Windows audio/document fixtures are consumed by the test suite; the
  shared assets (10 language tables × 1,166 keys, question library, playbooks)
  are byte-identical to Windows, verified by SHA-256 in CI on every run.
- The 60-minute simulated soak test is mirrored and in the suite.

## Honest open items (not done, not claimed)

- **No real-device verification yet.** The end-to-end device session (sign-in
  against the live backend, device registration, a full live session in both
  capture modes, metering against the server ledger, a prepared interview)
  is an owner-device step and remains open, as do the on-device update,
  export/delete and Paddle-sandbox upgrade checks.
- **Google Play publication is pending the owner's Play Console steps**
  (developer-account check OD-3, app creation, the `pro_monthly` subscription
  product, licence testers). The Play Billing client is built and tested; Play
  purchases cannot grant entitlement until the server-side verification bridge
  exists — that bridge is a change in the (read-only) Windows repository and
  needs the owner's separate approval (DC-187). No client-side entitlement is
  ever granted without server verification.
- **Backend.Tests re-run** (a parity-gate item) is environment-blocked in this
  project: no .NET SDK / PostgreSQL is available here (DC-192).
- **Compose accessibility tree assertions** are device/emulator-pending
  (DC-195); the JVM-level accessibility behaviours are pinned by tests.
- One shared-source gap is mirrored faithfully rather than fixed on Android
  alone: the `pf_title` string is missing from all 10 language tables on both
  platforms (DC-196); its fix is a Windows lockstep change.
- The CI release artifacts are **unsigned**. The owner signs the direct-channel
  APK locally with the release key whose certificate SHA-256 fingerprint is
  recorded in `docs/P8-PORT-NOTES.md`; that fingerprint anchors the same-signer
  update chain.

## Distribution plan (owner decision OD-1)

1. **Direct download** through the project's own release system (the Step-41
   release panel): the signed APK is published as platform `android`, channel
   `stable`, with its SHA-256 recorded; the in-app updater verifies hash and
   signer before offering it.
2. **Google Play**: the AAB from CI is uploaded by the owner. Play App Signing
   re-signs the app with Google's key, so the Play channel and the direct
   channel have different signers by design; the channels are kept strictly
   separate and the updater never falls back across channels.
