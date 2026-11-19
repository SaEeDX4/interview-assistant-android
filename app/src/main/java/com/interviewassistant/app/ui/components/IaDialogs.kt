package com.interviewassistant.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ProfileBundlePreview
import com.interviewassistant.app.viewmodels.DeleteAccountChoice
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.ExportChoice
import com.interviewassistant.app.viewmodels.MessageKind
import com.interviewassistant.app.ui.theme.IaTextStyles
import com.interviewassistant.app.ui.theme.LocalIaColors
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The code-built dialogs of Views/Dialogs.cs, Views/DeleteAccountDialog.cs and Views/ExportDialog.cs, as
 * suspend builders over a [DialogController] — the shell's implementation of the view models' dialog seam.
 * Field layout is the app's one layout ([IaFormField] via [DialogFormField]); validation runs inside the
 * dialog through the actions' Validate, exactly as in the C#.
 */
object IaDialogs {

    /** New password (twice) with inline validation; null when cancelled (Prompt.NewPasswordAsync). */
    suspend fun newPassword(controller: DialogController): String? {
        val form = NewPasswordForm()
        val result = controller.show(
            DialogRequest(
                title = Loc.t("password_choose_new"), tone = DialogTone.Info,
                body = { NewPasswordBody(form) },
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(
                        text = Loc.t("set_password"), role = DialogButtonRole.Primary, isDefault = true,
                        validate = { form.validate() },
                    ),
                ),
            ),
        )
        return if (result == AppDialogResult.Primary) form.result else null
    }

    /**
     * Asks for the current password and checks it with [verify] (null = correct, else the error to show)
     * while the dialog stays open (Prompt.CurrentPasswordAsync). True = the password verified.
     */
    suspend fun currentPassword(
        controller: DialogController,
        reason: String,
        email: String,
        verify: suspend (String) -> String?,
    ): Boolean {
        val form = CurrentPasswordForm()
        val result = controller.show(
            DialogRequest(
                title = Loc.t("reauth_title"), message = reason, tone = DialogTone.Info,
                body = { CurrentPasswordBody(form, email) },
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(
                        text = Loc.t("dlg_continue"), role = DialogButtonRole.Primary, isDefault = true,
                        validate = { form.validate(verify) },
                    ),
                ),
            ),
        )
        return result == AppDialogResult.Primary
    }

    /** Account deletion, step 1 (DeleteAccountDialog.ConfirmAsync). Step 2 is [currentPassword]. */
    suspend fun deleteAccount(controller: DialogController, subscriptionBlocks: Boolean): DeleteAccountChoice {
        val result = controller.show(
            DialogRequest(
                title = Loc.t("del_title"),
                message = Loc.t(DeleteAccountLogic.messageKey(subscriptionBlocks)),
                tone = DeleteAccountLogic.tone(subscriptionBlocks),
                body = { DeleteAccountDialogBody(subscriptionBlocks) },
                maxWidth = 520.dp,
                actions = DeleteAccountLogic.actions(subscriptionBlocks),
            ),
        )
        return DeleteAccountLogic.choice(result, subscriptionBlocks)
    }

    /** After the deletion: keep (default, Escape) or delete this device's copy of the account's data. */
    suspend fun askDeleteLocalData(controller: DialogController): Boolean {
        val result = controller.show(
            DialogRequest(
                title = Loc.t("del_local_title"), message = Loc.t("del_local_message"), tone = DialogTone.Question,
                actions = DeleteAccountLogic.localDataActions(),
            ),
        )
        return result == AppDialogResult.Primary
    }

    /** A choice between export formats: Continue (default, Enter) / Cancel (Escape); null when cancelled. */
    suspend fun chooseExportFormat(
        controller: DialogController,
        title: String,
        message: String,
        choices: List<ExportChoice>,
        selectedKey: String? = null,
    ): String? {
        val state = ExportChoiceState(choices, selectedKey)
        val result = controller.show(
            DialogRequest(
                title = title, message = message, tone = DialogTone.Question,
                body = { ExportChoiceList(state, Loc.t("exp_choice_group")) },
                maxWidth = 520.dp,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(text = Loc.t("dlg_continue"), result = AppDialogResult.Primary, role = DialogButtonRole.Primary, isDefault = true),
                ),
            ),
        )
        return if (result == AppDialogResult.Primary) state.selectedKey else null
    }

    /** What a backup contains, before anything is written: Import (default) / Cancel (ExportDialog.ConfirmImportAsync). */
    suspend fun confirmImport(controller: DialogController, preview: ProfileBundlePreview): Boolean {
        val result = controller.show(
            DialogRequest(
                title = Loc.t("exp_import_title"), message = Loc.t("exp_import_message"), tone = DialogTone.Question,
                body = { ImportPreviewBody(preview) },
                maxWidth = 480.dp,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(text = Loc.t("exp_import_btn"), result = AppDialogResult.Primary, role = DialogButtonRole.Primary, isDefault = true),
                ),
            ),
        )
        return result == AppDialogResult.Primary
    }
}

