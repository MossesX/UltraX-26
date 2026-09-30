package com.ultrax26.recorder.util

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

object Maths {
    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Long, lo: Long, hi: Long): Long = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun dbfs(rms: Double): Double = if (rms <= 1e-9) -120.0 else 20.0 * log10(rms)
    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    /** Exposure time in ns -> "1/250" style string. */
    fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "—"
        val s = ns / 1e9
        return if (s >= 0.5) String.format("%.1fs", s) else "1/${(1.0 / s).roundToInt()}"
    }

    /** Log-scale mapping helper: 0..1 slider position <-> [lo, hi] (both > 0). */
    fun logToRange(t: Float, lo: Double, hi: Double): Double = lo * (hi / lo).pow(t.toDouble())
    fun rangeToLog(v: Double, lo: Double, hi: Double): Float =
        if (hi <= lo || v <= lo) 0f else (ln(v / lo) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)

    fun approxEqual(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps
}
