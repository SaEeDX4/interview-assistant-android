# P8 — Release: port notes (DC-202…DC-207)

Scope of this phase as executed here: the **CI-verifiable subset** of the
TRANSFER-MAP §7 P8 row — release build in CI, versioning, signing support with
the real key generated and held outside the repo, licence notices inside the
package, release notes — plus the publication steps prepared as an exact
owner runbook (§5 below). Publication itself, the Play Console work (OD-3) and
the device smoke test are owner-executed and are **not** claimed done anywhere
in these notes. Nothing in this phase was compiled or run locally: CI is the
first execution check for the release build.

## 1. What was built

- `.github/workflows/build.yml`:
  - A **guard step** (DC-204) runs before any Gradle work and fails the build
    if signing material or secrets ever enter the tree.
  - After the existing steps (full debug test suite → §5.5 coverage gate →
    coverage printout → shared-asset checksums), CI now runs
    `:app:assembleRelease :app:bundleRelease` and uploads the APK + AAB as the
    workflow artifact **`app-release`**. A log line states plainly whether the
    artifacts are signed or unsigned (today: unsigned — no `IA_RELEASE_*`
    secrets are configured).
- `app/build.gradle.kts`:
  - `versionCode` is now derived from `versionName` by the lockstep scheme
    (DC-203): 2.1.0-rc.5 → **20100005**.
  - The release build type takes an injected signing config when the four
    `IA_RELEASE_*` environment variables (or `-P` properties) are present and
    is unsigned otherwise; configuration logs which case applies (DC-204).
  - R8/minify and resource shrinking stay off (DC-202).
  - The release variant carries `assets/THIRD_PARTY_NOTICES.md`, generated
    from `parity/THIRD_PARTY_NOTICES.md` by the `copyThirdPartyNotices` task
    wired ahead of `mergeReleaseAssets` (DC-205).
- `docs/RELEASE-NOTES-2.1.0-rc.5.md`: the release notes for this version,
  naming the mirrored Windows commit and the parity evidence, with the open
  items stated plainly.
- The release keystore was generated locally (see §3). It lives **outside**
  the project tree and is never committed.

## 2. Divergences registered in this phase

### DC-202 — R8/minify is OFF for the first release

The release build ships with `isMinifyEnabled = false` and
`isShrinkResources = false`, as the P1 skeleton already had it. Reasoning:
(1) the Windows app ships unobfuscated — bytecode shrinking has no Windows
counterpart behaviour to mirror; (2) the app's correctness depends on
reflection-shaped surfaces (kotlinx.serialization generated serializers,
OkHttp, the Play Billing client) whose keep-rules can only be validated by
running the shrunk artifact; (3) no device or emulator is available in this
environment, so a shrunk build **cannot be smoke-tested at all** here, and
enabling a shrinker whose output cannot be verified would trade a known-good
artifact for an unverifiable one. R8 may be revisited after the device gate,
with the shrunk build passing the same smoke checklist in §5.3 before it
ships. This is a packaging decision, not a behaviour divergence: the shipped
code is byte-for-byte the code the 1,133 tests exercise.

### DC-203 — versionCode scheme (derived, mechanical, monotonic)

`versionCode = MAJOR * 10,000,000 + MINOR * 100,000 + PATCH * 1,000 + stage`,
where `stage` is the N of a `-rc.N` suffix (1..998) or 999 for a final
release. Implemented as `lockstepVersionCode()` in `app/build.gradle.kts`;
the function rejects any version name outside `MAJOR.MINOR.PATCH[-rc.N]`, so
a future lockstep bump that does not fit the scheme fails the build loudly
instead of shipping a wrong code. Worked line: 2.1.0-rc.5 → 20100005 <
2.1.0 → 20100999 < 2.1.1-rc.1 → 20101001. Both distribution channels use the
same code for the same version name; every published upload in either
channel must use a strictly greater code than the previous upload in that
channel, which the scheme guarantees as long as version names move forward.

### DC-204 — Signing is injected; CI is unsigned; the key is owner-held

