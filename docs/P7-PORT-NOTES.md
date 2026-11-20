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

### P7 CI fix round 2 (DC-198)

Run #333 on head d1bdf64 was SUCCESS for the test suite, but the four
JaCoCo tasks were SKIPPED ("40 up-to-date"): the coverageExecData
fileTree pointed only at AGP's managed-coverage path
(outputs/unit_test_code_coverage/...), while the Gradle jacoco
plugin writes the Test task's exec data to jacoco/testDebugUnitTest
.exec. With an empty executionData set, both JacocoReport and
JacocoCoverageVerification skip themselves (@SkipWhenEmpty) — a
silently disabled gate, which this project does not accept as green.
coverageExecData in both :core and :client now includes both
candidate paths. The gate counts as enforced only when the CI log
shows the verification tasks EXECUTED.

## 10. P7 coverage closure round

CI run #336 was the first run in which the JaCoCo verification tasks
genuinely EXECUTED (after DC-197/DC-198), and both modules failed the
§5.5 floor: `:core` ≈ 0.89 line coverage, `:client` ≈ 0.49, against the
0.90 minimum in each module's `build.gradle.kts`. All 1002 tests passed;
the gap was coverage depth, not correctness. This round closes it with
TESTS ONLY: no production file was modified, the 0.90 floor was not
lowered, no class or package was excluded from coverage, and no
existing test's expected value was changed. Every new assertion pins
behaviour read from the C# counterpart in `windows-reference/` first.

### Tests added — `:client` (+94 @Test; module total 38 → 132)

Five new files under `client/src/test/java/com/interviewassistant/client/`,
using in-process fake transports at the module's existing seams (no real
sockets):

- **BackendClientTests.kt (25)** — BackendClient.kt previously had ZERO
  direct tests. Pins: config/plans are unauthenticated (the token
  provider is never consulted); `X-Client-Version` and a per-request
  32-hex correlation id on every call; base-URL trailing-slash
  normalisation; every account/device/document/session endpoint's
  method, path, request body and DTO decoding (including DTO defaults);
  deleteAccount's 204/empty body → null; the 401 rule — retried exactly
  once with a forced token refresh and a fresh correlation id, never on
  unauthenticated endpoints, never twice; the signed-out short-circuit
  (BackendException 401/`signed_out`/"Please sign in." with zero HTTP
  calls); `accessTokenAsync` force-flag delegation; ApiError body
  parsing with the `http_<status>` fallback for malformed bodies;
  IOException → offline (status 0, transport message as the code);
  timeout → 504; an undecodable 200 body throwing rather than being
  wrapped; `webSocketUri` (https→wss / http→ws, language replacing the
  query, blank language keeping the resolved query); `BackendClient.Json`
  being the shared ContractJson instance; and the SSE parser — deltas in
  order, empty/missing deltas skipped, comment lines ignored, `[DONE]`,
  an `error` event → `ai_interrupted` 502, blank line clearing the
  event name, EOF without `[DONE]` → `stream_truncated` 502, non-2xx →
  ApiError, stream-open 401 retry, and `nextDelta` staying null after
  `[DONE]`.
- **CloudSessionTests.kt (17)** — CloudSession.kt previously had ZERO
  direct tests. Pins: start-response mapping and the idempotency-key /
  relay request body; heartbeat-interval clamping to 5..120 s; the start
  retry rule (503/502/status-0 retried with the SAME idempotency key;
  a `maintenance` 503 and a 402 are NOT retried; at most 3 attempts);
  relayEndpoint/relayHeaders; `nextConnectionCredentialAsync` consuming
  the refresh flag exactly once and returning null (with no token fetch)
  once the lease has ended; heartbeat metering (-1 sentinels keep the
  previous values, RemainingChanged events, sequence numbers from 1);
  the minutes-left warning firing only for remaining in 1..300 with the
  `remaining/60 + 1` minute text; server-end handling (endReason,
  default "ended", Ended fired exactly once, later heartbeats no-ops);
  404/409/403 ending the lease with the server's error code; other
  failures counting to the `svc_unstable` warning at 3 with success
  resetting the count; stop semantics (server notified once, ends as
  "stopped", second stop a no-op, a failing stop call swallowed, no stop
  call for a server-ended lease, disposeAsync = stop).
