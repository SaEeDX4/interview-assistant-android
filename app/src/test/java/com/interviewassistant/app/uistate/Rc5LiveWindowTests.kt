package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.LanguageRegistry
import com.interviewassistant.core.languages.Loc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the Windows `Rc5LiveWindowTests.cs` — the JVM-portable cases (Live chrome rule, Interview Focus
 * geometry, context-aware Quick help, the in-app health check).
 * Deferred by name (WPF source wiring; counterparts in P4-E/G): LiveToolbar_HasNoDiagnosticsButton_NoDiagPanel_NoOldPreInterviewIcon,
 * QuickHelp_IsACompactPopover_WithoutDontShowAgain, LiveWindow_MaximizeRestore_WorkAreaHook_ReadableColumn,
 * HelpPage_HasADiagnosticsSection_AndTheHealthCheckNeverLaunchesTheCiSelfTest.
 */
class Rc5LiveWindowTests {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() = UiStateLocFixtures.installLocTables()

        private val hk = QuickHelpHotkeys("Ctrl+Alt+L", "Ctrl+Alt+Q", "Ctrl+Alt+Space", "Ctrl+Alt+C")
    }

    // ---------------- LiveChrome ----------------

    @Test
    fun liveChrome_SetupMode_ShowsSetupAffordances_InterviewMode_CollapsesToTheDot() {
        val setup = LiveChrome.`for`(sessionActive = false, compact = false, width = 760.0)
        assertEquals(ReadinessIndicator.Label, setup.readiness)
        assertTrue(setup.setupEmptyState)
        assertTrue(setup.homeLink)

        val interview = LiveChrome.`for`(sessionActive = true, compact = false, width = 760.0)
        assertEquals(ReadinessIndicator.Dot, interview.readiness)
        assertFalse(interview.setupEmptyState)
        assertFalse(interview.homeLink)
        assertTrue(interview.startLabel) // interview controls stay
    }

    @Test
    fun liveChrome_MinimumWidth_KeepsOnlyPrimaryItems_OverflowTakesTheRest() {
        val narrow = LiveChrome.`for`(false, false, LiveChrome.minWidth)
        assertEquals(ReadinessIndicator.Dot, narrow.readiness)
        assertFalse(narrow.startLabel)
        assertFalse(narrow.adaptiveBadge)
        assertFalse(narrow.presentationChip)
        assertFalse(narrow.minutesLabel)
        assertTrue(narrow.presentationInMore)
        assertTrue(narrow.minutesInMore)

        val wide = LiveChrome.`for`(true, false, 1600.0)
        assertTrue(wide.presentationChip && wide.minutesLabel && wide.adaptiveBadge && wide.startLabel)
        assertFalse(wide.presentationInMore || wide.minutesInMore)
    }

    @Test
    fun liveChrome_CompactView_IsMinimal() {
        val c = LiveChrome.`for`(false, compact = true, width = 1600.0)
        assertEquals(ReadinessIndicator.Dot, c.readiness)
        assertFalse(c.presentationChip || c.minutesLabel || c.adaptiveBadge || c.homeLink)
    }

    @Test
    fun liveChrome_GrowingWidth_NeverHidesAnItem() {
        for ((session, compact) in listOf(false to false, true to false, false to true, true to true)) {
            var previous: LiveChromeLayout? = null
            var w = LiveChrome.minWidth
            while (w <= 2000) {
                val c = LiveChrome.`for`(session, compact, w)
                val p = previous
                if (p != null) {
                    assertTrue("start label at $w", !p.startLabel || c.startLabel)
                    assertTrue("chip at $w", !p.presentationChip || c.presentationChip)
                    assertTrue("minutes at $w", !p.minutesLabel || c.minutesLabel)
                    assertTrue("readiness at $w", p.readiness == ReadinessIndicator.Dot || c.readiness == ReadinessIndicator.Label)
                }
                previous = c
                w += 10
            }
        }
    }

    // ---------------- Interview Focus ----------------

    @Test
    fun focusPlacement_EveryPreset_InsideTheWorkArea_AtTheTop_NeverMoreThan40Percent() {
        // (name, left, top, width, height)
        val workAreas = listOf(
            arrayOf("1366x768", 0.0, 0.0, 1366.0, 728.0),
            arrayOf("1920x1080", 0.0, 0.0, 1920.0, 1040.0),
            arrayOf("4K at 100 %", 0.0, 0.0, 3840.0, 2120.0),
            arrayOf("4K at 150 %", 0.0, 0.0, 2560.0, 1400.0),
            arrayOf("left of the primary", -1920.0, 0.0, 1920.0, 1040.0),
            arrayOf("above, shifted", -640.0, -1080.0, 1920.0, 1040.0),
            arrayOf("small 1280x720 at 200 %", 0.0, 0.0, 640.0, 340.0),
        )
        for (wa in workAreas) {
            val name = wa[0] as String
            val width = wa[3] as Double; val height = wa[4] as Double
            val work = PlacementRect(wa[1] as Double, wa[2] as Double, width, height)
            for (preset in FocusPreset.entries) {
                val r = FocusPlacement.compute(work, preset)
                assertTrue("$name $preset: $r outside $work", work.contains(r))
                assertEquals("$name $preset top", work.top, r.top, 0.0)
                // 40 % cap, with the window's own minimum size as the floor
                assertTrue("$name $preset: width ${r.width}", r.width <= max(width * FocusPlacement.maxShare, LiveChrome.minWidth) + 0.01)
                assertTrue("$name $preset: height ${r.height}", r.height <= max(height * FocusPlacement.maxShare, 260.0) + 0.01)
                assertTrue(
                    "$name $preset: below the minimum size",
                    r.width >= min(LiveChrome.minWidth, width) - 0.01 && r.height >= min(260.0, height) - 0.01,
                )
            }
        }
    }

    @Test
    fun focusPlacement_1366x768_IsCappedAt40Percent_AndCentredUnderTheWebcam() {
        val work = PlacementRect(0.0, 0.0, 1366.0, 728.0)
        val r = FocusPlacement.compute(work, FocusPreset.TopCentre)
        assertTrue(r.toString(), r.width <= 1366 * 0.4 && r.height <= 728 * 0.4)
        assertTrue("centred", abs((r.left + r.width / 2) - 683) <= 1)
        assertEquals(0.0, r.top, 0.0)
    }

    @Test
    fun focusPlacement_LargeScreens_UseTheRecommendedSize_CornersKeepAGap() {
        val work = PlacementRect(0.0, 0.0, 3840.0, 2120.0)
        val centre = FocusPlacement.compute(work, FocusPreset.TopCentre)
        assertEquals(FocusPlacement.recommendedWidth, centre.width, 0.0)
        assertEquals(FocusPlacement.recommendedHeight, centre.height, 0.0)
        assertEquals(FocusPlacement.edgeGap, FocusPlacement.compute(work, FocusPreset.TopLeft).left, 0.0)
        assertEquals(work.right - FocusPlacement.edgeGap, FocusPlacement.compute(work, FocusPreset.TopRight).right, 0.0)
    }

    @Test
    fun focusPlacement_NegativeCoordinateMonitor_StaysOnThatMonitor() {
        val work = PlacementRect(-1920.0, -200.0, 1920.0, 1040.0)
        val left = FocusPlacement.compute(work, FocusPreset.TopLeft)
        val right = FocusPlacement.compute(work, FocusPreset.TopRight)
        assertEquals(-1920 + FocusPlacement.edgeGap, left.left, 0.0)
        assertEquals(-200.0, left.top, 0.0)
        assertTrue(right.toString(), right.right <= 0 && right.left > -1920)
        val centred = FocusPlacement.compute(work, FocusPreset.TopCentre).left + 280
        assertTrue(centred > -961 && centred < -959)
    }

    @Test
    fun focusPreset_IsRememberedAsText_UnknownFallsBackToTopCentre() {
        for (p in FocusPreset.entries) assertEquals(p, FocusPlacement.parse(p.toString()))
        assertEquals(FocusPreset.TopCentre, FocusPlacement.parse(null))
        assertEquals(FocusPreset.TopCentre, FocusPlacement.parse("Sideways"))
        assertEquals(FocusPreset.TopCentre, FocusPlacement.parse("42"))
    }

    // ---------------- Quick help ----------------

    @Test
    fun quickHelp_StatePrecedence_ASessionWinsOverTheSignIn() {
        assertEquals(QuickHelpState.SignedOut, QuickHelp.stateFor(signInRequired = true, listening = false, paused = false))
        assertEquals(QuickHelpState.Setup, QuickHelp.stateFor(false, false, false))
        assertEquals(QuickHelpState.Listening, QuickHelp.stateFor(true, listening = true, paused = false))
        assertEquals(QuickHelpState.Paused, QuickHelp.stateFor(true, false, paused = true))
    }

    @Test
    fun quickHelp_ContentFollowsTheContext_WithTheConfiguredHotkeys() {
        val signedOut = QuickHelp.`for`(QuickHelpState.SignedOut, answerShown = true, hk)
        assertEquals("qh_title_signed_out", signedOut.sections[0].titleKey)
        assertEquals(1, signedOut.sections.size) // no answer tips without an account
        assertEquals(QuickHelpAction.SignIn, signedOut.action)
        assertEquals("account", QuickHelp.actionRoute(signedOut.action))

        val setup = QuickHelp.`for`(QuickHelpState.Setup, false, hk)
        assertTrue(setup.sections[0].lineKeys.contains("qh_setup_1"))
        assertEquals("preinterview", QuickHelp.actionRoute(setup.action))
        assertTrue(setup.hotkeys.any { h -> h.labelKey == "qh_hk_start" && h.keys == "Ctrl+Alt+L" })

        val custom = QuickHelp.`for`(QuickHelpState.Listening, false, hk.copy(startPause = "Ctrl+Shift+F9"))
        assertTrue(custom.hotkeys.any { h -> h.labelKey == "qh_hk_start" && h.keys == "Ctrl+Shift+F9" }) // the user's own hotkey
        assertEquals(QuickHelpAction.None, custom.action)

        val paused = QuickHelp.`for`(QuickHelpState.Paused, answerShown = true, hk)
        assertEquals(listOf("qh_title_paused", "qh_title_answer"), paused.sections.map { s -> s.titleKey })
        assertTrue(QuickHelp.`for`(QuickHelpState.Listening, false, hk).sections.none { s -> s.titleKey == "qh_title_answer" })
        assertTrue(QuickHelp.`for`(QuickHelpState.Setup, false, hk.copy(manualInput = "")).hotkeys.none { h -> h.labelKey == "qh_hk_type" })
    }

    @Test
    fun quickHelp_FocusAndHealthCheck_TextsExist_AndAreTranslated() {
        val keys = (
            QuickHelpState.entries.flatMap { s -> QuickHelp.`for`(s, true, hk).keys } +
                FocusPreset.entries.map { p -> FocusPlacement.key(p) } +
                (HealthCheck.evaluate(HealthFacts(1, ServiceReach.Reachable, true, true)) +
                    HealthCheck.evaluate(HealthFacts(0, ServiceReach.Unreachable, false, false)))
                    .flatMap { r -> listOf(r.labelKey, r.detailKey) } +
                listOf(
                    "live_rd_tt", "live_rd_tt_run", "tt_more", "more_compact", "more_coach", "more_focus", "more_home", "more_last_report",
                    "help_diag", "help_diag_hint", "help_diag_copy", "help_diag_run", "help_diag_result", "hc_pass", "hc_warn", "qh_shortcuts", "qh_full_help",
                    "focus_title_first", "focus_body_first", "focus_title", "focus_body", "focus_apply", "focus_restore", "live_empty_setup", "live_open_home",
                ) +
                ReadinessState.entries.map { s -> ReadinessSnapshot.stateKey(s) }
            ).distinct()
        val en = Loc.tableFor("en")
        for (lang in LanguageRegistry.all.map { it.code }) {
            val t = Loc.tableFor(lang)
            for (k in keys) {
                val v = t[k]
                assertTrue("$lang: $k missing", v != null && v.trim().isNotEmpty())
                if (lang != "en" && k != "settings" && k != "sign_in") assertTrue("$lang: $k not translated", en[k] != v)
            }
        }
        // honest framing: Interview Focus never claims to hide the window
        assertTrue(en["focus_body_first"]!!.contains("does not hide it from screen sharing"))
        assertFalse(en["focus_body_first"]!!.contains("invisible", ignoreCase = true))
        assertEquals("Ready when you are — start listening when the interview begins.", en["live_empty_setup"])
    }

    // ---------------- health check ----------------

    @Test
    fun healthCheck_PassAndWarnRows_NeverFail() {
        val ok = HealthCheck.evaluate(HealthFacts(2, ServiceReach.Reachable, true, true))
        assertEquals(4, ok.size)
        assertEquals(4, HealthCheck.passed(ok))
        val bad = HealthCheck.evaluate(HealthFacts(0, ServiceReach.Unreachable, false, false))
        for (r in bad) assertEquals(HealthOutcome.Warn, r.outcome)
        assertEquals(
            listOf(HealthCheckId.AudioDevice, HealthCheckId.Service, HealthCheckId.Settings, HealthCheckId.Disk),
            bad.map { r -> r.id },
        )
        assertEquals(HealthOutcome.Pass, HealthCheck.evaluate(HealthFacts(1, ServiceReach.DeveloperMode, true, true))[1].outcome)
        assertEquals(HealthOutcome.Warn, HealthCheck.evaluate(HealthFacts(1, ServiceReach.NotConfigured, true, true))[1].outcome)
        assertEquals(1.3, HealthCheck.answerSeconds(1260, 900)!!, 0.0)
        assertEquals(0.9, HealthCheck.answerSeconds(-1, 900)!!, 0.0) // typed question: no speech end
        assertNull(HealthCheck.answerSeconds(0, 0))
    }
}
