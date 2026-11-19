package com.interviewassistant.app.services

import android.content.Context
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.IDataProtector
import com.interviewassistant.core.persistence.WorkspaceStore
import com.interviewassistant.core.preparation.PreparationAssets
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Mirror of `InterviewAssistant.App/Services/Workspace.cs`. */

/**
 * The signed-in account's local workspace (profiles, interviews, prepared answers, reports —
 * encrypted at rest) plus the shared preparation assets shipped with the app. One workspace per
 * account (`WorkspaceLocator`).
 *
 * The C# file also defines `DpapiProtector` (the DPAPI `IDataProtector`); its Android counterpart
 * already exists in the platform layer — `KeystoreDataProtector` (P2) — and is not duplicated here.
 * The workspace files themselves are encrypted by whichever protector the composition root passes in.
 */
class Workspace(root: String, protector: IDataProtector) {
    val store: WorkspaceStore = WorkspaceStore(root, protector)
    val assets: PreparationAssets
    val knowledgeDir: String?

    /** Account id hash (or "local"/"selftest") this workspace belongs to. */
    var owner: String = "local"

    init {
        // Shipped generic assets: knowledge/generic_question_library.json + playbooks (no candidate data).
        knowledgeDir = KnowledgeBase.findDirectory(AppPaths.baseDirectory, "knowledge", PreparationAssets.LibraryFileName)
        assets = knowledgeDir?.let { PreparationAssets.load(it) } ?: PreparationAssets()
        if (assets.library.isEmpty()) {
            AppLog.warn("Generic preparation library not found next to the app — preparation will produce fewer questions.")
        }
    }

    /**
     * Plain HTML copies of this account's reports, created only so a browser can display one. They live in the
     * account's own folder under the local app data (not in Documents, not shared between accounts, never synced) and are
     * removed when the account's workspace is closed or the report is deleted. The authoritative copy stays encrypted.
     */
    val viewerDir: String get() = File(File(AppPaths.local, "view"), owner).path

    fun writeViewerCopy(reportId: String, html: String): String {
        File(viewerDir).mkdirs()
        val path = File(viewerDir, "report-$reportId.html")
        path.writeText(html, Charsets.UTF_8)
        return path.path
    }

    fun deleteViewerCopy(reportId: String) {
        try {
            Files.deleteIfExists(File(viewerDir, "report-$reportId.html").toPath())
        } catch (e: IOException) {
        } catch (e: SecurityException) {
        }
    }

    fun clearViewerCopies() {
        try {
            val dir = File(viewerDir)
            if (dir.exists()) dir.deleteRecursively()
        } catch (e: SecurityException) {
        }
    }

    /** Knowledge for the selected profile/target. Falls back to generic (no candidate claims) when nothing is prepared. */
    fun loadActive(profileId: String?, targetId: String?): Pair<KnowledgeBase, String> {
        if (profileId != null && targetId != null) {
            val profile = store.getProfile(profileId)
            val target = store.getTarget(targetId)
            val pack = if (target != null && target.profileId == profileId) store.getPack(targetId) else null
            if (profile != null && target != null && pack != null) {
                val packed = pack.toKnowledgeBase()
                // Personal answers and boundaries are always the CURRENT confirmed ones (editing them never needs re-preparation).
                // The C# replaces the two lists in place; :core's CandidateProfile holds immutable lists, so the
                // knowledge base is rebuilt with the refreshed profile — the returned values are the same
                // (P4-PORT-NOTES DC-80).
                val p = packed.profile
                val refreshed = CandidateProfile(
                    name = p.name, headline = p.headline, summary = p.summary,
                    coreCompetencies = p.coreCompetencies, experience = p.experience,
                    verifiedNumbers = p.verifiedNumbers, verifiedEntities = p.verifiedEntities,
                    knownGaps = p.knownGaps, education = p.education, languages = p.languages,
                    preferredName = p.preferredName,
                    personal = ProfileService.personalEntries(profile),
                    boundaries = ProfileService.boundaryEntries(profile),
                )
                val kb = KnowledgeBase(
                    profile = refreshed, stories = packed.stories, questions = packed.questions,
                    snippets = packed.snippets, systemPromptTemplate = packed.systemPromptTemplate,
                    context = packed.context, directory = packed.directory,
                )
                return kb to "${if (profile.preferredName.isNotEmpty()) profile.preferredName else profile.name} · ${target.displayName}"
            }
        }
        return KnowledgeBase.empty(assets.playbooks) to Loc.t("no_interview_prepared")
    }

    /** The interview selected for the Live Assistant in this workspace. */
    fun loadActive(): Pair<KnowledgeBase, String> {
        val (p, t) = store.getActive()
        return loadActive(p, t)
    }

    companion object {
        /** Loads a fixture pack directory (test/sample data is NOT shipped in the product; self-tests pass the path). */
        fun loadSample(dir: String?): Pair<KnowledgeBase, String>? {
            if (dir == null || !File(dir, "question_bank.json").exists()) return null
            val kb = KnowledgeBase.load(dir)
            return kb to "Sample · ${kb.context.candidateName} · ${kb.context.companyName}"
        }
    }
}

// ---------------------------------------------------------------------------
// Platform glue for the shipped assets. The C# finds the knowledge folder "next to the EXE";
// Android packages it as assets (in :core, merged into the app), which are not files — the
// composition root extracts them once into AppPaths.baseDirectory, where the mirrored
// file-based search above finds them. A stamp (the library asset's SHA-256) re-extracts
// when an app update changes the bundled assets.
// ---------------------------------------------------------------------------

/** Extracts the bundled `knowledge/` assets into [AppPaths.baseDirectory]; returns that directory. */
fun extractBundledKnowledge(context: Context): String {
    val base = AppPaths.baseDirectory
    val dest = File(base, "knowledge")
    try {
        val assets = context.assets
        val libraryBytes = assets.open("knowledge/${PreparationAssets.LibraryFileName}").use { it.readBytes() }
        val stamp = MessageDigest.getInstance("SHA-256").digest(libraryBytes)
            .joinToString("") { "%02x".format(it) }
        val stampFile = File(dest, ".stamp")
        val current = if (File(dest, PreparationAssets.LibraryFileName).isFile) {
            try {
                stampFile.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                null
            }
        } else {
            null
        }
        if (current != stamp) {
            copyAssetDir(assets, "knowledge", dest)
            stampFile.writeText(stamp, Charsets.UTF_8)
        }
    } catch (e: IOException) {
        AppLog.warn("Bundled knowledge assets could not be extracted: " + AppLog.describe(e))
    } catch (e: SecurityException) {
        AppLog.warn("Bundled knowledge assets could not be extracted: " + AppLog.describe(e))
    }
    return base
}

private fun copyAssetDir(assets: android.content.res.AssetManager, assetPath: String, destDir: File) {
    val entries = assets.list(assetPath) ?: return
    destDir.mkdirs()
    for (entry in entries) {
        val childPath = "$assetPath/$entry"
        val children = assets.list(childPath)
        if (children != null && children.isNotEmpty()) {
            copyAssetDir(assets, childPath, File(destDir, entry))
        } else {
            assets.open(childPath).use { input ->
                File(destDir, entry).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
