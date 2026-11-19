# P4 Port Notes — UI (Compose) mirror of InterviewAssistant.App

Phase P4 per TRANSFER-MAP §7: Compose theme + components, Home shell + all 8 pages,
Settings (7 sections), PreInterview page, Live screen static/chrome states; mirrored
UiState + MainViewModel wired; §3.3 breakpoints/foldable rules; accessibility semantics.

Standing rules (same as P3): port from the actual C#/XAML in
`~/workspace/interview-assistant-android/windows-reference/` (READ-ONLY — never modify);
1:1 names/order/defaults/literals; no TODO/NotImplemented/placeholders; every divergence
registered here as a numbered DC (continuing from DC-59 → DC-60+); port the corresponding
Windows tests in the same package (same names/inputs/expected values); claim nothing
compiled or tested — GitHub Actions is the first execution check.

Windows test files inherited by P4 (accounted in P3-PORT-NOTES final accounting):
ErrorStateTests.cs (UiState half → P4-A; BackendException half → P4-C/:client),
Rc5AccessibilityTests.cs, Rc5AccountTests.cs (also named in the §7 P6 gate),
Rc5DesignSystemTests.cs, Rc5LiveWindowTests.cs, Rc5ReadinessPageTests.cs,
Rc5ReadinessTests.cs, Rc5SettingsTests.cs, UiFoundationTests.cs, UiPagesTests.cs.
Each package below records which parts it ports and which parts it defers BY NAME
(source-scan/XAML-structure assertions adapt to the Compose sources or defer to the
package that owns the surface; live-audio behaviors belong to P5, billing/account
completion to P6).

Architecture note (registered, not silent): navigation is state-driven — the mirrored
HomeRoute + DirtyStateGuard/NavigationGuard live in the view-model layer and the single
activity renders destinations by route state. This mirrors the Windows original (route
strings + VM state; WPF has no nav framework either) and keeps every navigation behavior
JVM-testable in CI (no emulator exists in CI for instrumented navigation tests). The
map §6.2 "NavHost" wording is satisfied by this NavHost-equivalent state holder;
androidx.navigation:navigation-compose is not added.

## Package P4-A — UiState mirror (20 files)

### P4-A delivery record (2026-11-19, local only — not pushed, not compiled; CI is the first execution check)

All 20 C# files ported 1:1 into `project/app/src/main/java/com/interviewassistant/app/uistate/`
(package `com.interviewassistant.app.uistate`), pure JVM logic — no Android/Compose imports.
The placeholder `UiState.kt` now carries only the package declaration + a doc comment listing
the 20 mirrored files. Every port was verified line-by-line against the C# source in
`windows-reference/src/InterviewAssistant.App/UiState/` (see "Verification pass" below).

