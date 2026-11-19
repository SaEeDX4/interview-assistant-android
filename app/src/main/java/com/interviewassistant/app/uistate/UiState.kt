package com.interviewassistant.app.uistate

/**
 * Mirror of `InterviewAssistant.App/UiState` (TRANSFER-MAP §2/§3, layer: Mirror) — the WPF-free pure-logic
 * layer of the Windows app: navigation routes and guards, the auth form state machine, the readiness
 * snapshot/store and its presentation, settings categories and defaults, live-window chrome rules,
 * account plan view, error copy, health check, quick help and the small state holders the pages use.
 *
 * One Kotlin file per C# file, same name and member order:
 * AccountMenu, AccountView, AuthFormState, ConnectionToastGate, DirtyStateGuard, ErrorCopy,
 * FocusPlacement, HealthCheck, HistoryCounter, HomeRoute, LiveChrome, LocalData, PendingReset,
 * QuickHelp, Readiness, ReadinessPresentation, ReadinessVerdict, SectionState, SettingsCategories,
 * SettingsDefaults. Port record and divergences: docs/P4-PORT-NOTES.md (Package P4-A).
 */
