package com.interviewassistant.app.ui

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.ProfileBundleError
import com.interviewassistant.app.ui.components.AppDialogResult
import com.interviewassistant.app.ui.components.CurrentPasswordForm
import com.interviewassistant.app.ui.components.DeleteAccountLogic
import com.interviewassistant.app.ui.components.DialogButtonRole
import com.interviewassistant.app.ui.components.ExportChoiceState
import com.interviewassistant.app.ui.components.ExportDialogLogic
import com.interviewassistant.app.ui.components.NewPasswordForm
import com.interviewassistant.app.ui.components.PasswordField
import com.interviewassistant.app.ui.components.RovingKey
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.app.viewmodels.DeleteAccountChoice
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.ExportFormats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the form rules of Views/Dialogs.cs (Prompt), Views/DeleteAccountDialog.cs and
 * Views/ExportDialog.cs / ExportChoiceList.xaml.cs: validation, the delete-account action construction and
 * choice mapping, the export choices and their initial selection.
 */
class DialogFormsTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() {
            UiStateLocFixtures.installLocTables()
            Loc.language = "en"
        }
    }

    // ---------------- new password (Prompt.NewPasswordAsync) ----------------

    @Test
    fun newPassword_TooShort_IsRefusedWithTheShortError() {
        val f = NewPasswordForm()
        f.password = "short"; f.confirm = "short"
        assertFalse(f.validate())
        assertEquals(Loc.t("err_password_short"), f.errorText)
        assertEquals(PasswordField.New, f.errorField)
        assertNull(f.result)
    }

    @Test
    fun newPassword_Mismatch_IsRefusedWithTheMismatchError() {
        val f = NewPasswordForm()
        f.password = "long-enough"; f.confirm = "different-long"
        assertFalse(f.validate())
        assertEquals(Loc.t("err_password_mismatch"), f.errorText)
        assertEquals(PasswordField.Confirm, f.errorField)
    }

    @Test
    fun newPassword_EightCharsAndMatching_Passes() {
        val f = NewPasswordForm()
        f.password = "12345678"; f.confirm = "12345678"
        assertTrue(f.validate())
        assertNull(f.errorText)
        assertEquals("12345678", f.result)
    }

    // ---------------- current password (Prompt.CurrentPasswordAsync) ----------------

    @Test
    fun currentPassword_Empty_IsRefusedWithoutCallingVerify() = runBlocking {
        val f = CurrentPasswordForm()
        var calls = 0
        assertFalse(f.validate { calls++; null })
        assertEquals(Loc.t("err_password_empty"), f.errorText)
        assertEquals(0, calls)
    }

    @Test
    fun currentPassword_Wrong_ShowsTheVerifierErrorAndClearsTheBox() = runBlocking {
        val f = CurrentPasswordForm()
        f.password = "wrong"
        assertFalse(f.validate { "err_wrong_password" })
        assertEquals("err_wrong_password", f.errorText)
        assertEquals("", f.password)
        assertEquals(PasswordField.Current, f.errorField)
    }

    @Test
    fun currentPassword_Correct_Passes() = runBlocking {
        val f = CurrentPasswordForm()
        f.password = "right"
        assertTrue(f.validate { null })
        assertNull(f.errorText)
    }

    // ---------------- delete account (DeleteAccountDialog.ConfirmAsync) ----------------

    @Test
    fun deleteAccount_ChoiceMapping_OnlyTheUnblockedDialogCanDelete() {
        assertEquals(DeleteAccountChoice.Delete, DeleteAccountLogic.choice(AppDialogResult.Primary, subscriptionBlocks = false))
        assertEquals(DeleteAccountChoice.Keep, DeleteAccountLogic.choice(AppDialogResult.Primary, subscriptionBlocks = true))
        assertEquals(DeleteAccountChoice.ManageBilling, DeleteAccountLogic.choice(AppDialogResult.Secondary, subscriptionBlocks = true))
        assertEquals(DeleteAccountChoice.Keep, DeleteAccountLogic.choice(AppDialogResult.Secondary, subscriptionBlocks = false))
        assertEquals(DeleteAccountChoice.Keep, DeleteAccountLogic.choice(AppDialogResult.Cancel, subscriptionBlocks = false))
        assertEquals(DeleteAccountChoice.Keep, DeleteAccountLogic.choice(AppDialogResult.Cancel, subscriptionBlocks = true))
        assertEquals(DeleteAccountChoice.Keep, DeleteAccountLogic.choice(AppDialogResult.None, subscriptionBlocks = false))
    }

    @Test
    fun deleteAccount_Actions_Unblocked_KeepIsDefault_DeleteIsRed() {
        val (keep, delete) = DeleteAccountLogic.actions(subscriptionBlocks = false)
        assertEquals(Loc.t("del_keep"), keep.text)
        assertTrue(keep.isCancel); assertTrue(keep.isDefault)
        assertEquals(Loc.t("del_confirm"), delete.text)
        assertEquals(DialogButtonRole.Destructive, delete.role)
        assertFalse(delete.isDefault)
        assertEquals(DialogTone.Danger, DeleteAccountLogic.tone(false))
        assertEquals("del_irreversible", DeleteAccountLogic.messageKey(false))
        assertEquals("del_note_subscription", DeleteAccountLogic.subscriptionNoteKey(false))
    }

    @Test
    fun deleteAccount_Actions_Blocked_ManageBillingIsTheDefault_NoDeleteOffered() {
        val (keep, billing) = DeleteAccountLogic.actions(subscriptionBlocks = true)
        assertEquals(Loc.t("del_keep"), keep.text)
        assertTrue(keep.isCancel); assertFalse(keep.isDefault)
        assertEquals(Loc.t("manage_billing"), billing.text)
        assertEquals(DialogButtonRole.Primary, billing.role)
        assertEquals(AppDialogResult.Secondary, billing.result)
        assertTrue(billing.isDefault)
        assertEquals(DialogTone.Warning, DeleteAccountLogic.tone(true))
        assertEquals("del_note_subscription_blocks", DeleteAccountLogic.messageKey(true))
        assertEquals("del_note_subscription_blocks", DeleteAccountLogic.subscriptionNoteKey(true))
    }

    @Test
    fun deleteAccount_ItemsAndNotes_AreTheDialogKeys() {
        assertEquals(
            listOf("del_item_account", "del_item_plan", "del_item_devices", "del_item_service_data"),
            DeleteAccountLogic.itemKeys,
        )
        assertEquals(listOf("del_note_paddle", "del_note_providers", "del_note_local"), DeleteAccountLogic.noteKeys)
        (DeleteAccountLogic.itemKeys + DeleteAccountLogic.noteKeys).forEach { assertTrue(Loc.has(it)) }
    }

    @Test
    fun localDataActions_KeepIsDefaultAndEscape_DeleteIsDestructive() {
        val (keep, delete) = DeleteAccountLogic.localDataActions()
        assertEquals(Loc.t("del_local_keep"), keep.text)
        assertTrue(keep.isCancel); assertTrue(keep.isDefault)
        assertEquals(Loc.t("del_local_delete"), delete.text)
        assertEquals(DialogButtonRole.Destructive, delete.role)
        assertEquals(AppDialogResult.Primary, delete.result)
    }

    // ---------------- export dialogs (ExportDialog.cs) ----------------

    @Test
    fun exportChoices_CarryTheFormatKeysAndLocalizedTexts() {
        val account = ExportDialogLogic.accountChoices()
        assertEquals(listOf(ExportFormats.AccountHtml, ExportFormats.AccountJson), account.map { it.key })
        assertEquals(Loc.t("exp_opt_html"), account[0].label)
        assertEquals(Loc.t("exp_opt_json_desc"), account[1].description)
        val profile = ExportDialogLogic.profileChoices()
        assertEquals(
            listOf(ExportFormats.ProfileHtml, ExportFormats.ProfileBackup, ExportFormats.ProfileRawZip),
            profile.map { it.key },
        )
        assertEquals(Loc.t("exp_opt_backup"), profile[1].label)
    }

    @Test
    fun exportChoiceState_PreselectsTheNamedKey_ElseTheFirst() {
        val choices = ExportDialogLogic.profileChoices()
        assertEquals(ExportFormats.ProfileHtml, ExportChoiceState(choices).selectedKey)
        assertEquals(ExportFormats.ProfileBackup, ExportChoiceState(choices, ExportFormats.ProfileBackup).selectedKey)
        assertEquals(ExportFormats.ProfileHtml, ExportChoiceState(choices, "no-such-key").selectedKey)
        val empty = ExportChoiceState(emptyList())
        assertNull(empty.selectedKey)
        assertEquals(-1, empty.selectedIndex)
    }

    @Test
    fun exportChoiceState_ArrowsMoveAndWrap_MirroredInRtl() {
        val state = ExportChoiceState(ExportDialogLogic.profileChoices())
        assertTrue(state.onArrowKey(RovingKey.Down, rtl = false))
        assertEquals(1, state.selectedIndex)
        assertTrue(state.onArrowKey(RovingKey.Down, rtl = false))
        assertTrue(state.onArrowKey(RovingKey.Down, rtl = false)) // wraps to the first
        assertEquals(0, state.selectedIndex)
        assertTrue(state.onArrowKey(RovingKey.Up, rtl = false)) // wraps to the last
        assertEquals(2, state.selectedIndex)
        assertTrue(state.onArrowKey(RovingKey.Left, rtl = true)) // RTL: Left moves forward
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun exportDescribe_MapsErrorsToPlainLanguageKeys() {
        assertEquals(Loc.t("exp_import_newer"), ExportDialogLogic.describe(ProfileBundleError.NewerVersion))
        assertEquals(Loc.t("exp_import_too_large"), ExportDialogLogic.describe(ProfileBundleError.TooLarge))
        assertEquals(Loc.t("exp_import_invalid"), ExportDialogLogic.describe(ProfileBundleError.NotABundle))
        assertEquals(Loc.t("exp_import_invalid"), ExportDialogLogic.describe(ProfileBundleError.Tampered))
    }
}
