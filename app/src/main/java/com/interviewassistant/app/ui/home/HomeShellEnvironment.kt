package com.interviewassistant.app.ui.home

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.interviewassistant.app.ui.components.AppDialog
import com.interviewassistant.app.ui.components.DialogController
import com.interviewassistant.app.ui.components.IaDialogs
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.LeaveDecision
import com.interviewassistant.app.viewmodels.ClipboardBusyException
import com.interviewassistant.app.viewmodels.DeleteAccountChoice
import com.interviewassistant.app.viewmodels.DialogTone
import com.interviewassistant.app.viewmodels.ExportChoice
import com.interviewassistant.app.viewmodels.FactEditResult
import com.interviewassistant.app.viewmodels.FactRow
import com.interviewassistant.app.viewmodels.HomeClipboard
import com.interviewassistant.app.viewmodels.HomeDialogs
import com.interviewassistant.app.viewmodels.HomeFiles
import com.interviewassistant.app.viewmodels.HomePlacement
import com.interviewassistant.app.viewmodels.HomeShell
import com.interviewassistant.app.viewmodels.PickedFile
import com.interviewassistant.core.persistence.ProfileBundlePreview
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The production implementations of the Home view model's platform seams (Package P4-E): the
 * dialogs over the shell's dialog host, the clipboard, the system file pickers, and the window
 * shell. MainActivity creates one environment and hands its pieces to the HomeViewModel and the
 * HomeScreen. Nothing here invents behavior: each seam does on Android what the C# does with the
 * WPF dialog / clipboard / shell APIs, with the platform differences registered as DCs.
 */

// ---------------------------------------------------------------------------
// Dialogs (HomeDialogs over the P4-D dialog host)
// ---------------------------------------------------------------------------

/** The shell's [HomeDialogs]: every call is one of the P4-D dialog builders over the one controller. */
class ShellHomeDialogs(private val controller: DialogController) : HomeDialogs {

    override val isOpen: Boolean get() = controller.isOpen

    override suspend fun alert(message: String, tone: DialogTone) {
        AppDialog.alert(controller, message, tone)
    }

    override suspend fun confirm(message: String, confirmText: String, destructive: Boolean, title: String?): Boolean =
        AppDialog.confirm(controller, message, confirmText, destructive, title)

    override suspend fun askUnsaved(message: String): LeaveDecision =
        AppDialog.askUnsaved(controller, message)

    override suspend fun editFact(row: FactRow?): FactEditResult? =
        FactEditor.show(controller, row)

    override suspend fun currentPassword(reason: String, email: String, verify: suspend (String) -> String?): Boolean =
        IaDialogs.currentPassword(controller, reason, email, verify)

    override suspend fun newPassword(): String? =
        IaDialogs.newPassword(controller)

    override suspend fun chooseExportFormat(title: String, message: String, choices: List<ExportChoice>): String? =
        IaDialogs.chooseExportFormat(controller, title, message, choices)

    override suspend fun confirmImport(preview: ProfileBundlePreview): Boolean =
        IaDialogs.confirmImport(controller, preview)

    override suspend fun confirmDeleteAccount(subscriptionBlocks: Boolean): DeleteAccountChoice =
        IaDialogs.deleteAccount(controller, subscriptionBlocks)

    override suspend fun askDeleteLocalData(): Boolean =
        IaDialogs.askDeleteLocalData(controller)
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

/** The platform clipboard (the C#'s `Clipboard.SetText`). */
class ShellHomeClipboard(context: Context) : HomeClipboard {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    override fun setText(text: String) {
        try {
            clipboard?.setPrimaryClip(ClipData.newPlainText("Interview Assistant", text))
                ?: throw ClipboardBusyException()
        } catch (e: ClipboardBusyException) {
            throw e
        } catch (e: Exception) {
            // The seam's only failure channel is the busy exception (the C#'s ExternalException);
            // any platform failure to take the clip is reported through it.
            throw ClipboardBusyException()
        }
    }
}

// ---------------------------------------------------------------------------
// File pickers (the C#'s OpenFileDialog / SaveFileDialog)
// ---------------------------------------------------------------------------

/**
 * Maps the WPF file-dialog filter text the view models pass ("Label (*.pdf;*.docx)|*.pdf;*.docx")
 * to Android MIME types. An extension with no known MIME type (e.g. the backup bundle's own
 * extension) widens the picker to all types rather than hiding the user's files.
 */
object WpfFilter {
    private val mimeByExtension = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "json" to "application/json",
        "zip" to "application/zip",
        "csv" to "text/csv",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
    )

