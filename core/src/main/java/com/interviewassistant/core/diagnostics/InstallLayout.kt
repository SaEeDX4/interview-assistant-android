package com.interviewassistant.core.diagnostics

/**
 * Mirror of `InterviewAssistant.Core/Diagnostics/InstallLayout.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port (its Windows tests live in `UpdatePolicyTests.cs`, ported in P2 with the client suite).
 *
 * Detects an installed (Setup.exe / Velopack) copy from the executable's location, independent of whether
 * automatic updates are enabled. Velopack installs per user as %LocalAppData%\{AppId}\current\{App}.exe next
 * to %LocalAppData%\{AppId}\Update.exe; a portable ZIP runs from any other folder.
 *
 * Adaptation: the paths examined are Windows paths by definition, so directory splitting follows Windows
 * rules (both `\` and `/` are separators) regardless of the host OS — `java.io.File` would treat `\` as a
 * plain character on Android/Linux and change the result.
 */
object InstallLayout {
    fun isInstalled(processPath: String?, fileExists: (String) -> Boolean): Boolean {
        if (processPath.isNullOrBlank()) return false
        val dir = directoryName(processPath) ?: return false
        if (!fileName(dir).equals("current", ignoreCase = true)) return false
        val root = directoryName(dir) ?: return false
        return fileExists(combine(root, "Update.exe"))
    }

    /** `Path.GetDirectoryName` under Windows separator rules; null when there is no directory part. */
    private fun directoryName(path: String): String? {
        val trimmed = path.trimEnd('\\', '/')
        val sep = trimmed.lastIndexOfAny(charArrayOf('\\', '/'))
        if (sep < 0) return null
        if (sep == 0) return trimmed.substring(0, 1)
        // A drive root ("C:\x" → "C:\") keeps its trailing separator, like Path.GetDirectoryName.
        if (sep == 2 && trimmed[1] == ':') return trimmed.substring(0, 3)
        return trimmed.substring(0, sep)
    }

    /** `Path.GetFileName` under Windows separator rules. */
    private fun fileName(path: String): String {
        val trimmed = path.trimEnd('\\', '/')
        val sep = trimmed.lastIndexOfAny(charArrayOf('\\', '/'))
        return if (sep < 0) trimmed else trimmed.substring(sep + 1)
    }

    /** `Path.Combine` under Windows separator rules. */
    private fun combine(a: String, b: String): String =
        if (a.endsWith('\\') || a.endsWith('/')) a + b else a + "\\" + b
}
