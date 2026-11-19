package com.interviewassistant.core.persistence

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactStats
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.ProfileMigration
import com.interviewassistant.core.domain.ProfileService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/WorkspaceStoreTests.cs` — a Package A gap closed by Package D
 * (the store was ported in A; its dedicated test file was not in any package's list).
 */
class WorkspaceStoreTests {

    private class XorProtector : IDataProtector {
        override fun protect(plain: ByteArray): ByteArray = plain.map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unprotect(cipher: ByteArray): ByteArray = protect(cipher)
    }

    private fun tempRoot(): String = Files.createTempDirectory("ia-ws-").toString()

    @Test
    fun dataIsEncryptedAtRestAndRoundTrips() {
        val root = tempRoot()
        val store = WorkspaceStore(root, XorProtector())
        val p = Fixtures.shervinProfile()
        store.saveProfile(p)
        val raw = File(root, "profiles").listFiles()!![0].readBytes()
        assertFalse(String(raw, Charsets.UTF_8).contains("Arzif"))
        val back = store.getProfile(p.id)!!
        assertEquals(p.facts.size, back.facts.size)
        for (f in back.facts) assertEquals(FactStatus.UserConfirmed, f.status)
        File(root).deleteRecursively()
    }

    @Test
    fun damagedFile_IsQuarantinedAndEverythingElseStaysVisible() {
        val root = tempRoot()
        val store = WorkspaceStore(root, XorProtector())
        val good = Fixtures.shervinProfile()
        store.saveProfile(good)
        val damaged = File(root, "profiles/damaged-1.bin")
        damaged.writeBytes(byteArrayOf(1, 2, 3, 4, 5)) // not decryptable / not JSON
        var folder: String? = null
        store.addFileQuarantinedListener { f -> folder = f }

        val profiles = store.profiles() // RC1: threw and hid every profile
        assertEquals(1, profiles.size)
        assertEquals(good.id, profiles[0].id)
        assertEquals("profiles", folder)
        assertEquals(1, store.quarantinedFiles)
        assertFalse(damaged.exists())
        assertEquals(1, File(root, "profiles").listFiles { f -> f.name.startsWith("damaged-1.bin.") && f.name.endsWith(".corrupt") }!!.size) // kept for support
        assertNull(store.getProfile("damaged-1"))
        File(root).deleteRecursively()
    }

    @Test
    fun migrationTolerates_DuplicateDocumentIds() {
        val p = Fixtures.shervinProfile()
        p.schemaVersion = 1
        if (p.documents.isNotEmpty()) p.documents.add(p.documents[0])
        assertTrue(ProfileMigration.migrate(p))
        assertEquals(CandidateProfileRecord.CurrentSchemaVersion, p.schemaVersion)
    }

    @Test
    fun idsCannotTraversePaths() {
        val store = WorkspaceStore(tempRoot(), NoProtection())
        for (id in listOf("../escape", "a/b", "")) {
            try {
                store.getProfile(id)
                fail("expected IllegalArgumentException for '$id'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun exportContainsProfileTargetsAndSessionsAndRetentionDeletes() {
        val root = tempRoot()
        val store = WorkspaceStore(root, NoProtection())
        val p = Fixtures.shervinProfile()
        store.saveProfile(p)
        val t = Fixtures.teroxxTarget(p.id)
        store.saveTarget(t)
        store.saveSession(StoredSession(profileId = p.id, targetId = t.id, startedUtc = OffsetDateTime.now(ZoneOffset.UTC).minusDays(100)))
        store.saveSession(StoredSession(profileId = p.id, targetId = t.id))
        val out = ByteArrayOutputStream()
        store.exportProfile(p.id, out)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        assertTrue(names.contains("profile.json"))
        assertEquals(1, names.count { n -> n.startsWith("targets/") })
        assertEquals(2, names.count { n -> n.startsWith("sessions/") })
        assertEquals(1, store.applyRetention(Duration.ofDays(90), OffsetDateTime.now(ZoneOffset.UTC)))
        assertEquals(1, store.sessions().size)
        File(root).deleteRecursively()
    }
}

class AccountWorkspaceIsolationTests {

    private fun tempDir(): String {
        val d = Files.createTempDirectory("ia-ws-").toFile()
        return d.toString()
    }

    @Test
    fun accountsHaveSeparateWorkspaces_AndNothingLeaksAcrossThem() {
        val baseDir = tempDir()
        val a = WorkspaceStore(WorkspaceLocator.rootFor(baseDir, "user-a"), NoProtection())
        val profile = CandidateProfileRecord(name = "Alice")
        ProfileService.setPersonal(profile, "hobby", "Sailing")
        a.saveProfile(profile)
        a.saveSession(StoredSession(id = "s1", profileId = profile.id, reportJson = "{}"))
        a.setActive(profile.id, null)

        val b = WorkspaceStore(WorkspaceLocator.rootFor(baseDir, "user-b"), NoProtection())
        assertTrue(b.profiles().isEmpty())
        assertTrue(b.sessions().isEmpty())
        assertEquals(Pair<String?, String?>(null, null), b.getActive())
        assertTrue(a.root != b.root)
        assertFalse(a.root.contains("user-a")) // no identifiers in paths
        assertEquals(1, WorkspaceStore(WorkspaceLocator.rootFor(baseDir, "user-a"), NoProtection()).profiles().size)
    }

    @Test
    fun legacySharedWorkspace_IsAdoptedOnceByTheFirstAccount() {
        val baseDir = tempDir()
        val legacy = WorkspaceStore(baseDir, NoProtection())
        legacy.saveProfile(CandidateProfileRecord(name = "Legacy"))
        assertTrue(WorkspaceLocator.adoptLegacy(baseDir, "first"))
        assertFalse(WorkspaceLocator.adoptLegacy(baseDir, "second"))
        val first = WorkspaceStore(WorkspaceLocator.rootFor(baseDir, "first"), NoProtection())
        val second = WorkspaceStore(WorkspaceLocator.rootFor(baseDir, "second"), NoProtection())
        assertEquals("Legacy", first.profiles().single().name)
        assertTrue(second.profiles().isEmpty())
        assertTrue(legacy.profiles().isEmpty())
    }

    @Test
    fun rc1ProfileMigratesWithoutLosingConfirmedFacts() {
        val baseDir = tempDir()
        val store = WorkspaceStore(baseDir, NoProtection())
        // An RC1 profile: no schema version, no provenance fields.
        val json = """{"Id":"abc123","Name":"Shervin","Facts":[{"Id":"f1","Kind":"Role","Text":"Lead","Company":"Arzif","Status":"UserConfirmed","Provenance":{"DocumentId":"d1","LineStart":3,"LineEnd":3,"Snippet":"Lead"}},{"Id":"f2","Kind":"Summary","Text":"PO","Status":"Source"}],"Documents":[{"Id":"d1","FileName":"cv.pdf","Text":"…"}]}"""
        val dir = File(baseDir, "profiles")
        dir.mkdirs()
        File(dir, "abc123.bin").writeText(json)
        val p = store.getProfile("abc123")!!
        assertEquals(CandidateProfileRecord.CurrentSchemaVersion, p.schemaVersion)
        val f1 = p.facts.single { f -> f.id == "f1" }
        assertEquals(FactStatus.UserConfirmed, f1.status)
        assertEquals(com.interviewassistant.core.domain.FactSource.ResumeImport, f1.source)
        assertEquals("cv.pdf", f1.sourceName)
        assertEquals(FactStats(2, 1, 1), p.stats) // one authoritative count
        store.saveProfile(p)
        assertEquals(2, store.getProfile("abc123")!!.facts.size)
    }

    @Test
    fun factStats_HeaderListAndFiltersAgreeAfterEveryChange() {
        val p = CandidateProfileRecord()
        val role = ProfileService.addManualFact(p, FactKind.Role, "Lead", company = "Arzif", confirmed = false)
        val summary = ProfileService.addManualFact(p, FactKind.Summary, "PO with 10 years", confirmed = false)
        assertEquals(FactStats(2, 0, 2), p.stats)
        role.setConfirmed(true, OffsetDateTime.now(ZoneOffset.UTC))
        assertEquals(FactStats(2, 1, 1), p.stats)
        p.confirmAll()
        assertEquals(FactStats(2, 2, 0), p.stats)
        assertEquals(0, p.pendingReviewCount)
        summary.setConfirmed(false, OffsetDateTime.now(ZoneOffset.UTC))
        assertEquals(FactStats(2, 1, 1), p.stats)
        assertTrue(ProfileService.deleteFact(p, summary.id))
        assertEquals(FactStats(1, 1, 0), p.stats)
    }

    @Test
    fun personalAnswers_OnlyConfirmedReachTheEngineProfile() {
        val p = CandidateProfileRecord(name = "Maria")
        ProfileService.setPersonal(p, "hobby", "Bouldering")
        ProfileService.setPersonal(p, "avoid_topic", "My previous salary")
        val engine = ProfileService.toEngineProfile(p)
        assertEquals("Bouldering", engine.personal.single().text)
        assertTrue(engine.boundaries.contains("My previous salary"))
        p.facts.single { f -> f.category == "hobby" }.setConfirmed(false, OffsetDateTime.now(ZoneOffset.UTC))
        assertTrue(ProfileService.toEngineProfile(p).personal.isEmpty())
        ProfileService.setPersonal(p, "hobby", "")
        assertFalse(p.facts.any { f -> f.category == "hobby" })
    }
}
