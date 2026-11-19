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
