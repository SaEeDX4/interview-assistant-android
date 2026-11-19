package com.interviewassistant.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P4-G: the slider snap math the Settings Listening/Window panels apply (the values are stored
 * exactly on the tick grid — VAD 0.6–1.8 step 0.1, opacity 0.75–1.0 step 0.05 — as the C#'s
 * tick-snapping sliders store them).
 */
class SliderSnapTests {

    @Test
    fun snap_RoundsToTheNearestTick() {
        assertEquals(0.7, SliderSnap.snap(0.65, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(0.6, SliderSnap.snap(0.64, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(1.3, SliderSnap.snap(1.26, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(0.75, SliderSnap.snap(0.77, 0.75, 1.0, 0.05), 1e-9)
        assertEquals(0.8, SliderSnap.snap(0.78, 0.75, 1.0, 0.05), 1e-9)
        assertEquals(1.0, SliderSnap.snap(0.99, 0.75, 1.0, 0.05), 1e-9)
    }

    @Test
    fun snap_ClampsToTheRange() {
        assertEquals(0.6, SliderSnap.snap(0.5, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(1.8, SliderSnap.snap(2.0, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(0.75, SliderSnap.snap(0.0, 0.75, 1.0, 0.05), 1e-9)
        assertEquals(1.0, SliderSnap.snap(1.5, 0.75, 1.0, 0.05), 1e-9)
    }

    @Test
    fun snap_KeepsTickValuesExact() {
        assertEquals(1.0, SliderSnap.snap(1.0, 0.6, 1.8, 0.1), 1e-9)
        assertEquals(0.9, SliderSnap.snap(0.9, 0.75, 1.0, 0.05), 1e-9)
    }
}
