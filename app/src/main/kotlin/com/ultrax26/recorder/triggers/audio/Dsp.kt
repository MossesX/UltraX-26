package com.ultrax26.recorder.triggers.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, dependency-free DSP toolkit (pure Kotlin so it is unit-testable on the JVM). */
object Dsp {
    fun hamming(n: Int): FloatArray = FloatArray(n) { (0.54 - 0.46 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }

    fun rms(x: FloatArray, n: Int = x.size): Double { var s = 0.0; for (i in 0 until n) s += x[i].toDouble() * x[i]; return sqrt(s / n.coerceAtLeast(1)) }
    fun dbfs(v: Double): Float = if (v <= 1e-9) -120f else (20.0 * log10(v)).toFloat()

    /** Mix interleaved 16-bit PCM to mono floats in [-1, 1]. */
    fun toMono(samples: ShortArray, frames: Int, channels: Int, out: FloatArray) {
        if (channels == 1) { for (i in 0 until frames) out[i] = samples[i] / 32768f; return }
        var j = 0
        for (i in 0 until frames) { var acc = 0; for (c in 0 until channels) acc += samples[j++]; out[i] = acc / (32768f * channels) }
    }

    /** Simple decimating low-pass (3-tap moving average + drop) for 48 kHz → 16 kHz. */
    fun decimateBy3(x: FloatArray, n: Int, out: FloatArray): Int {
        var o = 0
        var i = 0
        while (i + 2 < n) { out[o++] = (x[i] + x[i + 1] + x[i + 2]) / 3f; i += 3 }
        return o
    }

    fun preEmphasis(x: FloatArray, n: Int, coeff: Float = 0.97f) {
        for (i in n - 1 downTo 1) x[i] = x[i] - coeff * x[i - 1]
    }

    /** log-spaced mel filterbank matrix [nFilters][nBins]. */
    fun melFilterbank(nFilters: Int, fftSize: Int, sampleRate: Int, fMin: Float, fMax: Float): Array<FloatArray> {
        fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        val nBins = fftSize / 2 + 1
        val melPts = DoubleArray(nFilters + 2) { hzToMel(fMin.toDouble()) + it * (hzToMel(fMax.toDouble()) - hzToMel(fMin.toDouble())) / (nFilters + 1) }
        val bin = IntArray(nFilters + 2) { ((fftSize + 1) * melToHz(melPts[it]) / sampleRate).toInt().coerceIn(0, nBins - 1) }
        return Array(nFilters) { m ->
            val row = FloatArray(nBins)
            val l = bin[m]; val c = bin[m + 1]; val r = bin[m + 2]
            for (k in l until c) if (c > l) row[k] = (k - l).toFloat() / (c - l)
            for (k in c until r) if (r > c) row[k] = (r - k).toFloat() / (r - c)
            row
        }
    }

    /** DCT-II of the first `nOut` coefficients. */
    fun dct(input: FloatArray, nOut: Int): FloatArray {
        val n = input.size
        val out = FloatArray(nOut)
        for (k in 0 until nOut) {
            var s = 0.0
            for (i in 0 until n) s += input[i] * cos(PI * k * (2 * i + 1) / (2.0 * n))
            out[k] = (s * sqrt(2.0 / n)).toFloat()
        }
        return out
    }

    fun spectralCentroid(mag: FloatArray, nBins: Int, binHz: Float): Float {
        var num = 0.0; var den = 0.0
        for (k in 1 until nBins) { num += k * binHz * mag[k]; den += mag[k] }
        return if (den <= 1e-9) 0f else (num / den).toFloat()
    }

    /** Wiener entropy / spectral flatness in [0,1]; 1 = white noise, ~0 = pure tone. */
    fun spectralFlatness(mag: FloatArray, from: Int, to: Int): Float {
        var logSum = 0.0; var sum = 0.0; var n = 0
        for (k in from until to) { val v = mag[k].toDouble() + 1e-9; logSum += ln(v); sum += v; n++ }
        if (n == 0) return 0f
        val geo = exp(logSum / n); val arith = sum / n
        return (geo / arith).toFloat().coerceIn(0f, 1f)
    }
}

/** In-place iterative radix-2 FFT (real input helper included). */
class Fft(val size: Int) {
    private val cosT = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val rev = IntArray(size).also { r ->
        var bits = 0; while (1 shl bits < size) bits++
        for (i in 0 until size) { var x = i; var y = 0; for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }
    val re = FloatArray(size)
    val im = FloatArray(size)

    init { require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" } }

    /** Compute magnitudes of the real signal `x` (first `size` samples, windowed by `window` if given) into `mag` (size/2+1). */
    fun magnitudes(x: FloatArray, window: FloatArray?, mag: FloatArray) {
        for (i in 0 until size) { val v = if (i < x.size) x[i] else 0f; re[rev[i]] = if (window != null && i < window.size) v * window[i] else v; im[i] = 0f }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]; val wi = -sinT[k]
                    val ar = re[i + j + half]; val ai = im[i + j + half]
                    val tr = ar * wr - ai * wi; val ti = ar * wi + ai * wr
                    re[i + j + half] = re[i + j] - tr; im[i + j + half] = im[i + j] - ti
                    re[i + j] += tr; im[i + j] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        val n = size / 2 + 1
        for (k in 0 until n) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
    }
}
