package com.ultrax26.recorder.triggers.audio

import kotlin.math.abs

/**
 * Detects a sustained near-pure tone (a whistle) between 500 Hz and 5 kHz. Reports once when the tone
 * has lasted [minDurationMs]; re-arms after the tone ends.
 */
class WhistleDetector(
    private val sampleRate: Int,
    sensitivity: Float,
    private val minDurationMs: Long,
    private val onWhistle: (timeMs: Long, durationMs: Long, freqHz: Float) -> Unit,
) {
    private val fftSize = 2048
    private val hop = sampleRate / 100
    private val fft = Fft(fftSize)
    private val window = Dsp.hann(fftSize)
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var filled = 0
    private val frame = FloatArray(fftSize)
    private val mag = FloatArray(fftSize / 2 + 1)
    private var hopSamples = 0
    private val prominenceDb = 22f - 10f * sensitivity.coerceIn(0f, 1f)
    private val minLevelDbfs = -50f - 10f * sensitivity.coerceIn(0f, 1f)

    private var toneStartMs = -1L
    private var toneFreq = 0f
    private var reported = false
    private var missHops = 0

    val active: Boolean get() = toneStartMs >= 0
    val currentFreq: Float get() = if (active) toneFreq else 0f

    fun process(mono: FloatArray, n: Int, startMs: Long) {
        for (i in 0 until n) {
            ring[ringPos] = mono[i]; ringPos = (ringPos + 1) % fftSize
            if (filled < fftSize) filled++
            if (++hopSamples >= hop) { hopSamples = 0; if (filled >= fftSize) analyze(startMs + i * 1000L / sampleRate) }
        }
    }

    private fun analyze(nowMs: Long) {
        var p = ringPos
        for (i in 0 until fftSize) { frame[i] = ring[p]; p = (p + 1) % fftSize }
        fft.magnitudes(frame, window, mag)
        val binHz = sampleRate.toFloat() / fftSize
        val lo = (500f / binHz).toInt(); val hi = (5000f / binHz).toInt()
        var peakK = lo; var peakV = 0f; var sum = 0.0
        for (k in lo..hi) { val v = mag[k]; sum += v; if (v > peakV) { peakV = v; peakK = k } }
        val mean = ((sum - peakV) / (hi - lo).coerceAtLeast(1)).toFloat()
        val prominence = if (mean <= 1e-6f) 0f else 20f * kotlin.math.log10(peakV / mean)
        // level of the peak relative to full scale (rough: |X[k]| / (N/2))
        val levelDb = Dsp.dbfs((peakV / (fftSize / 2)).toDouble())
        // Harmonic check: a whistle has a weak 2nd harmonic compared to the fundamental.
        val h2 = if (peakK * 2 <= fftSize / 2) mag[peakK * 2] else 0f
        val pure = h2 < peakV * 0.5f
        val freq = peakK * binHz
        val isTone = prominence >= prominenceDb && levelDb >= minLevelDbfs && pure

        if (isTone && (toneStartMs < 0 || abs(freq - toneFreq) / toneFreq < 0.08f)) {
            if (toneStartMs < 0) { toneStartMs = nowMs; toneFreq = freq; reported = false }
            else toneFreq = toneFreq * 0.8f + freq * 0.2f
            missHops = 0
            if (!reported && nowMs - toneStartMs >= minDurationMs) { reported = true; onWhistle(nowMs, nowMs - toneStartMs, toneFreq) }
        } else if (toneStartMs >= 0) {
            if (++missHops > 8) { toneStartMs = -1; missHops = 0 } // ~80 ms gap ends the tone
        }
    }
}

/** Fires when the peak level exceeds a threshold (with a 1 s refractory period). */
class LoudnessDetector(private val thresholdDbfs: Float, private val onLoud: (timeMs: Long, peakDb: Float) -> Unit) {
    private var lastMs = -100000L
    fun process(peakDbfs: Float, nowMs: Long) {
        if (peakDbfs >= thresholdDbfs && nowMs - lastMs > 1000) { lastMs = nowMs; onLoud(nowMs, peakDbfs) }
    }
}
