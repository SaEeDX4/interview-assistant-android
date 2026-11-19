package com.interviewassistant.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Debug
import android.os.Process
import com.interviewassistant.app.services.INetworkAvailability
import com.interviewassistant.app.services.ProcessStats
import com.interviewassistant.app.services.ProcessStatsProvider

/**
 * Platform implementations of the small seams the view models consume (P4-PORT-NOTES DC-93/DC-94),
 * supplied by the composition root. The big seams (capture, updates) land in P5/P6.
 */

/**
 * [INetworkAvailability] over `ConnectivityManager`: `onAvailable` reports true, losing the last
 * network reports false — the `NetworkChange.NetworkAvailabilityChanged` counterpart.
 */
class AndroidNetworkAvailability(context: Context) : INetworkAvailability {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun addAvailabilityChangedListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
            if (callback == null) {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = fire(true)
                    override fun onLost(network: Network) = fire(false)
                }
                callback = cb
                connectivityManager.registerDefaultNetworkCallback(cb)
            }
        }
    }

    override fun removeAvailabilityChangedListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
            if (listeners.isEmpty()) {
                callback?.let { connectivityManager.unregisterNetworkCallback(it) }
                callback = null
            }
        }
    }

    private fun fire(available: Boolean) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) listener(available)
    }
}

/**
 * [ProcessStatsProvider] over `android.os.Process` / `Debug` / `Runtime`: elapsed CPU time of this
 * process, its PSS as the working-set counterpart, the ART heap in use as the managed-memory
 * counterpart, and the device's processor count.
 */
class AndroidProcessStats : ProcessStatsProvider {
    override fun sample(): ProcessStats {
        val runtime = Runtime.getRuntime()
        return ProcessStats(
            cpuTimeMs = Process.getElapsedCpuTime(),
            workingSetBytes = Debug.getPss() * 1024L,
            managedBytes = runtime.totalMemory() - runtime.freeMemory(),
            processorCount = runtime.availableProcessors(),
        )
    }
}
