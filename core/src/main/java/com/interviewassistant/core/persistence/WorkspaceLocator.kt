package com.interviewassistant.core.persistence

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Mirror of `InterviewAssistant.Core/Persistence/WorkspaceLocator.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * One local workspace per signed-in account, so another account on the same Windows user can never see this
 * account's profiles, résumé facts, interviews, prepared answers or reports:
 *   {base}/accounts/{hash(userId)}/…   signed-in accounts (hash: no e-mail or id in the path)
 *   {base}/local/…                     developer builds without an account
 * The pre-RC2 shared workspace ({base}/profiles, …) is adopted once, by the first account that signs in after the
 * upgrade, and a marker records which account it went to.
 */
object WorkspaceLocator {
    private val folders = arrayOf("profiles", "targets", "packs", "sessions")
    const val LegacyMarker = "legacy-adopted-by.txt"

    fun accountKey(userId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("ia-workspace:" + userId.trim().lowercase()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 24)

    fun rootFor(baseDir: String, userId: String?): String =
        if (userId.isNullOrBlank()) File(baseDir, "local").path
        else File(File(baseDir, "accounts"), accountKey(userId)).path

    fun hasLegacyData(baseDir: String): Boolean =
        folders.any { f ->
            val dir = File(baseDir, f)
            dir.isDirectory && (dir.listFiles { file -> file.name.endsWith(".bin") }?.isNotEmpty() == true)
        }

    /**
     * Moves the legacy shared workspace into [userId]'s workspace if nobody adopted it yet and that
     * workspace is still empty. Returns true when data was moved. Never overwrites existing files.
     */
    fun adoptLegacy(baseDir: String, userId: String): Boolean {
        val accounts = File(baseDir, "accounts")
        val marker = File(accounts, LegacyMarker)
        if (marker.exists() || !hasLegacyData(baseDir)) return false
        val root = rootFor(baseDir, userId)
        if (folders.any { f ->
                val dir = File(root, f)
                dir.isDirectory && (dir.listFiles { file -> file.name.endsWith(".bin") }?.isNotEmpty() == true)
            }
        ) return false
        accounts.mkdirs()
        for (f in folders) {
            val from = File(baseDir, f)
            if (!from.isDirectory) continue
            val to = File(root, f)
            to.mkdirs()
            for (file in from.listFiles { file -> file.name.endsWith(".bin") } ?: emptyArray()) {
                val dest = File(to, file.name)
                if (!dest.exists()) Files.move(file.toPath(), dest.toPath())
            }
        }
        marker.writeText(accountKey(userId), Charsets.UTF_8)
        return true
    }
}