The release signing config exists only when `IA_RELEASE_STORE_FILE`,
`IA_RELEASE_STORE_PASSWORD`, `IA_RELEASE_KEY_ALIAS` (default
`interview-assistant`) and `IA_RELEASE_KEY_PASSWORD` are supplied via the
environment or `-P` properties. CI configures none of them, so CI artifacts
are **unsigned** and both the Gradle configuration log and the workflow log
say so in plain words. Signing the direct-channel APK is a local owner step
(§5.1). A CI guard step fails the build if a keystore/key file
(`*.jks`/`*.keystore`/`*.p12`/`*.pfx`/`*.key`), a literal signing-password
assignment in a Gradle file, or a real PEM private-key body ever enters the
tree. (The guard is written so the legitimate look-alikes do not trip it:
`SecretFilter`'s redaction regexes and its short fake test fixture are not
key material.) The keystore itself is held at
`~/workspace/interview-assistant-android/signing/` — a sibling of
`project/`, outside the repository tree — with 0600 permissions on both the
keystore and its password file. Only the certificate fingerprint is recorded
(§3); the password is recorded **nowhere** in these notes or the repo.

### DC-205 — Licence notices ship as `assets/THIRD_PARTY_NOTICES.md`

Closes the packaging half of DC-193. A `copyThirdPartyNotices` Copy task
copies the single source of truth, `parity/THIRD_PARTY_NOTICES.md`, into
`app/build/generated/thirdPartyNotices/`, which is added as an assets source
dir of the **release** source set only; `mergeReleaseAssets` depends on the
copy task, so both the release APK and the release AAB carry the notices at
`assets/THIRD_PARTY_NOTICES.md`. Debug artifacts are unchanged. The
two-direction licence test from P7 (`LicenseComplianceTests`) continues to
pin the notices content against the dependency catalog.

### DC-206 — The two channels have different signers, permanently

Play App Signing means Google re-signs the Play-distributed app with
Google's app-signing key; the direct channel is signed with the project key
in §3. The same-signer update chain (D4, P6 update client) therefore has a
**different anchor per channel**, and that is by design, not an accident to
be repaired: the in-app updater reads only the project's own release
manifest (the direct channel), selects only the exact channel from
settings, and refuses any cross-channel fallback (P6). Maintenance rules:
never publish a Play-signed build to the direct manifest; never treat a
direct-signed APK as an update path for a Play-installed copy (Android
itself would refuse the signature mismatch); an owner who wants to switch a
device between channels must uninstall first, which Android enforces. The
Play channel's updates are delivered by Play itself.

### DC-207 — Release-variant unit tests are not run in CI

CI runs the debug-variant unit-test suite (all four modules), the §5.5
coverage gate on `:core`/`:client`, and only then builds the release
artifacts. `:app:testReleaseUnitTest` is not run: with minify off (DC-202)
the release variant compiles the identical sources the debug suite
exercises, so a second run would re-test the same bytecode inputs while
doubling CI time. If R8 is ever enabled (DC-202 revisit), this decision must
be revisited with it, because a shrunk release is no longer the tested code.

## 3. Release key (the same-signer anchor, D4)

Generated locally with `keytool` (JDK 17) on 2026-11-20:

- Keystore: `~/workspace/interview-assistant-android/signing/interview-assistant-release.jks`
  (outside `project/`; verified not inside the repository tree)
- Password file: `~/workspace/interview-assistant-android/signing/release-keystore-password.txt`
  (0600; the password itself is recorded nowhere else)
- Alias: `interview-assistant`
- Algorithm: RSA 4096, SHA256withRSA; DN: `CN=Interview Assistant`
- Validity: 2026-11-20 → 2051-11-14 (9,125 days, ≥ the 25-year horizon Play
  requires)
- **Certificate SHA-256 fingerprint:**
  `8B:26:15:C0:3E:4C:4D:A7:D1:E3:54:37:16:BE:CF:DE:E9:C5:7F:B1:76:46:AC:65:28:B4:88:01:3E:C0:F4:C1`
- Certificate SHA-1 fingerprint:
  `1F:88:45:7C:A6:91:6D:64:C4:A2:50:5C:68:4A:15:6A:60:C0:78:C3`

