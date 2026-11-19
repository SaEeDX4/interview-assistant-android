package com.interviewassistant.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.interviewassistant.app.ui.components.AppDialogResult
import com.interviewassistant.app.ui.components.DialogAction
import com.interviewassistant.app.ui.components.DialogButtonRole
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.DialogRequest
import com.interviewassistant.app.ui.components.IaCheck
import com.interviewassistant.app.ui.components.IaCombo
import com.interviewassistant.app.ui.components.IaFormField
import com.interviewassistant.app.ui.components.IaInlineMessage
import com.interviewassistant.app.ui.components.Input
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.LocalIaColors
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.FactEditResult
import com.interviewassistant.app.viewmodels.FactRow
import com.interviewassistant.app.viewmodels.MessageKind
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.languages.Loc

/**
 * The fact editor (the C#'s `Views/Dialogs.cs` FactEditor; deferred from P4-D): add or edit one
 * profile fact — kind (locked while editing), the fact text, the role fields, and the confirm
 * tick, with the C#'s inline validation. Shown by the shell's [com.interviewassistant.app.viewmodels.HomeDialogs]
 * implementation over the dialog host.
 */

/** The editor's kind list, in the C#'s order (FactEditor.Kinds). */
val FactEditorKinds: List<FactKind> = listOf(
    FactKind.Role, FactKind.Achievement, FactKind.Skill, FactKind.Education, FactKind.Language,
    FactKind.Certification, FactKind.Project, FactKind.Summary, FactKind.Other,
)

private const val FACT_TEXT_MAX = 800
private const val FACT_FIELD_MAX = 120

/**
 * The editor's form state (the P4-D dialog-form pattern): snapshot state the body binds, and the
 * C#'s validation — the text must have at least 2 non-space characters, unless the kind is Role
 * and a job title is present (a role is carried by its title/company/period fields).
 */
class FactEditorForm(row: FactRow?) {
    /** The C#: `Math.Max(0, IndexOf(Kinds, f?.Kind ?? Achievement))` — an unlisted kind shows the first entry. */
    var kindIndex: Int by mutableStateOf(
        maxOf(0, FactEditorKinds.indexOf(row?.fact?.kind ?: FactKind.Achievement)),
    )

    /** The kind combo is disabled while editing an existing fact (the C#'s `IsEnabled = f == null`). */
    val kindLocked: Boolean = row != null

    var text: String by mutableStateOf(row?.fact?.text ?: "")
    var company: String by mutableStateOf(row?.fact?.company ?: "")
    var title: String by mutableStateOf(row?.fact?.title ?: "")
    var period: String by mutableStateOf(row?.fact?.period ?: "")
    var confirmed: Boolean by mutableStateOf(row?.confirmed ?: true)

    var errorText: String? by mutableStateOf(null)
        private set
    var textHasError: Boolean by mutableStateOf(false)
        private set

    val kind: FactKind get() = FactEditorKinds[kindIndex]

    /** The Save action's validation: false keeps the dialog open with the error shown inside. */
    fun validate(): Boolean {
        if (text.trim().length < 2 && !(kind == FactKind.Role && title.trim().isNotEmpty())) {
            errorText = Loc.t("fact_text_required")
            textHasError = true
            return false
        }
        errorText = null
        textHasError = false
        return true
    }

    /** The dialog's result (the C# builds it inside Validate, from the trimmed field values). */
    fun result(): FactEditResult =
        FactEditResult(kind, text.trim(), company.trim(), title.trim(), period.trim(), confirmed)
}

object FactEditor {

    /** Shows the editor over [controller]; null = cancelled (FactEditor.ShowAsync). */
    suspend fun show(controller: DialogController, row: FactRow?): FactEditResult? {
        val form = FactEditorForm(row)
        val result = controller.show(
            DialogRequest(
                title = Loc.t(if (row == null) "fact_add_title" else "fact_edit_title"),
                tone = DialogTone.Info,
                body = { FactEditorBody(form) },
                maxWidth = 560.dp,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(
                        text = Loc.t("dlg_save"), role = DialogButtonRole.Primary, isDefault = true,
                        validate = { form.validate() },
                    ),
                ),
            ),
        )
        return if (result == AppDialogResult.Primary) form.result() else null
    }
}

@Composable
private fun FactEditorBody(form: FactEditorForm) {
    val colors = LocalIaColors.current
    val textRequester = remember { FocusRequester() }
    // The C#'s InitialFocus: the fact text field takes the focus when the editor opens.
    LaunchedEffect(Unit) { runCatching { textRequester.requestFocus() } }
    Column {
        IaFormField(label = Loc.t("fact_kind")) {
            IaCombo(
                items = FactEditorKinds.map { Loc.t("fact_kind_" + it.name.lowercase()) },
                selectedIndex = form.kindIndex,
                onSelect = { form.kindIndex = it },
                enabled = !form.kindLocked,
                description = Loc.t("fact_kind"),
            )
        }
        IaFormField(label = Loc.t("fact_text")) {
            Input.Multiline(
                value = form.text,
                // The C#'s MaxLength (DC-146): over-limit edits are refused, so the text never exceeds it.
                onValueChange = { if (it.length <= FACT_TEXT_MAX) form.text = it },
                tall = true,
                hasError = form.textHasError,
                description = Loc.t("fact_text"),
                modifier = Modifier.focusRequester(textRequester),
            )
        }
        Row {
            IaFormField(label = Loc.t("fact_company"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = form.company,
                    onValueChange = { if (it.length <= FACT_FIELD_MAX) form.company = it },
                    description = Loc.t("fact_company"),
                )
            }
            Spacer(Modifier.width(IaTokens.ColumnGap))
            IaFormField(label = Loc.t("fact_title"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = form.title,
                    onValueChange = { if (it.length <= FACT_FIELD_MAX) form.title = it },
                    description = Loc.t("fact_title"),
                )
            }
            Spacer(Modifier.width(IaTokens.ColumnGap))
            IaFormField(label = Loc.t("fact_period"), modifier = Modifier.weight(1f)) {
                Input.Single(
                    value = form.period,
                    onValueChange = { if (it.length <= FACT_FIELD_MAX) form.period = it },
                    description = Loc.t("fact_period"),
                )
            }
        }
        IaCheck(
            checked = form.confirmed,
            onCheckedChange = { form.confirmed = it },
            label = Loc.t("fact_confirm_label"),
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            Loc.t("fact_confirm_hint"),
            style = IaTextStyles.hint(colors),
            modifier = Modifier.padding(top = IaTokens.HintMarginTop),
        )
        form.errorText?.let { error ->
            IaInlineMessage(
                text = error, kind = MessageKind.Error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
