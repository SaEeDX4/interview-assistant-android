package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.DirtyStateGuard
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.core.persistence.ProfileBundlePreview
import java.io.IOException

/** Test doubles for Home's view seams (dialogs, files, clipboard, shell, hosted page controllers). */

class FakeHomeDialogs : HomeDialogs {
    override var isOpen: Boolean = false

    /** Ordered log of seam calls, for flow-ordering checks ("confirm" before "delete" …). */
    val calls = mutableListOf<String>()

    val alerts = mutableListOf<Pair<String, DialogTone>>()
    var confirmAnswer: Boolean = true
    val confirmCalls = mutableListOf<String>()
    var unsavedAnswer: LeaveDecision = LeaveDecision.Save
    val unsavedCalls = mutableListOf<String>()
    var factResult: FactEditResult? = null
    var editFactCalls: Int = 0

    /** The password the fake prompt "types"; null = the user cancels the prompt. */
    var passwordToEnter: String? = "correct-password"
    var newPasswordAnswer: String? = "new-password-1"
    var exportChoice: (List<ExportChoice>) -> String? = { it.firstOrNull()?.key }
    var importAnswer: Boolean = true
    var lastPreview: ProfileBundlePreview? = null
    var deleteChoice: DeleteAccountChoice = DeleteAccountChoice.Delete
    var deleteLocalAnswer: Boolean = false

    override suspend fun alert(message: String, tone: DialogTone) {
        calls += "alert"
        alerts += message to tone
    }

    override suspend fun confirm(
        message: String,
        confirmText: String,
        destructive: Boolean,
        title: String?,
    ): Boolean {
        calls += "confirm"
        confirmCalls += message
        return confirmAnswer
    }

    override suspend fun askUnsaved(message: String): LeaveDecision {
        calls += "askUnsaved"
        unsavedCalls += message
        return unsavedAnswer
    }

    override suspend fun editFact(row: FactRow?): FactEditResult? {
        calls += "editFact"
        editFactCalls++
        return factResult
    }

    override suspend fun currentPassword(
        reason: String,
        email: String,
        verify: suspend (String) -> String?,
    ): Boolean {
        calls += "currentPassword"
        val pw = passwordToEnter ?: return false
        return verify(pw) == null
    }

    override suspend fun newPassword(): String? {
        calls += "newPassword"
        return newPasswordAnswer
    }

    override suspend fun chooseExportFormat(
        title: String,
        message: String,
        choices: List<ExportChoice>,
    ): String? {
        calls += "chooseExportFormat"
        return exportChoice(choices)
    }

    override suspend fun confirmImport(preview: ProfileBundlePreview): Boolean {
        calls += "confirmImport"
        lastPreview = preview
        return importAnswer
    }

    override suspend fun confirmDeleteAccount(subscriptionBlocks: Boolean): DeleteAccountChoice {
        calls += "confirmDeleteAccount"
        return deleteChoice
    }

    override suspend fun askDeleteLocalData(): Boolean {
        calls += "askDeleteLocalData"
        return deleteLocalAnswer
    }
}

class FakePickedFile(
    override val name: String,
    private val bytes: ByteArray,
    override val sizeBytes: Long = bytes.size.toLong(),
    private val readError: Exception? = null,
) : PickedFile {
    override suspend fun readBytes(): ByteArray {
        readError?.let { throw it }
        return bytes
    }
}

class FakeHomeFiles : HomeFiles {
    var picked: PickedFile? = null
    val pickCalls = mutableListOf<String>()

    /** The path the fake save dialog returns; a function so tests can place it in a temp dir. */
    var savePath: (String) -> String? = { null }
    val saveCalls = mutableListOf<String>()

    override suspend fun pickFile(title: String, filter: String): PickedFile? {
        pickCalls += title
        return picked
    }

    override suspend fun saveFile(fileName: String, filter: String): String? {
        saveCalls += fileName
        return savePath(fileName)
    }
}

