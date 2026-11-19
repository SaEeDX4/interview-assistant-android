package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.DirtyStateGuard
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.uistate.SetupStep
import com.interviewassistant.app.uistate.StepStatus
import com.interviewassistant.contracts.DeviceDto
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactSource
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ProfileBundlePreview
import com.interviewassistant.core.reports.InterviewReport
import com.interviewassistant.core.persistence.StoredSession
import com.interviewassistant.app.uistate.UsageMetric
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Row/record types and view seams of the Home window (the C#'s `HomeWindow.*.cs` display records,
 * the `Views/` dialog helpers it calls, and the platform interactions it performs). Everything here
 * is view-neutral: WPF types in the C# state (Visibility, GridLength, Brush) become booleans,
 * fractions and "B.*" brush-key strings, exactly as the P4-A UiState ports did (DC-67).
 */

// ---------------------------------------------------------------------------
// Inline messages (the C#'s Views/InlineMessage.cs, as state)
// ---------------------------------------------------------------------------

/** Mirror of `InterviewAssistant.App/Views/InlineMessage.cs`'s `MessageKind` (same members, same order). */
enum class MessageKind { Error, Warning, Info, Success }

/**
 * The state of one inline message (icon + text + optional detail + optional text action, e.g.
 * "Try again"), placed right where the problem is. Hidden while it has no text. The Compose
 * component (P4-D) binds these properties; every text is assigned by [HomeViewModel] exactly
 * where the C# calls `InlineMessage.Show`/`Clear`.
 */
class InlineMessageState : ObservableObject() {
    var text: String by bindable("")
    var detail: String? by bindable(null)
    var kind: MessageKind by bindable(MessageKind.Error)
    var actionText: String? by bindable(null)

    val isVisible: Boolean get() = text.isNotEmpty()

    fun show(text: String, kind: MessageKind, detail: String? = null, actionText: String? = null) {
        this.text = text
        this.kind = kind
        this.detail = detail
        this.actionText = actionText
    }

    fun clear() {
        text = ""
        detail = null
        actionText = null
    }
}

// ---------------------------------------------------------------------------
// Dialog seams (the C#'s Views/AppDialog.cs, Dialogs.cs, ExportDialog.cs,
// DeleteAccountDialog.cs — shown by the shell, faked by tests)
// ---------------------------------------------------------------------------

/** Mirror of `InterviewAssistant.App/Views/AppDialog.cs`'s `DialogTone`. */
enum class DialogTone { Info, Question, Warning, Danger, Success }

/** Mirror of `InterviewAssistant.App/Views/DeleteAccountDialog.cs`'s `DeleteAccountChoice`. */
enum class DeleteAccountChoice { Keep, Delete, ManageBilling }

/** Keys the header account menu handles (the C#'s AccountMenu_KeyDown cases). */
enum class AccountMenuKey { Down, Up, Home, End, Escape }

/** Mirror of `InterviewAssistant.App/Views/Dialogs.cs`'s `FactEditResult` record. */
data class FactEditResult(
    val kind: FactKind,
    val text: String,
    val company: String,
    val title: String,
    val period: String,
    val confirmed: Boolean,
)

/** One choice of the export format dialog (the C#'s `Views/ExportChoiceList` item). */
data class ExportChoice(val key: String, val label: String, val description: String)

/** The export format keys (the C#'s `Views/ExportDialog.cs` constants). */
object ExportFormats {
    const val AccountHtml: String = "html"
    const val AccountJson: String = "json"
    const val ProfileHtml: String = "profile-html"
    const val ProfileBackup: String = "backup"
    const val ProfileRawZip: String = "raw-zip"
}

/**
 * The in-app dialogs Home asks for, with the C# shapes. The shell (P4-D/E) implements them over
 * the dialog host; tests supply fakes. No fake behaviour lives in the view model: every flow
 * awaits the real answer of this seam.
 */
interface HomeDialogs {
    /** `AppDialog.IsOpen(owner)`: a dialog is currently open over Home. */
    val isOpen: Boolean

    /** `AppDialog.AlertAsync(owner, message, tone)` — a message with a single OK button. */
    suspend fun alert(message: String, tone: DialogTone)

    /** `AppDialog.ConfirmAsync(owner, message, confirmText, destructive, title)`. */
    suspend fun confirm(message: String, confirmText: String, destructive: Boolean = false, title: String? = null): Boolean

