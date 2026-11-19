package com.interviewassistant.app.uistate

import com.interviewassistant.core.providers.ConnectionStatus
import java.time.Duration
import java.time.OffsetDateTime

/** Mirror of `InterviewAssistant.App/UiState/ConnectionToastGate.cs`. */

enum class ConnectionToast { None, Lost, Restored }

/**
 * Decides when the "Connection lost — reconnecting…" / "Connection restored" notifications appear, so that network
 * blips never turn into notification noise:
 * - "lost" only after the connection has stayed down for [grace] (a blip under ~3 s shows nothing);
 * - "restored" only after a "lost" was actually shown (never a lone "restored");
 * - at most one lost/restored pair per [minInterval]: a new outage soon after the previous "lost"
 *   waits until that interval has passed, and shows nothing if it is over by then.
 *
 * The owner reports every state change with [report] and calls [poll] periodically while
 * [needsPolling] is true (the delayed "lost" is decided there). Time comes from an injected clock.
 */
class ConnectionToastGate(
    private val now: () -> OffsetDateTime,
    grace: Duration? = null,
    minInterval: Duration? = null,
) {
    val grace: Duration = grace ?: defaultGrace
    val minInterval: Duration = minInterval ?: defaultMinInterval

    private var downSince: OffsetDateTime? = null
    private var lastLostShown: OffsetDateTime? = null
    private var lostShown: Boolean = false

    var isConnected: Boolean = true
        private set

    /** A "lost" notification is pending (down, not shown yet): call [poll] again soon. */
    val needsPolling: Boolean get() = !isConnected && !lostShown

    /**
     * The connection state changed (or was re-confirmed). [silent]: the connection no longer matters
     * (interview stopped, signed out) — forget the outage without announcing a recovery that did not happen.
     */
    fun report(connected: Boolean, silent: Boolean = false): ConnectionToast {
        if (connected == isConnected) return poll()
        isConnected = connected
        if (!connected) {
            downSince = now()
            return poll()
        }
        downSince = null
        val wasShown = lostShown
        lostShown = false
        return if (wasShown && !silent) ConnectionToast.Restored else ConnectionToast.None
    }

    /** Shows "lost" once the outage has lasted the grace period (and the rate limit allows it). */
    fun poll(): ConnectionToast {
        val since = downSince
        if (isConnected || lostShown || since == null) return ConnectionToast.None
        val n = now()
        if (Duration.between(since, n) < grace) return ConnectionToast.None
        val last = lastLostShown
        if (last != null && Duration.between(last, n) < minInterval) return ConnectionToast.None
        lostShown = true
        lastLostShown = n
        return ConnectionToast.Lost
    }

    companion object {
        val defaultGrace: Duration = Duration.ofSeconds(3)
        val defaultMinInterval: Duration = Duration.ofSeconds(10)
    }
}

/** The (up, silent) pair of a relay transition. */
data class RelayTransition(val up: Boolean, val silent: Boolean)

/** Maps the live relay's own connection state onto the toast gate (not the status line, which answers overwrite). */
object RelayConnection {
    /**
     * New up/down state for a transcriber event, or null when nothing changes. Reconnecting = down; Connected = up
     * (may show "restored"); Failed/Disconnected while down = the session ended: up again, silently (never "restored").
     */
    fun transition(currentlyUp: Boolean, status: ConnectionStatus): RelayTransition? = when {
        status == ConnectionStatus.Reconnecting && currentlyUp -> RelayTransition(false, false)
        status == ConnectionStatus.Connected && !currentlyUp -> RelayTransition(true, false)
        (status == ConnectionStatus.Failed || status == ConnectionStatus.Disconnected) && !currentlyUp -> RelayTransition(true, true)
        else -> null
    }
}