This SHA-256 digest is the value the P6 update client compares (signer
certificate digest of the downloaded APK vs. the installed app) on the
direct channel: any direct-channel update signed with this key passes the
signer check; anything signed with any other key is refused. **Losing this
keystore or its password ends the direct channel's update chain** — back
both files up outside this machine before the first publication.

## 4. Release identity

- versionName: `2.1.0-rc.5` — the mirrored Windows release (TRANSFER-MAP
  §1.2 step 7); the Windows csproj carries the identical string.
- versionCode: `20100005` (DC-203).
- Mirrored Windows commit: `4976e16f5379d38e9b265d3db979d2f8d7d7aa21`.
- Application id: `com.interviewassistant.app` (unchanged since P1).

## 5. Publication runbook (owner-executed, exact steps)

Prerequisite for §5.1: a machine with the Android SDK build-tools
(`apksigner`, `zipalign` on PATH) and the two files from §3.

### 5.1 Direct-APK channel (project release system, Step-41 panel)

1. Open the GitHub Actions run for the release commit and confirm it is
   fully green (tests, coverage gate, checksums). Download the
   **`app-release`** artifact and unzip it. You get `app-release-unsigned.apk`
   and `app-release.aab` (unsigned, per DC-204).
2. Align and sign the APK with the project key (the key password is read
   from the password file, never typed into a command line that is logged):

   ```
   zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
   apksigner sign \
     --ks ~/workspace/interview-assistant-android/signing/interview-assistant-release.jks \
     --ks-key-alias interview-assistant \
     --ks-pass file:~/workspace/interview-assistant-android/signing/release-keystore-password.txt \
     --key-pass file:~/workspace/interview-assistant-android/signing/release-keystore-password.txt \
     --out interview-assistant-2.1.0-rc.5.apk \
     app-release-aligned.apk
   ```

3. Verify the signed APK before publishing — the signer digest must equal
   the §3 fingerprint, or the update chain anchor is wrong:

   ```
   apksigner verify --verbose --print-certs interview-assistant-2.1.0-rc.5.apk
   sha256sum interview-assistant-2.1.0-rc.5.apk
   ```

4. In the Step-41 release admin panel, create the release: platform
   **android**, channel **stable**, version **2.1.0-rc.5**; upload
   `interview-assistant-2.1.0-rc.5.apk`; the panel records the file's
   SHA-256 — check it character-for-character against the `sha256sum`
   output from step 3 before publishing.
5. Publish, then verify the manifest the update client actually reads:

   ```
   curl -s https://admin.cybervalida.online/manifest
   ```

   Confirm the android/stable entry for 2.1.0-rc.5 with an `https` file URL,
   the exact SHA-256 from step 3, and the real file size.
6. Acceptance logic (what the P6 client will do, for the step-7 check):
   the manifest entry passes the client's own file rules (https URL,
   64-hex SHA-256, size ≥ 0); the client selects only its settings channel
   (stable) with no cross-channel fallback; after download it verifies the
   SHA-256 first and then the signer digest against the installed app —
   both match only because steps 2–4 used this key and this file; and it
   never checks or applies during a live session.
7. On a device already running an earlier direct-channel build, open the
   app → Settings → Updates → Check updates: the offer must appear, and
   Restart-to-update must hand off to the system installer. (First install
   of the direct APK itself is step §5.3.1.)

### 5.2 Google Play channel (owner, Play Console)

1. **OD-3 check:** sign in at `play.google.com/console` and confirm the
   developer account from before is still active (identity verification
   complete, no outstanding policy strikes). If it is gone, register a new
   one first — everything below waits on it.
2. **Create the app:** name *Interview Assistant*, default language
   English (United States), App (not Game), Free (the subscription is
   in-app). Complete the store listing, content rating questionnaire,
   target audience, and the **Data safety** section (microphone audio is
   processed for the live feature; declare exactly what the privacy policy
   says — do not under-declare).
3. **Play App Signing:** accept the default enrolment. Consequence (DC-206):
   Google holds the final app-signing key and re-signs every Play build;
   the §3 keystore serves as the **upload key** for Play and remains the
   signing key of the direct channel only.
