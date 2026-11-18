# Interview Assistant — Android Transfer Map

**Master technical document for the 100 % native Android copy of the Windows Interview Assistant.**
Stack: Kotlin + Jetpack Compose. Architecture: Sync-First (Shared / Mirror / Platform).
Source of truth for this map: `audit-core.md`, `audit-app.md`, `audit-backend-tests.md` (full deep-read audits of the Windows reference) and `all-src-files.txt` (204-file ground truth).

---

## 0. Purpose, source baseline, rules of the copy

### 0.1 Purpose

This document is the complete, point-by-point transfer plan from the Windows app to a native Android app. It exists so that:

1. Nothing in the Windows app is dropped, simplified, or "watered down" in the Android copy — every file, feature, screen, rule, threshold, prompt and string has a named Android destination (§2, §3).
2. Future Windows upgrades propagate to Android in lockstep, by procedure, not by memory (§1.2).
3. Every place where Android physically cannot do what Windows does is named honestly, with the exact substitute and the residual gap, if any (§8). No silent divergence is permitted.

### 0.2 Source baseline (verified)

| Fact | Value |
|---|---|
| Windows repo | `SaEeDX4/REAL-TIME-WINDOWS-INTERVIEW-ASSISTANT` — **READ-ONLY for all Android work. Never modified, never committed to.** |
| Baseline commit | `4976e16f5379d38e9b265d3db979d2f8d7d7aa21` (`4976e16`) |
| Android repo | New, separate repository, planned name: `interview-assistant-android` (visibility = owner decision OD-2, §9) |
| Source files in scope (`src/**`) | **204 files** — App 79, Backend 38, Core 75, Client 10, Contracts 2 (incl. project/asset files; `all-src-files.txt` is the ground truth) |
| Source size | Core C# 9,602 lines; App C# 11,791 + XAML 2,542; Backend 7,066 (3,082 excl. generated migrations); Client 973 (audit count 956–973 by counting method); Contracts 92; i18n 10 × 1,168 lines |
| Windows test suite | **674** `[Fact]`/`[Theory]` methods (Backend.Tests 120, InterviewAssistant.Tests 554) + **356** `[InlineData]` expansions (33 + 323) + **80** fixture files (§5) |
| App version at baseline | 2.1.0-rc.5 |
| Backend | ASP.NET Core + PostgreSQL, Supabase Auth (JWT/JWKS), Paddle (Merchant of Record), OpenAI — **shared unchanged**; Android is a new client of the same `/api/v1` (42 mapped endpoints) |
| Also transferred but outside the 204 | `knowledge/generic_question_library.json` (52 questions, 144 variants — bundled byte-identical), `knowledge/playbooks/*.md` (2 files, bundled byte-identical), `samples/shervin-teroxx/**` (golden parity fixtures, §5.3), `tests/**` (§5) |

### 0.3 Rules of the copy — what "100 %" means

1. **100 % = behaviour parity proven by tests, not visual similarity and not line-count similarity.** For every observable behaviour of the Windows app (output text, bullet counts, thresholds, timing, metering, error codes, state machines), the Android app must produce the same result for the same input, and a Parity Test derived from the Windows suite must prove it (§5). A screen that merely "looks like" the Windows screen but behaves differently is a failed copy. A screen that looks native-Android but passes every behaviour test is a successful copy.
2. **Every number is copied, not rounded, tuned, or "improved".** Thresholds, caps, timeouts, token limits, style tables, VAD values, prices of record, plan limits — all are transcribed exactly (§4) and asserted by tests. If a value looks wrong, it is still copied and flagged in §8 (see the question-bank floor, D13) — the Android team does not silently fix Windows behaviour.
3. **Every prompt and string is copied verbatim.** The LiveAnswer system prompt, Coach prompt, transcription bias prompt, honest-bridge sentences, personal-guidance texts, report label tables and all 10 i18n files (1,168 keys each) transfer byte-for-byte (§2). Prompts live in exactly one mirrored location per platform, generated/checked from the same source text.
4. **Same structure, same names.** Mirror-layer Kotlin files keep the Windows module, file and type names (`TurnDetector.kt` mirrors `TurnDetector.cs`, package `…core.turn`). A developer reading a Windows diff must be able to open the identically-named Android file. Renaming for "Kotlin style" is forbidden where it breaks this traceability.
5. **No feature is removed because the screen is small.** Phone/foldable adaptations change layout only (§3.3). Every control, action and piece of information present on Windows is reachable on Android.
6. **No pill/capsule buttons, ever.** Buttons are rectangular with the Windows radii (Card 12 dp, Control 8 dp) — owner's standing design rule, already encoded in the Windows theme (§3.1).
7. **The Windows repo is never touched.** All Android work — code, docs, fixtures — lives in the Android repo and in `~/workspace/interview-assistant-android/`. The reference clone under `windows-reference/` is a read-only snapshot of commit `4976e16`, refreshed by re-clone only.
8. **Honesty over coverage claims.** Anything not yet proven by a parity test is "unproven", not "done". Divergences are registered in §8 before the affected phase starts, not discovered after it ends.

---

## 1. Sync-First architecture and the Lockstep upgrade protocol

### 1.1 The three layers

```
┌──────────────────────────────────────────────────────────────────┐
│ (A) SHARED — one source, both platforms                          │
│  Backend (ASP.NET Core/PostgreSQL) · /api/v1 contracts ·         │
│  RuntimeConfig · prompts served/derived server-side ·            │
│  i18n JSON (byte-identical) · knowledge library + playbooks ·    │
│  plan catalog · Paddle billing            → upgrade once,        │
│                                             both platforms get it│
├──────────────────────────────────────────────────────────────────┤
│ (B) MIRROR — same logic, two languages, proven equal             │
│  Core client logic (turn, intelligence, preparation, live,       │
│  domain, ingestion, persistence, reports) · Client layer         │
│  (auth/session/answers) · UiState pure logic · AppSettings ·     │
│  CloudService      → C# file-for-file in Kotlin, same names,     │
│                      equality proven by Parity Tests (§5)        │
├──────────────────────────────────────────────────────────────────┤
│ (C) PLATFORM — OS-bound, behaviour-identical                     │
│  Audio capture · foreground service · Keystore · OAuth redirect  │
│  update delivery · file pickers · window/foldable handling ·     │
│  Compose UI implementation    → Android-native code behind the   │
│                                 exact behaviour specs of §3/§4   │
└──────────────────────────────────────────────────────────────────┘
```

Layer counts from the §2 mapping (204 files): **Shared 49 · Mirror 101 · Platform 45 · Not-Needed 9** (each Not-Needed entry carries its justification in the table; e.g. EF migrations stay server-side, `InstallLayout.cs` is Velopack-only, framework plumbing such as `ObservableObject.cs` is provided by Compose/StateFlow).

Design consequences, decided by this map (not owner decisions):

- **As much behaviour as possible is pushed to Layer A.** Anything the backend already decides (entitlements, metering, models, fallbacks, maintenance, minimum version, feature flags via `RuntimeConfig`) is never re-decided on Android. Android reads `RuntimeConfig` exactly like Windows (`GET /api/v1/config`, 15 s server cache, offline-grace 72 h, cached config enables offline start).
- **Mirror files are transliterations, not redesigns.** Control flow, ordering, tie-breaking, regexes, and even known quirks (§8 D13) are preserved. Where C# and Kotlin differ semantically (e.g. regex Unicode classes, `CultureInfo` vs ICU, LINQ ordering stability), the parity tests for that file must include the edge cases from the Windows tests.
- **Platform code is thin.** A Platform file may adapt an OS API, but every decision it makes (when to restart capture, when an update may apply, which error string to show) comes from a Mirror-layer rule or a §4 value.

### 1.2 Lockstep upgrade protocol (mandatory procedure for every future Windows change)

When the Windows app changes, Android is upgraded by this exact procedure. No step may be skipped; the release gate is step 7.

1. **Freeze the Windows target.** Record the new Windows commit hash and version. Re-clone the Windows repo read-only at that commit into the Android workspace (never edit it).
2. **Mechanical diff.** Diff the new commit against the previous mirrored commit over `src/**`, `knowledge/**`, and `tests/**`. Produce the changed-file list. Any file not previously in the §2 table is a mapping-table defect: add its row (with layer classification) in the same change.
3. **Classify every changed file** by its §2 layer:
   - **Shared** → no Android code change. Verify only: does the change alter the wire format, `RuntimeConfig`, prompts, or plan catalog? If yes, update the contract-parity fixtures (§5.4) and re-run the backend test suite.
   - **Mirror** → transliterate the diff into the identically-named Kotlin file, hunk by hunk. Do not refactor surrounding code in the same change.
   - **Platform** → check whether the behaviour spec changed (new action, new threshold, new state). If only the Windows OS mechanism changed, Android does not change; record "reviewed, no Android impact" in the sync log.
   - **Not-Needed** → record "reviewed, no Android impact".
4. **Sync the tests first.** Port every new/changed Windows test and fixture (§5) into the parity suite before or together with the code change. A Windows test with no Android counterpart (and no Shared-backend rerun reference) blocks the release.
5. **Sync assets.** i18n JSONs, knowledge library, playbooks and prompt strings are copied byte-for-byte; a checksum manifest (`parity/assets.sha256`) is regenerated and verified in CI. Any hand-edit of these files on the Android side is a build failure.
6. **Run the full parity gate** (§5.5): all mirrored-module parity tests, all 80 audio fixtures, golden preparation/engine fixtures, contract fixtures, and the shared Backend.Tests suite against the shared backend.
7. **Release in lockstep.** Android ships with the **same product version number** as the Windows release it mirrors (e.g. Windows 2.2.0 ⇒ Android 2.2.0; `versionCode` is an independent monotonic build number). The Android release notes name the Windows commit mirrored. If the parity gate fails, Android does not ship that version — it never ships a partial copy under the shared version number.
8. **Log the sync.** Append one entry to `parity/SYNC-LOG.md` in the Android repo: Windows commit, changed-file count by layer, tests added/changed, gate result, Android build number. This log is the auditable proof of lockstep.

### 1.3 Standing sync invariants (checked in CI on every Android build)

- Every path in the Android repo's copy of the source-file inventory appears exactly once in the §2 mapping table (mechanical check, same method used to verify this document).
- Asset checksums (i18n ×10, knowledge library, playbooks, prompt source texts) match the mirrored Windows commit.
- No Android-only feature flags: a behaviour difference between platforms is either a §8 divergence or a bug.
- `MinimumClientVersion` gate (426 `client_update_required`) is honoured identically: Android sends `X-Client-Version` on every API request and surfaces the update-required state exactly like Windows.

---

## 2. Complete file-by-file mapping (204 / 204)

Layer key: **Shared** = reused unchanged (server-side or byte-identical asset) · **Mirror** = rewritten 1:1 in Kotlin, same structure/names, parity-tested · **Platform** = Android-native implementation behind the identical behaviour spec · **Not-Needed** = no Android target, justification in Notes.

