package com.ultrax26.recorder

import com.ultrax26.recorder.recording.AudioCapture
import com.ultrax26.recorder.recording.AudioScrubber
import com.ultrax26.recorder.recording.PcmChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioScrubberTest {
    private val rate = 48000
    private val chunkFrames = 960 // 20 ms

    private fun chunk(index: Int, value: Short = 1000): PcmChunk {
        val samples = ShortArray(chunkFrames * 2) { value }
        return PcmChunk(samples, chunkFrames, 2, rate, index * 20_000_000L, 0.5f)
    }

    private class Sink : AudioCapture.Listener {
        val got = ArrayList<PcmChunk>()
        override fun onPcm(chunk: PcmChunk) { got += chunk }
    }

    @Test fun `chunks are released only after the delay has elapsed`() {
        val sink = Sink()
        val sc = AudioScrubber(sink, delayMs = 100)
        for (i in 0 until 5) sc.onPcm(chunk(i))          // 0..100 ms of audio
        assertEquals(0, sink.got.size)                    // newest end = 100 ms; chunk 0 ends at 20 → 80 < 100
        sc.onPcm(chunk(5))                                // end 120 ms → chunk 0 (end 20) is 100 ms old
        assertEquals(1, sink.got.size)
        assertEquals(0L, sink.got[0].ptsNs)
        sc.flush()
        assertEquals(6, sink.got.size)
        assertEquals(5 * 20_000_000L, sink.got.last().ptsNs)
    }

    @Test fun `muted range is silenced with fades and untouched elsewhere`() {
        val sink = Sink()
        val sc = AudioScrubber(sink, delayMs = 200)
        sc.mute(30_000_000L, 70_000_000L)                 // 30–70 ms: all of chunk 2 (40–60), halves of 1 and 3
        for (i in 0 until 12) sc.onPcm(chunk(i))
        sc.flush()
        assertEquals(12, sink.got.size)
        val c0 = sink.got[0]; val c1 = sink.got[1]; val c2 = sink.got[2]; val c3 = sink.got[3]; val c5 = sink.got[5]
        assertTrue(c0.samples.all { it == 1000.toShort() })
        assertTrue(c5.samples.all { it == 1000.toShort() })
        assertTrue("middle of range is silent", c2.samples.all { it == 0.toShort() })
        // chunk 1: first half (20–30 ms) loud, second half (30–40) silent, with a short fade before 30 ms
        val f30 = (10 * rate / 1000) * 2
        assertTrue("fade-in starts 12 ms before the range", c1.samples[0].toInt() in 700..999)
        assertEquals(0, c1.samples[f30 + 100].toInt())
        assertTrue("fade exists", c1.samples[f30 - 200].toInt() in 1..999)
        // chunk 3: first half silent, then fading back up to full level; chunk 4 is untouched
        assertEquals(0, c3.samples[10].toInt())
        assertTrue("fading back in", c3.samples[c3.samples.size - 2].toInt() in 700..999)
        assertTrue(sink.got[4].samples.all { it == 1000.toShort() })
        assertEquals(0f, c2.peak)
    }

    @Test fun `duck mode attenuates instead of silencing`() {
        val sink = Sink()
        val sc = AudioScrubber(sink, delayMs = 0, mode = AudioScrubber.Mode.DUCK)
        sc.mute(0L, 1_000_000_000L)
        sc.onPcm(chunk(0, 10000)); sc.onPcm(chunk(1, 10000)); sc.flush()
        val mid = sink.got[0].samples[chunkFrames]      // middle of chunk 0
        assertTrue("ducked to about -30 dB: $mid", abs(mid - 316) <= 3)
        assertTrue(sink.got[0].peak > 0f)
    }

    @Test fun `a range marked after the audio arrived still applies to queued chunks`() {
        val sink = Sink()
        val sc = AudioScrubber(sink, delayMs = 500)
        for (i in 0 until 10) sc.onPcm(chunk(i))          // 0–200 ms queued, nothing released yet
        sc.mute(0L, 200_000_000L)                         // retroactive: the clap was 200 ms ago
        sc.flush()
        assertTrue(sink.got.all { c -> c.samples.all { it == 0.toShort() } })
        assertEquals(1, sc.mutedRanges)
    }

    @Test fun `old ranges are forgotten and queue size is bounded by the delay`() {
        val sink = Sink()
        val sc = AudioScrubber(sink, delayMs = 100)
        sc.mute(0L, 10_000_000L)
        for (i in 0 until 400) sc.onPcm(chunk(i))         // 8 s of audio
        assertTrue(sc.queuedChunks <= 6)
        assertEquals(400 - sc.queuedChunks, sink.got.size)
    }
}
