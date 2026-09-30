package com.ultrax26.recorder.triggers.vision

import com.ultrax26.recorder.settings.HandGestureConfig
import com.ultrax26.recorder.triggers.HandGestureType
import com.ultrax26.recorder.triggers.Handedness
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.audio.BurstCounter
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns per-frame hand observations into hold/release, wave, visual-clap, hands-up and
 * finger-count events. Pure Kotlin (unit-testable).
 */
class HandGestureInterpreter(private val cfg: HandGestureConfig, private val sink: (TriggerEvent) -> Unit) {

    private class Slot {
        var gesture: HandGestureType? = null
        var since = 0L
        var lastSeen = 0L
        var frames = 0
        var handedness: Handedness? = null
        var score = 0f
    }

    private val slots = arrayOf(Slot(), Slot())
    private var fingers: Int? = null
    private var fingersSince = 0L
    private var fingersFrames = 0
    private var handsUpSince = -1L
    // wave
    private var waveLastX = Float.NaN
    private var waveDir = 0
    private var waveSwings = 0
    private var waveStartMs = 0L
    private var waveLastSwingMs = 0L
    private var waveExtremeX = Float.NaN
    // visual clap
    private var handsDist = Float.NaN
    private var handsApartMs = -1L
    private val clapBurst = BurstCounter(900) { count, endMs -> sink(TriggerEvent.VisualClap(count, endMs)) }
    private var lastClapMs = -10000L

    var currentLabel: String? = null
        private set
    var currentHeldMs: Long = 0
        private set
    var currentFingers: Int? = null
        private set

    fun process(hands: List<HandObservation>, nowMs: Long) {
        // --- per-hand gesture hold tracking (up to 2 slots keyed by handedness) ---
        val assigned = BooleanArray(2)
        for (h in hands.take(2)) {
            val idx = when (h.handedness) { Handedness.LEFT -> 0; Handedness.RIGHT -> 1; null -> if (!assigned[0]) 0 else 1 }
            val slot = slots[idx]; assigned[idx] = true
            val g = classify(h)
            val score = if (h.gesture == g) h.gestureScore else 0.9f
            if (g != null && g == slot.gesture) {
                slot.frames++; slot.lastSeen = nowMs; slot.score = score; slot.handedness = h.handedness
                if (slot.frames >= cfg.stabilityFrames) sink(TriggerEvent.HandHeld(g, h.handedness, score, nowMs - slot.since, nowMs))
            } else if (g != null && (slot.gesture == null || nowMs - slot.lastSeen > cfg.releaseGraceMs || g != slot.gesture)) {
                release(slot, nowMs)
                slot.gesture = g; slot.since = nowMs; slot.lastSeen = nowMs; slot.frames = 1; slot.score = score; slot.handedness = h.handedness
            } else if (g == null && slot.gesture != null && nowMs - slot.lastSeen > cfg.releaseGraceMs) {
                release(slot, nowMs)
            }
        }
        for (i in 0..1) if (!assigned[i] && slots[i].gesture != null && nowMs - slots[i].lastSeen > cfg.releaseGraceMs) release(slots[i], nowMs)
        val primary = slots.filter { it.gesture != null && it.frames >= cfg.stabilityFrames }.maxByOrNull { nowMs - it.since }
        currentLabel = primary?.gesture?.label
        currentHeldMs = if (primary != null) nowMs - primary.since else 0

        // --- finger count (primary hand) ---
        val first = hands.firstOrNull { it.landmarks.size >= 21 }
        val fc = first?.let { countFingers(it.landmarks) }
        currentFingers = fc
        if (fc != null && fc == fingers) {
            fingersFrames++
            if (fingersFrames >= cfg.stabilityFrames) sink(TriggerEvent.FingersHeld(fc, nowMs - fingersSince, nowMs))
        } else {
            if (fingers != null) sink(TriggerEvent.FingersReleased(nowMs))
            fingers = fc; fingersSince = nowMs; fingersFrames = if (fc != null) 1 else 0
        }

        // --- both hands raised ---
        val wrists = hands.mapNotNull { it.wrist }
        val up = wrists.size >= 2 && wrists.all { it.y < 0.45f }
        if (up) {
            if (handsUpSince < 0) handsUpSince = nowMs
            sink(TriggerEvent.HandsUpHeld(nowMs - handsUpSince, nowMs))
        } else if (handsUpSince >= 0) { handsUpSince = -1; sink(TriggerEvent.HandsUpReleased(nowMs)) }

        // --- wave: open palm oscillating horizontally ---
        val palmHand = hands.firstOrNull { classify(it) == HandGestureType.OPEN_PALM && it.landmarks.size >= 21 }
        if (palmHand != null) {
            val x = palmHand.palmCenter()!!.x
            if (waveLastX.isNaN()) { waveLastX = x; waveExtremeX = x; waveStartMs = nowMs }
            val dx = x - waveLastX
            val dir = if (dx > 0.004f) 1 else if (dx < -0.004f) -1 else 0
            if (dir != 0 && dir != waveDir) {
                val swingAmp = abs(x - waveExtremeX)
                if (waveDir != 0 && swingAmp > 0.05f) {
                    waveSwings++; waveLastSwingMs = nowMs
                    if (waveSwings >= 2) sink(TriggerEvent.Wave(waveSwings, nowMs))
                }
                waveDir = dir; waveExtremeX = x
            }
            if (nowMs - waveLastSwingMs > 1200 && waveSwings > 0) { waveSwings = 0 }
            waveLastX = x
        } else { waveLastX = Float.NaN; waveDir = 0; waveSwings = 0 }

        // --- visual clap: two palms rush together ---
        if (hands.size >= 2) {
            val a = hands[0].palmCenter(); val b = hands[1].palmCenter()
            if (a != null && b != null) {
                val d = hypot(a.x - b.x, a.y - b.y)
                if (d > 0.22f) handsApartMs = nowMs
                if (!handsDist.isNaN() && d < 0.09f && handsApartMs >= 0 && nowMs - handsApartMs < 500 && nowMs - lastClapMs > 600) {
                    lastClapMs = nowMs; handsApartMs = -1
                    clapBurst.hit(nowMs)
                }
                handsDist = d
            }
        } else handsDist = Float.NaN
        clapBurst.tick(nowMs)
    }