| # | Windows source file | Android target | Layer | Notes |
|---|---|---|---|---|
| 1 | `src/InterviewAssistant.App/App.xaml` | `app/src/main/java/com/interviewassistant/app/ui/theme/InterviewAssistantTheme.kt` | Platform | Merges Theme tokens into Compose theme; dark-only |
| 2 | `src/InterviewAssistant.App/App.xaml.cs` | `app/src/main/java/com/interviewassistant/app/InterviewAssistantApplication.kt` | Platform | Application class: 3 global exception handlers, log prune 14 d, self-test isolation mode |
| 3 | `src/InterviewAssistant.App/Assets/app.ico` | `app/src/main/res/ (adaptive icon set ic_launcher)` | Platform | Icon artwork redrawn as adaptive icon; same brand mark |
| 4 | `src/InterviewAssistant.App/GlobalUsings.cs` | `— (no target)` | Not-Needed | C# language construct; Kotlin uses per-file imports |
| 5 | `src/InterviewAssistant.App/HomeWindow.Account.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/AccountPage.kt` | Platform | Account page + auth card; state machine is mirrored AuthFormState |
| 6 | `src/InterviewAssistant.App/HomeWindow.DataExport.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/DataExport.kt` | Platform | Export/import flows via Storage Access Framework (D10); formats and guards identical |
| 7 | `src/InterviewAssistant.App/HomeWindow.Help.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/HelpPage.kt` | Platform | Help & About page incl. diagnostics copy |
| 8 | `src/InterviewAssistant.App/HomeWindow.Readiness.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/HomeReadiness.kt` | Platform | Readiness snapshot rendering on Start/Interviews/sidebar |
| 9 | `src/InterviewAssistant.App/HomeWindow.Start.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/StartPage.kt` | Platform | Get-started page; planner logic mirrored in UiState/Readiness |
| 10 | `src/InterviewAssistant.App/HomeWindow.xaml` | `app/src/main/java/com/interviewassistant/app/ui/home/HomeScreen.kt` | Platform | Home shell: sidebar nav → NavigationRail/Drawer by width class; same 8 routes |
| 11 | `src/InterviewAssistant.App/HomeWindow.xaml.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/HomeViewModel.kt` | Platform | Shell logic, guarded navigation, account menu, settings footer Save/Discard |
| 12 | `src/InterviewAssistant.App/InterviewAssistant.App.csproj` | `— (replaced by Gradle build script of the target module)` | Not-Needed | Build-system file only; target module :app. NAudio/Velopack deps → §8 D1/D4 |
| 13 | `src/InterviewAssistant.App/MainWindow.Chrome.cs` | `app/src/main/java/com/interviewassistant/app/ui/live/LiveChromeLayout.kt` | Platform | Width-breakpoint chrome rules mirrored from UiState/LiveChrome (440/580/640/700/780/880 dp) |
| 14 | `src/InterviewAssistant.App/MainWindow.Focus.cs` | `app/src/main/java/com/interviewassistant/app/ui/live/InterviewFocus.kt` | Platform | Interview Focus → Picture-in-Picture + placement presets (D9) |
| 15 | `src/InterviewAssistant.App/MainWindow.QuickHelp.cs` | `app/src/main/java/com/interviewassistant/app/ui/live/QuickHelpSheet.kt` | Platform | Quick-help popover → Compose sheet/popover, same state-dependent content |
| 16 | `src/InterviewAssistant.App/MainWindow.xaml` | `app/src/main/java/com/interviewassistant/app/ui/live/LiveScreen.kt` | Platform | Live Assistant screen (§3.2) |
| 17 | `src/InterviewAssistant.App/MainWindow.xaml.cs` | `app/src/main/java/com/interviewassistant/app/ui/live/LiveViewModel.kt` | Platform | Live screen logic hosting the mirrored MainViewModel state |
| 18 | `src/InterviewAssistant.App/Program.cs` | `app/src/main/java/com/interviewassistant/app/MainActivity.kt` | Platform | Entry point; Velopack pre-start hooks replaced by update-client init (D4); --update-test/--selftest become instrumentation modes |
| 19 | `src/InterviewAssistant.App/Services/AnswerServiceProbe.cs` | `app/src/main/java/com/interviewassistant/app/services/AnswerServiceProbe.kt` | Mirror |  |
| 20 | `src/InterviewAssistant.App/Services/AppLog.cs` | `app/src/main/java/com/interviewassistant/app/platform/AppLog.kt` | Platform | File logging in app-private storage; redaction rules, 14-day prune, 400-line ring mirrored exactly |
| 21 | `src/InterviewAssistant.App/Services/AppPaths.cs` | `app/src/main/java/com/interviewassistant/app/platform/AppPaths.kt` | Platform | %APPDATA%/%LOCALAPPDATA% → filesDir/cacheDir + SAF-chosen export locations; folder layout below base identical |
| 22 | `src/InterviewAssistant.App/Services/AppSettings.cs` | `app/src/main/java/com/interviewassistant/app/services/AppSettings.kt` | Mirror | Every property/default/migration (SettingsVersion=3) mirrored; stored as settings.json in filesDir, atomic write |
| 23 | `src/InterviewAssistant.App/Services/AudioCaptureService.cs` | `app/src/main/java/com/interviewassistant/app/platform/audio/AudioCaptureService.kt` | Platform | THE platform service: AudioRecord (mic, second-device) + AudioPlaybackCapture where permitted → same 24 kHz/100 ms pipeline (D1) |
| 24 | `src/InterviewAssistant.App/Services/Authenticode.cs` | `— (no standalone target)` | Not-Needed | WinVerifyTrust publisher check is performed by Android's APK signature verification inside the update flow (D4); same-signer rule preserved there |
| 25 | `src/InterviewAssistant.App/Services/Branding.cs` | `app/src/main/java/com/interviewassistant/app/services/Branding.kt` | Mirror |  |
| 26 | `src/InterviewAssistant.App/Services/BuildFlavor.cs` | `app/src/main/java/com/interviewassistant/app/services/BuildFlavor.kt` | Mirror | DEVELOPER_BUILD → BuildConfig.DEVELOPER_BUILD / product flavors |
| 27 | `src/InterviewAssistant.App/Services/BuildInfo.cs` | `app/src/main/java/com/interviewassistant/app/services/BuildInfo.kt` | Mirror | Version 2.1.0-rc.5 lineage → versionName/versionCode + commit id |
| 28 | `src/InterviewAssistant.App/Services/CloudService.cs` | `app/src/main/java/com/interviewassistant/app/services/CloudService.kt` | Mirror | State machine (NotConfigured…SignedIn) mirrored 1:1 over :client |
| 29 | `src/InterviewAssistant.App/Services/HotkeyService.cs` | `app/src/main/java/com/interviewassistant/app/platform/ShortcutService.kt` | Platform | No global hotkeys on Android → notification/FGS actions + in-app + hardware-keyboard shortcuts; same 5 actions, same defaults shown in UI (D2) |
| 30 | `src/InterviewAssistant.App/Services/SecretStore.cs` | `app/src/main/java/com/interviewassistant/app/platform/SecretStore.kt` | Platform | DPAPI → Android Keystore (AES-GCM, app-private files); same entropies as key aliases, same mask rule first3+last4 (D3) |
| 31 | `src/InterviewAssistant.App/Services/SelfTest.cs` | `app/src/main/java/com/interviewassistant/app/services/SelfTest.kt` | Mirror | Self-test checklist mirrored; runnable via diagnostics screen + instrumentation |
| 32 | `src/InterviewAssistant.App/Services/UpdateService.cs` | `app/src/main/java/com/interviewassistant/app/platform/UpdateService.kt` | Platform | Velopack → project release system client (Step-41 R2/D1 service) or Play per owner decision; policy from Client/UpdatePolicy (D4) |
| 33 | `src/InterviewAssistant.App/Services/WindowPlacement.cs` | `app/src/main/java/com/interviewassistant/app/platform/WindowPlacement.kt` | Platform | Monitor/DPI geometry → WindowSizeClass + foldable posture; persisted placement becomes persisted nav/pane state (D8) |
| 34 | `src/InterviewAssistant.App/Services/Workspace.cs` | `app/src/main/java/com/interviewassistant/app/services/Workspace.kt` | Mirror | Workspace orchestration over Core WorkspaceStore; DPAPI protector injected from platform SecretStore |
| 35 | `src/InterviewAssistant.App/Themes/Theme.xaml` | `app/src/main/java/com/interviewassistant/app/ui/theme/Theme.kt + Tokens.kt` | Platform | Every token value carried over exactly (§3.1); implementation is Compose, values are not redesigned |
| 36 | `src/InterviewAssistant.App/UiState/AccountMenu.cs` | `app/src/main/java/com/interviewassistant/app/uistate/AccountMenu.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 37 | `src/InterviewAssistant.App/UiState/AccountView.cs` | `app/src/main/java/com/interviewassistant/app/uistate/AccountView.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 38 | `src/InterviewAssistant.App/UiState/AuthFormState.cs` | `app/src/main/java/com/interviewassistant/app/uistate/AuthFormState.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 39 | `src/InterviewAssistant.App/UiState/ConnectionToastGate.cs` | `app/src/main/java/com/interviewassistant/app/uistate/ConnectionToastGate.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 40 | `src/InterviewAssistant.App/UiState/DirtyStateGuard.cs` | `app/src/main/java/com/interviewassistant/app/uistate/DirtyStateGuard.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 41 | `src/InterviewAssistant.App/UiState/ErrorCopy.cs` | `app/src/main/java/com/interviewassistant/app/uistate/ErrorCopy.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 42 | `src/InterviewAssistant.App/UiState/FocusPlacement.cs` | `app/src/main/java/com/interviewassistant/app/uistate/FocusPlacement.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 43 | `src/InterviewAssistant.App/UiState/HealthCheck.cs` | `app/src/main/java/com/interviewassistant/app/uistate/HealthCheck.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 44 | `src/InterviewAssistant.App/UiState/HistoryCounter.cs` | `app/src/main/java/com/interviewassistant/app/uistate/HistoryCounter.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 45 | `src/InterviewAssistant.App/UiState/HomeRoute.cs` | `app/src/main/java/com/interviewassistant/app/uistate/HomeRoute.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 46 | `src/InterviewAssistant.App/UiState/LiveChrome.cs` | `app/src/main/java/com/interviewassistant/app/uistate/LiveChrome.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 47 | `src/InterviewAssistant.App/UiState/LocalData.cs` | `app/src/main/java/com/interviewassistant/app/uistate/LocalData.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 48 | `src/InterviewAssistant.App/UiState/PendingReset.cs` | `app/src/main/java/com/interviewassistant/app/uistate/PendingReset.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 49 | `src/InterviewAssistant.App/UiState/QuickHelp.cs` | `app/src/main/java/com/interviewassistant/app/uistate/QuickHelp.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 50 | `src/InterviewAssistant.App/UiState/Readiness.cs` | `app/src/main/java/com/interviewassistant/app/uistate/Readiness.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 51 | `src/InterviewAssistant.App/UiState/ReadinessPresentation.cs` | `app/src/main/java/com/interviewassistant/app/uistate/ReadinessPresentation.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 52 | `src/InterviewAssistant.App/UiState/ReadinessVerdict.cs` | `app/src/main/java/com/interviewassistant/app/uistate/ReadinessVerdict.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 53 | `src/InterviewAssistant.App/UiState/SectionState.cs` | `app/src/main/java/com/interviewassistant/app/uistate/SectionState.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 54 | `src/InterviewAssistant.App/UiState/SettingsCategories.cs` | `app/src/main/java/com/interviewassistant/app/uistate/SettingsCategories.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 55 | `src/InterviewAssistant.App/UiState/SettingsDefaults.cs` | `app/src/main/java/com/interviewassistant/app/uistate/SettingsDefaults.kt` | Mirror | WPF-free pure logic — mirrored 1:1, unit-parity tested |
| 56 | `src/InterviewAssistant.App/ViewModels/MainViewModel.cs` | `app/src/main/java/com/interviewassistant/app/viewmodels/MainViewModel.kt` | Mirror | All state/commands mirrored 1:1; StateFlow replaces INotifyPropertyChanged |
| 57 | `src/InterviewAssistant.App/ViewModels/ObservableObject.cs` | `— (no target)` | Not-Needed | Framework plumbing (INotifyPropertyChanged); Compose/StateFlow provides it |
| 58 | `src/InterviewAssistant.App/Views/Accessible.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/Accessible.kt` | Platform | UIA peers/live regions → Compose semantics + TalkBack live regions |
| 59 | `src/InterviewAssistant.App/Views/AppDialog.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/AppDialog.kt` | Platform | In-window dialog host → Compose Dialog with identical backdrop/token rules |
| 60 | `src/InterviewAssistant.App/Views/Converters.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/Converters.kt` | Platform | Value converters → pure formatting functions (logic unchanged) |
| 61 | `src/InterviewAssistant.App/Views/DeleteAccountDialog.cs` | `app/src/main/java/com/interviewassistant/app/ui/home/account/DeleteAccountDialog.kt` | Platform | Two-step flow incl. subscription-blocks rule (§3) |
| 62 | `src/InterviewAssistant.App/Views/Dialogs.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/Dialogs.kt` | Platform | Confirm/alert builders; destructive-confirm semantics identical |
| 63 | `src/InterviewAssistant.App/Views/ExportChoiceList.xaml` | `app/src/main/java/com/interviewassistant/app/ui/components/ExportChoiceList.kt` | Platform | Radio choice cards; merged with its code-behind |
| 64 | `src/InterviewAssistant.App/Views/ExportChoiceList.xaml.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/ExportChoiceList.kt` | Platform | Merged into ExportChoiceList.kt (same file as XAML part) |
| 65 | `src/InterviewAssistant.App/Views/ExportDialog.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/ExportDialog.kt` | Platform |  |
| 66 | `src/InterviewAssistant.App/Views/FormAssist.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/FormAssist.kt` | Platform |  |
| 67 | `src/InterviewAssistant.App/Views/FormField.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/FormField.kt` | Platform | FormField template → composable with same label/InfoTip/accessory/hint layout |
| 68 | `src/InterviewAssistant.App/Views/InfoTip.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/InfoTip.kt` | Platform |  |
| 69 | `src/InterviewAssistant.App/Views/InlineMessage.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/InlineMessage.kt` | Platform |  |
| 70 | `src/InterviewAssistant.App/Views/Localization.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/Localization.kt` | Mirror | LocFlow/RTL direction logic mirrored; drives LocalLayoutDirection |
| 71 | `src/InterviewAssistant.App/Views/Popover.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/Popover.kt` | Platform | Close-on-outside/deactivate/move semantics → Compose Popup with same rules |
| 72 | `src/InterviewAssistant.App/Views/PreInterviewPage.xaml` | `app/src/main/java/com/interviewassistant/app/ui/preinterview/PreInterviewPage.kt` | Platform | Check page UI (§3) |
| 73 | `src/InterviewAssistant.App/Views/PreInterviewPage.xaml.cs` | `app/src/main/java/com/interviewassistant/app/ui/preinterview/PreInterviewViewModel.kt` | Platform | Check execution + test tools logic |
| 74 | `src/InterviewAssistant.App/Views/RovingRadios.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/RovingRadios.kt` | Platform | Roving-tabindex radio groups → Compose focus/keyboard navigation, arrow-key + RTL rules identical |
| 75 | `src/InterviewAssistant.App/Views/SettingsPage.xaml` | `app/src/main/java/com/interviewassistant/app/ui/settings/SettingsPage.kt` | Platform | Settings page UI, 7 categories (§3) |
| 76 | `src/InterviewAssistant.App/Views/SettingsPage.xaml.cs` | `app/src/main/java/com/interviewassistant/app/ui/settings/SettingsViewModel.kt` | Platform | Settings load/save/preview logic; dirty-guard semantics mirrored |
| 77 | `src/InterviewAssistant.App/Views/ToastHost.cs` | `app/src/main/java/com/interviewassistant/app/ui/components/ToastHost.kt` | Platform | Max 3, dedupe, durations 7000/5500/4500/3200 ms mirrored |
| 78 | `src/InterviewAssistant.App/app.manifest` | `app/src/main/AndroidManifest.xml` | Platform | Permissions (RECORD_AUDIO, FGS-microphone, notifications, internet), App Link intent filters; PerMonitorV2 DPI has no equivalent |
| 79 | `src/InterviewAssistant.App/cloud.json` | `app/src/main/assets/cloud.json` | Shared | Same file, same keys; Android consumes apiBaseUrl + updateUrl |
| 80 | `src/InterviewAssistant.Backend/Data/AppDbContext.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 81 | `src/InterviewAssistant.Backend/Data/DesignTimeFactory.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 82 | `src/InterviewAssistant.Backend/Data/Entities.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 83 | `src/InterviewAssistant.Backend/Data/Migrations/20261007121900_InitialCommercialSchema.Designer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 84 | `src/InterviewAssistant.Backend/Data/Migrations/20261007121900_InitialCommercialSchema.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 85 | `src/InterviewAssistant.Backend/Data/Migrations/20261013205108_ProductionHardening.Designer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 86 | `src/InterviewAssistant.Backend/Data/Migrations/20261013205108_ProductionHardening.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 87 | `src/InterviewAssistant.Backend/Data/Migrations/20261016034340_Rc3RealtimeRelay.Designer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 88 | `src/InterviewAssistant.Backend/Data/Migrations/20261016034340_Rc3RealtimeRelay.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 89 | `src/InterviewAssistant.Backend/Data/Migrations/20261016035003_Rc3IdentityDeletionAndAdjustments.Designer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 90 | `src/InterviewAssistant.Backend/Data/Migrations/20261016035003_Rc3IdentityDeletionAndAdjustments.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 91 | `src/InterviewAssistant.Backend/Data/Migrations/20261016041654_Rc3SelfReviewFixes.Designer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 92 | `src/InterviewAssistant.Backend/Data/Migrations/20261016041654_Rc3SelfReviewFixes.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 93 | `src/InterviewAssistant.Backend/Data/Migrations/AppDbContextModelSnapshot.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | EF Core migration — stays server-side; schema is shared, never ported |
| 94 | `src/InterviewAssistant.Backend/Dockerfile` | `— (shared backend, unchanged; Android is a client)` | Shared | Server deployment unchanged |
| 95 | `src/InterviewAssistant.Backend/Endpoints/ApiEndpoints.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 96 | `src/InterviewAssistant.Backend/Endpoints/PublicPages.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 97 | `src/InterviewAssistant.Backend/Infrastructure/ApiException.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 98 | `src/InterviewAssistant.Backend/Infrastructure/JwksProvider.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 99 | `src/InterviewAssistant.Backend/Infrastructure/Middleware.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 100 | `src/InterviewAssistant.Backend/Infrastructure/Options.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 101 | `src/InterviewAssistant.Backend/InterviewAssistant.Backend.csproj` | `— (shared backend, unchanged; Android is a client)` | Shared | Server build unchanged |
| 102 | `src/InterviewAssistant.Backend/Program.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 103 | `src/InterviewAssistant.Backend/Properties/launchSettings.json` | `— (dev-only VS launch profiles; Android dev uses run configs)` | Not-Needed | Dev-only file, no product behaviour |
| 104 | `src/InterviewAssistant.Backend/Services/AccountService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 105 | `src/InterviewAssistant.Backend/Services/AnswerProxy.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 106 | `src/InterviewAssistant.Backend/Services/Billing.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 107 | `src/InterviewAssistant.Backend/Services/ConfigService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 108 | `src/InterviewAssistant.Backend/Services/DeviceService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 109 | `src/InterviewAssistant.Backend/Services/EntitlementService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 110 | `src/InterviewAssistant.Backend/Services/IdentityAdmin.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 111 | `src/InterviewAssistant.Backend/Services/RealtimeRelay.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 112 | `src/InterviewAssistant.Backend/Services/RealtimeSecretIssuer.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 113 | `src/InterviewAssistant.Backend/Services/SessionService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 114 | `src/InterviewAssistant.Backend/Services/TranscriptionPrompt.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 115 | `src/InterviewAssistant.Backend/Services/UsageService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 116 | `src/InterviewAssistant.Backend/Services/UserService.cs` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 117 | `src/InterviewAssistant.Backend/appsettings.json` | `— (shared backend, unchanged; Android is a client)` | Shared | Runs unchanged on the server; Android consumes it via /api/v1 |
| 118 | `src/InterviewAssistant.Client/Auth.cs` | `client/src/main/java/com/interviewassistant/client/Auth.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 119 | `src/InterviewAssistant.Client/BackendClient.cs` | `client/src/main/java/com/interviewassistant/client/BackendClient.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 120 | `src/InterviewAssistant.Client/CloudAnswerProvider.cs` | `client/src/main/java/com/interviewassistant/client/CloudAnswerProvider.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 121 | `src/InterviewAssistant.Client/CloudOptions.cs` | `client/src/main/java/com/interviewassistant/client/CloudOptions.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 122 | `src/InterviewAssistant.Client/CloudSession.cs` | `client/src/main/java/com/interviewassistant/client/CloudSession.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 123 | `src/InterviewAssistant.Client/InstallationIdentity.cs` | `client/src/main/java/com/interviewassistant/client/InstallationIdentity.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 124 | `src/InterviewAssistant.Client/InterviewAssistant.Client.csproj` | `— (replaced by Gradle build script of the target module)` | Not-Needed | Build-system file only; target module :client |
| 125 | `src/InterviewAssistant.Client/LoopbackRedirect.cs` | `app/src/main/java/com/interviewassistant/app/auth/AppLinkRedirect.kt` | Platform | Loopback HTTP listener impossible/unsafe on Android → Custom Tab + verified App Link redirect; PKCE/state/timeout semantics identical (D5) |
| 126 | `src/InterviewAssistant.Client/Pkce.cs` | `client/src/main/java/com/interviewassistant/client/Pkce.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 127 | `src/InterviewAssistant.Client/UpdatePolicy.cs` | `client/src/main/java/com/interviewassistant/client/UpdatePolicy.kt` | Mirror | Client logic mirrored 1:1 (see audit-backend §10) |
| 128 | `src/InterviewAssistant.Contracts/ApiContracts.cs` | `contracts/src/main/java/com/interviewassistant/contracts/ApiContracts.kt` | Shared | DTO definitions mirrored field-for-field; wire format (PascalCase JSON names, defaults, -1 sentinels) identical — see §4/§5 |
| 129 | `src/InterviewAssistant.Contracts/InterviewAssistant.Contracts.csproj` | `— (replaced by Gradle build script of the target module)` | Not-Needed | Build-system file only |
| 130 | `src/InterviewAssistant.Core/Audio/AcousticAnalyzer.cs` | `core/src/main/java/com/interviewassistant/core/audio/AcousticAnalyzer.kt` | Mirror |  |
| 131 | `src/InterviewAssistant.Core/Audio/PcmConverter.cs` | `core/src/main/java/com/interviewassistant/core/audio/PcmConverter.kt` | Mirror |  |
| 132 | `src/InterviewAssistant.Core/Diagnostics/Backoff.cs` | `core/src/main/java/com/interviewassistant/core/diagnostics/Backoff.kt` | Mirror |  |
| 133 | `src/InterviewAssistant.Core/Diagnostics/Clock.cs` | `core/src/main/java/com/interviewassistant/core/diagnostics/Clock.kt` | Mirror |  |
| 134 | `src/InterviewAssistant.Core/Diagnostics/InstallLayout.cs` | `— (no target)` | Not-Needed | Velopack install-layout detection; Android install state comes from PackageManager inside the update flow (D4) |
| 135 | `src/InterviewAssistant.Core/Diagnostics/Metrics.cs` | `core/src/main/java/com/interviewassistant/core/diagnostics/Metrics.kt` | Mirror |  |
| 136 | `src/InterviewAssistant.Core/Domain/Models.cs` | `core/src/main/java/com/interviewassistant/core/domain/Models.kt` | Mirror |  |
| 137 | `src/InterviewAssistant.Core/Domain/ProfileMigration.cs` | `core/src/main/java/com/interviewassistant/core/domain/ProfileMigration.kt` | Mirror |  |
| 138 | `src/InterviewAssistant.Core/Domain/ProfileService.cs` | `core/src/main/java/com/interviewassistant/core/domain/ProfileService.kt` | Mirror |  |
| 139 | `src/InterviewAssistant.Core/Ingestion/DocumentTextExtractor.cs` | `core/src/main/java/com/interviewassistant/core/ingestion/DocumentTextExtractor.kt` | Mirror |  |
| 140 | `src/InterviewAssistant.Core/Ingestion/JobDescriptionParser.cs` | `core/src/main/java/com/interviewassistant/core/ingestion/JobDescriptionParser.kt` | Mirror |  |
| 141 | `src/InterviewAssistant.Core/Ingestion/PromptInjectionGuard.cs` | `core/src/main/java/com/interviewassistant/core/ingestion/PromptInjectionGuard.kt` | Mirror |  |
| 142 | `src/InterviewAssistant.Core/Ingestion/ResumeParser.cs` | `core/src/main/java/com/interviewassistant/core/ingestion/ResumeParser.kt` | Mirror |  |
| 143 | `src/InterviewAssistant.Core/Ingestion/UploadValidator.cs` | `core/src/main/java/com/interviewassistant/core/ingestion/UploadValidator.kt` | Mirror |  |
| 144 | `src/InterviewAssistant.Core/Intelligence/AnswerPlanner.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/AnswerPlanner.kt` | Mirror |  |
| 145 | `src/InterviewAssistant.Core/Intelligence/AnswerStyle.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/AnswerStyle.kt` | Mirror |  |
| 146 | `src/InterviewAssistant.Core/Intelligence/BulletStreamParser.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/BulletStreamParser.kt` | Mirror |  |
| 147 | `src/InterviewAssistant.Core/Intelligence/ContextRetriever.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/ContextRetriever.kt` | Mirror |  |
| 148 | `src/InterviewAssistant.Core/Intelligence/EvidenceIndex.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/EvidenceIndex.kt` | Mirror |  |
| 149 | `src/InterviewAssistant.Core/Intelligence/FactValidator.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/FactValidator.kt` | Mirror |  |
| 150 | `src/InterviewAssistant.Core/Intelligence/FollowUpIntent.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/FollowUpIntent.kt` | Mirror |  |
| 151 | `src/InterviewAssistant.Core/Intelligence/PersonalGrounding.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/PersonalGrounding.kt` | Mirror |  |
| 152 | `src/InterviewAssistant.Core/Intelligence/PromptBuilder.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/PromptBuilder.kt` | Mirror |  |
| 153 | `src/InterviewAssistant.Core/Intelligence/PromptEchoDetector.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/PromptEchoDetector.kt` | Mirror |  |
| 154 | `src/InterviewAssistant.Core/Intelligence/QuestionClassifier.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/QuestionClassifier.kt` | Mirror |  |
| 155 | `src/InterviewAssistant.Core/Intelligence/QuestionExtractor.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/QuestionExtractor.kt` | Mirror |  |
| 156 | `src/InterviewAssistant.Core/Intelligence/QuestionMatcher.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/QuestionMatcher.kt` | Mirror |  |
| 157 | `src/InterviewAssistant.Core/Intelligence/SpeechPlausibility.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/SpeechPlausibility.kt` | Mirror |  |
| 158 | `src/InterviewAssistant.Core/Intelligence/TextNormalizer.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/TextNormalizer.kt` | Mirror |  |
| 159 | `src/InterviewAssistant.Core/Intelligence/UtteranceGate.cs` | `core/src/main/java/com/interviewassistant/core/intelligence/UtteranceGate.kt` | Mirror |  |
| 160 | `src/InterviewAssistant.Core/InterviewAssistant.Core.csproj` | `— (replaced by Gradle build script of the target module)` | Not-Needed | Build-system file only; target module :core. PdfPig dependency → §8 D6 |
| 161 | `src/InterviewAssistant.Core/Knowledge/KnowledgeBase.cs` | `core/src/main/java/com/interviewassistant/core/knowledge/KnowledgeBase.kt` | Mirror |  |
| 162 | `src/InterviewAssistant.Core/Knowledge/KnowledgeModels.cs` | `core/src/main/java/com/interviewassistant/core/knowledge/KnowledgeModels.kt` | Mirror |  |
| 163 | `src/InterviewAssistant.Core/Knowledge/PromptTemplates.cs` | `core/src/main/java/com/interviewassistant/core/knowledge/PromptTemplates.kt` | Mirror |  |
| 164 | `src/InterviewAssistant.Core/Knowledge/TargetContext.cs` | `core/src/main/java/com/interviewassistant/core/knowledge/TargetContext.kt` | Mirror |  |
| 165 | `src/InterviewAssistant.Core/Languages/LanguageDetector.cs` | `core/src/main/java/com/interviewassistant/core/languages/LanguageDetector.kt` | Mirror |  |
| 166 | `src/InterviewAssistant.Core/Languages/LanguageRegistry.cs` | `core/src/main/java/com/interviewassistant/core/languages/LanguageRegistry.kt` | Mirror |  |
| 167 | `src/InterviewAssistant.Core/Languages/Loc.cs` | `core/src/main/java/com/interviewassistant/core/languages/Loc.kt` | Mirror |  |
| 168 | `src/InterviewAssistant.Core/Languages/UiStrings.cs` | `core/src/main/java/com/interviewassistant/core/languages/UiStrings.kt` | Mirror |  |
| 169 | `src/InterviewAssistant.Core/Languages/i18n/ar.json` | `core/src/main/assets/i18n/ar.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 170 | `src/InterviewAssistant.Core/Languages/i18n/de.json` | `core/src/main/assets/i18n/de.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 171 | `src/InterviewAssistant.Core/Languages/i18n/en.json` | `core/src/main/assets/i18n/en.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 172 | `src/InterviewAssistant.Core/Languages/i18n/es.json` | `core/src/main/assets/i18n/es.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 173 | `src/InterviewAssistant.Core/Languages/i18n/fa.json` | `core/src/main/assets/i18n/fa.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 174 | `src/InterviewAssistant.Core/Languages/i18n/fr.json` | `core/src/main/assets/i18n/fr.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 175 | `src/InterviewAssistant.Core/Languages/i18n/hi.json` | `core/src/main/assets/i18n/hi.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 176 | `src/InterviewAssistant.Core/Languages/i18n/it.json` | `core/src/main/assets/i18n/it.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 177 | `src/InterviewAssistant.Core/Languages/i18n/pt.json` | `core/src/main/assets/i18n/pt.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 178 | `src/InterviewAssistant.Core/Languages/i18n/zh.json` | `core/src/main/assets/i18n/zh.json` | Shared | Copied byte-for-byte; same keys, same en-fallback (Loc) |
| 179 | `src/InterviewAssistant.Core/Live/CoachOutput.cs` | `core/src/main/java/com/interviewassistant/core/live/CoachOutput.kt` | Mirror |  |
| 180 | `src/InterviewAssistant.Core/Live/CoachPrompt.cs` | `core/src/main/java/com/interviewassistant/core/live/CoachPrompt.kt` | Mirror |  |
| 181 | `src/InterviewAssistant.Core/Live/InterruptionTracker.cs` | `core/src/main/java/com/interviewassistant/core/live/InterruptionTracker.kt` | Mirror |  |
| 182 | `src/InterviewAssistant.Core/Live/SessionMemory.cs` | `core/src/main/java/com/interviewassistant/core/live/SessionMemory.kt` | Mirror |  |
| 183 | `src/InterviewAssistant.Core/Orchestration/EngineModels.cs` | `core/src/main/java/com/interviewassistant/core/orchestration/EngineModels.kt` | Mirror |  |
| 184 | `src/InterviewAssistant.Core/Orchestration/InterviewEngine.cs` | `core/src/main/java/com/interviewassistant/core/orchestration/InterviewEngine.kt` | Mirror |  |
| 185 | `src/InterviewAssistant.Core/Persistence/ExportFile.cs` | `core/src/main/java/com/interviewassistant/core/persistence/ExportFile.kt` | Mirror |  |
| 186 | `src/InterviewAssistant.Core/Persistence/ProfileBundle.cs` | `core/src/main/java/com/interviewassistant/core/persistence/ProfileBundle.kt` | Mirror |  |
| 187 | `src/InterviewAssistant.Core/Persistence/SecretFilter.cs` | `core/src/main/java/com/interviewassistant/core/persistence/SecretFilter.kt` | Mirror |  |
| 188 | `src/InterviewAssistant.Core/Persistence/WorkspaceLocator.cs` | `core/src/main/java/com/interviewassistant/core/persistence/WorkspaceLocator.kt` | Mirror |  |
| 189 | `src/InterviewAssistant.Core/Persistence/WorkspaceStore.cs` | `core/src/main/java/com/interviewassistant/core/persistence/WorkspaceStore.kt` | Mirror |  |
| 190 | `src/InterviewAssistant.Core/Preparation/PreparationPipeline.cs` | `core/src/main/java/com/interviewassistant/core/preparation/PreparationPipeline.kt` | Mirror |  |
| 191 | `src/InterviewAssistant.Core/Preparation/QuestionFactory.cs` | `core/src/main/java/com/interviewassistant/core/preparation/QuestionFactory.kt` | Mirror |  |
| 192 | `src/InterviewAssistant.Core/Providers/Contracts.cs` | `core/src/main/java/com/interviewassistant/core/providers/Contracts.kt` | Mirror |  |
| 193 | `src/InterviewAssistant.Core/Providers/OpenAiAuthProbe.cs` | `core/src/main/java/com/interviewassistant/core/providers/OpenAiAuthProbe.kt` | Mirror |  |
| 194 | `src/InterviewAssistant.Core/Providers/OpenAiChatAnswerProvider.cs` | `core/src/main/java/com/interviewassistant/core/providers/OpenAiChatAnswerProvider.kt` | Mirror |  |
| 195 | `src/InterviewAssistant.Core/Providers/OpenAiRealtimeTranscriber.cs` | `core/src/main/java/com/interviewassistant/core/providers/OpenAiRealtimeTranscriber.kt` | Mirror |  |
| 196 | `src/InterviewAssistant.Core/Reports/AccountExportRenderer.cs` | `core/src/main/java/com/interviewassistant/core/reports/AccountExportRenderer.kt` | Mirror |  |
| 197 | `src/InterviewAssistant.Core/Reports/ExportLabels.cs` | `core/src/main/java/com/interviewassistant/core/reports/ExportLabels.kt` | Mirror |  |
| 198 | `src/InterviewAssistant.Core/Reports/HtmlReportRenderer.cs` | `core/src/main/java/com/interviewassistant/core/reports/HtmlReportRenderer.kt` | Mirror |  |
| 199 | `src/InterviewAssistant.Core/Reports/InterviewReport.cs` | `core/src/main/java/com/interviewassistant/core/reports/InterviewReport.kt` | Mirror |  |
| 200 | `src/InterviewAssistant.Core/Reports/ProfileExportRenderer.cs` | `core/src/main/java/com/interviewassistant/core/reports/ProfileExportRenderer.kt` | Mirror |  |
| 201 | `src/InterviewAssistant.Core/Reports/ReportLabels.cs` | `core/src/main/java/com/interviewassistant/core/reports/ReportLabels.kt` | Mirror |  |
| 202 | `src/InterviewAssistant.Core/Turn/ConversationMemory.cs` | `core/src/main/java/com/interviewassistant/core/turn/ConversationMemory.kt` | Mirror |  |
| 203 | `src/InterviewAssistant.Core/Turn/DuplicateGuard.cs` | `core/src/main/java/com/interviewassistant/core/turn/DuplicateGuard.kt` | Mirror |  |
| 204 | `src/InterviewAssistant.Core/Turn/TurnDetector.cs` | `core/src/main/java/com/interviewassistant/core/turn/TurnDetector.kt` | Mirror |  |

