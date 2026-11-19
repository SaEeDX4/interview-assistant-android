package com.interviewassistant.core.persistence

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.CoreDateTimeNullableSerializer
import com.interviewassistant.core.domain.CoreDateTimeSerializer
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.ProfileMigration
import com.interviewassistant.core.domain.newGuidN
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Mirror of `InterviewAssistant.Core/Persistence/WorkspaceStore.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * The [IDataProtector] / [NoProtection] slice below is the P2 port, preserved verbatim; P3 adds the
 * [WorkspaceStore] document store and [StoredSession], completing the file.
 *
 * Adaptations: the C# write flush (`FileOptions.WriteThrough` + `Flush(flushToDisk: true)`) is a
 * FileOutputStream followed by `fd.sync()` before the same atomic rename; the quarantine catch set
 * (JsonException | CryptographicException | NotSupportedException | ArgumentException) maps to
 * SerializationException | GeneralSecurityException | UnsupportedOperationException |
 * IllegalArgumentException; the C# `event Action<string>` is a listener list (the P2 Contracts pattern).
 */

/** Encrypts data at rest. Desktop uses Windows DPAPI; tests use [NoProtection]. */
interface IDataProtector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(cipher: ByteArray): ByteArray
}

class NoProtection : IDataProtector {
    override fun protect(plain: ByteArray): ByteArray = plain
    override fun unprotect(cipher: ByteArray): ByteArray = cipher
}

/**
 * Local multi-profile workspace (offline-first). Layout:
 *   profiles/{profileId}.bin, targets/{targetId}.bin, packs/{targetId}.bin, sessions/{sessionId}.bin
 * Every file is JSON encrypted with the supplied protector. Ids are validated (hex) so file names can never
 * be influenced by user input (no path traversal).
 */
class WorkspaceStore(val root: String, private val protector: IDataProtector) {
    private val json = CoreJson.instance
    private val lock = Any()

    init {
        for (d in arrayOf("profiles", "targets", "packs", "sessions")) File(root, d).mkdirs()
    }

    /** The interview opened in the Live Assistant for THIS workspace (ids only, no content). */
    fun getActive(): Pair<String?, String?> {
        try {
            val p = File(root, "active.json")
            if (!p.exists()) return Pair(null, null)
            val d = json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer().nullable),
                p.readText(Charsets.UTF_8),
            )
            fun id(k: String): String? {
                val v = d[k]
                return if (v != null && v.length <= 64 && v.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) v else null
            }
            return Pair(id("profile"), id("target"))
        } catch (ex: Exception) {
            if (ex is IOException || ex is SerializationException || ex is SecurityException) return Pair(null, null)
            throw ex
        }
    }

    fun setActive(profileId: String?, targetId: String?) {
        val map = LinkedHashMap<String, String?>()
        map["profile"] = profileId
        map["target"] = targetId
        synchronized(lock) {
            File(root, "active.json").writeText(
                json.encodeToString(MapSerializer(String.serializer(), String.serializer().nullable), map),
                Charsets.UTF_8,
            )
        }
    }

    private fun safeId(id: String): String {
        if (id.isEmpty() || id.length > 64 || !id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' })
            throw IllegalArgumentException("Invalid id")
        return id
    }

    private fun pathFor(folder: String, id: String): String = File(File(root, folder), safeId(id) + ".bin").path

    private fun <T> write(folder: String, id: String, value: T, serializer: KSerializer<T>) {
        val bytes = protector.protect(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
        val path = pathFor(folder, id)
        synchronized(lock) {
            val tmp = path + ".tmp"
            // Flushed to disk before the atomic rename, so a power loss can never leave an empty file behind.
            FileOutputStream(tmp).use { fs ->
                fs.write(bytes)
                fs.flush()
                fs.fd.sync()
            }
            Files.move(File(tmp).toPath(), File(path).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Files moved aside because they could not be decrypted or parsed (see [addFileQuarantinedListener]). */
    val quarantinedFiles: Int get() = quarantined.get()
    private val quarantined = AtomicInteger(0)
    private val unreadable = ConcurrentHashMap<String, Byte>()

    private val fileQuarantinedListeners = mutableListOf<(String) -> Unit>()

    /** Registers a listener raised with the folder name ("profiles", "sessions", …) when a damaged file is moved aside. */
    fun addFileQuarantinedListener(listener: (String) -> Unit) {
        synchronized(fileQuarantinedListeners) { fileQuarantinedListeners.add(listener) }
    }

    fun removeFileQuarantinedListener(listener: (String) -> Unit) {
        synchronized(fileQuarantinedListeners) { fileQuarantinedListeners.remove(listener) }
    }

    private fun <T> read(folder: String, id: String, serializer: KSerializer<T>): T? {
        val path = pathFor(folder, id)
        if (!File(path).exists() || unreadable.containsKey(path)) return null
        try {
            return json.decodeFromString(serializer, String(protector.unprotect(File(path).readBytes()), Charsets.UTF_8))
        } catch (ex: Exception) {
            if (ex is SerializationException || ex is GeneralSecurityException || ex is UnsupportedOperationException ||
                ex is IllegalArgumentException
            ) {
                // One damaged file (disk error, or encrypted for another Windows user) must not hide every other item:
                // it is renamed to *.corrupt (kept for support, never deleted) and skipped.
                synchronized(lock) {
                    try {
                        val stamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        Files.move(File(path).toPath(), File("$path.$stamp.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (moveEx: Exception) {
                        if (moveEx is IOException || moveEx is SecurityException) unreadable[path] = 0 // could not move: skip it from now on
                        else throw moveEx
                    }
                }
                quarantined.incrementAndGet()
                synchronized(fileQuarantinedListeners) { fileQuarantinedListeners.toList() }.forEach { it(folder) }
                return null
            }
            throw ex
        }
    }

    private fun <T> all(folder: String, serializer: KSerializer<T>): List<T> {
        val dir = File(root, folder)
        val files = dir.listFiles { f -> f.name.endsWith(".bin") } ?: return emptyList()
        val out = mutableListOf<T>()
        for (f in files) {
            val v = read(folder, f.nameWithoutExtension, serializer)
            if (v != null) out.add(v)
        }
        return out
    }

    // ---- profiles
    fun saveProfile(p: CandidateProfileRecord) {
        ProfileMigration.migrate(p)
        p.updatedUtc = OffsetDateTime.now(ZoneOffset.UTC)
        write("profiles", p.id, p, CandidateProfileRecord.serializer())
    }

    /** Loads a profile, upgrading older schemas in memory (persisted on the next save; nothing is lost). */
    fun getProfile(id: String): CandidateProfileRecord? {
        val p = read("profiles", id, CandidateProfileRecord.serializer())
        if (p != null) ProfileMigration.migrate(p)
        return p
    }

    fun profiles(): List<CandidateProfileRecord> =
        all("profiles", CandidateProfileRecord.serializer())
            .map { p -> ProfileMigration.migrate(p); p }
            .sortedBy { it.createdUtc }

    /** Deletes the profile and everything owned by it (targets, packs, sessions). */
    fun deleteProfile(id: String) {
        for (t in targets(id)) deleteTarget(t.id)
        for (s in sessions().filter { it.profileId == id }) deleteSession(s.id)
        delete("profiles", id)
    }

    // ---- targets
    fun saveTarget(t: InterviewTarget) {
        t.updatedUtc = OffsetDateTime.now(ZoneOffset.UTC)
        write("targets", t.id, t, InterviewTarget.serializer())
    }

    fun getTarget(id: String): InterviewTarget? = read("targets", id, InterviewTarget.serializer())

    fun targets(profileId: String): List<InterviewTarget> =
        all("targets", InterviewTarget.serializer()).filter { it.profileId == profileId }.sortedBy { it.createdUtc }

    fun deleteTarget(id: String) {
        delete("packs", id)
        delete("targets", id)
    }

    // ---- packs
    fun savePack(p: PreparedPack) = write("packs", p.targetId, p, PreparedPack.serializer())
    fun getPack(targetId: String): PreparedPack? = read("packs", targetId, PreparedPack.serializer())

    // ---- sessions (reports + memory snapshots)
    fun saveSession(s: StoredSession) = write("sessions", s.id, s, StoredSession.serializer())
    fun getSession(id: String): StoredSession? = read("sessions", id, StoredSession.serializer())
    fun sessions(): List<StoredSession> = all("sessions", StoredSession.serializer()).sortedByDescending { it.startedUtc }
    fun deleteSession(id: String) = delete("sessions", id)

    /** Removes sessions older than the retention period. Returns number removed. */
    fun applyRetention(retention: Duration, nowUtc: OffsetDateTime): Int {
        var n = 0
        for (s in sessions().filter { java.time.Duration.between(it.startedUtc, nowUtc) > retention.toJavaDuration() }) {
            deleteSession(s.id)
            n++
        }
        return n
    }


    private fun delete(folder: String, id: String) {
        val p = pathFor(folder, id)
        synchronized(lock) {
            val f = File(p)
            if (f.exists()) f.delete()
        }
    }

    /**
     * "Raw data (ZIP, advanced)": the profile with everything it owns — the same set [deleteProfile] removes
     * (interviews, prepared packs, sessions/reports) — as a plain (unencrypted) ZIP of JSON files, for other tools and
     * data-portability requests. Passed through [SecretFilter]. Not importable: the restorable backup with
     * a manifest and hashes is [ProfileBundle] (.iaprofile).
     */
    fun exportProfile(profileId: String, output: OutputStream) {
        val profile = getProfile(profileId) ?: throw NoSuchElementException("profile")
        // ZipArchive(leaveOpen: true): the caller's stream stays open — finish the ZIP, do not close it.
        val zip = ZipOutputStream(output)
        fun text(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        fun <T> add(name: String, v: T, serializer: KSerializer<T>) = text(name, SecretFilter.toJson(v, serializer))
        text(
            "README.txt",
            "Export format v1 (raw data): profile.json, targets/*.json, packs/*.json, sessions/*.json. Source document text is included in profile/target JSON. " +
                "Passwords, tokens and keys are never included. To restore or move a profile to another PC, use the .iaprofile backup instead.",
        )
        add("profile.json", profile, CandidateProfileRecord.serializer())
        for (t in targets(profileId)) {
            add("targets/${t.id}.json", t, InterviewTarget.serializer())
            val pack = getPack(t.id)
            if (pack != null) add("packs/${t.id}.json", pack, PreparedPack.serializer())
        }
        for (s in sessions().filter { it.profileId == profileId }) add("sessions/${s.id}.json", s, StoredSession.serializer())
        zip.finish()
        zip.flush()
    }
}

/** Persisted record of a finished (or interrupted) interview session. */
@Serializable
class StoredSession(
    @SerialName("Id") val id: String = newGuidN(),
    @SerialName("ProfileId") val profileId: String = "",
    @SerialName("TargetId") val targetId: String = "",
    @SerialName("StartedUtc") @Serializable(with = CoreDateTimeSerializer::class) val startedUtc: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    @SerialName("EndedUtc") @Serializable(with = CoreDateTimeNullableSerializer::class) var endedUtc: OffsetDateTime? = null,
    @SerialName("MemoryJson") var memoryJson: String = "",
    @SerialName("ReportJson") var reportJson: String = "",
)
