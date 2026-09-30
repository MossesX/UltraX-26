package com.ultrax26.recorder.util

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor

/** A HandlerThread paired with its Handler and an Executor view, for Camera2/MediaCodec callbacks. */
class WorkerThread(name: String, priority: Int = android.os.Process.THREAD_PRIORITY_DEFAULT) {
    val thread: HandlerThread = HandlerThread(name, priority).also { it.start() }
    val handler: Handler = Handler(thread.looper)
    val executor: Executor = Executor { r -> handler.post(r) }

    fun post(r: () -> Unit) { handler.post(r) }
    fun postDelayed(delayMs: Long, r: () -> Unit) { handler.postDelayed(r, delayMs) }
    fun quit() { thread.quitSafely() }
}

/** Monotonic clock helpers. Camera and audio timestamps are aligned to CLOCK_BOOTTIME. */
object Clock {
    fun bootNs(): Long = android.os.SystemClock.elapsedRealtimeNanos()
    fun bootMs(): Long = android.os.SystemClock.elapsedRealtime()
    fun wallMs(): Long = System.currentTimeMillis()
}
