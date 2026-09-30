package com.ultrax26.recorder.camera

import android.hardware.camera2.params.TonemapCurve
import com.ultrax26.recorder.settings.TonemapPreset
import kotlin.math.ln
import kotlin.math.pow

/** Builds TONEMAP_CURVE values for the presets the app offers on top of the device's own curves. */
object Tonemaps {

    fun curve(preset: TonemapPreset, gamma: Float, custom: List<Float>, maxPoints: Int): TonemapCurve? {
        val n = maxPoints.coerceIn(2, 64)
        val pts = when (preset) {
            TonemapPreset.LINEAR -> floatArrayOf(0f, 0f, 1f, 1f)
            TonemapPreset.SRGB -> sample(n) { srgb(it) }
            TonemapPreset.REC709 -> sample(n) { rec709(it) }
            TonemapPreset.FLAT_LOG -> sample(n) { flatLog(it) }
            TonemapPreset.GAMMA -> sample(n) { it.toDouble().pow(1.0 / gamma.toDouble().coerceIn(0.2, 6.0)).toFloat() }
            TonemapPreset.CUSTOM -> if (custom.size >= 4 && custom.size % 2 == 0) custom.toFloatArray() else return null
            TonemapPreset.DEVICE -> return null
        }
        return TonemapCurve(pts, pts.copyOf(), pts.copyOf())
    }

    private fun sample(n: Int, f: (Float) -> Float): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i / (n - 1).toFloat()
            out[i * 2] = x
            out[i * 2 + 1] = f(x).coerceIn(0f, 1f)
        }
        return out
    }

    fun srgb(x: Float): Float = if (x <= 0.0031308f) 12.92f * x else (1.055 * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    fun rec709(x: Float): Float = if (x < 0.018f) 4.5f * x else (1.099 * x.toDouble().pow(0.45) - 0.099).toFloat()

    /** Lifted-shadow, compressed-highlight log-like curve intended for grading (not a vendor log spec). */
    fun flatLog(x: Float, k: Double = 40.0): Float = (ln(1.0 + k * x) / ln(1.0 + k)).toFloat()
}
