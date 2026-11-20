# P7 — Parity hardening: port notes

Status: implemented locally, **not pushed, not compiled/tested** — CI is the
first execution check (this environment has no JVM, no Android SDK and no
.NET SDK; see DC-192/DC-194). Nothing below claims a green run.

Divergences continue at DC-191.

## 1. TEST-MAP closure (workstream 1)

`project/parity/TEST-MAP.md` now accounts for **all 674** Windows test methods,
enumerated mechanically from the C# sources (attribute scan of
`windows-reference/tests/`; totals reproduce §5.1 exactly: 554 + 120):

| Disposition | Count |
|---|---|
| (a) Backend — re-run unchanged | 120 |
| (b) Mirrored in Kotlin | 495 |
| (c) UI-mechanics-only, counterpart named | 59 |
| Unaccounted | **0** |

Method: name-normalised matching against all 983 pre-P7 Kotlin `@Test`
methods (472 of 554 matched by name), then hand disposition of the remaining
82 — every one is either ported in this phase (22 methods, below), matched to
a pre-existing behavioural counterpart under a different name (3: AppLog
sanitize/describe, CloudService reset refusal, RadioGroups), or recorded (c)
with its state-logic counterpart named (57 rows: WPF/XAML/WPF-code scans whose
Android counterpart is a named JVM test, a Compose semantics mechanism, or —
for pure tree assertions — DC-195).

**Ported in P7 (19 new `@Test` methods; project total 983 → 1002):**

