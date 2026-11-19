# SYNC-LOG — Windows ↔ Android Lockstep record

Every entry names the Windows commit mirrored, what changed on the Android side, and the
parity evidence. Procedure: TRANSFER-MAP §1.2. This file is the canonical sync record for
the Android repository (TRANSFER-MAP §6.1 places it with the parity scaffolding; it is kept
at the repository root so it is the first thing a reader of either repo finds — see P1-REPORT).

## 2026-11-18 — Baseline established, P1 started

- **Windows baseline:** `4976e16f5379d38e9b265d3db979d2f8d7d7aa21` (`4976e16`), app version
  2.1.0-rc.5. The Windows repository is read-only for all Android work; it was not modified.
- **Transfer Map completed** from a full deep-read of the Windows repo (204/204 source files
  mapped; 674 Windows test methods + 356 InlineData + 80 fixtures inventoried as the parity
  source). Map + audits live in `docs/`.
- **Android repository created public** on GitHub (owner decision OD-2, 2026-11-18: public;
  the owner may make it private later). Repository: `SaEeDX4/interview-assistant-android`.
  Nothing has been pushed from this P1 workspace yet — the skeleton was built locally first.
- **P1 — Foundation started:** Gradle multi-module skeleton per TRANSFER-MAP §6.1
  (`:app`, `:core`, `:client`, `:contracts`, `:parity`), design tokens transcribed from
  §3.1 into `app/.../ui/theme/Tokens.kt`, Shared assets (10× i18n JSON, knowledge library,
  2 playbooks, `cloud.json`) copied byte-identical and pinned in `parity/assets.sha256`.
- **Parity status:** 0 tests ported, 0 claimed passing. Porting starts with P2/P3 per module.
- **Open owner decisions (from TRANSFER-MAP §9):** OD-1 distribution channel (needed by end
  of P5), OD-3 Play signing (only if OD-1 = Play), OD-4 AudioPlaybackCapture in v1 (needed
  by P5). OD-2 is answered (public).

## Sync #1 — 2026-11-18 — P1 Foundation push (COMPLETE, build verified)
- Route: connector push-files was blocked (403 on git/trees for this repo even after the owner added it to the app installation; contents API worked). Files were therefore pushed via (a) GitHub web upload UI in the owner's signed-in browser (127 files, per-directory commits) and (b) contents API for README.md, InterviewEngine.kt, WorkspaceStore.kt, and the material3 fix.
- Remote head after sync: de4c489 (main). Total repo files: 130 (129 skeleton files incl. gradle-wrapper.jar + fixed libs.versions.toml; local.properties intentionally not pushed).
- One skeleton bug found by CI and fixed in de4c489: material3 group was androidx.compose.material; corrected to androidx.compose.material3 so the Compose BOM supplies the version.
- BUILD VERIFIED: GitHub Actions run #51 on de4c489 = SUCCESS (1m52s): :app:assembleDebug + :core/:client/:contracts testDebugUnitTest all passed.
- Windows baseline remains 4976e16 (read-only; untouched).

