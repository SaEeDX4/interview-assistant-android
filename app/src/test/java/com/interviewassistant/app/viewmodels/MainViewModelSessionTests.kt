package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.AppPaths
import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.providers.ConnectionStatus
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Session-flow checks: the lease flow against fake transports (refusal copy, the device_unknown
 * re-register retry, a full start → stop with report), BuildEngine's refusal during a session, and
 * the connection-gate integration (service outage + relay transitions, driven on the gate's fake
 * clock through the VM's own diagnostics tick).
 */
class MainViewModelSessionTests {
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

    /** A signed-in VM, initialized, with the fixture bank loaded and listening allowed. */
    private fun signedInVm(cloud: VmCloudFixture): VmFixture {
        val f = VmFixture(cloud)
        fixture = f
        cloud.settings.cloudDeviceId = UUID.fromString(VM_DEVICE_ID)
        cloud.settings.authorizedUseAcknowledged = true
        f.vm.initialize()
        f.vm.useKnowledge(FixtureKnowledge.build(), "Fixture · Product Owner · Teroxx")
        return f
    }

    // Lease refusal: the note is the refusal's own copy (never a raw error), listening never starts.
    @Test
    fun leaseRefused_AllowanceExhausted_NoteIsLeaseCopy() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.sessionStart = { _, _ -> 402 to """{"code":"allowance_exhausted","message":"no minutes left"}""" }
        val f = signedInVm(cloud)
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        assertEquals(Loc.t("lease_allowance"), f.vm.note)
    }

    @Test
    fun leaseRefused_DailyCap_NoteIsLeaseCopy() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.sessionStart = { _, _ -> 402 to """{"code":"daily_live_cap","message":"cap reached"}""" }
        val f = signedInVm(cloud)
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        assertEquals(Loc.t("lease_daily_cap"), f.vm.note)
    }

    // Lease refusal with a generic failure: the typed-still-work note wraps the service description.
    @Test
    fun leaseRefused_Generic_NoteIsTypedStillWork() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        cloud.sessionStart = { _, _ -> 400 to """{"code":"weird_failure","message":"something odd"}""" }
        val f = signedInVm(cloud)
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        val note = f.vm.note ?: ""
        assertTrue(note.startsWith("something odd") || note.contains("Typed questions still work"))
        assertTrue(note.contains("Typed questions still work"))
    }

    // device_unknown: the stale local device id is re-registered once and the lease retried (C# StartLeaseAsync).
    @Test
    fun lease_DeviceUnknown_ReRegistersAndRetries() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        var starts = 0
        cloud.sessionStart = { _, _ ->
            starts++
            if (starts == 1) 400 to """{"code":"device_unknown","message":"unknown device"}"""
            else 200 to vmSessionStartJson
        }
        val f = signedInVm(cloud)
        f.vm.toggleListeningAsync()
        assertTrue(f.vm.isListening)
        assertEquals(2, starts)
        assertTrue(cloud.apiFake.countCalls { isApi(it, "devices") && it.method == "POST" } >= 1)
        f.vm.stopAsync()
    }

    // Full session: start → BuildEngine refused → stop → idle, report saved, transcript withheld.
    @Test
    fun session_StartStop_IdleReportSavedTranscriptWithheld() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.vm.submitManualAsync("How would you increase XAB adoption?")
        assertEquals(3, f.vm.bullets.size)

        f.vm.toggleListeningAsync()
        assertTrue(f.vm.isListening)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_listening") && it.second == ToastKind.Success })

        // BuildEngine refused during the session: the engine instance must not change.
        val engineDuring = f.vm.engine
        f.vm.buildEngine()
        assertSame(engineDuring, f.vm.engine)

        f.vm.stopAsync()
        assertFalse(f.vm.isListening)
        assertFalse(f.vm.isPaused)

        // Report: viewer copy exists with the honesty labelling, the encrypted store copy is non-empty,
        // and the ReportSaved event fired with the session id.
        val path = f.vm.lastReportPath
        assertNotNull(path)
        val html = File(path!!).readText(Charsets.UTF_8)
        assertTrue(html.contains("microphone was OFF"))
        assertTrue(html.contains("Content-Security-Policy"))
        val stored = f.vm.workspace!!.store.sessions()
        assertEquals(1, stored.size)
        assertTrue(stored[0].reportJson.isNotEmpty())
        assertEquals(listOf(stored[0].id), f.reportSaved)

        // SaveSessionTranscript=false (the default): no plain transcript file was written.
        val sessionFiles = File(AppPaths.sessions).listFiles()?.filter { it.name.endsWith(".md") } ?: emptyList()
        assertTrue("no transcript expected, found $sessionFiles", sessionFiles.isEmpty())
    }

    // Pause ends the lease and says so; resume starts a fresh one.
    @Test
    fun session_Pause_StopsListeningAndToasts() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.vm.toggleListeningAsync()
        assertTrue(f.vm.isListening)
        f.toasts.clear()
        f.vm.toggleListeningAsync()
        assertFalse(f.vm.isListening)
        assertTrue(f.vm.isPaused)
        assertTrue(f.toasts.any { it.first == Loc.t("toast_paused") })
        f.vm.stopAsync()
    }

    // ---------------- connection gate integration ----------------

    @Test
    fun connectionGate_ServiceOutage_LostThenRestored() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.cloud.service.refreshAsync()
        f.toasts.clear()

        val base = cloud.apiFake.handler
        cloud.apiFake.handler = { req, body ->
            if (isApi(req, "config")) throw IOException("network down") else base(req, body)
        }
        f.cloud.service.refreshAsync() // → ServiceUnavailable → the gate hears "down" (grace: no toast yet)
        assertTrue(f.toasts.none { it.first == Loc.t("toast_connection_lost_reconnecting") })

        f.gateNow = f.gateNow.plusSeconds(4)
        f.vm.onDiagTick() // the 1 s tick polls the gate: the delayed "lost" fires
        val lost = f.toasts.filter { it.first == Loc.t("toast_connection_lost_reconnecting") }
        assertEquals(1, lost.size)
        assertEquals(ToastKind.Warning, lost[0].second)

        cloud.apiFake.handler = base
        f.gateNow = f.gateNow.plusSeconds(11) // past the ~10 s minimum interval
        f.cloud.service.refreshAsync() // → SignedIn → "restored"
        f.vm.onDiagTick()
        val restored = f.toasts.filter { it.first == Loc.t("toast_connection_restored") }
        assertEquals(1, restored.size)
        assertEquals(ToastKind.Success, restored[0].second)
    }

    @Test
    fun connectionGate_RelayTransitions_LostThenRestored() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.cloud.service.refreshAsync()
        f.toasts.clear()

        val engine = f.vm.engine!!
        engine.transcriberConnectionChangedListeners.forEach { it(ConnectionStatus.Reconnecting) }
        f.gateNow = f.gateNow.plusSeconds(4)
        f.vm.onDiagTick()
        assertEquals(1, f.toasts.count { it.first == Loc.t("toast_connection_lost_reconnecting") })

        f.gateNow = f.gateNow.plusSeconds(11)
        engine.transcriberConnectionChangedListeners.forEach { it(ConnectionStatus.Connected) }
        f.vm.onDiagTick()
        assertEquals(1, f.toasts.count { it.first == Loc.t("toast_connection_restored") })
    }

    // A short blip inside the grace window never produces a toast.
    @Test
    fun connectionGate_ShortBlip_NoToast() = runBlocking {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.cloud.service.refreshAsync()
        f.toasts.clear()

        val engine = f.vm.engine!!
        engine.transcriberConnectionChangedListeners.forEach { it(ConnectionStatus.Reconnecting) }
        f.gateNow = f.gateNow.plusSeconds(1) // inside the 3 s grace
        engine.transcriberConnectionChangedListeners.forEach { it(ConnectionStatus.Connected) }
        f.gateNow = f.gateNow.plusSeconds(30)
        f.vm.onDiagTick()
        assertTrue(f.toasts.none {
            it.first == Loc.t("toast_connection_lost_reconnecting") || it.first == Loc.t("toast_connection_restored")
        })
    }

    // The update timer tick reaches the update service with the session state.
    @Test
    fun updateTick_CarriesSessionActive() {
        val cloud = VmCloudFixture(session = vmSession())
        val f = signedInVm(cloud)
        f.vm.onUpdateTick()
        assertEquals(listOf(false), f.updates.tickCalls)
    }
}
