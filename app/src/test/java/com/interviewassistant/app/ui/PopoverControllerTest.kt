package com.interviewassistant.app.ui

import com.interviewassistant.app.ui.components.PopoverController
import com.interviewassistant.app.ui.components.PopoverTapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the closing rules of Views/Popover.cs against the pure [PopoverController]. */
class PopoverControllerTest {

    @Test
    fun openCloseToggle_FollowTheState() {
        val p = PopoverController()
        assertFalse(p.isOpen)
        p.open(); assertTrue(p.isOpen)
        p.open(); assertTrue(p.isOpen) // opening an open popover is a no-op
        p.toggle(); assertFalse(p.isOpen)
        p.toggle(); assertTrue(p.isOpen)
        p.close(); assertFalse(p.isOpen)
    }

    @Test
    fun closedListener_FiresOncePerClose_WhateverClosedIt() {
        val p = PopoverController()
        var closed = 0
        p.addClosedListener { closed++ }
        p.close() // not open: no event
        assertEquals(0, closed)
        p.open(); p.close()
        assertEquals(1, closed)
        p.open(); p.onEscape()
        assertEquals(2, closed)
        p.open(); p.onWindowGeometryChanged()
        assertEquals(3, closed)
    }

    @Test
    fun escape_ClosesAndRestoresFocus_WhenClosedItIsNotHandled() {
        val p = PopoverController()
        assertFalse(p.onEscape())
        p.open()
        assertTrue(p.onEscape())
        assertFalse(p.isOpen)
        assertEquals(1, p.focusRestoreRequests)
    }

    @Test
    fun plainClose_RestoresFocus_SilentClosesDoNot() {
        val p = PopoverController()
        p.open(); p.close() // default: restoreFocus = true (a close button)
        assertEquals(1, p.focusRestoreRequests)
        p.open(); p.close(restoreFocus = false)
        assertEquals(1, p.focusRestoreRequests)
    }

    @Test
    fun windowTap_AnchorAndContentStay_ElsewhereClosesSilently() {
        val p = PopoverController()
        p.open()
        p.onWindowTap(PopoverTapTarget.Anchor)
        assertTrue(p.isOpen)
        p.onWindowTap(PopoverTapTarget.Content)
        assertTrue(p.isOpen)
        p.onWindowTap(PopoverTapTarget.Elsewhere)
        assertFalse(p.isOpen)
        assertEquals(0, p.focusRestoreRequests)
    }

    @Test
    fun deactivation_ClosesOnlyWhenFocusIsOutside() {
        val p = PopoverController()
        p.open()
        p.onWindowDeactivated(keyboardFocusWithin = true)
        assertTrue(p.isOpen)
        p.onWindowDeactivated(keyboardFocusWithin = false)
        assertFalse(p.isOpen)
        assertEquals(0, p.focusRestoreRequests)
    }

    @Test
    fun moveResizeStateChange_CloseSilently() {
        val p = PopoverController()
        p.open()
        p.onWindowGeometryChanged()
        assertFalse(p.isOpen)
        assertEquals(0, p.focusRestoreRequests)
    }
}
