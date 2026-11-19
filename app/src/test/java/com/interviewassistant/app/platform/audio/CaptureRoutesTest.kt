package com.interviewassistant.app.platform.audio

import com.interviewassistant.app.uistate.UiStateLocFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * P5: the capture-route model behind `AudioCaptureService.ListDevices()` (TRANSFER-MAP §3.2
 * row 11, §8 D1). The list shape mirrors the C#'s: system default first, present routes in a
 * fixed order, the AudioPlaybackCapture source last.
 */
class CaptureRoutesTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun installLoc() {
            UiStateLocFixtures.installLocTables()
        }
    }

    private fun info(kind: CaptureRoutes.RouteKind, name: String) = CaptureRoutes.RouteInfo(kind, name)

    @Test
    fun list_SystemDefaultFirst_ThenPresentRoutesInKindOrder_ThenPlayback() {
        val list = CaptureRoutes.buildList(
            listOf(
                info(CaptureRoutes.RouteKind.Bluetooth, "BT Headset"),
                info(CaptureRoutes.RouteKind.Builtin, "Built-in Mic"),
            ),
        )
        assertEquals(4, list.size)
        assertNull(list[0].id) // system default, as in the C#
        assertEquals(CaptureRoutes.ID_BUILTIN, list[1].id)
        assertEquals("Built-in Mic", list[1].name)
        assertEquals(CaptureRoutes.ID_BLUETOOTH, list[2].id)
        assertEquals(CaptureRoutes.ID_PLAYBACK, list[3].id)
    }

    @Test
    fun list_NoRoutesPresent_StillOffersDefaultAndPlayback() {
        val list = CaptureRoutes.buildList(emptyList())
        assertEquals(2, list.size)
        assertNull(list[0].id)
        assertEquals(CaptureRoutes.ID_PLAYBACK, list[1].id)
    }

    @Test
    fun resolve_NullAndForeignIds_FollowTheDefault() {
        val present = setOf(CaptureRoutes.RouteKind.Builtin)
        assertEquals(CaptureRoutes.Resolved.Default, CaptureRoutes.resolve(null, present))
        // A Windows WASAPI endpoint GUID in a copied settings.json: ignored, as the C# ignores
        // an id that does not name a capture source of this platform.
        assertEquals(
            CaptureRoutes.Resolved.Default,
            CaptureRoutes.resolve("{0.0.1.00000000}.{12345678-1234-1234-1234-1234567890ab}", present),
        )
        assertEquals(CaptureRoutes.Resolved.Default, CaptureRoutes.resolve("nonsense", present))
    }

    @Test
    fun resolve_PresentRoute_AndPlayback() {
        val present = setOf(CaptureRoutes.RouteKind.Wired)
        assertEquals(
            CaptureRoutes.Resolved.Route(CaptureRoutes.RouteKind.Wired),
            CaptureRoutes.resolve(CaptureRoutes.ID_WIRED, present),
        )
        assertEquals(
            CaptureRoutes.Resolved.Playback,
            CaptureRoutes.resolve(CaptureRoutes.ID_PLAYBACK, present),
        )
    }

    @Test
    fun resolve_KnownRouteAbsent_IsAbsent() {
        val resolved = CaptureRoutes.resolve(CaptureRoutes.ID_BLUETOOTH, setOf(CaptureRoutes.RouteKind.Builtin))
        assertTrue(resolved is CaptureRoutes.Resolved.Absent)
        assertEquals(CaptureRoutes.ID_BLUETOOTH, (resolved as CaptureRoutes.Resolved.Absent).id)
    }
}
