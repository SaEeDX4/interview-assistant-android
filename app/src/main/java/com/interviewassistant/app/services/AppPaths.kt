package com.interviewassistant.app.services

import android.content.Context
import android.os.Environment
import java.io.File

/** Mirror of `InterviewAssistant.App/Services/AppPaths.cs`. */

/**
 * The app's folders. The C# reads the Windows special folders (`%APPDATA%`, `%LOCALAPPDATA%`,
 * My Documents); the Android counterpart bases are installed once at startup by [install]
 * (P4-PORT-NOTES DC-75): roaming/local base = the app-private files dir, documents base = the
 * app-specific Documents dir. Every getter creates its directory, as in C#.
 */
object AppPaths {
    /** Self-test isolation: user data (settings, workspace, tokens, reports) goes to a throwaway folder. Logs stay put. */
    var isolatedRoot: String? = null

    // ---- platform seam: the Environment.GetFolderPath counterparts, installed by the composition root.
    @Volatile private var roamingBase: String? = null
    @Volatile private var localBase: String? = null
    @Volatile private var documentsBase: String? = null

    /** Installs the three base folders (the Context-free form; unit tests install temp directories). */
    fun installBaseDirs(roamingBase: String, localBase: String, documentsBase: String) {
        this.roamingBase = roamingBase
        this.localBase = localBase
        this.documentsBase = documentsBase
    }

    /** Installs the base folders from the Android context (called once from `Application.onCreate`). */
    fun install(context: Context) {
        val files = context.filesDir.path
        installBaseDirs(
            roamingBase = files,
            localBase = files,
            documentsBase = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.path ?: files,
        )
    }

    /** Before [install] (early startup, unit tests without the seam), data lands under a temp-dir app folder. */
    private fun baseOrDefault(base: String?): String =
        base ?: File(System.getProperty("java.io.tmpdir"), "InterviewAssistant-base").path

    private fun roamingFolder(): String = File(baseOrDefault(roamingBase), "InterviewAssistant").path
    private fun localFolder(): String = File(baseOrDefault(localBase), "InterviewAssistant").path

    val roaming: String
        get() = ensure(isolatedRoot?.let { File(it, "roaming").path } ?: roamingFolder())

    val local: String
        get() = ensure(isolatedRoot?.let { File(it, "local").path } ?: localFolder())

    /** Logs are never isolated (the C# reads them from the real local folder even in self-tests). */
    val logs: String
        get() = ensure(File(localFolder(), "logs").path)

    val sessions: String
        get() = ensure(
            isolatedRoot?.let { File(it, "sessions").path }
                ?: File(baseOrDefault(documentsBase), "InterviewAssistant Sessions").path,
        )

    /**
     * The `AppContext.BaseDirectory` counterpart: the (never isolated) local app folder. The
     * platform layer extracts the bundled `knowledge/` assets here ([extractBundledKnowledge]),
     * which is where the shipped-assets search starts — the Android form of "next to the EXE".
     */
    val baseDirectory: String
        get() = localFolder()

    private fun ensure(p: String): String {
        File(p).mkdirs()
        return p
    }
}