---

## 3. Screen-by-screen UI mapping

Routes keep the Windows route strings (`HomeRoute.Parse("page[/section]")`): `start, profiles, interviews, preinterview, reports, account, settings, help` and `settings/<section>` for the 7 settings sections (`audio, answers, listening, window, hotkeys, privacy, updates`). Deep links resolve before first composition, exactly as on Windows; unknown page → `start`; unknown section dropped. The signed-out gate is identical: cloud-configured + signed out + no workspace ⇒ `profiles|interviews|reports` rewrite to `account`. Guarded navigation (Save/Discard/Stay across all dirty editors; reasons Navigate/SwitchItem/CloseWindow/SignOut/ReloadData prompt, LanguageChange/Resize/ThemeChange/ResourceRefresh/ListRefresh never prompt) is mirrored in the Android NavHost via the mirrored `DirtyStateGuard`/navigation-guard logic. Language switch is not navigation: all open screens relocalize in place, drafts and dirty state preserved, RTL flips via `LocalLayoutDirection`.

### 3.1 Design tokens carried over exactly (from `Themes/Theme.xaml`, audit-app §1)

Dark-only theme. All values below are transcribed verbatim (ARGB → Compose `Color`, WPF device-independent px → dp, font sizes → sp at the same numeric value).

| Token | Value | Token | Value |
|---|---|---|---|
| Bg | `#FF0F1115` | AccentStrong | `#FF3567D0` |
| Surface | `#FF161920` | InputBorder | `#FF5C6577` |
| Surface2 | `#FF1D212A` | Placeholder | `#FF8F96A4` |
| Surface3 | `#FF252A35` | Listening | `#FF3DD68C` |
| Border | `#FF2A303C` | Thinking | `#FFF2B44C` |
| Text | `#FFECEEF2` | Error | `#FFEF6B6B` |
| TextDim | `#FFA3A9B5` | ErrorSoft | `#40EF6B6B` |
| TextFaint | `#FF8B92A0` | ErrorStrong | `#FFB3373A` |
| Accent | `#FF5B8DEF` | ThinkingSoft | `#1FF2B44C` |
| AccentSoft | `#335B8DEF` | Backdrop | `#B3080A0E` |