4. **Declarations Play requires for this app:**
   - *Foreground services:* declare the **microphone** foreground-service
     type and its purpose (live interview audio capture during a session
     the user started).
   - *Battery optimisation exemption* (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
     DC-188): restricted permission — provide the justification when Play
     asks: the app must stay alive through a live interview session it is
     actively assisting; the exemption is requested in context, at most
     once, from the session flow. If Play refuses it for the Play build,
     that is a Play-channel-only change to decide then — the direct channel
     is unaffected.
5. **Subscription product:** Monetize → Products → Subscriptions → create
   product ID **`pro_monthly`** (exactly this ID — it is the constant the
   P6 Billing client queries), add a base plan (monthly, auto-renewing)
   and set the price. The price is the owner's decision: the Windows
   sandbox reference is USD 19.99/month and is **unconfirmed** for launch;
   do not copy it into the Console without deciding it. Note DC-187: Play
   purchases cannot grant entitlement until the server-side verification
   bridge is approved and built — do not activate the offer for real users
   before that bridge exists.
6. **Licence testers:** Settings → Licence testing → add the owner's
   Google account(s); testers get licence responses (free test purchases)
   in any track.
7. **First upload:** Testing → Internal testing → create release → upload
   `app-release.aab` from the green CI run (versionName 2.1.0-rc.5,
   versionCode 20100005). Roll out to the internal track and install it on
   the owner's device from the Play link — this is also the Play-channel
   half of the §5.3 smoke test. Production promotion is a separate, later
   owner decision after the smoke test and the DC-187 bridge.

### 5.3 Device smoke test (the §7 exit gate, owner's device)

Use the owner's Samsung device. Direct channel first (§5.1 file), then —
once §5.2.7 exists — the Play internal-track build. Record pass/fail per
line; any failure stops publication.

1. **Install:** sideload `interview-assistant-2.1.0-rc.5.apk`; allow
   install-from-this-source when Android asks; the app opens to the Home
   shell. Pass = clean install, no crash, correct app name/icon.
2. **Sign-in:** sign in with the real account (browser PKCE flow returns
   into the app). Pass = dashboard shows the account and plan; Settings →
   Account → Devices lists this device with platform **android**.
3. **Readiness:** run the pre-interview check. Pass = all checks render
   verdicts; grant RECORD_AUDIO when asked; if the battery-exemption
   prompt appears, it appears once, in context.
4. **One live session (microphone mode):** start a session, speak test
   questions. Pass = transcription flows, 3 answers appear per question,
   pause visibly stops metering, stop ends the session, and the account
   usage afterwards matches the session length (heartbeat metering against
   the server ledger).
5. **One live session (same-phone capture, if a meeting app permits):**
   join a test call on the same phone and start a session. Pass = the
   interviewer's voice is captured via playback capture; if the meeting
   app blocks capture, the app falls back to microphone and says so
   honestly — silent failure is a fail.
6. **One prepared interview:** build a preparation (job description →
   question bank), then start the prepared session. Pass = the bank is
   generated (≤150 questions), the session uses it, and the report exists
   afterwards under Reports.
7. **Update path (direct channel):** with §5.1.5's manifest live and an
   older direct build installed, Check updates offers 2.1.0-rc.5 and the
   install completes; then confirm Settings → Updates shows *Up to date*.
   Also confirm no update is ever offered or applied while a session is
   live (start a session first, then check).
8. **Sixty-minute soak (recommended before production promotion):** one
   continuous live session of ~60 minutes. Pass = no duplicate answers,
   no runaway request counts, the app and the foreground-service
   notification stay healthy to the end.

## 6. What remains open after this phase (stated plainly)

- CI has not yet run on this phase's changes at the time of writing — the
  release build's first execution check is the next CI run.
- Publication itself (both channels), OD-3, the DC-187 bridge decision,
  and the §5.3 device gate are owner steps; none is done by these notes.
- Carried open items: DC-192 (Backend.Tests re-run, environment-blocked),
  DC-195 (Compose accessibility tree assertions, device-pending),
  DC-196 (`pf_title`, Windows lockstep fix).
- The SYNC-LOG entry for P8 is written when the release is actually
  published, naming the mirrored Windows commit — not before.

### P8 CI fix round 1 (DC-208)