    /** `AppDialog.AskUnsavedAsync(owner, message)` — Save (default) / Discard changes / Cancel (= Stay). */
    suspend fun askUnsaved(message: String): LeaveDecision

    /** `FactEditor.ShowAsync(owner, row)`; null = cancelled. */
    suspend fun editFact(row: FactRow?): FactEditResult?

    /**
     * `Prompt.CurrentPasswordAsync(owner, reason, email, verify)`: asks for the current password
     * and checks it with [verify] (null = correct, else the error to show) while the prompt stays
     * open. True = the password verified.
     */
    suspend fun currentPassword(reason: String, email: String, verify: suspend (String) -> String?): Boolean

    /** `Prompt.NewPasswordAsync(owner)`; null = cancelled. */
    suspend fun newPassword(): String?

    /**
     * `ExportDialog.ChooseAsync(owner, title, message, choices)`: the chosen key (the first
     * choice is preselected); null = cancelled.
     */
    suspend fun chooseExportFormat(title: String, message: String, choices: List<ExportChoice>): String?

    /** `ExportDialog.ConfirmImportAsync(owner, preview)` — the backup preview, Import / Cancel. */
    suspend fun confirmImport(preview: ProfileBundlePreview): Boolean

    /** `DeleteAccountDialog.ConfirmAsync(owner, subscriptionBlocks)`. */
    suspend fun confirmDeleteAccount(subscriptionBlocks: Boolean): DeleteAccountChoice

    /** `DeleteAccountDialog.AskDeleteLocalDataAsync(owner)`. */
    suspend fun askDeleteLocalData(): Boolean
}

// ---------------------------------------------------------------------------
// File, clipboard and window seams
// ---------------------------------------------------------------------------

/**
 * One file the user picked: name and size are known before the content is read (the C# checks
 * the `FileInfo.Length` first, so an oversized file is refused without being read).
 */
interface PickedFile {
    val name: String
    val sizeBytes: Long
    suspend fun readBytes(): ByteArray
}

interface HomeFiles {
    /** The C#'s `OpenFileDialog` (title + the WPF filter text; the platform maps it to its picker). Null = cancelled. */
    suspend fun pickFile(title: String, filter: String): PickedFile?

    /** The C#'s `SaveFileDialog` (initial file name + filter text): the chosen path. Null = cancelled. */
    suspend fun saveFile(fileName: String, filter: String): String?
}

/** The platform clipboard is busy (the C#'s `ExternalException` from `Clipboard.SetText`). */
class ClipboardBusyException : Exception("The clipboard is busy")

interface HomeClipboard {
    /** `Clipboard.SetText`. Throws [ClipboardBusyException] when another app holds the clipboard. */
    fun setText(text: String)
}

