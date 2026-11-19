package com.interviewassistant.app.services

/**
 * Small platform seams the view models consume, in the same style as the P4-B/P4-C1 seams: the C# reads
 * these from the OS directly; the Android composition root supplies the platform implementations and the
 * unit tests supply fakes. (The two big seams — capture and updates — have their own files.)
 */

/**
 * Counterpart of `System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged`, which the C#
 * `MainViewModel.Initialize` subscribes to: the listener receives the new availability (`IsAvailable`).
 * The production implementation (ConnectivityManager) lives in the platform layer
 * (`com.interviewassistant.app.platform`); see P4-PORT-NOTES DC-93.
 */
interface INetworkAvailability {
    fun addAvailabilityChangedListener(listener: (Boolean) -> Unit)
    fun removeAvailabilityChangedListener(listener: (Boolean) -> Unit)
}

/** One process-resource sample for the diagnostics lines (the C# reads `System.Diagnostics.Process` + GC). */
data class ProcessStats(
    /** Total processor time of this process, milliseconds (the C#'s `TotalProcessorTime`). */
    val cpuTimeMs: Long,
    /** Working set of this process, bytes (the C#'s `WorkingSet64`). */
    val workingSetBytes: Long,
    /** Managed heap in use, bytes (the C#'s `GC.GetTotalMemory(false)`). */
    val managedBytes: Long,
    /** The C#'s `Environment.ProcessorCount`. */
    val processorCount: Int,
)

/** Supplies [ProcessStats]; the production provider lives in the platform layer (P4-PORT-NOTES DC-94). */
interface ProcessStatsProvider {
    fun sample(): ProcessStats
}
