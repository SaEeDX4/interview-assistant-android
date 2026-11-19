package com.interviewassistant.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ProfileBundleError
import com.interviewassistant.app.viewmodels.DeleteAccountChoice
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.ExportChoice
import com.interviewassistant.app.viewmodels.ExportFormats

/**
 * Form states of the code-built dialogs (Views/Dialogs.cs `Prompt`, Views/DeleteAccountDialog.cs,
 * Views/ExportDialog.cs): the field values, the inline error and the validation rules, with no UI — the
 * Compose host binds controls to them and the dialog actions call [validate]. Error texts are the localized
 * strings, exactly as the C# shows them via its InlineMessage.
 */

/** Which field a password-form error belongs to (the C# focuses it and marks it with Field.SetHasError). */
enum class PasswordField { New, Confirm, Current }

/**
 * New password (twice) with inline validation (Prompt.NewPasswordAsync): at least 8 characters, and the two
 * entries must match. [result] holds the password once [validate] has passed.
 */
class NewPasswordForm {
    // Snapshot state, so the dialog's controlled fields and inline error recompose as the user types
    // (the C# binds the same plain properties through WPF data binding).
    var password: String by androidx.compose.runtime.mutableStateOf("")
    var confirm: String by androidx.compose.runtime.mutableStateOf("")
    var errorText: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var errorField: PasswordField? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var result: String? = null
        private set

    fun validate(): Boolean {
        if (password.length < 8) {
            errorText = Loc.t("err_password_short"); errorField = PasswordField.New
            return false
        }
        if (password != confirm) {
            errorText = Loc.t("err_password_mismatch"); errorField = PasswordField.Confirm
            return false
        }
        errorText = null; errorField = null
        result = password
        return true
    }
}

/**
 * Current-password prompt (Prompt.CurrentPasswordAsync): asks for the current password and checks it with
 * [verify] (null = correct, else the error to show) while the dialog stays open: a wrong password is
 * reported inside the dialog, the box is cleared, and the user can retry.
 */
class CurrentPasswordForm {
    var password: String by androidx.compose.runtime.mutableStateOf("")
    var errorText: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var errorField: PasswordField? by androidx.compose.runtime.mutableStateOf(null)
        private set

    suspend fun validate(verify: suspend (String) -> String?): Boolean {
        errorText = null; errorField = null
        if (password.isEmpty()) {
            errorText = Loc.t("err_password_empty"); errorField = PasswordField.Current
            return false
        }
        val problem = verify(password)
        if (problem == null) return true
        errorText = problem; errorField = PasswordField.Current
        password = ""
        return false
    }
}

/**
 * Account deletion, step 1 (Views/DeleteAccountDialog.cs): what will be deleted, the subscription note, and
 * the actions. When the subscription can still charge, deletion is not offered at all — the primary action
 * is "Manage billing" and "Keep account" closes the dialog. Otherwise "Keep account" is the default (and
 * Escape) and "Delete permanently" is the red action. Step 2 is the re-authentication
 * ([CurrentPasswordForm]); after the deletion a separate question decides about the local data
 * ([localDataActions]).
 */
object DeleteAccountLogic {
    /** The "will be deleted" bullet keys, in order. */
    val itemKeys: List<String> = listOf("del_item_account", "del_item_plan", "del_item_devices", "del_item_service_data")

    /** The hint lines under the subscription note. */
    val noteKeys: List<String> = listOf("del_note_paddle", "del_note_providers", "del_note_local")

    fun tone(subscriptionBlocks: Boolean): DialogTone = if (subscriptionBlocks) DialogTone.Warning else DialogTone.Danger

    fun messageKey(subscriptionBlocks: Boolean): String =
        if (subscriptionBlocks) "del_note_subscription_blocks" else "del_irreversible"

    fun subscriptionNoteKey(subscriptionBlocks: Boolean): String =
        if (subscriptionBlocks) "del_note_subscription_blocks" else "del_note_subscription"

    fun actions(subscriptionBlocks: Boolean): List<DialogAction> =
        if (subscriptionBlocks) {
            listOf(
                DialogAction(text = Loc.t("del_keep"), result = AppDialogResult.Cancel, role = DialogButtonRole.Secondary, isCancel = true),
                DialogAction(text = Loc.t("manage_billing"), result = AppDialogResult.Secondary, role = DialogButtonRole.Primary, isDefault = true),
            )
        } else {
            listOf(
                DialogAction(text = Loc.t("del_keep"), result = AppDialogResult.Cancel, role = DialogButtonRole.Secondary, isCancel = true, isDefault = true),
                DialogAction(text = Loc.t("del_confirm"), result = AppDialogResult.Primary, role = DialogButtonRole.Destructive),
            )
        }

    /** Only the unblocked dialog can return [DeleteAccountChoice.Delete]. */
    fun choice(result: AppDialogResult, subscriptionBlocks: Boolean): DeleteAccountChoice = when {
        result == AppDialogResult.Primary && !subscriptionBlocks -> DeleteAccountChoice.Delete
        result == AppDialogResult.Secondary && subscriptionBlocks -> DeleteAccountChoice.ManageBilling
        else -> DeleteAccountChoice.Keep
    }

    /** After the deletion: keep (default, Escape) or delete this device's copy of the account's data. */
    fun localDataActions(): List<DialogAction> = listOf(
        DialogAction(text = Loc.t("del_local_keep"), result = AppDialogResult.Cancel, role = DialogButtonRole.Secondary, isCancel = true, isDefault = true),
        DialogAction(text = Loc.t("del_local_delete"), result = AppDialogResult.Primary, role = DialogButtonRole.Destructive),
    )
}

/** The export dialogs' content (Views/ExportDialog.cs): the format choices and the refusal copy. */
object ExportDialogLogic {
    /** "Readable report (HTML) — recommended" or "Raw data (JSON) — advanced". */
    fun accountChoices(): List<ExportChoice> = listOf(
        ExportChoice(ExportFormats.AccountHtml, Loc.t("exp_opt_html"), Loc.t("exp_opt_html_desc")),
        ExportChoice(ExportFormats.AccountJson, Loc.t("exp_opt_json"), Loc.t("exp_opt_json_desc")),
    )

    /** Readable profile (HTML), backup & transfer (.iaprofile) or raw data (ZIP). */
    fun profileChoices(): List<ExportChoice> = listOf(
        ExportChoice(ExportFormats.ProfileHtml, Loc.t("exp_opt_profile_html"), Loc.t("exp_opt_profile_html_desc")),
        ExportChoice(ExportFormats.ProfileBackup, Loc.t("exp_opt_backup"), Loc.t("exp_opt_backup_desc")),
        ExportChoice(ExportFormats.ProfileRawZip, Loc.t("exp_opt_raw_zip"), Loc.t("exp_opt_raw_zip_desc")),
    )

    /** Plain-language reason a backup was refused (never the technical detail). */
    fun describe(error: ProfileBundleError): String = Loc.t(
        when (error) {
            ProfileBundleError.NewerVersion -> "exp_import_newer"
            ProfileBundleError.TooLarge -> "exp_import_too_large"
            else -> "exp_import_invalid"
        },
    )
}
