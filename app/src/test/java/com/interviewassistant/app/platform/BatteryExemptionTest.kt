package com.interviewassistant.app.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BatteryExemption] (D12): the ask-once decision over the installed platform halves.
 * The object is process-global, so every test installs its own halves and the
 * teardown restores the pristine state.
 */
class BatteryExemptionTest {

    private var exempt = false
    private var askCount = 0

    @Before
    fun setUp() {
        BatteryExemption.resetForTest()
        BatteryExemption.probe = { exempt }
        BatteryExemption.requester = { askCount++ }
    }

    @After
    fun tearDown() {
        BatteryExemption.probe = null
        BatteryExemption.requester = null
        BatteryExemption.resetForTest()
    }

    @Test
    fun state_ReflectsTheProbe() {
        exempt = true
        assertEquals(BatteryExemption.State.Exempt, BatteryExemption.state())
        exempt = false
        assertEquals(BatteryExemption.State.NotExempt, BatteryExemption.state())
    }

    @Test
    fun requestIfNeeded_WhenExempt_NeverAsks() {
        exempt = true
        assertFalse(BatteryExemption.requestIfNeeded())
        assertEquals(0, askCount)
    }

    @Test
    fun requestIfNeeded_WhenNotExempt_AsksExactlyOncePerProcess() {
        exempt = false
        assertTrue(BatteryExemption.requestIfNeeded())
        assertEquals(1, askCount)
        assertFalse(BatteryExemption.requestIfNeeded())
        assertEquals(1, askCount)
    }
}
