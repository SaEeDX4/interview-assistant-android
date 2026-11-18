# Interview Assistant — Android

**A 100% native Android copy of the Windows Interview Assistant** — every feature, screen,
rule, threshold, prompt and string carried over point-by-point (Kotlin + Jetpack Compose).
"100%" means *behaviour parity proven by tests*, not visual similarity (TRANSFER-MAP §0.3).

## Source of truth

- Windows app: `SaEeDX4/REAL-TIME-WINDOWS-INTERVIEW-ASSISTANT` — **READ-ONLY. Never modified,
  never committed to, never pushed to.** All Android work lives in this separate repository.
- Baseline mirrored by this project: Windows commit `4976e16f5379d38e9b265d3db979d2f8d7d7aa21`
  (app version 2.1.0-rc.5).
- Master plan: [`docs/TRANSFER-MAP.md`](docs/TRANSFER-MAP.md) — the complete file-by-file
  transfer map (204/204 files), built from the deep-read audits in `docs/audit-*.md`.

## Sync-First / Lockstep (summary)

The Android app is built so future Windows upgrades propagate mechanically, in lockstep:

- **(A) Shared** — backend, `/api/v1` contracts, prompts/config served by the backend, i18n
  JSON and knowledge assets are used *unchanged*; a Windows-side upgrade there reaches both
  platforms at once. Shared assets in this repo are byte-identical copies, pinned by
  `parity/assets.sha256`.
- **(B) Mirror** — Windows Core client logic is ported 1:1 to Kotlin with the same module,
  file and type names, and proven equal by Parity Tests derived from the Windows test suite
  (674 test methods + 356 InlineData + 80 fixtures; see `parity/TEST-MAP.md`).
- **(C) Platform** — only OS-bound pieces (audio capture, Keystore, updates, OAuth browser
  flow, …) get Android-native implementations, behind the identical behaviour specs, with
  every divergence registered in TRANSFER-MAP §8. No silent divergence is permitted.

Every Windows upgrade follows the 8-step Lockstep protocol (TRANSFER-MAP §1.2) and is
recorded in [`SYNC-LOG.md`](SYNC-LOG.md). Android releases carry the **same version number**
as the mirrored Windows release.

## Modules (TRANSFER-MAP §6.1)

| Module | Mirrors | Depends on |
|---|---|---|
| `:app` | `InterviewAssistant.App` (WPF) | `:core`, `:client` |
| `:core` | `InterviewAssistant.Core` | — |
| `:client` | `InterviewAssistant.Client` | `:core`, `:contracts` |
| `:contracts` | `InterviewAssistant.Contracts` | — |
| `:parity` | Windows `tests/**` (parity harness) | `:core`, `:contracts`, `:client` |

minSdk 29 (Android 10) — reasoning in TRANSFER-MAP §6.3. Toolchain: JDK 17, Gradle 8.11.1,
AGP 8.7.3, Kotlin 2.0.21 (pinned in `gradle/libs.versions.toml`).

## Status

Phase **P1 — Foundation** (skeleton). No product behaviour is implemented yet; placeholder
files mark the exact mirror targets from TRANSFER-MAP §2. See `../P1-REPORT.md` in the audit
workspace for the P1 verification record.