- **CloudAnswerProviderTests.kt (16)** — CloudAnswerProvider.kt
  previously had ZERO direct tests. Pins: purpose resolution (auto with
  a live session → live, carrying sessionId/deviceId; auto without →
  manual; live; manual never consulting the session lambda; prep sending
  prepJobId and no session); an empty stream still recording its purpose;
  the `no_active_session` fallback to manual exactly once, for auto+live
  only (no fallback for live/manual purposes or other error codes; a
  fallback failure mapped from the second error); a mid-stream error
  event → ServiceUnavailable after earlier deltas were emitted; a
  truncated stream → ServiceUnavailable; caller cancellation →
  ProviderException(Cancelled); and the full `map()` taxonomy — all 28
  named codes plus the 14 status fallbacks, with code, correlationId,
  message and cause carried through.
- **AuthEdgeTests.kt (22)** — the branches CloudAuthTests did not reach.
  Pins: the interactive OAuth flow (authorize URL for the chosen
  provider; default = first configured provider; code exchanged);
  provider matching case-insensitively with URL escaping; magic-link
  failure parsing and the PKCE request fields; malformed token responses
  (JSON null → "Empty token response.", garbage → "Unexpected
  response…", empty tokens → "Incomplete token response.", a missing
  user → empty id/email; all `bad_response`); the 60-second expiry floor
  with longer expiries honoured; forced refresh bypassing the freshness
  window and publishing SessionChanged; refresh with config not loaded
  returning the stale token with zero network calls; a non-sign-out
  refresh failure rethrowing and keeping the session; `user_banned` →
  signedOutReason "disabled" and a bare `http_400` refresh failure →
  "expired"; sign-out (logout call with the Bearer token, store and
  session cleared, reason "user", offline sign-out still clearing, a
  signed-out sign-out making no request but still publishing);
  updatePassword signed-out/failure; sign-up failure; `fail()` parsing
  precedence (error_code over error, msg over error_description,
  non-string fields and non-JSON bodies falling back to `http_<status>`);
  a removed SessionChanged listener not being called; and the token
  store (missing file → null, nested-directory creation, clear
  tolerating absence).
- **ClientPrimitivesTests.kt (14)** — Pkce.kt, OAuthRedirect.kt,
  CloudOptions.kt, InstallationIdentity.kt, TimeProvider.kt. Pins: PKCE
  verifier length window 43..128 and alphabet; base64url vectors
  (`+/8=` → `-_8`, padding stripped); verifier/state shapes (64/32
  chars); UriEscape escape/unescape rules (unreserved set untouched,
  uppercase UTF-8 hex, `+` literal on decode, invalid/truncated `%`
  sequences passed through); RedirectUriParser.parseQuery (first value
  wins, `+` → space, `a=b=c`); the evaluate decision table (trailing
  slash trimmed, wrong path/state/unparseable → NotOurs,
  error_description precedence, an empty error_description not shadowing
  a code, MissingCode); browserMessageKey values; the CloudOptions
  authReady/isConfigured matrices (https or loopback only, key
  required); fromJson full/defaults/malformed-throws; InstallationIdentity
  (validity window 12..64 plus alphabet, a valid stored id reused
  untouched, invalid content regenerated as `inst-` + 32 hex); and
  TimeProvider.System (UTC now, default delay completing).

### Tests added — `:core` (+37 @Test; module total 346 → 383)

Three new files. The diagnostics and domain packages had no dedicated
test directories; both are pure logic the C# test suite exercises
indirectly at best.

- **core/diagnostics/DiagnosticsTests.kt (13)** — SessionMetrics
  (counters independent; totalSuppressed = exactly the 8 suppression
  counters; the suppression map's keys in the C#'s order; the bounded
  sample history keeping the newest entries; the cost-estimate formula;
  percentiles: empty/all-negative → -1, negatives filtered, index
  clamped), LatencySample fields, ExponentialBackoff (doubling inside
  the ±15% jitter bands, the 30 s cap, reset, custom initial/max),
  ManualClock/SystemClock monotonicity, and InstallLayout edges (the
  port's forward-slash joins asserted exactly — Path.Combine semantics
  producing a mixed-separator path; CURRENT matched case-insensitively;
  no-current-folder → false; a drive-root install checking exactly
  `C:\Update.exe`).
- **core/domain/DomainModelTests.kt (10)** — ProfileFact.setConfirmed
  transitions and timestamps (re-confirming keeps the original time;
  un-confirming a non-confirmed fact is a no-op); the review-state
  matrix by FactStatus; FactStats.of (reviewable-only totals, the
  profile-answer split); CandidateProfileRecord confirmedFacts /
  pendingReviewCount / confirmAll with and without a filter;
  PersonalCategories (21 entries, unique keys, the boundary flags on
  avoid_topic/disallowed_claim, find returning null); InterviewTarget
  .displayName; and ProfileMigration (a current profile untouched and
  reporting false; provenance gain → ResumeImport plus the document's
  file name; timestamp backfill from the profile's created/updated
  stamps; known sources never rewritten; a missing document keeping the
  existing sourceName; duplicate document ids → first name wins; a
  second migrate returning false).