    private val extensionPattern = Regex("""\*\.([A-Za-z0-9]+)""")

    /** The MIME types a WPF filter string allows; the all-types pattern when empty or unmapped. */
    fun mimeTypesFor(filter: String): Array<String> {
        val extensions = extensionPattern.findAll(filter)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
        if (extensions.isEmpty()) return arrayOf("*/*")
        val mimes = mutableListOf<String>()
        for (extension in extensions) {
            mimes += mimeByExtension[extension] ?: return arrayOf("*/*")
        }
        return mimes.distinct().toTypedArray()
    }

    /** The MIME type for a save-file name, by its extension; the all-types pattern when unknown. */
    fun mimeTypeFor(fileName: String): String =
        mimeByExtension[fileName.substringAfterLast('.', "").lowercase()] ?: "*/*"
}

/**
 * Bridges the activity-result file pickers to the suspending seam calls. MainActivity registers
 * the launchers and forwards their results here; a second request cancels the first (the C#'s
 * dialogs are modal — only one picker is ever in flight).
 */
class HomeFileBridge {
    /** Set by the activity: launches the open-document picker with the given MIME types. */
    var launchPick: ((Array<String>) -> Unit)? = null

    /** Set by the activity: launches the create-document picker for (file name, MIME type). */
    var launchSave: ((String, String) -> Unit)? = null

    private var pickContinuation: CancellableContinuation<Uri?>? = null
    private var saveContinuation: CancellableContinuation<Uri?>? = null

