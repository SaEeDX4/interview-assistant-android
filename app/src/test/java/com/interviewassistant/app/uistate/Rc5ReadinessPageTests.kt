package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `Rc5ReadinessPageTests.cs` — the JVM-portable presentation cases.
 * Deferred by name (WPF wiring/source scans; Compose counterparts land in P4-D/E/G):
 * PreInterviewCheck_IsAHomePage_AndTheSeparateWindowIsGone, OpenReadiness_DeepLinksHomeToThePage,
 * ThePage_WritesTheStore_AndRendersFromIt, StickyActionBar_HasRunSettingsAndOnePrimaryStart,
 * GetStarted_HasExactlyOnePrimaryAction_FromTheSetupPlanner, InterviewsCard_AndTitleBarIndicator_OpenTheCheck,
 * SelfTest_DrivesThePage_AndChecksGetStarted.
 */
class Rc5ReadinessPageTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()

        private fun run(ctx: String, vararg results: Pair<ReadinessCheckId, CheckOutcome>): ReadinessStore {
            val store = ReadinessStore()
            store.begin(ctx)
            val given = results.toMap()
            for (id in ReadinessCheckId.entries)
                store.report(ReadinessCheckResult(id, given[id] ?: CheckOutcome.Pass))
            return store
        }
    }

    // ---------------- keys ----------------

    @Test
    fun everyStateAndActionKey_ExistsInEnglishAndEveryLanguage() {
        val keys = (ReadinessState.entries.map { s -> ReadinessSnapshot.stateKey(s) } +
            SetupAction.entries.map { a -> SetupPlanner.actionKey(a) }).distinct()
        assertEquals(12, keys.size)
        for (k in keys) assertTrue("en.json lacks $k", Loc.has(k))
        for (lang in LanguageRegistry.all.map { it.code }) {
            val table = Loc.tableFor(lang)
            for (k in keys) {
                val v = table[k]
                assertTrue("$lang: $k", v != null && v.trim().isNotEmpty())
            }
        }
    }

    @Test
    fun everyPresentationKey_ExistsInEveryLanguage() {
        val keys = mutableListOf<String>()
        for (o in CheckOutcome.entries.map { it as CheckOutcome? } + listOf(null)) keys.add(ReadinessPresentation.statusKey(o))
        for (id in ReadinessCheckId.entries) {
            for (cloud in listOf(true, false)) {
                keys.add(ReadinessPresentation.titleKey(id, cloud))
                keys.add(ReadinessPresentation.fixHintKey(id, cloud))
            }
        }
        for (f in ReadinessFix.entries) ReadinessPresentation.fixLabelKey(f)?.let { k -> keys.add(k) }
        for (s in ReadinessState.entries) keys.add(ReadinessPresentation.hintKey(s))
        for (lang in LanguageRegistry.all.map { it.code }) {
            val table = Loc.tableFor(lang)
            for (k in keys.distinct()) assertTrue("$lang: $k", table.containsKey(k))
        }
    }

    // ---------------- presentation logic ----------------

    @Test
    fun everyFix_HasALabel_AndALegalRouteOrRunsOnThePage() {
        for (f in ReadinessFix.entries.filter { it != ReadinessFix.None }) {
            assertNotNull(ReadinessPresentation.fixLabelKey(f))
            val route = ReadinessPresentation.fixRoute(f)
            if (f == ReadinessFix.TestAudio || f == ReadinessFix.TestTranscription || f == ReadinessFix.TestAi) {
                assertNull(route) // test tools run on the page
            } else {
                assertEquals(route!!.split('/')[0], HomeRoute.parse(route).page) // never falls back to Get started
            }
        }
        assertEquals("settings/audio", ReadinessPresentation.fixRoute(ReadinessFix.OpenAudioSettings))
        assertEquals("settings/window", ReadinessPresentation.fixRoute(ReadinessFix.OpenWindowSettings))
        assertEquals("account", ReadinessPresentation.fixRoute(ReadinessFix.SignIn))
        assertNull(ReadinessPresentation.fixLabelKey(ReadinessFix.None))
    }

    @Test
    fun startAndAutoRun_FollowTheState() {
        // (state, canStart, autoRun)
        val cases = listOf(
            Triple(ReadinessState.Ready, true, false),
            Triple(ReadinessState.ReadyWithWarnings, true, false),
            Triple(ReadinessState.NotReady, false, false),
            Triple(ReadinessState.Checking, false, false),
            Triple(ReadinessState.Outdated, false, true),
            Triple(ReadinessState.NotChecked, false, true),
        )
        for ((s, canStart, autoRun) in cases) {
            assertEquals(canStart, ReadinessPresentation.canStart(s))
            assertEquals(autoRun, ReadinessPresentation.shouldAutoRun(s))
        }
    }

    @Test
    fun outcomeBadges_ShareTheStateColours_AndAlwaysHaveAnIcon() {
        assertEquals("B.Listening", ReadinessSnapshot.brushKey(ReadinessPresentation.stateOf(CheckOutcome.Pass)))
        assertEquals("B.Thinking", ReadinessSnapshot.brushKey(ReadinessPresentation.stateOf(CheckOutcome.Warning)))
        assertEquals("B.Error", ReadinessSnapshot.brushKey(ReadinessPresentation.stateOf(CheckOutcome.Fail)))
        assertEquals(ReadinessState.NotChecked, ReadinessPresentation.stateOf(null))
        val icons = ReadinessState.entries.map { s -> ReadinessPresentation.icon(s) }
        for (i in icons) assertFalse(i.isEmpty())
        assertEquals(icons.size, icons.distinct().size) // the icon tells the states apart without colour
    }

    @Test
    fun summaryNumbers_BelongToWhatIsSelectedNow() {
        val s = run("a|t|d", ReadinessCheckId.WindowOnTop to CheckOutcome.Warning).snapshot
        assertEquals(7, ReadinessPresentation.shownPassed(s, "a|t|d"))
        assertEquals(0, ReadinessPresentation.shownPassed(s, "a|other|d")) // out of date: never another interview's numbers
        assertEquals(0, ReadinessPresentation.shownPassed(ReadinessSnapshot.empty, "a|t|d"))
    }

    @Test
    fun blockers_AreTheFailedChecks_AndTypedQuestionsStillWorkWhenOnlyConnectionChecksFail() {
        val s = run(
            "c",
            ReadinessCheckId.AccountPlan to CheckOutcome.Fail,
            ReadinessCheckId.Transcription to CheckOutcome.Fail,
            ReadinessCheckId.WindowOnTop to CheckOutcome.Warning,
        ).snapshot
        assertEquals(
            listOf(ReadinessCheckId.AccountPlan, ReadinessCheckId.Transcription),
            ReadinessPresentation.blockers(s).map { c -> c.id },
        )
        assertEquals(listOf(ReadinessCheckId.WindowOnTop), ReadinessPresentation.warnings(s).map { c -> c.id })
        assertTrue(ReadinessPresentation.typedQuestionsStillWork(s))
        val noPack = run("c", ReadinessCheckId.PreparedAnswers to CheckOutcome.Fail).snapshot
        assertFalse(ReadinessPresentation.typedQuestionsStillWork(noPack))
    }

    @Test
    fun transitionLog_StatesAndCountsOnly() {
        val s = run("user@example.com|target|Headset", ReadinessCheckId.AudioSignal to CheckOutcome.Warning).snapshot
        val line = ReadinessPresentation.transitionLog(ReadinessState.Checking, ReadinessState.ReadyWithWarnings, s)
        assertEquals("Readiness: Checking -> ReadyWithWarnings (7/8 passed)", line)
        assertFalse(line!!.contains("example.com")) // no personal data
        assertNull(ReadinessPresentation.transitionLog(ReadinessState.Ready, ReadinessState.Ready, s))
    }
}
