package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.audio.BurstCounter
import com.ultrax26.recorder.triggers.audio.TransientDetector
import com.ultrax26.recorder.triggers.audio.WhistleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class AudioDetectorsTest {
    private val sr = 48000

    /** Quiet room noise with N impulsive broadband bursts (clap-like). */
    private fun clapSignal(claps: Int, gapMs: Int, seconds: Float = 3f): FloatArray {
        val n = (sr * seconds).toInt()
        val rnd = Random(7)
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.002).toFloat() } // ~ -54 dBFS floor
        for (c in 0 until claps) {
            val start = sr / 2 + c * gapMs * sr / 1000
            // 50 ms burst: low-passed noise (4-tap moving average ≈ energy below ~8 kHz) with a fast exponential decay
            val raw = DoubleArray(sr / 20 + 4) { rnd.nextGaussian() }
            for (i in 0 until sr / 20) {
                val env = exp(-i / (sr * 0.008))
                val lp = (raw[i] + raw[i + 1] + raw[i + 2] + raw[i + 3]) / 4.0
                if (start + i < n) x[start + i] += (lp * 0.6 * env).toFloat()
            }
        }
        return x
    }

    @Test fun detectsTwoClapsNotNoise() {
        val hits = ArrayList<Long>()
        val det = TransientDetector(sr, TransientDetector.Profile.clap(0.6f)) { t, _ -> hits += t }
        val sig = clapSignal(2, 400)
        feed(det::process, sig)
        assertEquals("hits=$hits", 2, hits.size)
        assertTrue(hits[1] - hits[0] in 300..500)

        // Sustained white noise must not register as claps.
        val hits2 = ArrayList<Long>()
        val det2 = TransientDetector(sr, TransientDetector.Profile.clap(0.6f)) { t, _ -> hits2 += t }
        val rnd = Random(3)
        val noise = FloatArray(sr * 2) { (rnd.nextGaussian() * 0.2).toFloat() }
        feed(det2::process, noise)
        assertEquals(0, hits2.size)
    }

    @Test fun burstCounterGroupsHits() {
        val bursts = ArrayList<Int>()
        val bc = BurstCounter(600) { c, _ -> bursts += c }
        bc.hit(1000); bc.hit(1300); bc.tick(2500)
        bc.hit(5000); bc.tick(5100); bc.tick(6000)
        assertEquals(listOf(2, 1), bursts)
    }

    @Test fun detectsWhistleTone() {
        val results = ArrayList<Float>()
        val det = WhistleDetector(sr, 0.5f, 300) { _, _, hz -> results += hz }
        val n = sr * 2
        val rnd = Random(11)
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.001).toFloat() }
        for (i in (sr / 2) until (sr / 2 + sr * 6 / 10)) x[i] += (0.2 * sin(2.0 * PI * 1500 * i / sr)).toFloat()
        feed(det::process, x)
        assertEquals("results=$results", 1, results.size)
        assertTrue(kotlin.math.abs(results[0] - 1500f) < 60f)
    }

    private fun feed(process: (FloatArray, Int, Long) -> Unit, sig: FloatArray) {
        val chunk = sr / 50
        var i = 0
        while (i < sig.size) {
            val n = minOf(chunk, sig.size - i)
            process(sig.copyOfRange(i, i + n), n, i * 1000L / sr)
            i += n
        }
    }
}
