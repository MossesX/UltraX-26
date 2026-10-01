package com.ultrax26.recorder.triggers.audio

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One enrolled utterance: a sequence of normalized MFCC(+delta) vectors. */
@Serializable
data class KeywordTemplate(val command: String, val frames: List<FloatArray>)

@Serializable
data class KeywordTemplates(val templates: List<KeywordTemplate> = emptyList())

/**
 * Speaker-trained, fully offline keyword spotter (MFCC + dynamic time warping).
 *
 * Why this exists: the system SpeechRecognizer runs in another process and Android's concurrent
 * microphone rules can starve it while we record. This spotter consumes the *same* PCM stream as the
 * recording, so voice control keeps working during a take. It recognizes short phrases the user
 * enrolled in their own voice (3+ samples each recommended).
 *
 * Pipeline: 48 kHz → 16 kHz, 25 ms frames / 10 ms hop, pre-emphasis, Hamming, 512-pt FFT, 26 mel
 * bands (300–8000 Hz), log, DCT → 13 MFCC (c0 replaced by log-energy) + deltas, per-utterance
 * mean/variance normalization; energy VAD segments utterances; DTW (Sakoe–Chiba band) against templates.
 */
class KeywordSpotter(
    private val inputSampleRate: Int,
    var sensitivity: Float,
    var relativeMargin: Float,
    private val minUtteranceMs: Long,
    private val maxUtteranceMs: Long,
    private val silenceMs: Long,
    private val onResult: (Result) -> Unit,
) {
    data class Result(val command: String?, val score: Float, val secondScore: Float, val timeMs: Long, val durationMs: Long, val enrolled: Boolean)

    companion object {
        const val FS = 16000
        const val FRAME = 400      // 25 ms
        const val HOP = 160        // 10 ms
        const val NFFT = 512
        const val NMEL = 26
        const val NCEP = 13
        const val DIMS = NCEP * 2
        private const val PRE_FRAMES = 12

        fun thresholdFor(sensitivity: Float): Float = 0.85f + 1.1f * sensitivity.coerceIn(0f, 1f)
    }

    private val decim = FloatArray(4096)
    private val pending = FloatArray(FRAME * 4)
    private var pendingN = 0
    private val window = Dsp.hamming(FRAME)
    private val fft = Fft(NFFT)
    private val mag = FloatArray(NFFT / 2 + 1)
    private val mel = Dsp.melFilterbank(NMEL, NFFT, FS, 300f, 8000f)
    private val frameBuf = FloatArray(NFFT)
    private val logMel = FloatArray(NMEL)

    // VAD
    private var noiseFloorDb = -55f
    private var inSpeech = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private var utterStartMs = 0L
    private val utterance = ArrayList<FloatArray>()
    private val preBuffer = ArrayDeque<FloatArray>()
    private var frameTimeMs = 0L
    private var onsetCandidates = 0

    @Volatile var templates: List<KeywordTemplate> = emptyList()
    @Volatile var enrollingCommand: String? = null
    @Volatile var speechActive: Boolean = false
        private set
    @Volatile var lastResult: Result? = null
        private set

    val commands: List<String> get() = templates.map { it.command }.distinct()

    // ---- manual enrollment: the user taps Record, speaks, taps Stop & save. No VAD dependence. ----
    @Volatile var manualCapture: Boolean = false
        private set
    private val manualFrames = ArrayList<FloatArray>()
    private val manualEnergy = ArrayList<Float>()
    @Volatile var manualMs: Long = 0L
        private set
    @Volatile var manualMaxDb: Float = -120f
        private set
    @Volatile var manualLevelDb: Float = -120f
        private set

    fun beginManualEnrollment(command: String) {
        synchronized(manualFrames) { manualFrames.clear(); manualEnergy.clear() }
        enrollingCommand = command; manualMs = 0L; manualMaxDb = -120f; manualLevelDb = -120f
        manualCapture = true
    }

    fun cancelManualEnrollment() { manualCapture = false; enrollingCommand = null; synchronized(manualFrames) { manualFrames.clear(); manualEnergy.clear() } }

    /**
     * Stops the manual recording, trims silence around the spoken part and stores it as a template.
     * Returns null on success, otherwise a message explaining what went wrong.
     */
    fun finishManualEnrollment(): String? {
        manualCapture = false
        val cmd = enrollingCommand ?: return "No command selected"
        enrollingCommand = null
        val frames: List<FloatArray>; val energy: List<Float>
        synchronized(manualFrames) { frames = manualFrames.toList(); energy = manualEnergy.toList(); manualFrames.clear(); manualEnergy.clear() }
        if (frames.isEmpty()) return "No audio was captured — is the microphone running?"
        val maxDb = energy.max()
        if (maxDb < -60f) return "Only silence reached the app (peak ${maxDb.toInt()} dBFS). Another app or the system speech recognizer may be holding the microphone — turn the system recognizer off under Voice ▸ Engines and try again."
        val floor = maxOf(maxDb - 28f, -70f)
        var first = energy.indexOfFirst { it > floor }
        var last = energy.indexOfLast { it > floor }
        if (first < 0 || last < first) return "Could not find the spoken part of the recording"
        first = (first - 8).coerceAtLeast(0); last = (last + 8).coerceAtMost(frames.size - 1)
        val seg = frames.subList(first, last + 1)
        if (seg.size < 8) return "Too short — keep recording while you say the whole phrase"
        if (seg.size > 800) return "Too long — keep one sample under 8 seconds"
        templates = templates + KeywordTemplate(cmd, normalize(withDeltas(seg)))
        val r = Result(cmd, 0f, 0f, frameTimeMs, seg.size * 10L, enrolled = true)
        lastResult = r; onResult(r)
        return null
    }

    fun process(mono: FloatArray, n: Int, startMs: Long) {
        val m = if (inputSampleRate == 48000) Dsp.decimateBy3(mono, n, decim) else {
            // generic nearest-neighbour resample
            val ratio = inputSampleRate.toDouble() / FS
            var o = 0; var pos = 0.0
            while (pos < n && o < decim.size) { decim[o++] = mono[pos.toInt()]; pos += ratio }
            o
        }
        var i = 0
        if (frameTimeMs == 0L) frameTimeMs = startMs
        while (i < m) {
            val take = min(m - i, FRAME - pendingN)
            System.arraycopy(decim, i, pending, pendingN, take)
            pendingN += take; i += take
            if (pendingN >= FRAME) {
                onFrame(pending, frameTimeMs)
                System.arraycopy(pending, HOP, pending, 0, FRAME - HOP)
                pendingN = FRAME - HOP
                frameTimeMs += 10
            }
        }
    }

    private fun onFrame(x: FloatArray, nowMs: Long) {
        val energyDb = Dsp.dbfs(Dsp.rms(x, FRAME))
        val feat = mfcc(x, energyDb)
        if (manualCapture) {
            synchronized(manualFrames) { if (manualFrames.size < 1500) { manualFrames += feat; manualEnergy += energyDb; manualMs += 10 } }
            manualLevelDb = energyDb; if (energyDb > manualMaxDb) manualMaxDb = energyDb
            return
        }
        if (!inSpeech) {
            noiseFloorDb = if (energyDb < noiseFloorDb) noiseFloorDb * 0.8f + energyDb * 0.2f else noiseFloorDb * 0.995f + energyDb * 0.005f
            preBuffer.addLast(feat); if (preBuffer.size > PRE_FRAMES) preBuffer.removeFirst()
            if (energyDb > noiseFloorDb + 12f && energyDb > -50f) {
                if (++onsetCandidates >= 3) {
                    inSpeech = true; speechActive = true; speechFrames = 0; silenceFrames = 0
                    utterance.clear(); utterance.addAll(preBuffer)
                    utterStartMs = nowMs - PRE_FRAMES * 10
                    onsetCandidates = 0
                }
            } else onsetCandidates = 0
        } else {
            utterance += feat
            speechFrames++
            if (energyDb < noiseFloorDb + 6f) silenceFrames++ else silenceFrames = 0
            val durMs = speechFrames * 10L
            if (silenceFrames * 10L >= silenceMs || durMs >= maxUtteranceMs) {
                inSpeech = false; speechActive = false
                val effective = durMs - silenceFrames * 10L
                if (effective >= minUtteranceMs) finishUtterance(nowMs, effective)
            }
        }
    }

    private fun mfcc(x: FloatArray, energyDb: Float): FloatArray {
        System.arraycopy(x, 0, frameBuf, 0, FRAME)
        for (i in FRAME until NFFT) frameBuf[i] = 0f
        Dsp.preEmphasis(frameBuf, FRAME)
        fft.magnitudes(frameBuf, window, mag)
        for (i in mag.indices) mag[i] = mag[i] * mag[i]
        for (m in 0 until NMEL) {
            var s = 0.0
            val row = mel[m]
            for (k in row.indices) s += row[k] * mag[k]
            logMel[m] = ln(s + 1e-6).toFloat()
        }
        val c = Dsp.dct(logMel, NCEP)
        c[0] = energyDb / 10f
        return c
    }

    private fun finishUtterance(endMs: Long, durMs: Long) {
        val frames = normalize(withDeltas(utterance))
        if (frames.size < 8) return
        val enrolling = enrollingCommand
        if (enrolling != null) {
            templates = templates + KeywordTemplate(enrolling, frames)
            enrollingCommand = null
            val r = Result(enrolling, 0f, 0f, endMs, durMs, enrolled = true)
            lastResult = r; onResult(r)
            return
        }
        if (templates.isEmpty()) return
        val perCommand = HashMap<String, Float>()
        for (t in templates) {
            if (t.frames.isEmpty()) continue
            val ratio = frames.size.toFloat() / t.frames.size
            if (ratio < 0.5f || ratio > 2.0f) continue
            val d = dtw(frames, t.frames)
            val prev = perCommand[t.command]
            if (prev == null || d < prev) perCommand[t.command] = d
        }
        if (perCommand.isEmpty()) return
        val sorted = perCommand.entries.sortedBy { it.value }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: Float.MAX_VALUE
        val thr = thresholdFor(sensitivity)
        val accepted = best.value <= thr && (second == Float.MAX_VALUE || second - best.value >= relativeMargin * best.value)
        val r = Result(if (accepted) best.key else null, best.value, second, endMs, durMs, enrolled = false)
        lastResult = r
        onResult(r)
    }

    private fun withDeltas(seq: List<FloatArray>): List<FloatArray> {
        val n = seq.size
        return List(n) { i ->
            val out = FloatArray(DIMS)
            val cur = seq[i]
            System.arraycopy(cur, 0, out, 0, NCEP)
            val prev = seq[max(0, i - 2)]; val next = seq[min(n - 1, i + 2)]
            for (d in 0 until NCEP) out[NCEP + d] = (next[d] - prev[d]) / 2f
            out
        }
    }

    /** Per-dimension mean/variance normalization (removes channel & level effects). */
    private fun normalize(seq: List<FloatArray>): List<FloatArray> {
        if (seq.isEmpty()) return seq
        val mean = FloatArray(DIMS); val sd = FloatArray(DIMS)
        for (f in seq) for (d in 0 until DIMS) mean[d] += f[d]
        for (d in 0 until DIMS) mean[d] /= seq.size
        for (f in seq) for (d in 0 until DIMS) { val v = f[d] - mean[d]; sd[d] += v * v }
        for (d in 0 until DIMS) sd[d] = sqrt(sd[d] / seq.size).coerceAtLeast(1e-3f)
        return seq.map { f -> FloatArray(DIMS) { d -> (f[d] - mean[d]) / sd[d] } }
    }

    /** Path-length-normalized DTW distance with a Sakoe–Chiba band. */
    fun dtw(a: List<FloatArray>, b: List<FloatArray>): Float {
        val n = a.size; val m = b.size
        val band = max(abs(n - m), (max(n, m) * 0.25f).toInt()) + 1
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(m + 1) { inf }
        var cur = FloatArray(m + 1) { inf }
        var prevLen = IntArray(m + 1)
        var curLen = IntArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf); cur[0] = inf
            val jLo = max(1, i - band); val jHi = min(m, i + band)
            for (j in jLo..jHi) {
                val cost = dist(a[i - 1], b[j - 1])
                var bestV = prev[j - 1]; var bestL = prevLen[j - 1]
                if (prev[j] < bestV) { bestV = prev[j]; bestL = prevLen[j] }
                if (cur[j - 1] < bestV) { bestV = cur[j - 1]; bestL = curLen[j - 1] }
                cur[j] = bestV + cost; curLen[j] = bestL + 1
            }
            val t = prev; prev = cur; cur = t
            val tl = prevLen; prevLen = curLen; curLen = tl
        }
        val total = prev[m]
        return if (total >= inf) inf else total / max(1, prevLen[m])
    }

    private fun dist(x: FloatArray, y: FloatArray): Float {
        var s = 0f
        for (d in 0 until DIMS) { val v = x[d] - y[d]; s += v * v }
        return sqrt(s) / sqrt(DIMS.toFloat())
    }

    fun reset() { inSpeech = false; speechActive = false; utterance.clear(); preBuffer.clear(); pendingN = 0; frameTimeMs = 0L }
}
