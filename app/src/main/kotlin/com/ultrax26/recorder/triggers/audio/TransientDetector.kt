package com.ultrax26.recorder.triggers.audio

/** Counts quick repetitions (double clap, triple snap…) and reports the burst when it ends. */
class BurstCounter(private val maxGapMs: Long, private val maxCount: Int = 6, private val onBurst: (count: Int, endMs: Long) -> Unit) {
    private var count = 0
    private var lastMs = -1L

    fun hit(nowMs: Long) {
        if (lastMs >= 0 && nowMs - lastMs > maxGapMs) flush(lastMs)
        count++
        lastMs = nowMs
        if (count >= maxCount) flush(nowMs)
    }

    fun tick(nowMs: Long) { if (count > 0 && lastMs >= 0 && nowMs - lastMs > maxGapMs) flush(nowMs) }

    private fun flush(atMs: Long) { val c = count; count = 0; lastMs = -1L; if (c > 0) onBurst(c, atMs) }
    fun reset() { count = 0; lastMs = -1L }
}

/**
 * Detects short broadband impulses (hand claps, finger snaps) in mono float audio.
 *
 * Algorithm per hop (~10 ms): energy in dB vs. an adaptive background estimate, spectral centroid and
 * flatness from a 1024-point FFT. A candidate onset needs a sharp rise above the background that is
 * broadband (high flatness) and in the right band; it is confirmed only if the energy decays quickly
 * (impulsive) instead of sustaining (speech, music, wind).
 */
class TransientDetector(
    private val sampleRate: Int,
    private val profile: Profile,
    private val onHit: (timeMs: Long, peakDb: Float) -> Unit,
) {
    data class Profile(
        val riseDb: Float,          // onset must exceed background by this much
        val jumpDb: Float,          // and jump from the previous hop by at least this
        val centroidMinHz: Float,
        val centroidMaxHz: Float,
        val minFlatness: Float,
        val decayWindowMs: Long,    // must fall back by decayDb within this window
        val decayDb: Float,
        val refractoryMs: Long,
        val minAbsDbfs: Float,      // ignore very quiet impulses
    ) {
        companion object {
            fun clap(sensitivity: Float): Profile {
                val s = sensitivity.coerceIn(0f, 1f)
                return Profile(riseDb = 24f - 12f * s, jumpDb = 10f - 4f * s, centroidMinHz = 500f, centroidMaxHz = 9000f,
                    minFlatness = 0.22f - 0.08f * s, decayWindowMs = 160, decayDb = 9f, refractoryMs = 130, minAbsDbfs = -40f - 15f * s)
            }
            fun snap(sensitivity: Float): Profile {
                val s = sensitivity.coerceIn(0f, 1f)
                return Profile(riseDb = 20f - 10f * s, jumpDb = 9f - 4f * s, centroidMinHz = 2000f, centroidMaxHz = 14000f,
                    minFlatness = 0.18f - 0.06f * s, decayWindowMs = 90, decayDb = 8f, refractoryMs = 100, minAbsDbfs = -45f - 15f * s)
            }
        }
    }

    private val fftSize = 1024
    private val hop = sampleRate / 100 // 10 ms
    private val fft = Fft(fftSize)
    private val window = Dsp.hann(fftSize)
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var filled = 0
    private val mag = FloatArray(fftSize / 2 + 1)
    private val frame = FloatArray(fftSize)
    private var background = -60f
    private var prevDb = -120f
    private var hopSamples = 0
    private var sampleCount = 0L
    private var lastHitMs = -100000L

    // candidate onset state
    private var candidateMs = -1L
    private var candidateDb = 0f
    private var minSinceCandidate = 0f

    val backgroundDb: Float get() = background

    fun process(mono: FloatArray, n: Int, startMs: Long) {
        for (i in 0 until n) {
            ring[ringPos] = mono[i]
            ringPos = (ringPos + 1) % fftSize
            if (filled < fftSize) filled++
            hopSamples++
            sampleCount++
            if (hopSamples >= hop) { hopSamples = 0; analyze(startMs + (i * 1000L) / sampleRate) }
        }
    }

    private fun analyze(nowMs: Long) {
        // Copy ring (oldest→newest) into frame.
        var p = ringPos
        for (i in 0 until fftSize) { frame[i] = ring[p]; p = (p + 1) % fftSize }
        val hopStart = fftSize - hop
        var e = 0.0
        for (i in hopStart until fftSize) e += frame[i].toDouble() * frame[i]
        val db = Dsp.dbfs(Math.sqrt(e / hop))

        // Adaptive background: quick to fall, slow to rise.
        background = if (db < background) background * 0.90f + db * 0.10f else background * 0.995f + db * 0.005f

        // Pending candidate: confirm on quick decay, reject on sustain.
        if (candidateMs >= 0) {
            if (db < minSinceCandidate) minSinceCandidate = db
            val age = nowMs - candidateMs
            if (candidateDb - minSinceCandidate >= profile.decayDb) {
                candidateMs = -1
                if (nowMs - lastHitMs >= profile.refractoryMs) { lastHitMs = nowMs; onHit(nowMs, candidateDb) }
            } else if (age > profile.decayWindowMs) {
                candidateMs = -1 // sustained sound, not an impulse
            }
        } else if (filled >= fftSize && db > profile.minAbsDbfs && db - background >= profile.riseDb && db - prevDb >= profile.jumpDb && nowMs - lastHitMs >= profile.refractoryMs) {
            fft.magnitudes(frame, window, mag)
            val binHz = sampleRate.toFloat() / fftSize
            val nBins = fftSize / 2 + 1
            val centroid = Dsp.spectralCentroid(mag, nBins, binHz)
            val flat = Dsp.spectralFlatness(mag, (300f / binHz).toInt().coerceAtLeast(1), (10000f / binHz).toInt().coerceAtMost(nBins - 1))
            if (centroid in profile.centroidMinHz..profile.centroidMaxHz && flat >= profile.minFlatness) {
                candidateMs = nowMs; candidateDb = db; minSinceCandidate = db
            }
        }
        prevDb = db
    }

    fun reset() { candidateMs = -1; prevDb = -120f; background = -60f; filled = 0 }

}
