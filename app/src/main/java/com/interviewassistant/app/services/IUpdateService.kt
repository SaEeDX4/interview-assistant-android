package com.interviewassistant.app.services

/**
 * Seam for `InterviewAssistant.App/Services/UpdateService.cs` (194 lines): the interface mirrors the C#
 * class's public surface member-for-member, so [com.interviewassistant.app.viewmodels.MainViewModel] is a
 * complete port now. The platform implementation (the Android update channel, TRANSFER-MAP D4) lands in
 * P6 — see P4-PORT-NOTES DC-91. The C#'s `#if UPDATE_TEST RunUpdateTestAsync` is a CI-only entry point of
 * the Windows updater test build, not part of the app surface, and is not mirrored.
 */
interface IUpdateService {
    /** English status (logs/self-test). The UI shows [statusText] in the user's language. */
    val status: String

    /** The status in the user's language (the C# renders it from an `upd_*` key + args through `Loc`). */
    val statusText: String

    val isInstalled: Boolean
    val updateReady: Boolean
    val pendingVersion: String?
    val channel: String

    /** A check can run: a signed, installed copy with an https feed. */
    val canCheck: Boolean
    val isChecking: Boolean

    /** The C#'s `Changed` event. */
    val changedListeners: MutableList<() -> Unit>

    /** Background tick (called periodically). Respects the live-session rule. */
    suspend fun tickAsync(sessionActive: Boolean)

    /** "Check for updates": the background check without its interval — never during a live session. */
    suspend fun checkNowAsync(sessionActive: Boolean)

    /** Called during shutdown: hands a downloaded update to the updater, which installs after exit. */
    fun applyOnExit()

    /** User-initiated "Restart to update" — refused (false) while a session is active. */
    fun tryApplyAndRestart(sessionActive: Boolean): Boolean
}