/** `DialogForm.Labelled` of Dialogs.cs: a labelled field in the app's one form-field layout. */
@Composable
private fun DialogFormField(label: String, hint: String = "", content: @Composable () -> Unit) =
    IaFormField(label = label, hint = hint, content = content)

@Composable
private fun NewPasswordBody(form: NewPasswordForm) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column {
        DialogFormField(Loc.t("password_new")) {
            PasswordInput(
                value = form.password,
                onValueChange = { form.password = it },
                hasError = form.errorField == PasswordField.New,
                description = Loc.t("password_new"),
                modifier = Modifier.focusRequester(focus),
            )
        }
        DialogFormField(Loc.t("confirm_password"), hint = Loc.t("password_rules")) {
            PasswordInput(
                value = form.confirm,
                onValueChange = { form.confirm = it },
                hasError = form.errorField == PasswordField.Confirm,
                description = Loc.t("confirm_password"),
            )
        }
        form.errorText?.let { IaInlineMessage(it, MessageKind.Error) }
    }
}

@Composable
private fun CurrentPasswordBody(form: CurrentPasswordForm, email: String) {
    val colors = LocalIaColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column {
        if (email.isNotEmpty()) {
            // the account address reads left-to-right also in Arabic/Persian
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(email, style = IaTextStyles.body(colors).copy(color = colors.Text))
                }
            }
        }
        DialogFormField(Loc.t("password_current")) {
            PasswordInput(
                value = form.password,
                onValueChange = { form.password = it },
                hasError = form.errorField == PasswordField.Current,
                description = Loc.t("password_current"),
                modifier = Modifier.focusRequester(focus),
            )
        }
        form.errorText?.let { IaInlineMessage(it, MessageKind.Error) }
    }
}

/** Step 1's body (DeleteAccountDialog.ConfirmAsync): the deleted-items list, the subscription note, the hints. */
@Composable
fun DeleteAccountDialogBody(subscriptionBlocks: Boolean) {
    val colors = LocalIaColors.current
    Column {
        Text(
            Loc.t("del_will_be_deleted"),
            style = IaTextStyles.label(colors),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).semantics { heading() },
        )
        DeleteAccountLogic.itemKeys.forEach { key ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp, end = 10.dp)) {
                    IaIcon(IaGlyph.Cross, 11.dp, colors.Error, null)
                }
                Text(Loc.t(key), style = IaTextStyles.body(colors).copy(color = colors.Text))
            }
        }
        IaInlineMessage(
            Loc.t(DeleteAccountLogic.subscriptionNoteKey(subscriptionBlocks)),
            if (subscriptionBlocks) MessageKind.Warning else MessageKind.Info,
            modifier = Modifier.padding(top = 4.dp),
        )
        DeleteAccountLogic.noteKeys.forEach { key ->
            Text(Loc.t(key), style = IaTextStyles.hint(colors), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The import preview grid (ExportDialog.ConfirmImportAsync): label/value rows, counts in the UI culture. */
@Composable
private fun ImportPreviewBody(preview: ProfileBundlePreview) {
    val colors = LocalIaColors.current
    val numbers = remember { java.text.NumberFormat.getNumberInstance(Loc.locale) }
    @Composable
    fun row(labelKey: String, value: String, technical: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(Loc.t(labelKey), style = IaTextStyles.metaKey(colors), modifier = Modifier.weight(1f))
            if (technical) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(value, style = IaTextStyles.body(colors).copy(color = colors.Text))
                }
            } else {
                Text(value, style = IaTextStyles.body(colors).copy(color = colors.Text))
            }
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        row("exp_prev_profile", preview.profileName.ifEmpty { Loc.t("profile_unnamed") })
        row("exp_prev_facts", numbers.format(preview.facts))
        row("exp_prev_interviews", numbers.format(preview.interviews))
        row("exp_prev_packs", numbers.format(preview.packs))
        row("exp_prev_reports", numbers.format(preview.reports))
        if (preview.createdUtc.isAfter(OffsetDateTime.MIN.plusDays(1))) {
            val formatted = preview.createdUtc.atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT).withLocale(Loc.locale))
            row("exp_prev_created", formatted)
        }
        if (preview.appVersion.isNotEmpty()) row("exp_prev_app", preview.appVersion, technical = true)
    }
}
