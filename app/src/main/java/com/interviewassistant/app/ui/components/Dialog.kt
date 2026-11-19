package com.interviewassistant.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.viewmodels.DialogTone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Mirror of `Views/AppDialog.cs`'s result / role enums (same members, same order). */
enum class AppDialogResult { None, Primary, Secondary, Cancel }
enum class DialogButtonRole { Primary, Secondary, Destructive }

/**
 * One button of an in-app dialog (AppDialog.cs `DialogAction`). [validate] runs before the dialog closes;
 * false keeps it open (a validation error shown inside the dialog). While it runs every button is disabled,
 * so a double click cannot submit twice. Cancel actions never validate.
 */
class DialogAction(
    val text: String,
    val result: AppDialogResult = AppDialogResult.Primary,
    val role: DialogButtonRole = DialogButtonRole.Secondary,
    /** Enter (outside buttons and multi-line fields) runs this action; it also receives the initial focus. */
    val isDefault: Boolean = false,
    /** Escape runs this action. */
    val isCancel: Boolean = false,
    val validate: (suspend () -> Boolean)? = null,
)

/**
 * A dialog to show (AppDialog.cs `DialogRequest`). [body] is optional form content below the message;
 * [initialFocusAction] names the action whose button takes the initial focus instead of the default action
 * (the C#'s InitialFocus element — form dialogs point it at their first field via the host's focus seam).
 */
class DialogRequest(
    val title: String? = null,
    val message: String? = null,
    val tone: DialogTone = DialogTone.Info,
    val body: (@Composable () -> Unit)? = null,
    val actions: List<DialogAction>,
    val maxWidth: Dp = 460.dp,
    val initialFocusAction: DialogAction? = null,
)

/** What has the keyboard focus when Enter is pressed inside a dialog (AppDialog.cs `OnKeyAsync`'s switch). */
sealed interface DialogFocus {
    data object Other : DialogFocus
    /** A multi-line text field: Enter inserts a new line. */
    data object MultilineField : DialogFocus
    /** A ComboBox with its drop-down open: Enter picks the highlighted item. */
    data object OpenCombo : DialogFocus
    /** One of the dialog's own buttons: Enter runs that button. */
    data class Button(val actionIndex: Int) : DialogFocus
}

/**
 * The dialog stack of one window (AppDialog.cs `DialogHost`): dialogs stack, the newest is on top and owns
 * the keyboard; a closing window completes every pending dialog with Cancel, and a late caller on a closed
 * owner is answered Cancel at once, never left waiting. Pure logic — the Compose host renders [layers].
 */
class DialogController {

    data class Layer(val id: Int, val request: DialogRequest, val busy: Boolean = false)

    private val _layers = MutableStateFlow<List<Layer>>(emptyList())
    val layers: StateFlow<List<Layer>> = _layers
    private val deferreds = mutableMapOf<Int, CompletableDeferred<AppDialogResult>>()
    private var nextId = 1

    var ownerClosed: Boolean = false
        private set

    val isOpen: Boolean get() = _layers.value.isNotEmpty()
    val topLayer: Layer? get() = _layers.value.lastOrNull()

    /** Shows a dialog and awaits its result (AppDialog.ShowAsync). */
    suspend fun show(request: DialogRequest): AppDialogResult {
        if (ownerClosed) return AppDialogResult.Cancel
        val id = nextId++
        val deferred = CompletableDeferred<AppDialogResult>()
        synchronized(this) {
            deferreds[id] = deferred
            _layers.value = _layers.value + Layer(id, request)
        }
        return deferred.await()
    }

    /** A button click: validation first (buttons disabled meanwhile), then completion (AppDialog.RunAsync). */
    suspend fun clickAction(layerId: Int, action: DialogAction) {
        val layer = synchronized(this) { _layers.value.firstOrNull { it.id == layerId } } ?: return
        if (layer.busy || isCompleted(layerId)) return
        if (action.validate != null && !action.isCancel) {
            setBusy(layerId, true)
            val ok = try {
                action.validate.invoke()
            } finally {
                setBusy(layerId, false)
            }
            if (!ok) return
        }
        complete(layerId, action.result)
    }

    /**
     * Escape: runs the cancel action directly (no validation); with a single action, that action. While the
     * layer is busy the key is handled but does nothing. True when the key was handled.
     */
    fun escape(layerId: Int): Boolean {
        val layer = topLayer ?: return false
        if (layer.id != layerId) return false
        if (layer.busy) return true
        val actions = layer.request.actions
        val cancel = actions.firstOrNull { it.isCancel } ?: if (actions.size == 1) actions.first() else null
        if (cancel != null) complete(layerId, cancel.result)
        return true
    }