- **Radii:** Card 12, Control 8, FocusRing 11 (2 dp ring drawn 3 dp outside), empty-state icon disc 28 (56 dp disc), readiness badge 10. **No pill/capsule shapes anywhere** — segmented control, chips and buttons all use radius 8.
- **Sizes:** control/button height 36, compact button 30, multiline min 72, multiline tall 170, input font 13.5, border 1, input padding 10×6, multiline padding 10×8, button padding 14×6 (base 12×6, ghost 8×6, link 6×2, chip 11×5, form-primary/danger 16×6), card padding 18×14, page margin 8/0/16/8, page max width 820, content max width 1120, reading max 760, split max 940, form max 620, short field 320, side pane 240, pane gap 20, pane min 260, column gap 12, section margin 0/24/0/8, field-label margin 0/12/0/6, live readable column max 980.
- **Shadow:** blur 24, depth 4, direction 270°, opacity 0.35 black → Compose elevation/shadow approximation with the same visual weight (cosmetic only).
- **Text styles:** Label 11 SemiBold TextFaint; Body 13 TextDim; Hint 12 TextFaint; MetaKey/MetaValue 12.5 (TextDim/Text); Icon 14; EmptyTitle 15 SemiBold Text; H1 20 SemiBold (Display); Section = Label + section margin, heading semantics level 2.
- **Control styles (all mirrored as composables):** Card, Btn.Base (12.5, disabled opacity 0.4), Btn.Primary (AccentStrong, white, SemiBold), Btn.Danger (ErrorStrong, white, SemiBold), Btn.Ghost, Btn.Link (Accent; hover/pressed → Text), Btn.Chip, Btn.Form/FormPrimary/FormDanger, Input, Input.Multiline, Input.Bare, PasswordInput, Combo + chevron-only toggle (popup items 13), Check, ListItem.Row (padding 10×7), Seg.Track (Surface2, radius 8, padding 3, min height 36) + Seg.Item (13; checked = accent fill, white, SemiBold), Divider, EmptyState.Icon, FocusRing, slim ScrollBar equivalent, ToolTip (Surface3), FormField (label row: label + InfoTip + right accessory; hint; short-field width rule). High-contrast mode: Windows swaps to system colours → Android maps to the platform high-contrast/accessibility palette in the same states.
- **Answer/question typography (from `AppSettings.FontPreset`, not the theme file):** Answer 16/19/**22**/26 and Question 14/16/**17**/19 for Compact/Normal/**Large (default)**/ExtraLarge; answer line height = font × 1.42 (× 1.24 compact); bullet margin 14 (6 compact); Coach font = answer × 1.18; auto-fit minimum = max(13, preset × 0.7).
- **Typeface/icons (cosmetic Platform substitution, §8 D11):** Segoe UI Variable → platform sans (Roboto) at identical sizes/weights; Segoe Fluent glyph code points (play `U+E768`, stop `U+E71A`, help `U+E897`, more `U+E712`, min `U+E921`, max `U+E922`/restore `U+E923`, close `U+E8BB`, home `U+E80F`, send `U+E724`, check `U+E73E`, warning `U+E7BA`, cross `U+E711`, sync `U+E895`, clock `U+E823`, unknown `U+E9CE`, chevron `U+E70D`) → vector drawables, one drawable per glyph, same placement/size.

### 3.2 Destination map — every Windows surface → Compose destination

