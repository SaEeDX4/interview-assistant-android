package com.interviewassistant.app.ui

import com.interviewassistant.app.ui.components.InlineMessageStyle
import com.interviewassistant.app.ui.components.LevelMeterValue
import com.interviewassistant.app.ui.components.RovingKey
import com.interviewassistant.app.ui.components.RovingSelection
import com.interviewassistant.app.ui.components.ScrollbarGeometry
import com.interviewassistant.app.viewmodels.MessageKind
import androidx.compose.ui.semantics.LiveRegionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure component logic: the roving radio navigation (Views/RovingRadios.cs), the slim scrollbar geometry
 * (Themes/Theme.xaml ScrollBar), the level meter's screen-reader value (Views/Accessible.cs LevelMeter),
 * the info-tip name and the inline message's announcement rules (Views/InfoTip.cs, Views/InlineMessage.cs).
 */
class ComponentLogicTest {

    @Test
    fun roving_StepsFollowTheArrows() {
        assertEquals(1, RovingSelection.step(RovingKey.Down, rtl = false))
        assertEquals(1, RovingSelection.step(RovingKey.Right, rtl = false))
        assertEquals(-1, RovingSelection.step(RovingKey.Up, rtl = false))
        assertEquals(-1, RovingSelection.step(RovingKey.Left, rtl = false))
    }

    @Test
    fun roving_LeftRightMirrorInRtl_UpDownDoNot() {
        assertEquals(1, RovingSelection.step(RovingKey.Left, rtl = true))
        assertEquals(-1, RovingSelection.step(RovingKey.Right, rtl = true))
        assertEquals(1, RovingSelection.step(RovingKey.Down, rtl = true))
        assertEquals(-1, RovingSelection.step(RovingKey.Up, rtl = true))
    }

    @Test
    fun roving_NextWraps_AndAnUncheckedGroupStartsFromTheFirst() {
        assertEquals(1, RovingSelection.next(0, 3, RovingKey.Down, rtl = false))
        assertEquals(0, RovingSelection.next(2, 3, RovingKey.Down, rtl = false))
        assertEquals(2, RovingSelection.next(0, 3, RovingKey.Up, rtl = false))
        // C#: i = Max(0, FindIndex(checked)) — no checked item reads as index 0 before the step
        assertEquals(1, RovingSelection.next(-1, 3, RovingKey.Down, rtl = false))
        assertEquals(-1, RovingSelection.next(0, 0, RovingKey.Down, rtl = false))
    }

    @Test
    fun scrollbar_HiddenWhenEverythingFits() {
        assertNull(ScrollbarGeometry.thumb(viewport = 100f, content = 100f, scroll = 0f))
        assertNull(ScrollbarGeometry.thumb(viewport = 100f, content = 80f, scroll = 0f))
        assertNull(ScrollbarGeometry.thumb(viewport = 0f, content = 400f, scroll = 0f))
    }

    @Test
    fun scrollbar_ThumbCoversTheVisibleFraction_AndTravelsTheTrack() {
        val top = ScrollbarGeometry.thumb(viewport = 100f, content = 400f, scroll = 0f)!!
        assertEquals(0f, top.topFraction, 0.0001f)
        assertEquals(0.25f, top.heightFraction, 0.0001f)
        val mid = ScrollbarGeometry.thumb(viewport = 100f, content = 400f, scroll = 150f)!!
        assertEquals(0.375f, mid.topFraction, 0.0001f)
        val bottom = ScrollbarGeometry.thumb(viewport = 100f, content = 400f, scroll = 300f)!!
        assertEquals(0.75f, bottom.topFraction, 0.0001f)
        // an over-scrolled value clamps to the end of the track
        val clamped = ScrollbarGeometry.thumb(viewport = 100f, content = 400f, scroll = 999f)!!
        assertEquals(0.75f, clamped.topFraction, 0.0001f)
    }

    @Test
    fun levelMeterValue_IsTheRoundedClampedPercent() {
        assertEquals(0, LevelMeterValue.percent(0.0))
        assertEquals(100, LevelMeterValue.percent(1.0))
        assertEquals(50, LevelMeterValue.percent(0.5))
        assertEquals(33, LevelMeterValue.percent(0.333))
        assertEquals(100, LevelMeterValue.percent(1.4))
        assertEquals(0, LevelMeterValue.percent(-0.2))
    }

    @Test
    fun inlineMessage_AnnouncementAndLiveMode() {
        assertEquals("Broken", InlineMessageStyle.announcement("Broken", null))
        assertEquals("Broken", InlineMessageStyle.announcement("Broken", ""))
        assertEquals("Broken. Try again later", InlineMessageStyle.announcement("Broken", "Try again later"))
        assertEquals(LiveRegionMode.Assertive, InlineMessageStyle.liveMode(MessageKind.Error))
        assertEquals(LiveRegionMode.Polite, InlineMessageStyle.liveMode(MessageKind.Warning))
        assertEquals(LiveRegionMode.Polite, InlineMessageStyle.liveMode(MessageKind.Info))
        assertEquals(LiveRegionMode.Polite, InlineMessageStyle.liveMode(MessageKind.Success))
    }
}