- **core/domain/ProfileServiceTests.kt (14)** — createDocument (file-name
  sanitising `..\evil:name?.txt` → `evil_name_.txt`; an injection line
  removed and flagged; SHA-256 taken over the RAW unsanitised text);
  upload outcomes (empty → "File is empty.", bad PDF, unsupported type,
  binary text, scanner block, good TXT); addResume (document attached,
  migration run to schema 2, facts stamped ResumeImport); addManualFact
  (trimming, sourceName "Added by you" vs "Interview profile questions",
  confirmed default, metrics extracted); editFact (metrics recomputed,
  null fields untouched, un-confirm on edit); deleteFact (plus story
  rebuild, unknown id → false); setPersonal (create/edit/remove, the
  boundary kind, unknown category throwing "Unknown category", 600-char
  truncation); rebuildStories (only confirmed Achievement/Project facts;
  situation framing only with a company; topics/questionTypes);
  storyTopics; toEngineProfile's truth boundary (only confirmed facts;
  verified numbers including the metric and digit-only forms and period
  years; the experience total; entities excluding an unconfirmed fact's
  company); yearsOfExperience ignoring unconfirmed roles; the personal /
  boundary entry filters; and toEngineStory's field mapping.

### Local verification (not a coverage claim)

The new files were type-checked with the project's own Kotlin 2.0.21
compiler against the project's dependency versions, and executed on a
local JVM harness: all 131 new tests pass (94 `:client`, 37 `:core`),
alongside the pre-existing `:client` tests. Four pre-existing
CloudAuthTests cases that use a real loopback HTTP server cannot run in
this sandbox (loopback HttpURLConnection is broken at the VM level —
proven with a trivial JDK HttpServer round-trip returning -1); they are
untouched and pass in CI. The coverage RATIOS themselves are not claimed
here: they are re-measured by the CI JaCoCo verification tasks on the
next run, which is the only measurement §5.5 accepts.

### DC-199 — CloudAnswerProvider: a failed fallback REQUEST escapes unmapped

Found by this round's tests. In the C#, the `no_active_session`
fallback's request is lazy: `Open(...)` only creates the enumerator, so
an HTTP failure on the re-asked question surfaces from the first
`MoveNextAsync` INSIDE the inner try and is thrown as `Map(ex2)`. The
Kotlin port's `openAnswerStream` performs the request eagerly, and in
`CloudAnswerProvider.streamAsync` the fallback `open(...)` call sits
OUTSIDE the inner try — so a non-2xx answer to the fallback request
escapes `streamAsync` as a raw `BackendException` instead of the mapped
`ProviderException` every other failure becomes. Reproduced locally:
first stream 409 `no_active_session`, fallback request 429
`ai_rate_limited` → `BackendException: ai_rate_limited` propagates out
of the flow. The fix is production-side (guard the fallback open with
the same mapping) and is therefore NOT made by this tests-only round;
the round's fallback-failure test pins the first-delta failure stage,
which both implementations map identically. When the fix lands, add the
non-2xx-at-fallback-open case beside it.

### DC-200 — CloudOptions.isLoopback misses IPv6 loopback (`[::1]`)

Found by this round's tests. .NET's `Uri.IsLoopback` is true for
`http://[::1]:54321`, so a local Supabase on IPv6 loopback satisfies
`authReady`/`isConfigured` on Windows. The port compares
`java.net.URI.getHost()` against `"::1"`, but the JVM returns the host
WITH brackets (`"[::1]"`), so the comparison never matches and the URL
is judged non-loopback (verified locally against the JDK directly).
Production-side fix (strip brackets before comparing, or compare
against both forms); not made by this tests-only round. The `::1` case
is recorded here and in a comment in ClientPrimitivesTests; the
localhost and 127.0.0.1 cases are pinned as passing.

### DC-201 — BackendClient.webSocketUri double-escapes the language query