| # | Windows surface | Compose destination | Notes (behaviour identical; audit-app §2 is normative) |
|---|---|---|---|
| 1 | **MainWindow — Live Assistant** (borderless 720×560, min 440×260, topmost) | `LiveScreen` (single-activity destination; compact/floating behaviour via PiP, D8/D9) | Header (readiness btn, status pill + pulsing dot, level meter max 56, adaptive badge, Answer/Coach chip, minutes, Start/Pause/Resume, Stop, Help, More), service banner, setup empty state, question card, live transcript, waiting line, answer card (bullets / Coach keywords+structure+reminder / profile-gap card), thinking dots, action bar (Shorter/Technical/Example/Full/Regenerate, Previous/counter/Next), manual bar (input + send, Enter submits, same-height rule), toasts, in-app dialogs. Status mapping keys `status_*`/`detail_*` identical. |
| 2 | **HomeWindow shell** (1040×720, min 820×560; sidebar nav; account-menu overlay; settings footer) | `HomeScreen` + NavHost | 8 routes; account menu (avatar initial rule, order Manage/Settings/Switch/Sign out, arrow keys wrap); settings footer with dirty dot + Discard/Save shown only on Settings; toasts + dialog host. |
| 3 | Home — **Get started** (`PageStart`) | `StartPage` | Ack card (`authorized_use` + checkbox; after ack only `gs_ack_note`), 4 step cards with per-step links, exactly one primary action (SetupPlanner), developer-mode button (developer builds only), readiness footer (Run all / Start interview). |
| 4 | Home — **Profiles** (`PageProfiles`) | `ProfilesPage` | List + detail two-pane; Import résumé / Add document / New empty / Export… / Import backup / Delete (danger); name+preferred, headline, dirty indicator; segmented Facts/Personal tabs (roving, equal width, arrow keys); fact filters All/Review/Confirmed, fact rows with confirm/edit/delete, Confirm all; personal fields generated from the 21 categories; Save + message. |
| 5 | Home — **Interviews** (`PageInterviews`) | `InterviewsPage` | List + detail; New/Delete; job title, company, JD multiline, interview/answer language (+ separation hint), Import file, documents-are-data notice, readiness card, preparation status + progress, Prepare/Save/Start interview/Check, profile selector, dirty + message. |
| 6 | Home — **Pre-interview check** (`PagePreInterview` + `PreInterviewPage`) | `PreInterviewPage` | Summary card (badge radius 10, "n of m passed", checked-at), blockers panel (+ typed-still-work line), 8 check rows with fix buttons, test tools (audio/STT/AI) with meter, tool card + collapsible technical details. Refused while a session is active. |
| 7 | Home — **Reports** (`PageReports`) | `ReportsPage` | List (≈0.32 width share on wide layouts) + preview pane; Open / Copy as text / Save as HTML / Delete; empty and select states. Report content rule: questions + suggestions shown, **no scores, no hiring predictions**. |
| 8 | Home — **Account** (`PageAccount`, signed-out state) | `AccountPage` (auth card) | Responsive hero+form (form ≤460, total ≤940 on wide); 5-mode auth state machine (SignIn/CreateAccount/ForgotPassword/ForgotSent/SwitchAccount) with the exact fields, primary actions, busy keys, per-field errors, 60 s resend cooldown, OAuth provider buttons (google→Google, azure→Microsoft, github→GitHub, apple→Apple), legal sentence from server URLs (https-only). |
| 9 | Home — **Account** (signed-in dashboard) | `AccountPage` (dashboard) | 1 col < 720 dp content, 2 col ≥ 720; Plan & usage card (metrics + progress bars + billing-state badge tones), Upgrade (poll 5 s × up to 15 min), Manage billing, Devices card (this-device marker, CanRemove rule), Security card (change password with re-auth), Privacy card (export), Danger zone (delete), Refresh, Switch account, developer mode. Sections load independently with stale-data notes (`acct_section_stale`). |
| 10 | Home — **Settings** (`PageSettings` + `SettingsPage`) | `SettingsPage` | Category list (accent bar, Up/Down clamped) + pane; explicit Save/Discard (no auto-save); per-category reset + Restore-all with destructive confirm; hotkey validation failure jumps to Hotkeys category and blocks navigation. 7 category panels below (#11–17). |
| 11 | Settings — **Audio** (`CatAudio`) | `SettingsPage/AudioSection` | Playback-device list (first = system default) + refresh; live level meter (max 200); selecting a device previews it live; Discard reverts the preview. On Android the list is capture routes (built-in mic, wired headset, Bluetooth) — D1. |
| 12 | Settings — **Answers** (`CatAnswers`) | `SettingsPage/AnswersSection` | Developer key panel (developer builds only), answer-model editable combo, STT-model editable combo, protocol combo (auto/…), font-size combo (Compact/Normal/Large/ExtraLarge), FastCache checkbox. |
| 13 | Settings — **Listening** (`CatListening`) | `SettingsPage/ListeningSection` | VAD/end-wait slider **0.6–1.8, step 0.1, snap**, faster/waits-longer end labels (= `VadSensitivity`, turn-settle multiplier). |
| 14 | Settings — **Window** (`CatWindow`) | `SettingsPage/WindowSection` | Opacity slider **0.75–1.0, step 0.05, snap** and Topmost checkbox are persisted identically but take effect through the Android substitutes (D8); Compact mode + Auto-fit remain Live More-menu/VM toggles in this reset category. |
| 15 | Settings — **Hotkeys** (`CatHotkeys`) | `SettingsPage/ShortcutsSection` | 3 editable shortcut fields (Toggle/Start/Manual) + inline errors; Coach + Reset-adaptive fixed values remain conflict references; validation = parse + conflict vs all 5. Android binds them to the D2 substitutes; the stored strings and defaults (`Ctrl+Alt+Space/L/Q/C/R`) are preserved verbatim for cross-platform settings parity. |
| 16 | Settings — **Privacy** (`CatPrivacy`) | `SettingsPage/PrivacySection` | Save-transcript checkbox (default **off**) + hint. |
| 17 | Settings — **Updates** (`CatUpdates`) | `SettingsPage/UpdatesSection` | Version/channel/build rows, channel combo (stable/beta), Check updates, Restart-to-update (visible only when an update is ready), status live region, install note. Never checks/applies during a live session (D4). |
| 18 | **ExportChoiceList** (radio choice cards) | `ExportChoiceList` composable | Card template (Surface2, radius 8, padding 12×10, 18 dp ring + 8 dp dot, whole card is the target, roving group). Used by account export (2 choices) and profile export (3 choices). |
| 19 | **Dialogs** (`AppDialog`, `Dialogs`, `DeleteAccountDialog`, confirm/alert builders) | Compose `Dialog` set | Authorized-use confirm (Continue default), destructive confirms, Interview Focus first-use dialog (radio presets TopCentre/TopLeft/TopRight), New-password dialog (≥ 8 chars + match), Delete-account 2-step (step 1 items + subscription-blocks → Manage billing instead; step 2 re-auth), post-delete local-data dialog (Keep default / Delete), export-format dialog, import-backup preview (Import default), plain alerts. Backdrop `#B3080A0E`, in-app only — no OS message boxes in product flows. Escape/back = cancel semantics, focus restored. |
| 20 | **Popovers** (`Popover`, QuickHelp, More menu, Account menu) | Compose `Popup`/sheet equivalents | Close on outside tap / deactivate / move / resize / state change; Escape closes + restores focus. QuickHelp content by state (SignedOut 2 lines, Setup 3, Listening 3, Paused 2, + answer section 2 when shown, + configured-shortcut rows, + Full help → `help`); auto-opens once (`LiveIntroDismissed`). More menu rows: Compact ✓, Coach ✓, Focus, Focus restore, Home, Last report, Settings, Minutes. |
| 21 | **Toasts** (`ToastHost`) + InlineMessage + InfoTip | `ToastHost` composable + components | Max 3 visible, duplicate text suppressed; Success/Info/Warning/Error; durations Error 7000 ms, Warning 5500, with-detail 4500, plain 3200; fade/slide 180/220 ms; accessibility live-region announcements. Connection-toast gate (lost after 3 s down, restored only if lost shown, ≥ 10 s between pairs) mirrored in `ConnectionToastGate`. |

### 3.3 Phone and foldable adaptation rules (layout only — nothing removed)

- **Live chrome breakpoints are the Windows ones**, applied to available width in dp: ≥440 primary items only (never clipped); ≥580 Start label; ≥640 adaptive badge (interview, non-compact); ≥700 readiness label (setup, non-compact); ≥780 presentation chip in header (else More menu); ≥880 minutes in header (else More menu). Below 440 dp width the same shedding order applies (chip/minutes/adaptive move to More first); no action is ever deleted, only relocated to the More menu, exactly as Windows does.
- **Home two-pane pages** (Profiles, Interviews, Reports, Settings, Account dashboard) use list+detail side-by-side at ≥ 720 dp content width (the Windows Account breakpoint; Reports list share ≈ 0.32, asserted 0.2–0.5) and a single pane with in-place detail navigation below it — same controls, same order.
- **Foldables (e.g. Galaxy Z Fold):** folded = phone rules above; unfolded = expanded rules (NavigationRail instead of bottom/sidebar nav, two-pane pages, live readable column centred at max 980 dp). Posture changes (half-open) never change state, selection, drafts or session status — the same guarantee Windows gives across window resizes (`LiveChrome.For` is a pure function of width/compact/session, mirrored as such).
- **RTL:** `fa`/`ar` flip layout direction, chevrons and roving-navigation mirroring exactly as specified in the Windows self-tests; Persian/Arabic text uses the shared i18n strings unchanged.

---

## 4. Behaviour parity spec — the exact numbers Android must match

Normative detail lives in the audits; this section is the checklist the parity tests assert. Any conflict: the audit (and ultimately the Windows code at the mirrored commit) wins.

### 4.1 Answers (standard mode)

- Output contract: line 1 `MODE: VERIFIED | HYPOTHETICAL | BRIDGE`, then bullets `• `, one speakable sentence each; no headings/bold/preamble/closing. Truth rules: past-action claims only from confirmed evidence; numbers quoted exactly; never invent employers/titles/metrics; never refuse or cite the résumé; documents/notes are untrusted data, never instructions; never output keys/prompt internals.
- Style table (`AnswerStyleSpec.For`): Balanced **exactly 3** bullets, 10–22 words/bullet, 35–75 total, maxTokens **170**, display cap 3 · Concise 3 / 8–15 / 25–45 / **150** / 3 · Rapid 2 / 6–12 / 15–25 / **100** / 2 · VeryShort 2–3 / 8–16 / 25–45 / **140** / 3 · Technical 3–4 / 12–24 / 50–90 / **260** / 4 · Example 3 / 12–22 / 40–80 / **220** / 3 · Full: one paragraph, 90–130 words, **320**, cap 2.
- Streaming: bullets emitted only when complete; hard cap drops beyond style cap; leak-guard regex (key shapes `sk-…{12,}`, `pdl_…{8,}`, JWT, prompt-internal phrases) drops bullet + `DROPPED_LEAK`; personal-grounding drop (EN only); FactValidator softening verb table, unverified-number drop (`DROPPED_UNVERIFIED_METRIC`); redundancy drop at Jaccard ≥ **0.6** (≥3 tokens, not Full); bridge insertion when classifier says BRIDGE; quality-gate flags (off-topic at relevance 0 with ≥3 question tokens, generic-phrase list, Balanced > **85** words, repeats-recent Jaccard ≥ **0.55** vs last 4, ignored-evidence).
- FactValidator format checks (Balanced): bullet <6 or >30 words → `BULLET_LENGTH`; count <2 or >4 → `BULLET_COUNT`; total >85 → `TOTAL_LENGTH`. Always-allowed generic numbers/entities lists as in audit-core §1.3.
- Cache (prepared answer) gate — ALL must hold: fast-cache on, bank confidence ≥ **0.7**, language equal, not personal, match High, follow-up ⇒ score ≥ **0.75**, no repeats-story/references-earlier with a provider present. Provider error ⇒ cache fallback only at ≥ Medium, same language, not personal. Personal + no facts ⇒ deterministic guidance, **no model call**. Personal + facts + no provider/all-dropped ⇒ confirmed facts verbatim, Take(3).
- One finalized question ⇒ at most one LLM request; continuation hold **300 ms**; Coach request maxTokens **80**.

### 4.2 Coach Mode

Exactly **3 keywords** (each 1–32 chars), **one** structure containing `→` (≤ 90 chars), optional one-line reminder. Labels `KEYWORDS:`/`STRUCTURE:`/`REMINDER:` stay English; content in the answer language. Keyword splitting on `· • | , ، 、`, arrows `-> => ⇒ ›` normalized to ` → `, letter keywords upper-cased. Prepared path: bank coach keywords (==3) as-is, else deterministic derivation; `FromPrepared` pads with `RESULT`, default structure `Direct answer → approach → result`; mode structures Verified `Situation → what I did → result`, Bridge `Honest bridge → approach → closest evidence`, else `Direct answer → approach → measure`. LLM path: stories Take(2), snippets Take(3), memory recent 3, maxTokens 80, parse failure ⇒ `COACH_FORMAT` flag + local fallback. Engine displays exactly 2 bullets (keyword line joined ` · `, then structure); a Coach answer counts as **25 words** for interruption timing.

### 4.3 Question bank & preparation

- Bank cap **150**: dedupe by canonical fingerprint (first wins); if > 150, order by Confidence descending, Take(150); renumber `T001…`. **There is no code that pads the bank up to 50** — the "50–150" range is a product description, the 50 floor is reached by generation volume only. Android mirrors this exactly (flagged finding, §8 D13).
- `Add()` drops questions with < 2 non-empty bullets; bullets Take(4); keywords distinct Take(14); coach keywords padded `GOAL, APPROACH, RESULT` to 3; factory is English-only (`Language = "en"`); confidences 0.5–0.85 per call site.
- Quotas: requirements Take(30) (≤ 2 questions each), responsibilities Take(20), per-role roles Take(6) newest first, achievements per role Take(2), behavioural = fixed 11 topics (only with a matching story) + 1 failure question (0.5); core = fixed set (intro, why-company, why-hire, strengths-conditional, weakness, first-90-days, questions-for-us, five-years).
- Confidence < **0.7** ⇒ prepared answer is LLM reference only, never shown instantly. Pipeline: `GeneratorVersion = "local-2.0"`, 15 stages; caps — requirements+nice-to-have distinct Take(30), terminology Take(60), vocabulary Take(80), DomainNumbers Take(30), ExtraEntities Take(30), snippets Take(200)/document, responsibilities in guidance Take(6), gaps in truth policy Take(5), difficult questions = gaps Take(6) + 3 fixed; gap = best requirement score < **0.25**. Enricher failure ⇒ warning, local pack kept.

### 4.4 Turn detection, acoustic gate, matching (headline values; full tables in audit-core §1.6–1.7)

- TurnDetector defaults: `QuestionCompleteMs` **600**, `DefaultMs` **800**, `IncompleteMs` **1700**, question-start+`.` ⇒ **700**, `MissingTranscriptTimeoutMs` **4000**, `ContinuationWindowMs` **2500**, `FollowUpReplyAllowanceMs` **1800**, `MaxSegmentsPerTurn` **400**, `MaxTurnChars` **8000**, `MaxPartialChars` **4000** (tail kept), `Sensitivity` default 1.0 (settings slider 0.6–1.8). Restatement containment ≥ **0.8**; restart cues ≤ 3 chained.
- EngineOptions: TickMs **40**, ContinuationHoldMs **300**, NoAudioWarningSeconds **20**, QuietAfterSeconds **6**, HistoryCapacity **500**, EvidenceLeadMs **1000**, LiveTextIntervalMs **120**, LiveTextMaxChars **360**, MinCacheConfidence **0.7**, audio-activity floor **−55 dBFS**.
- DuplicateGuard: capacity **12**, window **30,000 ms**, Jaccard ≥ **0.8** or containment ≥ **0.85** (token diff ≤ 1). PendingContext: 1500 chars / 120,000 ms. ConversationMemory capacity 5 (bullets Take(3), truncate 110 chars). SessionMemory: detailed 12 / total 400, interruptions cap 200, prompt recent 4.
- AcousticAnalyzer: 24 kHz PCM16 → 12 kHz, hop 20 ms, window 40 ms, FFT 512, pitch 80–400 Hz; `ActiveDbfs −50`, `VoicedCorrelation 0.5`, `TonalConcentration 0.97`, `SustainedSimilarity 0.95`, `SteadyPitchDelta 0.006`, `PitchJumpDelta 0.025`. SpeechEvidence: MinVoicedMs **100**, ShortEventMs **1500**, MaxTonalShare **0.2**, MinTonalMs **100**, MaxPitchSteadiness **0.65**, MinPitchPairs **10**, MinRunHops **10**, MaxFlatRunShare **0.75**, UnheardWindowMs **600,000**, incomplete if analysed < 75 % of window. SpeechPlausibility: 35/70 ms per syllable, max 9 syllables/active-second, slack 2. PromptEchoDetector thresholds as audit-core §1.7 (coverage 0.6/0.7/0.8, run ≥5 & ≥70 %, strong-speech VoicedMs ≥ 1500 cancels unless marker+≥3 terms or run ≥8 & ≥90 %).
- Matching (BM25): K1 **1.2**, B **0.6**; High **0.62** / Medium **0.42** / Low **0.25**; score = 0.5·BM25norm + 0.5·min(1, bestPhrasing×1.35), bestPhrasing = 0.55·tokenJaccard + 0.45·trigramJaccard; High demoted if runner-up within **0.03** or topic-inconsistent. Context retrieval: stories ≤ 3, snippets ≤ 6, snippet keep > **0.04**. Evidence support coverage **0.6** (FactValidator **0.5**).
- Interruption adaptation: 2.4 words/s + 1500 ms allowance; calm at ≥ expected × 0.85; event only if confidence ≥ **0.35** (+0.15 if another within 5 min); ≥ 2 events/5 min ⇒ Rapid else Concise; 3 calm answers step down one level.

### 4.5 Providers (developer mode / direct paths; cloud mode goes through the shared backend relay)

- Chat: `https://api.openai.com/v1/chat/completions`, model **`gpt-5.4-mini`**, fallback **`gpt-4.1-mini`**, temperature **0.4** (non-reasoning only), first-token timeout **8 s**, stream idle **20 s**, max stream **90 s**, reasoning-effort mapping and `+600` completion-token rule, `stream:true, store:false`, ≤ 3 attempts (pre-stream BadRequest fixes only). Auth probe: `GET /v1/models`, 10 s.
- Realtime transcription: `wss://api.openai.com/v1/realtime?intent=transcription`, model **`gpt-4o-transcribe`**, fallback **`gpt-4o-mini-transcribe`**, server VAD threshold **0.5**, prefix padding **300 ms**, silence **400 ms**, GA session format with legacy-beta fallback once, rotate after **25 min** idle, audio channel bounded **30** DropOldest, connect 10 s, keep-alive 15 s, receive cap **4 MiB**, backoff 1 s ×2 cap 30 s jitter 0.85–1.15. PCM format: 24 kHz mono PCM16, 100 ms frames (4,800 bytes), base64 `input_audio_buffer.append`.
- Audio conversion: any source format → 24 kHz mono PCM16 (channel average, box-filter/linear resample, clamp [−1,1]); `LevelDb` floor **−100 dBFS**. Signal threshold in capture: RMS > **−50 dBFS**.

### 4.6 Sessions, metering, plans, limits (server-enforced; Android client behaviour must match)

- Heartbeat interval **30 s** (client clamp [5,120]), timeout **90 s**; max session **120 min**; `MaxDurationSeconds = min(7200, remaining)`; heartbeat bills clamp(elapsed, 0, 90) — stretched heartbeats never under-bill; every lease end bills the tail the same way; pause bills nothing (lease ends on pause; resume = new lease); sweeper every 30 s expires leases silent > 90 s; relay window = min(allowance-at-heartbeat, 90) − **5 s**, relay re-check ≤ 2 s, one relay per lease, `MaxSecretsPerSession = 125`.
- Client session driver: start idempotency key reused across retries; retry start on 0/502/503 (except `maintenance`) ≤ 3 attempts, delay 500 ms × attempt; remaining ≤ 300 s warning; ≥ 3 consecutive heartbeat failures ⇒ "unstable" warning; stop POST with 5 s timeout, failures swallowed (sweeper expires).
- Plans (server catalog, read-only for Android): **Trial** — 30 trial minutes (lifetime), MaxProfiles 1, PrepJobsPerMonth 2, MaxDevices 1, MaxConcurrentSessions 1, ReportRetentionDays 14, CoachMode true. **Pro** — LiveMinutesPerPeriod **600**, MaxProfiles 3, PrepJobsPerMonth 20, MaxDevices 2, MaxConcurrentSessions 1, ReportRetentionDays 365, CoachMode true. Past-due grace **7 days**; lapsed if period ended > 3 days without renewal.
- Safety caps: per-account daily live **240 min**, global daily **100,000 min**, answers/day/account **2,000**, global answers/day **1,000,000**, manual answers/day **300**, prep AI requests/job **80**, session starts/hour **240**, abandoned/hour **8**, answers maxTokens clamp [16, **600**], document max **2 MiB** server-side (client upload max **10 MiB**, `UploadValidator`), documents/kind **500**, request body **4 MiB**, transcription prompt max **1,500 chars**, vocab terms Take(80) each ≤ 48 chars.
- Rate limits (server): `answers` 30/min/user, `credentials` 30/min/user, `user` 300/min, `anon` 240/min/IP, `webhooks` 600/min/IP — rejection 429 `rate_limited`. Client-version gate: below `MinimumClientVersion` (default `2.0.0`) ⇒ 426 on `/api/v1/*` except config/webhooks/plans/admin.
- Auth: Supabase PKCE S256 (verifier = Base64Url(48 random bytes) = 64 chars; state = Base64Url(24 bytes)); token refresh when ≤ 60 s to expiry, single-flight; refresh-failure code set ⇒ local sign-out with reason expired/disabled; re-auth window for export/delete/change-password **10 min** server-side (recent-auth), same-user-id rule on password re-auth; password reset: enumeration-safe notice, 60 s resend, link wait up to **15 min**, refused while a different account is signed in; OAuth browser timeout **5 min**.
- Documents/sync: kinds `profile|target|report`, optimistic versioning (409 `version_conflict`), cascade delete, profile limit 402, document limit 429. Installation id: `inst-` + hex(16 random bytes), 12–64 chars alnum/`-`, survives upgrades, not hardware-derived. Device name ≤ 60 chars; Android registers platform string `android` (Windows uses `windows-x64`) with full semver client version.
- Workspace/persistence: account key = first **24** hex of SHA-256(`"ia-workspace:" + userId.trim().lowercase()`); folders `profiles, targets, packs, sessions`; ids ≤ 64 chars ASCII alnum/`-`; atomic `.tmp` write (WriteThrough + flush-to-disk) + move; corrupt ⇒ `.{yyyyMMddHHmmss}.corrupt` quarantine, never deleted. `.iaprofile` bundle: version **1**, file ≤ 64 MiB, entry ≤ 32 MiB, total ≤ 64 MiB, entries ≤ 4000, JSON MaxDepth 64, duplicate properties refused, import always creates new ids + re-runs PromptInjectionGuard. SecretFilter: MaxDepth 96 (parse 128), replacement `[removed]`, fail-closed on regex timeout (2 s); key/value shape lists as audit-core §1.9.
- Uploads: extensions `.pdf` (`%PDF-`), `.docx` (`PK\x03\x04` + `word/document.xml`), `.txt`/`.md` (no NUL, strict UTF-8); DOCX zip: > 2000 entries or > 200 MiB unpacked rejected; PDF with < 20 chars extracted text ⇒ `NeedsOcr` with the verbatim error string. Personal fact text cap **600 chars**. Fact parser confidences: summary 0.9, role 0.9 (0.5 missing title/company), achievement 0.85, education 0.9/0.8, skill 0.9, language 0.9, certification 0.85, project 0.8, other 0.5, default 0.8; date errors ⇒ 0.4 + flags; provenance snippet cap 240 chars.
- Languages: the 10 of audit-core §1.1 in registry order; answer-language resolution order (explicit answer setting → detected → interview setting → `en`); Arabic forced Gregorian, Persian Solar Hijri; language-detection thresholds as audit-core §1.9 (script share > 0.3, etc.). UI strings: 1,168 keys × 10 languages, en fallback, no missing keys (pinned by LocalizationCoverageTests parity).
- Readiness: 8 checks in order (PlaybackDevice, AudioSignal, AccountPlan, Transcription, AnswerEngine, InterviewPrepared, PreparedAnswers, WindowOnTop); verdict Pending→Checking, any Fail→NotReady, any Warning→ReadyWithWarnings, else Ready; only FAIL blocks Start; snapshot staleness **30 min**; AudioSignal 3 s grace + 2 min silent re-check; one snapshot for all surfaces.
- Settings defaults: the full `AppSettings` table (audit-app §3.1) — headline: `AnswerModel gpt-5.4-mini`, `TranscriptionModel gpt-4o-transcribe`, `RealtimeProtocol auto`, FontPreset Large, WindowOpacity **0.97**, VadSensitivity **1.0**, UseFastCache **true**, CompactMode false, AlwaysOnTop **true**, SaveSessionTranscript **false**, hotkeys `Ctrl+Alt+Space/L/Q/C/R`, Mode `cloud`, AnswerLanguage `same`, UpdateChannel `stable`, Live window 720×560, Home 980×700 (XAML opens 1040×720), cost prices 0.017 / 0.75 / 4.50 (diagnostics only). Reset ownership per category and the protected-field list (audit-app §3.2) are mirrored exactly.
- Updates (policy, Client `UpdatePolicy`): check interval **6 h**, startup delay **45 s**, never check/apply during a live session, channel normalize `beta` else `stable`.
- Error copy: `ErrorCopy` mapping (code-first, then status) and `BackendException → ProviderException` kind table are mirrored verbatim; never a raw exception, never "Unknown"; correlation-id reference suffix = first 8 chars when included.

---

## 5. Parity Test plan

### 5.1 Source inventory (verified, audit-backend-tests §11–12)

- **674** test methods: Backend.Tests **120**, InterviewAssistant.Tests **554**.
- **356** `[InlineData]` case expansions: Backend.Tests 33 (4 files), Tests 323 (41 files; largest Rc5EngineAuditTests 58, ShortTurnTests 30, Rc5ReadinessTests 25).
- **80 fixture files**: `fixtures/audio/` 74 WAV (24 kHz mono 16-bit PCM) + `fixtures.json` + `MANIFEST.md` (76), `fixtures/jobs/` 2 TXT, `fixtures/resumes/` 2 TXT.
- Golden product fixtures: `samples/shervin-teroxx/` — candidate profile, 13 stories, **97-question** bank, **107** test questions, target context (32 vocab terms, 6 aliases); `knowledge/generic_question_library.json` (52 questions).
- Support harnesses to mirror: `InterviewSimulator` (225 lines), `SyntheticAudio`, `Fakes`, `Fixtures`, Backend `ApiFactory` (real PostgreSQL, stub OpenAI/Paddle/Supabase, fake clock, fake realtime upstream).

### 5.2 Mapping rules

1. **Backend.Tests (120 + 33 cases)** — the backend is Shared, so these suites are **re-run unchanged** against the shared backend in Android CI (they already include `ClientEndToEndTests` behaviours). Additionally, every client-visible behaviour they pin (error codes, metering, relay rules, version gate, document versioning) gets a Kotlin client-level test in `:client`/`:app` with the same assertions, driven against the same stubbed backend shapes.
2. **Core/Client/UiState tests (554 + 323 cases)** — each Windows test file gets a same-named Kotlin test file in the mirrored module (`TurnDetectorTests.cs` → `TurnDetectorTests.kt` in `:core`), same test names (snake_case rendering permitted, name stem identical), same inputs, same expected values. The per-file method inventory in audit-backend-tests Appendix A is the checklist; a test is "mapped" only when its Kotlin counterpart exists and passes.
3. **UI-foundation tests** (`UiFoundationTests`, `UiPagesTests`, `Rc5DesignSystemTests`, `Rc5AccessibilityTests`, `Rc5LiveWindowTests`, `Rc5ReadinessPageTests`, `Rc5SettingsTests`, `Rc5AccountTests`) pin WPF mechanics that are **not** Android targets; what transfers is the underlying state logic (dirty guard, section state, readiness verdict/planner, settings persistence, account sections, follow-up handling, chrome breakpoints, token values). Each such file maps to (a) Kotlin unit tests of the mirrored UiState logic, and (b) Compose UI tests asserting the §3.1 tokens, §3.3 breakpoints and §3.2 presence of every control. The mapping is recorded per file in `parity/TEST-MAP.md` so no Windows test silently vanishes.
4. **Fixtures are copied byte-for-byte** into `:core` test resources (`fixtures/audio/**` incl. `fixtures.json` SHA-256 manifest — checksums must still verify) and `samples/**` into the parity harness. The Android acoustic gate must classify all 74 WAVs exactly as the Windows suite expects, including the documented residual look-alikes (quiet knock, sung melody) and their downstream rejection behaviour.
5. **Golden fixtures:** preparation over the shervin-teroxx inputs must produce a bank matching the golden 97-question bank on the pinned comparison rules (canonical fingerprints, modes, structures — byte-equality where the Windows pipeline is deterministic); engine scenarios A–I (EngineAcceptanceTests) are replayed through the Kotlin `InterviewSimulator` mirror with the same scripted audio/text and the same assertions.

### 5.3 New Android-only tests (additions, never replacements)

Platform-layer behaviour that has no Windows counterpart gets its own tests, held to the same standard: AudioRecord frame continuity into `PcmConverter` (100 ms frames, no drift over 60 min soak mirror), foreground-service lifecycle (start/pause/stop, process death → lease expiry behaviour), Keystore protector round-trip + corruption ⇒ signed-out/no-crash, App Link redirect state/PKCE validation (mirroring LoopbackRedirectTests assertions), update-never-mid-session, PiP/focus transitions preserving session state, foldable posture/resize preserving drafts and chrome state.

### 5.4 Contract parity

Kotlin DTOs in `:contracts` are tested against golden JSON captured from the Windows contracts (PascalCase names, optional-field defaults, `-1` sentinels in `UsageSummary`/`StartSessionResponse`/`HeartbeatResponse`, `BillingStates` string set). A serialization drift on either side fails CI. RuntimeConfig parsing is tested against the §2.5 field list.

### 5.5 CI gate and coverage targets

- **Gate (no exceptions):** an Android build may not be released (and a lockstep version number may not be used, §1.2 step 7) unless: all mapped parity tests pass; all 80 fixtures pass; golden bank/engine fixtures pass; contract fixtures pass; Backend.Tests re-run passes; asset checksums verify.
- **Method-mapping target: 674 / 674** Windows test methods accounted for — each either (a) re-run unchanged (backend), (b) mirrored in Kotlin, or (c) recorded in `TEST-MAP.md` as UI-mechanics-only with its state-logic counterpart named. InlineData expansions map with their methods (356 cases).
- **Line-coverage target:** `:core` and `:client` mirror modules ≥ the Windows suite's effective coverage of the same files, and never below **90 %** line coverage per module; `Intelligence`, `Turn`, `Live`, `Preparation` and `Audio` packages target **100 %** of the branches the Windows tests exercise (they are the product's correctness core). Coverage is reported per package in CI; a drop below target fails the build.
- **Soak:** the Windows 60-minute simulated soak (no duplicates, bounded LLM calls/memory) is mirrored and must pass before the parity-hardening phase exits (§7).

