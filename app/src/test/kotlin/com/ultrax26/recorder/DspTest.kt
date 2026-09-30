package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.audio.Dsp
import com.ultrax26.recorder.triggers.audio.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {
    @Test fun fftPeaksAtSineBin() {
        val n = 1024
        val fft = Fft(n)
        val k = 37
        val x = FloatArray(n) { sin(2.0 * PI * k * it / n).toFloat() }
        val mag = FloatArray(n / 2 + 1)
        fft.magnitudes(x, null, mag)
        var best = 0
        for (i in mag.indices) if (mag[i] > mag[best]) best = i
        assertEquals(k, best)
        assertTrue(mag[k] > 400f) // ≈ N/2
    }

    @Test fun windowsAreSymmetric() {
        val w = Dsp.hamming(64)
        for (i in 0 until 32) assertEquals(w[i], w[63 - i], 1e-5f)
    }

    @Test fun dctOfConstantIsDc() {
        val c = Dsp.dct(FloatArray(26) { 1f }, 13)
        assertTrue(c[0] > 1f)
        for (i in 1 until 13) assertEquals(0f, c[i], 1e-3f)
    }

    @Test fun melFilterbankCoversBand() {
        val fb = Dsp.melFilterbank(26, 512, 16000, 300f, 8000f)
        assertEquals(26, fb.size)
        assertTrue(fb.all { row -> row.sum() > 0f })
    }

    @Test fun monoDownmixAndDecimate() {
        val stereo = ShortArray(12) { if (it % 2 == 0) 1000 else 3000 }
        val mono = FloatArray(6)
        Dsp.toMono(stereo, 6, 2, mono)
        assertEquals(2000f / 32768f, mono[0], 1e-6f)
        val out = FloatArray(2)
        assertEquals(2, Dsp.decimateBy3(mono, 6, out))
    }

    @Test fun flatnessDistinguishesToneFromNoise() {
        val n = 1024; val fft = Fft(n); val mag = FloatArray(n / 2 + 1)
        val tone = FloatArray(n) { sin(2.0 * PI * 50 * it / n).toFloat() }
        fft.magnitudes(tone, null, mag)
        val fTone = Dsp.spectralFlatness(mag, 1, 500)
        val rnd = java.util.Random(1)
        val noise = FloatArray(n) { (rnd.nextGaussian() * 0.3).toFloat() }
        fft.magnitudes(noise, null, mag)
        val fNoise = Dsp.spectralFlatness(mag, 1, 500)
        assertTrue("tone=$fTone noise=$fNoise", fNoise > fTone * 5)
    }
}
