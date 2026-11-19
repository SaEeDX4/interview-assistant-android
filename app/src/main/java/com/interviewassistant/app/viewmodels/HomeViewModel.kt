package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.AppLog
import com.interviewassistant.app.services.AppPaths
import com.interviewassistant.app.services.AppSettings
import com.interviewassistant.app.services.Branding
import com.interviewassistant.app.services.BuildFlavor
import com.interviewassistant.app.services.BuildInfo
import com.interviewassistant.app.services.CloudService
import com.interviewassistant.app.services.CloudState
import com.interviewassistant.app.services.SecretStore
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.AccountDeletion
import com.interviewassistant.app.uistate.AccountMenu
import com.interviewassistant.app.uistate.AccountMenuItem
import com.interviewassistant.app.uistate.AccountSections
import com.interviewassistant.app.uistate.AuthField
import com.interviewassistant.app.uistate.AuthFormState
import com.interviewassistant.app.uistate.AuthMode
import com.interviewassistant.app.uistate.ChangeReason
import com.interviewassistant.app.uistate.DirtyStateGuard
import com.interviewassistant.app.uistate.HealthCheck
import com.interviewassistant.app.uistate.HealthFacts
import com.interviewassistant.app.uistate.HealthOutcome
import com.interviewassistant.app.uistate.HealthRow
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.uistate.LocalData
import com.interviewassistant.app.uistate.NavigationGuard
import com.interviewassistant.app.uistate.PasswordResetCopy
import com.interviewassistant.app.uistate.PendingReset
import com.interviewassistant.app.uistate.PlanView
import com.interviewassistant.app.uistate.PlanTone
import com.interviewassistant.app.uistate.ReadinessPresentation
import com.interviewassistant.app.uistate.ReadinessSnapshot
import com.interviewassistant.app.uistate.ReadinessState
import com.interviewassistant.app.uistate.SectionState
import com.interviewassistant.app.uistate.SectionStatus
import com.interviewassistant.app.uistate.ServiceReach
import com.interviewassistant.app.uistate.SettingsCategories
import com.interviewassistant.app.uistate.SetupAction
import com.interviewassistant.app.uistate.SetupFacts
import com.interviewassistant.app.uistate.SetupPlanner
import com.interviewassistant.app.uistate.SetupStep
import com.interviewassistant.app.uistate.StepStatus
import com.interviewassistant.client.AuthException
import com.interviewassistant.client.BackendException
import com.interviewassistant.contracts.BillingStates
import com.interviewassistant.contracts.DeviceDto
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.DocumentKind
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PersonalCategories
import com.interviewassistant.core.domain.PreparationState
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.languages.LanguageInfo
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ExportFile
import com.interviewassistant.core.persistence.InvalidDataException
import com.interviewassistant.core.persistence.ProfileBundle
import com.interviewassistant.core.persistence.ProfileBundleContent
import com.interviewassistant.core.persistence.ProfileBundleError
import com.interviewassistant.core.persistence.ProfileBundleException
import com.interviewassistant.core.persistence.ProfileBundleLimits
import com.interviewassistant.core.persistence.ProfileBundlePreview
import com.interviewassistant.core.persistence.SecretFilter
import com.interviewassistant.core.persistence.StoredSession
import com.interviewassistant.core.persistence.WorkspaceStore
import com.interviewassistant.core.preparation.PreparationPipeline
import com.interviewassistant.core.reports.AccountExportContext
import com.interviewassistant.core.reports.AccountExportRenderer
import com.interviewassistant.core.reports.HtmlReportRenderer
import com.interviewassistant.core.reports.InterviewReport
import com.interviewassistant.core.reports.ProfileExportRenderer
import com.interviewassistant.core.reports.ReportSerializer
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.days

/**
 * Mirror of the Windows `HomeWindow` code-behind (all six partial classes, in the C#'s order):
 * onboarding (Get started), the Candidate Knowledge Profile, interviews, reports, account,
 * settings hosting, pre-interview hosting and help/about. Everything shown belongs to the
 * signed-in account's workspace; signed out, no user data is loaded or shown.
 *
 * The C# is a WPF window; here the same logic drives view-neutral bindable state (the shell —
 * P4-E — renders it in Compose and supplies the view seams: [dialogs], [files], [clipboard],
 * [shell], plus the hosted [settingsPage] (P4-G) and [preInterview] (P4-F) controllers).
 * Navigation is route state ([HomeRoute]) per the P4 architecture note; a language switch only
 * re-renders texts — drafts, selections, unsaved-changes state stay exactly as they were.
 * Unsaved edits (profile, interview, settings) are protected by one [NavigationGuard] that asks
 * only before real data-loss actions, through the dialog seam.
 */
