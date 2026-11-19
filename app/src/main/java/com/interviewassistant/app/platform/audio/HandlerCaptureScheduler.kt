package com.interviewassistant.app.platform.audio

import android.os.Handler
import android.os.HandlerThread
import com.interviewassistant.app.services.AppLog

/**
 * [CaptureScheduler] on a dedicated HandlerThread (the C#'s `Task.Delay` continuations and its
 * 100 ms `System.Threading.Timer`, off the UI thread and off the capture read thread). A failing
 * task is logged and never kills the scheduler — the C#'s timer callbacks cannot fault the app
 * either.
 */
class HandlerCaptureScheduler : CaptureScheduler {
    private val thread = HandlerThread("AudioCaptureScheduler").apply { start() }
    private val handler = Handler(thread.looper)

    override fun scheduleOnce(delayMs: Long, task: () -> Unit): CaptureTask {
        val runnable = Runnable { runSafely(task) }
        handler.postDelayed(runnable, delayMs)
        return object : CaptureTask {
            override fun cancel() {
                handler.removeCallbacks(runnable)
            }
        }
    }

    override fun schedulePeriodic(periodMs: Long, task: () -> Unit): CaptureTask {
        val handle = object : CaptureTask {
            @Volatile var cancelled: Boolean = false
            override fun cancel() {
                cancelled = true
            }
        }
        val runnable = object : Runnable {
            override fun run() {
                if (handle.cancelled) return
                runSafely(task)
                if (!handle.cancelled) handler.postDelayed(this, periodMs)
            }
        }
        handler.postDelayed(runnable, periodMs)
        return handle
    }

    private fun runSafely(task: () -> Unit) {
        try {
            task()
        } catch (ex: Exception) {
            AppLog.error("Capture scheduler task failed", ex)
        }
    }
}
