package com.ultrax26.recorder.recording

/**
 * Sits between the microphone capture and the recording's audio encoder and holds the PCM back by a
 * configurable delay. When a rule fires on a sound (clap, snap, whistle, loud noise, spoken command)
 * the controller marks that time range; samples inside it are silenced (or ducked) with short fades
 * *before* they reach the encoder, so the trigger cannot be heard in the recording. Timestamps are
 * CLOCK_BOOTTIME nanoseconds, the same clock the trigger events use, and are passed through unchanged
 * so audio/video sync is unaffected — only the moment the audio is written moves.
 */
class AudioScrubber(private val downstream: AudioCapture.Listener, delayMs: Int, @Volatile var mode: Mode = Mode.MUTE) : AudioCapture.Listener {
    enum class Mode { MUTE, DUCK }

    private val delayNs = delayMs.coerceAtLeast(0) * 1_000_000L
    private val queue = ArrayDeque<PcmChunk>()
    private val ranges = ArrayList<LongArray>()   // [startNs, endNs]
    private val lock = Any()
    @Volatile var latestPtsNs = 0L
        private set
    @Volatile var mutedRanges = 0
        private set

    override fun onPcm(chunk: PcmChunk) {
        val out = ArrayList<PcmChunk>(2)
        synchronized(lock) {
            queue.addLast(chunk)
            latestPtsNs = endOf(chunk)
            while (queue.isNotEmpty()) {
                val c = queue.first()
                if (latestPtsNs - endOf(c) < delayNs) break
                queue.removeFirst(); out += apply(c)
            }
            // forget ranges that can no longer touch anything still queued
            val horizon = latestPtsNs - delayNs - 2_000_000_000L
            ranges.removeAll { it[1] < horizon }
        }
        out.forEach(downstream::onPcm)
    }

    /** Silence [startNs, endNs). Safe to call from any thread; ranges may precede chunks already queued. */
    fun mute(startNs: Long, endNs: Long) {
        if (endNs <= startNs) return
        synchronized(lock) { ranges += longArrayOf(startNs, endNs); mutedRanges++ }
    }

    /** Deliver everything that is being held back (call before the encoder is stopped or paused). */
    fun flush() {
        val out: List<PcmChunk>
        synchronized(lock) { out = queue.map { apply(it) }; queue.clear() }
        out.forEach(downstream::onPcm)
    }

    fun clear() = synchronized(lock) { queue.clear(); ranges.clear() }

    val queuedChunks: Int get() = synchronized(lock) { queue.size }

    private fun endOf(c: PcmChunk) = c.ptsNs + c.frames * 1_000_000_000L / c.sampleRate

    private fun apply(c: PcmChunk): PcmChunk {
        val endNs = endOf(c)
        val hits = ranges.filter { it[0] < endNs && it[1] > c.ptsNs }
        if (hits.isEmpty()) return c
        val samples = c.samples.copyOf()
        val ch = c.channels
        val fade = (c.sampleRate * FADE_MS / 1000).coerceAtLeast(1)
        val floor = if (mode == Mode.MUTE) 0f else DUCK_GAIN
        for (r in hits) {
            val f0 = frameAt(r[0], c)
            val f1 = frameAt(r[1], c)
            val lo = (f0 - fade).coerceAtLeast(0)
            val hi = (f1 + fade).coerceAtMost(c.frames)
            for (f in lo until hi) {
                val g = when {
                    f < f0 -> floor + (1f - floor) * (f0 - f).toFloat() / fade
                    f >= f1 -> floor + (1f - floor) * (f - f1 + 1).toFloat() / fade
                    else -> floor
                }
                val base = f * ch
                for (k in 0 until ch) samples[base + k] = (samples[base + k] * g).toInt().toShort()
            }
        }
        return PcmChunk(samples, c.frames, ch, c.sampleRate, c.ptsNs, if (floor == 0f) 0f else c.peak * floor)
    }

    private fun frameAt(ns: Long, c: PcmChunk): Int = (((ns - c.ptsNs) * c.sampleRate) / 1_000_000_000L).toInt().coerceIn(0, c.frames)

    companion object {
        const val FADE_MS = 12
        const val DUCK_GAIN = 0.0316f   // −30 dB
    }
}
