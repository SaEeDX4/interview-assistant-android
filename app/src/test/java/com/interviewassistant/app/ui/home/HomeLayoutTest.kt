package com.interviewassistant.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §3.3 layout decisions of the Home shell and its list+detail pages, as pure functions of
 * the available width (Package P4-E). The Compose rendering itself cannot run in CI (no
 * emulator); these are the decisions the rendering is built from.
 */
class HomeLayoutTest {

    @Test
    fun navForm_bottomBelow600_railFrom600() {
        assertEquals(NavForm.Bottom, HomeLayout.navForm(360f))
        assertEquals(NavForm.Bottom, HomeLayout.navForm(599f))
        assertEquals(NavForm.Rail, HomeLayout.navForm(600f))
        assertEquals(NavForm.Rail, HomeLayout.navForm(840f))
        assertEquals(NavForm.Rail, HomeLayout.navForm(1280f))
    }

    @Test
    fun paneMode_splitFrom720() {
        assertEquals(PaneMode.Split, HomeLayout.paneMode(720f, hasSelection = false))
        assertEquals(PaneMode.Split, HomeLayout.paneMode(720f, hasSelection = true))
        assertEquals(PaneMode.Split, HomeLayout.paneMode(1120f, hasSelection = true))
    }

    @Test
    fun paneMode_singlePaneShowsDetailOnlyWithSelection() {
        assertEquals(PaneMode.List, HomeLayout.paneMode(719f, hasSelection = false))
        assertEquals(PaneMode.Detail, HomeLayout.paneMode(719f, hasSelection = true))
        assertEquals(PaneMode.List, HomeLayout.paneMode(360f, hasSelection = false))
        assertEquals(PaneMode.Detail, HomeLayout.paneMode(360f, hasSelection = true))
    }

    @Test
    fun reportsShare_isTheStarShareAtTheDesignWidth() {
        // The C#'s content maximum (1120): 32*/68* with both panes above their minimums.
        assertEquals(0.32f, HomeLayout.reportsListShare(1120f), 0.001f)
        assertEquals(0.32f, HomeLayout.reportsListShare(1600f), 0.001f)
    }

    @Test
    fun reportsShare_staysWithinTheAssertedBand() {
        var width = 720f
        while (width <= 2400f) {
            val share = HomeLayout.reportsListShare(width)
            assertTrue("share $share at width $width", share in 0.2f..0.5f)
            width += 40f
        }
    }

    @Test
    fun reportsShare_listPaneNeverBelowItsMinimum() {
        // At 720 the 32% share (230) is under the 260 minimum, so the minimum wins: 260/720.
        assertEquals(260f / 720f, HomeLayout.reportsListShare(720f), 0.001f)
        // At 800 the minimum still wins over 256.
        assertEquals(260f / 800f, HomeLayout.reportsListShare(800f), 0.001f)
        // At 900 the star share (288) is above the minimum again.
        assertEquals(0.32f, HomeLayout.reportsListShare(900f), 0.001f)
    }
}