Run #348: the guard, tests, coverage and checksum steps all passed,
but the release step failed while configuring :app — "Cannot convert
'' to a File" at the signing config. Root cause: CI defines the
IA_RELEASE_* environment names from currently-unset secrets, which
produces EMPTY STRINGS, not absent variables; the readiness check
tested only for null, so "" was treated as an injected keystore path
(and the alias log printed ''). All four injected values now pass
through a presentOrNull() blank check (a present-but-empty value
counts as absent), and the alias falls back to "interview-assistant"
when blank. No signing behaviour changes when real values are
injected.

### P8 CI fix round 2 (DC-209)

Run #350: signing configuration now passes (DC-208 held), but
:app:copyThirdPartyNotices failed Gradle's task validation —
"Property has implicit dependency" (the path quoted in the first
checker's report did not exist anywhere in the build; the verbatim
log lines were re-read before acting). Root cause: wiring the
generated directory in as a plain sourceSets assets.srcDir plus a
tasks.matching dependsOn on mergeReleaseAssets declares a task
dependency but not the input/output producer relationship Gradle's
validator requires. The directory now reaches the release variant
through the AGP variant API (sources.assets
addGeneratedSourceDirectory), which wires both. The Copy task and
its single source of truth (parity/THIRD_PARTY_NOTICES.md) are
unchanged.

### P8 CI fix round 3 (DC-210)

Run #352: DC-209 held — the release APK and AAB were built and
packaged (:app:packageRelease, :app:bundleRelease and
:app:signReleaseBundle all ran) — but :app:lintVitalRelease failed
with 5 errors, all the same check
(InvalidFragmentVersionForActivityResult from androidx.activity) at
the five registerForActivityResult call sites in MainActivity.kt
(lines 131/135/141/146/148, read verbatim from the log). The check
requires the Fragment library at >= 1.3.0 wherever the Activity
Result APIs are used; nothing in the dependency graph brought
androidx.fragment in at all (activity-compose does not carry it),
and the debug variant never runs lintVital — which is why this
surfaced only when the release build was first exercised in P8.
Fix: androidx.fragment:fragment-ktx 1.8.5 is declared explicitly in
the catalog and as an :app implementation dependency — a real
dependency satisfying the rule as written; no lint suppression, no
baseline, no abortOnError change. The licence inventory gained the
matching row (the two-direction LicenseComplianceTests pin catalog
<-> notices). An earlier checker report for this run quoted plugin
versions that exist nowhere in this project (AGP 8.13.2 / Kotlin
2.2.21); it was set aside and the log re-read verbatim before acting.

## P8 CI VERIFICATION — COMPLETE (the CI-verifiable subset)

Run #356 on head 9fdccc7 — SUCCESS.

- The release step EXECUTED and SUCCEEDED: :app:assembleRelease and
  :app:bundleRelease both ran — "BUILD SUCCESSFUL" (161 tasks).
  lintVitalRelease passes with the DC-210 dependency declared.
- The "app-release" artifact (APK + AAB) was uploaded for the run
  (18,509,090 bytes). The artifacts are UNSIGNED, exactly as designed
  (DC-204): CI carries no signing material; signing is the local
  owner step in the §5.1 runbook with the §3 key.
- The signing-material guard step PASSED ("no keystore files,
  literal signing passwords or private-key material in the tree").
- :app:testDebugUnitTest EXECUTED fresh and passed (the full
  1,133-test project suite stands); the JaCoCo step PASSED with the
  measured counters unchanged (:client missed 44 / covered 704 =
  94.1%; :core missed 729 / covered 6,758 = 90.3%); shared-asset
  checksums 13/13 OK.
- Fix rounds: DC-208 (empty signing env counts as absent), DC-209
  (notices wired through the AGP variant API), DC-210
  (fragment-ktx declared for lintVitalRelease).

P8 status: the build/packaging half of the §7 P8 gate is verified.
The gate's remaining half is owner-executed by design and is NOT
claimed here: signing + publication through the release panel and
Play (runbook §5.1/§5.2, incl. OD-3), and the device smoke test
(§5.3). SYNC-LOG Sync #8 records this state and names the mirrored
Windows commit; it does not claim publication.