class HomeViewModel(
    val vm: MainViewModel,
    private val scope: CoroutineScope,
    private val postToUi: (() -> Unit) -> Unit,
    private val dialogs: HomeDialogs,
    private val files: HomeFiles,
    private val clipboard: HomeClipboard,
    private val shell: HomeShell,
    private val settingsPage: SettingsPageController,
    private val preInterview: PreInterviewPageController,
    initial: HomeRoute? = null,
    private val codeSigner: () -> String? = { null },
    private val osVersion: () -> String = { "" },
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val nowUtc: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
    private val today: () -> LocalDate = { LocalDate.now() },
) : ObservableObject() {

    // ================= helpers (the C#'s T/F/Store/IsStorageError) =================

    private fun t(key: String): String = Loc.t(key)
    private fun f(key: String, vararg args: Any?): String = Loc.f(key, *args)

    /** The signed-in account's store — null while a workspace switch is pending, so nothing of another account is shown or changed. */
    private val store: WorkspaceStore?
        get() = if (vm.workspaceMatchesAccount) vm.workspace!!.store else null

    companion object {
        /**
         * Local storage failures (disk full, file locked, folder not writable, protected data
         * unreadable): shown with contextual copy. The C# set is IOException |
         * UnauthorizedAccessException | CryptographicException; the Android family is
         * IOException | SecurityException | GeneralSecurityException (DC-81 precedent).
         */
        internal fun isStorageError(ex: Exception): Boolean =
            ex is IOException || ex is SecurityException || ex is GeneralSecurityException

        /** The C#'s `Short`: the first [n] chars + "…" when longer. */
        internal fun short(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "…"

        /** Interviews page state label (also used by the Get started dashboard). */
        internal fun stateLabel(s: PreparationState): String = Loc.t(
            when (s) {
                PreparationState.Prepared -> "state_prepared"
                PreparationState.Preparing -> "state_preparing"
                PreparationState.Stale -> "state_stale"
                PreparationState.Failed -> "state_failed"
                else -> "state_not_prepared"
            },
        )

        /** Provider display name of the Account page (an unknown provider shows as-is). */
        internal fun providerName(p: String): String = when (p) {
            "google" -> "Google"
            "azure" -> "Microsoft"
            "github" -> "GitHub"
            "apple" -> "Apple"
            else -> p
        }

        /** Provider label of the re-authentication confirm (an unknown provider is capitalized). */
        internal fun providerLabel(provider: String): String = when (provider) {
            "google" -> "Google"
            "azure" -> "Microsoft"
            "github" -> "GitHub"
            "apple" -> "Apple"
            else -> if (provider.isNotEmpty()) provider[0].uppercaseChar() + provider.substring(1) else provider
        }

        /**
         * Wraps an e-mail address or a version number in a left-to-right isolate (U+2066 … U+2069)
         * before it goes into a localized sentence, so bidi reordering in Arabic and Persian
         * cannot scramble it.
         */
        internal fun ltrIsolated(text: String?): String = if (text.isNullOrEmpty()) "" else "\u2066" + text + "\u2069"

        // ---- HomeWindow.DataExport.cs statics ----

        /** Export file-name stamp: the local date, `yyyy-MM-dd`, invariant. */
        internal fun fileStamp(day: LocalDate): String =
            day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))

        /** File-name part from a user-chosen name: letters/digits kept (any script), everything else becomes "-". */
        internal fun fileSlug(name: String): String {
            val chars = name.trim().map { c -> if (c.isLetterOrDigit()) c else '-' }
            val slug = chars.joinToString("").split('-').filter { it.isNotEmpty() }.joinToString("-")
            return if (slug.isEmpty()) "profile" else if (slug.length > 40) slug.substring(0, 40) else slug
        }

        /** Plain-language reason a backup was refused (the C#'s `ExportDialog.Describe`). */
        internal fun describeBundleError(error: ProfileBundleError): String = Loc.t(
            when (error) {
                ProfileBundleError.NewerVersion -> "exp_import_newer"
                ProfileBundleError.TooLarge -> "exp_import_too_large"
                else -> "exp_import_invalid"
            },
        )

        /**
         * Only the unblocked delete dialog can return Delete (the C#'s
         * `DeleteAccountDialog.Choice` mapping of the raw dialog result).
         */
        internal fun deleteAccountChoice(primary: Boolean, secondary: Boolean, subscriptionBlocks: Boolean): DeleteAccountChoice = when {
            primary && !subscriptionBlocks -> DeleteAccountChoice.Delete
            secondary && subscriptionBlocks -> DeleteAccountChoice.ManageBilling
            else -> DeleteAccountChoice.Keep
        }
    }

    // ================= notifications & focus (the C#'s Notify + .Focus() calls) =================

    /** Home's own notification area (bottom-right): the C#'s `Notify` → `Toasts.Show`. */
    val notifyListeners: MutableList<(String, ToastKind, String?) -> Unit> = mutableListOf()

    fun notify(text: String, kind: ToastKind = ToastKind.Success, detail: String? = null) {
        val snapshot = synchronized(notifyListeners) { notifyListeners.toList() }
        for (listener in snapshot) listener(text, kind, detail)
    }

    /** Focus requests for the view (the C#'s scattered `.Focus()` calls; values from [HomeFocus]). */
    val focusListeners: MutableList<(String) -> Unit> = mutableListOf()

    private fun requestFocus(target: String) {
        val snapshot = synchronized(focusListeners) { focusListeners.toList() }
        for (listener in snapshot) listener(target)
    }

    // ================= core state (HomeWindow.xaml.cs) =================

    var windowTitle: String by bindable("")
        private set

    /** The current page tag (the C#'s `_page`). */
    var page: String by bindable("start")
        private set

    val profileGuard: DirtyStateGuard = DirtyStateGuard("profile")
    val targetGuard: DirtyStateGuard = DirtyStateGuard("interview")
    val sections: AccountSections = AccountSections()

    /** The one navigation guard (profile, interview, settings — registered in the C#'s order). */
    val nav: NavigationGuard = NavigationGuard { guard, _ -> askUnsavedAsync(guard) }

    private var closeConfirmed: Boolean = false
    private var prepJob: Job? = null

    // Dirty indicators (UpdateDirtyIndicators).
    var profileDirtyText: String by bindable("")
        private set
    var targetDirtyText: String by bindable("")
        private set
    var settingsDirtyText: String by bindable("")
        private set
    var settingsDirtyVisible: Boolean by bindable(false)
        private set
    var settingsSaveEnabled: Boolean by bindable(false)
        private set
    var settingsDiscardEnabled: Boolean by bindable(false)
        private set

    // UI-language picker (the window's own language box).
    var uiLanguageItems: List<LanguageInfo> by bindable(emptyList())
        private set
    var uiLanguageCode: String? by bindable(null)
        private set

    // About rows (RefreshAbout).
    var aboutProductText: String by bindable("")
        private set
    var aboutVersionText: String by bindable("")
        private set
    var aboutChannelText: String by bindable("")
        private set
    var aboutBuildText: String by bindable("")
        private set
    var aboutInstallText: String by bindable("")
        private set
    var aboutSigningText: String by bindable("")
        private set
    var aboutServiceText: String by bindable("")
        private set


    /** A field's pending-edit bookkeeping (see the init listener). */
    private fun onFieldEdited(name: String) {
        when (name) {
            "profileName", "profilePreferredName", "profileHeadline" -> profileGuard.markDirty()
            "targetTitle" -> {
                targetTitleHasError = false
                targetGuard.markDirty()
            }
            "targetCompany", "targetJd", "targetLanguageCode", "targetAnswerLanguageCode" -> targetGuard.markDirty()
            "authEmail" -> {
                emailError.clear()
                emailHasError = false
            }
            "authPassword" -> {
                passwordError.clear()
                passwordHasError = false
            }
            "authConfirmPassword" -> {
                confirmError.clear()
                confirmHasError = false
            }
        }
    }

    /** The C#'s Closed handler: subscriptions end, an unsaved audio preview and the reset wait die with the window. */
    fun close() {
        vm.cloud.removeChangedListener(cloudChangedHandler)
        vm.updates.changedListeners -= updatesChangedHandler
        vm.workspaceChangedListeners -= workspaceChangedHandler
        vm.reportSavedListeners -= reportSavedHandler
        Loc.removeLanguageChangedListener(languageChangedHandler)
        prepJob?.cancel()
        settingsPage.guard.discard?.invoke() // an unsaved audio-device preview never outlives the page
        detachReadiness() // probes and listeners of the pre-interview check stop with the window
        cancelPendingReset("window closed")
        stopResendTimer()
        diagTimerJob?.cancel()
        diagTimerJob = null
    }

    // ================= events from the main view model =================

    private val updatesChangedHandler: () -> Unit = { postToUi { refreshUpdates() } }
    private val cloudChangedHandler: () -> Unit = { postToUi { onCloudChanged() } }
    private val workspaceChangedHandler: () -> Unit = { postToUi { onWorkspaceChanged() } }
    private val reportSavedHandler: (String) -> Unit = { id -> postToUi { refreshReports(id) } }
    private val languageChangedHandler: () -> Unit = { postToUi { relocalize() } }

    private fun onCloudChanged() {
        // Any successful refresh (sign-in, upgrade polling, live session) clears a stale summary error by itself.
        val c = vm.cloud
        if (!c.isSignedIn) {
            sections.resetAll()
        } else if (!sections.summary.isLoading) {
            if (c.state == CloudState.SignedIn && c.account != null) {
                sections.summary.loaded(now())
            } else if (c.state == CloudState.ServiceUnavailable && c.lastRefreshError != null) {
                sections.summary.failed(CloudService.describe(c.lastRefreshError!!, includeReference = false))
            }
        }
        refreshAccount()
        refreshChecklist()
    }

    private fun onWorkspaceChanged() {
        // Another account's workspace: nothing of the previous one stays on screen. An explicit sign-out was
        // confirmed through the guard; when the account changed by itself the edits cannot be saved to it
        // any more — the user is told instead of losing them silently.
        if (profileGuard.isDirty || targetGuard.isDirty) notify(t("toast_edits_discarded_account"), ToastKind.Warning)
        profileGuard.markClean()
        targetGuard.markClean()
        profile = null
        target = null
        refreshAll()
        showPage(page)
    }

    /**
     * The UI language changed: re-render every text composed in code. Never navigation: no record
     * is reloaded, no list selection changes, pending edits and the unsaved-changes state stay,
     * nothing is saved. (The C# also preserves the scroll offset — the view's concern, as the
     * selection suppression is: programmatic selection writes here are plain state writes, and
     * user selections arrive only through the on*SelectionChanged entries.)
     */
    fun relocalize() {
        profileGuard.suspend().use {
            targetGuard.suspend().use {
                buildLanguagePickers()
                raise("personalTexts") // the questionnaire's headings, labels and prompts re-render from their keys
                for (r in facts) r.raiseAll()
                updateFactSummary()
                refreshProfiles()
                refreshTargets()
                refreshReports(selectedReportId())
                refreshChecklist()
                refreshAccount()
                refreshAbout()
                refreshUpdates()
                applyAuthState()
                for (m in listOf(authMessage, emailError, passwordError, confirmError, securityMessage, privacyMessage, dangerMessage, targetMessage)) {
                    m.text = Loc.relocalize(m.text) ?: m.text
                    m.detail = Loc.relocalize(m.detail) ?: m.detail
                }
                settingsPage.relocalize()
                relocalizeReadiness()
                updateDirtyIndicators()
            }
        }
    }

    // ================= unsaved changes =================

    private suspend fun askUnsavedAsync(guard: DirtyStateGuard): LeaveDecision {
        shell.activate()
        return dialogs.askUnsaved(
            t(
                when (guard.name) {
                    "profile" -> "dlg_unsaved_profile"
                    "interview" -> "dlg_unsaved_interview"
                    else -> "dlg_unsaved_settings"
                },
            ),
        )
    }

    private fun guardsOfPage(page: String): Array<DirtyStateGuard> = when (page) {
        "profiles" -> arrayOf(profileGuard)
        "interviews" -> arrayOf(targetGuard)
        "settings" -> arrayOf(settingsPage.guard)
        else -> emptyArray()
    }

    fun updateDirtyIndicators() {
        profileDirtyText = if (profileGuard.isDirty) t("profile_unsaved") else ""
        targetDirtyText = if (targetGuard.isDirty) t("profile_unsaved") else ""
        val dirty = settingsPage.guard.isDirty
        settingsDirtyText = if (dirty) t("profile_unsaved") else t("set_all_saved")
        settingsDirtyVisible = dirty
        settingsSaveEnabled = dirty
        settingsDiscardEnabled = dirty
    }

    /** The view's close request (the C#'s `OnClosing`): true = the close may proceed now. */
    fun onClosing(): Boolean {
        if (!closeConfirmed) {
            if (dialogs.isOpen) return false // answer the open dialog first
            if (nav.anyDirty) {
                scope.launch { closeAfterConfirmAsync() }
                return false
            }
        }
        savePlacement()
        return true
    }

    private suspend fun closeAfterConfirmAsync() {
        if (!nav.canProceedAsync(ChangeReason.CloseWindow)) return
        closeConfirmed = true
        shell.close()
    }

    /** True when any editor in Home holds unsaved edits (self-test: a fresh start must have none). */
    val hasUnsavedEdits: Boolean get() = nav.anyDirty

    /** The Live Assistant is closing the app: unsaved edits are asked about first. False = stay open. */
    suspend fun confirmCloseAsync(): Boolean {
        if (dialogs.isOpen) {
            shell.activate()
            return false
        }
        if (!nav.anyDirty) return true
        if (!nav.canProceedAsync(ChangeReason.CloseWindow)) return false
        closeConfirmed = true
        return true
    }

    private fun savePlacement() {
        val s = vm.settings
        val p = shell.placement()
        s.homeMaximized = p.isMaximized
        if (p.isNormal) {
            s.homeLeft = p.left
            s.homeTop = p.top
            s.homeWidth = p.width
            s.homeHeight = p.height
        }
        s.save()
    }

    // ================= navigation =================

    /** User navigation: asks about unsaved edits of the page being left; refused = nothing changes. */
    fun navigate(tag: String, section: String? = null) {
        scope.launch { navigateAsync(HomeRoute(tag, section)) }
    }

    /**
     * Deep-link navigation (Live gear, pre-interview check, account banner). The route is resolved
     * first and the page switched in one step. False = the user chose to stay.
     */
    suspend fun navigateAsync(route: HomeRoute): Boolean {
        val target = route.gate(signedOutGate)
        if (target.page != page && !nav.canProceedAsync(ChangeReason.Navigate, *guardsOfPage(page))) return false
        showRoute(target)
        return true
    }

    /** Shows a page and its section (no unsaved-changes question). */
    fun showRoute(route: HomeRoute) {
        showPage(route.page)
        if (page == "settings") settingsPage.showSection(route.section)
        if (page == "profiles" && (route.section == "personal" || route.section == "facts")) selectProfileTab(route.section)
    }

    /** Opens the Settings page (deep link from the Live Assistant gear and the pre-interview check). */
    fun showSettings(section: String? = null) = navigate("settings", section)

    val signedOutGate: Boolean get() = vm.cloud.isConfigured && vm.usingCloud && !vm.hasWorkspace

    /** The data pages' nav entries are hidden while the signed-out gate applies. */
    val navDataPagesVisible: Boolean get() = !signedOutGate

    /** Switches the visible page (no unsaved-changes question: callers that leave edits use [navigate]). */
    fun showPage(tagIn: String) {
        val tag = HomeRoute(tagIn).gate(signedOutGate).page
        val entering = tag != page
        val leftPreInterview = entering && page == "preinterview"
        page = tag
        if (tag == "account" && vm.cloud.isSignedIn) {
            scope.launch { refreshAccountSectionsAsync(summary = !sections.summary.hasData) }
        }
        if (tag == "help") refreshAbout()
        if (tag == "start") renderDashboard()
        if (tag == "reports") refreshReports(selectedReportId())
        if (tag == "settings") settingsPage.loadFromSettings() // stored values unless edits are pending
        if (leftPreInterview) preInterview.onHidden() // open-ended probes stop when the page is left
        if (entering && tag == "preinterview") preInterview.onShown() // runs the checks when there is no current result
    }

    fun refreshAll() {
        refreshUpdates()
        refreshProfiles()
        refreshReports(selectedReportId())
        refreshAccount()
        refreshChecklist()
        refreshAbout()
    }

    /** Profiles page on the Interview profile tab (onboarding step 3 and the Live window's "Add to profile"). */
    suspend fun showInterviewProfile() {
        if (page != "profiles" && !nav.canProceedAsync(ChangeReason.Navigate, *guardsOfPage(page))) return
        showPage("profiles")
        selectProfileTab("personal")
    }

    fun onSettingsSaveClick() {
        settingsPage.save()
    }

    fun onSettingsDiscardClick() {
        settingsPage.discard()
    }

    /** The window's language box: the choice persists and every open window re-renders (no restart, no reload). */
    fun onUiLanguageChanged(code: String?) {
        val l = LanguageRegistry.find(code) ?: return
        if (l.code == vm.uiLanguage) return
        uiLanguageCode = l.code
        vm.settings.uiLanguage = l.code
        vm.settings.save()
        vm.refreshLanguage() // sets Loc.Language → the language-changed listeners re-render
    }

    fun buildLanguagePickers() {
        val langs = LanguageRegistry.all.map { LangItem(it.displayName, it.code) }
        val interview = targetLanguageCode
        val answer = targetAnswerLanguageCode
        targetGuard.suspend().use {
            interviewLanguageItems = listOf(LangItem(t("lang_detect"), "auto")) + langs
            answerLanguageItems = listOf(LangItem(t("lang_same"), "same")) + langs
            targetLanguageCode = interviewLanguageItems.firstOrNull {
                it.code == (interview ?: target?.expectedLanguage ?: "auto")
            }?.code
            targetAnswerLanguageCode = answerLanguageItems.firstOrNull {
                it.code == (answer ?: target?.answerLanguage ?: "same")
            }?.code
        }
    }

    // ================= help & about (the About rows; diagnostics are the Help region) =================

    /** About values (labels are bound by the view and follow the UI language; versions and ids stay technical). */
    fun refreshAbout() {
        val signer = codeSigner()
        aboutProductText = Branding.productName
        aboutVersionText = BuildInfo.version
        aboutChannelText = BuildInfo.channelLabel
        aboutBuildText = BuildInfo.commit
        aboutInstallText = t(if (BuildInfo.isInstalled) "about_installed" else "about_portable")
        aboutSigningText = if (signer != null) f("about_signed", signer) else t("about_unsigned")
        val env = vm.cloud.config?.billingEnvironment
        aboutServiceText = if (!env.isNullOrEmpty()) {
            t(if (env == "sandbox") "about_env_sandbox" else "about_env_live")
        } else {
            t("about_env_offline")
        }
    }

    fun onCopyBuildInfoClick() {
        clipboard.setText(supportLine())
        notify(t("toast_copied"), ToastKind.Success)
    }

    fun onOpenLogsClick() {
        try {
            shell.openFolder(AppPaths.logs)
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) {
                notify(t("toast_logs_failed"), ToastKind.Error)
                return
            }
            throw ex
        }
    }

    // ================= profiles =================

    var profile: CandidateProfileRecord? = null
        private set
    var profileItems: List<ListItem<String>> by bindable(emptyList())
        private set
    var selectedProfileId: String? by bindable(null)
        private set
    var profilesEmpty: Boolean by bindable(false)
        private set

    var profileName: String by bindable("")
    var profilePreferredName: String by bindable("")
    var profileHeadline: String by bindable("")

    val profileDetailEnabled: Boolean get() = profile != null
    val exportProfileEnabled: Boolean get() = profile != null
    val deleteProfileEnabled: Boolean get() = profile != null

    var profileTab: String by bindable("facts")
        private set

    var facts: List<FactRow> by bindable(emptyList())
        private set
    var factFilter: String by bindable("all")
        private set

    /** The facts list as the C#'s collection view shows it: the active filter, unconfirmed first (stable). */
    val visibleFacts: List<FactRow>
        get() = facts
            .filter { factFilter == "all" || (factFilter == "review") != it.confirmed }
            .sortedBy { it.confirmed }

    var factsHeaderText: String by bindable("")
        private set
    var factSummaryText: String by bindable("")
        private set
    var factsEmptyVisible: Boolean by bindable(false)
        private set

    val profileMessage: InlineMessageState = InlineMessageState()

    var personalSections: List<PersonalSectionModel> by bindable(emptyList())
        private set
    private val personalTextsMap: MutableMap<String, String> = mutableMapOf()

    /** The current (possibly unsaved) answer text of one interview-profile question. */
    fun personalText(key: String): String = personalTextsMap[key] ?: ""

    /** A user edit of an interview-profile answer (marks the profile dirty, as the C#'s TextChanged does). */
    fun setPersonalText(key: String, text: String) {
        if (personalTextsMap[key] == text) return
        personalTextsMap[key] = text
        raise("personalTexts")
        profileGuard.markDirty()
    }

    /**
     * Rebuilds the profile list (labels, counts) and keeps the selection. The edited profile is
     * reloaded only when the selection really changes or [reload] asks for it (after a save or
     * an import into it).
     */
    fun refreshProfiles(selectId: String? = null, reload: Boolean = false) {
        val list = store?.profiles() ?: emptyList()
        profileItems = list.map { p ->
            ListItem(
                "${if (p.preferredName.isNotEmpty()) p.preferredName else if (p.name.isNotEmpty()) p.name else t("profile_unnamed")}  ·  " +
                    f("profile_confirmed_count", p.stats.confirmedExperience, p.stats.confirmedProfileAnswers),
                p.id,
            )
        }
        profilesEmpty = list.isEmpty()
        val id = selectId ?: profile?.id ?: vm.activeProfileId ?: list.firstOrNull()?.id
        selectedProfileId = profileItems.firstOrNull { it.value == id }?.value
        val selected = selectedProfileId
        if (reload || selected != profile?.id) loadProfile(selected?.let { store?.getProfile(it) })
    }

    /** A user selection in the profile list (programmatic rebuilds never reach this entry). */
    suspend fun onProfileSelectionChanged(next: String?) {
        if (DirtyStateGuard.classifySelection(profile?.id, next, programmatic = false) == ChangeReason.ListRefresh) return
        val current = profile?.id
        // Switching profile also reloads its interviews: both editors are asked about.
        if (!nav.canProceedAsync(ChangeReason.SwitchItem, profileGuard, targetGuard)) {
            selectedProfileId = current
            return
        }
        selectedProfileId = next
        loadProfile(next?.let { store?.getProfile(it) })
    }

    fun loadProfile(p: CandidateProfileRecord?) {
        profileGuard.suspend().use {
            profile = p
            profileName = p?.name ?: ""
            profilePreferredName = p?.preferredName ?: ""
            profileHeadline = p?.headline ?: ""
            val rows = mutableListOf<FactRow>()
            if (p != null) {
                for (fact in p.facts
                    .filter { it.isReviewable && it.kind != FactKind.Personal && it.kind != FactKind.Boundary }
                    .sortedWith(compareBy<ProfileFact> { it.kind.ordinal }.thenBy { it.company })) {
                    rows += addRow(fact)
                }
            }
            facts = rows
            loadPersonal(p)
            updateFactSummary()
        }
        profileGuard.markClean()
        refreshTargets()
    }

    private fun addRow(fact: ProfileFact): FactRow {
        val row = FactRow(fact, fact.status)
        row.addPropertyChangedListener { name ->
            if (name == "confirmed") {
                updateFactSummary()
                profileGuard.markDirty()
            }
        }
        return row
    }

    fun selectProfileTab(tab: String) {
        profileTab = tab
        raise("profileTab")
    }

    /** The optional interview-profile questions, grouped by section (all localized; nothing required). Built once; a language switch only re-renders the texts (they are keys here). */
    private fun buildPersonalFields(): List<PersonalSectionModel> =
        PersonalCategories.all.groupBy { it.section }.map { (section, categories) ->
            PersonalSectionModel(
                section = section,
                headingKey = "pcs_$section",
                fields = categories.map { c ->
                    PersonalFieldModel(key = c.key, labelKey = "pc_${c.key}_label", promptKey = "pc_${c.key}_prompt")
                },
            )
        }

    private fun loadPersonal(p: CandidateProfileRecord?) {
        profileGuard.suspend().use {
            for (section in personalSections) {
                for (field in section.fields) {
                    val kind = if (PersonalCategories.find(field.key)!!.boundary) FactKind.Boundary else FactKind.Personal
                    val text = p?.facts?.firstOrNull {
                        it.kind == kind && it.category == field.key && it.status == FactStatus.UserConfirmed
                    }?.text ?: ""
                    if (personalTextsMap[field.key] != text) {
                        personalTextsMap[field.key] = text
                        raise("personalTexts")
                    }
                }
            }
        }
    }

    internal suspend fun pickFileAsync(title: String): Pair<ByteArray?, String> {
        val picked = files.pickFile(
            title,
            t("file_filter_documents") + " (*.pdf;*.docx;*.txt;*.md)|*.pdf;*.docx;*.txt;*.md",
        ) ?: return Pair(null, "")
        if (picked.sizeBytes > 10 * 1024 * 1024) {
            dialogs.alert(t("warn_file_too_large"), DialogTone.Warning)
            return Pair(null, "")
        }
        try {
            return Pair(picked.readBytes(), picked.name)
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) {
                AppLog.warn("Document could not be read (${ex.javaClass.simpleName})")
                dialogs.alert(t("err_file_read"), DialogTone.Warning)
                return Pair(null, "")
            }
            throw ex
        }
    }

    fun onNewProfileClick() {
        scope.launch { importResumeAsync(newProfile = true) }
    }

    fun onAddResumeClick() {
        scope.launch { importResumeAsync(newProfile = profile == null) }
    }

    internal suspend fun profileLimitReachedAsync(): Boolean {
        val st = store ?: return true
        val ent = vm.cloud.account?.entitlements
        if (ent != null && st.profiles().size >= ent.maxProfiles) {
            dialogs.alert(f("warn_profile_limit", ent.maxProfiles), DialogTone.Warning)
            return true
        }
        return false
    }

    internal suspend fun importResumeAsync(newProfile: Boolean) {
        if (store == null) return
        // A new profile replaces the one being edited; a document added to it reloads it: both would drop pending edits.
        if (!nav.canProceedAsync(
                if (newProfile) ChangeReason.SwitchItem else ChangeReason.ReloadData,
                profileGuard, targetGuard,
            )
        ) return
        if (newProfile && profileLimitReachedAsync()) return
        val (bytes, name) = pickFileAsync(t("import_resume_title"))
        if (bytes == null || store == null) return
        val (doc, error) = ProfileService.upload(name, bytes, DocumentKind.Resume)
        if (doc == null) {
            dialogs.alert(error ?: t("warn_import_failed"), DialogTone.Warning)
            return
        }
        val prof = if (newProfile) CandidateProfileRecord() else store!!.getProfile(profile!!.id) ?: profile!!
        val parsed = ProfileService.addResume(prof, doc)
        try {
            store!!.saveProfile(prof)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Profile save after import failed", ex)
                dialogs.alert(t("err_profile_save"), DialogTone.Warning)
                return
            }
            throw ex
        }
        AppLog.info("Resume imported: ${parsed.facts.size} facts, ${parsed.warnings.size} warnings, flags ${doc.securityFlags.size}")
        refreshProfiles(prof.id, reload = true)
        showPage("profiles")
        selectProfileTab("facts")
        refreshChecklist()
        notify(
            if (parsed.facts.isNotEmpty()) f("toast_resume_imported", parsed.facts.size) else t("toast_resume_no_facts"),
            if (parsed.facts.isNotEmpty()) ToastKind.Success else ToastKind.Warning,
        )
        if (doc.securityFlags.isNotEmpty()) dialogs.alert(t("warn_injection_in_document"), DialogTone.Warning)
    }

    fun onNewEmptyProfileClick() {
        scope.launch { newEmptyProfileAsync() }
    }

    internal suspend fun newEmptyProfileAsync() {
        if (store == null ||
            !nav.canProceedAsync(ChangeReason.SwitchItem, profileGuard, targetGuard) ||
            profileLimitReachedAsync() || store == null
        ) return
        val p = CandidateProfileRecord(name = t("profile_new_name"))
        try {
            store!!.saveProfile(p)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("New profile could not be saved", ex)
                notify(t("err_profile_save"), ToastKind.Error)
                return
            }
            throw ex
        }
        refreshProfiles(p.id, reload = true)
        requestFocus(HomeFocus.ProfileNameSelectAll)
        notify(t("toast_profile_created"), ToastKind.Success)
    }

    fun onFactFilterClick(tag: String) {
        factFilter = tag
        updateFactSummary()
    }

    /** Header, footer and filters all use the same numbers (pending ticks included) — they can never disagree. */
    fun updateFactSummary() {
        val confirmed = facts.count { it.confirmed }
        val toReview = facts.size - confirmed
        factsHeaderText = if (profile == null) t("facts_header") else f("facts_header_count", toReview)
        factSummaryText = if (facts.isEmpty()) "" else f("facts_summary", confirmed, facts.size, toReview)
        factsEmptyVisible = profile != null && facts.isEmpty()
        raise("visibleFacts")
    }

    fun onConfirmAllClick() {
        scope.launch { confirmAllAsync() }
    }

    /**
     * Resume content becomes verified history only by explicit user action: Confirm all asks
     * first, ticks the boxes, and nothing is stored until Save profile.
     */
    internal suspend fun confirmAllAsync() {
        val pending = facts.filter { !it.confirmed }
        if (pending.isEmpty()) {
            notify(t("toast_all_confirmed"), ToastKind.Info)
            return
        }
        if (!dialogs.confirm(f("confirm_all_question", pending.size), t("dlg_confirm_facts"))) return
        for (row in pending) row.confirmed = true
        updateFactSummary()
        notify(f("toast_facts_ticked", pending.size), ToastKind.Info, t("toast_facts_ticked_detail"))
    }

    fun onAddFactClick() {
        scope.launch { addFactAsync() }
    }

    internal suspend fun addFactAsync() {
        if (profile == null) return
        val result = dialogs.editFact(null)
        if (result == null || profile == null) return
        val fact = ProfileService.addManualFact(
            profile!!, result.kind, result.text,
            company = result.company, title = result.title, period = result.period, confirmed = result.confirmed,
        )
        facts = facts + addRow(fact)
        updateFactSummary()
        profileGuard.markDirty()
    }

    fun onEditFactClick(row: FactRow) {
        scope.launch { editFactAsync(row) }
    }

    internal suspend fun editFactAsync(row: FactRow) {
        if (profile == null) return
        val result = dialogs.editFact(row)
        if (result == null || profile == null) return
        ProfileService.editFact(profile!!, row.fact, result.text, result.confirmed, result.company, result.title, result.period)
        row.confirmed = result.confirmed
        row.raiseAll()
        updateFactSummary()
        profileGuard.markDirty()
    }

    fun onDeleteFactClick(row: FactRow) {
        scope.launch { deleteFactAsync(row) }
    }

    internal suspend fun deleteFactAsync(row: FactRow) {
        if (profile == null) return
        if (!dialogs.confirm(f("confirm_delete_fact", short(row.text, 80)), t("dlg_delete"), destructive = true)) return
        ProfileService.deleteFact(profile!!, row.fact.id)
        facts = facts - row
        updateFactSummary()
        profileGuard.markDirty()
    }

    fun onSaveProfileClick() {
        saveProfile()
    }

    /** Stores every pending profile edit (also the "Save" choice of the unsaved-changes question). */
    fun saveProfile(): Boolean {
        val prof = profile
        val st = store
        if (prof == null || st == null) return false
        profileMessage.clear()
        prof.name = profileName.trim()
        prof.preferredName = profilePreferredName.trim()
        prof.headline = profileHeadline.trim()
        val now = nowUtc()
        for (r in facts) {
            if (r.confirmed) {
                r.fact.setConfirmed(true, now)
            } else if (r.fact.status == FactStatus.UserConfirmed) {
                r.fact.setConfirmed(false, now) // un-confirmed: back to "to review", not deleted
            }
        }
        for (section in personalSections) {
            for (field in section.fields) {
                ProfileService.setPersonal(prof, field.key, personalTextsMap[field.key] ?: "")
            }
        }
        ProfileService.rebuildStories(prof)
        try {
            st.saveProfile(prof)
            for (target in st.targets(prof.id)) {
                if (target.preparation == PreparationState.Prepared) {
                    target.preparation = PreparationState.Stale
                    st.saveTarget(target)
                }
            }
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                // The edits stay on screen (still unsaved): the user can free space and press Save again.
                AppLog.error("Profile save failed", ex)
                profileMessage.show(t("err_profile_save"), MessageKind.Error)
                return false
            }
            throw ex
        }
        // Personal answers apply to the Live Assistant immediately (no re-preparation needed).
        if (vm.activeProfileId == prof.id && !vm.sessionActive && vm.workspace != null) {
            val (kb, label) = vm.workspace!!.loadActive(vm.activeProfileId, vm.activeTargetId)
            vm.useKnowledge(kb, label)
        }
        profileGuard.markClean()
        vm.invalidateReadiness() // prepared interviews of this profile became stale
        val saved = prof
        refreshProfiles(saved.id) // labels and counts; the saved record stays on screen
        refreshTargets(target?.id) // preparation states may have become stale
        refreshChecklist()
        notify(
            t("toast_profile_saved"), ToastKind.Success,
            f("toast_profile_saved_detail", saved.stats.confirmedExperience, saved.stats.confirmedProfileAnswers),
        )
        return true
    }

    fun onDeleteProfileClick() {
        scope.launch { deleteProfileAsync() }
    }

    internal suspend fun deleteProfileAsync() {
        val prof = profile
        if (prof == null || store == null) return
        if (vm.sessionActive && vm.activeProfileId == prof.id) {
            dialogs.alert(t("warn_stop_first"), DialogTone.Warning)
            return
        }
        // Destructive confirmation in the in-app dialog: Cancel is the default button (Enter/Escape never delete).
        if (!dialogs.confirm(f("confirm_delete_profile", prof.name), t("delete_profile"), destructive = true) ||
            profile == null || store == null
        ) return
        val targetIds = store!!.targets(prof.id).map { it.id }
        try {
            store!!.deleteProfile(prof.id)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Profile deletion failed", ex)
                notify(t("err_profile_delete"), ToastKind.Error)
                return // nothing changed on screen: pending edits stay
            }
            throw ex
        }
        if (vm.activeProfileId == prof.id) vm.setActive(null, null)
        scope.launch { vm.forgetTranscriptionContextAsync(targetIds) }
        profileGuard.markClean()
        targetGuard.markClean() // the deletion was confirmed: its pending edits go with it
        profile = null
        refreshProfiles(reload = true)
        refreshChecklist()
        notify(t("toast_profile_deleted"), ToastKind.Info)
    }

    // ================= interviews =================

    var target: InterviewTarget? = null
        private set
    var targetItems: List<ListItem<String>> by bindable(emptyList())
        private set
    var selectedTargetId: String? by bindable(null)
        private set
    var interviewsHeaderText: String by bindable("")
        private set
    var interviewsEmptyVisible: Boolean by bindable(false)
        private set

    var targetTitle: String by bindable("")
    var targetCompany: String by bindable("")
    var targetJd: String by bindable("")
    var targetLanguageCode: String? by bindable(null)
    var targetAnswerLanguageCode: String? by bindable(null)
    var interviewLanguageItems: List<LangItem> by bindable(emptyList())
        private set
    var answerLanguageItems: List<LangItem> by bindable(emptyList())
        private set

    var targetTitleHasError: Boolean by bindable(false)
        private set
    val targetDetailEnabled: Boolean get() = profile != null
    val deleteTargetEnabled: Boolean get() = target != null
    var prepProgress: Double by bindable(0.0)
        private set
    var prepStatusText: String by bindable("")
        private set
    var prepSummaryText: String by bindable("")
        private set
    var useTargetEnabled: Boolean by bindable(false)
        private set
    var prepareIsPrimary: Boolean by bindable(true)
        private set
    var useIsPrimary: Boolean by bindable(false)
        private set
    var prepareEnabled: Boolean by bindable(true)
        private set

    val targetMessage: InlineMessageState = InlineMessageState()

    fun refreshTargets(selectId: String? = null, reload: Boolean = false) {
        interviewsHeaderText = if (profile == null) {
            t("interviews_need_profile")
        } else {
            f("interviews_for", profile!!.preferredName.ifEmpty { profile!!.name })
        }
        val st = store
        val list = if (profile == null || st == null) emptyList() else st.targets(profile!!.id)
        targetItems = list.map { tg ->
            ListItem(
                "${tg.displayName}  ·  ${stateLabel(tg.preparation)}" +
                    if (tg.id == vm.activeTargetId) "  ·  " + t("state_active") else "",
                tg.id,
            )
        }
        interviewsEmptyVisible = profile != null && list.isEmpty()
        val id = selectId ?: (if (target?.profileId == profile?.id) target?.id else null) ?: vm.activeTargetId
        selectedTargetId = targetItems.firstOrNull { it.value == id }?.value
        val selected = selectedTargetId
        // A new interview that is being typed (not saved yet) stays on screen while the list refreshes.
        val editingNew = target == null && targetGuard.isDirty && selected == null && profile != null
        if (reload || (selected != target?.id && !editingNew) ||
            (target?.profileId != profile?.id && target != null)
        ) {
            loadTarget(selected?.let { store?.getTarget(it) })
        } else {
            refreshTargetState()
        }
    }

    /** A user selection in the interview list (programmatic rebuilds never reach this entry). */
    suspend fun onTargetSelectionChanged(next: String?) {
        if (DirtyStateGuard.classifySelection(target?.id, next, programmatic = false) == ChangeReason.ListRefresh) return
        val current = target?.id
        if (!nav.canProceedAsync(ChangeReason.SwitchItem, targetGuard)) {
            selectedTargetId = current
            return
        }
        selectedTargetId = next
        loadTarget(next?.let { store?.getTarget(it) })
    }

    fun loadTarget(tg: InterviewTarget?) {
        targetGuard.suspend().use {
            target = tg
            targetTitle = tg?.jobTitle ?: ""
            targetCompany = tg?.company ?: ""
            targetJd = tg?.jobDescriptionText ?: ""
            targetLanguageCode = interviewLanguageItems.firstOrNull { it.code == (tg?.expectedLanguage ?: "auto") }?.code
            targetAnswerLanguageCode = answerLanguageItems.firstOrNull { it.code == (tg?.answerLanguage ?: "same") }?.code
            targetTitleHasError = false
            targetMessage.clear()
        }
        targetGuard.markClean()
        refreshTargetState()
    }

    /** Preparation status of the shown interview (no field is touched). */
    fun refreshTargetState() {
        val tg = target
        raise("targetDetailEnabled")
        raise("deleteTargetEnabled")
        prepProgress = if (tg?.preparation == PreparationState.Prepared) 1.0 else 0.0
        prepStatusText = if (tg == null) {
            ""
        } else {
            stateLabel(tg.preparation) + (tg.preparationError?.let { " — " + (Loc.relocalize(it) ?: it) } ?: "")
        }
        val pack = tg?.let { store?.getPack(it.id) }
        prepSummaryText = if (pack == null) {
            ""
        } else {
            f("prep_summary", pack.questions.size, pack.stories.size, pack.gaps.size, formatGeneral(pack.createdUtc))
        }
        useTargetEnabled = tg?.preparation == PreparationState.Prepared || tg?.preparation == PreparationState.Stale
        // one primary action: Prepare until the interview is prepared, then "Start interview" (Prepare stays as re-prepare)
        val prepared = tg?.preparation == PreparationState.Prepared
        prepareIsPrimary = !prepared
        useIsPrimary = prepared
        renderInterviewReadiness()
    }

    fun onNewTargetClick() {
        scope.launch { newTargetAsync() }
    }

    internal suspend fun newTargetAsync() {
        if (profile == null) {
            dialogs.alert(t("warn_create_profile_first"), DialogTone.Info)
            return
        }
        if (!nav.canProceedAsync(ChangeReason.SwitchItem, targetGuard)) return
        selectedTargetId = null
        loadTarget(null)
        requestFocus(HomeFocus.TargetTitle)
    }

    fun onImportJdClick() {
        scope.launch { importJdAsync() }
    }

    internal suspend fun importJdAsync() {
        val (bytes, name) = pickFileAsync(t("import_jd_title"))
        if (bytes == null) return
        val (doc, error) = ProfileService.upload(name, bytes, DocumentKind.JobDescription)
        if (doc == null) {
            dialogs.alert(error ?: t("warn_import_failed"), DialogTone.Warning)
            return
        }
        targetJd = doc.text // a pending edit until Save / Prepare
    }

    /** Stores the interview fields. A missing job title is shown at the field (no dialog); returns null then. */
    fun saveTargetFields(): InterviewTarget? {
        val prof = profile
        val st = store
        if (prof == null || st == null) return null
        if (targetTitle.isBlank()) {
            targetTitleHasError = true
            targetMessage.show(t("warn_job_title"), MessageKind.Error)
            if (page == "interviews") requestFocus(HomeFocus.TargetTitle)
            return null
        }
        targetMessage.clear()
        val tg = target ?: InterviewTarget(profileId = prof.id)
        val changed = tg.jobTitle != targetTitle.trim() || tg.company != targetCompany.trim() ||
            tg.jobDescriptionText != targetJd
        tg.jobTitle = targetTitle.trim()
        tg.company = targetCompany.trim()
        tg.jobDescriptionText = targetJd
        tg.expectedLanguage = targetLanguageCode ?: "auto"
        tg.answerLanguage = targetAnswerLanguageCode ?: "same"
        if (changed && tg.preparation == PreparationState.Prepared) tg.preparation = PreparationState.Stale
        try {
            st.saveTarget(tg)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Interview save failed", ex)
                targetMessage.show(t("err_interview_save"), MessageKind.Error)
                return null
            }
            throw ex
        }
        target = tg
        targetGuard.markClean()
        refreshTargets(tg.id)
        return tg
    }

    fun onSaveTargetClick() {
        val tg = saveTargetFields()
        if (tg != null) notify(t("toast_interview_saved"), ToastKind.Success, tg.displayName)
    }

    fun onPrepareClick() {
        scope.launch { prepareAsync() }
    }

    internal suspend fun prepareAsync() {
        if (!prepareEnabled) return
        val tg = saveTargetFields()
        if (tg == null || profile == null || store == null || vm.workspace == null) return
        if (profile!!.confirmedFacts.isEmpty() &&
            !dialogs.confirm(t("confirm_prepare_no_facts"), t("dlg_continue"))
        ) return
        prepareEnabled = false
        try {
            if (vm.usingCloud) {
                // Preparations are a plan entitlement metered by the service (the work itself runs on this device).
                if (!vm.cloud.isSignedIn) {
                    dialogs.alert(t("warn_sign_in_to_prepare"), DialogTone.Info)
                    navigate("account")
                    return
                }
                try {
                    val job = vm.cloud.api!!.startPreparationAsync()
                    AppLog.info("Preparation job granted (${job.prepJobsRemaining} left this month)")
                } catch (ex: BackendException) {
                    dialogs.alert(
                        if (ex.code == "prep_limit") t("warn_prep_limit") else CloudService.describe(ex),
                        DialogTone.Warning,
                    )
                    return
                }
            }
            runPreparationAsync(tg)
        } finally {
            prepareEnabled = true
        }
    }

    internal suspend fun runPreparationAsync(tg: InterviewTarget) {
        val prof = profile
        val st = store
        if (prof == null || st == null || vm.workspace == null) return
        // A new run cancels the previous one (the C#'s _prepCts): a child job of this coroutine,
        // so cancelling it never cancels the caller.
        prepJob?.cancel()
        val token = Job(currentCoroutineContext()[Job])
        prepJob = token
        tg.preparation = PreparationState.Preparing
        tg.preparationError = null
        st.saveTarget(tg)
        try {
            val pack = withContext(token) {
                PreparationPipeline(vm.workspace!!.assets).runAsync(prof, tg, null) { p ->
                    postToUi {
                        prepProgress = p.stage.toDouble() / p.totalStages
                        prepStatusText = f("prep_progress", p.stage, p.totalStages)
                    }
                }
            }
            st.savePack(pack)
            tg.preparation = PreparationState.Prepared
            tg.preparedUtc = nowUtc()
            AppLog.info("Prepared target: ${pack.questions.size} questions")
            notify(t("toast_prep_complete"), ToastKind.Success, f("toast_prep_complete_detail", pack.questions.size))
        } catch (ex: CancellationException) {
            if (token.isCancelled) {
                tg.preparation = PreparationState.NotPrepared
            } else {
                throw ex // the caller itself was cancelled: not a "preparation cancelled" outcome
            }
        } catch (ex: Exception) {
            // Any failure (I/O, encryption, network, service) ends as "failed — try again", never stuck in "preparing".
            tg.preparation = PreparationState.Failed
            tg.preparationError = if (ex is BackendException) CloudService.describe(ex) else t("prep_failed_short")
            AppLog.error("Preparation failed", ex)
            notify(t("toast_prep_failed"), ToastKind.Error)
        } finally {
            st.saveTarget(tg)
            vm.invalidateReadiness() // prepared (or failed): the last pre-interview result described the old preparation
            if (target?.id == tg.id) target = tg
            refreshTargets(tg.id)
            refreshChecklist()
            // The token is a child of the caller's job (the C#'s _prepCts needs no
            // completion signal; a Kotlin Job does — an uncompleted child keeps a
            // runBlocking caller waiting forever, DC-54's lesson).
            token.complete()
        }
    }

    fun onUseTargetClick() {
        scope.launch { useTargetAsync() }
    }

    internal suspend fun useTargetAsync() {
        if (target == null || profile == null || vm.workspace == null || !vm.workspaceMatchesAccount) return
        if (vm.sessionActive) {
            dialogs.alert(t("warn_stop_before_switch"), DialogTone.Warning)
            return
        }
        // Starting uses the stored interview: pending edits are saved or discarded first.
        if (!nav.canProceedAsync(ChangeReason.Navigate, targetGuard) ||
            target == null || profile == null || vm.workspace == null
        ) return
        vm.setActive(profile!!.id, target!!.id)
        val (kb, label) = vm.workspace!!.loadActive(profile!!.id, target!!.id)
        vm.useKnowledge(kb, label)
        refreshTargets(target!!.id)
        refreshChecklist()
        shell.showLiveWindow()
        shell.showLiveToast(f("toast_live_opened", target!!.displayName), ToastKind.Success)
    }

    fun onDeleteTargetClick() {
        scope.launch { deleteTargetAsync() }
    }

    internal suspend fun deleteTargetAsync() {
        val tg = target
        if (tg == null || store == null) return
        if (vm.sessionActive && vm.activeTargetId == tg.id) {
            dialogs.alert(t("warn_stop_first"), DialogTone.Warning)
            return
        }
        if (!dialogs.confirm(f("confirm_delete_interview", tg.displayName), t("dlg_delete"), destructive = true) ||
            target == null || store == null
        ) return
        try {
            store!!.deleteTarget(tg.id)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Interview deletion failed", ex)
                notify(t("err_interview_delete"), ToastKind.Error)
                return
            }
            throw ex
        }
        if (vm.activeTargetId == tg.id) vm.setActive(vm.activeProfileId, null)
        val id = tg.id
        scope.launch { vm.forgetTranscriptionContextAsync(listOf(id)) }
        targetGuard.markClean()
        target = null
        refreshTargets(reload = true)
        notify(t("toast_interview_deleted"), ToastKind.Info)
    }

    // ================= reports =================

    var reportRows: List<ReportRow> by bindable(emptyList())
        private set
    var selectedReportIndex: Int by bindable(-1)
    var reportsEmpty: Boolean by bindable(true)
        private set

    var reportOpenEnabled: Boolean by bindable(false)
        private set
    var reportCopyEnabled: Boolean by bindable(false)
        private set
    var reportExportEnabled: Boolean by bindable(false)
        private set
    var reportDeleteEnabled: Boolean by bindable(false)
        private set
    var reportSelectStateVisible: Boolean by bindable(true)
        private set
    var reportPreviewVisible: Boolean by bindable(false)
        private set
    var reportPreviewTitle: String by bindable("")
        private set
    var reportPreviewMeta: String by bindable("")
        private set
    var reportPreviewBody: String by bindable("")
        private set

    val selectedRow: ReportRow? get() = reportRows.getOrNull(selectedReportIndex)

    fun selectedReportId(): String? = selectedRow?.session?.id

    /**
     * Newest first; called on page open and automatically right after Stop saves a report (which
     * selects it). Keeps the selection. No reports: the empty state replaces both panes.
     */
    fun refreshReports(selectId: String? = null) {
        val sessions = store?.sessions() ?: emptyList()
        val rows = sessions
            .filter { it.reportJson.isNotEmpty() }
            .sortedByDescending { it.endedUtc ?: it.startedUtc }
            .map { reportRowFor(it) }
        reportRows = rows
        selectedReportIndex = rows.indexOfFirst { it.session.id == selectId }
        reportsEmpty = rows.isEmpty()
        showSelectedReport()
    }

    private fun reportRowFor(s: StoredSession): ReportRow {
        val r = ReportSerializer.fromJson(s.reportJson)
        val targetTitle = store?.getTarget(s.targetId)?.displayName
        val title = if (!targetTitle.isNullOrEmpty()) {
            targetTitle
        } else if (!r?.targetLabel.isNullOrEmpty()) {
            r!!.targetLabel
        } else {
            t("report_default_label")
        }
        return ReportRow(
            session = s,
            report = r,
            whenText = formatGeneral(s.startedUtc),
            title = title,
            questions = if (r == null) t("report_unreadable_short") else f("report_row_questions", r.questions.size),
        )
    }

    /** A user selection in the reports list. */
    fun onReportSelectionChanged(index: Int) {
        selectedReportIndex = index
        showSelectedReport()
    }

    /** Preview pane: "Select a report" without a selection; otherwise the toolbar and the summary of the selected report. */
    fun showSelectedReport() {
        val row = selectedRow
        val r = row?.report
        reportOpenEnabled = r != null
        reportCopyEnabled = r != null
        reportExportEnabled = r != null
        reportDeleteEnabled = row != null
        reportSelectStateVisible = row == null
        reportPreviewVisible = row != null
        if (row == null) return
        if (r == null) {
            // A damaged report can still be deleted; nothing else is offered.
            reportPreviewTitle = row.title
            reportPreviewMeta = row.whenText
            reportPreviewBody = t("report_unreadable")
            return
        }
        val header = HtmlReportRenderer.header(r.profileLabel, r.targetLabel)
        reportPreviewTitle = if (header.isNotEmpty()) header else row.title
        reportPreviewMeta = f(
            "report_preview_meta", formatGeneral(r.startedUtc),
            Duration.between(r.startedUtc, r.endedUtc).toMinutes().toInt(), r.questions.size,
        )
        reportPreviewBody = r.questions.joinToString("\n") { q ->
            f("report_q_prefix", q.index) + " " + short(q.originalWording, 160)
        }
    }

    private fun selectedReport(): Pair<InterviewReport?, StoredSession?> =
        Pair(selectedRow?.report, selectedRow?.session)

    fun onOpenReportClick() {
        val (r, s) = selectedReport()
        if (r == null || s == null) return
        if (vm.workspace == null || !vm.workspaceMatchesAccount) return
        try {
            // Viewer copy in this account's private folder (not Documents); removed when the workspace closes.
            val path = vm.workspace!!.writeViewerCopy(s.id, HtmlReportRenderer.render(r, Branding.productName))
            shell.openFile(path)
        } catch (ex: Exception) {
            if (isStorageError(ex) || ex is IllegalStateException) {
                AppLog.error("Report could not be opened", ex)
                notify(t("err_report_open"), ToastKind.Error)
                return
            }
            throw ex
        }
    }

    fun onCopyReportClick() {
        val (r, _) = selectedReport()
        if (r == null) return
        try {
            clipboard.setText(HtmlReportRenderer.renderText(r, Branding.productName))
        } catch (ex: ClipboardBusyException) {
            AppLog.warn("Clipboard busy: ${ex.javaClass.simpleName}")
            notify(t("err_report_copy"), ToastKind.Error)
            return
        }
        notify(t("toast_report_copied"), ToastKind.Success)
    }

    fun onExportReportClick() {
        scope.launch { exportReportAsync() }
    }

    internal suspend fun exportReportAsync() {
        val (r, _) = selectedReport()
        if (r == null) return
        val path = files.saveFile("interview-report.html", t("file_filter_html") + " (*.html)|*.html") ?: return
        try {
            File(path).writeText(HtmlReportRenderer.render(r, Branding.productName), Charsets.UTF_8)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Report export failed", ex)
                notify(t("err_report_save"), ToastKind.Error)
                return
            }
            throw ex
        }
        notify(t("toast_report_exported"), ToastKind.Success)
    }

    fun onDeleteReportClick() {
        scope.launch { deleteReportAsync() }
    }

    internal suspend fun deleteReportAsync() {
        val row = selectedRow
        if (row == null || store == null) return
        val index = selectedReportIndex
        // Destructive confirmation in the in-app dialog: Cancel is the default button.
        if (!dialogs.confirm(t("confirm_delete_report"), t("dlg_delete"), destructive = true) || store == null) return
        try {
            store!!.deleteSession(row.session.id)
        } catch (ex: Exception) {
            if (isStorageError(ex)) {
                AppLog.error("Report deletion failed", ex)
                notify(t("err_report_delete"), ToastKind.Error)
                return
            }
            throw ex
        }
        vm.workspace?.deleteViewerCopy(row.session.id)
        refreshReports()
        // Keyboard flow: the neighbouring report is selected and the list keeps focus.
        if (reportRows.isNotEmpty()) {
            selectedReportIndex = index.coerceIn(0, reportRows.size - 1)
            showSelectedReport()
            requestFocus(HomeFocus.ReportList)
        }
        notify(t("toast_report_deleted"), ToastKind.Info)
    }

    // ================= updates =================

    /** Updates live in Settings → Updates (the settings controller renders them). */
    fun refreshUpdates() = settingsPage.refreshUpdates()


    // ================= account (HomeWindow.Account.cs) =================

    /** State of the signed-out form (sign in, create account, forgot password, reset sent, switch account). */
    val auth: AuthFormState = AuthFormState()

    // Panels and page-level state (RefreshAccount).
    var notConfiguredPanelVisible: Boolean by bindable(false)
        private set
    var signedOutPanelVisible: Boolean by bindable(false)
        private set
    var signedInPanelVisible: Boolean by bindable(false)
        private set
    var accountStatusText: String by bindable("")
        private set
    var accountStatusBrushKey: String by bindable("B.TextDim")
        private set
    var accountHeaderVisible: Boolean by bindable(true)
        private set
    var forgotVisible: Boolean by bindable(false)
        private set

    // The signed-out form's fields (the C#'s EmailBox/PasswordBox/ConfirmBox contents).
    var authEmail: String by bindable("")
    var authPassword: String by bindable("")
    var authConfirmPassword: String by bindable("")
    var emailHasError: Boolean by bindable(false)
        private set
    var passwordHasError: Boolean by bindable(false)
        private set
    var confirmHasError: Boolean by bindable(false)
        private set

    var authProductName: String by bindable("")
        private set

    // ApplyAuthState render state.
    var authHeadingText: String by bindable("")
        private set
    var authSubtitleText: String by bindable("")
        private set
    var authSubtitleVisible: Boolean by bindable(false)
        private set
    var emailFieldVisible: Boolean by bindable(false)
        private set
    var passwordFieldVisible: Boolean by bindable(false)
        private set
    var confirmFieldVisible: Boolean by bindable(false)
        private set
    var providersVisible: Boolean by bindable(false)
        private set
    var legalNoticeVisible: Boolean by bindable(false)
        private set
    var passwordHintVisible: Boolean by bindable(false)
        private set
    var modePromptText: String by bindable("")
        private set
    var modeLinkText: String by bindable("")
        private set
    var modePromptVisible: Boolean by bindable(false)
        private set
    var modeLinksVisible: Boolean by bindable(false)
        private set
    var authSubmitText: String by bindable("")
        private set
    var authSubmitEnabled: Boolean by bindable(true)
        private set
    var modeLinkEnabled: Boolean by bindable(true)
        private set
    var forgotEnabled: Boolean by bindable(true)
        private set
    var emailReadOnly: Boolean by bindable(false)
        private set
    var providerButtons: List<ProviderButtonModel> by bindable(emptyList())
        private set
    var resendVisible: Boolean by bindable(false)
        private set
    var resendText: String by bindable("")
        private set
    var resendEnabled: Boolean by bindable(false)
        private set

    val authMessage: InlineMessageState = InlineMessageState()
    val authStatusMessage: InlineMessageState = InlineMessageState()
    val emailError: InlineMessageState = InlineMessageState()
    val passwordError: InlineMessageState = InlineMessageState()
    val confirmError: InlineMessageState = InlineMessageState()

    // Signed-in dashboard state.
    var securityEmailText: String by bindable("")
        private set
    var planText: String by bindable("")
        private set
    var planSubText: String by bindable("")
        private set
    var planBadgeText: String by bindable("")
        private set
    var planBadgeBrushKey: String by bindable("B.TextDim")
        private set
    var planBadgeVisible: Boolean by bindable(false)
        private set
    var usageMetrics: List<MetricRow> by bindable(emptyList())
        private set
    var billingNoticeText: String by bindable("")
        private set
    var upgradeVisible: Boolean by bindable(false)
        private set
    var upgradeText: String by bindable("")
        private set
    var upgradeEnabled: Boolean by bindable(false)
        private set
    var manageBillingVisible: Boolean by bindable(false)
        private set
    var manageBillingEnabled: Boolean by bindable(false)
        private set
    var upgradeStatusText: String by bindable("")
        private set
    var billingTermsText: String by bindable("")
        private set

    val summaryMessage: InlineMessageState = InlineMessageState()
    val devicesMessage: InlineMessageState = InlineMessageState()
    val billingMessage: InlineMessageState = InlineMessageState()
    val securityMessage: InlineMessageState = InlineMessageState()
    val privacyMessage: InlineMessageState = InlineMessageState()
    val dangerMessage: InlineMessageState = InlineMessageState()

    var refreshAccountEnabled: Boolean by bindable(true)
        private set

    var deviceRows: List<DeviceRow> by bindable(emptyList())
        private set
    var devicesEmptyVisible: Boolean by bindable(false)
        private set
    private var devices: List<DeviceDto> = emptyList()
    private var deviceBusy: Boolean = false

    // Legal links.
    var signUpLegalText: String by bindable("")
        private set
    var legalLinks: List<LegalLinkModel> by bindable(emptyList())
        private set
    var supportEmailText: String by bindable("")
        private set
    var legalHeaderVisible: Boolean by bindable(false)
        private set

    // Header account menu.
    var accountMenuOpen: Boolean by bindable(false)
        private set
    var accountMenuButtonText: String by bindable("")
        private set
    var accountMenuButtonTooltip: String by bindable("")
        private set
    var accountMenuButtonName: String by bindable("")
        private set
    var accountAvatarText: String by bindable("")
        private set
    var accountAvatarVisible: Boolean by bindable(false)
        private set
    var accountMenuChevronVisible: Boolean by bindable(false)
        private set
    var accountMenuEmailText: String by bindable("")
        private set
    var accountMenuPlanText: String by bindable("")
        private set
    var accountMenuItemLabels: List<String> by bindable(emptyList())
        private set
    var accountMenuFocusIndex: Int by bindable(0)
        private set

    private var authBusyText: String? = null
    private var authRetry: (suspend () -> Unit)? = null
    private var resendTimerJob: Job? = null
    private var lastBillingAction: (suspend () -> Pair<Boolean, String?>)? = null

    fun refreshAccount() {
        val c = vm.cloud
        refreshAccountMenu()
        notConfiguredPanelVisible = !c.isConfigured
        signedOutPanelVisible = c.isConfigured && !c.isSignedIn
        signedInPanelVisible = c.isSignedIn
        // Page level: only the account-wide state. A request that failed (refresh, devices, billing) is
        // reported in its own section, next to the data it concerns — never as a page-wide error.
        val email = c.account?.email ?: c.auth?.session?.email
        accountStatusText = if (c.isSignedIn &&
            (c.state == CloudState.SignedIn || c.state == CloudState.ServiceUnavailable || c.state == CloudState.Connecting) &&
            !email.isNullOrEmpty()
        ) {
            f("acct_signed_in_email", ltrIsolated(email))
        } else if (c.state == CloudState.Connecting) {
            t("acct_connecting_service")
        } else {
            Loc.relocalize(c.message) ?: ""
        }
        accountStatusBrushKey = if (c.state == CloudState.AccountDisabled || c.state == CloudState.UpdateRequired ||
            c.state == CloudState.SessionExpired ||
            (c.state == CloudState.ServiceUnavailable && !c.isSignedIn)
        ) {
            "B.Thinking"
        } else {
            "B.TextDim"
        }

        // Sign-in options come from the service (publishable configuration only).
        forgotVisible = c.options.emailPassword
        providerButtons = c.options.oAuthProviders.map { p ->
            ProviderButtonModel(p, f("continue_with", providerName(p)), !auth.busy)
        }
        signUpLegalText = legalSentence()
        // Signed out: the service state that blocks sign-in is shown inside the form (the page header is hidden).
        accountHeaderVisible = !signedOutPanelVisible
        val blocking = !c.isSignedIn &&
            (c.state == CloudState.SessionExpired || c.state == CloudState.AccountDisabled ||
                c.state == CloudState.UpdateRequired || c.state == CloudState.ServiceUnavailable ||
                c.state == CloudState.Connecting)
        val status = if (c.state == CloudState.Connecting) t("acct_connecting_service") else Loc.relocalize(c.message)
        if (blocking && !status.isNullOrEmpty()) {
            authStatusMessage.show(status, if (c.state == CloudState.Connecting) MessageKind.Info else MessageKind.Warning)
        } else {
            authStatusMessage.clear()
        }
        applyAuthState()

        securityEmailText = if (c.isSignedIn) email ?: "" else ""
        val account = c.account
        if (account != null && c.isSignedIn) {
            // Plan name + state badge, then the metric cards (minutes, today, preparations, limits) with usage bars.
            planText = account.entitlements.planName
            val label = MainViewModel.billingLabel(account)
            planSubText = if (label != account.entitlements.planName) label else ""
            showPlanBadge(account.billingState)
            usageMetrics = PlanView.metrics(account) { d -> formatShortDate(d) }.map { MetricRow.from(it) }
            showDevices(devices) // row texts follow the UI language
            billingNoticeText = when (account.billingState) {
                BillingStates.PastDue -> f(
                    "bill_notice_past_due",
                    account.subscription?.graceUntilUtc?.let { formatShortDate(it) },
                )
                BillingStates.Canceling -> f(
                    "bill_notice_canceling",
                    account.subscription?.currentPeriodEndUtc?.let { formatShortDate(it) },
                )
                BillingStates.Paused -> t("bill_notice_paused")
                BillingStates.Expired -> t("bill_notice_expired")
                BillingStates.Revoked -> t("bill_notice_revoked")
                BillingStates.TrialExhausted -> t("bill_notice_trial_used")
                else -> ""
            }
            val pro = c.plans.firstOrNull { it.purchasable }
            val paid = PlanView.isPaid(account.billingState) // Upgrade is the card's primary action only on a plan that is not paid
            upgradeVisible = pro != null && !paid
            upgradeText = if (pro?.priceDisplay != null) {
                f("upgrade_to_price", pro!!.name, pro.priceDisplay)
            } else {
                f("upgrade_to", pro?.name)
            }
            upgradeEnabled = !c.upgradePending && !sections.billing.isLoading
            manageBillingVisible = account.subscription != null
            manageBillingEnabled = !sections.billing.isLoading
            upgradeStatusText = if (c.upgradePending) t("upgrade_pending") else ""
            billingTermsText = if (pro == null) "" else f("billing_terms", pro.name, pro.priceDisplay ?: "")
        } else {
            // No account data (signed out, or this account's data is not loaded yet): never leave the
            // previous account's plan, minutes or devices on screen.
            planText = ""
            planSubText = ""
            billingNoticeText = ""
            upgradeStatusText = ""
            billingTermsText = ""
            planBadgeVisible = false
            usageMetrics = emptyList()
            upgradeVisible = false
            manageBillingVisible = false
            if (!c.isSignedIn) {
                devices = emptyList()
                deviceRows = emptyList()
                devicesEmptyVisible = false
                securityMessage.clear()
                privacyMessage.clear()
                dangerMessage.clear()
            }
        }
        renderSections()
        refreshLegal()
        refreshUpdates()
    }

    /** Inline status of each Account section (loading, failed with retry, saved data from …). */
    fun renderSections() {
        fun render(s: SectionState, m: InlineMessageState, failedKey: String, retry: Boolean = true) {
            if (s.status == SectionStatus.Failed) {
                val detail = listOf(Loc.relocalize(s.error), s.staleNote { time -> formatShortTime(time) })
                    .filter { !it.isNullOrEmpty() }
                    .joinToString(" ")
                m.show(
                    t(failedKey),
                    if (s.isStale) MessageKind.Warning else MessageKind.Error,
                    detail,
                    if (retry) t("dlg_retry") else null,
                )
            } else if (s.isLoading && !s.hasData) {
                m.show(t("acct_section_loading"), MessageKind.Info)
            } else {
                m.clear()
            }
        }
        render(sections.summary, summaryMessage, "acct_summary_failed")
        render(sections.devices, devicesMessage, "acct_devices_failed")
        render(sections.billing, billingMessage, "acct_billing_failed", retry = lastBillingAction != null)
        refreshAccountEnabled = !sections.summary.isLoading && !sections.devices.isLoading
    }

    /** The plan's state as a small badge next to its name (Trial, Active, Payment problem …). */
    private fun showPlanBadge(billingState: String) {
        planBadgeBrushKey = when (PlanView.badgeTone(billingState)) {
            PlanTone.Positive -> "B.Listening"
            PlanTone.Warning -> "B.Thinking"
            PlanTone.Problem -> "B.Error"
            PlanTone.Accent -> "B.Accent"
            else -> "B.TextDim"
        }
        planBadgeText = t(PlanView.badgeKey(billingState))
        planBadgeVisible = true
    }

    private fun legalSentence(): String {
        val l = vm.cloud.config?.links
        if (l?.terms != null && l.privacyPolicy != null) return t("legal_agree_both")
        if (l?.terms != null) return t("legal_agree_terms")
        if (l?.privacyPolicy != null) return t("legal_agree_privacy")
        return ""
    }

    private fun refreshLegal() {
        val l = vm.cloud.config?.links
        legalLinks = listOf(
            LegalLinkModel(t("legal_privacy"), l?.privacyPolicy ?: ""),
            LegalLinkModel(t("legal_terms"), l?.terms ?: ""),
            LegalLinkModel(t("legal_billing_terms"), l?.billingTerms ?: ""),
            LegalLinkModel(t("legal_support_link"), l?.support ?: ""),
        ).filter { it.url.isNotBlank() }
        supportEmailText = l?.supportEmail?.takeIf { it.isNotEmpty() }?.let { f("legal_support_email", ltrIsolated(it)) } ?: ""
        legalHeaderVisible = legalLinks.isNotEmpty() || supportEmailText.isNotEmpty()
    }

    fun onLegalLinkClick(link: LegalLinkModel) {
        CloudService.openExternal(link.url)
    }

    fun onProviderClick(provider: String) {
        scope.launch { runAuth({ vm.cloud.signInWithProviderAsync(provider) }, t("acct_continue_in_browser")) }
    }

    // ----- signed-out form: one card, five states (UiState/AuthFormState) -----

    /** Constructor wiring of the form (the C#'s InitAuthForm): the state machine drives the form. */
    private fun initAuthForm() {
        authProductName = Branding.productName
        auth.addChangedListener { applyAuthState() }
        // Leaving the reset screens (Back to sign in, a sign-in, sign-out, switch) ends the wait for the
        // reset link, so a link opened later can never sign in on top of another account.
        auth.addChangedListener {
            if (!auth.inResetFlow) cancelPendingReset("the form left the reset state")
        }
        applyAuthState()
    }

    /** Renders the current state: heading, subtitle, visible fields, the one primary button, the links. */
    fun applyAuthState() {
        val s = auth
        authHeadingText = t(s.headingKey)
        authSubtitleText = s.subtitleKey?.let { f(it, ltrIsolated(s.previousEmail)) } ?: ""
        authSubtitleVisible = authSubtitleText.isNotEmpty()
        emailFieldVisible = s.shows(AuthField.Email)
        passwordFieldVisible = s.shows(AuthField.Password)
        confirmFieldVisible = s.shows(AuthField.ConfirmPassword)
        forgotVisible = s.showsForgotLink && vm.cloud.options.emailPassword
        providersVisible = s.showsProviders && providerButtons.isNotEmpty()
        legalNoticeVisible = s.showsLegal && signUpLegalText.isNotEmpty()
        passwordHintVisible = s.shows(AuthField.Password)
        // Mode switch through links: "New here? Create an account" / "Already have an account? Sign in" / "Back to sign in".
        val (prompt, link) = when {
            s.showsCreateLink -> t("auth_new_here") to t("auth_create_link")
            s.showsSignInLink -> t("auth_have_account") to t("sign_in")
            else -> "" to t("auth_back_to_sign_in")
        }
        modePromptText = prompt
        modeLinkText = link
        modePromptVisible = modePromptText.isNotEmpty()
        modeLinksVisible = s.showsCreateLink || s.showsSignInLink || s.showsBackLink
        // One primary button; while its request runs it shows the progress text and nothing can be submitted twice.
        authSubmitText = if (s.busy) {
            authBusyText ?: s.busyKey?.let { t(it) } ?: t(s.primaryKey)
        } else {
            t(s.primaryKey)
        }
        authSubmitEnabled = !s.busy
        modeLinkEnabled = !s.busy
        forgotEnabled = !s.busy
        emailReadOnly = s.busy
        providerButtons = providerButtons.map { it.copy(enabled = !s.busy) }
        updateResend()
    }

    /** "Resend" in the link-sent state: disabled with a countdown until the cooldown ends. */
    fun updateResend() {
        val at = now()
        val left = auth.resendSecondsLeft(at)
        resendVisible = auth.showsResend
        resendText = if (left > 0) f("auth_resend_in", left) else t("auth_resend")
        resendEnabled = auth.canResend(at)
        if (auth.showsResend && left > 0) startResendTimer() else stopResendTimer()
    }

    private fun startResendTimer() {
        if (resendTimerJob?.isActive == true) return
        resendTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                updateResend()
            }
        }
    }

    private fun stopResendTimer() {
        resendTimerJob?.cancel()
        resendTimerJob = null
    }

    fun clearAuthErrors() {
        authMessage.clear()
        emailError.clear()
        passwordError.clear()
        confirmError.clear()
        emailHasError = false
        passwordHasError = false
        confirmHasError = false
        authRetry = null
    }

    /** Focus after a state change: the first visible field (caret at the end), else the primary button. */
    private fun focusAuthForm() = requestFocus(HomeFocus.AuthForm)

    fun onModeLinkClick() {
        if (!(if (auth.showsCreateLink) auth.toCreateAccount() else auth.toSignIn())) return
        clearAuthErrors()
        focusAuthForm()
    }

    /** "Forgot password?": the dedicated reset state — e-mail only, pre-filled with what was typed. */
    fun onForgotClick() {
        if (!auth.toForgotPassword()) return
        clearAuthErrors()
        focusAuthForm()
    }

    /** Enter in the e-mail field: on to the password when it is shown and empty, else submit. */
    fun onEmailEnter() {
        if (auth.shows(AuthField.Password) && authPassword.isEmpty()) {
            requestFocus(HomeFocus.Password)
            return
        }
        scope.launch { submitAuthAsync() }
    }

    /** Enter in a password field: on to the confirmation when it is shown and empty, else submit. */
    fun onPasswordEnter(isConfirmField: Boolean) {
        if (!isConfirmField && auth.shows(AuthField.ConfirmPassword) && authConfirmPassword.isEmpty()) {
            requestFocus(HomeFocus.ConfirmPassword)
            return
        }
        scope.launch { submitAuthAsync() }
    }

    fun onAuthSubmitClick() {
        if (auth.primarySubmits) {
            scope.launch { submitAuthAsync() }
            return
        }
        if (auth.toSignIn()) { // link sent: "Back to sign in"
            clearAuthErrors()
            focusAuthForm()
        }
    }

    fun onAuthMessageActionClick() {
        val retry = authRetry
        if (retry != null && !auth.busy) scope.launch { retry() }
    }

    fun onSummaryMessageActionClick() {
        scope.launch {
            loadSummaryAsync()
            applyAccountLimits()
        }
    }

    fun onDevicesMessageActionClick() {
        scope.launch { loadDevicesAsync() }
    }

    fun onBillingMessageActionClick() {
        val action = lastBillingAction
        if (action != null) scope.launch { runBillingAsync(action) }
    }

    internal fun validEmail(email: String): Boolean =
        Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(email)

    /** A validation error appears right under its field, which turns red and takes the focus. */
    private fun fieldError(field: AuthField, message: InlineMessageState, text: String) {
        when (field) {
            AuthField.Email -> emailHasError = true
            AuthField.Password -> passwordHasError = true
            AuthField.ConfirmPassword -> confirmHasError = true
        }
        message.show(text, MessageKind.Error)
        requestFocus(
            when (field) {
                AuthField.Email -> HomeFocus.Email
                AuthField.Password -> HomeFocus.Password
                AuthField.ConfirmPassword -> HomeFocus.ConfirmPassword
            },
        )
    }

    internal suspend fun submitAuthAsync() {
        if (auth.busy || !auth.primarySubmits) return
        val email = authEmail.trim()
        clearAuthErrors()
        if (!validEmail(email)) {
            fieldError(AuthField.Email, emailError, t("err_email_invalid"))
            return
        }
        if (auth.mode == AuthMode.ForgotPassword) {
            requestResetAsync(email, force = false)
            return
        }
        val pw = authPassword
        if (pw.isEmpty()) {
            fieldError(AuthField.Password, passwordError, t("err_password_empty"))
            return
        }
        if (auth.mode == AuthMode.CreateAccount) {
            if (pw.length < 8) {
                fieldError(AuthField.Password, passwordError, t("err_password_short"))
                return
            }
            if (pw != authConfirmPassword) {
                fieldError(AuthField.ConfirmPassword, confirmError, t("err_password_mismatch"))
                return
            }
            var confirm = false
            runAuth(action = {
                val (ok, needsConfirmation, error) = vm.cloud.signUpAsync(email, pw)
                confirm = ok && needsConfirmation
                Pair(ok, error)
            })
            if (confirm && !vm.cloud.isSignedIn && auth.toSignIn()) {
                authMessage.show(f("acct_confirm_email_sent", ltrIsolated(email)), MessageKind.Success)
            }
        } else {
            runAuth(action = { vm.cloud.signInAsync(email, pw) })
        }
    }

    /** @param busyText Progress text instead of the state's own (provider sign-in: "Continue in your browser…"). */
    internal suspend fun runAuth(action: suspend () -> Pair<Boolean, String?>, busyText: String? = null) {
        if (auth.busy) return
        authBusyText = busyText
        auth.setBusy(true)
        try {
            val (ok, error) = action()
            if (!ok) {
                authMessage.show(error ?: t("err_sign_in_failed"), MessageKind.Error)
            } else {
                authPassword = ""
                authConfirmPassword = ""
                if (vm.cloud.isSignedIn) {
                    cancelPendingReset("signed in")
                    vm.openWorkspaceForCurrentAccount() // this account's data only
                    if (!vm.sessionActive) vm.buildEngine()
                    auth.reset() // a later sign-out starts at the plain sign-in again
                    sections.summary.loaded(now())
                    refreshAccountSectionsAsync(summary = false)
                    notify(t("toast_signed_in"), ToastKind.Success, vm.cloud.account?.email)
                    if (!vm.settings.firstRunCompleted) showRoute(HomeRoute("start")) // first run: on to step 2
                }
            }
        } finally {
            authBusyText = null
            auth.setBusy(false)
            refreshAll()
        }
    }

    // The pending reset waits up to 15 minutes for the link. A new request (e.g. a corrected address, or
    // Resend) replaces it; leaving the reset screens, signing in or out, switching account or closing
    // the window cancels it.
    private val reset: PendingReset = PendingReset()

    /** The address of the last accepted reset request ("Resend" sends to it again). */
    private var resetSentTo: String? = null

    private fun cancelPendingReset(why: String) {
        if (reset.cancel()) AppLog.info("Pending password reset cancelled: $why")
    }

    /**
     * Password reset (the "Send reset link" button of the forgot-password state). Enumeration-safe:
     * the same neutral notice appears for every syntactically valid address, whether or not an
     * account exists, and it never claims that an e-mail was delivered. Rate limits are still
     * reported; a timeout gets its own copy; every error offers a retry. Only the current request
     * ([PendingReset.isCurrent]) may change the form: a cancelled or replaced one never clears
     * Busy, resets the state or shows its result.
     */
    internal suspend fun requestResetAsync(email: String, force: Boolean) {
        fun requested() {
            resetSentTo = email
            auth.setBusy(false)
            if (!auth.resetRequested(now())) return
            authMessage.show(PasswordResetCopy.requested, MessageKind.Success, t("acct_reset_requested_hint"))
            focusAuthForm() // the e-mail field that had the focus is hidden now
        }
        // Same address again while its link is awaited: nothing new to send (Resend sends again on
        // purpose). Another address: the old wait ends.
        if (!force && reset.isPendingFor(email)) {
            requested()
            return
        }
        val ticket = reset.begin(email)
        var accepted = false
        auth.setBusy(true)
        try {
            // The auth server sends one recovery e-mail per address per interval and reports an earlier
            // request as accepted without sending it. The previous link stopped working when its wait
            // was cancelled, so wait out the interval. The wait and the call run under the ticket's
            // job (the C#'s ct: ticket.Token): cancelling the ticket cancels both.
            val wait = reset.waitBeforeSending(email, now(), AuthFormState.resendCooldown)
            val (ok, error) = withContext(ticket.token) {
                if (wait > Duration.ZERO) delay(wait.toMillis())
                vm.cloud.resetPasswordAsync(
                    email,
                    askNewPassword = { dialogs.newPassword() },
                    requested = {
                        accepted = true
                        reset.markRequested(email, now())
                        if (reset.isCurrent(ticket)) requested()
                    },
                )
            }
            if (!reset.isCurrent(ticket)) return // replaced by a newer request, or the form/account moved on
            if (ok || vm.cloud.isSignedIn) {
                // Done (or the link signed this account in although the new password was not set):
                // a normal sign-in from here.
                reset.release(ticket)
                auth.reset()
                vm.openWorkspaceForCurrentAccount()
                if (ok) {
                    authMessage.show(t("acct_password_reset_done"), MessageKind.Success)
                    notify(t("acct_password_reset_done"), ToastKind.Success)
                } else {
                    notify(error ?: t("acct_password_not_changed"), ToastKind.Warning)
                }
            } else {
                showResetError(error, accepted, email)
            }
        } catch (ex: CancellationException) {
            if (!ticket.token.isCancelled) throw ex // the C# catches only the ticket's own cancellation
        } finally {
            if (reset.release(ticket)) auth.setBusy(false)
            ticket.close()
            refreshAll()
        }
    }

    /** A failed reset: back to the e-mail step with contextual copy and "Try again" (sends a new link). */
    private fun showResetError(error: String?, accepted: Boolean, email: String) {
        val lastError = vm.cloud.lastAuthError
        val timedOut = lastError is TimeoutException ||
            (!accepted && lastError is AuthException && lastError.code == "network")
        val text = if (timedOut) {
            t(if (accepted) "auth_reset_link_expired" else "auth_reset_request_timeout")
        } else {
            error ?: t("err_reset_failed")
        }
        auth.resetFailed()
        if (auth.mode != AuthMode.ForgotPassword) {
            AppLog.info("Password reset ended after the form left the reset state")
            return
        }
        authMessage.show(text, MessageKind.Error, null, t("dlg_retry"))
        authRetry = { requestResetAsync(email, force = true) }
        focusAuthForm() // back at the e-mail step
    }

    fun onResendClick() {
        scope.launch { resendAsync() }
    }

    internal suspend fun resendAsync() {
        val email = resetSentTo
        if (!auth.canResend(now()) || email == null) return
        requestResetAsync(email, force = true)
    }

    /**
     * Sensitive actions ask for the current password again (or a fresh provider sign-in for OAuth
     * accounts). A wrong password is reported inside the prompt, which stays open for another try.
     */
    internal suspend fun reauthenticateAsync(reasonKey: String, messages: InlineMessageState): Boolean {
        messages.clear()
        // Accounts that sign in with Google/Microsoft/GitHub/Apple have no password: confirm with that provider instead.
        val provider = vm.cloud.signInProvider
        if (!provider.isNullOrEmpty() && provider != "email") {
            if (!dialogs.confirm(
                    f("reauth_provider_confirm", providerLabel(provider)), t("dlg_continue"),
                    title = t("reauth_title"),
                )
            ) return false
            val (pok, perror) = vm.cloud.reauthenticateWithProviderAsync(provider)
            if (!pok) messages.show(perror ?: t("err_reauth_failed"), MessageKind.Error)
            return pok
        }
        return dialogs.currentPassword(t(reasonKey), vm.cloud.account?.email ?: "") { password ->
            val (ok, error) = vm.cloud.reauthenticateAsync(password)
            if (ok) null else error ?: t("err_reauth_failed")
        }
    }

    fun onChangePasswordClick() {
        scope.launch { changePasswordAsync() }
    }

    internal suspend fun changePasswordAsync() {
        if (!reauthenticateAsync("reauth_reason_password", securityMessage)) return
        val pw = dialogs.newPassword() ?: return
        val (ok, error) = vm.cloud.changePasswordAsync(pw)
        if (ok) {
            securityMessage.show(t("acct_password_changed"), MessageKind.Success)
            notify(t("acct_password_changed"), ToastKind.Success)
        } else {
            securityMessage.show(error ?: t("err_generic"), MessageKind.Error)
        }
    }

    /** Loads the Account sections independently: one failed or slow request never fails the others. */
    suspend fun refreshAccountSectionsAsync(summary: Boolean) {
        if (!vm.cloud.isSignedIn) {
            sections.resetAll()
            renderSections()
            return
        }
        coroutineScope {
            launch { loadDevicesAsync() }
            if (summary) launch { loadSummaryAsync() }
        }
        applyAccountLimits()
    }

    internal suspend fun loadSummaryAsync() {
        val ticket = sections.summary.begin()
        renderSections()
        val ok = vm.cloud.refreshAsync()
        if (!vm.cloud.isSignedIn) {
            sections.resetAll()
        } else if (ok && vm.cloud.state == CloudState.SignedIn) {
            sections.summary.succeed(ticket, now())
        } else {
            sections.summary.fail(
                ticket,
                vm.cloud.lastRefreshError?.let { CloudService.describe(it, includeReference = false) }
                    ?: t("svc_unavailable"),
            )
        }
        refreshAccount()
    }

    internal suspend fun loadDevicesAsync() {
        val api = vm.cloud.api
        if (api == null || !vm.cloud.isSignedIn) {
            deviceRows = emptyList()
            devices = emptyList()
            return
        }
        val requestedFor = vm.cloud.auth?.session?.userId
        val ticket = sections.devices.begin()
        renderSections()
        try {
            val list = api.devicesAsync()
            if (vm.cloud.auth?.session?.userId != requestedFor) return // the account changed while loading
            if (!sections.devices.succeed(ticket, now())) return
            showDevices(list.filter { !it.revoked })
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (ex is BackendException || ex is IOException) {
                // The list already on screen stays (marked "saved data from …"); the correlation id goes to the log only.
                val be = ex as? BackendException
                AppLog.warn("Devices request failed: ${be?.code ?: ex.javaClass.simpleName} (ref ${be?.correlationId ?: ""})")
                if (vm.cloud.auth?.session?.userId == requestedFor) {
                    sections.devices.fail(ticket, CloudService.describe(ex, includeReference = false))
                }
            } else {
                throw ex
            }
        } finally {
            renderSections()
        }
    }

    /** Rich device rows: this device first, then the most recently seen. */
    fun showDevices(list: List<DeviceDto>) {
        devices = list
        fun isThisPc(d: DeviceDto): Boolean = d.isCurrent || d.id == vm.settings.cloudDeviceId
        deviceRows = list
            .sortedWith(compareByDescending<DeviceDto> { isThisPc(it) }.thenByDescending { it.lastSeenUtc })
            .map { d ->
                DeviceRow(
                    device = d,
                    name = d.name,
                    meta = f("acct_device_meta", ltrIsolated(d.clientVersion), formatGeneral(d.lastSeenUtc)),
                    thisPcVisible = isThisPc(d),
                    removeName = f("acct_device_remove_name", d.name),
                    canRemove = !deviceBusy,
                )
            }
        devicesEmptyVisible = list.isEmpty() && sections.devices.hasData
    }

    /** Plan limits for data that never leaves this device: report retention (server value, applied locally). */
    fun applyAccountLimits() {
        val e = vm.cloud.account?.entitlements ?: return
        val st = store ?: return
        val removed = st.applyRetention(e.reportRetentionDays.days, nowUtc())
        if (removed > 0) {
            AppLog.info("Retention removed $removed report(s)")
            refreshReports(selectedReportId())
        }
    }

    /** Billing actions (checkout, portal) report in the billing section; a retry repeats the failed action. */
    internal suspend fun runBillingAsync(action: suspend () -> Pair<Boolean, String?>) {
        if (sections.billing.isLoading) return
        lastBillingAction = action
        val ticket = sections.billing.begin()
        refreshAccount()
        val (ok, error) = action()
        if (ok) {
            sections.billing.succeed(ticket, now())
        } else {
            sections.billing.fail(ticket, error ?: t("err_generic"))
        }
        refreshAccount()
    }

    fun onUpgradeClick() {
        scope.launch { upgradeAsync() }
    }

    internal suspend fun upgradeAsync() {
        val pro = vm.cloud.plans.firstOrNull { it.purchasable } ?: return
        runBillingAsync { vm.cloud.startUpgradeAsync(pro.code) }
    }

    fun onPortalClick() {
        scope.launch { runBillingAsync { vm.cloud.openPortalAsync() } }
    }

    fun onRefreshAccountClick() {
        scope.launch { refreshAccountClickAsync() }
    }

    internal suspend fun refreshAccountClickAsync() {
        refreshAccountSectionsAsync(summary = true)
        if (vm.cloud.isSignedIn && sections.all.none { it.status == SectionStatus.Failed }) {
            notify(t("toast_billing_refreshed"), ToastKind.Info)
        } else if (!vm.cloud.isSignedIn && !vm.cloud.message.isNullOrEmpty()) {
            notify(vm.cloud.message!!, ToastKind.Warning)
        }
    }

    fun onRemoveDeviceClick(row: DeviceRow) {
        scope.launch { removeDeviceAsync(row) }
    }

    /** Remove on a device row: confirmed (destructive, Cancel is the default) before the device is revoked. */
    internal suspend fun removeDeviceAsync(row: DeviceRow) {
        val d = row.device
        if (vm.cloud.api == null || deviceBusy) return
        if (!dialogs.confirm(f("confirm_remove_device", d.name), t("dlg_remove"), destructive = true) ||
            vm.cloud.api == null
        ) return
        deviceBusy = true
        showDevices(devices)
        try {
            vm.cloud.api!!.revokeDeviceAsync(d.id)
            notify(t("toast_device_removed"), ToastKind.Success)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (ex is BackendException || ex is IOException) {
                val be = ex as? BackendException
                AppLog.warn("Device removal failed: ${be?.code ?: ex.javaClass.simpleName} (ref ${be?.correlationId ?: ""})")
                devicesMessage.show(
                    t("acct_device_remove_failed"), MessageKind.Error,
                    CloudService.describe(ex, includeReference = false),
                )
                return // the finally still re-renders; the list is not reloaded after a failed removal
            }
            throw ex
        } finally {
            deviceBusy = false
            showDevices(devices)
        }
        loadDevicesAsync()
    }

    fun onDeleteAccountClick() {
        scope.launch { deleteAccountAsync() }
    }

    /**
     * Delete account: (1) the in-app dialog listing what is deleted ("Keep account" is the
     * default), (2) re-authentication as the confirmation, then the deletion; afterwards a
     * SEPARATE question about this device's copy of the data.
     */
    internal suspend fun deleteAccountAsync() {
        if (vm.cloud.api == null) return
        dangerMessage.clear()
        val blocks = AccountDeletion.subscriptionBlocks(vm.cloud.account?.subscription)
        val choice = dialogs.confirmDeleteAccount(blocks)
        // A subscription that can still charge blocks deletion: the dialog offers Manage billing instead of the deletion.
        if (choice == DeleteAccountChoice.ManageBilling) {
            runBillingAsync { vm.cloud.openPortalAsync() }
            return
        }
        if (choice != DeleteAccountChoice.Delete) return
        if (!reauthenticateAsync("reauth_reason_delete", dangerMessage) || vm.cloud.api == null) return
        try {
            if (vm.sessionActive) vm.stopAsync()
            // This account's local folders, captured BEFORE the deletion: a sign-out during the request
            // (401, refresh) closes the workspace, and the local-data question must still be asked.
            val localRoot = vm.workspace?.store?.root
            val viewer = vm.workspace?.viewerDir
            val deleted = vm.cloud.api!!.deleteAccountAsync()
            profileGuard.markClean()
            targetGuard.markClean() // the account and its data are gone
            vm.signOutAsync(quiet = true) // the form below says the account was deleted
            auth.reset()
            // The service removes the sign-in too; if the auth provider was unreachable it finishes that automatically.
            refreshAll()
            authMessage.show(
                t(if (deleted?.identity == "pending") "acct_deleted_identity_pending" else "acct_deleted"),
                MessageKind.Success,
            )
            // The service data is gone; this device's copy of the account's profiles, interviews and
            // reports is the user's to keep or remove.
            if (localRoot != null && dialogs.askDeleteLocalData()) {
                val failed = LocalData.delete(localRoot, viewer)
                if (failed == null) {
                    notify(t("toast_local_data_deleted"), ToastKind.Info)
                } else {
                    // Not everything could be removed: say so and name the folder (the path stays out of the log).
                    AppLog.warn("Local data removal failed: ${failed.error}")
                    dialogs.alert(f("del_local_failed", failed.folder), DialogTone.Warning)
                }
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (ex is BackendException || ex is IOException) {
                // e.g. subscription_active: the service refuses while the subscription can still charge
                // (contextual copy, no raw text).
                val be = ex as? BackendException
                AppLog.warn("Account deletion failed: ${be?.code ?: ex.javaClass.simpleName} (ref ${be?.correlationId ?: ""})")
                dangerMessage.show(CloudService.describe(ex, includeReference = false), MessageKind.Error)
            } else {
                throw ex
            }
        }
        refreshAll()
    }

    /** Sign-out closes this account's workspace: pending profile/interview edits are asked about first. */
    internal suspend fun confirmSignOutAsync(): Boolean {
        if (!nav.canProceedAsync(ChangeReason.SignOut, profileGuard, targetGuard)) return false
        return !vm.sessionActive ||
            dialogs.confirm(t("confirm_sign_out_session"), t("dlg_sign_out"), destructive = true)
    }

    fun onSignOutClick() {
        scope.launch { signOutAsync() }
    }

    /** Sign out (header menu): the normal sign-out with its "Signed out" notification; the form starts at Sign in. */
    internal suspend fun signOutAsync() {
        if (!confirmSignOutAsync()) return
        cancelPendingReset("signed out")
        vm.signOutAsync()
        auth.reset()
        authEmail = ""
        authMessage.clear()
        refreshAll()
        showPage("account")
    }

    fun onSwitchAccountClick() {
        scope.launch { switchAccountAsync() }
    }

    /**
     * Switch account (Security card and header menu): asks first, signs out QUIETLY (no "Signed
     * out" notification — the page that follows says what happened) and opens the sign-in form in
     * its "Sign in with another account" state with an empty e-mail field and the focus in it.
     */
    internal suspend fun switchAccountAsync() {
        val previous = vm.cloud.account?.email ?: vm.cloud.auth?.session?.email ?: ""
        if (!dialogs.confirm(
                f("switch_confirm_message", ltrIsolated(previous)), t("switch_account"),
                title = t("switch_confirm_title"),
            )
        ) return
        if (!confirmSignOutAsync()) return
        cancelPendingReset("switching account")
        vm.signOutAsync(quiet = true)
        auth.startSwitch(previous)
        authEmail = ""
        authPassword = ""
        authConfirmPassword = ""
        authMessage.clear()
        refreshAll()
        showPage("account")
        requestFocus(HomeFocus.Email)
    }

    // ----- header account button and menu -----

    /** Header button: avatar initial + e-mail (signed in) or "Sign in"; the menu shows the e-mail and the plan line. */
    fun refreshAccountMenu() {
        val c = vm.cloud
        val signedIn = c.isSignedIn
        val email = c.account?.email ?: c.auth?.session?.email ?: ""
        accountAvatarVisible = signedIn
        accountMenuChevronVisible = signedIn
        accountAvatarText = AccountMenu.initial(email)
        // Signed out: "Sign in" (or the build's state, e.g. developer mode, when no account service is configured).
        accountMenuButtonText = if (signedIn) email else if (c.isConfigured) t("sign_in") else vm.accountLabel
        accountMenuButtonTooltip = t(if (signedIn) "tt_account_menu" else "tt_open_account")
        accountMenuButtonName = if (signedIn) f("acct_menu_name", email) else accountMenuButtonText
        accountMenuEmailText = email
        accountMenuPlanText = if (c.account != null && signedIn) MainViewModel.billingLabel(c.account!!) else vm.accountLabel
        accountMenuItemLabels = AccountMenu.items.map { t(AccountMenu.labelKey(it)) }
        if (!signedIn && accountMenuOpen) closeAccountMenu(returnFocus = false)
    }

    /** Enter, Space or a click: signed in, the menu opens with focus on its first entry; signed out, Account opens. */
    fun onAccountMenuButtonClick() {
        if (!AccountMenu.opensMenu(vm.cloud.isSignedIn)) {
            navigate("account")
            return
        }
        if (accountMenuOpen) closeAccountMenu(returnFocus = true) else openAccountMenu()
    }

    fun openAccountMenu() {
        refreshAccountMenu()
        accountMenuOpen = true
        accountMenuFocusIndex = 0
        requestFocus(HomeFocus.AccountMenuFirstItem)
    }

    fun closeAccountMenu(returnFocus: Boolean) {
        if (!accountMenuOpen) return
        accountMenuOpen = false
        if (returnFocus) requestFocus(HomeFocus.AccountMenuButton)
    }

    fun onAccountMenuBackdropClick() = closeAccountMenu(returnFocus = false)

    /** The account menu's keys: Down/Up move (wrapping), Home/End jump, Escape closes. Returns "handled". */
    fun onAccountMenuKey(key: AccountMenuKey, current: Int): Boolean {
        val count = AccountMenu.items.size
        val next: Int? = when (key) {
            AccountMenuKey.Down -> AccountMenu.move(current, count, down = true)
            AccountMenuKey.Up -> AccountMenu.move(current, count, down = false)
            AccountMenuKey.Home -> 0
            AccountMenuKey.End -> count - 1
            AccountMenuKey.Escape -> null
        }
        if (key == AccountMenuKey.Escape) {
            closeAccountMenu(returnFocus = true)
            return true
        }
        if (next != null && next >= 0) {
            accountMenuFocusIndex = next
            return true
        }
        return false
    }

    fun onAccountMenuItemClick(item: AccountMenuItem) {
        closeAccountMenu(returnFocus = true)
        when (item) {
            AccountMenuItem.ManageAccount -> navigate(AccountMenu.route(item)!!)
            AccountMenuItem.Settings -> navigate(AccountMenu.route(item)!!)
            AccountMenuItem.SwitchAccount -> onSwitchAccountClick()
            AccountMenuItem.SignOut -> onSignOutClick()
        }
    }


    // ================= start / get started (HomeWindow.Start.cs) =================

    var ackChecked: Boolean by bindable(false)
    var ackCardVisible: Boolean by bindable(true)
        private set
    var ackNoteVisible: Boolean by bindable(false)
        private set
    var dashboardSteps: List<DashboardStep> by bindable(emptyList())
        private set
    var primaryActionLabel: String by bindable("")
        private set
    var primaryActionEnabled: Boolean by bindable(false)
        private set
    var primaryActionTooltip: String? by bindable(null)
        private set
    var accountLinkVisible: Boolean by bindable(false)
        private set
    var developerModeVisible: Boolean by bindable(false)
        private set
    var workspaceLinksVisible: Boolean by bindable(false)
        private set
    var interviewProfileLinkVisible: Boolean by bindable(false)
        private set

    private var primaryAction: SetupAction? = null

    /** Self-test hook: the dashboard's current primary action. */
    val primaryActionForTest: SetupAction? get() = primaryAction

    /**
     * The interview the dashboard is about: the active one when there is one, else the first
     * prepared one, else the first — across all profiles.
     */
    fun focusInterview(profiles: List<CandidateProfileRecord>): InterviewTarget? {
        val st = store ?: return null
        val all = profiles.flatMap { st.targets(it.id) }
        return all.firstOrNull { it.id == vm.activeTargetId }
            ?: all.firstOrNull { it.preparation == PreparationState.Prepared }
            ?: all.firstOrNull()
    }

    /** Developer builds may run without an account when a key is stored (never in release builds). */
    val developerKeyMode: Boolean get() = BuildFlavor.Developer && !vm.usingCloud

    fun currentSetupFacts(
        profiles: List<CandidateProfileRecord>,
        focus: InterviewTarget?,
    ): SetupFacts = SetupFacts(
        accountReady = vm.hasWorkspace &&
            (vm.cloud.isSignedIn || (developerKeyMode && SecretStore.hasKey)),
        hasProfile = profiles.isNotEmpty(),
        hasConfirmedFacts = profiles.any { it.confirmedFacts.isNotEmpty() },
        hasInterview = focus != null,
        interviewPrepared = focus?.preparation == PreparationState.Prepared,
        readiness = vm.readinessState,
    )

    /** Re-renders the checklist (and with it the dashboard): the readiness page re-checks and reports. */
    fun refreshChecklist() {
        preInterview.refresh()
    }

    fun renderDashboard() {
        val profiles = store?.profiles() ?: emptyList()
        val focus = focusInterview(profiles)
        val facts = currentSetupFacts(profiles, focus)
        primaryAction = SetupPlanner.primaryAction(facts)
        dashboardSteps = SetupPlanner.steps(facts).map { view ->
            DashboardStep(
                step = view.step,
                status = view.status,
                stateText = t(
                    when (view.status) {
                        StepStatus.Done -> "gs_status_done"
                        StepStatus.Current -> "gs_status_current"
                        StepStatus.Upcoming -> "gs_status_upcoming"
                    },
                ),
                detail = when (view.step) {
                    SetupStep.Account -> accountStepDetail()
                    SetupStep.Profile -> profileStepDetail(profiles)
                    SetupStep.InterviewSetup -> interviewsStepDetail(focus)
                    SetupStep.PreInterviewCheck -> checkStepDetail()
                },
            )
        }
        // The confirmation is step 1: until it is ticked the card stays and the primary action is disabled.
        val ack = vm.settings.authorizedUseAcknowledged
        ackCardVisible = !ack
        ackNoteVisible = ack
        accountLinkVisible = vm.cloud.isConfigured && vm.cloud.isSignedIn
        developerModeVisible = BuildFlavor.Developer
        workspaceLinksVisible = vm.hasWorkspace
        interviewProfileLinkVisible = vm.hasWorkspace && profiles.isNotEmpty()
        primaryActionLabel = t(SetupPlanner.actionKey(primaryAction!!))
        primaryActionEnabled = ack
        primaryActionTooltip = if (ack) null else t("start_step1_hint")
    }

    private fun accountStepDetail(): String = when {
        developerKeyMode -> t(if (SecretStore.hasKey) "start_dev_key" else "start_dev_no_key")
        !vm.cloud.isConfigured -> t("acct_not_configured_long")
        vm.cloud.isSignedIn -> f("gs_account_signed_in", vm.accountLabel)
        else -> Loc.relocalize(vm.cloud.message) ?: t("start_account_hint")
    }

    private fun profileStepDetail(profiles: List<CandidateProfileRecord>): String {
        if (!vm.hasWorkspace) return t("start_sign_in_first")
        if (profiles.isEmpty()) return t("start_profile_hint")
        val confirmed = profiles.sumOf { it.stats.confirmed }
        return if (confirmed > 0) {
            f("gs_profile_status", profiles.size, confirmed)
        } else {
            f("gs_profile_review", profiles.size, profiles.sumOf { it.stats.toReview })
        }
    }

    private fun interviewsStepDetail(focus: InterviewTarget?): String {
        if (!vm.hasWorkspace) return t("start_sign_in_first")
        if (focus == null) return t("start_interview_hint")
        return "${focus.displayName} · ${stateLabel(focus.preparation)}"
    }

    private fun checkStepDetail(): String {
        val state = vm.readinessState
        if (state == ReadinessState.NotChecked) return t("rd_hint_not_checked")
        return "${t(ReadinessSnapshot.stateKey(state))} · ${summaryText()}"
    }

    fun onPrimaryActionClick() {
        scope.launch { primaryActionClickAsync() }
    }

    internal suspend fun primaryActionClickAsync() {
        if (!ackChecked) return // the button is disabled; Enter on the card must not bypass it either
        when (primaryAction) {
            SetupAction.SignIn -> goAccount()
            SetupAction.CompleteProfile -> navigateAsync(HomeRoute("profiles", "facts"))
            SetupAction.CreateInterview -> {
                navigateAsync(HomeRoute("interviews"))
                newTargetAsync()
            }
            SetupAction.PrepareInterview -> prepareFocusInterviewAsync()
            SetupAction.RunPreInterviewCheck -> {
                useFocusInterview()
                completeFirstRun()
                navigateAsync(HomeRoute("preinterview"))
            }
            SetupAction.StartInterview -> startInterviewAsync()
            null -> Unit
        }
    }

    /** "Prepare the interview" from Get started: show it in the editor, then run its Prepare button's flow. */
    internal suspend fun prepareFocusInterviewAsync() {
        val st = store ?: return
        val focus = focusInterview(st.profiles()) ?: return
        navigateAsync(HomeRoute("interviews"))
        if (target?.id != focus.id) {
            if (!nav.canProceedAsync(ChangeReason.SwitchItem, profileGuard, targetGuard)) return
            if (profile?.id != focus.profileId) refreshProfiles(focus.profileId)
            refreshTargets(focus.id, reload = true)
            if (target?.id != focus.id) return
        }
        requestFocus(HomeFocus.PrepareButton)
        prepareAsync()
    }

    /** Make the dashboard's interview the active one (same steps as "Use this interview"). */
    fun useFocusInterview() {
        val st = store ?: return
        val focus = focusInterview(st.profiles()) ?: return
        if (focus.id != vm.activeTargetId && !vm.sessionActive && vm.workspace != null && vm.workspaceMatchesAccount) {
            vm.setActive(focus.profileId, focus.id)
            val (kb, label) = vm.workspace!!.loadActive(focus.profileId, focus.id)
            vm.useKnowledge(kb, label)
            refreshTargets(vm.activeTargetId)
        }
    }

    /** The first run ends when the user reaches the pre-interview check with the confirmation ticked. */
    fun completeFirstRun() {
        if (ackChecked && !vm.settings.firstRunCompleted) {
            vm.settings.firstRunCompleted = true
            vm.settings.save()
        }
    }

    fun onAckChanged(checked: Boolean) {
        ackChecked = checked
        vm.settings.authorizedUseAcknowledged = checked
        vm.settings.save()
        refreshChecklist()
        if (checked) requestFocus(HomeFocus.PrimaryAction)
    }

    /** Developer builds only: switch this device to developer mode (its own workspace, no account). */
    fun onDeveloperModeClick() {
        if (!BuildFlavor.Developer || vm.sessionActive) return
        vm.settings.mode = "developer"
        vm.settings.save()
        vm.openWorkspaceForCurrentAccount()
        vm.buildEngine()
        refreshAll()
        navigate("settings")
    }

    /** The Account step: cloud mode first (developer builds may be in developer mode), then the page. */
    fun goAccount() {
        if (vm.settings.mode != "cloud" && !vm.sessionActive) {
            vm.settings.mode = "cloud"
            vm.settings.save()
            vm.openWorkspaceForCurrentAccount()
            vm.buildEngine()
        }
        navigate("account")
    }

    /** The Interviews page's "Pre-interview check" button. */
    fun onReadinessClick() = navigate("preinterview")

    // ================= readiness surfaces (HomeWindow.Readiness.cs) =================

    var readinessFooterText: String by bindable("")
        private set
    var readinessFooterBrushKey: String by bindable("B.TextFaint")
        private set
    var pfRunEnabled: Boolean by bindable(true)
        private set
    var pfStartEnabled: Boolean by bindable(false)
        private set
    var pfStartTooltip: String by bindable("")
        private set
    var ivBadgeText: String by bindable("")
        private set
    var ivBadgeIcon: String by bindable("")
        private set
    var ivSummaryText: String by bindable("")
        private set
    var ivAppliesText: String by bindable("")
        private set
    var ivAppliesVisible: Boolean by bindable(false)
        private set
    var interviewCheckText: String by bindable("")
        private set
    var pillText: String by bindable("")
        private set
    var pillBrushKey: String by bindable("B.TextFaint")
        private set
    var pillTooltip: String by bindable("")
        private set
    var pillName: String by bindable("")
        private set

    private val readinessNavigateHandler: (String) -> Unit = { route ->
        val r = HomeRoute.parse(route)
        navigate(r.page, r.section)
    }
    private val readinessStartHandler: () -> Unit = { scope.launch { startInterviewAsync() } }
    private val readinessStateHandler: () -> Unit = { refreshReadinessSurfaces() }

    /** Wires the pre-interview page controller (built in P4-F) into Home, as the C#'s constructor does. */
    private fun initializeReadiness() {
        preInterview.navigateRequestedListeners += readinessNavigateHandler
        preInterview.startRequestedListeners += readinessStartHandler
        preInterview.stateChangedListeners += readinessStateHandler
        refreshReadinessSurfaces()
    }

    private fun detachReadiness() {
        preInterview.navigateRequestedListeners -= readinessNavigateHandler
        preInterview.startRequestedListeners -= readinessStartHandler
        preInterview.stateChangedListeners -= readinessStateHandler
        preInterview.detach()
    }

    private fun relocalizeReadiness() {
        preInterview.relocalize()
    }

    /** The readiness summary line ("7 of 8 checks passed" …) in the current language. */
    fun summaryText(): String {
        val s = ReadinessPresentation.summary(vm.readiness.snapshot, vm.readinessContext)
        return if (s.args.isEmpty()) t(s.key) else f(s.key, *s.args.toTypedArray())
    }

    /** Footer status, the pre-interview footer's buttons, the Interviews card and the sidebar pill — one snapshot. */
    fun refreshReadinessSurfaces() {
        val state = vm.readinessState
        readinessFooterText = "${t(ReadinessSnapshot.stateKey(state))} · ${summaryText()}"
        readinessFooterBrushKey = ReadinessSnapshot.brushKey(state)
        pfRunEnabled = !preInterview.isRunning
        pfStartEnabled = preInterview.canStart
        pfStartTooltip = when {
            preInterview.canStart -> t("tt_pf_start")
            state == ReadinessState.NotReady -> t("tt_pf_start_blocked")
            state == ReadinessState.Checking -> t("rd_hint_checking")
            else -> t("tt_pf_start_run_first")
        }
        renderInterviewReadiness()
        renderReadinessPill()
    }

    /** The readiness card on the Interviews page (same snapshot as everywhere else). */
    fun renderInterviewReadiness() {
        val state = vm.readinessState
        ivBadgeText = t(ReadinessSnapshot.stateKey(state))
        ivBadgeIcon = ReadinessPresentation.icon(state)
        ivSummaryText = summaryText()
        val tg = target
        ivAppliesText = if (tg != null && vm.activeTargetId != null && tg.id != vm.activeTargetId &&
            vm.activeLabel.isNotEmpty()
        ) {
            f("rd_card_applies", vm.activeLabel)
        } else {
            ""
        }
        ivAppliesVisible = ivAppliesText.isNotEmpty()
        interviewCheckText = t(if (ReadinessPresentation.shouldAutoRun(state)) "rd_run_check" else "rd_open_check")
    }

    /** The sidebar readiness pill; on the Get started page it also re-renders the dashboard's check step. */
    fun renderReadinessPill() {
        val state = vm.readinessState
        pillText = t(ReadinessSnapshot.stateKey(state))
        pillBrushKey = ReadinessSnapshot.brushKey(state)
        pillTooltip = if (state == ReadinessState.NotChecked) t("rd_hint_not_checked") else summaryText()
        pillName = f("rd_pill_name", pillText)
        if (page == "start") renderDashboard()
    }

    fun onReadinessPillClick() = navigate("preinterview")

    fun onPfRunClick() {
        scope.launch { preInterview.runAllAsync() }
    }

    fun onPfSettingsClick() = navigate("settings", "audio")

    fun onPfStartClick() = preInterview.requestStart()

    /**
     * "Start the interview": Home closes (asking about unsaved edits first, as on any close) and
     * the live window takes over; listening starts with the "Interview started" notification.
     */
    suspend fun startInterviewAsync() {
        if (!confirmCloseAsync()) return
        if (ackChecked && !vm.settings.firstRunCompleted) {
            vm.settings.firstRunCompleted = true
            vm.settings.save()
        }
        shell.showLiveWindow()
        closeConfirmed = true // the close was already confirmed above
        shell.close()
        if (!vm.isListening) vm.toggleListeningAsync(startToast = "toast_interview_started")
    }

    // ================= help & diagnostics (HomeWindow.Help.cs) =================

    var helpConnectionText: String by bindable("")
        private set
    var helpAudioText: String by bindable("")
        private set
    var helpLatencyText: String by bindable("")
        private set
    var helpQuestionsText: String by bindable("")
        private set
    var healthRows: List<HealthRow> by bindable(emptyList())
        private set
    var healthStatusText: String by bindable("")
        private set
    var healthStatusVisible: Boolean by bindable(false)
        private set
    var healthRunEnabled: Boolean by bindable(true)
        private set
    private var healthRowsOrNull: List<HealthRow>? = null
    private var healthRunning: Boolean = false
    private var diagTimerJob: Job? = null

    /** The four Help diagnostics values plus one automation name per health-check row (self-test hook). */
    val helpDiagnosticsTexts: List<String>
        get() = listOf(helpConnectionText, helpAudioText, helpLatencyText, helpQuestionsText) +
            healthRows.map { healthRowName(it) }

    fun refreshHelpDiagnostics() {
        helpConnectionText = if (!vm.usingCloud) {
            t("hc_service_dev")
        } else {
            when (vm.cloud.state) {
                CloudState.SignedIn -> t("help_diag_conn_ok")
                CloudState.Connecting -> t("acct_connecting")
                CloudState.ServiceUnavailable -> t("acct_unavailable")
                CloudState.NotConfigured -> t("acct_not_configured")
                CloudState.UpdateRequired -> t("acct_update_required")
                CloudState.SessionExpired -> t("acct_session_expired")
                CloudState.AccountDisabled -> t("acct_disabled")
                else -> t("acct_signed_out")
            }
        }
        helpAudioText = "${vm.currentDeviceName.ifEmpty { t("audio_system_default") }} · ${vm.audioStatus}"
        val sample = vm.engine?.metrics?.samples?.lastOrNull()
        val seconds = sample?.let { HealthCheck.answerSeconds(it.speechEndToFirstBulletMs, it.finalizedToFirstBulletMs) }
        helpLatencyText = if (seconds != null) {
            f("help_diag_latency_value", formatFixed1(seconds))
        } else {
            t("help_diag_none")
        }
        helpQuestionsText = LanguageRegistry.numberFormat(Loc.language).format(vm.engine?.metrics?.questions ?: 0)
    }

    /** "0.0" in the UI culture (Latin digits, DC-44). */
    private fun formatFixed1(value: Double): String {
        val nf = LanguageRegistry.numberFormat(Loc.language)
        nf.minimumFractionDigits = 1
        nf.maximumFractionDigits = 1
        nf.isGroupingUsed = false
        return nf.format(value)
    }

    /**
     * One support line (English on purpose — read by the support team): product, version, channel,
     * build, install kind, signing, OS. The signer comes from the platform seam (Authenticode on
     * Windows); only "signed"/"unsigned" is printed, never a credential.
     */
    fun supportLine(): String =
        "${Branding.productName} ${BuildInfo.version} (${BuildInfo.channel}) build ${BuildInfo.commit} · " +
            "${if (BuildInfo.isInstalled) "installed" else "portable"} · " +
            "${if (codeSigner() != null) "signed" else "unsigned"} · ${osVersion()}"

    /** The text "Copy diagnostics" puts on the clipboard: friendly lines only — no raw errors, no secrets. */
    fun diagnosticsText(): String {
        vm.refreshDiagnostics()
        val sb = StringBuilder()
        sb.appendLine(supportLine())
        sb.appendLine("Service: ${if (vm.usingCloud) vm.cloud.state else "developer mode"} · audio ${vm.capture.status}")
        healthRowsOrNull?.let { rows ->
            sb.appendLine("Health check: " + rows.joinToString(", ") { "${it.id} ${it.outcome}" })
        }
        if (vm.diagnostics.isNotEmpty()) sb.appendLine(vm.diagnostics)
        return sb.toString().trimEnd()
    }

    fun onCopyDiagnosticsClick() {
        try {
            clipboard.setText(diagnosticsText())
            notify(t("toast_copied"), ToastKind.Success)
        } catch (ex: ClipboardBusyException) {
            AppLog.warn("Clipboard busy: ${ex.javaClass.simpleName}")
            notify(t("err_diag_copy"), ToastKind.Error)
        }
    }

    fun onRunHealthCheckClick() {
        scope.launch { runHealthCheckAsync() }
    }

    /**
     * "Run check": four facts (playback devices, service reach, settings readable, disk writable)
     * through the pure [HealthCheck] rules. Never throws: any failure shows "the check could not
     * be completed" and logs the exception TYPE only.
     */
    internal suspend fun runHealthCheckAsync(): List<HealthRow> {
        if (healthRunning) return healthRowsOrNull ?: emptyList()
        healthRunning = true
        healthRunEnabled = false
        try {
            val devices = maxOf(0, vm.capture.listDevices().size - 1)
            val facts = HealthFacts(
                playbackDevices = devices,
                service = serviceReachAsync(),
                settingsReadable = settingsReadable(),
                diskWritable = diskWritable(),
            )
            val rows = HealthCheck.evaluate(facts)
            healthRowsOrNull = rows
            healthRows = rows
            for (row in rows) AppLog.info("Health check ${row.id}: ${row.outcome} (${row.detailKey})")
            healthStatusText = f("help_diag_result", HealthCheck.passed(rows), rows.size)
            healthStatusVisible = true
            return rows
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.error("Health check failed: ${ex.javaClass.simpleName}")
            healthStatusText = t("help_diag_failed")
            healthStatusVisible = true
            return healthRowsOrNull ?: emptyList()
        } finally {
            healthRunning = false
            healthRunEnabled = true
        }
    }

    /** Can this device reach the account service right now? Bounded to 10 s. */
    internal suspend fun serviceReachAsync(): ServiceReach {
        if (!vm.usingCloud) return ServiceReach.DeveloperMode
        if (!vm.cloud.isConfigured) return ServiceReach.NotConfigured
        val ok = withTimeoutOrNull(10_000) { vm.cloud.refreshAsync() }
        if (ok == null) return ServiceReach.Unreachable // timed out (the refresh restored its prior state)
        return if (ok || (vm.cloud.state != CloudState.ServiceUnavailable && vm.cloud.state != CloudState.Connecting)) {
            ServiceReach.Reachable
        } else {
            ServiceReach.Unreachable
        }
    }

    /** The settings file parses (a missing file is fine — defaults apply). */
    fun settingsReadable(): Boolean {
        val file = File(AppSettings.currentPath)
        if (!file.exists()) return true
        return try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8))
            true
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (ex is IOException || ex is SecurityException || ex is SerializationException) {
                AppLog.warn("Settings file unreadable: ${ex.javaClass.simpleName}")
                false
            } else {
                throw ex
            }
        }
    }

    /** A probe file can be written and deleted in the local data folder. */
    fun diskWritable(): Boolean {
        val probe = File(AppPaths.local, "health-${UUID.randomUUID().toString().replace("-", "")}.tmp")
        return try {
            probe.writeBytes(byteArrayOf(0))
            true
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) false else throw ex
        } finally {
            try {
                probe.delete()
            } catch (ex: SecurityException) {
                // best effort, as in the C#
            }
        }
    }

    fun healthRowName(row: HealthRow): String = f(
        "fmt_label_value", t(row.labelKey),
        "${t(if (row.outcome == HealthOutcome.Pass) "hc_pass" else "hc_warn")} — ${t(row.detailKey)}",
    )

    fun healthRowIcon(row: HealthRow): String = if (row.outcome == HealthOutcome.Pass) "\uE73E" else "\uE7BA"

    fun healthRowBrushKey(row: HealthRow): String =
        if (row.outcome == HealthOutcome.Pass) "B.Listening" else "B.Thinking"

    /** The Help page's 2 s diagnostics ticker: runs only while the page is shown. */
    fun onHelpVisibilityChanged(visible: Boolean) {
        if (visible) {
            refreshHelpDiagnostics()
            if (diagTimerJob?.isActive != true) {
                diagTimerJob = scope.launch {
                    while (isActive) {
                        delay(2000)
                        refreshHelpDiagnostics()
                    }
                }
            }
        } else {
            diagTimerJob?.cancel()
            diagTimerJob = null
        }
    }

    // ================= data export & import (HomeWindow.DataExport.cs) =================

    private val exportJson: Json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** The account export's two choices (the C#'s ExportDialog.ChooseAccountFormatAsync list). */
    fun accountExportChoices(): List<ExportChoice> = listOf(
        ExportChoice(ExportFormats.AccountHtml, t("exp_opt_html"), t("exp_opt_html_desc")),
        ExportChoice(ExportFormats.AccountJson, t("exp_opt_json"), t("exp_opt_json_desc")),
    )

    /** The profile export's three choices (the C#'s ExportDialog.ChooseProfileFormatAsync list). */
    fun profileExportChoices(): List<ExportChoice> = listOf(
        ExportChoice(ExportFormats.ProfileHtml, t("exp_opt_profile_html"), t("exp_opt_profile_html_desc")),
        ExportChoice(ExportFormats.ProfileBackup, t("exp_opt_backup"), t("exp_opt_backup_desc")),
        ExportChoice(ExportFormats.ProfileRawZip, t("exp_opt_raw_zip"), t("exp_opt_raw_zip_desc")),
    )

    /** The import preview's rows (label, value), exactly the C#'s ConfirmImportAsync grid. */
    fun importPreviewRows(preview: ProfileBundlePreview): List<Pair<String, String>> {
        val nf = LanguageRegistry.numberFormat(Loc.language)
        nf.maximumFractionDigits = 0
        val rows = mutableListOf(
            t("exp_prev_profile") to preview.profileName.ifEmpty { t("profile_unnamed") },
            t("exp_prev_facts") to nf.format(preview.facts),
            t("exp_prev_interviews") to nf.format(preview.interviews),
            t("exp_prev_packs") to nf.format(preview.packs),
            t("exp_prev_reports") to nf.format(preview.reports),
        )
        if (preview.createdUtc > OffsetDateTime.MIN.plusDays(1)) {
            rows += t("exp_prev_created") to formatGeneral(preview.createdUtc)
        }
        if (preview.appVersion.isNotEmpty()) rows += t("exp_prev_app") to preview.appVersion
        return rows
    }

    fun onExportAccountClick() {
        scope.launch { exportAccountDataAsync() }
    }

    internal suspend fun exportAccountDataAsync() {
        if (vm.cloud.api == null) return
        val format = dialogs.chooseExportFormat(
            t("exp_dlg_account_title"), t("exp_dlg_account_message"), accountExportChoices(),
        )
        if (format == null || vm.cloud.api == null) return
        if (!reauthenticateAsync("reauth_reason_export", privacyMessage)) return
        val html = format == ExportFormats.AccountHtml
        val path = files.saveFile(
            "interview-assistant-account-${fileStamp(today())}${if (html) ".html" else ".json"}",
            if (html) t("file_filter_html") + " (*.html)|*.html" else t("file_filter_json") + " (*.json)|*.json",
        ) ?: return
        if (vm.cloud.api == null) return
        val export: JsonElement = try {
            vm.cloud.api!!.exportAsync()
        } catch (ex: BackendException) {
            AppLog.warn("Export failed: ${ex.code} (ref ${ex.correlationId})")
            privacyMessage.show(CloudService.describe(ex, includeReference = false), MessageKind.Error)
            return
        }
        val content: String = try {
            // Raw JSON stays the service's format, minus anything that looks like a credential. A response
            // the filter cannot check (malformed, too deep, duplicate names) is not exported at all.
            val clean = SecretFilter.cleanChecked(export)
            if (html) {
                val account = vm.cloud.account
                val context = account?.let {
                    AccountExportContext(it.entitlements.planName, MainViewModel.billingLabel(it))
                }
                AccountExportRenderer.render(clean, Loc.language, Branding.productName, context)
            } else {
                exportJson.encodeToString(JsonElement.serializer(), clean)
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (!isExportPrepareError(ex)) throw ex
            AppLog.warn("Account export could not be prepared: ${ex.javaClass.simpleName}")
            privacyMessage.show(t("exp_err_account_export"), MessageKind.Error)
            return
        }
        // Written to a temporary file first: a full disk never leaves a truncated export under the chosen name.
        try {
            ExportFile.writeTextAsync(path, content)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (!isStorageError(ex)) throw ex
            AppLog.warn("Account export could not be saved: ${ex.javaClass.simpleName}") // the message holds the path (user name)
            privacyMessage.show(t("exp_err_save"), MessageKind.Error)
            return
        }
        privacyMessage.clear()
        notify(t("toast_account_exported"), ToastKind.Success)
    }

    /** The C#'s prepare-error filter: JSON / invalid-data / argument / invalid-operation / key-not-found / format / null / not-supported. */
    private fun isExportPrepareError(ex: Exception): Boolean = when (ex) {
        is SerializationException, is InvalidDataException, is IllegalArgumentException,
        is IllegalStateException, is NoSuchElementException, is NumberFormatException,
        is NullPointerException, is UnsupportedOperationException,
        -> true
        else -> false
    }

    fun onExportProfileClick() {
        scope.launch { exportProfileAsync() }
    }

    internal suspend fun exportProfileAsync() {
        if (profile == null || store == null) return
        // The export reads the saved profile: pending edits are saved (or discarded) first.
        if (!nav.canProceedAsync(ChangeReason.ReloadData, profileGuard) || profile == null || store == null) return
        val prof = profile!!
        val name = prof.preferredName.ifEmpty { prof.name.ifEmpty { t("profile_unnamed") } }
        val format = dialogs.chooseExportFormat(
            t("exp_dlg_profile_title"), f("exp_dlg_profile_message", name), profileExportChoices(),
        )
        if (format == null || profile == null || store == null) return
        val (ext, filter) = when (format) {
            ExportFormats.ProfileHtml -> ".html" to t("file_filter_html") + " (*.html)|*.html"
            ExportFormats.ProfileBackup ->
                ProfileBundle.FileExtension to t("exp_file_filter_backup") +
                    " (*${ProfileBundle.FileExtension})|*${ProfileBundle.FileExtension}"
            else -> ".zip" to t("file_filter_zip") + " (*.zip)|*.zip"
        }
        val suffix = when (format) {
            ExportFormats.ProfileRawZip -> "-raw-data"
            ExportFormats.ProfileBackup -> "-backup"
            else -> ""
        }
        val path = files.saveFile("${fileSlug(name)}$suffix-${fileStamp(today())}$ext", filter) ?: return
        if (profile == null || store == null) return
        val profileId = profile!!.id
        val st = store!!
        try {
            // Every format is written to a temporary file and moved into place when complete (no truncated file on a full disk).
            when (format) {
                ExportFormats.ProfileHtml -> {
                    val saved = st.getProfile(profileId) ?: profile!!
                    ExportFile.writeTextAsync(path, ProfileExportRenderer.render(saved, Loc.language, Branding.productName))
                }
                ExportFormats.ProfileBackup -> ExportFile.writeAsync(path) { fs ->
                    ProfileBundle.write(st, profileId, fs, BuildInfo.version)
                }
                else -> ExportFile.writeAsync(path) { fs -> st.exportProfile(profileId, fs) }
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (!isStorageError(ex) && ex !is NoSuchElementException) throw ex
            AppLog.warn("Profile export failed: ${ex.javaClass.simpleName}") // the message holds the path (user and candidate name)
            notify(t("err_profile_export"), ToastKind.Error)
            return
        }
        notify(t(if (format == ExportFormats.ProfileBackup) "exp_toast_backup_saved" else "toast_profile_exported"), ToastKind.Success)
    }

    fun onImportProfileBackupClick() {
        scope.launch { importProfileBackupAsync() }
    }

    internal suspend fun importProfileBackupAsync() {
        if (store == null) return
        if (!nav.canProceedAsync(ChangeReason.SwitchItem, profileGuard, targetGuard) ||
            profileLimitReachedAsync() || store == null
        ) return
        val picked = files.pickFile(
            t("exp_import_title"),
            t("exp_file_filter_backup") + " (*${ProfileBundle.FileExtension})|*${ProfileBundle.FileExtension}",
        ) ?: return
        if (store == null) return
        val content: ProfileBundleContent = try {
            if (picked.sizeBytes > ProfileBundleLimits.Default.maxFileBytes) {
                throw ProfileBundleException(ProfileBundleError.TooLarge, "file")
            }
            ProfileBundle.read(picked.readBytes().inputStream())
        } catch (ex: ProfileBundleException) {
            AppLog.warn("Profile backup rejected: ${ex.error}")
            dialogs.alert(describeBundleError(ex.error), DialogTone.Warning)
            return
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (ex is IOException || ex is SecurityException) {
                AppLog.warn("Profile backup could not be read (${ex.javaClass.simpleName})")
                dialogs.alert(t("err_file_read"), DialogTone.Warning)
                return
            }
            // Untrusted input: whatever else goes wrong, the user gets the plain "not a valid backup" message, never a crash.
            AppLog.warn("Profile backup rejected: unexpected ${ex.javaClass.simpleName}")
            dialogs.alert(describeBundleError(ProfileBundleError.Invalid), DialogTone.Warning)
            return
        }
        if (!dialogs.confirmImport(content.preview) || store == null) return
        if (profileLimitReachedAsync() || store == null) return // the plan may have changed while the preview was open
        val st = store!!
        val original = content.preview.profileName
        val name = if (original.isNotEmpty() &&
            st.profiles().any { it.name.equals(original, ignoreCase = true) }
        ) {
            f("exp_imported_name", original)
        } else {
            null
        }
        val (imported, removedLines) = try {
            ProfileBundle.importWithReport(st, content, name)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (isStorageError(ex)) {
                AppLog.warn("Profile backup import failed: ${ex.javaClass.simpleName}")
                dialogs.alert(t("exp_import_failed"), DialogTone.Warning)
                return
            }
            // Anything else comes from the bundle's content (already rolled back): the plain "not a valid backup" message.
            AppLog.warn("Profile backup import rejected: ${ex.javaClass.simpleName}")
            dialogs.alert(describeBundleError(ProfileBundleError.Invalid), DialogTone.Warning)
            return
        }
        AppLog.info(
            "Profile backup imported: ${content.preview.interviews} interviews, ${content.preview.packs} packs, " +
                "${content.preview.reports} reports, $removedLines instruction-like line(s) removed",
        )
        refreshProfiles(imported.id, reload = true)
        refreshReports()
        showPage("profiles")
        refreshChecklist()
        notify(
            t("exp_toast_imported"), ToastKind.Success,
            f("exp_toast_imported_detail", content.preview.interviews, content.preview.reports),
        )
        // Like an uploaded résumé: text that looked like instructions to the AI was removed, and the user is told.
        if (removedLines > 0) dialogs.alert(t("warn_injection_in_document"), DialogTone.Warning)
    }

    // ================= self-test hooks (the C#'s internal test surface) =================

    /** The current page tag. */
    val currentPage: String get() = page

    /** The profile being edited, if any. */
    val editedProfileId: String? get() = profile?.id

    /** The Profiles page's current tab ("facts" / "personal"). */
    val currentProfileTab: String get() = profileTab

    /** The auth form's state machine (tests drive mode transitions through it). */
    val authStateForTest: AuthFormState get() = auth

    val profileGuardForTest: DirtyStateGuard get() = profileGuard
    val targetGuardForTest: DirtyStateGuard get() = targetGuard

    /** Opens the forgot-password state (the C#'s ShowForgotPasswordForTest). */
    fun showForgotPasswordForTest() = onForgotClick()

    // ================= construction (the C#'s HomeWindow constructor) =================

    init {
        // Field edits (the C#'s TextChanged/SelectionChanged handlers + Field.ClearOnEdit):
        // profile/interview fields mark their guard dirty; auth fields clear their own error.
        // Every programmatic write in this class runs under the guard's suspension, where
        // MarkDirty is a no-op — exactly as in the C#.
        addPropertyChangedListener { name -> onFieldEdited(name) }

        // Building the window and filling its pickers raises change events: none of that is a user edit.
        profileGuard.suspend().use {
            targetGuard.suspend().use {
                windowTitle = Branding.productName
                uiLanguageItems = LanguageRegistry.all
                uiLanguageCode = vm.uiLanguage
                buildLanguagePickers()
                ackChecked = vm.settings.authorizedUseAcknowledged
                personalSections = buildPersonalFields()
            }
        }

        // One guard for every editor in Home: asks (in-app dialog) only before real data-loss actions.
        nav.register(profileGuard)
        nav.register(targetGuard)
        nav.register(settingsPage.guard)
        profileGuard.save = { saveProfile() }
        profileGuard.discard = { if (profile != null) loadProfile(store?.getProfile(profile!!.id)) }
        // A "Save" answer that fails validation shows the page with the problem (the user stays to fix it).
        targetGuard.save = {
            if (saveTargetFields() != null) true
            else {
                showPage("interviews")
                requestFocus(HomeFocus.TargetTitle)
                false
            }
        }
        targetGuard.discard = { loadTarget(if (target != null) store?.getTarget(target!!.id) else null) }
        for (g in nav.allGuards) g.addDirtyChangedListener { updateDirtyIndicators() }
        settingsPage.guard.save = {
            if (settingsPage.save()) true
            else {
                showPage("settings")
                settingsPage.showSection("hotkeys")
                false
            }
        }
        settingsPage.savedListeners += { notify(t("toast_settings_saved"), ToastKind.Success) }
        settingsPage.saveFailedListeners += {
            notify(t("err_settings_not_saved"), ToastKind.Error, t("err_settings_not_saved_detail"))
        }
        settingsPage.defaultsRestoredListeners += { category ->
            notify(
                if (category == null) {
                    t("toast_defaults_restored")
                } else {
                    f("toast_section_reset", t(SettingsCategories.all[SettingsCategories.indexOf(category)].titleKey))
                },
                ToastKind.Success,
            )
        }
        initializeReadiness()
        initAuthForm()

        vm.updates.changedListeners += updatesChangedHandler
        vm.cloud.addChangedListener(cloudChangedHandler)
        vm.workspaceChangedListeners += workspaceChangedHandler
        vm.reportSavedListeners += reportSavedHandler
        Loc.addLanguageChangedListener(languageChangedHandler)

        selectProfileTab("facts")
        if (vm.cloud.isSignedIn && vm.cloud.state == CloudState.SignedIn && vm.cloud.account != null) {
            sections.summary.loaded(now())
        }
        refreshAll()
        updateDirtyIndicators()
        // Home opens on the readiness dashboard: first run and every later visit.
        showRoute(initial ?: HomeRoute.start)
    }
}
