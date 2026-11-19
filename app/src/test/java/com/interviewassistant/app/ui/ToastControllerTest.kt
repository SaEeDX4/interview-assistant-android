package com.interviewassistant.app.ui

import com.interviewassistant.core.languages.Loc
import com.interviewassistant.app.ui.components.IaGlyph
import com.interviewassistant.app.ui.components.ToastController
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.ConnectionToast
import com.interviewassistant.app.uistate.UiStateLocFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Port of the ToastHost rules of Views/ToastHost.cs (stack max 3, 4 s de-duplication, the duration table,
 * fade-out dismissal, Escape = newest, relocalization, the connection pair) against the pure controller.
 */
class ToastControllerTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installTables() {
            UiStateLocFixtures.installLocTables()
            Loc.language = "en"
        }
    }

    @Test
    fun blankText_IsIgnored() {
        val c = ToastController()
        c.show("", ToastKind.Info, nowMs = 0)
        c.show("   ", ToastKind.Info, nowMs = 0)
        assertTrue(c.visible(0).isEmpty())
    }

    @Test
    fun announcement_JoinsDetailWithPeriod() {
        val c = ToastController()
        c.show("Saved", ToastKind.Success, nowMs = 0)
        c.show("Prepared", ToastKind.Success, detail = "12 questions", nowMs = 10)
        assertEquals(listOf("Saved", "Prepared. 12 questions"), c.visibleTexts(20))
    }

    @Test
    fun duplicateText_WithinFourSeconds_IsShownOnce_ThenAllowedAgain() {
        val c = ToastController()
        c.show("Heads up", ToastKind.Warning, nowMs = 0)
        c.show("Heads up", ToastKind.Warning, nowMs = 3999)
        assertEquals(1, c.visible(3999).size)
        // the recent entry is pruned once it is older than 4 s (the first toast is still inside its 5500 ms)
        c.show("Heads up", ToastKind.Warning, nowMs = 4001)
        assertEquals(2, c.visible(4001).size)
    }

    @Test
    fun fourthToast_RemovesTheOldestImmediately() {
        val c = ToastController()
        c.show("one", ToastKind.Info, nowMs = 0)
        c.show("two", ToastKind.Info, nowMs = 100)
        c.show("three", ToastKind.Info, nowMs = 200)
        c.show("four", ToastKind.Info, nowMs = 300)
        assertEquals(listOf("two", "three", "four"), c.visibleTexts(300))
    }

    @Test
    fun durations_FollowTheKindTable() {
        assertEquals(7000, ToastController.defaultDurationMs(ToastKind.Error, null))
        assertEquals(7000, ToastController.defaultDurationMs(ToastKind.Error, "detail"))
        assertEquals(5500, ToastController.defaultDurationMs(ToastKind.Warning, null))
        assertEquals(3200, ToastController.defaultDurationMs(ToastKind.Info, null))
        assertEquals(4500, ToastController.defaultDurationMs(ToastKind.Info, "detail"))
        assertEquals(3200, ToastController.defaultDurationMs(ToastKind.Success, null))
        assertEquals(4500, ToastController.defaultDurationMs(ToastKind.Success, "detail"))
    }

    @Test
    fun explicitMilliseconds_WinOverTheTable() {
        val c = ToastController()
        c.show("quick", ToastKind.Error, milliseconds = 1000, nowMs = 0)
        assertEquals(ToastController.Phase.Shown, c.visible(999).single().phase)
        assertEquals(ToastController.Phase.Dismissing, c.visible(1000).single().phase)
    }

    @Test
    fun expiry_StartsTheFade_AndRemovalFollowsAfter160ms() {
        val c = ToastController()
        c.show("note", ToastKind.Info, nowMs = 0) // plain: 3200 ms
        assertEquals(ToastController.Phase.Shown, c.visible(3199).single().phase)
        assertEquals(ToastController.Phase.Dismissing, c.visible(3200).single().phase)
        assertEquals(1, c.visible(3359).size)
        assertTrue(c.visible(3360).isEmpty())
    }

    @Test
    fun escape_DismissesTheNewest_SkippingDismissingOnes() {
        val c = ToastController()
        c.show("a", ToastKind.Info, nowMs = 0)
        c.show("b", ToastKind.Info, nowMs = 10)
        c.show("c", ToastKind.Info, nowMs = 20)
        assertTrue(c.dismissNewest(30))
        assertEquals(ToastController.Phase.Dismissing, c.visible(30).last().phase)
        assertTrue(c.dismissNewest(40))
        assertEquals("b", c.visible(40)[1].text)
        assertEquals(ToastController.Phase.Dismissing, c.visible(40)[1].phase)
        assertTrue(c.dismissNewest(50))
        assertFalse(c.dismissNewest(60))
    }

    @Test
    fun clickDismiss_ById_AndUnknownIdIsIgnored() {
        val c = ToastController()
        c.show("a", ToastKind.Info, nowMs = 0)
        val id = c.visible(0).single().id
        c.dismiss(id + 99, 10)
        assertEquals(ToastController.Phase.Shown, c.visible(10).single().phase)
        c.dismiss(id, 10)
        assertEquals(ToastController.Phase.Dismissing, c.visible(10).single().phase)
        c.dismiss(id, 20) // already dismissing: no-op
        assertEquals(1, c.visible(20).size)
    }

    @Test
    fun relocalize_RewritesVisibleTexts_KeepsUntranslatableOnes() {
        val c = ToastController()
        c.show("Hello", ToastKind.Info, detail = "World", nowMs = 0)
        c.relocalize { mapOf("Hello" to "Bonjour")[it] }
        assertEquals(listOf("Bonjour. World"), c.visibleTexts(0))
    }

    @Test
    fun errorsAnnounceAssertively_OthersDoNot() {
        val c = ToastController()
        c.show("bad", ToastKind.Error, nowMs = 0)
        c.show("ok", ToastKind.Success, nowMs = 10)
        assertTrue(c.visible(20).first { it.text == "bad" }.assertive)
        assertFalse(c.visible(20).first { it.text == "ok" }.assertive)
    }

    @Test
    fun glyphs_FollowTheKindSwitch() {
        assertEquals(IaGlyph.Check, ToastController.glyph(ToastKind.Success))
        assertEquals(IaGlyph.Warning, ToastController.glyph(ToastKind.Warning))
        assertEquals(IaGlyph.Error, ToastController.glyph(ToastKind.Error))
        assertEquals(IaGlyph.Info, ToastController.glyph(ToastKind.Info))
    }

    @Test
    fun connectionToasts_UseTheMainViewModelWording() {
        val c = ToastController()
        c.showConnection(ConnectionToast.None, nowMs = 0)
        assertTrue(c.visible(0).isEmpty())
        c.showConnection(ConnectionToast.Lost, nowMs = 0)
        val lost = c.visible(0).single()
        assertEquals(Loc.t("toast_connection_lost_reconnecting"), lost.text)
        assertEquals(Loc.t("toast_connection_lost_detail"), lost.detail)
        assertEquals(ToastKind.Warning, lost.kind)
        c.showConnection(ConnectionToast.Restored, nowMs = 100)
        val restored = c.visible(100).first { it.kind == ToastKind.Success }
        assertEquals(Loc.t("toast_connection_restored"), restored.text)
        assertEquals(Loc.t("toast_connection_restored_detail"), restored.detail)
    }
}
