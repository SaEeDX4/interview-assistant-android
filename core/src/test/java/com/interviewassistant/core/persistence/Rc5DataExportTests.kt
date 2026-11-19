package com.interviewassistant.core.persistence

import com.interviewassistant.core.Fixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.domain.newGuidN
import com.interviewassistant.core.knowledge.TargetContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/Rc5DataExportTests.cs` — the cases that depend only on Package A's
 * surface (SecretFilter, ProfileBundle, WorkspaceStore). The Reports-renderer cases are deferred to the
 * Reports package (named in P3-PORT-NOTES.md).
 *
 * Seed adaptation (documented in P3-PORT-NOTES.md): the C# seed builds the pack with PreparationPipeline
 * (a later package) and the report with ReportSerializer; the bundle only carries those payloads, so the
 * pack is constructed directly with the same ids/context and the report JSON is an opaque document with
 * the same SessionId field the bundle's remapper rewrites.
 */
class Rc5DataExportTests {

    companion object {
        private const val FakeApiKey = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWx1234" // fake-credential: test fixture
        private const val FakeJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    }

    private fun tempRoot(): String = Files.createTempDirectory("ia-rc5-export-").toString()

    private fun deleteQuietly(root: String) {
        try {
            File(root).deleteRecursively()
        } catch (e: Exception) {
            if (e !is java.io.IOException && e !is SecurityException) throw e
        }
    }

    // ------------------------------------------------------------------ secret filter

    @Test
    fun secretKeyNamesAreRecognised() {
        for (key in listOf(
            "password", "Password", "access_token", "refreshToken", "id_token", "apiKey", "api-key",
            "OPENAI_API_KEY", "client_secret", "ProviderCredential", "Authorization", "privateKey",
            "sessionCookie", "Jwt",
        )) assertTrue(key, SecretFilter.isSecretKey(key))
    }

    @Test
    fun ordinaryKeyNamesAreKept() {
        for (key in listOf(
            "Text", "Company", "SecurityFlags", "Name", "email", "Kind", "Id", "meteredSeconds",
            "Terminology", "Positioning", "JobDescriptionText", "ReportJson", "MemoryJson", "Provenance",
            "planCode",
        )) assertFalse(key, SecretFilter.isSecretKey(key))
    }

    @Test
    fun filterRemovesSecretKeysEverywhere_AndRedactsCredentialShapedValues() {
        val node = Json.parseToJsonElement(
            """
            {
              "account": { "id": "a1", "email": "jane@example.com", "password": "hunter2", "nested": [ { "refresh_token": "r", "keep": "yes" } ] },
              "documents": [ { "data": { "accessToken": "$FakeJwt", "notes": "my key is $FakeApiKey ok" } } ],
              "report": "{\"SessionId\":\"s1\",\"apiKey\":\"abc\",\"Questions\":[]}",
              "header": "Bearer abcdefghijklmnopqrstuvwxyz012345",
              "text": "Reduced checkout drop-off by 18% for 40,000 merchants."
            }
            """,
        )
        val cleaned = SecretFilter.clean(node)
        assertTrue(cleaned.changed)
        val json = cleaned.node.toString()
        assertFalse(json.contains("hunter2"))
        assertFalse(json.contains("refresh_token"))
        assertFalse(json.contains("accessToken"))
        assertFalse(json.contains(FakeApiKey))
        assertFalse(json.contains(FakeJwt))
        assertFalse(json.contains("apiKey")) // inside a JSON document stored as a string
        assertFalse(json.contains("abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(json.contains("jane@example.com"))
        assertTrue(json.contains("\"keep\":\"yes\""))
        assertTrue(json.contains("SessionId"))
        assertTrue(json.contains("Reduced checkout drop-off by 18% for 40,000 merchants."))
        assertTrue(json.contains(SecretFilter.Removed))
        assertFalse(SecretFilter.clean(Json.parseToJsonElement("""{"text":"nothing secret here"}""")).changed)
    }

    @Test
    fun credentialShapedValuesAreDetected() {
        for (value in listOf(
            FakeApiKey, FakeJwt, "Authorization: Bearer abcdefghijklmnop0123456789",
            "pdl_live_apikey_01abcdefghijklmnop", "AKIAABCDEFGHIJKLMNOP", "password=hunter2",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----", // fake-credential: test fixture
        )) {
            assertTrue(value, SecretFilter.looksSecret(value))
            assertFalse(value, SecretFilter.looksSecret(SecretFilter.redact(value)))
        }
    }

    @Test
    fun ordinaryTextIsNotTreatedAsSecret() {
        for (value in listOf(
            "Senior Product Manager — Contoso Payments (2019 – 2024)", "Password management and SSO rollout",
            "Led a team of 6 engineers; sk-learn and pandas", "Tokenomics for a loyalty token",
        )) assertFalse(value, SecretFilter.looksSecret(value))
    }

    // ------------------------------------------------------------------ .iaprofile bundle

    private class Seeded(
        val store: WorkspaceStore,
        val profile: CandidateProfileRecord,
        val target: InterviewTarget,
        val unprepared: InterviewTarget,
        val report: StoredSession,
        val orphan: StoredSession,
    )

    private fun fixtureProfile(): CandidateProfileRecord {
        val p = Fixtures.shervinProfile(confirm = false)
        p.name = "Shervin <Fallahdoust>"
        p.headline = "Product Owner & payments"
        // confirm the roles and their first achievement, leave the rest to review
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        for (role in p.facts.filter { it.kind == FactKind.Role }) role.setConfirmed(true, now)
        val achievement = p.facts.first { it.kind == FactKind.Achievement && it.parentId != null }
        achievement.setConfirmed(true, now)
        ProfileService.setPersonal(p, "hobby", "Sailing <img src=x onerror=alert(1)>")
        ProfileService.setPersonal(p, "avoid_topic", "My previous salary")
        return p
    }

    private fun seed(root: String): Seeded {
        val store = WorkspaceStore(root, NoProtection())
        val p = fixtureProfile()
        p.notes = "Remember: $FakeApiKey"
        store.saveProfile(p)
        val t = Fixtures.teroxxTarget(p.id)
        t.jobTitle = "Product Owner"
        t.company = "Teroxx"
        t.roleNotes = "portal password=hunter2"
        store.saveTarget(t)
        val pack = PreparedPack(
            profileId = p.id, targetId = t.id,
            context = TargetContext(profileId = p.id, targetId = t.id),
        )
        store.savePack(pack)
        val t2 = InterviewTarget(profileId = p.id, jobTitle = "Head of Product", company = "Fabrikam")
        store.saveTarget(t2)
        val reportId = newGuidN()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val report = StoredSession(
            id = reportId, profileId = p.id, targetId = t.id,
            startedUtc = now.minusHours(2), endedUtc = now.minusHours(1),
            reportJson = """{"SessionId":"$reportId","ProfileLabel":"Shervin <Fallahdoust>","TargetLabel":"Teroxx"}""",
        )
        store.saveSession(report)
        val orphan = StoredSession(profileId = p.id, targetId = newGuidN(), startedUtc = now.minusDays(3)) // its interview was deleted
        store.saveSession(orphan)
        store.saveSession(StoredSession(profileId = "someoneelse", targetId = t.id)) // another profile's session is never exported
        return Seeded(store, store.getProfile(p.id)!!, t, t2, report, orphan)
    }

    private fun writeBundle(s: Seeded, now: OffsetDateTime? = null): ByteArray {
        val ms = ByteArrayOutputStream()
        ProfileBundle.write(s.store, s.profile.id, ms, "2.1.0-rc.5", now ?: OffsetDateTime.of(2026, 10, 17, 9, 0, 0, 0, ZoneOffset.UTC))
        return ms.toByteArray()
    }

    private fun entriesOf(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    /** Rebuilds a ZIP from entries (optionally recomputing the manifest hashes so only the later checks can catch it). */
    private fun zipOf(entries: Map<String, ByteArray>, rehash: Boolean = false): ByteArray {
        val map = LinkedHashMap(entries)
        if (rehash) {
            val manifest = Json.parseToJsonElement(map["manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject
            val list = JsonArray(map.entries.filter { it.key != "manifest.json" }.map { (name, bytes) ->
                JsonObject(
                    linkedMapOf(
                        "path" to JsonPrimitive(name),
                        "size" to JsonPrimitive(bytes.size),
                        "sha256" to JsonPrimitive(sha256Hex(bytes)),
                    ),
                )
            })
            val m = LinkedHashMap(manifest)
            m["entries"] = list
            map["manifest.json"] = Json.encodeToString(JsonElement.serializer(), JsonObject(m)).toByteArray(Charsets.UTF_8)
        }
        val ms = ByteArrayOutputStream()
        ZipOutputStream(ms).use { z ->
            for ((name, bytes) in map) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return ms.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rejected(zip: ByteArray, limits: ProfileBundleLimits? = null): ProfileBundleError =
        assertThrows(ProfileBundleException::class.java) { ProfileBundle.read(ByteArrayInputStream(zip), limits) }.error

    @Test
    fun bundle_HasAManifestWithHashes_AndEveryEntry() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val entries = entriesOf(writeBundle(s))
            val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject
            assertEquals("iaprofile", (manifest["format"] as JsonPrimitive).content)
            assertEquals("1", (manifest["version"] as JsonPrimitive).content)
            assertEquals("2.1.0-rc.5", (manifest["appVersion"] as JsonPrimitive).content)
            assertEquals("2026-10-17T09:00:00Z", (manifest["createdUtc"] as JsonPrimitive).content)
            val counts = manifest["counts"] as JsonObject
            assertEquals("2", (counts["interviews"] as JsonPrimitive).content)
            assertEquals("1", (counts["packs"] as JsonPrimitive).content)
            assertEquals("2", (counts["sessions"] as JsonPrimitive).content)
            assertEquals("1", (counts["reports"] as JsonPrimitive).content)
            assertTrue(entries.containsKey("profile.json"))
            assertTrue(entries.containsKey("targets/${s.target.id}.json"))
            assertTrue(entries.containsKey("targets/${s.unprepared.id}.json"))
            assertTrue(entries.containsKey("packs/${s.target.id}.json"))
            assertTrue(entries.containsKey("sessions/${s.report.id}.json"))
            assertEquals(7, entries.size) // manifest, profile, 2 interviews, 1 pack, 2 sessions: the other profile's session is not included
            for (e in manifest["entries"] as JsonArray) {
                val entry = e as JsonObject
                val bytes = entries[(entry["path"] as JsonPrimitive).content]!!
                assertEquals(sha256Hex(bytes), (entry["sha256"] as JsonPrimitive).content)
                assertEquals(bytes.size.toString(), (entry["size"] as JsonPrimitive).content)
            }
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun bundle_RoundTrip_ImportsAsANewProfileWithRemappedIds_AndNeverOverwrites() {
        val rootA = tempRoot()
        val rootB = tempRoot()
        try {
            val s = seed(rootA)
            val bytes = writeBundle(s)
            val content = ProfileBundle.read(ByteArrayInputStream(bytes))
            assertEquals(s.profile.name, content.preview.profileName)
            assertEquals(s.profile.facts.count { it.isReviewable }, content.preview.facts)
            assertEquals(Triple(2, 1, 1), Triple(content.preview.interviews, content.preview.packs, content.preview.reports))
            assertEquals("2.1.0-rc.5", content.preview.appVersion)

            // another PC (empty workspace)
            val other = WorkspaceStore(rootB, NoProtection())
            val imported = ProfileBundle.import(other, content)
            assertImported(other, s, imported)

            // the same PC: a second copy next to the original, the original untouched
            val copy = ProfileBundle.import(s.store, ProfileBundle.read(ByteArrayInputStream(bytes)), "Copy")
            assertNotEquals(s.profile.id, copy.id)
            assertEquals("Copy", copy.name)
            assertEquals(2, s.store.profiles().size)
            assertEquals(s.profile.name, s.store.getProfile(s.profile.id)!!.name)
            assertEquals(2, s.store.targets(s.profile.id).size)
            assertNotNull(s.store.getPack(s.target.id))
            assertEquals(2, s.store.sessions().count { it.profileId == s.profile.id })
            assertImported(s.store, s, copy)
        } finally {
            deleteQuietly(rootA)
            deleteQuietly(rootB)
        }
    }

    private fun assertImported(store: WorkspaceStore, s: Seeded, imported: CandidateProfileRecord) {
        assertNotEquals(s.profile.id, imported.id)
        val factsSerializer = ListSerializer(ProfileFact.serializer())
        assertEquals(
            CoreJson.instance.encodeToString(factsSerializer, s.profile.facts),
            CoreJson.instance.encodeToString(factsSerializer, imported.facts),
        )
        assertEquals(s.profile.documents.map { it.text }, imported.documents.map { it.text })
        val targets = store.targets(imported.id)
        assertEquals(2, targets.size)
        assertTrue(targets.none { it.id == s.target.id || it.id == s.unprepared.id })
        val target = targets.single { it.company == "Teroxx" }
        assertEquals(s.target.jobDescriptionText, target.jobDescriptionText)
        val pack = store.getPack(target.id)
        assertNotNull(pack)
        assertEquals(target.id, pack!!.targetId)
        assertEquals(imported.id, pack.profileId)
        assertEquals(imported.id, pack.context.profileId)
        assertEquals(target.id, pack.context.targetId)
        assertNull(store.getPack(targets.single { it.company == "Fabrikam" }.id))
        val sessions = store.sessions().filter { it.profileId == imported.id }
        assertEquals(2, sessions.size)
        assertTrue(sessions.none { it.id == s.report.id || it.id == s.orphan.id })
        val report = sessions.single { it.reportJson.isNotEmpty() }
        assertEquals(target.id, report.targetId)
        val reportNode = Json.parseToJsonElement(report.reportJson) as JsonObject
        assertEquals(report.id, (reportNode["SessionId"] as JsonPrimitive).content) // report → session remapped
        assertEquals("", sessions.single { it.reportJson.isEmpty() }.targetId) // its interview was not in the bundle
    }

    @Test
    fun bundle_TamperedEntryIsRejected() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val entries = entriesOf(writeBundle(s))
            val profile = entries["profile.json"]!!.toString(Charsets.UTF_8).replace("Shervin", "Mallory")
            entries["profile.json"] = profile.toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Tampered, rejected(zipOf(entries)))

            val removed = entriesOf(writeBundle(s))
            removed.remove("sessions/${s.report.id}.json")
            assertEquals(ProfileBundleError.Tampered, rejected(zipOf(removed)))

            val added = entriesOf(writeBundle(s))
            added["sessions/abcdef.json"] = "{}".toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Tampered, rejected(zipOf(added)))

            // consistent hashes but a claimed count that does not match the content
            val countsEdited = entriesOf(writeBundle(s))
            val manifest = Json.parseToJsonElement(countsEdited["manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject
            val m = LinkedHashMap(manifest)
            val c = LinkedHashMap(m["counts"] as JsonObject)
            c["sessions"] = JsonPrimitive(7)
            m["counts"] = JsonObject(c)
            countsEdited["manifest.json"] = Json.encodeToString(JsonElement.serializer(), JsonObject(m)).toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Tampered, rejected(zipOf(countsEdited)))
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun bundle_PathTraversalIsRejected() {
        val root = tempRoot()
        try {
            val s = seed(root)
            for (name in listOf("../evil.json", "targets/../../evil.json", "/etc/evil.json", "C:/Windows/evil.json", "targets\\evil.json", "..\\..\\evil.json")) {
                val entries = entriesOf(writeBundle(s))
                entries[name] = "{}".toByteArray(Charsets.UTF_8)
                assertEquals(name, ProfileBundleError.UnsafePath, rejected(zipOf(entries, rehash = true)))
            }
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun bundle_UnexpectedEntriesAndBadJsonAreRejected() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val exe = entriesOf(writeBundle(s))
            exe["payload.exe"] = byteArrayOf(0x4d, 0x5a)
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(exe, rehash = true)))

            val badJson = entriesOf(writeBundle(s))
            badJson["profile.json"] = "{ not json".toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(badJson, rehash = true)))

            val deep = entriesOf(writeBundle(s))
            deep["profile.json"] = ("[".repeat(500) + "]".repeat(500)).toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(deep, rehash = true)))

            val wrongType = entriesOf(writeBundle(s))
            wrongType["profile.json"] = """{"Id":"abc","Facts":"not a list"}""".toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(wrongType, rehash = true)))

            // an interview that belongs to another profile
            val foreign = entriesOf(writeBundle(s))
            val tName = "targets/${s.unprepared.id}.json"
            val t = LinkedHashMap(Json.parseToJsonElement(foreign[tName]!!.toString(Charsets.UTF_8)) as JsonObject)
            t["ProfileId"] = JsonPrimitive("otherprofile")
            foreign[tName] = Json.encodeToString(JsonElement.serializer(), JsonObject(t)).toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(foreign, rehash = true)))

            // a pack for an interview that is not in the bundle
            val orphanPack = entriesOf(writeBundle(s))
            orphanPack["packs/${newGuidN()}.json"] = orphanPack["packs/${s.target.id}.json"]!!
            assertEquals(ProfileBundleError.Invalid, rejected(zipOf(orphanPack, rehash = true)))
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun bundle_OversizeAndZipBombsAreRejected() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val bytes = writeBundle(s)
            assertEquals(ProfileBundleError.TooLarge, rejected(bytes, ProfileBundleLimits(maxFileBytes = 1024)))
            assertEquals(ProfileBundleError.TooLarge, rejected(bytes, ProfileBundleLimits(maxEntryBytes = 512)))
            assertEquals(ProfileBundleError.TooLarge, rejected(bytes, ProfileBundleLimits(maxEntries = 3)))
            assertEquals(ProfileBundleError.TooLarge, rejected(bytes, ProfileBundleLimits(maxTotalBytes = 4096)))

            // 40 MB of zeros compresses to a few kilobytes: the uncompressed limit stops it
            val bomb = entriesOf(bytes)
            bomb["sessions/bomb.json"] = ByteArray(40 shl 20)
            val zip = zipOf(bomb, rehash = true)
            assertTrue(zip.size < (1 shl 20))
            assertEquals(ProfileBundleError.TooLarge, rejected(zip))
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun bundle_VersionFormatAndNonZipAreRejected() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val newer = entriesOf(writeBundle(s))
            val nm = LinkedHashMap(Json.parseToJsonElement(newer["manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject)
            nm["version"] = JsonPrimitive(2)
            newer["manifest.json"] = Json.encodeToString(JsonElement.serializer(), JsonObject(nm)).toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.NewerVersion, rejected(zipOf(newer)))

            val format = entriesOf(writeBundle(s))
            val fm = LinkedHashMap(Json.parseToJsonElement(format["manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject)
            fm["format"] = JsonPrimitive("something-else")
            format["manifest.json"] = Json.encodeToString(JsonElement.serializer(), JsonObject(fm)).toByteArray(Charsets.UTF_8)
            assertEquals(ProfileBundleError.NotABundle, rejected(zipOf(format)))

            val noManifest = entriesOf(writeBundle(s))
            noManifest.remove("manifest.json")
            assertEquals(ProfileBundleError.NotABundle, rejected(zipOf(noManifest)))

            assertEquals(ProfileBundleError.NotABundle, rejected("this is not a zip file".toByteArray(Charsets.UTF_8)))
            assertEquals(ProfileBundleError.NotABundle, rejected(ByteArray(0)))
        } finally {
            deleteQuietly(root)
        }
    }

    @Test
    fun noSecretsInAnyProfileExport() {
        val root = tempRoot()
        try {
            val s = seed(root)
            val secrets = listOf(FakeApiKey, "hunter2")
            val bundle = entriesOf(writeBundle(s))
            for ((name, bytes) in bundle) {
                val text = bytes.toString(Charsets.UTF_8)
                for (secret in secrets) assertFalse("$name must not contain a secret", text.contains(secret))
                assertFalse(name, SecretFilter.looksSecret(text))
            }
            val raw = ByteArrayOutputStream()
            s.store.exportProfile(s.profile.id, raw)
            for ((name, bytes) in entriesOf(raw.toByteArray())) {
                val text = bytes.toString(Charsets.UTF_8)
                for (secret in secrets) assertFalse("$name must not contain a secret", text.contains(secret))
                assertFalse(name, SecretFilter.looksSecret(text))
            }
            // (The C# also asserts the rendered HTML export here; the renderers belong to the Reports package.)
            // the import of a filtered bundle still works (nothing structural was removed)
            assertNotNull(ProfileBundle.read(ByteArrayInputStream(writeBundle(s))))
        } finally {
            deleteQuietly(root)
        }
    }
}
