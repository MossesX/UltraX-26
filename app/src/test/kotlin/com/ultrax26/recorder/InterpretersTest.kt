package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.vision.NormRect
import com.ultrax26.recorder.settings.FaceGestureConfig
import com.ultrax26.recorder.settings.HandGestureConfig
import com.ultrax26.recorder.triggers.HandGestureType
import com.ultrax26.recorder.triggers.Handedness
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.vision.FaceGestureInterpreter
import com.ultrax26.recorder.triggers.vision.FaceObservation
import com.ultrax26.recorder.triggers.vision.HandGestureInterpreter
import com.ultrax26.recorder.triggers.vision.HandObservation
import com.ultrax26.recorder.triggers.vision.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretersTest {
    private fun face(l: Float, r: Float, t: Long = 0) = FaceObservation(1, NormRect(0.3f, 0.3f, 0.6f, 0.7f), l, r, 0.1f, 0f, 0f, 0f, 0.3f)

    @Test fun threeFastBlinksMakeOneBurst() {
        val events = ArrayList<TriggerEvent>()
        val fi = FaceGestureInterpreter(FaceGestureConfig()) { events += it }
        var t = 1000L
        fun frames(open: Boolean, n: Int) { repeat(n) { fi.process(listOf(face(if (open) 0.95f else 0.05f, if (open) 0.95f else 0.05f)), t); t += 33 } }
        frames(true, 10)
        repeat(3) { frames(false, 4); frames(true, 6) } // 4 frames ≈ 130 ms closed, 200 ms open
        frames(true, 40) // > burst gap
        val bursts = events.filterIsInstance<TriggerEvent.BlinkBurst>()
        assertEquals("events=$events", 1, bursts.size)
        assertEquals(3, bursts[0].count)
    }

    @Test fun nodCounted() {
        val events = ArrayList<TriggerEvent>()
        val fi = FaceGestureInterpreter(FaceGestureConfig()) { events += it }
        var t = 0L
        fun f(pitch: Float) = FaceObservation(1, NormRect(0.3f, 0.3f, 0.6f, 0.7f), 0.9f, 0.9f, 0f, pitch, 0f, 0f, 0.3f)
        repeat(10) { fi.process(listOf(f(0f)), t); t += 33 }
        repeat(2) {
            repeat(4) { fi.process(listOf(f(-14f)), t); t += 33 }
            repeat(6) { fi.process(listOf(f(0f)), t); t += 33 }
        }
        repeat(40) { fi.process(listOf(f(0f)), t); t += 33 }
        val nods = events.filterIsInstance<TriggerEvent.HeadNod>()
        assertEquals("events=${events.filterIsInstance<TriggerEvent.HeadNod>()}", 1, nods.size)
        assertEquals(2, nods[0].count)
    }

    private fun hand(g: HandGestureType?, score: Float = 0.9f, lm: List<NormPoint> = emptyList()) = HandObservation(g, score, Handedness.RIGHT, 0.9f, lm)

    @Test fun heldGestureEmitsHeldThenReleased() {
        val events = ArrayList<TriggerEvent>()
        val hi = HandGestureInterpreter(HandGestureConfig(stabilityFrames = 2, releaseGraceMs = 100)) { events += it }
        var t = 0L
        repeat(10) { hi.process(listOf(hand(HandGestureType.THUMB_UP)), t); t += 50 }
        val held = events.filterIsInstance<TriggerEvent.HandHeld>()
        assertTrue(held.size >= 8)
        assertTrue(held.last().heldMs >= 400)
        t += 300
        hi.process(emptyList(), t)
        assertTrue(events.last() is TriggerEvent.HandReleased)
    }

    @Test fun fingerCountingFromLandmarks() {
        // Open hand pointing up: wrist at bottom, all tips far above their PIP joints.
        val lm = MutableList(21) { NormPoint(0.5f, 0.9f) }
        val wrist = NormPoint(0.5f, 0.9f); lm[0] = wrist
        lm[9] = NormPoint(0.5f, 0.6f) // middle MCP
        lm[17] = NormPoint(0.62f, 0.62f) // pinky MCP
        // thumb: 3 (IP) and 4 (tip) — tip farther from pinky MCP than IP
        lm[3] = NormPoint(0.40f, 0.62f); lm[4] = NormPoint(0.30f, 0.58f)
        val tips = intArrayOf(8, 12, 16, 20); val pips = intArrayOf(6, 10, 14, 18)
        for (i in 0 until 4) { pips[i].let { lm[it] = NormPoint(0.44f + i * 0.04f, 0.45f) }; tips[i].let { lm[it] = NormPoint(0.44f + i * 0.04f, 0.20f) } }
        val hi = HandGestureInterpreter(HandGestureConfig()) { }
        assertEquals(5, hi.countFingers(lm))
        // Curl the pinky and ring → 3 fingers + thumb = "three fingers" derived gesture requires !pinky
        lm[20] = NormPoint(0.56f, 0.70f); lm[16] = NormPoint(0.52f, 0.70f)
        assertEquals(3, hi.countFingers(lm))
    }
}
