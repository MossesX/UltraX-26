package com.ultrax26.recorder.util

import android.util.Log

/**
 * Tiny logging facade so the pure-logic classes (DSP, trigger engine) never touch android.util.Log
 * directly and stay testable on a plain JVM.
 */
object UxLog {
    const val TAG = "UltraX26"
    @Volatile var enabled: Boolean = true
    @Volatile var sink: ((level: Int, tag: String, msg: String, t: Throwable?) -> Unit)? = null

    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Log.ERROR, tag, msg, t)

    private fun log(level: Int, tag: String, msg: String, t: Throwable?) {
        if (!enabled) return
        val s = sink
        if (s != null) { s(level, tag, msg, t); return }
        try {
            val full = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
            Log.println(level, "$TAG/$tag", full)
        } catch (_: Throwable) {
            // Unit tests on the JVM: android.util.Log is a stub.
        }
    }
}
