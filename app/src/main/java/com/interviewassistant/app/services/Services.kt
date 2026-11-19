package com.interviewassistant.app.services

/**
 * Mirror of `InterviewAssistant.App/Services` (TRANSFER-MAP §2) — the app-level services the view
 * models and pages consume: persisted settings, paths, the structured log, product identity, build
 * information, the developer-key secret store and the per-account workspace composition.
 *
 * One Kotlin file per C# file, same name and member order:
 * AppLog, AppPaths, AppSettings, Branding, BuildFlavor, BuildInfo, SecretStore, Workspace.
 * Port record and divergences: docs/P4-PORT-NOTES.md (Package P4-B).
 *
 * The remaining Services files land with their phases (see the P4-B accounting in the port notes):
 * AnswerServiceProbe and CloudService wiring with the view models (P4-C), AudioCaptureService and
 * HotkeyService (P5), Authenticode and UpdateService (P6). SelfTest is not ported as a harness;
 * its checks are distributed across the ported suites.
 */