| File | Methods | Mirrors |
|---|---|---|
| `client/.../SecurityAuditFixTests.kt` (new) | 6 | The 4 ResetLink facts + PerAddressLimit theory (5 cases as a loop) + OlderServers fact of SecurityAuditFixTests.cs — the behaviour existed in `:client` Auth.kt (P2) but had no mirror tests |
| `app/uistate/SecurityAuditFixTests.kt` (new) | 3 | PendingReset ×2 + AuthForm_InResetFlow (UiState half of the same C# file) |
| `core/persistence/SecurityAuditExportTests.kt` (edited) | +1 | `entryNamesWithATrailingNewline_AreRejected` standalone (the same input/assertion already existed as a case inside malformedBackup; the C# keeps it a separate method, now the mirror does too) |
| `app/uistate/SecurityAuditExportTests.kt` (new) | 1 | `localDataDeletion_ReportsAFolderItCouldNotRemove` (LocalData is App-layer; the C#'s Windows-only assertion branch is kept as a platform branch) |
| `app/LicenseComplianceTests.kt` (new) | 4 | LicenseComplianceTests.cs, adapted (DC-193) |
| `app/LocalizationSourceScanTests.kt` (new) | 3 | The 3 source-scanning methods of LocalizationCoverageTests.cs (see §4) |
| `app/RepositoryHygieneTests.kt` (new) | 1 | RepositoryHygieneTests.NoSecretsInRepository (see §6) |

Module totals after P7: `:app` 607, `:core` 346, `:client` 38,
`:contracts` 11 — **1002**.

### DC-191 — §5.1's InlineData total (356) is an undercount; mechanical count is 612

§5.1 states 356 `[InlineData]` expansions (33 backend + 323 tests). The
reproducible attribute count is **612** (33 + 579); the backend figure agrees
under both methods. The audit's method cannot be reconstructed (it matches
neither the attribute count nor the 425 source lines containing InlineData),
but multiple attributes routinely share one line (ShortTurnTests: 59
attributes on 30 lines — and 30 is the audit's figure for that file), so a
line-based count is the likely origin. Impact: none on the mapping itself —
cases map with their methods (theories run as case loops in the ports);
TEST-MAP.md carries the per-file attribute counts (summing to 612) as the
re-audit checklist. The map's §5.1 number should be corrected at the next
Windows-side map revision; this project does not edit the map's claims
silently, hence this DC.

### DC-192 — Backend.Tests re-run is environment-blocked here

§5.5 requires a Backend.Tests re-run. This environment has **no .NET SDK**
(`dotnet` not present) and no PostgreSQL for `IA_TEST_PG`, so the re-run did
not happen and is **not claimed**. The 120 methods carry disposition (a) in
TEST-MAP.md (the backend is shared, unchanged Windows code — this project
never modifies it). The re-run remains an open §5.5 gate item for an
environment with the SDK; client-visible behaviours are additionally covered
by the `:client`/`:app` mirrors per §5.2 rule 1.

## 2. Coverage enforcement (workstream 2)

Wired: the Gradle `jacoco` plugin in `:core` and `:client`
(`toolVersion 0.8.12`), `jacocoTestReport` (per-package XML + HTML) and
`jacocoTestCoverageVerification` with the §5.5 rule — **LINE COVEREDRATIO
≥ 0.90 per module, build fails below it**. CI (`.github/workflows/build.yml`)
runs both tasks for both modules after the test step and uploads
`**/build/reports/jacoco/**` as the `coverage-reports` artifact.
`includeNoLocationClasses` is set on the test tasks (JDK 17 lambda classes).

### DC-194 — Coverage numbers are UNVERIFIED until the first CI run; per-package targets are reported, not blind-enforced

No JVM exists in this environment, so no coverage number could be measured
locally; any number stated before CI would be invented. The §5.5 per-package
target ("Intelligence, Turn, Live, Preparation, Audio: 100% of the branches
the Windows tests exercise") is a branch-equivalence claim whose evidence is
the TEST-MAP closure itself (every Windows test for those packages is
mirrored and passing); JaCoCo cannot measure "branches the Windows tests
exercise", so the packages are **reported** per package in CI (XML/HTML in
the artifact) rather than given invented numeric rules. Follow-up rule for
the CI fix loop: if a module measures below the 0.90 floor, the gap is
closed by porting/adding faithful tests for mirrored behaviour — the floor
is never lowered silently; if a floor genuinely cannot be met, a new DC
records the measured number and the reason. `:client` is the known risk
(38 tests over 10 files; its suite is the 1:1 Windows client suite).

## 3. Fixtures in CI (workstream 3)

All **80** Windows fixture files are present byte-identical in
`:core` test resources and consumed by CI tests (verified by inspection +
a filename-reference sweep: every non-WAV resource is named in test sources;
the WAVs are consumed by index iteration):

| Fixture class | Files | Consuming test |
|---|---|---|
| Audio WAVs | 74 | `core/audio/RecordedStyleAudioTests.kt` (iterates `fixtures.json`, classifies every WAV) + `AcousticAnalyzerTests`/`AudioTests` |
| `fixtures/audio/fixtures.json` + `MANIFEST.md` | 2 | `RecordedStyleAudioTests` (index + manifest cross-check incl. KnownVoiceLookalikes) via `Fixtures.AudioFixtures` |
| `fixtures/jobs/*.txt` | 2 | `ProfileIngestionTests`, `PreparationTests`, `Fixtures.teroxxTarget` |
| `fixtures/resumes/*.txt` | 2 | `ProfileIngestionTests`, `Fixtures.shervinProfile` |
| Golden sample set (shervin-teroxx: profile, stories, 97-question bank, 107 test questions, target context, knowledge docs) | in `core/src/test/resources` | `PreparationTests` (asserts the golden bank size 97), `KnowledgeTests`, engine acceptance suites |
| `knowledge/generic_question_library.json` (52 questions) | 1 | `KnowledgeTests` via `Fixtures.knowledgeDir()` |
| Contract fixtures (literal JSON, P2) | 11 tests | `contracts/ApiContractsJsonTest.kt` |

Total `:core` test resources: 92 files, all consumed. No fixture existed
unconsumed; nothing had to be wired in.

## 4. Error-copy / localization sweep (workstream 4)

- **(i) Error copy:** `app/uistate/ErrorCopy.kt` is the mirrored
  audit-app §9 mapping and is what the UI surfaces (pinned by
  `app/uistate/ErrorStateTests.kt`). Audit for hard-coded user text: the
  only `Text("…")`-shaped literal in app main sources is a clipboard label
  (`ClipData.newPlainText("Interview Assistant", …)` — a system clipboard
  label, not UI copy); UI-facing named-argument literals are only Compose
  animation labels. The remaining English literals in platform code are
  `IOException` messages in `HomeShellEnvironment` — log/diagnostic-facing
  exception text (the C# platform layer does the same); user-visible
  failures are mapped through ErrorCopy/Loc. Now pinned by
  `LocalizationSourceScanTests` (dry-run of its exact regexes over the
  tree: 0 offenders).
- **(ii) Key sets:** verified programmatically — all 10 i18n files carry
  **1,166 keys, identical sets** (byte-identical shared assets). Already
  pinned by `core/languages/LocalizationCoverageTests.kt`
  (`englishTableIsTheReference`, `everySupportedLanguageIsComplete`,
  `noTableDefinesAKeyTwice`).
- **(iii) RTL / numbers:** `rightToLeftLanguagesAreRegisteredAsRtl` +
  `L10nDateAndBidiTests` pin fa/ar direction and bidi isolation; the DC-44
  Latin-digit rule in `LanguageRegistry.numberFormat` is untouched.
- **Key-usage scan** (Loc.t/Loc.f literals + the T/F alias files, 630 keys):
  exactly one key used but defined nowhere — `pf_title` (DC-196).

### DC-196 — `pf_title` is missing from the shared i18n tables on BOTH platforms

`PreInterviewPage.xaml.cs` (Windows) and its mirror
`PreInterviewPageControllerImpl.kt` both call `T("pf_title")` when a test
tool is started during a live session; the key exists in **none** of the 10
shared tables, so both platforms show Loc's fallback (the raw key) in that
one spot. This is a Windows-source latent gap faithfully mirrored, not an
Android-introduced string. It is allowlisted (by name, with this DC cited)
in `LocalizationSourceScanTests.everyKeyUsedInTheAppExistsInEnglish`. The
fix — adding the key to the shared tables — is a Windows lockstep change
(the assets are byte-identical by gate) and must not be made from the
Android repo alone.

## 5. Accessibility audit vs Rc5AccessibilityTests (workstream 5)

The C# file is 8 methods (7 WPF source scans + the InfoTips fact). Audit:

| Windows behaviour | Verdict | Android carrier |
|---|---|---|
| LiveRegions announce | platform-substituted | Compose `liveRegion` semantics (InlineMessage Assertive for errors, ToastHost); JVM: `Rc5ComponentAccessibilityTests.liveRegions_ErrorsAreAssertive_OnEveryAnnouncingComponent` |
| FocusableOrNamedContainers have an automation peer | platform-substituted | Compose semantics / contentDescription on focusable containers |
| StyledFocusableBorders are AccessibleBorders | platform-substituted | StepCard composable carries semantics; state: `StepCardModelTest` |
| AudioMeters are progress bars for screen readers | mirrored | LevelMeter progress semantics; JVM: `Rc5ComponentAccessibilityTests.levelMeter_TheScreenReaderValueStaysAReadOnlyPercent` |
| LiveReadinessIndicator is an icon, not a colour-only dot | mirrored | `ReadinessPresentation` (icon + state); JVM: `uistate/Rc5ReadinessTests` |
| ToggleMenuRows announce state; in-place actions keep focus | platform-substituted | More-menu rows carry `stateDescription` (components/ListItem); menu model: `LiveScreenLogicTests` |
| RadioGroups are one tab stop with arrow selection | mirrored | JVM: `Rc5ComponentAccessibilityTests.radioGroups_TheCheckedOptionIsTheOneEntry_ArrowsMoveSelectionAndFocusTogether` |
| InfoTips name the field they explain | mirrored | JVM: `Rc5ComponentAccessibilityTests.infoTips_NameTheFieldTheyExplain` (name-matched port) |

### DC-195 — Compose semantics-tree assertions are device/emulator-pending

The JVM tests above pin the semantics-carrying state and logic. Assertions
that need a live Compose semantics tree (a container actually exposing a
peer, focus actually restoring after a menu action, a live region actually
announcing) cannot run in this CI (no emulator — the same boundary recorded
in P4) and are **not claimed**. They belong to the first device/emulator
pass, alongside the P5/P6 device gate.

## 6. §8 register re-review + hygiene (workstream 6)

§8 (D1–D14) re-read line by line against the implementation:

| ID | Outcome | Evidence |
|---|---|---|
| D1 | **Confirmed** | P5: mic primary + AudioPlaybackCapture secondary, honest in-app wording (DC-169…DC-181); residual gap unchanged |
| D2 | **Confirmed** | P5 ShortcutService + FGS notification actions; stored strings/defaults verbatim |
| D3 | **Confirmed** | P2 Keystore AES-GCM behind IDataProtector, entropy strings as aliases |
| D4 | **Confirmed** | P6 update service over the Step-41 release system; same-signer via certificate-digest equality (DC-184, stricter than the C# subject comparison); installer-prompt residual stands (DC-185) |
| D5 | **Confirmed** | P2 App Links + PKCE; Custom Tabs launcher installed in production wiring in P6 (DC-186) |
| D6 | **Corrected (by DC-10)** | No PDF library was adopted: a hand-written text layer implements the line rules (P3, DC-10). The map's "Android PDF library" wording is superseded; the adversarial-PDF residual stands |
| D7 | **Resolved by OD-1** (was "decision pending") | Both channels: Paddle path identical (Custom Tabs + 5 s × 15 min poll, P6); Play Billing client built, but the Play→backend bridge does not exist and no entitlement is granted client-side (DC-187) |
| D8 | **Confirmed** | Full-screen Live + PiP + keep-screen-on (P4/P5); opacity literal-effect residual stands |
| D9 | **Confirmed** | PiP Focus; DC-174: geometry is system-owned, presets not honoured geometrically |
| D10 | **Confirmed** | SAF pickers, same default file names/slug rule, viewer copies (P4) |
| D11 | **Confirmed** | Roboto at identical sizes/weights; 34 glyph drawables (P4) |
| D12 | **Confirmed** | FGS (microphone type) + battery-exemption flow wired in P6 (DC-188); OEM-killer residual stands |
| D13 | **Confirmed** | Cap 150 mirrored, no invented floor; golden-bank test pins the mirrored behaviour |
| D14 | **Confirmed** (informational) | No action; Windows repo read-only |

**Secret sweep:** the C# hygiene regex run over the whole Android tree
(sources, docs, notes, assets; build/.git excluded): **0 offenders** — and
now pinned in CI by `app/RepositoryHygieneTests.kt`.

**Licence inventory:** `project/parity/THIRD_PARTY_NOTICES.md` lists every
dependency in the version catalog with its licence (runtime: all
Apache License 2.0; test-only JUnit is EPL-1.0), pinned by
`app/LicenseComplianceTests.kt`.

### DC-193 — Licence compliance adapted to the Gradle layout; the "ships in the release build" half is P8

The Windows tests check a `THIRD_PARTY_NOTICES.md` + `licenses/` folder
against `.csproj` PackageReferences. The Android build's package references
live in `gradle/libs.versions.toml`, so the mirror checks the catalog
against `parity/THIRD_PARTY_NOTICES.md` in both directions (notices entries
must be real dependencies; every dependency must be listed) plus the
required-licence presence rule. There is no `licenses/` folder (Android
notices name the licence per dependency rather than bundling texts — the
same information, the platform's usual form). The C#'s
`ReleaseBuildShipsTheNotices…` packaging assertion cannot run in a JVM
unit test; the mirror pins the notices' presence in the tree, and copying
them into the release package is a P8 packaging step (stated in the
notices file itself).

## 7. Soak (workstream 7)

The Windows 60-minute simulated soak (`SoakTests.cs`, 2 methods) **already
has its Kotlin mirror**: `core/orchestration/SoakTests.kt` with
`VirtualLatencyProvider`, both methods name-matched in TEST-MAP, running on
the virtual clock in CI unit-test time. Verified against the C# in this
phase: the assertions correspond (≥100 questions over ≥40 simulated
minutes, duplicates suppressed ≤ 3, exactly one answer per question,
1 reconnect, provider requests ≤ questions, provider failures handled,
history/memory bounds — memory growth < 8 MB via the DC-43 used-heap
adaptation, p95 latency bounds 1700 ms / 60 ms / 1200 ms). No port was
needed; nothing was changed.

## 8. Files changed in P7

- `project/parity/TEST-MAP.md` — full 674-row closure (was the P1 skeleton).
- `project/parity/THIRD_PARTY_NOTICES.md` — new.
- `project/core/build.gradle.kts`, `project/client/build.gradle.kts` —
  JaCoCo wiring + 0.90 verification.
- `project/.github/workflows/build.yml` — coverage step + artifact upload.
- Tests: `client/.../SecurityAuditFixTests.kt` (new, 6),
  `app/uistate/SecurityAuditFixTests.kt` (new, 3),
  `app/uistate/SecurityAuditExportTests.kt` (new, 1),
  `core/persistence/SecurityAuditExportTests.kt` (+1),
  `app/LicenseComplianceTests.kt` (new, 4),
  `app/LocalizationSourceScanTests.kt` (new, 3),
  `app/RepositoryHygieneTests.kt` (new, 1).
- No production code was changed in P7. No expected value was changed
  anywhere; every ported test keeps the C#'s inputs and expected values.

## 9. Honestly deferred (not claimed)

1. Backend.Tests re-run — DC-192 (no .NET SDK/PostgreSQL here).
2. All coverage numbers — DC-194 (first CI run measures them; the 0.90
   module floor is enforced from that run on).
3. Compose semantics-tree accessibility assertions — DC-195
   (device/emulator pass).
4. The notices-in-release-package step — DC-193 (P8 packaging).
5. The `pf_title` table fix — DC-196 (Windows lockstep change).

### P7 CI fix round 1 (DC-197)

Run #330 on head b61230f failed in :core:testDebugUnitTest before any
test executed: every Gradle Test Executor fork crashed with
NoClassDefFoundError: jdk/internal/reflect/
GeneratedSerializationConstructorAccessor1. Root cause is in the P7
JaCoCo wiring itself: the Test-task JaCoCo extension set
isIncludeNoLocationClasses = true, which makes the agent instrument
classes with no source location — including JDK-internal generated
serialization accessors, whose instrumented forms then fail to load
in the test JVM. The JaCoCo default (false) exists precisely to
exclude such classes. Both :core and :client build files now set it
to false explicitly. No test, target or threshold was changed.
