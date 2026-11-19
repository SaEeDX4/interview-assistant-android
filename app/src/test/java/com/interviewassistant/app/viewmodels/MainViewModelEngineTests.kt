package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The view-model behaviour checks of the Windows SelfTest (`Services/SelfTest.cs`), driven against the
 * REAL :core engine with the fixture bank (Q001/Q027, the SelfTest's own questions): prepared answers,
 * paraphrase match, history navigation, rework, Coach mode — plus the VM-only surfaces the Rc5 suites
 * assert (history counter, fonts, text flow, live settings). The cloud is configured but signed out, so
 * the engine runs the cloud provider path exactly as the SelfTest's hermetic run does; prepared answers
 * never touch the network.
 */
class MainViewModelEngineTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()
    }

    private var fixture: VmFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
    }

    /** A VM whose engine is built on the fixture knowledge (the SelfTest's LoadSample step). */
    private fun vmWithKnowledge(): VmFixture {
        val f = VmFixture(VmCloudFixture(session = null))
        fixture = f
        f.vm.useKnowledge(FixtureKnowledge.build(), "Fixture · Product Owner · Teroxx")
        return f
    }

    // SelfTest: "Manual question → 3 prepared bullets in UI (no API key needed)"
    @Test
    fun manualQuestion_PreparedBullets_ExactlyThreeDistinct() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        assertEquals(3, f.vm.bullets.size)
        assertEquals(3, f.vm.bullets.map { it.text }.distinct().size)
        assertTrue(f.vm.question.contains("XAB"))
        assertEquals(
            "I'd start with utility, understanding exactly why users should earn, hold and use XAB.",
            f.vm.bullets[0].text,
        )
    }

    // SelfTest: "Spoken-style paraphrase matches"
    @Test
    fun manualQuestion_Paraphrase_ThreeBullets() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("so um tell me a little bit about yourself")
        assertEquals(3, f.vm.bullets.size)
        assertTrue(f.vm.question.contains("yourself", ignoreCase = true))
    }

    // SelfTest: "History counter hidden without history (never \"0 / 1\")"
    @Test
    fun historyLabel_HiddenWithoutHistory_PreviousDisabled() {
        val f = vmWithKnowledge()
        assertEquals("", f.vm.historyLabel)
        assertFalse(f.vm.previousCommand.canExecute())
    }

    // SelfTest: "Previous / next answer navigation"
    @Test
    fun navigation_PreviousNext_MovesAndRestores() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        f.vm.submitManualAsync("Tell me about yourself.")
        val before = f.vm.question
        assertEquals("2 / 2", f.vm.historyLabel)
        f.vm.previousCommand.execute()
        assertNotEquals(before, f.vm.question)
        assertEquals("1 / 2", f.vm.historyLabel)
        f.vm.nextCommand.execute()
        assertEquals(before, f.vm.question)
        assertEquals("2 / 2", f.vm.historyLabel)
        assertFalse(f.vm.nextCommand.canExecute())
    }

    // SelfTest: "Rework buttons act on the displayed item and never add a history entry"
    @Test
    fun rework_OnDisplayedItem_NoNewHistoryEntry() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        f.vm.submitManualAsync("Tell me about yourself.")
        f.vm.previousCommand.execute()
        val label = f.vm.historyLabel
        val q = f.vm.question
        assertTrue(f.vm.shorterCommand.canExecute())
        f.vm.shorterCommand.execute()
        // Give the fire-and-forget variant time to land, then: same position, same question, same count.
        Thread.sleep(500)
        assertEquals(label, f.vm.historyLabel)
        assertEquals(q, f.vm.question)
        assertEquals(2, f.vm.engine!!.history.size)
    }

    // SelfTest: "Coach mode (Ctrl+Alt+C): 3 keywords + structure, then back to bullets"
    @Test
    fun coachToggle_KeywordsAndStructure_ThenBackToBullets() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.toggleCoach()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        assertTrue(f.vm.isCoachView)
        assertTrue(f.vm.coachStructure.contains('→'))
        val kw = f.vm.coachKeywords.split('·').map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(3, kw.size)
        assertEquals(listOf("XAB", "REWARD", "INCREMENTAL"), kw)
        f.vm.toggleCoach()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        assertFalse(f.vm.isCoachView)
        assertEquals(3, f.vm.bullets.size)
    }

    // CanRework rules: no rework without a displayed answer; rework follows the displayed item.
    @Test
    fun canRework_FalseBeforeAnyAnswer_TrueAfter() = runBlocking {
        val f = vmWithKnowledge()
        assertFalse(f.vm.shorterCommand.canExecute())
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        assertTrue(f.vm.shorterCommand.canExecute())
        assertTrue(f.vm.regenerateCommand.canExecute())
    }

    // SelfTest: "Unknown question without the AI service: readable message, no crash"
    @Test
    fun unknownQuestion_ReadableNote_NoExceptionText() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("Zebra quantum basketball orchestra symphony?")
        val note = f.vm.note ?: ""
        assertTrue("note should not be empty", note.isNotEmpty())
        assertFalse(note.contains("Exception"))
        assertFalse(note.contains(" at "))
    }

    // ApplyLiveSettings: the live-safe fields reach the running engine.
    @Test
    fun applyLiveSettings_UpdatesEngineFields() {
        val f = vmWithKnowledge()
        f.cloud.settings.vadSensitivity = 2.0
        f.cloud.settings.useFastCache = false
        f.cloud.settings.answerLanguage = "fa"
        f.vm.applyLiveSettings()
        assertEquals(2.0, f.vm.engine!!.turn.sensitivity, 0.0)
        assertFalse(f.vm.engine!!.options.useFastCache)
        assertEquals("fa", f.vm.engine!!.answerLanguageOverride)
        f.cloud.settings.answerLanguage = "same"
        f.vm.applyLiveSettings()
        assertEquals(null, f.vm.engine!!.answerLanguageOverride)
    }

    // BuildEngine refuses while a session is active — the paused half of the rule (the listening half is
    // covered by MainViewModelSessionTests): pause state is reached there; here the fresh-engine reset
    // rule: a rebuild while idle replaces the engine and clears the displayed history position.
    @Test
    fun buildEngine_WhenIdle_ReplacesEngineAndResetsHistory() = runBlocking {
        val f = vmWithKnowledge()
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        val first = f.vm.engine
        assertEquals("1 / 1", f.vm.historyLabel)
        f.vm.buildEngine()
        assertTrue(f.vm.engine !== first)
        assertEquals(-1, f.vm.historyPosition)
        assertEquals("", f.vm.historyLabel)
        assertFalse(f.vm.shorterCommand.canExecute())
    }

    // Fonts: auto-fit clamps to half steps inside [min, base]; base means "no fit".
    @Test
    fun fitFontSize_ClampsAndRoundsToHalfSteps() {
        val f = vmWithKnowledge()
        val base = f.vm.baseAnswerFontSize
        val min = f.vm.minAnswerFontSize
        assertEquals(base, f.vm.answerFontSize, 0.0)
        f.vm.setFitFontSize(base - 0.3)
        assertEquals(base - 0.5, f.vm.answerFontSize, 0.0)
        f.vm.setFitFontSize(1.0)
        assertEquals(min, f.vm.answerFontSize, 0.0)
        f.vm.setFitFontSize(base + 10.0)
        assertEquals(base, f.vm.answerFontSize, 0.0)
        f.vm.setFitFontSize(null)
        assertEquals(base, f.vm.answerFontSize, 0.0)
        // Coach size is the rounded ×1.18 of the answer size; compact spacing tightens margin + line height.
        assertEquals(Math.rint(base * 1.18), f.vm.coachFontSize, 0.0)
        assertEquals(14.0, f.vm.bulletMargin.bottom, 0.0)
        f.vm.compactSpacing = true
        assertEquals(6.0, f.vm.bulletMargin.bottom, 0.0)
    }

    // TextFlow: the question follows its own script, not the UI language.
    @Test
    fun textFlow_PersianQuestionIsRtl_EnglishIsLtr() {
        val f = vmWithKnowledge()
        f.vm.question = "Tell me about yourself"
        assertEquals(FlowDirection.LeftToRight, f.vm.questionFlowDirection)
        f.vm.question = "لطفا درباره خودتان بگویید"
        assertEquals(FlowDirection.RightToLeft, f.vm.questionFlowDirection)
        assertTrue(f.vm.hasQuestionText)
    }

    // DismissLiveIntro: remembers by default, not when told not to.
    @Test
    fun dismissLiveIntro_RemembersOnce() {
        val f = vmWithKnowledge()
        f.vm.showLiveIntro = true
        f.vm.dismissLiveIntro()
        assertFalse(f.vm.showLiveIntro)
        assertTrue(f.cloud.settings.liveIntroDismissed)
    }

    // Meta: with diagnostics on, the meta line carries style · source · mode · category · confidence · match.
    @Test
    fun meta_WithDiagnostics_ShowsFullLine() = runBlocking {
        val f = vmWithKnowledge()
        f.cloud.settings.showDiagnostics = true
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        val meta = f.vm.answerMeta
        assertTrue(meta.contains(Loc.t("meta_prepared")))
        assertTrue(meta.contains("hypothetical"))
        assertTrue(Regex("match \\d+\\.\\d{2}").containsMatchIn(meta))
    }

    // TestAiAsync (dev path of the hermetic fixture is the cloud path with no lease): the manual-purpose
    // cloud engine answers a prepared question through the production pipeline.
    @Test
    fun testAiAsync_PreparedQuestion_ReturnsAnswerAndModel() = runBlocking {
        val f = vmWithKnowledge()
        f.cloud.service.refreshAsync() // loads the runtime config (the reported model name comes from it)
        val bullets = mutableListOf<String>()
        val (answer, _, model) = f.vm.testAiAsync("How would you increase XAB adoption?") { bullets += it }
        assertTrue(answer.question.contains("XAB"))
        assertEquals("cloud: gpt-5.4-mini", model)
    }
}