Found by this round's tests. The C# assigns
`UriBuilder.Query = "language=" + Uri.EscapeDataString(language)`
verbatim, yielding e.g. `language=pt%20BR`. The port builds the URI
with the multi-argument `java.net.URI` constructor, which quotes the
`%` of the already-escaped value a second time, producing
`language=pt%2520BR` (verified locally). Language codes in practice are
simple (`en`, `fa`, …) and need no escaping, so the live impact is
limited to values containing characters that escape — but the wire
format diverges from the C# for exactly those. Production-side fix
(build the query without re-quoting, e.g. via the raw string form);
not made by this tests-only round. The unescaped-language replacement
case is pinned in BackendClientTests; the escaped case is recorded
here for restoration with the fix.

### Still thin after this round (honest)

- `AnswerStreamSession`'s IOException-mid-read branch and
  `executeAsync`'s response-arriving-after-cancellation branch are
  reachable in tests only through timing races; the deterministic
  equivalents (offline mapping, caller cancellation) are pinned instead.
- `ProtectedFileTokenStore`'s AtomicMoveNotSupported fallback is
  filesystem-dependent and untestable in a plain JVM suite.
- `CloudSession`'s background heartbeat LOOP is exercised through
  `heartbeatOnceAsync` — the same deterministic surface the C#'s own
  design exposes — rather than by running the timed loop.
- Whether `:client`'s climb from ≈0.49 and `:core`'s from ≈0.89 clear
  0.90 is settled only by the CI re-measurement; this round's additions
  concentrate on the previously zero-coverage files (BackendClient,
  CloudSession, CloudAnswerProvider, diagnostics, domain), which is
  where the missing lines were.

### P7 fix round 3 — DC-199/DC-200/DC-201 production fixes

The coverage-closure round found three real port divergences and,
under its tests-only mandate, registered them without fixing. The
parent fixes them here, each against the C# evidence:
- DC-199 (CloudAnswerProvider): the eager fallback openAnswerStream
  call now sits inside the same try as the first moveNext, so a
  non-2xx on the re-asked question is mapped exactly as the C#'s
  lazy open maps it; the close in that catch is null-safe because
  the open itself may be what threw.
- DC-200 (CloudOptions.isLoopback): the host is stripped of IPv6
  brackets before comparison, matching .NET Uri.IsLoopback for
  http://[::1]:…; pinned by a new assertTrue in ClientPrimitivesTests.
- DC-201 (BackendClient.webSocketUri): the URI is assembled from
  the resolved URI's raw authority/path and parsed once, so an
  already-escaped language query is preserved (pt%20BR) instead of
  double-escaped (pt%2520BR); the no-language branch now uses
  rawQuery so an existing query is preserved byte-exactly too.

### P7 fix round 4 — the coverage gate must never replay a cached verdict

Run #341 attempt 1 died at plugin resolution (transient plugin-portal
failure, 15 s, before any code ran — the P3 Maven-outage class).
Attempt 2 succeeded, but its JaCoCo step reported the :client report
and verification tasks as FROM-CACHE. A coverage verdict restored
from cache is not a measurement of the current tree, so the workflow
now runs the JaCoCo step with --no-build-cache and prints the
measured LINE counters from both modules' JaCoCo XML reports into
the CI log on every run. The P7 verification record is written only
from those printed numbers.

## P7 CI VERIFICATION — COMPLETE (2026-11-19)

- Run #343 on head 60a49a1 — SUCCESS. All four JaCoCo tasks EXECUTED
  fresh (--no-build-cache, fix round 4); measured LINE counters
  printed in the CI log: :client missed 44 / covered 704 = 94.1%;
  :core missed 729 / covered 6758 = 90.3% — both at or above the
  §5.5 floor of 90%, so both jacocoTestCoverageVerification tasks
  PASSED on genuinely measured data. Checksums 13/13 OK.
  (Fix-round runs #330–#341 are recorded in DC-197/DC-198 and fix
  rounds 3–4 above; run #341 attempt 1 was a transient plugin-portal
  failure, attempt 2 succeeded.)
- Test totals on current content: :app 607 (executed fresh in run
  #333 on identical inputs; FROM-CACHE since), :core 383, :client
  132, :contracts 11 — project total 1,133 tests, all passing.
- TEST-MAP closure stands: 674/674 Windows methods accounted
  (120 backend / 495 mirrored / 59 UI-mechanics-only), 0 unaccounted;
  80/80 fixtures consumed; soak mirror verified in the suite.
- §5.5 gate items still open, stated plainly: Backend.Tests re-run
  (DC-192, environment-blocked — no dotnet SDK/PostgreSQL here) and
  the Compose semantics-tree accessibility assertions (DC-195,
  device/emulator-pending). Neither is claimed done.
