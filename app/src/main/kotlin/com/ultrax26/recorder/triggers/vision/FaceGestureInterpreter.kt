package com.ultrax26.recorder.triggers.vision

import com.ultrax26.recorder.settings.FaceGestureConfig
import com.ultrax26.recorder.triggers.Eye
import com.ultrax26.recorder.triggers.TiltDirection
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.audio.BurstCounter
import kotlin.math.abs

/** Blink bursts, winks, smile/mouth holds, nods, shakes, tilts and presence from face observations. */
class FaceGestureInterpreter(private val cfg: FaceGestureConfig, private val sink: (TriggerEvent) -> Unit) {
    // Blink state
    private var eyesClosed = false
    private var closedSince = 0L
    private var burstStartMs = 0L
    private var blinkCountInBurst = 0
    private val blinkBurst = BurstCounter(cfg.blinkBurstGapMs, 8) { count, endMs ->
        sink(TriggerEvent.BlinkBurst(count, (endMs - burstStartMs).coerceAtLeast(0), endMs))
        blinkCountInBurst = 0
    }
    // Wink state
    private var winkEye: Eye? = null
    private var winkSince = 0L
    private var winkReported = false
    // Smile / mouth
    private var smileSince = -1L
    private var mouthSince = -1L
    // Head motion
    private var basePitch = Float.NaN; private var baseYaw = Float.NaN
    private var nodPhase = 0; private var shakePhase = 0
    private val nodBurst = BurstCounter(1000, 6) { c, t -> sink(TriggerEvent.HeadNod(c, t)) }
    private val shakeBurst = BurstCounter(1000, 6) { c, t -> sink(TriggerEvent.HeadShake(c, t)) }
    private var tiltDir: TiltDirection? = null
    private var tiltSince = 0L
    // Presence
    private var facePresent = false
    private var presentSince = -1L
    private var goneSince = -1L
    private var appearedReported = false
    private var lastGoneReport = 0L

    var blinkCount: Int = 0
        private set