---

## 6. Android project structure and tech choices

### 6.1 Gradle modules (mirroring the Windows solution)

```
interview-assistant-android/
├─ settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
├─ app/            → mirrors InterviewAssistant.App
│   src/main/java/com/interviewassistant/app/
│     InterviewAssistantApplication.kt, MainActivity.kt
│     ui/live/  ui/home/  ui/settings/  ui/preinterview/  ui/components/  ui/theme/
│     uistate/  viewmodels/  services/  platform/ (audio, keystore, update, oauth)
│   src/main/assets/cloud.json
├─ core/           → mirrors InterviewAssistant.Core, package com.interviewassistant.core
│     audio/ diagnostics/ domain/ ingestion/ intelligence/ knowledge/
│     languages/ live/ orchestration/ persistence/ preparation/
│     providers/ reports/ turn/          (same names as C# namespaces)
│   src/main/assets/i18n/*.json          (byte-identical)
│   src/main/assets/knowledge/           (generic_question_library.json + playbooks, byte-identical)
├─ client/         → mirrors InterviewAssistant.Client (com.interviewassistant.client)
├─ contracts/      → mirrors InterviewAssistant.Contracts (com.interviewassistant.contracts)
└─ parity/         → TEST-MAP.md, SYNC-LOG.md, assets.sha256, golden fixtures, simulator harness
```

