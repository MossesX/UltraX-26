package com.ultrax26.recorder.triggers.vision

import android.graphics.Bitmap
import com.ultrax26.recorder.triggers.HandGestureType
import com.ultrax26.recorder.triggers.Handedness

data class NormPoint(val x: Float, val y: Float, val z: Float = 0f)

/** Normalized rectangle (0..1), Android-free so interpreters stay JVM-testable. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
}

/** One detected hand in *upright, normalized* image coordinates (0..1). */
data class HandObservation(
    val gesture: HandGestureType?,
    val gestureScore: Float,
    val handedness: Handedness?,
    val handednessScore: Float,
    val landmarks: List<NormPoint>,   // 21 MediaPipe landmarks or empty
) {
    val wrist: NormPoint? get() = landmarks.getOrNull(0)
    fun palmCenter(): NormPoint? {
        if (landmarks.size < 21) return null
        val ids = intArrayOf(0, 5, 9, 13, 17)
        var x = 0f; var y = 0f
        for (i in ids) { x += landmarks[i].x; y += landmarks[i].y }
        return NormPoint(x / ids.size, y / ids.size)
    }
}

/** One detected face in upright normalized coordinates. */
data class FaceObservation(
    val trackingId: Int?,
    val box: NormRect,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smile: Float?,
    val eulerX: Float,   // pitch (nod)
    val eulerY: Float,   // yaw (shake)
    val eulerZ: Float,   // roll (tilt)
    val mouthOpenRatio: Float?,
) {
    val area: Float get() = box.width() * box.height()
}

/** A frame handed to the detectors. `rotationDegrees` makes `bitmap` upright. */
class VisionFrame(val bitmap: Bitmap, val rotationDegrees: Int, val timestampMs: Long, val mirrored: Boolean)

interface HandDetector {
    fun detect(frame: VisionFrame): List<HandObservation>
    fun close()
}

interface FaceDetector {
    fun detect(frame: VisionFrame): List<FaceObservation>
    fun close()
}

/** Everything the camera HUD draws for the gesture system. */
data class VisionHudState(
    val hands: List<HandObservation> = emptyList(),
    val faces: List<FaceObservation> = emptyList(),
    val analysisFps: Float = 0f,
    val inferenceMs: Float = 0f,
    val heldGesture: String? = null,
    val heldMs: Long = 0,
    val blinkCount: Int = 0,
    val fingerCount: Int? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val handsAvailable: Boolean = false,
    val facesAvailable: Boolean = false,
    val lastError: String? = null,
    val meshTracked: Boolean = false,
    val meshReady: Boolean = false,
    val segReady: Boolean = false,
    val poseReady: Boolean = false,
    val segTracked: Boolean = false,
    val modelsLoading: Boolean = false,
)