    suspend fun pick(mimeTypes: Array<String>): Uri? = suspendCancellableCoroutine { continuation ->
        pickContinuation?.cancel()
        pickContinuation = continuation
        continuation.invokeOnCancellation { if (pickContinuation === continuation) pickContinuation = null }
        val launch = launchPick
        if (launch == null) {
            pickContinuation = null
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        launch(mimeTypes)
    }

    suspend fun save(fileName: String, mimeType: String): Uri? = suspendCancellableCoroutine { continuation ->
        saveContinuation?.cancel()
        saveContinuation = continuation
        continuation.invokeOnCancellation { if (saveContinuation === continuation) saveContinuation = null }
        val launch = launchSave
        if (launch == null) {
            saveContinuation = null
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        launch(fileName, mimeType)
    }

    fun deliverPick(uri: Uri?) {
        val continuation = pickContinuation
        pickContinuation = null
        if (continuation != null && continuation.isActive) continuation.resume(uri)
    }

    fun deliverSave(uri: Uri?) {
        val continuation = saveContinuation
        saveContinuation = null
        if (continuation != null && continuation.isActive) continuation.resume(uri)
    }
}

/** One picked document: name and size are read from the provider before the content (as the C# reads FileInfo first). */
class SafPickedFile(context: Context, private val uri: Uri) : PickedFile {
    private val resolver = context.contentResolver

    override val name: String
    override val sizeBytes: Long

    init {
        var displayName: String? = null
        var size: Long = -1
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (ignored: Exception) {
            // A provider that cannot answer the query still yields a readable file below.
        }
        name = displayName ?: uri.lastPathSegment ?: "file"
        sizeBytes = size
    }

    override suspend fun readBytes(): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the picked file.")
    }
}

/**
 * Watches the staged export files (DC-138): the view models write to a real path after the save
 * picker returns, but Android's picker hands back a content Uri, not a path. [stage] therefore
 * returns a staging path in the app's private exports folder and, once the written file's size
 * has settled (the writers are one-shot: a direct write, or a temp file renamed over the target),
 * copies it to the user's picked destination and removes the staging copy. A file that is never
 * written is dropped when its wait times out.
 */
class StagedExports(
    private val directory: File,
    private val scope: CoroutineScope,
    private val copy: (File, String) -> Unit,
    private val pollMs: Long = 200,
    private val settlePolls: Int = 2,
    private val timeoutMs: Long = 120_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(val file: File, val destination: String, val stagedAtMs: Long)

    private val pending = mutableListOf<Pending>()
    private var watcherActive = false

    /** Stages one export: returns the path the caller writes to; the copy follows when it settles. */
    fun stage(fileName: String, destination: String): String {
        val file = File(directory, fileName)
        synchronized(this) {
            directory.mkdirs()
            pending.removeAll { it.file == file }
            if (file.exists()) file.delete()
            pending += Pending(file, destination, nowMs())
            if (!watcherActive) {
                watcherActive = true
                scope.launch { watch() }
            }
        }
        return file.absolutePath
    }

    private suspend fun watch() {
        val sizes = mutableMapOf<File, Pair<Long, Int>>()
        while (true) {
            // The exit decision and stage()'s add share this lock, so an entry can never be
            // stranded by a watcher that has just decided to stop.
            val snapshot = synchronized(this) {
                if (pending.isEmpty()) {
                    watcherActive = false
                    return
                }
                pending.toList()
            }
            delay(pollMs)
            val now = nowMs()
            for (entry in snapshot) {
                val file = entry.file
                if (now - entry.stagedAtMs > timeoutMs) {
                    finish(entry)
                    sizes.remove(file)
                    continue
                }
                if (!file.exists()) {
                    sizes.remove(file)
                    continue
                }
                val length = file.length()
                val (lastLength, stableCount) = sizes[file] ?: (Long.MIN_VALUE to 0)
                val stable = if (length == lastLength) stableCount + 1 else 0
                sizes[file] = length to stable
                if (stable >= settlePolls) {
                    try {
                        copy(file, entry.destination)
                    } catch (ignored: Exception) {
                        // The destination could not be written (provider gone); the staging file
                        // is removed with the entry — the export's own success toast has already
                        // been decided by the view model, exactly as a failed late flush would be.
                    }
                    finish(entry)
                    sizes.remove(file)
                }
            }
        }
    }

    @Synchronized
    private fun finish(entry: Pending) {
        pending.remove(entry)
        if (entry.file.exists()) entry.file.delete()
    }
}

/** The shell's [HomeFiles]: Storage Access Framework pickers behind the WPF-shaped seam. */
class ShellHomeFiles(
    context: Context,
    private val bridge: HomeFileBridge,
    scope: CoroutineScope,
) : HomeFiles {
    private val appContext = context.applicationContext

    private val staged = StagedExports(
        directory = File(appContext.cacheDir, "home-exports"),
        scope = scope,
        copy = { staging, destination -> copyToDestination(staging, destination) },
    )

    override suspend fun pickFile(title: String, filter: String): PickedFile? {
        val uri = bridge.pick(WpfFilter.mimeTypesFor(filter)) ?: return null
        return SafPickedFile(appContext, uri)
    }

    override suspend fun saveFile(fileName: String, filter: String): String? {
        val uri = bridge.save(fileName, WpfFilter.mimeTypeFor(fileName)) ?: return null
        return staged.stage(fileName, uri.toString())
    }

    private fun copyToDestination(staging: File, destination: String) {
        val uri = Uri.parse(destination)
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            staging.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Could not open the save destination.")
    }
}

// ---------------------------------------------------------------------------
// Window shell
// ---------------------------------------------------------------------------

/**
 * The shell's [HomeShell]: the activity is the window (DC-137). [onCloseHome] and [onShowLive]
 * are the activity's destination callbacks (DC-143); the Live window's toasts are shown on Home's
 * toast surface until the Live destination has its own host (P4-G).
 */
class ShellHomeShell(
    private val context: Context,
    private val toasts: ToastController,
    private val onCloseHome: () -> Unit,
    private val onShowLive: () -> Unit,
) : HomeShell {

    override fun activate() {
        val manager = context.getSystemService(ActivityManager::class.java)
        try {
            manager?.appTasks?.firstOrNull()?.moveToFront()
        } catch (ignored: Exception) {
            // Bringing the task forward is best-effort; the activity is already the visible window
            // in the single-activity design whenever Home is in front.
        }
    }

    override fun close() = onCloseHome()

    override fun showLiveWindow() = onShowLive()

    override fun showLiveToast(text: String, kind: ToastKind) {
        toasts.show(text, kind, detail = null, nowMs = System.currentTimeMillis())
    }

    override fun openFile(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No viewer installed: the C#'s Process.Start failure family (IOException) is what the
            // view model turns into its error toast.
            throw IOException("No app can open this file.", e)
        }
    }

    override fun openFolder(path: String) {
        val folder = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", folder)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "resource/folder")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw IOException("No app can open this folder.", e)
        }
    }

    override fun placement(): HomePlacement {
        // DC-137: an Android activity has no window bounds to persist; the placement the settings
        // file carries is the current content size, always a normal (non-maximized) window.
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels / metrics.density.toDouble()
        val height = metrics.heightPixels / metrics.density.toDouble()
        return HomePlacement(isMaximized = false, isNormal = true, left = 0.0, top = 0.0, width = width, height = height)
    }
}