Module dependencies mirror the Windows project references exactly: `app → core, client`; `client → core, contracts`; `core → (nothing)`; `contracts → (nothing)`. No other dependency direction is allowed (this is what keeps the Lockstep diff mechanical).

### 6.2 Tech choices (decided by this map)

| Concern | Choice | Reason |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose (single-activity, NavHost) | Owner requirement: native Android. State via StateFlow (replaces INotifyPropertyChanged). |
| Networking | OkHttp (HTTP + SSE + WebSocket) + kotlinx.serialization | One client for REST, answer streaming (SSE `data:`/`[DONE]`/`event: error` semantics mirrored) and the realtime relay WebSocket; JSON names pinned by §5.4. |
| Local persistence | **Mirrored file store, not Room.** `WorkspaceStore` JSON documents in app-private storage with the same layout, atomic-write and quarantine rules; `AppSettings` as `settings.json` (SettingsVersion 3 migrations mirrored) | The Windows data model is document/JSON based; the `.iaprofile` bundle, sync documents and export formats are file formats. Room would create a second, divergent source of truth and break bundle/export parity. DataStore is not needed — settings are a single mirrored document. |
| Secrets | Android Keystore (AES-GCM) behind the mirrored `IDataProtector` interface | D3. Tokens and developer API key never in plain files, never logged; mask first3+last4. |
| Audio | AudioRecord → mirrored `PcmConverter`/`AudioFramer` (24 kHz, 100 ms) inside a **foreground service** (microphone type, persistent notification with Pause/Stop actions); AudioPlaybackCapture (API 29+) as the secondary source where the playing app permits it | D1. The engine consumes identical frames regardless of source. |
| PDF text | Android PDF text-extraction library (PdfBox-Android class) implementing PdfPig's word-ordering/line rules (Bottom desc, Left asc, >2 rounding) | D6; validated against the fixture résumés/JDs and Windows extraction outputs. DOCX path (zip+XML) ports directly. |
| Auth browser flow | Custom Tabs + verified App Links (https) redirect, PKCE S256 | D5; loopback HTTP listeners are not viable on Android. |
| Updates | Client for the project's own release system (Cloudflare R2/D1 service built for the website) with same-signer APK verification; Play delivery only if OD-1 chooses Play | D4; `UpdatePolicy` timing/session rules mirrored. |
| i18n | Shared JSON assets + mirrored `Loc`/`LanguageRegistry` over ICU | Same keys/fallback; Arabic forced Gregorian, Persian Solar Hijri via ICU calendar settings. |
| DI / async | Manual composition root mirroring `Program.cs` registrations (or Hilt if it stays invisible to module structure); Kotlin coroutines mirroring the async/CTS structure | Keeps diffs mechanical; cancellation semantics map to the Windows CancellationToken rules. |

### 6.3 Minimum SDK

**minSdk 29 (Android 10), targetSdk = current stable at project creation, compileSdk = same.** Reasoning: (1) `AudioPlaybackCapture` — the only sanctioned route to other-app audio — requires API 29; below it the D1 substitute loses its secondary mode entirely. (2) Foreground-service microphone typing and scoped storage are stable from 29, matching the app's privacy posture (no broad storage access; SAF for exports). (3) It covers the owner's devices (Galaxy Z Fold7) and the overwhelming majority of active Android devices, the same way the Windows app targets Windows 10/11 only. (4) Per-monitor-DPI/foldable posture APIs used for §3.3 are available via AndroidX WindowManager on 29+. Raising minSdk later is a lockstep decision; lowering it would require a §8 divergence entry.


---

## 7. Build order / phases

Phases and gates only — this map makes no date or effort promises. A phase starts only when the previous phase's exit gate passes. Parity tests for a module are written in the same phase as the module (per §1.2 step 4 discipline), not deferred to Phase 7.

| Phase | Scope | Entry criteria | Exit gate |
|---|---|---|---|
| **P1 — Foundation** | Android repo created (OD-2 visibility), Gradle module skeleton (§6.1), CI (build + unit tests + asset-checksum + mapping-table checks), reference-clone refresh script, `parity/` scaffolding (TEST-MAP, SYNC-LOG, assets.sha256), design-token file (`Tokens.kt`) transcribed from §3.1, PDF-extraction spike (D6) against fixture documents | This map approved; OD-2 answered (repo creation) | CI green on skeleton; token file passes a checksum-style diff against the transcribed §3.1 values; PDF spike reproduces Windows extraction on both fixture résumés and both fixture JDs (line rules verified) or D6 is escalated before proceeding |
| **P2 — Shared client** | `:contracts` DTOs + contract fixtures; `:client` mirror (Auth/PKCE, BackendClient, CloudSession, CloudAnswerProvider, CloudOptions, InstallationIdentity, UpdatePolicy) with App Link redirect (Platform) and Keystore token store; device registration with platform `android` | P1 gate; shared backend reachable (staging config) | Client parity subset passes (CloudAuthTests, UpdatePolicyTests mirrors + client-visible Backend.Tests behaviours); a real Android build signs in, registers a device, fetches config/plans/account against the shared backend; Backend.Tests re-run green |
| **P3 — Mirror Core** | `:core` mirror in dependency order: Domain/Persistence → Ingestion → Knowledge/Languages → Intelligence → Turn/Live → Preparation → Providers (direct) → Reports → Diagnostics/Audio math; byte-identical assets (i18n, knowledge) wired through mirrored `Loc`/`KnowledgeBase` | P1 gate (P2 may run in parallel) | Per-package parity tests pass as each package lands (incl. all 80 audio fixtures for Audio/Turn, golden 97-question bank for Preparation, language/localization coverage for Languages); module coverage targets of §5.5 met for completed packages |
| **P4 — UI** | Compose theme + components (Card/Btn/Input/Seg/FormField/Toast/Dialog/Popover/ExportChoiceList), Home shell + all 8 pages, Settings (7 sections), PreInterview page, Live screen static/chrome states; mirrored UiState + MainViewModel wired; §3.3 breakpoints and foldable rules; accessibility semantics | P3 core APIs stable for the pages being built; §3 tokens in place | Compose UI tests assert every §3.2 surface and control, §3.1 tokens, §3.3 breakpoints; UiState parity tests pass; navigation guard, dirty-state and relocalization behaviours verified by test (incl. fa RTL draft-preservation case) |
| **P5 — Live audio** | AudioCaptureService (AudioRecord + foreground service; AudioPlaybackCapture secondary), capture → PcmConverter/Framer → engine → relay WebSocket via CloudSession; live chrome states, level meter, silence injection, restart/backoff; Interview Focus via PiP (D9); manual question path | P2 + P3 + P4 gates | End-to-end live session against the shared backend on a real device: transcription events flow through the relay, answers stream, heartbeat metering matches server ledger, pause bills nothing, stop/expiry bill the tail; 60-min soak mirror passes; D1 behaviour verified for mic mode and (where permitted) playback-capture mode |
| **P6 — Billing & account completion** | Full account dashboard (usage metrics, devices, security/re-auth, export via SAF, delete 2-step + local-data choice), Paddle checkout/portal in Custom Tabs (direct-APK path), update client (release system) with never-mid-session rule, developer mode (own key, direct providers) | P2 + P4 gates; OD-1 answered **only if** Play billing must be built instead of/parallel to Paddle | Account parity tests (Rc5AccountTests mirrors + billing client-visible behaviours) pass; upgrade→poll→Pro flow verified against Paddle sandbox through the shared backend; export/delete flows verified end-to-end incl. SecretFilter behaviour |
| **P7 — Parity hardening** | Complete TEST-MAP closure (674/674), coverage targets enforced, golden fixtures + contract fixtures in CI, error-copy/localization full sweep, accessibility audit vs Rc5AccessibilityTests behaviours, hygiene (no secrets, licence compliance mirrors) | P1–P6 gates | Full §5.5 gate green with zero unmapped tests and zero unregistered divergences; §8 register re-reviewed line by line against the implementation |
| **P8 — Release** | Signing (same-signer update chain), release build, store/APK packaging per OD-1, release-notes + SYNC-LOG entry naming the mirrored Windows commit, staged rollout through the project's release panel | P7 gate; OD-1 (and OD-3 if Play) answered | Android ships with the **same version number** as the mirrored Windows release (§1.2 step 7); published through the Step-41 release system with SHA-256 recorded; smoke test on the owner's device (sign-in, readiness, one live session, one prepared interview) passes |

---

## 8. Divergence & risk register

Severity: **High** = user-visible capability differs from Windows in some conditions · **Medium** = behaviour preserved via substitute with edge cases · **Low** = cosmetic or fully equivalent substitute. "Residual gap" is stated honestly; an empty residual gap means the substitute is semantically identical.

