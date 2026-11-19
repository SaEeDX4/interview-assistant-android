package com.interviewassistant.app.ui

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.ui.components.AppDialog
import com.interviewassistant.app.ui.components.AppDialogResult
import com.interviewassistant.app.ui.components.DialogAction
import com.interviewassistant.app.ui.components.DialogButtonRole
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.DialogFocus
import com.interviewassistant.app.ui.components.DialogRequest
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.app.viewmodels.DialogTone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the AppDialog/DialogHost rules of Views/AppDialog.cs (builders' action construction, Escape and
 * Enter semantics, validation, stacking, owner-close) against the pure [DialogController].
 */
class DialogControllerTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() {
            UiStateLocFixtures.installLocTables()
            Loc.language = "en"
        }
    }

    private suspend fun awaitTop(c: DialogController): DialogController.Layer {
        repeat(100) { c.topLayer?.let { return it }; delay(10) }
        error("dialog never appeared")
    }

    @Test
    fun alert_HasASingleOkThatIsDefaultAndCancel() = runBlocking {
        val c = DialogController()
        val result = async { AppDialog.alert(c, "Hello", DialogTone.Info) }
        val layer = awaitTop(c)
        assertEquals(1, layer.request.actions.size)
        val ok = layer.request.actions.single()
        assertEquals(Loc.t("dlg_ok"), ok.text)
        assertEquals(DialogButtonRole.Primary, ok.role)
        assertTrue(ok.isDefault); assertTrue(ok.isCancel)
        c.clickAction(layer.id, ok)
        assertEquals(AppDialogResult.Primary, result.await())
        assertFalse(c.isOpen)
    }

    @Test
    fun confirm_NonDestructive_ConfirmIsDefault() = runBlocking {
        val c = DialogController()
        val answer = async { AppDialog.confirm(c, "Proceed?", "Go") }
        val layer = awaitTop(c)
        assertEquals(DialogTone.Question, layer.request.tone)
        val (cancel, confirm) = layer.request.actions
        assertEquals(Loc.t("dlg_cancel"), cancel.text)
        assertTrue(cancel.isCancel); assertFalse(cancel.isDefault)
        assertEquals(DialogButtonRole.Primary, confirm.role)
        assertTrue(confirm.isDefault)
        c.clickAction(layer.id, confirm)
        assertTrue(answer.await())
    }

    @Test
    fun confirm_Destructive_CancelIsDefault_ConfirmIsRed_EscapeSaysNo() = runBlocking {
        val c = DialogController()
        val answer = async { AppDialog.confirm(c, "Delete?", "Delete", destructive = true) }
        val layer = awaitTop(c)
        assertEquals(DialogTone.Danger, layer.request.tone)
        val (cancel, confirm) = layer.request.actions
        assertTrue(cancel.isDefault)
        assertEquals(DialogButtonRole.Destructive, confirm.role)
        assertFalse(confirm.isDefault)
        assertTrue(c.escape(layer.id))
        assertFalse(answer.await())
    }

    @Test
    fun escape_WithASingleAction_RunsIt() = runBlocking {
        val c = DialogController()
        val result = async { AppDialog.alert(c, "Hi") }
        val layer = awaitTop(c)
        assertTrue(c.escape(layer.id))
        assertEquals(AppDialogResult.Primary, result.await())
    }

    @Test
    fun escape_OnNoDialog_IsNotHandled() {
        val c = DialogController()
        assertFalse(c.escape(1))
    }

    @Test
    fun validateFalse_KeepsTheDialogOpen_AndBusyBlocksEscape() = runBlocking {
        val c = DialogController()
        val gate = CompletableDeferred<Boolean>()
        var validations = 0
        val action = DialogAction(
            text = "Save", role = DialogButtonRole.Primary, isDefault = true,
            validate = { validations++; gate.await() },
        )
        val result = async {
            c.show(DialogRequest(message = "m", actions = listOf(DialogAction(text = "Cancel", result = AppDialogResult.Cancel, isCancel = true), action)))
        }
        val layer = awaitTop(c)
        val click = async { c.clickAction(layer.id, action) }
        repeat(100) { if (c.topLayer?.busy == true) return@repeat; delay(10) }
        assertTrue(c.topLayer!!.busy)
        assertTrue(c.escape(layer.id)) // handled, but busy: nothing happens
        assertTrue(c.isOpen)
        gate.complete(false)
        click.await()
        assertEquals(1, validations)
        assertTrue(c.isOpen) // validation failed: still open
        assertFalse(c.topLayer!!.busy)
        assertTrue(c.escape(layer.id))
        assertEquals(AppDialogResult.Cancel, result.await())
    }

    @Test
    fun cancelActions_NeverValidate() = runBlocking {
        val c = DialogController()
        var validations = 0
        val cancel = DialogAction(
            text = "Cancel", result = AppDialogResult.Cancel, isCancel = true,
            validate = { validations++; false },
        )
        val result = async { c.show(DialogRequest(message = "m", actions = listOf(cancel))) }
        val layer = awaitTop(c)
        c.clickAction(layer.id, cancel)
        assertEquals(AppDialogResult.Cancel, result.await())
        assertEquals(0, validations)
    }

    @Test
    fun enter_MultilineAndOpenComboKeepTheKey_DefaultRunsOtherwise() = runBlocking {
        val c = DialogController()
        val save = DialogAction(text = "Save", role = DialogButtonRole.Primary, isDefault = true)
        val discard = DialogAction(text = "Discard", result = AppDialogResult.Secondary, role = DialogButtonRole.Destructive)
        val result = async {
            c.show(DialogRequest(message = "m", actions = listOf(DialogAction(text = "Cancel", result = AppDialogResult.Cancel, isCancel = true), discard, save)))
        }
        val layer = awaitTop(c)
        c.enter(layer.id, DialogFocus.MultilineField)
        assertTrue(c.isOpen)
        c.enter(layer.id, DialogFocus.OpenCombo)
        assertTrue(c.isOpen)
        c.enter(layer.id, DialogFocus.Button(1)) // the focused button runs itself
        assertEquals(AppDialogResult.Secondary, result.await())
    }

    @Test
    fun enter_Other_RunsTheDefaultAction() = runBlocking {
        val c = DialogController()
        val result = async { AppDialog.alert(c, "Hi") }
        val layer = awaitTop(c)
        c.enter(layer.id, DialogFocus.Other)
        assertEquals(AppDialogResult.Primary, result.await())
    }

    @Test
    fun dialogsStack_TheNewestOwnsTheKeys() = runBlocking {
        val c = DialogController()
        val first = async { AppDialog.alert(c, "first") }
        val firstLayer = awaitTop(c)
        val second = async { AppDialog.alert(c, "second") }
        repeat(100) { if (c.layers.value.size == 2) return@repeat; delay(10) }
        val secondLayer = c.topLayer!!
        assertTrue(secondLayer.id != firstLayer.id)
        // keys addressed to the buried layer do nothing
        assertFalse(c.escape(firstLayer.id))
        assertTrue(c.isOpen)
        assertTrue(c.escape(secondLayer.id))
        assertEquals(AppDialogResult.Primary, second.await())
        assertEquals(firstLayer.id, c.topLayer!!.id)
        assertTrue(c.escape(firstLayer.id))
        assertEquals(AppDialogResult.Primary, first.await())
    }

    @Test
    fun ownerClosed_CompletesEverythingWithCancel_AndLateShowsAnswerAtOnce() = runBlocking {
        val c = DialogController()
        val pending = async { AppDialog.alert(c, "waiting") }
        awaitTop(c)
        c.closeOwner()
        assertEquals(AppDialogResult.Cancel, pending.await())
        assertFalse(c.isOpen)
        assertEquals(AppDialogResult.Cancel, AppDialog.alert(c, "late"))
    }

    @Test
    fun askUnsaved_MapsResultsToLeaveDecisions() = runBlocking {
        suspend fun decide(pick: (List<DialogAction>) -> DialogAction): LeaveDecision {
            val c = DialogController()
            val decision = async { AppDialog.askUnsaved(c, "Unsaved changes") }
            val layer = awaitTop(c)
            assertEquals(Loc.t("dlg_unsaved_title"), layer.request.title)
            assertEquals(DialogTone.Warning, layer.request.tone)
            c.clickAction(layer.id, pick(layer.request.actions))
            return decision.await()
        }
        assertEquals(LeaveDecision.Save, decide { it.first { a -> a.result == AppDialogResult.Primary } })
        assertEquals(LeaveDecision.Discard, decide { it.first { a -> a.result == AppDialogResult.Secondary } })
        assertEquals(LeaveDecision.Stay, decide { it.first { a -> a.result == AppDialogResult.Cancel } })
    }

    @Test
    fun initialFocus_IsTheDefaultAction_UnlessAnActionIsNamed() { runBlocking {
        val c = DialogController()
        val named = DialogAction(text = "Field-side", result = AppDialogResult.Secondary)
        val def = DialogAction(text = "OK", isDefault = true)
        val req = DialogRequest(message = "m", actions = listOf(named, def))
        val pending = async { c.show(req) }
        val layer = awaitTop(c)
        assertEquals(1, c.initialFocusIndex(layer))
        c.closeOwner(); pending.await()

        val c2 = DialogController()
        val req2 = DialogRequest(message = "m", actions = listOf(named, def), initialFocusAction = named)
        val pending2 = async { c2.show(req2) }
        val layer2 = awaitTop(c2)
        assertEquals(0, c2.initialFocusIndex(layer2))
        c2.closeOwner(); pending2.await()

        val c3 = DialogController()
        val req3 = DialogRequest(message = "m", actions = listOf(DialogAction(text = "Only")))
        val pending3 = async { c3.show(req3) }
        assertEquals(-1, c3.initialFocusIndex(awaitTop(c3)))
        c3.closeOwner(); pending3.await()
    } }
}
