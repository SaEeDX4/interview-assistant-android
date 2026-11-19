package com.interviewassistant.core.persistence

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.CoreDateTimeSerializer
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.newGuidN
import com.interviewassistant.core.ingestion.PromptInjectionGuard
import com.interviewassistant.core.ingestion.UploadValidator
import com.interviewassistant.core.ingestion.dotNetRegex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mirror of `InterviewAssistant.Core/Persistence/ProfileBundle.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port, with the platform adaptations registered in P3-PORT-NOTES (DC-11 family):
 * ZIP access is [ZipInputStream]/[ZipOutputStream] over byte arrays (entry sizes are counted from the
 * decompressed bytes under the same limits, because ZIP local headers may omit them); JSON trees are
 * immutable kotlinx elements, so the import guard rebuilds instead of mutating; the C#'s reflection-based
 * `RequireValues` (NullabilityInfoContext) is re-implemented over kotlinx serial descriptors, which carry
 * the same nullability information; duplicate JSON property names and the 64-deep limit are enforced by
 * [StrictJson], as System.Text.Json's checked options do in the C#.
 */

/** Why a profile backup was rejected (the desktop maps each to a plain-language message). */
enum class ProfileBundleError {
    /** Not a ZIP, or no iaprofile manifest. */
    NotABundle,

    /** Created by a newer app (manifest version above [ProfileBundle.FormatVersion]). */
    NewerVersion,

    /** An entry does not match the SHA-256 or size recorded in the manifest, or entries were added/removed. */
    Tampered,

    /** Over a size or entry-count limit (also zip bombs). */
    TooLarge,

    /** An entry name tries to leave the bundle (path traversal, absolute path, drive letter, backslash). */
    UnsafePath,

    /** Unexpected entries or JSON that is not a valid profile, interview, prepared pack or report. */
    Invalid,
}

class ProfileBundleException(val error: ProfileBundleError, detail: String) : Exception("${error.name}: $detail")

/**
 * Limits for reading an untrusted bundle. The defaults are far above any real profile (a large one is a few MB),
 * and keep the memory an import can use bounded.
 */
@Serializable
data class ProfileBundleLimits(
    @SerialName("MaxFileBytes") val maxFileBytes: Long = 64L shl 20,
    @SerialName("MaxEntryBytes") val maxEntryBytes: Long = 32L shl 20,
    @SerialName("MaxTotalBytes") val maxTotalBytes: Long = 64L shl 20,
    @SerialName("MaxEntries") val maxEntries: Int = 4000,
) {
    companion object {
        val Default = ProfileBundleLimits()
    }
}

@Serializable
class ProfileBundleCounts(
    @SerialName("facts") var facts: Int = 0,
    @SerialName("interviews") var interviews: Int = 0,
    @SerialName("packs") var packs: Int = 0,
    @SerialName("sessions") var sessions: Int = 0,
    @SerialName("reports") var reports: Int = 0,
)

@Serializable
class ProfileBundleEntry(
    @SerialName("path") var path: String = "",
    @SerialName("size") var size: Long = 0,
    @SerialName("sha256") var sha256: String = "",
)

@Serializable
class ProfileBundleManifest(
    @SerialName("format") var format: String = ProfileBundle.FormatName,
    @SerialName("version") var version: Int = ProfileBundle.FormatVersion,
    @SerialName("appVersion") var appVersion: String = "",
    @SerialName("createdUtc") @Serializable(with = CoreDateTimeSerializer::class) var createdUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("profileName") var profileName: String = "",
    @SerialName("counts") var counts: ProfileBundleCounts = ProfileBundleCounts(),
    @SerialName("entries") var entries: MutableList<ProfileBundleEntry?> = mutableListOf(),
)

/** What the import preview shows (counts come from the validated content, not from the manifest's claims). */
data class ProfileBundlePreview(
    val profileName: String,
    val facts: Int,
    val interviews: Int,
    val packs: Int,
    val reports: Int,
    val createdUtc: OffsetDateTime,
    val appVersion: String,
)

/** An imported profile, and how many instruction-like lines (prompt injection) were removed from its text. */
data class ProfileBundleImport(val profile: CandidateProfileRecord, val removedInstructionLines: Int)

/** A validated bundle, ready to preview and import. */
class ProfileBundleContent(
    val manifest: ProfileBundleManifest,
    val profile: JsonObject,
    val targets: List<JsonObject>,
    val packs: List<JsonObject>,
    val sessions: List<JsonObject>,
    val preview: ProfileBundlePreview,
)

/**
 * ".iaprofile" backup & transfer bundle: a ZIP holding manifest.json (format "iaprofile", version 1, app version,
 * creation time, counts and the SHA-256 of every other entry), profile.json, targets/*.json, packs/*.json and
 * sessions/*.json. Unlike the DPAPI-encrypted workspace (readable only by the same Windows user on the same PC), the
 * bundle can be restored on another PC. Secrets are filtered on write. Reading treats the file as untrusted input:
 * size and entry limits, a strict entry-name allow-list (no traversal), hash verification and JSON validation, and
 * importing always creates a NEW profile with new ids (nothing on the PC is overwritten).
 */
object ProfileBundle {
    const val FormatName = "iaprofile"
    const val FormatVersion = 1
    const val FileExtension = ".iaprofile"

    private const val TypedMaxDepth = 64

    private val entryName: Regex = dotNetRegex("^(?:manifest\\.json|profile\\.json|(?:targets|packs|sessions)/[A-Za-z0-9\\-]{1,64}\\.json)\\z")
    private val safeId: Regex = dotNetRegex("^[A-Za-z0-9\\-]{1,64}\\z")

    // ------------------------------------------------------------------ write

    /** Writes the profile with its interviews, prepared packs and sessions (reports) to [output]. */
    fun write(store: WorkspaceStore, profileId: String, output: OutputStream, appVersion: String, nowUtc: OffsetDateTime? = null): ProfileBundleManifest {
        val profile = store.getProfile(profileId) ?: throw NoSuchElementException("profile")
        val targets = store.targets(profileId)
        val sessions = store.sessions().filter { it.profileId == profileId }
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries.add("profile.json" to json(profile, CandidateProfileRecord.serializer()))
        var packs = 0
        for (t in targets) {
            entries.add("targets/${t.id}.json" to json(t, InterviewTarget.serializer()))
            val pack = store.getPack(t.id)
            if (pack != null) {
                entries.add("packs/${t.id}.json" to json(pack, PreparedPack.serializer()))
                packs++
            }
        }
        for (s in sessions) entries.add("sessions/${s.id}.json" to json(s, StoredSession.serializer()))

        val manifest = ProfileBundleManifest(
            appVersion = appVersion, createdUtc = nowUtc ?: OffsetDateTime.now(ZoneOffset.UTC), profileName = profile.name,
            counts = ProfileBundleCounts(
                facts = profile.facts.count { it.isReviewable }, interviews = targets.size, packs = packs,
                sessions = sessions.size, reports = sessions.count { it.reportJson.isNotEmpty() },
            ),
        )
        manifest.entries.addAll(entries.map { (path, bytes) ->
            ProfileBundleEntry(path = path, size = bytes.size.toLong(), sha256 = hash(bytes))
        })
        // ZipArchive(leaveOpen: true): finish the ZIP on the caller's stream without closing it.
        val zip = ZipOutputStream(output)
        fun add(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        add("manifest.json", CoreJson.instance.encodeToString(ProfileBundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8).let { indent(it) })
        for ((path, bytes) in entries) add(path, bytes)
        zip.finish()
        zip.flush()
        return manifest
    }

    /** The manifest is written with the C#'s indented options (the entries go through [SecretFilter.toJson]). */
    private fun indent(compactUtf8: ByteArray): ByteArray {
        val el = StrictJson.parse(String(compactUtf8, Charsets.UTF_8), StrictJson.ParseMaxDepth)
        return JsonIndented.encodeToString(JsonElement.serializer(), el).toByteArray(Charsets.UTF_8)
    }

    // Secrets are removed by property name, and values only when they are unmistakable credentials (an API key or a
    // token pasted into notes): the backup restores the user's own prose exactly as written ("Secret: active clearance"
    // stays). The readable and raw exports also redact credentials written as a sentence.
    private fun <T> json(value: T, serializer: KSerializer<T>): ByteArray =
        SecretFilter.toJson(value, serializer, keepProse = true).toByteArray(Charsets.UTF_8)

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ read (untrusted)

    /**
     * Reads and fully validates a bundle. Throws [ProfileBundleException] for anything not acceptable.
     *
     * Any other failure while validating untrusted content (a null where a value is required, a duplicate JSON
     * property, a malformed value) is reported as [ProfileBundleError.Invalid] too: nothing else escapes.
     */
    fun read(input: InputStream, limits: ProfileBundleLimits? = null): ProfileBundleContent {
        try {
            return readUnchecked(input, limits ?: ProfileBundleLimits.Default)
        } catch (ex: ProfileBundleException) {
            throw ex
        } catch (ex: InvalidDataException) {
            throw ProfileBundleException(ProfileBundleError.NotABundle, ex.javaClass.simpleName)
        } catch (ex: Exception) {
            if (ex is NullPointerException || ex is IllegalArgumentException || ex is IllegalStateException ||
                ex is NoSuchElementException || ex is SerializationException || ex is IndexOutOfBoundsException ||
                ex is ArithmeticException
            ) throw ProfileBundleException(ProfileBundleError.Invalid, ex.javaClass.simpleName)
            throw ex
        }
    }

    private fun readUnchecked(input: InputStream, limits: ProfileBundleLimits): ProfileBundleContent {
        val buffer = readBounded(input, limits.maxFileBytes) { ProfileBundleException(ProfileBundleError.TooLarge, "file") }
        // The central directory's entry count is checked before it is parsed (ZipArchive would load every entry first).
        val declared = declaredEntryCount(buffer)
        if (declared != null && declared > limits.maxEntries) throw ProfileBundleException(ProfileBundleError.TooLarge, "entries")

        val files = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(buffer)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (isUnsafe(name)) throw ProfileBundleException(ProfileBundleError.UnsafePath, "entry name")
                    if (!entryName.containsMatchIn(name)) throw ProfileBundleException(ProfileBundleError.Invalid, "unexpected entry")
                    if (!seen.add(name.lowercase())) throw ProfileBundleException(ProfileBundleError.Invalid, "duplicate entry")
                    val bytes: ByteArray
                    try {
                        bytes = readBounded(zip, minOf(limits.maxEntryBytes, limits.maxTotalBytes - total)) {
                            ProfileBundleException(ProfileBundleError.TooLarge, "entry content")
                        }
                    } catch (ex: ZipException) {
                        throw InvalidDataException(ex.javaClass.simpleName, ex)
                    } catch (ex: EOFException) {
                        throw InvalidDataException(ex.javaClass.simpleName, ex)
                    }
                    total += bytes.size
                    files[name] = bytes
                    if (files.size > limits.maxEntries) throw ProfileBundleException(ProfileBundleError.TooLarge, "entries")
                }
            }
        } catch (ex: ZipException) {
            throw InvalidDataException(ex.javaClass.simpleName, ex)
        } catch (ex: EOFException) {
            throw InvalidDataException(ex.javaClass.simpleName, ex)
        }

        val manifestBytes = files["manifest.json"] ?: throw ProfileBundleException(ProfileBundleError.NotABundle, "no manifest")
        val manifest = parseManifest(manifestBytes)

        // The manifest lists exactly the other entries, each with its size and SHA-256.
        val listed = LinkedHashMap<String, ProfileBundleEntry>()
        for (m in manifest.entries) {
            if (m == null) throw ProfileBundleException(ProfileBundleError.Invalid, "manifest entry")
            else if (m.path == "manifest.json" || !entryName.containsMatchIn(m.path) || listed.put(m.path, m) != null)
                throw ProfileBundleException(ProfileBundleError.Tampered, "manifest entries")
        }
        if (listed.size != files.size - 1) throw ProfileBundleException(ProfileBundleError.Tampered, "entry set")
        for ((name, bytes) in files) {
            if (name == "manifest.json") continue
            val m = listed[name] ?: throw ProfileBundleException(ProfileBundleError.Tampered, "unlisted entry")
            if (m.size != bytes.size.toLong() || !m.sha256.equals(hash(bytes), ignoreCase = true))
                throw ProfileBundleException(ProfileBundleError.Tampered, "hash")
        }

        // ---- JSON sanity and references
        val profileBytes = files["profile.json"] ?: throw ProfileBundleException(ProfileBundleError.Invalid, "no profile")
        val profileNode = parseObject(profileBytes)
        val profile = deserialize(profileNode, CandidateProfileRecord.serializer(), "CandidateProfileRecord")
        requireValues(profileNode, CandidateProfileRecord.serializer().descriptor, "profile")
        if (!safeId.containsMatchIn(profile.id)) throw ProfileBundleException(ProfileBundleError.Invalid, "profile id")

        val targets = mutableListOf<JsonObject>()
        val targetIds = HashSet<String>()
        for ((name, bytes) in files.entries.filter { it.key.startsWith("targets/") }.sortedBy { it.key }) {
            val node = parseObject(bytes)
            val t = deserialize(node, InterviewTarget.serializer(), "InterviewTarget")
            requireValues(node, InterviewTarget.serializer().descriptor, "interview")
            if (t.id != idOf(name) || t.profileId != profile.id) throw ProfileBundleException(ProfileBundleError.Invalid, "interview reference")
            targetIds.add(t.id)
            targets.add(node)
        }
        val packs = mutableListOf<JsonObject>()
        for ((name, bytes) in files.entries.filter { it.key.startsWith("packs/") }.sortedBy { it.key }) {
            val node = parseObject(bytes)
            val p = deserialize(node, PreparedPack.serializer(), "PreparedPack")
            requireValues(node, PreparedPack.serializer().descriptor, "pack", maxDepth = 0) // generated content: only the pack's own fields and lists
            if (p.targetId != idOf(name) || p.targetId !in targetIds) throw ProfileBundleException(ProfileBundleError.Invalid, "pack reference")
            packs.add(node)
        }
        val sessions = mutableListOf<JsonObject>()
        var reports = 0
        for ((name, bytes) in files.entries.filter { it.key.startsWith("sessions/") }.sortedBy { it.key }) {
            val node = parseObject(bytes)
            val s = deserialize(node, StoredSession.serializer(), "StoredSession")
            requireValues(node, StoredSession.serializer().descriptor, "session")
            if (s.id != idOf(name) || s.profileId != profile.id) throw ProfileBundleException(ProfileBundleError.Invalid, "session reference")
            if (s.targetId.isNotEmpty() && !safeId.containsMatchIn(s.targetId)) throw ProfileBundleException(ProfileBundleError.Invalid, "session interview")
            if (s.reportJson.isNotEmpty()) reports++
            sessions.add(node)
        }

        val c = manifest.counts
        if (c.interviews != targets.size || c.packs != packs.size || c.sessions != sessions.size)
            throw ProfileBundleException(ProfileBundleError.Tampered, "counts")

        val preview = ProfileBundlePreview(
            clip(profile.name, 200), profile.facts.count { it.isReviewable }, targets.size, packs.size, reports,
            manifest.createdUtc, clip(manifest.appVersion, 64),
        )
        return ProfileBundleContent(manifest, profileNode, targets, packs, sessions, preview)
    }

    /** Path traversal and other names that could escape an extraction folder (the reader never extracts, but rejects them anyway). */
    internal fun isUnsafe(name: String): Boolean =
        name.isEmpty() || name.length > 200 || name.contains("..") || name.contains('\\') || name.contains(':') ||
            name.startsWith('/') || name.any { it.isISOControl() } || java.io.File(name).isAbsolute

    private fun idOf(entryName: String): String =
        entryName.substring(entryName.indexOf('/') + 1).substringBeforeLast('.')

    private fun clip(s: String?, max: Int): String {
        val t = (s ?: "").filter { !it.isISOControl() }.trim()
        return if (t.length > max) t.substring(0, max) else t
    }

    private fun parseManifest(bytes: ByteArray): ProfileBundleManifest {
        val node: JsonObject
        try {
            node = parseObject(bytes)
        } catch (e: ProfileBundleException) {
            throw ProfileBundleException(ProfileBundleError.NotABundle, "manifest json")
        }
        val format = (node["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FormatName) throw ProfileBundleException(ProfileBundleError.NotABundle, "format")
        val version = (node["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (version == null || version < 1) throw ProfileBundleException(ProfileBundleError.Invalid, "version")
        if (version > FormatVersion) throw ProfileBundleException(ProfileBundleError.NewerVersion, "version $version")
        val manifest = deserialize(node, ProfileBundleManifest.serializer(), "ProfileBundleManifest")
        for (e in manifest.entries)
            if (e == null || e.path == null || e.sha256 == null) throw ProfileBundleException(ProfileBundleError.Invalid, "manifest entry")
        return manifest
    }

    /**
     * The JSON may say null where the app always has a value ("Id": null, "Facts": [null], "TargetId": null): such a
     * field, and a null item in a list, is rejected (properties the model declares nullable may be null).
     * The C# walks the object graph with NullabilityInfoContext reflection; this walks the JSON tree with the
     * kotlinx serial descriptor, which carries the same nullability and shape information.
     */
    private fun requireValues(node: JsonElement, descriptor: SerialDescriptor, what: String, maxDepth: Int = 16) {
        fun check(obj: JsonElement, desc: SerialDescriptor, depth: Int) {
            if (obj !is JsonObject || desc.kind != StructureKind.CLASS) return
            for (idx in 0 until desc.elementsCount) {
                val name = desc.getElementName(idx)
                val childDesc = desc.getElementDescriptor(idx)
                val v = obj[name] ?: continue
                if (v is JsonNull) {
                    if (!childDesc.isNullable) throw ProfileBundleException(ProfileBundleError.Invalid, "$what: $name")
                    continue
                }
                if (v is JsonPrimitive && v.isString) continue
                if (childDesc.kind == StructureKind.LIST || childDesc.kind == StructureKind.MAP) {
                    if (v is JsonArray) {
                        val itemDesc = childDesc.getElementDescriptor(0)
                        for (item in v) {
                            if (item is JsonNull) {
                                if (!itemDesc.isNullable) throw ProfileBundleException(ProfileBundleError.Invalid, "$what: $name[]")
                                continue
                            }
                            if (depth < maxDepth && itemDesc.kind == StructureKind.CLASS) check(item, itemDesc, depth + 1)
                        }
                    }
                    continue
                }
                if (depth < maxDepth && childDesc.kind == StructureKind.CLASS) check(v, childDesc, depth + 1)
            }
        }
        check(node, descriptor, 0)
    }

    /** Total entry count from the ZIP's end-of-central-directory record (null when there is none to read). */
    private fun declaredEntryCount(zip: ByteArray): Int? {
        // EOCD: signature 50 4B 05 06, then (at +10) the total number of entries (uint16); at most a 64 KB comment follows.
        var i = zip.size - 22
        while (i >= 0 && i >= zip.size - 22 - 0xFFFF) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4B.toByte() && zip[i + 2] == 0x05.toByte() && zip[i + 3] == 0x06.toByte())
                return (zip[i + 10].toInt() and 0xFF) or ((zip[i + 11].toInt() and 0xFF) shl 8) // 0xFFFF (ZIP64) is far above every limit too
            i--
        }
        return null
    }

    private fun parseObject(bytes: ByteArray): JsonObject {
        try {
            return StrictJson.parse(String(bytes, Charsets.UTF_8), TypedMaxDepth) as? JsonObject
                ?: throw ProfileBundleException(ProfileBundleError.Invalid, "not an object")
        } catch (e: StrictJson.SyntaxException) {
            throw ProfileBundleException(ProfileBundleError.Invalid, "json")
        }
    }

    private fun <T> deserialize(node: JsonElement, serializer: KSerializer<T>, typeName: String): T {
        try {
            return CoreJson.instance.decodeFromJsonElement(serializer, node)
        } catch (ex: Exception) {
            if (ex is SerializationException || ex is UnsupportedOperationException || ex is IllegalStateException ||
                ex is IllegalArgumentException
            ) throw ProfileBundleException(ProfileBundleError.Invalid, typeName)
            throw ex
        }
    }

    private fun readBounded(s: InputStream, max: Long, tooLarge: () -> Exception): ByteArray {
        val ms = ByteArrayOutputStream()
        val buf = ByteArray(81920)
        var n: Int
        while (s.read(buf, 0, buf.size).also { n = it } > 0) {
            if (ms.size() + n > max) throw tooLarge()
            ms.write(buf, 0, n)
        }
        return ms.toByteArray()
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports a validated bundle as a NEW profile: new ids for the profile, its interviews and its sessions, with every
     * reference (interview → profile, pack → interview, session → profile/interview, report → session) remapped.
     * Nothing existing is overwritten; on a failure the partly imported profile is removed again. Returns the new profile.
     */
    fun import(store: WorkspaceStore, content: ProfileBundleContent, name: String? = null): CandidateProfileRecord =
        importWithReport(store, content, name).profile

    /**
     * [import], also reporting the instruction-like lines removed. The bundle's content is untrusted (anyone
     * can recompute its hashes), so it goes through the same guard as an uploaded résumé: documents, facts, interviews
     * and prepared packs are re-sanitized ([PromptInjectionGuard]), the documents' security flags are
     * recomputed rather than taken from the file, and document file names are sanitized again.
     */
    fun importWithReport(store: WorkspaceStore, content: ProfileBundleContent, name: String? = null): ProfileBundleImport {
        var removed = 0
        var newProfileId = newId()
        while (store.getProfile(newProfileId) != null) newProfileId = newId()
        val targetMap = LinkedHashMap<String, String>()
        for (t in content.targets) targetMap[t.stringValue("Id")!!] = newId()

        val profileNode = content.profile.toMutableObject()
        profileNode["Id"] = JsonPrimitive(newProfileId)
        if (!name.isNullOrBlank()) profileNode["Name"] = JsonPrimitive(name)
        val guardedProfile = guard(JsonObject(profileNode))
        removed += guardedProfile.second
        val profile = deserialize(guardedProfile.first!!, CandidateProfileRecord.serializer(), "CandidateProfileRecord")
        try {
            store.saveProfile(profile)
            for (t in content.targets) {
                val node = t.toMutableObject()
                node["Id"] = JsonPrimitive(targetMap[t.stringValue("Id")!!]!!)
                node["ProfileId"] = JsonPrimitive(newProfileId)
                val g = guard(JsonObject(node))
                removed += g.second
                store.saveTarget(deserialize(g.first!!, InterviewTarget.serializer(), "InterviewTarget"))
            }
            for (p in content.packs) {
                val node = p.toMutableObject()
                val targetId = targetMap[p.stringValue("TargetId")!!]!!
                node["TargetId"] = JsonPrimitive(targetId)
                node["ProfileId"] = JsonPrimitive(newProfileId)
                val ctx = node["Context"] as? JsonObject
                if (ctx != null) {
                    val c = ctx.toMutableObject()
                    c["profile_id"] = JsonPrimitive(newProfileId)
                    c["target_id"] = JsonPrimitive(targetId)
                    node["Context"] = JsonObject(c)
                }
                val g = guard(JsonObject(node))
                removed += g.second
                store.savePack(deserialize(g.first!!, PreparedPack.serializer(), "PreparedPack"))
            }
            for (s in content.sessions) {
                val node = s.toMutableObject()
                val sessionId = newId()
                node["Id"] = JsonPrimitive(sessionId)
                node["ProfileId"] = JsonPrimitive(newProfileId)
                val oldTarget = s.stringValue("TargetId") ?: ""
                node["TargetId"] = JsonPrimitive(targetMap[oldTarget] ?: "")
                val report = s.stringValue("ReportJson")
                if (!report.isNullOrEmpty()) node["ReportJson"] = JsonPrimitive(remapReport(report, sessionId))
                store.saveSession(deserialize(JsonObject(node), StoredSession.serializer(), "StoredSession"))
            }
        } catch (e: Throwable) {
            try {
                store.deleteProfile(newProfileId)
            } catch (cleanup: Exception) {
                if (cleanup !is java.io.IOException && cleanup !is SecurityException) throw cleanup
            }
            throw e
        }
        return ProfileBundleImport(store.getProfile(newProfileId) ?: profile, removed)
    }

    /**
     * Removes instruction-like lines from every text of an imported profile, interview or pack and returns the
     * rebuilt tree plus how many were removed (the C# mutates in place; kotlinx elements are immutable).
     * Source documents (objects with a Text, a FileName and SecurityFlags) get their flags
     * recomputed from their own text and their file name sanitized like an upload.
     */
    private fun guard(node: JsonElement?, depth: Int = 0): Pair<JsonElement?, Int> {
        if (node == null || node is JsonNull || depth > 64) return Pair(node, 0)
        var removed = 0
        return when (node) {
            is JsonObject -> {
                val out = node.toMutableObject()
                if (node["SecurityFlags"] is JsonArray && node.containsKey("FileName") && node.stringValue("Text") != null) {
                    val guarded = PromptInjectionGuard.sanitize(node.stringValue("Text")!!)
                    out["Text"] = JsonPrimitive(guarded.text)
                    out["SecurityFlags"] = JsonArray(guarded.flags.map { JsonPrimitive(it) })
                    out["FileName"] = JsonPrimitive(UploadValidator.sanitizeFileName(node.stringValue("FileName")))
                    removed += guarded.flags.size
                }
                for (key in node.keys.toList()) {
                    if (key == "SecurityFlags") continue // done above
                    if (key == "Text" && node.containsKey("FileName") && node["SecurityFlags"] is JsonArray) continue // done above
                    val child = out[key]
                    if (child is JsonPrimitive && child.isString) {
                        val text = child.content
                        if (!PromptInjectionGuard.looksMalicious(text)) continue
                        val guarded = PromptInjectionGuard.sanitize(text)
                        out[key] = JsonPrimitive(guarded.text)
                        removed += maxOf(1, guarded.flags.size)
                    } else {
                        val g = guard(child, depth + 1)
                        if (g.first != null) out[key] = g.first!!
                        removed += g.second
                    }
                }
                Pair(JsonObject(out), removed)
            }
            is JsonArray -> {
                val items = node.toMutableList()
                for (idx in items.size - 1 downTo 0) {
                    val item = items[idx]
                    if (item is JsonPrimitive && item.isString) {
                        val text = item.content
                        if (!PromptInjectionGuard.looksMalicious(text)) continue
                        val guarded = PromptInjectionGuard.sanitize(text)
                        removed += maxOf(1, guarded.flags.size)
                        if (guarded.text.isBlank()) items.removeAt(idx) // a list item that was only an instruction
                        else items[idx] = JsonPrimitive(guarded.text)
                    } else {
                        val g = guard(item, depth + 1)
                        if (g.first != null) items[idx] = g.first!!
                        removed += g.second
                    }
                }
                Pair(JsonArray(items), removed)
            }
            else -> Pair(node, 0)
        }
    }

    private fun remapReport(reportJson: String, sessionId: String): String {
        try {
            val r = StrictJson.parse(reportJson, TypedMaxDepth) as? JsonObject ?: return reportJson
            if (!r.containsKey("SessionId")) return JsonIndented.encodeToString(JsonElement.serializer(), r)
            val out = r.toMutableObject()
            out["SessionId"] = JsonPrimitive(sessionId)
            return JsonIndented.encodeToString(JsonElement.serializer(), JsonObject(out))
        } catch (e: StrictJson.SyntaxException) {
            return reportJson // an unreadable report stays as it was (shown as unreadable, like before)
        }
    }

    private fun newId(): String = newGuidN()

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.toMutableObject(): LinkedHashMap<String, JsonElement> = LinkedHashMap(this)
}

/** The indented JSON writer (System.Text.Json `WriteIndented = true`: two-space indent). */
private val JsonIndented: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