    /** Enter: the C#'s rules — multiline fields and open combos keep it; a focused button runs itself; else the default action runs. */
    suspend fun enter(layerId: Int, focus: DialogFocus) {
        val layer = topLayer ?: return
        if (layer.id != layerId) return
        when (focus) {
            DialogFocus.MultilineField, DialogFocus.OpenCombo -> return
            is DialogFocus.Button -> {
                val action = layer.request.actions.getOrNull(focus.actionIndex) ?: return
                clickAction(layerId, action)
            }
            DialogFocus.Other -> {
                val def = layer.request.actions.firstOrNull { it.isDefault } ?: return
                clickAction(layerId, def)
            }
        }
    }

    /** The window closed: every pending dialog completes with Cancel (DialogHost's owner-Closed handler). */
    fun closeOwner() {
        ownerClosed = true
        val pending = synchronized(this) { _layers.value.toList() }
        pending.forEach { complete(it.id, AppDialogResult.Cancel) }
    }

    /** Index of the action that takes the initial focus: the explicit initial-focus action, else the default action, else −1. */
    fun initialFocusIndex(layer: Layer): Int {
        val req = layer.request
        if (req.initialFocusAction != null) return req.actions.indexOf(req.initialFocusAction)
        return req.actions.indexOfFirst { it.isDefault }
    }

    private fun isCompleted(layerId: Int): Boolean =
        synchronized(this) { deferreds[layerId]?.isCompleted ?: true }

    private fun setBusy(layerId: Int, busy: Boolean) {
        synchronized(this) {
            _layers.value = _layers.value.map { if (it.id == layerId) it.copy(busy = busy) else it }
        }
    }

    private fun complete(layerId: Int, result: AppDialogResult) {
        val deferred = synchronized(this) {
            _layers.value = _layers.value.filter { it.id != layerId }
            deferreds.remove(layerId)
        }
        deferred?.complete(result)
    }
}

/**
 * The static dialog builders of AppDialog.cs, over a [DialogController]. In-app dialogs only — no OS
 * message boxes in product flows.
 */
object AppDialog {

    /** A message with a single OK button (the OK is both default and cancel). */
    suspend fun alert(controller: DialogController, message: String, tone: DialogTone = DialogTone.Info, title: String? = null): AppDialogResult =
        controller.show(
            DialogRequest(
                title = title, message = message, tone = tone,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_ok"), role = DialogButtonRole.Primary, isDefault = true, isCancel = true),
                ),
            ),
        )

    /**
     * Yes/no question. Non-destructive: the confirm button is the default. Destructive: the confirm button
     * is red and Cancel is the default, so Enter never destroys anything by accident.
     */
    suspend fun confirm(
        controller: DialogController,
        message: String,
        confirmText: String,
        destructive: Boolean = false,
        title: String? = null,
    ): Boolean {
        val result = controller.show(
            DialogRequest(
                title = title, message = message, tone = if (destructive) DialogTone.Danger else DialogTone.Question,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, role = DialogButtonRole.Secondary, isCancel = true, isDefault = destructive),
                    DialogAction(text = confirmText, result = AppDialogResult.Primary, role = if (destructive) DialogButtonRole.Destructive else DialogButtonRole.Primary, isDefault = !destructive),
                ),
            ),
        )
        return result == AppDialogResult.Primary
    }

    /** Unsaved changes: Save (default) / Discard changes / Cancel (= stay). */
    suspend fun askUnsaved(controller: DialogController, message: String): LeaveDecision {
        val result = controller.show(
            DialogRequest(
                title = Loc.t("dlg_unsaved_title"), message = message, tone = DialogTone.Warning,
                actions = listOf(
                    DialogAction(text = Loc.t("dlg_cancel"), result = AppDialogResult.Cancel, isCancel = true),
                    DialogAction(text = Loc.t("dlg_discard"), result = AppDialogResult.Secondary, role = DialogButtonRole.Destructive),
                    DialogAction(text = Loc.t("dlg_save"), result = AppDialogResult.Primary, role = DialogButtonRole.Primary, isDefault = true),
                ),
            ),
        )
        return when (result) {
            AppDialogResult.Primary -> LeaveDecision.Save
            AppDialogResult.Secondary -> LeaveDecision.Discard
            else -> LeaveDecision.Stay
        }
    }
}
