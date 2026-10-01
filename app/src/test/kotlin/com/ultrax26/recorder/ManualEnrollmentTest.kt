package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.audio.KeywordSpotter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ManualEnrollmentTest {
    private fun spotter(results: MutableList<KeywordSpotter.Result> = ArrayList()) = KeywordSpotter(48000, 0.5f, 0.08f, 250, 2000, 300) { results += it }

    /** 48 kHz mono: [silenceMs] of near-silence, [toneMs] of a modulated tone, [silenceMs] of silence. */
    private fun feed(sp: KeywordSpotter, silenceMs: Int, toneMs: Int, amplitude: Float = 0.3f) {
        val chunk = 960
        var t = 0L
        fun push(ms: Int, gen: (Int) -> Float) {
            var left = ms * 48
            var i = 0
            while (left > 0) {
                val n = minOf(chunk, left)
                val buf = FloatArray(n) { k -> gen(i + k) }
                sp.process(buf, n, t); t += n / 48; left -= n; i += n
            }
        }
        push(silenceMs) { k -> 0.0005f * sin(k * 0.3f) }
        push(toneMs) { k -> amplitude * sin(2 * PI * 440 * k / 48000.0).toFloat() * (0.6f + 0.4f * sin(2 * PI * 7 * k / 48000.0).toFloat()) }
        push(silenceMs) { k -> 0.0005f * sin(k * 0.3f) }
    }

    @Test fun `tap-to-record captures the spoken part and stores a template`() {
        val results = ArrayList<KeywordSpotter.Result>()
        val sp = spotter(results)
        sp.beginManualEnrollment("go")
        assertTrue(sp.manualCapture)
        feed(sp, 400, 700)
        assertTrue(sp.manualMs >= 1400)
        assertTrue("level tracked", sp.manualMaxDb > -30f)
        val err = sp.finishManualEnrollment()
        assertNoError(err)
        assertEquals(1, sp.templates.size); assertEquals("go", sp.templates[0].command)
        // trimmed to roughly the tone plus margins (~0.7 s + 0.16 s), well below the 1.5 s recorded
        assertTrue("frames=${sp.templates[0].frames.size}", sp.templates[0].frames.size in 60..110)
        assertEquals(1, results.size); assertTrue(results[0].enrolled)
        assertTrue(!sp.manualCapture)
    }

    @Test fun `silence is rejected with a microphone hint`() {
        val sp = spotter()
        sp.beginManualEnrollment("go")
        feed(sp, 300, 300, amplitude = 0.0001f)
        val err = sp.finishManualEnrollment()
        assertNotNull(err); assertTrue(err!!.contains("silence", ignoreCase = true) || err.contains("microphone", ignoreCase = true))
        assertTrue(sp.templates.isEmpty())
    }

    @Test fun `too short a recording is rejected and cancel drops the audio`() {
        val sp = spotter()
        sp.beginManualEnrollment("go")
        feed(sp, 0, 30)
        assertNotNull(sp.finishManualEnrollment())
        sp.beginManualEnrollment("go")
        feed(sp, 100, 500)
        sp.cancelManualEnrollment()
        assertTrue(!sp.manualCapture && sp.templates.isEmpty())
        assertNotNull(sp.finishManualEnrollment())   // nothing in progress
    }

    private fun assertNoError(v: String?) { if (v != null) throw AssertionError("unexpected error: $v") }
}
