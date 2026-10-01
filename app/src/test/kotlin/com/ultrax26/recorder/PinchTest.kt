package com.ultrax26.recorder

import com.ultrax26.recorder.settings.HandGestureConfig
import com.ultrax26.recorder.triggers.DefaultRules
import com.ultrax26.recorder.triggers.PinchDirection
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.triggers.Trigger
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.vision.HandGestureInterpreter
import com.ultrax26.recorder.triggers.vision.HandObservation
import com.ultrax26.recorder.triggers.vision.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchTest {
    /** A synthetic hand: wrist at (0.5,0.8), middle MCP 0.2 above it (hand size 0.2), thumb/index tips `spread`×0.2 apart. */
    private fun hand(spread: Float): HandObservation {
        val lm = MutableList(21) { NormPoint(0.5f, 0.6f) }
        lm[0] = NormPoint(0.5f, 0.8f)          // wrist
        lm[9] = NormPoint(0.5f, 0.6f)          // middle MCP → hand size 0.2
        lm[4] = NormPoint(0.5f - spread * 0.1f, 0.5f)   // thumb tip
        lm[8] = NormPoint(0.5f + spread * 0.1f, 0.5f)   // index tip
        return HandObservation(null, 0f, null, 0f, lm)
    }

    @Test fun `spreading fingers quickly fires an unpinch and closing fires a pinch`() {
        val events = ArrayList<TriggerEvent>()
        val interp = HandGestureInterpreter(HandGestureConfig()) { e -> events += e }
        var t = 1000L
        // closed for a few frames
        repeat(5) { interp.process(listOf(hand(0.2f)), t); t += 33 }
        // open quickly
        repeat(3) { interp.process(listOf(hand(1.2f)), t); t += 33 }
        val outs = events.filterIsInstance<TriggerEvent.Pinch>()
        assertEquals(1, outs.size); assertEquals(PinchDirection.OUT, outs[0].direction)
        // stay open, then close quickly after the cooldown
        t += 500
        repeat(3) { interp.process(listOf(hand(1.2f)), t); t += 33 }
        repeat(3) { interp.process(listOf(hand(0.15f)), t); t += 33 }
        val all = events.filterIsInstance<TriggerEvent.Pinch>()
        assertEquals(2, all.size); assertEquals(PinchDirection.IN, all[1].direction)
    }

    @Test fun `a slow movement does not count as a pinch`() {
        val events = ArrayList<TriggerEvent>()
        val interp = HandGestureInterpreter(HandGestureConfig(pinchWindowMs = 300)) { e -> events += e }
        var t = 0L
        repeat(3) { interp.process(listOf(hand(0.2f)), t); t += 33 }
        // open the fingers very gradually over a second: no fast change inside any 300 ms window
        for (i in 1..30) { interp.process(listOf(hand(0.2f + i * 0.033f)), t); t += 33 }
        repeat(3) { interp.process(listOf(hand(1.2f)), t); t += 33 }
        assertTrue(events.filterIsInstance<TriggerEvent.Pinch>().isEmpty())
    }

    @Test fun `a clear spread from a half-open hand counts as an unpinch`() {
        val events = ArrayList<TriggerEvent>()
        val interp = HandGestureInterpreter(HandGestureConfig()) { e -> events += e }
        var t = 0L
        repeat(5) { interp.process(listOf(hand(0.5f)), t); t += 33 }     // never fully pinched
        repeat(3) { interp.process(listOf(hand(1.0f)), t); t += 33 }     // quick spread of +0.5
        val p = events.filterIsInstance<TriggerEvent.Pinch>()
        assertEquals(1, p.size); assertEquals(PinchDirection.OUT, p[0].direction)
        t += 400
        repeat(3) { interp.process(listOf(hand(1.0f)), t); t += 33 }
        repeat(3) { interp.process(listOf(hand(0.5f)), t); t += 33 }     // quick close of -0.5
        val all = events.filterIsInstance<TriggerEvent.Pinch>()
        assertEquals(2, all.size); assertEquals(PinchDirection.IN, all[1].direction)
    }

    @Test fun `continuous mode reports spread relative to the hold and suppresses discrete events`() {
        val events = ArrayList<TriggerEvent>()
        val interp = HandGestureInterpreter(HandGestureConfig(continuousPinchZoom = true)) { e -> events += e }
        var t = 0L
        repeat(12) { interp.process(listOf(hand(0.2f)), t); t += 33 }     // ~400 ms pinch hold → session starts
        val starts = events.filterIsInstance<TriggerEvent.PinchScale>().filter { it.start }
        assertEquals(1, starts.size)
        repeat(4) { interp.process(listOf(hand(0.8f)), t); t += 33 }
        val scales = events.filterIsInstance<TriggerEvent.PinchScale>().filter { !it.start }
        assertTrue(scales.isNotEmpty()); assertTrue(scales.last().scale > 3f)
        assertTrue("no discrete pinch while a session runs", events.filterIsInstance<TriggerEvent.Pinch>().isEmpty())
        // hand leaves the frame → session ends; a new hold starts a new session
        t += 700; interp.process(emptyList(), t)
        assertTrue(!interp.pinchSessionActive)
    }

    @Test fun `pinch detection can be switched off`() {
        val events = ArrayList<TriggerEvent>()
        val interp = HandGestureInterpreter(HandGestureConfig(pinchZoom = false)) { e -> events += e }
        var t = 0L
        repeat(3) { interp.process(listOf(hand(0.2f)), t); t += 33 }
        repeat(3) { interp.process(listOf(hand(1.2f)), t); t += 33 }
        assertTrue(events.filterIsInstance<TriggerEvent.Pinch>().isEmpty())
    }

    @Test fun `default rules bind unpinch to zoom in and pinch to zoom out`() {
        val rules = DefaultRules.build()
        val zin = rules.first { (it.trigger as? Trigger.Pinch)?.direction == PinchDirection.OUT }
        val zout = rules.first { (it.trigger as? Trigger.Pinch)?.direction == PinchDirection.IN }
        assertEquals(RecAction.ZOOM_IN, zin.action); assertEquals(RecAction.ZOOM_OUT, zout.action)
        assertTrue(zin.enabled && zout.enabled)
    }
}
