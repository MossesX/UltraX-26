package com.ultrax26.recorder

import com.ultrax26.recorder.settings.TriggerSettings
import com.ultrax26.recorder.triggers.HandGestureType
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.triggers.RecState
import com.ultrax26.recorder.triggers.Trigger
import com.ultrax26.recorder.triggers.TriggerEngine
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.TriggerRule
import com.ultrax26.recorder.triggers.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TriggerEngineTest {
    private var now = 10_000L
    private var state = RecState.IDLE
    private val performed = CopyOnWriteArrayList<RecAction>()

    private fun engine(rules: List<TriggerRule>, countdown: Int = 0, globalCooldown: Long = 0): TriggerEngine {
        val e = TriggerEngine({ now }, { state }, { a, _ -> performed += a }, { })
        e.updateSettings(TriggerSettings(rules = rules, countdownSeconds = countdown, globalCooldownMs = globalCooldown))
        e.setArmed(true)
        return e
    }

    private fun settle(e: TriggerEngine) {
        // The engine is single-threaded; a marker event flushes the queue.
        val latch = CountDownLatch(1)
        val probe = TriggerEngine({ now }, { state }, { _, _ -> }, { })
        probe.shutdown()
        e.onEvent(TriggerEvent.Loud(-100f, now)) // harmless, matches nothing
        Thread.sleep(80)
        latch.countDown()
        assertTrue(latch.await(1, TimeUnit.SECONDS))
    }

    @Test fun blinkRuleFiresOnExactCountWithCooldown() {
        val rule = TriggerRule("r1", Trigger.Blink(count = 3), RecAction.TOGGLE_RECORD, cooldownMs = 1000)
        val e = engine(listOf(rule))
        e.onEvent(TriggerEvent.BlinkBurst(2, 500, now)); settle(e)
        assertEquals(0, performed.size)
        e.onEvent(TriggerEvent.BlinkBurst(3, 700, now)); settle(e)
        assertEquals(listOf(RecAction.TOGGLE_RECORD), performed.toList())
        e.onEvent(TriggerEvent.BlinkBurst(3, 700, now)); settle(e) // within cooldown
        assertEquals(1, performed.size)
        now += 2000
        e.onEvent(TriggerEvent.BlinkBurst(3, 700, now)); settle(e)
        assertEquals(2, performed.size)
        e.shutdown()
    }

    @Test fun holdGestureFiresOnceUntilReleased() {
        val rule = TriggerRule("r2", Trigger.HandGesture(HandGestureType.THUMB_UP, holdMs = 600), RecAction.START, cooldownMs = 0)
        val e = engine(listOf(rule))
        e.onEvent(TriggerEvent.HandHeld(HandGestureType.THUMB_UP, null, 0.9f, 200, now)); settle(e)
        assertEquals(0, performed.size)
        e.onEvent(TriggerEvent.HandHeld(HandGestureType.THUMB_UP, null, 0.9f, 650, now)); settle(e)
        assertEquals(1, performed.size)
        e.onEvent(TriggerEvent.HandHeld(HandGestureType.THUMB_UP, null, 0.9f, 900, now)); settle(e)
        assertEquals(1, performed.size) // still held → no re-fire
        e.onEvent(TriggerEvent.HandReleased(HandGestureType.THUMB_UP, 900, now)); settle(e)
        e.onEvent(TriggerEvent.HandHeld(HandGestureType.THUMB_UP, null, 0.9f, 700, now)); settle(e)
        assertEquals(2, performed.size)
        e.shutdown()
    }

    @Test fun stateFilterAndArming() {
        val rule = TriggerRule("r3", Trigger.Clap(2), RecAction.STOP, states = listOf(RecState.RECORDING))
        val e = engine(listOf(rule))
        e.onEvent(TriggerEvent.ClapBurst(2, now)); settle(e)
        assertEquals(0, performed.size) // IDLE, rule limited to RECORDING
        state = RecState.RECORDING
        e.onEvent(TriggerEvent.ClapBurst(2, now)); settle(e)
        assertEquals(listOf(RecAction.STOP), performed.toList())
        e.setArmed(false); settle(e)
        e.onEvent(TriggerEvent.ClapBurst(2, now + 5000)); settle(e)
        assertEquals(1, performed.size)
        e.shutdown()
    }

    @Test fun voiceMatchingUsesWholeWordsForSystemEngine() {
        val e = engine(emptyList())
        val t = Trigger.VoiceCommand("stop", aliases = listOf("cut"))
        assertTrue(e.voiceMatches(t, TriggerEvent.Voice("please stop now", 0.9f, VoiceEngine.SYSTEM, now)))
        assertTrue(e.voiceMatches(t, TriggerEvent.Voice("Cut!", 0.9f, VoiceEngine.SYSTEM, now)))
        assertTrue(!e.voiceMatches(t, TriggerEvent.Voice("stopwatch", 0.9f, VoiceEngine.SYSTEM, now)))
        assertTrue(e.voiceMatches(t, TriggerEvent.Voice("stop", 0.9f, VoiceEngine.KEYWORD, now)))
        assertTrue(!e.voiceMatches(t, TriggerEvent.Voice("please stop", 0.9f, VoiceEngine.KEYWORD, now)))
        e.shutdown()
    }

    @Test fun gestureSequenceMatchesTail() {
        val rule = TriggerRule("r4", Trigger.GestureSequence(listOf(HandGestureType.OPEN_PALM, HandGestureType.CLOSED_FIST), stepTimeoutMs = 2000), RecAction.SNAPSHOT, cooldownMs = 0)
        val e = engine(listOf(rule))
        e.onEvent(TriggerEvent.HandReleased(HandGestureType.OPEN_PALM, 500, now)); settle(e)
        assertEquals(0, performed.size)
        e.onEvent(TriggerEvent.HandReleased(HandGestureType.CLOSED_FIST, 500, now + 800)); settle(e)
        assertEquals(listOf(RecAction.SNAPSHOT), performed.toList())
        e.shutdown()
    }
}