## Sync #2 — 2026-11-18 — P2 Shared client (CI-VERIFIED 2026-11-19)
- :contracts full port of ApiContracts.cs (27 DTOs, camelCase wire format verified from source — map §5.4 corrected).
- :client full ports: Auth/PKCE, BackendClient (21 endpoints), CloudSession, CloudAnswerProvider, CloudOptions, InstallationIdentity, UpdatePolicy, OAuthRedirect (App Link equivalent of LoopbackRedirect, D5), TimeProvider.
- Core dependency slice ported: Providers/Contracts, Loc + LanguageRegistry, ChatMessage, IDataProtector/NoProtection, InstallLayout.
- :app platform: Keystore IDataProtector + token store (D3), App Link redirect receiver (D5).
- Tests: CloudAuthTests + UpdatePolicyTests mirrored 28 methods / 47 cases; 11 contract JSON fixture tests.
- Record: docs/P2-PORT-NOTES.md (completeness checklist + divergence candidates DC-2…DC-8).
- CI verification: GitHub Actions run #84 on commit 9237f00 — SUCCESS (1m13s). :client 28/28 tests PASSED (22 CloudAuthTests + 1 InstallLayoutTests + 5 UpdatePolicyTests methods = the 28 methods / 47 cases), :app:assembleDebug built, shared-asset checksums all OK. :contracts fixtures execute fresh on the bookkeeping commit that follows this entry.
- One test (loopbackRejectsMalformedAndOversizedRequestsAndKeepsWaiting) failed repeatedly for an ENVIRONMENT reason, never a product-code one: a failed plain `async` child of runBlocking cancels the parent job and is rethrown by runBlocking past the catch around await() (isolated by a temporary probe, runs #80/#81; probe deleted after diagnosis). Fix is test-side only: the two loopback tests run under supervisorScope — the faithful analog of the C# Task observation semantics. Recorded as DC-9 in docs/P2-PORT-NOTES.md.
- Still open from the §7 P2 exit gate (stated honestly, not covered by CI): a real-device sign-in + device registration + config/plans/account fetch against the live shared backend, and a Backend.Tests re-run. Both are scheduled with the first runnable app shell (P4/P6 device testing); the shared backend itself is unchanged Windows code.

## Sync #3 — 2026-11-19 — P3 Mirror Core (push pending CI verification at time of writing)
- :core mirror COMPLETE at source level: all 64 Windows Core files ported 1:1 in four packages — A: Domain/Persistence/Ingestion; B: Knowledge/Languages/Intelligence; C: Turn/Live/Preparation/Audio/Diagnostics; D: Providers (direct)/Reports/Orchestration (InterviewEngine). 13,050 lines of Kotlin; zero skeletons, zero placeholders remain.
- Tests: 345 @Test methods in :core (48 files) mirroring the Windows Core suites, incl. all 80 audio fixtures byte-identical, the golden 97-question bank test, engine scenarios A–I via the InterviewSimulator mirror, and every by-name deferred case from A/B/C closed in D. Final accounting of all 58 Windows test files in docs/P3-PORT-NOTES.md (remainder: App/UI source-level tests → P4, Backend.Tests → shared-backend re-run).
- Divergences registered: DC-10…DC-44 (incl. DC-38 OkHttp in :core as the BCL HttpClient/WebSocket counterpart; DC-44 Latin-digit number formatting, fixed in LanguageRegistry.numberFormat; DC-10 hand-written PDF text layer vs PdfPig).
- Record: docs/P3-PORT-NOTES.md (934 lines: per-file checklists, test mappings, fixture SHA-256 tables, honest compile risks — InterviewEngine's concurrency graph is the top CI watch item).
- CI verification COMPLETE 2026-11-19: GitHub Actions run #178 on head c7f10a7 — SUCCESS (BUILD SUCCESSFUL in 2m58s). :core 345/345 tests PASSED (0 failed, 0 skipped, executed fresh), :client 28/28, :contracts 11/11 — 384/384 total; :app:assembleDebug built; shared-asset checksums 13/13 OK. Fix rounds on the way: DC-45…DC-59 (compile adaptations, DC-54 engine Job completion, DC-55 test pump yields, DC-56 duplicate-key exception family, DC-57 old-generation GC counting, DC-58 raw-source close on stream idle timeout — locally reproduced, DC-59 JSON null reads as ""). A Maven Central fetch failure (poisoned cached resolution) was cured with --refresh-dependencies plus Google's Maven Central mirror in settings.gradle.kts; the test-task timeout is 20m for the 345-test suite. Full record: docs/P3-PORT-NOTES.md, CI fix rounds 1–10.
- P3 exit-gate honesty note: the §7 gate (per-package parity tests green incl. audio fixtures, golden bank, localization coverage) is met by run #178. The §5.5 line-coverage targets are NOT wired into CI and remain unmeasured. P2's open items (real-device sign-in/device registration against the live backend; Backend.Tests re-run) remain scheduled for P4/P6 and are not closed by this run.
