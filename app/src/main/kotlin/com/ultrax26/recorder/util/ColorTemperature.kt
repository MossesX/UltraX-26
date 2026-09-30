package com.ultrax26.recorder.util

import kotlin.math.ln
import kotlin.math.pow

/**
 * Correlated color temperature -> RGB channel gains for manual white balance
 * (used with COLOR_CORRECTION_MODE_TRANSFORM_MATRIX when AWB is OFF).
 *
 * Based on Tanner Helland's blackbody approximation, normalized so green = 1.0 and inverted
 * (a warm scene needs *less* red gain). `tint` (-1..1) skews green vs magenta.
 */
object ColorTemperature {
    data class Rgb(val r: Float, val g: Float, val b: Float)

    fun blackbodyRgb(kelvin: Int): Rgb {
        val t = kelvin.coerceIn(1000, 40000) / 100.0
        val r: Double = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g: Double = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b: Double = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
        return Rgb((r / 255.0).coerceIn(0.0, 1.0).toFloat(), (g / 255.0).coerceIn(0.05, 1.0).toFloat(), (b / 255.0).coerceIn(0.05, 1.0).toFloat())
    }

    /** Gains [R, G_even, G_odd, B] to neutralize illumination of the given temperature. */
    fun gainsForKelvin(kelvin: Int, tint: Float = 0f): FloatArray {
        val c = blackbodyRgb(kelvin)
        // Neutralize: gain = 1 / channel, normalized to green.
        var r = c.g / c.r
        var b = c.g / c.b
        var g = 1f
        // Tint: positive = magenta (less green), negative = green.
        g *= (1f - 0.25f * tint.coerceIn(-1f, 1f))
        val minGain = minOf(r, g, b)
        r /= minGain; g /= minGain; b /= minGain
        return floatArrayOf(r.coerceIn(1f, 8f), g.coerceIn(1f, 8f), g.coerceIn(1f, 8f), b.coerceIn(1f, 8f))
    }
}