Per-file checklist (C# lines → Kotlin lines):

| C# file | C# | Kotlin file | Kotlin |
|---|---|---|---|
| LocalData.cs | 24 | LocalData.kt | 39 |
| HomeRoute.cs | 31 | HomeRoute.kt | 34 |
| HistoryCounter.cs | 34 | HistoryCounter.kt | 39 |
| ReadinessVerdict.cs | 34 | ReadinessVerdict.kt | 35 |
| AccountMenu.cs | 50 | AccountMenu.kt | 46 |
| HealthCheck.cs | 51 | HealthCheck.kt | 56 |
| SettingsCategories.cs | 55 | SettingsCategories.kt | 60 |
| FocusPlacement.cs | 57 | FocusPlacement.kt | 67 |
| ErrorCopy.cs | 62 | ErrorCopy.kt | 92 |
| LiveChrome.cs | 62 | LiveChrome.kt | 62 |
| SettingsDefaults.cs | 81 | SettingsDefaults.kt | 99 |
| ConnectionToastGate.cs | 82 | ConnectionToastGate.kt | 91 |
| QuickHelp.cs | 88 | QuickHelp.kt | 85 |
| PendingReset.cs | 100 | PendingReset.kt | 99 |
| SectionState.cs | 103 | SectionState.kt | 103 |
| AccountView.cs | 106 | AccountView.kt | 115 |
| ReadinessPresentation.cs | 158 | ReadinessPresentation.kt | 153 |
| DirtyStateGuard.cs | 167 | DirtyStateGuard.kt | 157 |
| AuthFormState.cs | 179 | AuthFormState.kt | 186 |
| Readiness.cs | 219 | Readiness.kt | 216 |
| **Total** | **1,743** | (+ UiState.kt doc, 14) | **1,848** |

### Test mapping (Windows file → ported cases; xUnit theories → case loops inside one @Test)

Tests live in `project/app/src/test/java/com/interviewassistant/app/uistate/` (JUnit 4, as
configured in `:app`). A shared fixture `UiStateLocFixtures.installLocTables()` points
`Loc.tableLoader` at `../core/src/main/assets/i18n` (the :core Fixtures convention), so all
copy assertions run against the real shared tables. **103 @Test methods** in 8 files:

- **ErrorStateTests.cs (159) → ErrorStateTests.kt — 12 @Test, all portable cases ported.**
  ErrorStateTests (8: offline, one-endpoint timeout + reference strip, 500, user cancellation,
  slow, offline→reconnect stale note, retry success, section failure titles in every language),
  NavigationGuardOrderTests (1), RelayConnectionToastTests (3, with the C# `Run` helper:
  +5 s steps with a Poll after each event). Deferred: none.
- **UiFoundationTests.cs (335) → UiFoundationTests.kt — 18 @Test.**
  Guard theories (5 presentation reasons never ask + keep edits; 5 data-loss reasons ask and
  Stay), CleanEditors, SaveChoice (failed validation stays, second attempt saves), DiscardChoice,
  ScopedQuestion, ProgrammaticUpdates, LanguageSwitchWhileEditingAProfile (ClassifySelection:
  same id → ListRefresh, programmatic → ListRefresh, other id → SwitchItem), OnlyOneQuestionAtATime
  (CompletableDeferred gate; the second call is refused while the first is open — the first call is
  started with CoroutineStart.UNDISPATCHED to reproduce C# hot-task semantics), section tests
  (OneSectionTimingOut, RetrySuccess, StaleData "Showing saved data from 14:05." / de contains
  "14:05", LateResponses + Reset), Verdict_OnlyFailBlocks (incl. Pending+Fail → Checking),
  PreflightCopy, PasswordResetCopy_IsNeutral, and the table halves of
  EnglishCopy_SaysResume_WithoutAccents and AboutLabels_AreTranslated.
  **Deferred by name** (WPF source scans → Compose counterparts in P4-E/F/G):
  DesktopApp_NeverUsesTheNativeMessageBox, SettingsIsAPage_NotASeparateWindow,
  FormControls_UseTheSharedStyles_NoAdHocWatermarkOverlays, and the app-source halves of
  EnglishCopy_SaysResume_WithoutAccents and AboutLabels_AreTranslated.
- **UiPagesTests.cs (406) → UiPagesTests.kt — 8 @Test.**
  SegmentedNavigation theory (6 cases), HistoryCounter_NeverShowsZeroOfN (full sweep
  count 0..12 × position −3..14), ProfileGapCard per-language theory (10 languages, 24 keys,
  en exact values), ConnectionToasts ×5 with a FakeClock (short blip; real outage — Lost exactly
  once after the ~3 s grace, Restored once, re-confirm silent; at most one pair per 10 s;
  delayed Lost; silent reset).
  **Deferred by name** (source scans): ReportsPage_HasTwoResponsivePanes_FourActions_EmptyAndSelectStates,
  ReportsPage_Keyboard_EnterOpens_DeleteAsksDestructively,
  ProfileDelete_IsDestructive_SetApart_AndConfirmedWithCancelAsDefault,
  ProfileSections_AreASegmentedControl_WithAutomationNames_AndKeyboard,
  ProfileAndInterviewForms_UseTheSameGridTokens,
  LiveWindow_CounterHiddenWhenEmpty_HistoryResetOnRebuildAndSignOut,
  ManualQuestionBar_FieldAndSendButton_ShareTheHeightTokenOnOneRow,
  ProfileGapGuidance_IsADistinctCard_NeverTheCandidatesAnswer,
  LiveWindow_UsesTheGateForEverySource.
- **Rc5ReadinessTests.cs (278) → Rc5ReadinessTests.kt — 21 @Test.**
  All snapshot/store/verdict cases (AllPass 8/8, OneWarning, OneFailure, Running_IsChecking,
  AnotherInterviewOrDevice outdated, NothingRun/Clear, ALaterResult replaces + same snapshot
  instance to both subscribers, CloudTranscriptionNotTestedYet — summary
  "rd_summary_passed_untested" args [7,7,1], NotTestedNextToAFailure, AllPassed summary [8,8]
  and other-context [0,8], AnOldResult outdated at MaxAge+1 s, SavedSettings revision via
  ContextOf, ConcurrentReports never lost — parallel reports from 8 threads → 8 passed,
  GetStarted_CheckStepIsNeverDone), SetupPlanner theory (12 PrimaryAction cases), GetStarted
  steps ×3, EveryStateHasALabel theory (6), HomeRoute Route_Parse theory (8) + Route_SignedOutGate
  theory (6).
  **Deferred by name** (MainWindow/MainViewModel source scans → P4-E/F):
  AutoFit_NeverRefitsTheSameInputs, AuthorizedUse_IsAskedOnlyWhenListeningCanStart.
- **Rc5ReadinessPageTests.cs (266) → Rc5ReadinessPageTests.kt — 8 @Test.**
  EveryStateAndActionKey (12 distinct keys, all languages), EveryPresentationKey (all
  languages), EveryFix_HasALabel_AndALegalRouteOrRunsOnThePage, StartAndAutoRun theory (6),
  OutcomeBadges (brush keys B.Listening/B.Thinking/B.Error, StateOf(null) = NotChecked, icons
  non-empty + distinct), SummaryNumbers, Blockers/TypedQuestionsStillWork, TransitionLog
  ("Readiness: Checking -> ReadyWithWarnings (7/8 passed)", no personal data, null when unchanged).
  **Deferred by name** (wiring/source scans → P4-D/E/G):
  PreInterviewCheck_IsAHomePage_AndTheSeparateWindowIsGone, OpenReadiness_DeepLinksHomeToThePage,
  ThePage_WritesTheStore_AndRendersFromIt, StickyActionBar_HasRunSettingsAndOnePrimaryStart,
  GetStarted_HasExactlyOnePrimaryAction_FromTheSetupPlanner,
  InterviewsCard_AndTitleBarIndicator_OpenTheCheck, SelfTest_DrivesThePage_AndChecksGetStarted.
- **Rc5SettingsTests.cs (379) → Rc5SettingsTests.kt — 11 @Test.**
  Categories_AreOrdered (ids order, title/description keys, en "Meeting audio device and
  level", per-language key presence + one-line descriptions), Parse theory (8), DeepLinks,
  UpDown_MoveWithinTheList, ProtectedFields_Cover…, ResetSection (loop over the 7 categories;
  FakeSettings mirror with 49 properties and the C# defaults; Customized() driven by reflection
  with the C# rules — String→"custom-"+name, Boolean→negation, Double→+0.5, Double?→123.0,
  Int→+7, Guid?→random, FakeFont→Compact), RestoreAll…, ResetEffect (EffectOf cases),
  EveryAppSetting_IsClassifiedExactlyOnce (classification half: the classified lists equal the
  49 mirror property names, distinct, per-category disjoint + non-empty), plus the en copy
  assertions extracted from two otherwise-source tests (resetRestoreCopy: set_restore_*;
  updatesCopy: set_upd_install_note).
  **Deferred by name**: the AppSettings.cs source half of
  EveryAppSetting_IsClassifiedExactlyOnce_AndTheTestMirrorMatchesAppSettings (mirror-vs-source
  sync — lands with the P4 Settings port), ResetAndRestore_AskInTheInAppDialog_CancelIsTheDefault_AndApplyLikeASave
  (source half), SettingsPage_ShowsOneCategoryAtATime_WithAnAccessibleList_AndKeepsTheExplicitSaveModel,
  Updates_AreInSettings_NotOnTheAccountPage (source half),
  CheckNow_ReusesTheBackgroundCheck_AndNeverFakesAnUpdateForUnsignedBuilds,
  InfoTip_IsFocusable_NamedAndDescribedForScreenReaders,
  SecondaryHints_AreInfoTips_EssentialStatementsStayVisible.
- **Rc5AccountTests.cs (364) → Rc5AccountTests.kt — 12 @Test.**
  AuthForm ×6 (starts at sign-in; every-state fields/primary theory (5); forgot-password;
  transitions follow links; busy blocks — Changed count == 3; reset-sent cooldown —
  ResendSecondsLeft 55 at +5 s, cooldown ≥ 60 s, CanResend rules, ResetFailed →
  ResendAvailableAt null; switch account — heading "Sign in with another account" from the en
  table, subtitle keys, plain subtitle for a blank e-mail), AccountMenu_Items_Order_Labels_AndKeyboard,
  Dashboard_StacksBelowTheBreakpoint theory (719→1, 720→2, 772→2, 1600→2, 400→1),
  PlanView_BadgesAndMetrics (badge keys in 10 languages; metric ids
  [minutes_used, minutes_remaining, available_today, preparations, limits]; values
  "150 of 600 min", progress 0.25, "120 min", prep progress 0.2, limits progress null;
  Fraction(5,0)=0, Fraction(9,3)=1; Loc.language set to "en" and restored),
  Deletion_SubscriptionRule theory (6), DeleteDialog_TextsExist (en exact texts; all
  DialogKeys non-empty and ≠ en in the other 9 languages).
  **Deferred by name** (wiring scans): Header_HasOneAccountButtonAndMenu_NoBadgeOrSignOutButton,
  AccountPage_HasNoSignOutButton_AndSwitchAccountIsNamedSo,
  SwitchAccount_ConfirmsThenSignsOutQuietly_NormalSignOutKeepsItsToast,
  ForgotPassword_IsADedicatedState_WiredToTheStateMachine,
  DeleteAccount_DialogThenReauthenticationThenDeletion_ThenASeparateLocalDataQuestion, and the
  DeleteAccountDialog.cs source tail of DeleteDialog_TextsExist_AndAreTranslated_InAllTenLanguages.
- **Rc5LiveWindowTests.cs (345) → Rc5LiveWindowTests.kt — 13 @Test.**
  LiveChrome ×3 facts + GrowingWidth monotonic loop (4 session/compact combos, widths
  440→2000 step 10), FocusPlacement MemberData (7 work areas × 3 presets: inside the work
  area, top == work.Top, ≤40 % caps with min-size floors), 1366x768 centred (centre within 1
  of 683, top 0), LargeScreens recommended size + corner gaps, NegativeCoordinateMonitor,
  FocusPreset parse (round-trip; null/"Sideways"/"42" → TopCentre), QuickHelp ×2 + the
  per-language theory (10 languages; keys from QuickHelp content for all states with
  answerShown=true + FocusPlacement.Key + health-check row keys + the fixed extra key list;
  en focus_body_first contains "does not hide it from screen sharing" and not "invisible";
  en live_empty_setup exact), HealthCheck_PassAndWarnRows (AnswerSeconds(1260,900)=1.3,
  (-1,900)=0.9, (0,0)=null).
  **Deferred by name** (source scans): LiveToolbar_HasNoDiagnosticsButton_NoDiagPanel_NoOldPreInterviewIcon,
  QuickHelp_IsACompactPopover_WithoutDontShowAgain,
  LiveWindow_MaximizeRestore_WorkAreaHook_ReadableColumn,
  HelpPage_HasADiagnosticsSection_AndTheHealthCheckNeverLaunchesTheCiSelfTest.
- **Rc5AccessibilityTests.cs (104) → 0 ported — all 8 deferred by name** (XAML automation-peer /
  source scans → Compose-source counterparts in P4-D/E/F/G):
  LiveRegions_UseTheAnnouncingAttachedProperty, FocusableOrNamedContainers_HaveAnAutomationPeer,
  StyledFocusableBorders_AreAccessibleBorders, AudioMeters_AreProgressBarsForScreenReaders,
  LiveReadinessIndicator_IsAnIcon_NotAColourOnlyDot,
  ToggleMenuRows_AnnounceTheirState_AndInPlaceActionsKeepFocus,
  RadioGroups_AreOneTabStop_WithArrowSelection, InfoTips_NameTheFieldTheyExplain.

### Divergences (P4-A)

- **DC-60** Member naming follows the P3 convention: C# PascalCase members → Kotlin camelCase;
  C# static classes → Kotlin `object`s; C# records → `data class`es; C# `const` fields keep
  their values (names camelCased, e.g. `LiveChrome.MinWidth` → `minWidth`).
- **DC-61** C# events (`event Action`, `event Action<ReadinessSnapshot>`) → private listener
  lists with `add…Listener`/`remove…Listener` (the Loc convention established in P3).
  Affected: SectionState.Changed, DirtyStateGuard.DirtyChanged, AuthFormState.Changed,
  ReadinessStore.Changed.
- **DC-62** C# named tuples → small data classes: `(Shown, Total)` → `HistoryNumbers`,
  `(Up, Silent)` → `RelayTransition`, `(EngineChanged, ModelChanged)` → `ResetEffect`,
  `(Key, Args)` → `ReadinessSummary`.
- **DC-63** LocalData.Delete: the recursive delete is an NIO `Files.walk` (deepest first) —
  Kotlin's `deleteRecursively()` is NOT used because it swallows failures. Only
  `IOException`/`SecurityException` are recorded as a `Failure` (the JVM counterparts of the
  C# catch filter `IOException or UnauthorizedAccessException`; the recorded error is the
  exception's simple class name, as in C#). A path that exists but is not a directory raises
  `IOException`, mirroring `Directory.Delete` on a file path.
- **DC-64** ErrorCopy exception-type mapping: `HttpRequestException` → `java.io.IOException`;
  `TaskCanceledException` → `kotlinx.coroutines.TimeoutCancellationException`;
  `NotSupportedException` → `UnsupportedOperationException`; `System.Text.Json.JsonException`
  → `kotlinx.serialization.SerializationException`, matched reflectively (`Class.forName` +
  `isInstance`, which also covers subclasses exactly like the C# type pattern) because
  kotlinx-serialization is an `implementation` dependency of :core/:client/:contracts — it is
  on :app's runtime classpath but NOT its compile classpath, and this package adds no
  production dependency (if the parent prefers, adding
  `implementation(libs.kotlinx.serialization.json)` to :app makes the direct type check
  possible). `IsUserCancellation(ex, token)`: the CancellationToken counterpart is the call's
  `kotlinx.coroutines.Job` (`ex is CancellationException && job.isCancelled`).
- **DC-65** PendingReset.Ticket: the `CancellationToken` is exposed as a `kotlinx.coroutines.Job`
  (`token`), cancelled by the internal `cancel()`; `IDisposable` → `AutoCloseable.close()`,
  which — like CTS disposal — does not cancel. The C# per-address dictionary is
  `OrdinalIgnoreCase`; the Kotlin map stores trimmed addresses and both write and lookup are
  case-insensitive (a re-record under a different casing replaces the entry).
- **DC-66** SettingsDefaults.Reset: C# `System.Reflection` over the settings type → Java
  reflection over the bean accessors (`get<Name>`/`set<Name>`, walking superclasses); a
  missing property throws `IllegalStateException` (the established :core analog of
  `InvalidOperationException`) with the same message `"<ClassName> has no setting <name>"`.
- **DC-67** ReadinessPresentation.Icon keeps the Segoe Fluent glyph codepoints 1:1
  (Ready U+E73E, ReadyWithWarnings U+E7BA, NotReady U+E711, Checking U+E895, Outdated U+E823,
  default U+E9CE) so the parity tests compare the same values; Android has no Segoe font, so
  the Compose layer (P4-D+) maps states to Material icons at render time.
- **DC-68** DirtyStateGuard.Suspend() returns a nested `Scope : AutoCloseable` (the
  `IDisposable` counterpart), used with Kotlin's `use {}`.
- **DC-69** `LiveChrome.For(...)` and `QuickHelp.For(...)` keep the C# name via Kotlin's
  backticked keyword escape: `` fun `for`(...) ``.
- **DC-70** Rounding: .NET `Math.Round` (midpoint-to-even) → `java.lang.Math.rint`
  (FocusPlacement left/top) and `BigDecimal` with `RoundingMode.HALF_EVEN`
  (HealthCheck.answerSeconds, `Math.Round(ms/1000.0, 1)`); `AuthFormState.resendSecondsLeft`
  computes the ceiling from `Duration.toNanos()` to match `(at - now).TotalSeconds` exactly.
- **DC-71** ReadinessStore.ContextOf: C# string interpolation renders null as `""`; Kotlin
  templates render `"null"`, so the port writes `(accountId ?: "")` etc. explicitly.
- **DC-72** HomeRoute.Parse: C# `Split('/', 2, RemoveEmptyEntries | TrimEntries)` → Kotlin
  `split('/', limit = 2)` + trim + empty-filter; the section therefore keeps any further
  '/' segments ("a/b/c" → section "b/c"), as in C#.
- **DC-73** FocusPlacement.Parse: C# `Enum.TryParse(ignoreCase)` also accepts a defined
  numeric value ("1" → TopLeft); the port mirrors that (name match, else a defined ordinal,
  else TopCentre). Undefined numbers ("42") fall back, as in C#.
- **DC-74** PlanView.Metrics: the C# `Func<DateTime, string>` formatter becomes
  `(OffsetDateTime) -> String`, following the P2 contracts port where
  `UsageSummary.PeriodEndUtc` is an `OffsetDateTime`.

### Dependencies

None added. `:app` already had `testImplementation(libs.junit)` (JUnit 4.13.2);
kotlinx-coroutines reaches :app's compile classpath via
`androidx.lifecycle:lifecycle-runtime-ktx`'s api dependency on kotlinx-coroutines-android
(:core declares coroutines as `implementation`, so from :core it is runtime-only);
`:core` and `:contracts` reach :app's compile classpath via `:client`'s `api` project
dependencies. kotlinx-serialization is deliberately NOT used at compile time in :app
(see DC-64).

### Verification pass (process record)

After the first write, every Kotlin file was re-checked line-by-line against its C# original.
Two files had material drift in the first write and were re-ported from the C# source:
ConnectionToastGate (the gate's rate limit is keyed to the last LOST showing, `Report` with
an unchanged state returns `Poll()`, and the fields are downSince/lastLostShown/lostShown)
and ReadinessPresentation (the API is per-outcome `StateOf`/`StatusKey`, per-check
`TitleKey(id, usingCloud)`/`FixHintKey(id, usingCloud)`, fix labels `rd_fix_audio_settings`/
`pf_test_audio`/…, slash routes "settings/audio", `CanStart`/`ShouldAutoRun` take the state,
`ShownPassed` is context-gated, `NotTestedYet` returns the list, `TypedQuestionsStillWork`
requires ALL failures to be connection-dependent, `TransitionLog(from, to, snapshot)`).
Smaller corrections from the same pass: HomeRoute multi-segment sections (DC-72), LocalData
file-path failure (DC-63), FocusPlacement.Parse numeric values (DC-73),
AuthFormState.resendSecondsLeft precision (DC-70). The remaining 16 files matched the C#
on the first write. Nothing in this package has been compiled or run; GitHub Actions is the
first execution check.

### P4-A parent review (2026-11-19)

Parent verified: 20/20 mirror files + UiState.kt doc-only; 103 @Test in 8 test files (+loc
fixtures); zero placeholders/corruption; zero Android/Compose imports in the logic layer.
Spot checks against the C# passed: ConnectionToastGate (grace 3 s / min interval 10 s,
Report/Poll semantics as re-ported), LiveChrome thresholds 440/580/640/700/780/880 exact,
HomeRoute page list + signed-out gate rule exact.

- **DC-64 parent resolution:** the reflective SerializationException match is replaced by a
  direct `ex is SerializationException` check; `implementation(libs.kotlinx.serialization.json)`
  was added to app/build.gradle.kts from the existing catalog (the settings model in P4-B
  needs the same dependency for settings.json). No other dependency change.
- CI note: the workflow's Gradle command gains `:app:testDebugUnitTest` from this package
  onward (P4-A push), so the UiState parity tests execute in CI.

### P4-A CI VERIFICATION (2026-11-19)

Pushed in 6 commits (head d5563ae, 188 commits). GitHub Actions run #186 SUCCESS
(1m41s): :app:testDebugUnitTest EXECUTED fresh — all 103 UiState parity tests PASSED
(8 classes: ErrorStateTests, Rc5AccountTests, Rc5LiveWindowTests, Rc5ReadinessTests,
Rc5ReadinessPageTests, Rc5SettingsTests, UiFoundationTests, UiPagesTests);
:app:assembleDebug built; :core/:client/:contracts FROM-CACHE (inputs unchanged);
parity checksums 13/13 OK. Run: https://github.com/SaEeDX4/interview-assistant-android/actions/runs/44544811179
P4-A is CI-verified complete. First-attempt green — no fix round was needed.

## Package P4-B — App platform services

The app-level services the UI and view models consume, ported from
`InterviewAssistant.App/Services/`: AppSettings, AppLog, AppPaths, Branding,
BuildFlavor, BuildInfo, SecretStore, Workspace. All in
`project/app/src/main/java/com/interviewassistant/app/services/`
(package `com.interviewassistant.app.services`, confirmed from the P1 skeleton);
the skeleton `Services.kt` now carries only the package doc comment.
Tests in `project/app/src/test/java/com/interviewassistant/app/services/` —
43 @Test methods in 6 files. Nothing in this package has been compiled or run;
GitHub Actions is the first execution check.

### Per-file checklist (C# → Kotlin)

| C# file | Lines | Kotlin file | Lines | Notes |
|---|---|---|---|---|
| AppPaths.cs | 13 | AppPaths.kt | 76 | Special folders → installed base-dir seam (DC-75); adds `install(context)`/`installBaseDirs(...)` and `baseDirectory`. |
| AppLog.cs | 64 | AppLog.kt | 99 | Ring 400, daily file, redaction regex byte-identical, `Written` event → listener list (P3 convention). |
| AppSettings.cs | 127 | AppSettings.kt | 267 | All 49 properties in C# order/defaults; migrations; atomic save; file format per DC-76 (extra lines are the STJ-spelling renderer + serializers). |
| Branding.cs | 28 | Branding.kt | 43 | Defaults + branding.json override; catch filter SerializationException \| IOException (DC-82). |
| BuildFlavor.cs | 14 | BuildFlavor.kt | 15 | `const val Developer = false` (DC-78). |
| BuildInfo.cs | 43 | BuildInfo.kt | 72 | Pure derivation unchanged; version source + IsInstalled per DC-77. |
| SecretStore.cs | 65 | SecretStore.kt | 106 | DPAPI → Keystore protector seam (DC-79); Mask/SelfTestRoundTrip semantics exact. |
| Workspace.cs | 98 | Workspace.kt | 176 | Composition over :core WorkspaceStore/PreparationAssets; `DpapiProtector` not duplicated (counterpart is platform `KeystoreDataProtector`, P2); adds `extractBundledKnowledge` platform glue (DC-80). |
| (skeleton) Services.cs — none | — | Services.kt | 16 | Package doc only; the placeholder object is gone. |

### Test mapping (Windows → Android)

- `Services/SelfTest.cs` "Settings persistence" (AnswerModel + WindowOpacity via
  OverridePath) → `AppSettingsTests.settingsPersistence_SelfTestCheck`, same values;
  extended to a full 49-property save/load round-trip (`saveLoad_RoundTripsEveryProperty`,
  the C# `Customized()` recipe executed against the real model).
- `Services/SelfTest.cs` "DPAPI credential protection round-trip"
  (`SecretStore.SelfTestRoundTrip()`, "protect/unprotect ok, wrong entropy rejected") →
  `SecretStoreTests.selfTestRoundTrip_ProtectUnprotectOk_WrongEntropyRejected` over a fake
  keyed protector, plus mask/file/clear/corrupt cases.
- `Services/SelfTest.cs` "Build information: version, channel and commit" →
  `BuildInfoTests.buildInformation_SelfTestCheck` (same assertions) + a 10-case
  derivation table (rc/beta/alpha/pre-release/stable, short/empty/long metadata, "local").
- `Rc5SettingsTests.cs` classification invariant → executed against the REAL AppSettings
  (`AppSettingsTests.everyAppSetting_IsClassifiedExactlyOnce_AgainstTheRealModel`): the
  C# test reads the 49 property names from AppSettings.cs source and compares with a
  FakeSettings mirror; here reflection over the real class is compared with the same 49
  names pinned in the test and with the three SettingsDefaults buckets.
  `ResetSection_ResetsExactlyItsFields` / `RestoreAll_...` → the same checks against the
  real model for all 7 categories (`reset_WithTheRealSettings_...`,
  `restoreAll_WithTheRealSettings_...`). (P4-A already ports these against FakeSettings;
  P4-B re-runs them against the real class, as the brief required.)
- AppSettings defaults (audit §3.1 table) → `defaults_MatchTheWindowsModel` (all 49) and
  `fontSizes_FollowThePreset` (16/14, 19/16, 22/17, 26/19).
- Migrations (AppSettings.cs Load) → 4 cases: v1 both legs, v1 user-changed models kept,
  v2 transcription-only (answer model NOT migrated — the v1 leg does not run), and a file
  without SettingsVersion (STJ leaves the default 3 → no leg runs).
- File format → `fileFormat_MatchesTheWindowsSpellings` (enum ordinal 2, "720" not
  "720.0", nulls written, declaration order, derived echoes last) and
  `fileFormat_GuidIsTheLowercaseHyphenatedForm`.
- AppLog (no dedicated Windows unit test; behavior from AppLog.cs) → 8 tests: sanitize,
  redaction (sk- + 6 kept), line format + daily file, Describe type-only rule, Error
  composition, 400-line ring, Written listeners, Prune (20-day file deleted, 14-day
  boundary kept, non-date names kept).
- Branding → defaults → override (absent keys kept) → bad JSON ignored → non-string
  value refuses the file.
- Workspace → fallback (`no_interview_prepared` label, empty KB), LoadActive refresh
  (stale pack personal/boundaries replaced by the profile's current confirmed entries;
  unconfirmed/rejected facts excluded; preferred-name label; wrong profile/target →
  fallback), viewer copies write/delete/clear under `view/<owner>`, LoadSample
  (null/missing-bank/fixture with context label).

SelfTest checks covered by this package's tests: "Settings persistence",
"DPAPI credential protection round-trip", "Build information: version, channel and
commit", and the settings half of "Profile → confirm facts → prepare → load (isolated
DPAPI workspace)" (the Workspace composition + LoadActive shape; the full
prepare-pipeline half is :core's, ported in P3). The "Shipped cloud.json: public API
endpoint only, no secrets" check targets the :app asset installed in P2, not a service
in this package.

### Divergences

- **DC-75** AppPaths: `Environment.GetFolderPath` (ApplicationData / LocalApplicationData
  / MyDocuments) → base folders installed once by the composition root
  (`install(context)`: roaming/local base = app-private files dir, documents base =
  app-specific Documents dir; `installBaseDirs` is the Context-free form tests use).
  Before install, bases fall back to a temp-dir app folder so early logging never crashes.
  `IsolatedRoot` semantics unchanged (Roaming/Local/Sessions redirected, Logs never).
  Adds `baseDirectory` = the non-isolated local folder, the `AppContext.BaseDirectory`
  counterpart where bundled assets are extracted (see DC-80).
- **DC-76** AppSettings file format (kotlinx.serialization instead of System.Text.Json,
  same options shape: indented, defaults + nulls written, unknown keys ignored):
  `FontPreset` is written as its ordinal number — the C#'s plain options register no
  string-enum converter, so the number is the wire form on both platforms (contrast
  :core models, whose C# types carry JsonStringEnumConverter and travel as names);
  `CloudDeviceId` as the lowercase hyphenated UUID string (= Guid "D" form);
  integral doubles are rendered in STJ's spelling ("720", not kotlinx's "720.0") by a
  token-aware post-pass that never touches string literals, and the two get-only derived
  properties STJ writes (AnswerFontSize, QuestionFontSize, declared last) are appended
  by the same renderer — a Windows-written file loads identically on Android and vice
  versa. Residuals: string escaping follows kotlinx (STJ's default encoder additionally
  \uXXXX-escapes non-ASCII and HTML-sensitive characters; every settings value is ASCII
  by construction — model ids, hotkey chords, language codes, ids); the line separator
  is "\n" (kotlinx) where STJ-on-Windows writes "\r\n" — parse-compatible both ways;
  an out-of-range FontPreset ordinal reads as ExtraLarge (STJ accepts any int; the
  derived-size switches fall back to the same arm) instead of refusing the file.
- **DC-77** BuildInfo: the informational-version source is the installed package's
  version name (PackageManager), installed by `install(context)`; the derivation
  (Version/Commit/Channel/Summary/ChannelLabel) is the C#'s, unchanged, and pure.
  `IsInstalled` is constantly true: the C# probes the Velopack per-user layout to
  distinguish an installed copy from an unzipped portable run — Android has no portable
  mode, every copy is package-installed.
- **DC-78** BuildFlavor.Developer: the `DEVELOPER_BUILD` compilation symbol becomes a
  source-level `const val` (false in customer builds). Flip procedure for a developer
  build: set the const to `true` and rebuild (a Gradle build-config field can replace
  the const when the release pipeline lands; the value and its consumers do not change).
- **DC-79** SecretStore: DPAPI CurrentUser + entropy "InterviewAssistant.v1.apikey" →
  the platform Keystore protector (P2) under the dedicated alias
  "interview-assistant.apikey.v1" — the alias is the entropy binding, so the
  SelfTestRoundTrip wrong-entropy leg unprotects with a different-alias protector and
  must fail (in tests both protectors are keyed fakes). The protector is an installable
  seam (`protector`, internal set) because the object is otherwise static like the C#.
  The file name "apikey.dpapi" is kept for 1:1 layout parity (contents are platform
  ciphertext on both platforms, never portable). `getApiKey`'s catch set adds
  IllegalArgumentException — the platform protector's malformed-ciphertext signal (the
  :core WorkspaceStore catch set includes it for the same reason).
- **DC-80** Workspace: (a) `DpapiProtector` (defined in Workspace.cs) is not re-declared —
  its Android counterpart is `KeystoreDataProtector` in the platform layer (P2/DC-3);
  the composition root passes it to the Workspace constructor like the C# passes
  DpapiProtector. (b) "Found next to the EXE": the shipped knowledge assets live in
  :core's bundled assets (not files), so `extractBundledKnowledge(context)` copies
  `assets/knowledge/` into `AppPaths.baseDirectory/knowledge` once (a `.stamp` file
  holding the library asset's SHA-256 re-extracts after an app update changes the
  assets); the constructor then runs the C#'s exact `FindDirectory(base, "knowledge",
  LibraryFileName)` search over real files. (c) LoadActive's personal/boundary refresh:
  the C# clears and refills the pack KB's lists in place; :core's CandidateProfile
  holds immutable lists, so the knowledge base is rebuilt with a refreshed profile
  copy — the returned KB carries the same current entries (the pack KB is itself
  rebuilt by `toKnowledgeBase()` on every call, so no shared state differs).
- **DC-81** AppLog: exception type names in `Describe` are JVM names (e.g.
  AccessDeniedException where .NET says UnauthorizedAccessException); the rule —
  IO/security exceptions by type name only, never the path-bearing message — is
  unchanged. The daily-file append newline is the platform line separator ("\n" on
  Android; C# `Environment.NewLine`). Prune deletes via `File.delete()` (boolean)
  where C# `File.Delete` throws; only successful deletions are counted, as in C#.
  Prune's date parse uses uuuuMMdd STRICT (TryParseExact counterpart).
- **DC-82** Branding: a branding.json with a JSON null value for a key is refused as a
  whole (kotlinx `Map<String, String>` rejects null values; STJ's
  `Dictionary<string, string>` would admit it and assign null). Also: the override
  directory on Android (app base directory) is not user-writable, so branding.json is
  a support/diagnostics override rather than an end-user feature; `load(baseDir)`
  keeps the C# signature and behavior for any directory.
- **DC-83** Build: `:app` now applies the kotlin-serialization plugin (catalog alias,
  same Kotlin version as :core) so the AppSettings serializer is generated in-module;
  the serialization library dependency itself was already added by the DC-64 parent
  resolution. No other dependency or build change.

### Services NOT in this package (by-name accounting)

- **AnswerServiceProbe.cs** (27) → P4-C: it is consumed by the Settings/developer-key UI
  wiring with the view models; its provider (`OpenAiChatAnswerProvider`) is already
  ported in :core (P3).
- **AudioCaptureService.cs** (223) → P5 (WASAPI loopback → Android capture routes /
  AudioPlaybackCapture per OD-4).
- **HotkeyService.cs** (74) → P5 (global hotkeys → D2 Android substitutes; the stored
  chord strings/defaults are already mirrored in AppSettings here).
- **UpdateService.cs** (194) → P6 (Velopack → the project's own release system, D4);
  `UpdatePolicy` itself is already ported in :client (P2).
- **CloudService.cs** (405) → the service client itself is the P2 :client port
  (Auth/BackendClient/CloudSession/CloudAnswerProvider + Keystore token store +
  App Link redirect in platform/Platform.kt); the app-level composition/state wiring
  lands in P4-C with MainViewModel.
- **Authenticode.cs** (108) → P6 (WinVerifyTrust signer checks → Android APK signature
  verification for the update path).
- **WindowPlacement.cs** (133) → platform substitute: Android activities have no
  user-managed window placement; the placement SETTINGS fields are mirrored in
  AppSettings (protected from reset) and the Interview Focus geometry logic is
  already mirrored in uistate/FocusPlacement (P4-A). The substitute takes effect in
  the Live shell (P4-G); nothing else of WindowPlacement has an Android counterpart.
- **SelfTest.cs** (789) → not ported as a harness: it drives the real WPF process
  (windows, Dispatcher, XAML), which has no Android counterpart. Its ~35 checks are
  distributed across the ported suites — this package covers the four listed under
  Test mapping; the UiState checks landed in P4-A, the engine/knowledge checks in
  P2/P3 (:core/:client suites), and the window/page-driving checks land with the
  P4-D…G Compose counterparts or remain Windows-only (maximize/work-area, window
  placement, XAML watermark rules).

### Compile risks (self-identified, for CI)

1. AppSettings' `@Transient` on the computed `answerFontSize`/`questionFontSize`
   vals: expected to simply exclude them from the generated serializer; if the
   plugin demands an initializer, dropping the annotation also excludes them
   (no backing field).
2. `SecretStore.protector` / `wrongEntropyProtector` and `BuildInfo.installInformationalVersion`
   use internal visibility from the unit tests (friend-module access, the standard
   Gradle/Kotlin unit-test setup).
3. `AppSettings.serializer()` is called unqualified inside the class's own companion
   (the generated member); `GuidNullableSerializer` follows the CI-verified
   `CoreDateTimeNullableSerializer` pattern from :core.
4. AppPaths/BuildInfo/Workspace reference Android classes (Context, PackageManager,
   AssetManager) behind their seams; the classes load on the JVM unit-test classpath
   (stub android.jar) and the Android paths are never invoked there.

### P4-B push record (2026-11-19)

Pushed in 4 commits (626e0a0 services, 670c9ce tests, cde76ee build plugin, d934399
empty docs commit) + corrected notes re-upload be6a98a (the first docs upload used a
stale local copy of this file — the root copy is the master; project/docs/ must be
re-copied after every append). Head after P4-B: be6a98a (193 commits). Run #191 on
be6a98a SUCCESS with all test tasks FROM-CACHE from the cde76ee run (docs-only delta);
the executing run's per-test confirmation is recorded below when checked.

### P4-B CI VERIFICATION (2026-11-19)

GitHub Actions run #189 on head cde76ee SUCCESS (1m46s): :app:testDebugUnitTest
EXECUTED fresh — services tests 43/43 PASSED (AppSettingsTests 17, AppLogTests 8,
SecretStoreTests 6, BuildInfoTests 5, WorkspaceTests 5, BrandingTests 2), alongside
the 103 P4-A UiState tests in the same task; :app:assembleDebug built; other modules
FROM-CACHE (unchanged); checksums 13/13 OK. Runs #187/#188 on the intermediate push
heads (626e0a0/670c9ce) failed as expected — the serialization plugin arrived only in
the third commit (cde76ee); the complete pushed state is green. Run #191 on the final
head be6a98a SUCCESS (docs-only delta, tests FROM-CACHE from #189).
Run: https://github.com/SaEeDX4/interview-assistant-android/actions/runs/44548569115
P4-B is CI-verified complete. :app test total now 146; project total 530 green.

## Package P4-C1 — Observable base + CloudService facade + probe

The view-model foundation and the app-level account/service facade over the P2 `:client`.
Sources read in full before porting: `ViewModels/ObservableObject.cs`,
`Services/CloudService.cs`, `Services/AnswerServiceProbe.cs`, audit-app.md §7 + §11.2,
and the actual Kotlin signatures of `:client` (Auth/BackendClient/CloudOptions/
InstallationIdentity/OAuthRedirect), `platform/Platform.kt` and the P4-B services.

### Per-file checklist

- `ViewModels/ObservableObject.cs` (28) → `viewmodels/ObservableObject.kt` (127):
  `ObservableObject` + `RelayCommand` in one file, as in the C#. VMs declare one bindable
  property per C# property (`var statusText: String by bindable("")`), see DC-84.
- `Services/CloudService.cs` (405) → `services/CloudService.kt` (643): the facade
  member-by-member, in C# order — `CloudState` (8 states, same order), options loading,
  public-config cache, RefreshAsync + Classify, the auth actions, password reset,
  re-auth (password + provider, same-user rule), sign-out, EnsureDeviceAsync, upgrade
  poll, portal, OpenExternal. C# tuples become `Pair`/`Triple`. `Describe` delegates to
  the P4-A `uistate/ErrorCopy`, as the C# delegates to `UiState.ErrorCopy`.
- `Services/AnswerServiceProbe.cs` (27) → `services/AnswerServiceProbe.kt` (45):
  same prompt ("Reply with the single word: ready"), max 5 tokens, default model
  gpt-5.4-mini, 15 s budget, `set_key_no_reply` / ProviderException message /
  `set_key_timeout` outcomes; the provider is closed after the probe (the C#'s `using`).
- `viewmodels/ViewModels.kt`: the P1 placeholder object is replaced by the package doc.
- `app/build.gradle.kts`: + `implementation(libs.okhttp)` (DC-85).

### :client signature surprises (recorded for P4-C2/C3)

- `BackendClient`'s Kotlin constructor is `(http, tokenProvider, baseUrl, version)` —
  the base URL is a parameter, not the client's `BaseAddress`; `forAuth(http, auth,
  baseUrl, clientVersion)` is the composition the facade uses.
- `SupabaseAuth` methods take NO CancellationToken (coroutine cancellation instead) and
  the interactive/reset flows take the `OAuthRedirectReceiver` as a parameter — the C#
  client constructs its loopback internally (see DC-86).
- `CloudOptions.oAuthProviders` is a `List<String>` (C#: array) — ApplyPublicConfig
  assigns the config's list directly.
- The P2 platform helper `sessionTokenStore(context)` names its file `session.bin`;
  the facade's default store follows the C# file name `session.dpapi` in the roaming
  folder, behind the same `ProtectedFileTokenStore` + `KeystoreDataProtector` (DC-85).

### Divergences

- **DC-84** Observable base: `INotifyPropertyChanged` becomes a StateFlow-backed
  `bindable` delegate — assignment keeps `Set<T>`'s equality guard and named
  notification (the name comes from the delegate's property, the `[CallerMemberName]`
  counterpart); `raise(name)` takes the name explicitly (no CallerMemberName for plain
  calls). `RelayCommand`: `canExecute()` evaluates the predicate live and `execute()`
  ignores it, exactly as in the C#; WPF's `CommandManager.RequerySuggested` broadcast
  has no Android counterpart, so requery is explicit — `raiseCanExecuteChanged()`
  re-evaluates, updates `canExecuteFlow` (the Compose-facing snapshot) and notifies
  `CanExecuteChanged` listeners.
- **DC-85** CloudService construction: the C# builds its HttpClients, DPAPI token store
  and loopback internally; the port takes them as constructor seams with the production
  wiring as defaults (the established P4-B seam pattern). Auth client: OkHttp
  `callTimeout(20s)` = `HttpClient.Timeout`; API client: connect 8 s, connection pool
  10 min, no read/call timeout = the pooled handler + infinite timeout (DC-38
  precedent). `:app` gains the catalog's OkHttp as an `implementation` dependency.
  Token store default: `ProtectedFileTokenStore(roaming/session.dpapi,
  KeystoreDataProtector)` — the P2 store class and protector, the C#'s file name.
- **DC-86** Redirect + bundled-config seams: the facade creates one redirect receiver
  per interactive/reset flow via `redirectFactory` (production: a fresh
  `AppLinkRedirectReceiver` registered in `OAuthRedirectBus`, unregistered on close)
  and closes it in a `finally` — the C# client creates/disposes its loopback inside
  the flow. The shipped cloud.json reaches `loadOptions` through the
  `bundledCloudJson` seam (default: `AppPaths.baseDirectory/cloud.json`, the
  "next to the EXE" counterpart); the search order, parse-failure-continues and
  catch-and-warn behaviour are the C#'s. `loadOptions(developer)` is internal so the
  developer-override ordering is testable (BuildFlavor.Developer is a const `false`
  in customer builds).
- **DC-87** Cancellation: `RefreshAsync`'s caller-token branch (restore the pre-refresh
  state when the state is Connecting, return false) becomes: restore identically, then
  rethrow `CancellationException` — a cancelled coroutine must stay cancelled. A
  caller-imposed `withTimeout` (the health-check pattern) therefore takes the restore
  branch, not the C#'s ServiceUnavailable arm; the client layer already converts its
  own timeouts (`AuthException("network")`, `BackendException(504)`), so the
  difference is confined to caller deadlines, where restoring is the C#'s own choice
  for its token. The probe's 15 s token source is `withTimeout`, whose
  `TimeoutCancellationException` maps to `set_key_timeout`.
- **DC-88** Upgrade poll: the 15-minute `CancellationTokenSource` becomes an internal
  supervisor-scope Job (cancelled by sign-out / a newer upgrade, as the C# cancels the
  source) plus a deadline check on the injectable `nowMillis` clock and the injectable
  `upgradeDelay`; loop shape (delay 5 s → refresh → paid check) and the `finally`
  (UpgradePending = false + Changed) are unchanged.
- **DC-89** OpenExternal + device identity: the C#'s one-step validate-and-launch
  splits into the pure gate `isOpenableExternalUrl` (absolute https + DNS host name;
  IPv4/IPv6 literals and bare numbers refused, matching `UriHostNameType.Dns`) and the
  composition-installed `urlLauncher` (Android cannot launch a browser without a
  Context; uninstalled ⇒ refuse). The launcher receives the caller's validated string
  (the C# passes the canonicalized `AbsoluteUri`). `Environment.MachineName` becomes
  manufacturer+model (`android.os.Build`) behind `deviceNameProvider`; the 1..60-char
  rule is unchanged, the fallback name is "Android device" (C#: "Windows PC"), and
  `RegisterDeviceRequest.platform` is "android" (C#: "windows-x64").

### Test mapping (52 new @Test)

- `app/viewmodels/ObservableObjectTests.kt` — 8: Set equality guard + named raise,
  Raise unconditional, flow reflection, listener removal; RelayCommand live
  evaluation, unconditional Execute, raiseCanExecuteChanged flow + notification.
  (The C# has no dedicated tests for these 28 lines; expectations are the source's
  contract, asserted directly.)
- `app/services/CloudServiceTests.kt` — 36, against fake OkHttp transports for both
  hosts (the P2 interceptor pattern): options (not-configured, bad JSON, client
  version, developer override + bad-override fall-through, forced URL); refresh
  classification (signed-out sign-in message + config applied + cache written +
  cache applied at next construction, auth-unavailable message, plans-once, 426,
  account_disabled, 500 with short reference, offline, signed-in happy path with
  device registration asserted as platform "android" + device id saved, long device
  name fallback, 401-after-refresh → SessionExpired, invalid_grant → local sign-out
  → SignedOut, auth 500 during refresh → ServiceUnavailable with the session kept);
  sign-in (config-first, wrong password), sign-up (confirmation required vs
  immediate), provider sign-in (receiver closed), reset (refused for another
  account with zero HTTP calls; happy path sets the password and refreshes),
  re-auth (same user / different user signs out / wrong password / signed out),
  SignInProvider JWT table, sign-out clearing, upgrade poll (paid reached, 15-min
  timeout with real polling, non-https checkout refused), portal, IsPaidState table,
  OpenExternal gate table.
- `app/services/AnswerServiceProbeTests.kt` — 4: first delta Ok with the exact
  prompt + default model asserted on the wire, explicit model, empty stream →
  `set_key_no_reply`, 401 → the ProviderException's message verbatim. The 15 s
  timeout arm is not run (it needs a real 15 s stall); it is the same withTimeout
  the provider tests exercise at shorter budgets.
- `client/BackendExceptionTests.kt` — 4 (see the accounting below).

### ErrorStateTests.cs — BackendException half: accounting

ErrorStateTests asserts about `Client.BackendException` only by constructing it —
(0, "network"), (504, "timeout", correlation id), (500, "") — and asserting through
`ErrorCopy.Describe`. Those assertions were already ported in P4-A
(`app/uistate/ErrorStateTests.kt`, all three Describe facts + IsUserCancellation);
nothing was missing there, and no duplicate was added. The P2 `:client` suite had
ZERO BackendException coverage, so the genuinely missing piece is the exception's own
contract that the app layer branches on (Classify's `IsUpdateRequired`, ErrorCopy's
status/code branches): `client/BackendExceptionTests.kt` pins the stored fields with
ErrorStateTests' exact constructions, `IsUpdateRequired` (426 only) and
`IsEntitlement` (402 or device_limit/concurrent_limit/allowance_exhausted) with the
derivation table from `BackendClient.cs`, and the settable `Source` diagnostic.

### Compile risks (self-identified, for CI)

1. CloudService's seam defaults call private companion functions
   (`defaultAuthHttp`, `createPlatformReceiver`, `readTextOrNull`) from default
   parameter expressions — standard Kotlin, first exercised by CI.
2. `val options/auth/api` are assigned in the `init` block (definite assignment
   across the configured/not-configured branches).
3. `loadOptions` is `internal` and read from the unit tests (friend module, the
   P4-B precedent).
4. The probe's internal 3-arg `runAsync` overload is called from the `:app` unit
   tests (friend module); the public 2-arg form delegates to it.
5. `AppLinkRedirectReceiver.create`'s factory lambda shape
   (`(String) -> String`) is taken from Platform.kt as written; if its signature
   differs at compile time this is the first place it shows.

### P4-C1 CI VERIFICATION (2026-11-19)

Pushed in 7 commits (head d0d0f1b, 200 commits). GitHub Actions run #198 SUCCESS
(2m16s): :app:testDebugUnitTest and :client:testDebugUnitTest EXECUTED fresh — new
tests 52/52 PASSED (CloudServiceTests 36, ObservableObjectTests 8,
AnswerServiceProbeTests 4, BackendExceptionTests 4); :app:assembleDebug built;
checksums 13/13 OK. Run: https://github.com/SaEeDX4/interview-assistant-android/actions/runs/44553028816
P4-C1 is CI-verified complete. Project test total: 582 green (:app 194, :client 32,
:core 345, :contracts 11).

## Package P4-C2 — MainViewModel + composition root

The Live Assistant view model (the app-wide coordinator) ported member-by-member from
the C#, plus the two platform-service seams it depends on and the Android composition
root. No VM logic is stubbed: the only not-yet-real pieces are the two seam
*implementations*, which are P5/P6 platform work by design (DC-91).

### Per-file checklist (C# → Kotlin)

- `ViewModels/MainViewModel.cs` (1,201) → `viewmodels/MainViewModel.kt` (1,676):
  the complete mirror in C# member order — ctor wiring (update timer 1 min,
  diagnostics timer 1 s with connection-gate polling, Cloud.Changed → account
  label/banner/minutes/connectivity/workspace, Loc.LanguageChanged, capture
  events), all 11 commands, the L* labels, AccountLabel by CloudState,
  ServiceBanner, the static BillingLabel (bill_* keys), minutes label/tooltip,
  fonts (AnswerFontSize auto-fit ?? preset, CoachFontSize ×1.18, MinAnswerFontSize,
  CompactSpacing → BulletMargin/AnswerLineHeight, SetFitFontSize half-step clamp),
  TextFlow RTL detection, Initialize, UseKnowledge, LoadSample, SetActive,
  OpenWorkspaceForCurrentAccount (owner rules, deferred switch during a session,
  legacy-selection migration, atomic publish), ClearUserState, SignOutAsync(quiet),
  BuildEngine (refused during a session; cloud → CloudAnswerProvider + relay
  OpenAiRealtimeTranscriber, dev → own-key providers; all engine listener lists
  wired through Post), ApplyLiveSettings, audio (OnAudioFrame meter throttle,
  capture-trouble toasts, RestartAudio), TranscriberOptions/ChatOptions,
  TestTranscriptionAsync, TestAiAsync, ToggleListeningAsync (confirm hook → lease
  flow), StopAsync (session + report), the lease family (BeginLease/StartLease
  with the device_unknown re-register retry, PublishTranscriptionContext,
  UseRelay, RelayCredentialAsync's restart wait, OnRelayRefusedAsync,
  OnLeaseEndedAsync restart + lease_* notes, EndLease), SaveReport, SaveSession,
  SubmitManualAsync, Variant, Navigate, OnAnswerStarted/ShowAnswer/SyncBullets/
  Meta (meta_* keys), ApplyStatus (the full status table + the relay-forget rule),
  RefreshDiagnostics (all lines, formats), RefreshFonts,
  ForgetTranscriptionContextAsync, DismissLiveIntro, readiness
  (ReadinessStore/context/state/InvalidateReadiness), DisposeAsync.
  `BulletVm`, `FlowDirection` (System.Windows.FlowDirection) and `Thickness`
  (System.Windows.Thickness) live in the same file.
- `Services/AudioCaptureService.cs` (223) → `services/IAudioCaptureService.kt`
  (105): the seam interface with the C# surface member-for-member, the
  `PlaybackDevice` record, the pure `ToSourceFormat` mapping (input:
  `WaveFormatInfo`, DC-92), the `PeakDb` helper and the StatusLabel computation.
  Implementation → P5.
- `Services/UpdateService.cs` (194) → `services/IUpdateService.kt` (40): the seam
  interface (Status/StatusText/IsInstalled/UpdateReady/PendingVersion/Channel/
  CanCheck/IsChecking/Changed/TickAsync/CheckNowAsync/ApplyOnExit/
  TryApplyAndRestart). Implementation → P6.
- `Views/ToastHost.cs` (`ToastKind`) → `ui/components/ToastKind.kt` (6): the enum
  the VM's Notify event carries; the P4-D ToastHost consumes the same type.
- (seams) `services/PlatformSeams.kt` (35): `INetworkAvailability` (DC-93) +
  `ProcessStats`/`ProcessStatsProvider` (DC-94).
- `App.xaml.cs` (48) → statics in `App.kt` (20) (`object App`:
  requestedExitCode/selfTestOutput/fixtureDir) + startup in
  `InterviewAssistantApplication.kt` (65): installLocTables (P2, kept),
  AppPaths.install, the exception handler (DC-98), BuildInfo.install,
  extractBundledKnowledge, Branding.load, the start log line, AppLog.prune,
  then the composition root.
- `Program.cs` (24) → `MainActivity.kt` (65): unchanged OAuth redirect wiring
  (onCreate + onNewIntent) and the P1 skeleton screen; now also obtains the
  composition root from the Application and the VM from the composition
  (lazy; first touched by the Live UI in P4-G).
- Composition root (new, no single C# file): `AppComposition.kt` (62) builds
  settings → cloud → VM in the C# startup order; `platform/CompositionPlatform.kt`
  (72) holds the Android implementations of the two small seams
  (AndroidNetworkAvailability over ConnectivityManager, AndroidProcessStats over
  Process/Debug/Runtime).

### Divergences

- DC-90 (constructor seams): the C# constructor builds Settings/Cloud/Updates
  itself and captures the WPF Dispatcher. The port takes settings/cloud/updates/
  capture as constructor parameters (built by AppComposition in the same order),
  plus `postToUi` (the BeginInvoke counterpart), the timer `CoroutineScope`,
  `INetworkAvailability`, `ProcessStatsProvider`, `workspaceFactory`/
  `protectorFactory` (the C#'s `new Workspace(root, new DpapiProtector())`),
  the `ConnectionToastGate` (the C# news it inline with a UtcNow clock) and a
  `nowMillis` clock (the `Environment.TickCount64` counterpart for the meter
  throttle). The two DispatcherTimers are coroutine delay loops on that scope;
  their tick bodies (`onUpdateTick`/`onDiagTick`) are `internal` so tests drive
  them without waiting. CancellationTokens are dropped throughout, as in every
  earlier package (coroutine cancellation instead).
- DC-91 (the two platform seams): IAudioCaptureService/IUpdateService are
  interfaces mirroring the C# classes' public surfaces exactly — not stubs.
  AppComposition supplies them through provider lambdas that throw a clear
  IllegalStateException until the real implementations land (P5 capture, P6
  updates), and builds the VM lazily, so nothing presents fake behaviour as
  real and every other VM behaviour is fully working and tested now (with
  fakes). The C#'s `#if UPDATE_TEST RunUpdateTestAsync` is a CI-only entry
  point of the Windows updater test build, not app surface — not mirrored.
- DC-92 (capture seam shape): `AudioCaptureService.ListDevices()` is static in
  C#; on the seam it is an instance member (enumerating Android devices needs
  the platform instance). `ToSourceFormat`'s NAudio `WaveFormat` input becomes
  the platform-neutral `WaveFormatInfo` (its `isFloat` pre-combines the C#'s
  two float tests: Encoding == IeeeFloat, or an extensible sub-format of
  MEDIASUBTYPE_IEEE_FLOAT); the mapping and the `Unsupported bit depth {n}`
  failure are ported exactly (NotSupportedException → UnsupportedOperationException,
  the :core precedent). `StatusLabel` is computed by the shared
  `captureStatusLabel` helper, exactly the C#'s formula.
- DC-93 (network seam): `NetworkChange.NetworkAvailabilityChanged` →
  `INetworkAvailability`; the production implementation
  (AndroidNetworkAvailability, ConnectivityManager's default-network callback:
  onAvailable → true, onLost → false) lives in the platform layer.
- DC-94 (process-stats seam): RefreshDiagnostics' Process/GC reads →
  `ProcessStatsProvider`; production AndroidProcessStats uses
  Process.getElapsedCpuTime(), Debug.getPss() × 1024 (the working-set
  counterpart), Runtime heap in use (the GC.GetTotalMemory counterpart) and
  availableProcessors().
- DC-95 (App statics): `object App` keeps the C# defaults; Android has no
  --selftest/--fixture entry point yet, so selfTestOutput/fixtureDir stay null
  (a self-test port would set them, together with the AppSettings.overridePath/
  AppPaths.isolatedRoot isolation App.xaml.cs performs — deferred with it).
- DC-96 (brush/flow/thickness): StatusBrush holds the "B.*" brush-key string
  (the port's established brush convention, cf. ReadinessSnapshot.brushKey);
  the C#'s initial Brushes.Gray maps to "B.TextDim" — the brush ApplyStatus's
  Ready arm selects, applied by BuildEngine immediately. FlowDirection and
  Thickness are small mirror types in the viewmodels package; BulletMargin
  keeps WPF doubles (P4-D maps them to Dp).
- DC-97 (raises + command construction): the C#'s setter side-effect raises
  (Question → HasQuestionText/QuestionFlowDirection, IsListening/IsPaused →
  StartPauseLabel, HistoryPosition → HistoryLabel, IsCoachView/IsProfileGap →
  LAnswer, CompactSpacing → BulletMargin/AnswerLineHeight, Adaptive →
  AdaptiveLabel) are implemented by one PropertyChanged listener registered in
  init, because the bindable delegate (DC-84) cannot run setter side-effects;
  the notifications fire on the same changes, in the same order. Raise names
  are the camelCase Kotlin property names (the delegate's own names). Commands
  are built in a final init block: RelayCommand evaluates its predicate once
  at construction, and Kotlin initializers run in textual order (in C# the
  constructor simply runs after all field initializers).
- DC-98 (startup handlers): App.xaml.cs's three exception handlers → one
  Thread default uncaught-exception handler that logs via AppLog and then
  delegates to the previous handler (Android cannot "handle and continue"
  like DispatcherUnhandledException; the platform crash behaviour is
  unchanged). Program.cs's Velopack bootstrap has no counterpart until P6.
- DC-99 (date/number formats): `_sessionStartedUtc` (DateTime, default =
  unset) → OffsetDateTime? (null = default). SaveSession's DateTime.Now →
  OffsetDateTime.now() (system zone). BillingLabel's `ToString("d", Loc.Culture)`
  → DateTimeFormatter.ofLocalizedDate(SHORT) with Loc.locale over the
  system-zone-converted value. Meta's `:0.00` and the diagnostics
  `:0`/`:0.0`/`:0.000` formats go through LanguageRegistry.numberFormat
  (Latin digits, DC-44) with fixed fraction digits and grouping off.
  C# Math.Round → Math.rint (the :core precedent).

### Test mapping (40 new @Test)

Fixture: `viewmodels/MainViewModelFakes.kt` (fake capture/update/network/
process-stats + the fixture bank: Q001/Q027 field-for-field from the shipped
sample pack) and `viewmodels/MainViewModelFixture.kt` (a real CloudService over
fake OkHttp transports — the P4-C1 pattern — plus the VM over the fakes, an
immediate UI poster and the gate's fake clock).

- `MainViewModelEngineTests` (15) — the SelfTest's VM checks against the real
  :core engine: manual question → exactly 3 distinct prepared bullets;
  spoken-style paraphrase → 3 bullets; history counter hidden without history
  + Previous disabled; previous/next navigation + labels; rework acts on the
  displayed item, adds no history entry; Coach toggle (3 keywords split by
  '·' = XAB/REWARD/INCREMENTAL, structure contains '→', back to 3 bullets);
  CanRework rules; unknown question → readable note, no "Exception"/" at ";
  ApplyLiveSettings (sensitivity/fast-cache/answer-language reach the engine);
  BuildEngine-when-idle replacement + history reset; SetFitFontSize clamping/
  half-steps + CoachFontSize + CompactSpacing margins; TextFlow (Persian
  question RTL, English LTR); DismissLiveIntro remembering; Meta with
  diagnostics (style · source · mode · match 0.00-format); TestAiAsync cloud
  path (answer + "cloud: gpt-5.4-mini" from the loaded config).
- `MainViewModelAccountTests` (15) — BillingLabel table (all 8 states +
  unknown → plan name; Canceling's period-end date in the Loc culture, and
  the null-subscription empty slot); AccountLabel/ServiceBanner by state
  (SignedIn + email/billing, TrialExhausted upgrade banner, SignedOut,
  NotConfigured + its banner, ServiceUnavailable + offline banner,
  UpdateRequired); minutes (period-only label/tooltip verbatim, today-below-
  period, daily cap line, plain label without a plan figure); the SelfTest's
  signed-out Start (note contains "Sign in", not listening) and the
  not-configured Start; SignOut quiet vs normal toast.
- `MainViewModelSessionTests` (10) — lease refusals (allowance_exhausted and
  daily_live_cap notes are the lease_* copy verbatim; a generic refusal wraps
  the description in lease_typed_still_work); device_unknown → re-register +
  retry → listening; the full session (start toast, BuildEngine refused —
  same engine instance, stop → idle, report viewer copy contains
  "microphone was OFF" + the CSP meta, the stored session's ReportJson is
  non-empty, ReportSaved fired with its id, and NO transcript file while
  SaveSessionTranscript=false); pause ends listening with the paused toast;
  connection gate (service outage → Lost after grace via the diag tick →
  Restored; relay Reconnecting/Connected the same; a sub-grace blip toasts
  nothing); the update tick carries sessionActive=false.

Deferred BY NAME:
- TestTranscriptionAsync end-to-end → P5 (needs the real capture source and a
  live transcriber connection; the VM method itself is fully ported).
- Update-service behaviour (tick/check/apply against a real updater) → P6
  (the VM's tick wiring is covered through the seam fake).
- The SelfTest's window-level checks (Quick-help overlay open/close, auto-fit
  pixel measurements, ToastHost duplicate suppression) → P4-G with the views;
  they are Compose/instrumented territory and CI has no emulator.
- The SelfTest's production-service checks (forced-URL offline run) are
  mirrored hermetically through fake transports instead of the live backend;
  the P2 real-device/live-backend items remain scheduled for P4/P6 as before.

### Compile risks (self-identified, for CI)

1. `OpenAiRealtimeTranscriber`'s primary-constructor parameter order
   (credentialProvider, options) is taken from the class header and the
   secondary constructor's delegation; the VM calls it positionally, and via
   the secondary (apiKeyProvider, options) overload with a method reference
   (`SecretStore::getApiKey`).
2. `CloudAnswerProvider` named arguments (sessionId/deviceId/purpose) from
   its P2 header; `purpose = AnswerPurposes.Manual` (contracts object).
3. `ReportBuilder.build` is called positionally in the C#'s argument order
   (memory, kb, metrics, id, started, ended, language, pack, reconnects,
   duplicatesSuppressed, profileLabel, targetLabel).
4. `internal` tick bodies + `SecretStore.protector`'s internal setter are
   read from the :app unit tests (friend module — the P4-B/C1 precedent).
5. `delay(kotlin.time.Duration)` in TestTranscriptionAsync (coroutines 1.9).
6. Behaviour (not compile) risks: the unknown-question note arrives through
   the cloud provider's error path; TestAiAsync reads `engine.current` after
   a failed answer request; the session tests start the real transcriber
   against an unreachable relay (its documented contract: startAsync never
   throws into callers) — the lease/stop assertions do not depend on it.
7. MainActivity's `viewModel` lazy val is currently unused (warning only);
   the Live UI consumes it in P4-G.

### P4-C2 CI fix round 1 (DC-100)

Run #205 on head 6eeb989 failed at :app:compileDebugKotlin — three call-site defects
in MainViewModel.kt (the ported APIs were correct; the VM's uses of them were not):
(1) CloudState imported from com.interviewassistant.client — the enum lives in
com.interviewassistant.app.services (CloudService.kt); import corrected.
(2) ConnectionToastGate constructed with a trailing lambda, which Kotlin binds to
the LAST parameter (minInterval) instead of the first (now); the clock lambda is now
passed explicitly in parentheses — same fix in MainViewModelFixture.kt.
(3) OpenAiRealtimeTranscriber constructions were ambiguous between the
credentialProvider (suspend) primary constructor and the apiKeyProvider secondary;
all four call sites now use named arguments (credentialProvider = … /
apiKeyProvider = …), following the DC-51 precedent. No behavior or expectation
changed; no ported file was modified.

### P4-C2 CI fix round 2 (DC-100, continued)

Run #208 on head d0e1ef9: main code compiled (:app:assembleDebug completed); test
compilation failed in MainViewModelFakes.kt — FakeCaptureService declares both
`override var status` (JVM setter setStatus(String)) and a helper `fun setStatus(s)`
that also notifies listeners: platform declaration clash. The helper is renamed
changeStatus (its two call sites in start/stop updated); no test uses it externally,
no behavior changed.
