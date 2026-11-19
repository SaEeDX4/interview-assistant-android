package com.interviewassistant.app.services

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.FactKind
import com.interviewassistant.core.domain.FactStatus
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.PersonalEntry
import com.interviewassistant.core.persistence.NoProtection
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app-level Workspace composition: the generic fallback, LoadActive with the
 * personal/boundary refresh from the current profile (the shape of the SelfTest
 * "Profile → confirm facts → prepare → load" check), viewer copies and LoadSample.
 */
class WorkspaceTests {
    private lateinit var dir: File
    private var savedIsolated: String? = null

    @Before
    fun setUp() {
        UiStateLocFixtures.installLocTables()
        dir = Files.createTempDirectory("workspace-test").toFile()
        savedIsolated = AppPaths.isolatedRoot
        AppPaths.isolatedRoot = null
        AppPaths.installBaseDirs(dir.path, dir.path, dir.path)
    }

    @After
    fun tearDown() {
        AppPaths.isolatedRoot = savedIsolated
        dir.deleteRecursively()
    }

    private fun workspace(name: String): Workspace = Workspace(File(dir, name).path, NoProtection())

    @Test
    fun loadActive_NothingPrepared_FallsBackToGeneric() {
        val ws = workspace("ws-fallback")
        val (kb, label) = ws.loadActive("no-profile", "no-target")
        assertEquals("No interview prepared", label)
        assertTrue(kb.questions.isEmpty())
        assertEquals("", kb.profile.name)

        val (kb2, label2) = ws.loadActive() // nothing selected in the store either
        assertEquals("No interview prepared", label2)
        assertTrue(kb2.questions.isEmpty())
    }

    @Test
    fun loadActive_WithPack_RefreshesPersonalAndBoundariesFromTheCurrentProfile() {
        val ws = workspace("ws-active")
        val profile = CandidateProfileRecord(
            id = "prof1",
            name = "Ada Lovelace",
            preferredName = "",
            facts = mutableListOf(
                ProfileFact(kind = FactKind.Personal, category = "motivation", text = "Loves hard problems", status = FactStatus.UserConfirmed),
                ProfileFact(kind = FactKind.Personal, category = "hobby", text = "Unconfirmed hobby", status = FactStatus.Source),
                ProfileFact(kind = FactKind.Boundary, text = "Never mention X", status = FactStatus.UserConfirmed),
                ProfileFact(kind = FactKind.Boundary, text = "Rejected boundary", status = FactStatus.Rejected),
            ),
        )
        ws.store.saveProfile(profile)
        ws.store.saveTarget(InterviewTarget(id = "tgt1", profileId = "prof1", jobTitle = "Engineer", company = "Fabrikam"))
        ws.store.savePack(
            PreparedPack(
                profileId = "prof1",
                targetId = "tgt1",
                profile = CandidateProfile(
                    name = "Ada Lovelace",
                    personal = listOf(PersonalEntry("motivation", "STALE")),
                    boundaries = listOf("STALE-BOUNDARY"),
                ),
            ),
        )

        val (kb, label) = ws.loadActive("prof1", "tgt1")
        assertEquals("Ada Lovelace · Engineer · Fabrikam", label)
        // the pack's stale lists are replaced by the profile's CURRENT confirmed entries
        // (PersonalEntry has reference equality in :core, so compare the entry values)
        assertEquals(
            listOf("motivation" to "Loves hard problems"),
            kb.profile.personal.map { it.category to it.text },
        )
        assertEquals(listOf("Never mention X"), kb.profile.boundaries)

        // the preferred name wins the label when set
        profile.preferredName = "Ada"
        ws.store.saveProfile(profile)
        assertEquals("Ada · Engineer · Fabrikam", ws.loadActive("prof1", "tgt1").second)

        // a target of another profile (or a missing one) does not load this pack
        assertEquals("No interview prepared", ws.loadActive("prof1", "other-target").second)
        assertEquals("No interview prepared", ws.loadActive("other-profile", "tgt1").second)
    }

    @Test
    fun viewerCopies_WriteDeleteAndClear_UnderTheOwnerFolder() {
        val ws = workspace("ws-viewer")
        ws.owner = "selftest"
        val expectedDir = File(File(AppPaths.local, "view"), "selftest")
        assertEquals(expectedDir.path, ws.viewerDir)

        val path = ws.writeViewerCopy("r1", "<html>one</html>")
        assertEquals(File(expectedDir, "report-r1.html").path, path)
        assertEquals("<html>one</html>", File(path).readText(Charsets.UTF_8))

        ws.writeViewerCopy("r2", "<html>two</html>")
        ws.deleteViewerCopy("r1")
        assertFalse(File(path).exists())
        assertTrue(File(expectedDir, "report-r2.html").exists())

        ws.clearViewerCopies()
        assertFalse(expectedDir.exists())
    }

    @Test
    fun owner_DefaultsToLocal() {
        assertEquals("local", workspace("ws-owner").owner)
    }

    @Test
    fun loadSample_RequiresTheQuestionBank_AndLabelsFromTheContext() {
        assertNull(Workspace.loadSample(null))
        val empty = File(dir, "sample-empty").apply { mkdirs() }
        assertNull(Workspace.loadSample(empty.path))

        val fixture = File(dir, "sample")
        fixture.mkdirs()
        File(fixture, "candidate_profile.json").writeText("""{"name": "Sample Sam"}""")
        File(fixture, "candidate_stories.json").writeText("""{"stories": []}""")
        File(fixture, "question_bank.json").writeText("""{"questions": []}""")
        val loaded = Workspace.loadSample(fixture.path)
        assertTrue(loaded != null)
        assertEquals("Sample · Sample Sam · ", loaded!!.second)
        assertEquals("Sample Sam", loaded.first.context.candidateName)
    }
}
