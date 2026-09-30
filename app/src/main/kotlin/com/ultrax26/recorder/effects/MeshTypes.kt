package com.ultrax26.recorder.effects

import com.ultrax26.recorder.triggers.vision.FaceObservation
import com.ultrax26.recorder.triggers.vision.NormRect
import com.ultrax26.recorder.triggers.vision.VisionFrame

/** 478 face-mesh landmarks (x,y normalized to the upright frame, z relative), optional 4x4 transform, optional blendshapes. */
class FaceMeshResult(
    val landmarks: FloatArray,              // 478 * 3
    val matrix: FloatArray?,                // 16, column-major (MediaPipe facial transformation matrix)
    val blendshapes: Map<String, Float>?,
    val timestampMs: Long,
) {
    fun x(i: Int) = landmarks[i * 3]
    fun y(i: Int) = landmarks[i * 3 + 1]
    fun z(i: Int) = landmarks[i * 3 + 2]
    val count: Int get() = landmarks.size / 3
}

/** Packed segmentation: per pixel RGBA bytes = person, hair, faceSkin, clothes (0..255), upright orientation. */
class SegmentationMasks(val width: Int, val height: Int, val rgba: ByteArray, val timestampMs: Long)

/** 33 pose landmarks normalized to the upright frame (x, y, z, visibility). */
class PoseResult(val landmarks: FloatArray, val timestampMs: Long) {
    fun x(i: Int) = landmarks[i * 4]
    fun y(i: Int) = landmarks[i * 4 + 1]
    fun visibility(i: Int) = landmarks[i * 4 + 3]
    companion object {
        const val LEFT_SHOULDER = 11; const val RIGHT_SHOULDER = 12; const val LEFT_HIP = 23; const val RIGHT_HIP = 24
        const val NOSE = 0; const val LEFT_EAR = 7; const val RIGHT_EAR = 8
    }
}

interface FaceMeshDetector { fun detect(frame: VisionFrame): FaceMeshResult?; fun close() }
interface PersonSegmenter { fun segment(frame: VisionFrame): SegmentationMasks?; fun close() }
interface BodyPoseDetector { fun detect(frame: VisionFrame): PoseResult?; fun close() }

/**
 * Derives the gesture pipeline's [FaceObservation] from the face mesh so ML Kit does not need to run
 * while the effects pipeline already tracks the face (blendshapes give eye/mouth/smile states).
 */
object MeshFaceAdapter {
    fun toObservation(m: FaceMeshResult): FaceObservation {
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (i in 0 until m.count) { val x = m.x(i); val y = m.y(i); if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y }
        val bs = m.blendshapes
        val leftOpen = bs?.get("eyeBlinkLeft")?.let { 1f - it } ?: eyeOpenFromLandmarks(m, FaceLandmarks.LEFT_EYE_TOP, FaceLandmarks.LEFT_EYE_BOTTOM, FaceLandmarks.LEFT_EYE_OUTER, FaceLandmarks.LEFT_EYE_INNER)
        val rightOpen = bs?.get("eyeBlinkRight")?.let { 1f - it } ?: eyeOpenFromLandmarks(m, FaceLandmarks.RIGHT_EYE_TOP, FaceLandmarks.RIGHT_EYE_BOTTOM, FaceLandmarks.RIGHT_EYE_OUTER, FaceLandmarks.RIGHT_EYE_INNER)
        val smile = bs?.let { ((it["mouthSmileLeft"] ?: 0f) + (it["mouthSmileRight"] ?: 0f)) / 2f }
        val jaw = bs?.get("jawOpen")
        val mouthRatio = jaw?.let { 0.28f + 0.3f * it } ?: run {
            val h = maxY - minY
            if (h > 0f) (m.y(FaceLandmarks.LOWER_LIP_BOTTOM) - m.y(FaceLandmarks.NOSE_BOTTOM)) / h else null
        }
        val (yaw, pitch, roll) = FaceGeometry.eulerFromMesh(m)
        return FaceObservation(
            trackingId = 0,
            box = NormRect(minX, minY, maxX, maxY),
            leftEyeOpen = leftOpen, rightEyeOpen = rightOpen, smile = smile,
            eulerX = pitch, eulerY = yaw, eulerZ = roll, mouthOpenRatio = mouthRatio,
        )
    }

    /** Eye aspect ratio mapped to a pseudo-probability (0.22 closed … 0.34 open typical). */
    private fun eyeOpenFromLandmarks(m: FaceMeshResult, top: Int, bottom: Int, outer: Int, inner: Int): Float {
        val w = kotlin.math.hypot(m.x(outer) - m.x(inner), m.y(outer) - m.y(inner)).coerceAtLeast(1e-4f)
        val h = kotlin.math.hypot(m.x(top) - m.x(bottom), m.y(top) - m.y(bottom))
        val ratio = h / w
        return ((ratio - 0.12f) / 0.18f).coerceIn(0f, 1f)
    }

}