class FakeHomeClipboard : HomeClipboard {
    var text: String? = null
    var busy: Boolean = false

    override fun setText(text: String) {
        if (busy) throw ClipboardBusyException()
        this.text = text
    }
}

class FakeHomeShell : HomeShell {
    var activateCalls: Int = 0
    var closeCalls: Int = 0
    var liveShown: Int = 0
    val liveToasts = mutableListOf<Pair<String, ToastKind>>()
    val openedFiles = mutableListOf<String>()
    val openedFolders = mutableListOf<String>()
    var openFileError: Exception? = null
    var placementValue: HomePlacement = HomePlacement(false, true, 10.0, 20.0, 900.0, 700.0)

    override fun activate() {
        activateCalls++
    }

    override fun close() {
        closeCalls++
    }

    override fun showLiveWindow() {
        liveShown++
    }

    override fun showLiveToast(text: String, kind: ToastKind) {
        liveToasts += text to kind
    }

    override fun openFile(path: String) {
        openFileError?.let { throw it }
        openedFiles += path
    }

    override fun openFolder(path: String) {
        openedFolders += path
    }

    override fun placement(): HomePlacement = placementValue
}

class FakeSettingsPageController : SettingsPageController {
    override val guard: DirtyStateGuard = DirtyStateGuard("settings")
    override val savedListeners: MutableList<() -> Unit> = mutableListOf()
    override val saveFailedListeners: MutableList<() -> Unit> = mutableListOf()
    override val defaultsRestoredListeners: MutableList<(String?) -> Unit> = mutableListOf()

    var saveResult: Boolean = true
    var saveCalls: Int = 0
    var discardCalls: Int = 0
    val shownSections = mutableListOf<String?>()
    var loadCalls: Int = 0
    var relocalizeCalls: Int = 0
    var refreshUpdatesCalls: Int = 0

    init {
        // The real controller wires its own Save/Discard into the guard (P4-G); the fake does the same.
        guard.save = { save() }
        guard.discard = { discard() }
    }

    override fun save(): Boolean {
        saveCalls++
        return saveResult
    }

    override fun discard() {
        discardCalls++
    }

    override fun showSection(section: String?) {
        shownSections += section
    }

    override fun loadFromSettings() {
        loadCalls++
    }

    override fun relocalize() {
        relocalizeCalls++
    }

    override fun refreshUpdates() {
        refreshUpdatesCalls++
    }
}

class FakePreInterviewPageController : PreInterviewPageController {
    override var isRunning: Boolean = false
    override var canStart: Boolean = false
    override val navigateRequestedListeners: MutableList<(String) -> Unit> = mutableListOf()
    override val startRequestedListeners: MutableList<() -> Unit> = mutableListOf()
    override val stateChangedListeners: MutableList<() -> Unit> = mutableListOf()

    var refreshCalls: Int = 0
    var relocalizeCalls: Int = 0
    var shownCalls: Int = 0
    var hiddenCalls: Int = 0
    var detachCalls: Int = 0
    var runAllCalls: Int = 0

    /** Like the real page's Render: every refresh ends by raising StateChanged. */
    override fun refresh() {
        refreshCalls++
        fireStateChanged()
    }

    override fun relocalize() {
        relocalizeCalls++
    }

    override fun onShown() {
        shownCalls++
    }

    override fun onHidden() {
        hiddenCalls++
    }

    override fun detach() {
        detachCalls++
    }

    override suspend fun runAllAsync() {
        runAllCalls++
    }

    override fun requestStart() {
        for (l in startRequestedListeners.toList()) l()
    }

    fun fireStateChanged() {
        for (l in stateChangedListeners.toList()) l()
    }

    fun fireNavigate(route: String) {
        for (l in navigateRequestedListeners.toList()) l(route)
    }
}

/** A picked file whose read fails the way an unreadable document does. */
fun unreadablePickedFile(name: String = "cv.pdf"): FakePickedFile =
    FakePickedFile(name, ByteArray(0), readError = IOException("locked"))
