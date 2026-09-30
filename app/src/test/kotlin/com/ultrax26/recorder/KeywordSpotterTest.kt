package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.audio.KeywordSpotter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class KeywordSpotterTest {
    private val sr = 48000

    /** Synthetic "word": a sequence of formant-like tone pairs with an amplitude envelope. */
    private fun word(seq: List<Pair<Double, Double>>, segMs: Int = 120): FloatArray {
        val out = ArrayList<Float>()
        var phase1 = 0.0; var phase2 = 0.0
        for ((f1, f2) in seq) {
            val n = sr * segMs / 1000
            for (i in 0 until n) {
                val env = sin(PI * i / n)
                phase1 += 2 * PI * f1 / sr; phase2 += 2 * PI * f2 / sr
                out += (0.3 * env * (sin(phase1) + 0.6 * sin(phase2))).toFloat()
            }
        }
        return out.toFloatArray()
    }

    private fun silence(ms: Int, rnd: Random) = FloatArray(sr * ms / 1000) { (rnd.nextGaussian() * 0.0005).toFloat() }

    private fun feed(sp: KeywordSpotter, sig: FloatArray, startMs: Long): Long {
        val chunk = sr / 50
        var i = 0
        while (i < sig.size) {
            val n = minOf(chunk, sig.size - i)
            sp.process(sig.copyOfRange(i, i + n), n, startMs + i * 1000L / sr)
            i += n
        }
        return startMs + sig.size * 1000L / sr
    }

    @Test fun enrollsAndRecognizesTrainedWords() {
        val results = ArrayList<KeywordSpotter.Result>()
        val sp = KeywordSpotter(sr, sensitivity = 0.6f, relativeMargin = 0.05f, minUtteranceMs = 200, maxUtteranceMs = 2000, silenceMs = 250) { results += it }
        val rnd = Random(5)
        val start = word(listOf(300.0 to 2200.0, 700.0 to 1200.0, 400.0 to 2600.0, 250.0 to 900.0))
        val stop = word(listOf(1200.0 to 3000.0, 500.0 to 1000.0, 900.0 to 2800.0))
        var t = 0L
        t = feed(sp, silence(600, rnd), t)
        sp.enrollingCommand = "start"; t = feed(sp, start, t); t = feed(sp, silence(600, rnd), t)
        sp.enrollingCommand = "stop"; t = feed(sp, stop, t); t = feed(sp, silence(600, rnd), t)
        assertEquals(2, sp.templates.size)
        assertTrue(results.all { it.enrolled })
        results.clear()

        t = feed(sp, start, t); t = feed(sp, silence(600, rnd), t)
        assertEquals("results=$results", 1, results.size)
        assertEquals("start", results[0].command)
        assertTrue(results[0].score < results[0].secondScore)
        results.clear()

        t = feed(sp, stop, t); feed(sp, silence(600, rnd), t)
        assertEquals(1, results.size)
        assertEquals("stop", results[0].command)
    }

    @Test fun dtwIdenticalIsZero() {
        val sp = KeywordSpotter(sr, 0.5f, 0.1f, 200, 2000, 300) { }
        val seq = List(20) { i -> FloatArray(KeywordSpotter.DIMS) { d -> sin(i * 0.3 + d).toFloat() } }
        assertEquals(0f, sp.dtw(seq, seq), 1e-5f)
        val other = List(20) { i -> FloatArray(KeywordSpotter.DIMS) { d -> sin(i * 0.9 + d * 2).toFloat() } }
        assertNotNull(sp.dtw(seq, other))
        assertTrue(sp.dtw(seq, other) > 0.1f)
    }
}