    private fun release(slot: Slot, nowMs: Long) {
        val g = slot.gesture ?: return
        sink(TriggerEvent.HandReleased(g, nowMs - slot.since, nowMs))
        slot.gesture = null; slot.frames = 0
    }

    /** Classifier label when confident, else a landmark-derived gesture. */
    fun classify(h: HandObservation): HandGestureType? {
        if (h.gesture != null && h.gestureScore >= 0.5f) return h.gesture
        if (h.landmarks.size < 21) return null
        return derive(h.landmarks)
    }

    // MediaPipe landmark indices
    private val tips = intArrayOf(8, 12, 16, 20)
    private val pips = intArrayOf(6, 10, 14, 18)

    private fun d(a: NormPoint, b: NormPoint) = hypot(a.x - b.x, a.y - b.y)

    private fun extended(lm: List<NormPoint>): BooleanArray {
        val wrist = lm[0]
        val out = BooleanArray(5)
        for (i in 0 until 4) out[i + 1] = d(lm[tips[i]], wrist) > d(lm[pips[i]], wrist) * 1.15f
        // thumb: tip far from pinky MCP compared to the IP joint
        out[0] = d(lm[4], lm[17]) > d(lm[3], lm[17]) * 1.12f
        return out
    }

    fun countFingers(lm: List<NormPoint>): Int = extended(lm).count { it }

    fun derive(lm: List<NormPoint>): HandGestureType? {
        val ext = extended(lm)
        val size = d(lm[0], lm[9]).coerceAtLeast(1e-3f)
        val thumbIndex = d(lm[4], lm[8]) / size
        val thumb = ext[0]; val index = ext[1]; val middle = ext[2]; val ring = ext[3]; val pinky = ext[4]
        return when {
            thumbIndex < 0.35f && middle && ring && pinky -> HandGestureType.OK_SIGN
            thumbIndex < 0.30f && !middle && !ring && !pinky -> HandGestureType.PINCH
            index && pinky && !middle && !ring -> HandGestureType.ROCK_ON
            thumb && pinky && !index && !middle && !ring -> HandGestureType.CALL_ME
            index && middle && ring && !pinky -> HandGestureType.THREE_FINGERS
            index && middle && ring && pinky && !thumb -> HandGestureType.FOUR_FINGERS
            else -> null
        }
    }
}