    fun process(faces: List<FaceObservation>, nowMs: Long) {
        val face = if (cfg.trackLargestFaceOnly) faces.maxByOrNull { it.area } else faces.firstOrNull()
        presence(face != null, nowMs)
        blinkBurst.tick(nowMs); nodBurst.tick(nowMs); shakeBurst.tick(nowMs)
        if (face == null) { resetHolds(nowMs); return }

        // ---- eyes ----
        val l = face.leftEyeOpen; val r = face.rightEyeOpen
        if (l != null && r != null) {
            val bothClosed = l < cfg.eyeClosedThreshold && r < cfg.eyeClosedThreshold
            val bothOpen = l > cfg.eyeOpenThreshold && r > cfg.eyeOpenThreshold
            if (!eyesClosed && bothClosed) { eyesClosed = true; closedSince = nowMs }
            else if (eyesClosed && bothOpen) {
                eyesClosed = false
                val dur = nowMs - closedSince
                if (dur in cfg.blinkMinMs..cfg.blinkMaxMs) {
                    if (blinkCountInBurst == 0) burstStartMs = closedSince
                    blinkCountInBurst++; blinkCount = blinkCountInBurst
                    blinkBurst.hit(nowMs)
                }
            }
            // wink: exactly one eye closed
            val oneClosed = (l < cfg.eyeClosedThreshold) xor (r < cfg.eyeClosedThreshold)
            if (oneClosed && !eyesClosed) {
                val eye = if (l < cfg.eyeClosedThreshold) Eye.LEFT else Eye.RIGHT
                if (winkEye != eye) { winkEye = eye; winkSince = nowMs; winkReported = false }
                else if (!winkReported && nowMs - winkSince in 150..1200) { winkReported = true; sink(TriggerEvent.Wink(eye, nowMs)) }
            } else winkEye = null
        }

        // ---- smile ----
        val s = face.smile
        if (s != null) {
            if (s >= cfg.smileThreshold) { if (smileSince < 0) smileSince = nowMs; sink(TriggerEvent.SmileHeld(nowMs - smileSince, nowMs)) }
            else if (smileSince >= 0 && s < cfg.smileThreshold - 0.25f) { smileSince = -1; sink(TriggerEvent.SmileReleased(nowMs)) }
        }

        // ---- mouth open ----
        val m = face.mouthOpenRatio
        if (m != null) {
            if (m >= cfg.mouthOpenRatio) { if (mouthSince < 0) mouthSince = nowMs; sink(TriggerEvent.MouthOpenHeld(nowMs - mouthSince, nowMs)) }
            else if (mouthSince >= 0 && m < cfg.mouthOpenRatio - 0.06f) { mouthSince = -1; sink(TriggerEvent.MouthOpenReleased(nowMs)) }
        }

        // ---- nod / shake (relative to a slow baseline) ----
        if (basePitch.isNaN()) { basePitch = face.eulerX; baseYaw = face.eulerY }
        val dp = face.eulerX - basePitch
        val dy = face.eulerY - baseYaw
        basePitch += (face.eulerX - basePitch) * 0.03f
        baseYaw += (face.eulerY - baseYaw) * 0.03f
        when (nodPhase) {
            0 -> if (abs(dp) > cfg.nodDegrees) nodPhase = if (dp > 0) 1 else -1
            1 -> if (dp < cfg.nodDegrees * 0.3f) { nodPhase = 0; nodBurst.hit(nowMs) }
            -1 -> if (dp > -cfg.nodDegrees * 0.3f) { nodPhase = 0; nodBurst.hit(nowMs) }
        }
        when (shakePhase) {
            0 -> if (abs(dy) > cfg.shakeDegrees) shakePhase = if (dy > 0) 1 else -1
            1 -> if (dy < cfg.shakeDegrees * 0.3f) { shakePhase = 0; shakeBurst.hit(nowMs) }
            -1 -> if (dy > -cfg.shakeDegrees * 0.3f) { shakePhase = 0; shakeBurst.hit(nowMs) }
        }

        // ---- tilt ----
        val roll = face.eulerZ
        val dir = if (roll > cfg.tiltDegrees) TiltDirection.LEFT else if (roll < -cfg.tiltDegrees) TiltDirection.RIGHT else null
        if (dir != null) {
            if (tiltDir != dir) { tiltDir = dir; tiltSince = nowMs }
            sink(TriggerEvent.HeadTiltHeld(dir, nowMs - tiltSince, nowMs))
        } else if (tiltDir != null) { tiltDir = null; sink(TriggerEvent.HeadTiltReleased(nowMs)) }
    }

    private fun resetHolds(nowMs: Long) {
        if (smileSince >= 0) { smileSince = -1; sink(TriggerEvent.SmileReleased(nowMs)) }
        if (mouthSince >= 0) { mouthSince = -1; sink(TriggerEvent.MouthOpenReleased(nowMs)) }
        if (tiltDir != null) { tiltDir = null; sink(TriggerEvent.HeadTiltReleased(nowMs)) }
        eyesClosed = false; winkEye = null; basePitch = Float.NaN
    }

    private fun presence(present: Boolean, nowMs: Long) {
        if (present) {
            goneSince = -1
            if (!facePresent) { facePresent = true; presentSince = nowMs; appearedReported = false }
            else if (!appearedReported && nowMs - presentSince >= 500) { appearedReported = true; sink(TriggerEvent.FaceAppeared(nowMs)) }
        } else {
            if (facePresent) { facePresent = false; goneSince = nowMs; lastGoneReport = 0 }
            if (goneSince >= 0 && nowMs - lastGoneReport >= 500) { lastGoneReport = nowMs; sink(TriggerEvent.FaceGone(nowMs - goneSince, nowMs)) }
        }
    }
}
