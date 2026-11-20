package com.interviewassistant.app.platform

/**
 * The battery-optimisation exemption flow (TRANSFER-MAP §8 D12; deferred from P5, built in
 * P6 — P6-PORT-NOTES DC-188): an aggressive OEM battery killer can stop the capture
 * foreground service mid-interview, a failure Windows never has. The standard system flow
 * asks the user for the exemption; this object owns the decision (ask at most once per
 * process, only when not already exempt) and the activity installs the platform halves:
 * the exemption probe (PowerManager) and the requester (the system intent). MainActivity
 * triggers the request when a session first becomes active — the honest point on the
 * readiness path, never at app start.
 */
object BatteryExemption {
    enum class State { Exempt, NotExempt }

    /** The platform probe: true when the app already ignores battery optimisations. */
    var probe: (() -> Boolean)? = null

    /** The platform request: shows the standard system exemption prompt. */
    var requester: (() -> Unit)? = null

    private var requestedThisProcess: Boolean = false

    fun state(): State = if (probe?.invoke() == true) State.Exempt else State.NotExempt

    /**
     * Asks for the exemption when it is missing and has not been asked for in this process.
     * True when the prompt was actually shown.
     */
    fun requestIfNeeded(): Boolean {
        if (requestedThisProcess) return false
        if (state() == State.Exempt) return false
        val request = requester ?: return false
        requestedThisProcess = true
        request()
        return true
    }

    /** Test seam: forget that this process already asked. */
    fun resetForTest() {
        requestedThisProcess = false
    }
}