/** Home's window geometry as `SavePlacement` reads it (the C#'s WindowState + bounds). */
data class HomePlacement(
    val isMaximized: Boolean,
    val isNormal: Boolean,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

interface HomeShell {
    /** `Window.Activate()`. */
    fun activate()

    /** `Window.Close()` (the close runs through [HomeViewModel.onClosing] again; the confirmed close passes). */
    fun close()

    /** `MainWindow.Show()` + `Activate()`: the Live Assistant comes forward. */
    fun showLiveWindow()

    /** `MainWindow.ShowToast(text, kind)`. */
    fun showLiveToast(text: String, kind: ToastKind)

    /** `Process.Start(path, UseShellExecute = true)` for a report viewer copy; may throw IOException/SecurityException. */
    fun openFile(path: String)

    /** `Process.Start(folder, UseShellExecute = true)` for the log folder; may throw IOException/SecurityException. */
    fun openFolder(path: String)

    /** The current window placement (read by `SavePlacement`). */
    fun placement(): HomePlacement
}

/** Focus targets the view model asks the view to focus (the C#'s scattered `.Focus()` calls). */
object HomeFocus {
    const val TargetTitle: String = "targetTitle"
    const val ProfileName: String = "profileName"

    /** Profile name, with the text selected (after "New empty profile"). */
    const val ProfileNameSelectAll: String = "profileNameSelectAll"
    const val AuthForm: String = "authForm"
    const val Email: String = "email"
    const val Password: String = "password"
    const val ConfirmPassword: String = "confirmPassword"
    const val PrepareButton: String = "prepareButton"
    const val PrimaryAction: String = "primaryAction"
    const val AccountMenuButton: String = "accountMenuButton"
    const val AccountMenuFirstItem: String = "accountMenuFirstItem"
    const val ReportList: String = "reportList"
}

// ---------------------------------------------------------------------------
// Page controllers hosted by Home (built by their own packages)
// ---------------------------------------------------------------------------

/**
 * The Settings page as Home hosts it (the C#'s `Views/SettingsPage` surface Home uses). The
 * controller itself is P4-G: it owns [guard] (registered into Home's one NavigationGuard),
 * wires its own Save/Discard into the guard, and raises the three notifications Home toasts.
 */
interface SettingsPageController {
    val guard: DirtyStateGuard
    fun save(): Boolean
    fun discard()
    fun showSection(section: String?)
    fun loadFromSettings()
    fun relocalize()
    fun refreshUpdates()
    val savedListeners: MutableList<() -> Unit>
    val saveFailedListeners: MutableList<() -> Unit>
    val defaultsRestoredListeners: MutableList<(String?) -> Unit>
}

/**
 * The pre-interview check page as Home hosts it (the C#'s `Views/PreInterviewPage` surface Home
 * uses). The controller itself is P4-F: it writes the one readiness snapshot in the main view
 * model and raises [stateChangedListeners] after every render, like the C# page's `StateChanged`.
 */
interface PreInterviewPageController {
    val isRunning: Boolean
    val canStart: Boolean
    fun refresh()
    fun relocalize()
    fun onShown()
    fun onHidden()
    fun detach()
    suspend fun runAllAsync()
    fun requestStart()
    val navigateRequestedListeners: MutableList<(String) -> Unit>
    val startRequestedListeners: MutableList<() -> Unit>
    val stateChangedListeners: MutableList<() -> Unit>
}

// ---------------------------------------------------------------------------
// Display records
// ---------------------------------------------------------------------------

/** Display record of the C# (`Item<T>`): [toString] is the label, so no picker can ever show a debug format. */
data class ListItem<T>(val label: String, val value: T) {
    override fun toString(): String = label
}

/** Display record of the C# (`LangItem`): a language picker's entry. */
data class LangItem(val label: String, val code: String) {
    override fun toString(): String = label
}

/** One fact in the review list: confirmation is a pending edit until Save profile (the C#'s `FactRow`). */
class FactRow(val fact: ProfileFact, val initial: FactStatus) : ObservableObject() {
    var confirmed: Boolean by bindable(fact.status == FactStatus.UserConfirmed)

    val text: String
        get() = if (fact.kind == FactKind.Role) {
            "${fact.title} — ${fact.company} (${fact.period})".replace(" ()", "").trim(' ', '—')
        } else {
            fact.text
        }

    val meta: String
        get() {
            var kind = Loc.t("fact_kind_" + fact.kind.name.lowercase())
            if ((fact.kind == FactKind.Personal || fact.kind == FactKind.Boundary) &&
                com.interviewassistant.core.domain.PersonalCategories.find(fact.category) != null
            ) {
                kind = Loc.t("pc_${fact.category}_label")
            }
            if (fact.status == FactStatus.AiInference) kind += " · " + Loc.t("fact_ai_inference")
            val source = when (fact.source) {
                FactSource.ResumeImport -> Loc.f("fact_source_resume", fact.sourceName.ifEmpty { Loc.t("fact_source_document") })
                FactSource.Manual -> Loc.t("fact_source_manual")
                FactSource.Questionnaire -> Loc.t("fact_source_questionnaire")
                FactSource.Document -> Loc.f("fact_source_resume", fact.sourceName)
                else -> if (fact.provenance != null) Loc.t("fact_source_document") else Loc.t("fact_source_unknown")
            }
            val line = fact.provenance?.let { p ->
                if (p.snippet.isNotEmpty() && fact.source != FactSource.Manual) " · " + Loc.f("fact_line", p.lineStart) else ""
            } ?: ""
            val whenText = (fact.updatedUtc ?: fact.createdUtc)?.let { " · " + formatShortDate(it) } ?: ""
            return "$kind · $source$line$whenText"
        }

    /** Re-raises every property (the C#'s `RaiseAll` after a language switch: Text/Meta are computed). */
    fun raiseAll() = raise("")
}

/** One row of the Reports list: when, which interview, how many questions (the C#'s `ReportRow`). */
data class ReportRow(
    val session: StoredSession,
    val report: InterviewReport?,
    val whenText: String,
    val title: String,
    val questions: String,
) {
    override fun toString(): String =
        listOf(whenText, title, questions).filter { it.isNotEmpty() }.joinToString(", ")
}

/** One metric card of "Plan and usage" (the C#'s `MetricRow` record; star columns become fractions). */
data class MetricRow(
    val label: String,
    val value: String,
    val detail: String,
    val detailVisible: Boolean,
    val barVisible: Boolean,
    val filledFraction: Double,
    val restFraction: Double,
    val barName: String,
    val barBrushKey: String,
) {
    companion object {
        fun from(m: UsageMetric): MetricRow {
            val p = m.progress ?: 0.0
            return MetricRow(
                label = m.label,
                value = m.value,
                detail = m.detail,
                detailVisible = m.detail.isNotEmpty(),
                barVisible = m.progress != null,
                filledFraction = p,
                restFraction = 1 - p,
                barName = "${m.label}: ${m.value}",
                barBrushKey = if (p >= 0.9) "B.Thinking" else "B.Accent",
            )
        }
    }
}

/** One registered device (the C#'s `DeviceRow` record). */
data class DeviceRow(
    val device: DeviceDto,
    val name: String,
    val meta: String,
    val thisPcVisible: Boolean,
    val removeName: String,
    val canRemove: Boolean,
)

/** One optional interview-profile question (built from the `PersonalCategories` schema). */
data class PersonalFieldModel(
    val key: String,
    val labelKey: String,
    val promptKey: String,
    val maxLength: Int = 600,
    val multiline: Boolean = true,
)

/** One section of the interview-profile questionnaire. */
data class PersonalSectionModel(
    val section: String,
    val headingKey: String,
    val fields: List<PersonalFieldModel>,
)

/** One step card of the Get started dashboard, as `RenderDashboard` computes it. */
data class DashboardStep(
    val step: SetupStep,
    val status: StepStatus,
    val stateText: String,
    val detail: String,
) {
    val isDone: Boolean get() = status == StepStatus.Done
    val isCurrent: Boolean get() = status == StepStatus.Current

    /** Brush key of the step's number disc background; null = transparent. */
    val markBackgroundKey: String? get() = if (isCurrent) "B.AccentStrong" else if (isDone) "B.Surface2" else null
    val markBorderKey: String get() = if (isCurrent) "B.AccentStrong" else if (isDone) "B.Listening" else "B.Border"
    val numberVisible: Boolean get() = !isDone

    /** The number is white on the accent disc (the C#'s `Brushes.White`); otherwise faint text. */
    val numberOnAccent: Boolean get() = isCurrent
    val iconVisible: Boolean get() = isDone
    val stateBrushKey: String get() = if (isDone) "B.Listening" else if (isCurrent) "B.Accent" else "B.TextFaint"
    val cardBorderKey: String get() = if (isCurrent) "B.Accent" else "B.Border"
    val detailBrushKey: String get() = if (status == StepStatus.Upcoming) "B.TextFaint" else "B.TextDim"
}

/** One OAuth provider button of the sign-in form. */
data class ProviderButtonModel(val provider: String, val label: String, val enabled: Boolean)

/** One legal link of the Account page (label + the URL it opens). */
data class LegalLinkModel(val label: String, val url: String)

// ---------------------------------------------------------------------------
// Date helpers (the C#'s "d" / "g" / "t" formats in the UI language's culture)
// ---------------------------------------------------------------------------

/**
 * The Account page's date culture: the UI language's formatting locale (Arabic on the Gregorian
 * calendar, so billing dates match payment receipts — `LanguageRegistry.FormattingCulture`).
 */
internal val dateCulture: java.util.Locale get() = LanguageRegistry.formattingLocale(Loc.language)

/** `"d"`: short date, local time. */
internal fun formatShortDate(t: OffsetDateTime): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(dateCulture)
        .format(t.atZoneSameInstant(ZoneId.systemDefault()))

/** `"g"`: short date + short time, local time. */
internal fun formatGeneral(t: OffsetDateTime): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT).withLocale(dateCulture)
        .format(t.atZoneSameInstant(ZoneId.systemDefault()))

/** `"t"`: short time, local time. */
internal fun formatShortTime(t: OffsetDateTime): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(dateCulture)
        .format(t.atZoneSameInstant(ZoneId.systemDefault()))