| ID | Divergence | Severity | Windows mechanism | Android substitute (exact) | Residual gap |
|---|---|---|---|---|---|
| D1 | **System-audio capture** | **High** | WASAPI loopback on the render endpoint captures the interviewer's voice from the headset output; the microphone is *never* opened (NAudio `WasapiLoopbackCapture`) | Primary mode: **microphone as second device** — the phone sits beside the machine running the call and hears the interviewer via speaker/headset leak, fed into the identical 24 kHz/100 ms pipeline. Secondary mode: **AudioPlaybackCapture** (API 29+) captures the playing app's audio on the same device **only where that app permits capture** | Same-device capture is **not guaranteed**: apps that set `ALLOW_CAPTURE_BY_NONE` (many call/meeting apps) cannot be captured, and no Android API can override that. In mic mode, room noise/speaker quality affect the acoustic gate (thresholds unchanged). The product must present mic mode as the primary Android mode, as Windows presents loopback as its mode. Everything downstream of the frames (VAD, turn, acoustic evidence) is identical. |
| D2 | **Global hotkeys** | Medium | `RegisterHotKey` system-wide: Ctrl+Alt+Space/L/Q/C/R (toggle window, start/pause, manual input, coach, reset adaptive) | Same 5 actions via: foreground-service notification actions (Start/Pause, Coach), in-app controls, hardware-keyboard shortcuts when a keyboard is attached; stored shortcut strings/defaults and conflict validation preserved verbatim in Settings (§3.2 #15) | No system-wide key capture exists on Android for normal apps: shortcuts do not fire while another app is foreground and the FGS notification is the substitute surface. Actions, defaults and settings semantics are identical; the trigger surface differs. |
| D3 | **Secret protection** | Low | DPAPI CurrentUser with app entropies (`InterviewAssistant.v1.apikey`, `InterviewAssistant.workspace.v1`) | Android Keystore AES-GCM keys in app-private storage, same interface (`IDataProtector`), entropy strings retained as key aliases/context; same mask (first 3 + last 4) | None — semantics identical (hardware-backed where the device provides it, which is stronger than DPAPI software mode). |
| D4 | **App updates** | Medium | Velopack: GitHub/simple-web feed, Authenticode same-publisher check, background check (policy 6 h/45 s), applies on exit or Restart-to-update, **never during a live session**, installed-layout detection | Project release system (Cloudflare R2/D1 + admin panel, built in website Step 41): manifest + APK, **APK signature verification enforces the same-signer rule** (the Android equivalent of the Authenticode publisher check, absorbed from `Authenticode.cs`/`InstallLayout.cs`); `UpdatePolicy` timing and never-mid-session rules mirrored; Play delivery replaces the mechanism entirely if OD-1 chooses Play | Direct-APK updates require the user to allow install from the app once (Android platform prompt) — Velopack's silent apply-on-exit has no equivalent. Check/apply timing rules are otherwise identical. |
| D5 | **OAuth / password-recovery redirect** | Medium | Loopback HTTP listener on 127.0.0.1 (RFC 8252 §7.3) receives the PKCE code; external links opened after https+DNS-host validation | Custom Tabs + **verified App Links (https)** redirect into the app (custom scheme as fallback only where App Links are unavailable); PKCE S256, state, 5-min browser timeout, 15-min reset wait, enumeration-safety and different-account refusal all mirrored | Redirect integrity depends on App Link domain verification instead of loopback's inherent locality; a hijacked unverified fallback scheme is the known Android risk — mitigated by preferring verified links and by the identical state/PKCE checks. Behaviour visible to the user is identical. |
| D6 | **PDF text extraction** | Medium | PdfPig 0.1.16 word extraction with line rules (Bottom desc, Left asc, >2 rounding) | Android PDF library implementing the same word-ordering/line-grouping rules on its text layer; DOCX (zip+XML) path ports directly | Extraction edge cases (multi-column, rotated text) may differ on adversarial PDFs until fixture coverage grows; the P1 spike + fixture résumés/JDs + `NeedsOcr` rule (< 20 chars ⇒ scanned-PDF error) bound the risk. Upload validation (magic bytes, zip limits, 10 MiB) is identical. |
| D7 | **Payments** | Medium (decision pending) | Paddle hosted checkout + customer portal in the browser; backend is Merchant-of-Record authority | **Direct APK:** identical — same backend checkout/portal URLs opened in Custom Tabs; polling 5 s × 15 min mirrored. **Play Store:** Google policy forces **Play Billing** for digital subscriptions, which Paddle cannot satisfy | **Owner decision OD-1.** If Play is chosen, billing client code diverges (Play Billing Library) while backend entitlements/metering stay Shared — but subscription lifecycle events would need a Play→backend bridge that does not exist today. This map does not decide it. |
| D8 | **Window model: topmost, opacity, placement** | Medium | Live window is always-on-top, opacity 0.75–1.0 (default 0.97), free placement across monitors with DPI-aware restore | The Live screen is a full app screen on Android; "always visible while using another app" is provided by **Picture-in-Picture** for the live session (D9) and keep-screen-on during sessions; `WindowOpacity`/`AlwaysOnTop` settings persist identically and drive PiP/compact presentation choices; placement persistence becomes nav/pane/posture state persistence | A translucent always-on-top overlay window over other apps is not a permitted mainstream Android pattern (system-alert overlays are restricted and store-hostile) — not used. Opacity has no literal effect on a full-screen activity. The information and controls are all present; the compositing differs. |
| D9 | **Interview Focus placement** | Low | Window shrunk (recommended 560×360, ≤ 40 % of work area, ≥ 440×260) to a top-edge preset; prior placement saved/restored exactly | Focus = entering PiP (compact live view: status, current bullets/coach line, pause/stop) with the same presets concept (position is system-controlled in PiP); exiting PiP restores the exact prior Live state; first-use honest dialog and preset persistence mirrored | PiP size/position are chosen by Android, not by the app — the 560×360/40 % geometry rules become "request PiP with the live aspect ratio"; state restore is exact, geometry is approximate. |
| D10 | **File dialogs & documents folder** | Low | Win32 Open/Save dialogs; exports to chosen paths; reports viewer copies under `%LOCALAPPDATA%\…\view\<owner>\` | Storage Access Framework (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`) with the same default file names (`interview-assistant-account-YYYY-MM-DD.html/.json`, `<slug>[-backup|-raw-data]-YYYY-MM-DD.<ext>`, slug rule ≤ 40 chars); report "Open" writes the same plain-HTML viewer copy to app-private storage and opens it in the browser/Custom Tab; viewer copies deleted on workspace close/report delete, as on Windows | The user picks locations through the system picker (no silent writes to arbitrary paths) — a platform constraint, not a behaviour change. Formats, atomic-write and SecretFilter rules identical. |
| D11 | **Fonts & icons** | Low | Segoe UI Variable / Segoe UI; Segoe Fluent glyph font for all icons | Platform sans (Roboto) at identical sizes/weights; one vector drawable per Fluent glyph code point (§3.1) | Cosmetic only: glyph shapes and font metrics differ slightly; sizes, weights, colours, placements identical. HTML report/export font names ('Segoe UI'/Consolas) remain in the generated HTML strings (they degrade gracefully on Android viewers, as they do on any non-Windows viewer). |
| D12 | **Background execution limits** | Medium | Desktop process runs unrestricted; capture restarts with backoff 1 s→10 s; update/diagnostics timers run freely | Foreground service (microphone type) with persistent notification keeps capture + session alive; the same restart/backoff and timer rules run inside it; battery-optimisation exemption is requested through the standard system flow with the readiness check surfacing the state | Aggressive OEM battery killers can still stop the FGS on some devices; Windows has no equivalent failure. Mitigated (not eliminated) by FGS + exemption + the session lease design (server expires at 90 s silence and bills only the tail, so a killed app cannot corrupt metering). |
| D13 | **Question-bank "50 floor" does not exist in code** *(flagged finding)* | Low | Product description says banks are 50–150; the code enforces only the **150 cap** (dedupe → order by confidence → Take(150)). No code pads a small bank up to 50 | Android mirrors the code exactly: cap 150, no invented minimum. Generation volume (factory quotas, §4.3) is copied so real banks land in the same range as Windows | None — provided nobody "fixes" it on Android. If the owner ever wants a true 50 floor, it must be implemented on Windows first and arrive via the Lockstep protocol (§1.2), keeping the platforms identical. |
| D14 | **Build/doc inconsistency in the source** *(informational)* | Low | `BUILD.md` states ".NET 8 / C# 12" while the shipped projects are `net10.0` | Noted here so nobody treats the stale line as spec; the code and audits are normative | None. Windows-side doc fix is out of scope (repo is read-only for this project). |

**Risk notes (not divergences):** (R1) Direct-provider (developer) mode on Android holds the user's own OpenAI key in the Keystore store and talks to OpenAI directly, exactly as Windows developer mode does with DPAPI — same trust model, no new exposure. (R2) The relay design means cloud-mode Android never receives a provider credential, identically to Windows rc.3+. (R3) Any future Windows feature that depends on a new Windows-only API enters this register *before* its Android phase starts (§0.3 rule 8); an unregistered divergence blocks the P7 gate.

---

## 9. Owner decisions (only these)

Everything not listed here is decided by this map. Each decision lists when it is needed; work not depending on a decision proceeds without waiting for it.

| ID | Decision | Options | Needed by | This map's note |
|---|---|---|---|---|
| OD-1 | **Android distribution channel** | (a) Direct APK via the project's own release system (cybervalida.online / Step-41 panel) — keeps Paddle exactly as Windows; (b) Google Play — forces Play Billing (D7) and Play policy review for microphone/FGS use | End of P5 (before P6 billing is built) and P8 packaging | (a) is the only option under which billing is a 100 % copy. (b) is a product/policy choice, not a technical one — hence the owner's. |
| OD-2 | **New Android repo visibility** | Private or public, under `SaEeDX4`, planned name `interview-assistant-android` | P1 (repo creation) | The Windows source repo is public and the Android repo contains no secrets (keys live in Keystore/backend), so either is technically safe; visibility is the owner's call. |
| OD-3 | **Play signing & developer account** *(only if OD-1 = Play)* | Owner-held Google Play developer account + upload key custody | P8 | Conditional on OD-1; not needed for direct APK (project signing key is generated in P1 and its fingerprint recorded in SYNC-LOG). |
| OD-4 | **Same-device capture in v1** | Ship AudioPlaybackCapture as a secondary mode in v1 (with honest in-app wording that meeting apps may block it), or v1 = microphone mode only | P5 | This map recommends shipping it (it is the closest equivalent to loopback where permitted), but the user-facing promise is the owner's decision because the residual gap in D1 is a product-communication choice. |

Explicitly **not** owner decisions (decided above): Kotlin/Compose, module structure, minSdk 29, file-store instead of Room, OkHttp, Keystore, Custom Tabs + App Links, PiP for Focus, SAF for files, all thresholds/prompts/strings, the Lockstep protocol, and the parity gate.

---

## 10. Coverage proof

Mechanical verification method: every line of `all-src-files.txt` (204 paths) was extracted to its `src/…` relative form and checked to appear **exactly once** in the §2 table of this document (generation script asserted 204 rows, no missing, no extras; the same check is part of Android CI per §1.3).

| Claim | Count | Evidence |
|---|---|---|
| Source files mapped in §2 | **204 / 204** | §2 table rows 1–204, generated from `all-src-files.txt` |
| — Shared | 49 | Backend 37 (all except dev-only `launchSettings.json`), `ApiContracts.cs`, `cloud.json`, 10 × i18n JSON |
| — Mirror | 101 | Core 63, App 30 (incl. UiState 20), Client 8 (per §2 rows, counted mechanically) |
| — Platform | 45 | App UI/services/platform files per §2 rows |
| — Not-Needed (each justified in its row) | 9 | 4 × `.csproj`, `GlobalUsings.cs`, `ObservableObject.cs`, `InstallLayout.cs`, `Authenticode.cs` (absorbed into D4), `launchSettings.json` |
| Windows surfaces mapped in §3.2 | **21 / 21** | Live window, Home shell, 8 Home pages (incl. Settings), 7 settings category panels, ExportChoiceList, dialog set, popover set, toast/component set (audit-app §2 inventory) |
| Design tokens transcribed | All of audit-app §1 | §3.1 (20 colours, radii, sizes, spacing, text/control styles, answer/question typography) |
| API endpoints accounted for | **42 / 42** | Shared backend — audit-backend-tests §2; Android client surface specified in §2 (Client rows) + §5.4 |
| DB entities accounted for | **13 / 13** | Server-side only (Shared); listed in audit-backend-tests §3; no Android persistence equivalent by design (§6.2) |
| Windows test methods mapped by §5 rules | **674 / 674** | Backend.Tests 120 (re-run unchanged) + InterviewAssistant.Tests 554 (mirrored or TEST-MAP-recorded), + 356 InlineData cases mapped with their methods |
| Fixture files mapped | **80 / 80** | 74 WAV + `fixtures.json` + `MANIFEST.md` + 2 job TXTs + 2 résumé TXTs, copied byte-for-byte (§5.2 rule 4) |
| Golden fixtures mapped | 2 sets | shervin-teroxx sample set (97-question bank, 107 test questions) + generic question library (52 questions) |
| Phases defined (§7) | 8 | P1 Foundation → P8 Release, each with entry criteria and an exit gate |
| Divergences registered (§8) | 14 | D1–D14 (High 1, Medium 7, Low 6 — D14 informational) |
| Owner decisions (§9) | 4 | OD-1 distribution, OD-2 repo visibility, OD-3 Play signing (conditional), OD-4 same-device capture in v1 |

*End of Transfer Map. Companion documents: `audit-core.md`, `audit-app.md`, `audit-backend-tests.md`, `all-src-files.txt` (all in this folder). The Windows reference remains read-only; this map is the contract for every Android change that follows it.*
