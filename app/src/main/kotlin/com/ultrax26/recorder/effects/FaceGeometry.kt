package com.ultrax26.recorder.effects

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** MediaPipe face-mesh landmark indices used by the effects. */
object FaceLandmarks {
    const val NOSE_TIP = 1; const val NOSE_BOTTOM = 2; const val NOSE_BRIDGE = 6; const val GLABELLA = 9; const val FOREHEAD_TOP = 10
    const val CHIN = 152; const val LEFT_FACE = 234; const val RIGHT_FACE = 454
    const val LEFT_EYE_OUTER = 33; const val LEFT_EYE_INNER = 133; const val LEFT_EYE_TOP = 159; const val LEFT_EYE_BOTTOM = 145
    const val RIGHT_EYE_OUTER = 263; const val RIGHT_EYE_INNER = 362; const val RIGHT_EYE_TOP = 386; const val RIGHT_EYE_BOTTOM = 374
    const val LEFT_IRIS = 468; const val RIGHT_IRIS = 473
    const val UPPER_LIP_TOP = 0; const val UPPER_LIP_BOTTOM = 13; const val LOWER_LIP_TOP = 14; const val LOWER_LIP_BOTTOM = 17
    const val MOUTH_LEFT = 61; const val MOUTH_RIGHT = 291
    const val LEFT_CHEEK = 50; const val RIGHT_CHEEK = 280
    const val LEFT_BROW = 105; const val RIGHT_BROW = 334
    const val LEFT_EAR = 234; const val RIGHT_EAR = 454

    val FACE_OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
    val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    val LEFT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val RIGHT_EYE = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)
}

/** 2D point in "scene metric" space: x in [0, aspect], y in [0, 1], y down. */
data class P2(val x: Float, val y: Float) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun times(k: Float) = P2(x * k, y * k)
    fun len() = hypot(x, y)
}

/**
 * Maps points from the upright analysis frame (normalized) to the *sensor-oriented scene* the GL
 * pipeline renders (normalized, then metric with x scaled by the scene aspect).
 */
class SceneMapping(val rotationDegrees: Int, val frameAspect: Float, val sceneAspect: Float) {
    private val rot = ((rotationDegrees % 360) + 360) % 360
    /** Aspect of the scene once rotated upright. */
    val uprightAspect: Float = if (rot == 90 || rot == 270) 1f / sceneAspect else sceneAspect

    /** Analysis-frame coords → upright scene coords (both center crops of the same sensor). */
    fun fitToScene(u: Float, v: Float): P2 {
        val f = if (frameAspect > 0f) frameAspect else uprightAspect
        return if (f > uprightAspect + 0.01f) P2(0.5f + (u - 0.5f) * (f / uprightAspect), v)
        else if (f < uprightAspect - 0.01f) P2(u, 0.5f + (v - 0.5f) * (uprightAspect / f))
        else P2(u, v)
    }

    /** Upright scene coords → scene normalized (sensor orientation), rotation only. */
    fun rotateOnly(u: Float, v: Float): P2 = when (rot) {
        90 -> P2(v, 1f - u)
        180 -> P2(1f - u, 1f - v)
        270 -> P2(1f - v, u)
        else -> P2(u, v)
    }

    /** Upright normalized (u,v) of the analysis frame → scene normalized (sensor orientation). */
    fun toSceneNorm(u: Float, v: Float): P2 { val q = fitToScene(u, v); return rotateOnly(q.x, q.y) }
    fun toMetric(u: Float, v: Float): P2 { val n = toSceneNorm(u, v); return P2(n.x * sceneAspect, n.y) }
    /** Linear part of the upright→scene map in metric space (a pure rotation), as 2x2 [a b; c d]. */
    fun linear(): FloatArray {
        val o = toMetric(0f, 0f); val ex = toMetric(1f / frameAspectSafe(), 0f) - o; val ey = toMetric(0f, 1f) - o
        // ex corresponds to one metric unit along upright x, ey along upright y
        return floatArrayOf(ex.x, ey.x, ex.y, ey.y)
    }
    private fun frameAspectSafe() = if (frameAspect > 0f) frameAspect else 1f
}

/** Head pose and key points of one face in scene metric space. */
class FaceFrame(
    val center: P2, val width: Float, val height: Float, val roll: Float, val yaw: Float, val pitch: Float,
    val eyes: P2, val leftEye: P2, val rightEye: P2, val nose: P2, val noseBridge: P2, val upperLip: P2, val mouth: P2, val chin: P2,
    val forehead: P2, val brows: P2, val headTop: P2, val leftEar: P2, val rightEar: P2, val leftCheek: P2, val rightCheek: P2, val neck: P2,
    val rotation3: FloatArray, // 3x3 row-major head rotation in scene space (y down)
    val faceOval: FloatArray,  // metric polygon (x,y pairs)
    val lipsOuter: FloatArray, val lipsInner: FloatArray, val leftEyePoly: FloatArray, val rightEyePoly: FloatArray,
) {
    fun anchor(a: Anchor, pose: PoseFrame?, sceneAspect: Float): P2 = when (a) {
        Anchor.HEAD_TOP -> headTop; Anchor.FOREHEAD -> forehead; Anchor.BROWS -> brows; Anchor.EYES -> eyes; Anchor.LEFT_EYE -> leftEye; Anchor.RIGHT_EYE -> rightEye
        Anchor.NOSE_BRIDGE -> noseBridge; Anchor.NOSE -> nose; Anchor.UPPER_LIP -> upperLip; Anchor.MOUTH -> mouth; Anchor.CHIN -> chin
        Anchor.LEFT_EAR -> leftEar; Anchor.RIGHT_EAR -> rightEar; Anchor.LEFT_CHEEK -> leftCheek; Anchor.RIGHT_CHEEK -> rightCheek
        Anchor.FACE_CENTER, Anchor.FACE_FULL, Anchor.BEHIND_HEAD -> center; Anchor.NECK -> neck
        Anchor.LEFT_SHOULDER -> pose?.leftShoulder ?: (neck + down(0.55f) + right(-0.9f))
        Anchor.RIGHT_SHOULDER -> pose?.rightShoulder ?: (neck + down(0.55f) + right(0.9f))
        Anchor.CHEST -> pose?.chest ?: (neck + down(1.0f))
        Anchor.TORSO, Anchor.BEHIND_BODY -> pose?.torso ?: (neck + down(1.4f))
        Anchor.FRAME_TOP_LEFT -> P2(0f, 0f); Anchor.FRAME_TOP_RIGHT -> P2(sceneAspect, 0f)
        Anchor.FRAME_BOTTOM_LEFT -> P2(0f, 1f); Anchor.FRAME_BOTTOM_RIGHT -> P2(sceneAspect, 1f); Anchor.FRAME_CENTER -> P2(sceneAspect / 2f, 0.5f)
    }
    /** Unit vectors of the face frame in scene space (x to the face's right, y down toward the chin). */
    fun right(k: Float): P2 = P2(cos(roll) * width * k, sin(roll) * width * k)
    fun down(k: Float): P2 = P2(-sin(roll) * height * k, cos(roll) * height * k)
}

class PoseFrame(val leftShoulder: P2, val rightShoulder: P2, val chest: P2, val torso: P2, val shoulderWidth: Float)

object FaceGeometry {
    /** Yaw/pitch/roll (degrees) from the mesh: matrix when available, else landmark geometry. */
    fun eulerFromMesh(m: FaceMeshResult): Triple<Float, Float, Float> {
        val mat = m.matrix
        if (mat != null && mat.size >= 16) {
            // column-major 4x4; rotation columns c0=(m0,m1,m2) c1=(m4,m5,m6) c2=(m8,m9,m10)
            val r00 = mat[0]; val r10 = mat[1]; val r20 = mat[2]; val r21 = mat[6]; val r22 = mat[10]
            val pitch = Math.toDegrees(atan2(r21.toDouble(), r22.toDouble())).toFloat()
            val yaw = Math.toDegrees(asin((-r20).coerceIn(-1f, 1f).toDouble())).toFloat()
            val roll = Math.toDegrees(atan2(r10.toDouble(), r00.toDouble())).toFloat()
            return Triple(yaw, pitch, roll)
        }
        val le = P2(m.x(FaceLandmarks.LEFT_EYE_OUTER), m.y(FaceLandmarks.LEFT_EYE_OUTER)); val re = P2(m.x(FaceLandmarks.RIGHT_EYE_OUTER), m.y(FaceLandmarks.RIGHT_EYE_OUTER))
        val roll = Math.toDegrees(atan2((re.y - le.y).toDouble(), (re.x - le.x).toDouble())).toFloat()
        val w = (re - le).len().coerceAtLeast(1e-4f)
        val nose = P2(m.x(FaceLandmarks.NOSE_TIP), m.y(FaceLandmarks.NOSE_TIP)); val mid = (le + re) * 0.5f
        val yaw = ((nose.x - mid.x) / w * 90f).coerceIn(-60f, 60f)
        val chin = P2(m.x(FaceLandmarks.CHIN), m.y(FaceLandmarks.CHIN))
        val eyeToChin = (chin - mid).len().coerceAtLeast(1e-4f)
        val pitch = (((nose.y - mid.y) / eyeToChin - 0.42f) * -120f).coerceIn(-50f, 50f)
        return Triple(yaw, pitch, roll)
    }

    /** Build the scene-space [FaceFrame] from a mesh result. */
    fun frame(m: FaceMeshResult, map: SceneMapping, invertYaw: Boolean = false, invertPitch: Boolean = false): FaceFrame {
        fun p(i: Int) = map.toMetric(m.x(i), m.y(i))
        fun mid(a: Int, b: Int) = (p(a) + p(b)) * 0.5f
        val left = p(FaceLandmarks.LEFT_FACE); val right = p(FaceLandmarks.RIGHT_FACE)
        val top = p(FaceLandmarks.FOREHEAD_TOP); val chin = p(FaceLandmarks.CHIN)
        val width = (right - left).len().coerceAtLeast(1e-4f)
        val height = (chin - top).len().coerceAtLeast(1e-4f)
        val leftEye = if (m.count > FaceLandmarks.RIGHT_IRIS) p(FaceLandmarks.LEFT_IRIS) else mid(FaceLandmarks.LEFT_EYE_OUTER, FaceLandmarks.LEFT_EYE_INNER)
        val rightEye = if (m.count > FaceLandmarks.RIGHT_IRIS) p(FaceLandmarks.RIGHT_IRIS) else mid(FaceLandmarks.RIGHT_EYE_OUTER, FaceLandmarks.RIGHT_EYE_INNER)
        val eyes = (leftEye + rightEye) * 0.5f
        val roll = atan2(rightEye.y - leftEye.y, rightEye.x - leftEye.x)
        val center = (top + chin + left + right) * 0.25f
        val downDir = P2(-sin(roll), cos(roll))
        val headTop = top + downDir * (-0.42f * height)
        val neck = chin + downDir * (0.35f * height)
        val (yawDeg, pitchDeg, _) = eulerFromMesh(m)
        val yaw = Math.toRadians((if (invertYaw) -yawDeg else yawDeg).toDouble()).toFloat()
        val pitch = Math.toRadians((if (invertPitch) -pitchDeg else pitchDeg).toDouble()).toFloat()
        val rot = rotation(roll, yaw, pitch)
        fun poly(idx: IntArray): FloatArray { val out = FloatArray(idx.size * 2); for (k in idx.indices) { val q = p(idx[k]); out[k * 2] = q.x; out[k * 2 + 1] = q.y }; return out }
        return FaceFrame(
            center = center, width = width, height = height, roll = roll, yaw = yaw, pitch = pitch,
            eyes = eyes, leftEye = leftEye, rightEye = rightEye, nose = p(FaceLandmarks.NOSE_TIP), noseBridge = p(FaceLandmarks.NOSE_BRIDGE),
            upperLip = mid(FaceLandmarks.NOSE_BOTTOM, FaceLandmarks.UPPER_LIP_TOP), mouth = mid(FaceLandmarks.UPPER_LIP_BOTTOM, FaceLandmarks.LOWER_LIP_TOP), chin = chin,
            forehead = mid(FaceLandmarks.FOREHEAD_TOP, FaceLandmarks.GLABELLA), brows = mid(FaceLandmarks.LEFT_BROW, FaceLandmarks.RIGHT_BROW), headTop = headTop,
            leftEar = left + P2(cos(roll), sin(roll)) * (-0.06f * width), rightEar = right + P2(cos(roll), sin(roll)) * (0.06f * width),
            leftCheek = p(FaceLandmarks.LEFT_CHEEK), rightCheek = p(FaceLandmarks.RIGHT_CHEEK), neck = neck,
            rotation3 = rot, faceOval = poly(FaceLandmarks.FACE_OVAL), lipsOuter = poly(FaceLandmarks.LIPS_OUTER), lipsInner = poly(FaceLandmarks.LIPS_INNER),
            leftEyePoly = poly(FaceLandmarks.LEFT_EYE), rightEyePoly = poly(FaceLandmarks.RIGHT_EYE),
        )
    }

    fun poseFrame(p: PoseResult, map: SceneMapping): PoseFrame? {
        if (p.visibility(PoseResult.LEFT_SHOULDER) < 0.3f && p.visibility(PoseResult.RIGHT_SHOULDER) < 0.3f) return null
        val ls = map.toMetric(p.x(PoseResult.LEFT_SHOULDER), p.y(PoseResult.LEFT_SHOULDER))
        val rs = map.toMetric(p.x(PoseResult.RIGHT_SHOULDER), p.y(PoseResult.RIGHT_SHOULDER))
        val lh = map.toMetric(p.x(PoseResult.LEFT_HIP), p.y(PoseResult.LEFT_HIP))
        val rh = map.toMetric(p.x(PoseResult.RIGHT_HIP), p.y(PoseResult.RIGHT_HIP))
        val shoulders = (ls + rs) * 0.5f
        val hips = (lh + rh) * 0.5f
        val chest = shoulders + (hips - shoulders) * 0.28f
        val torso = shoulders + (hips - shoulders) * 0.5f
        return PoseFrame(ls, rs, chest, torso, (rs - ls).len())
    }

    /** Row-major 3x3 rotation = Rz(roll) * Ry(yaw) * Rx(pitch) in a y-down, z-toward-viewer frame. */
    fun rotation(roll: Float, yaw: Float, pitch: Float): FloatArray {
        val cr = cos(roll); val sr = sin(roll); val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        // Ry(yaw)
        val ry = floatArrayOf(cy, 0f, sy, 0f, 1f, 0f, -sy, 0f, cy)
        // Rx(pitch)
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cp, -sp, 0f, sp, cp)
        // Rz(roll)
        val rz = floatArrayOf(cr, -sr, 0f, sr, cr, 0f, 0f, 0f, 1f)
        return mul3(rz, mul3(ry, rx))
    }

    fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) { var s = 0f; for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]; o[r * 3 + c] = s }
        return o
    }

    fun apply3(r: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(r[0] * x + r[1] * y + r[2] * z, r[3] * x + r[4] * y + r[5] * z, r[6] * x + r[7] * y + r[8] * z)

    /**
     * Project a sticker quad into scene metric space. Returns 8 floats (4 corners x,y) in the order
     * top-left, top-right, bottom-right, bottom-left, and the depth-derived scale factor.
     */
    fun stickerQuad(face: FaceFrame?, layer: StickerLayer, asset: StickerAsset?, pose: PoseFrame?, sceneAspect: Float, timeSec: Float, mirrorX: Boolean): FloatArray? {
        val aspect = asset?.aspect ?: 1f
        val anchorFrame = layer.anchor.frame
        if (face == null && !anchorFrame) return null
        val unit = if (anchorFrame) 1f else face!!.width
        val anchor = if (anchorFrame) frameAnchor(layer.anchor, sceneAspect) else face!!.anchor(layer.anchor, pose, sceneAspect)
        val pulse = 1f + layer.pulse * sin(timeSec * 3.1f)
        val w = unit * layer.scale * pulse
        val h = w / aspect
        val spin = Math.toRadians((layer.spinDegPerSec * timeSec).toDouble()).toFloat()
        val rot: FloatArray = when {
            anchorFrame || face == null -> rotation(spin, 0f, 0f)
            layer.rotation == RotationMode.NONE -> rotation(spin, 0f, 0f)
            layer.rotation == RotationMode.BILLBOARD -> rotation(face.roll + spin, 0f, 0f)
            else -> mul3(face.rotation3, rotation(spin, 0f, 0f))
        }
        val ox = layer.offsetX * unit * (if (mirrorX) -1f else 1f)
        val oy = layer.offsetY * unit
        val oz = layer.offsetZ * unit
        val focal = 2.6f * unit
        val out = FloatArray(8)
        val corners = arrayOf(floatArrayOf(-w / 2, -h / 2), floatArrayOf(w / 2, -h / 2), floatArrayOf(w / 2, h / 2), floatArrayOf(-w / 2, h / 2))
        for (i in 0 until 4) {
            val cx = corners[i][0] * (if (layer.mirrorX xor mirrorX) -1f else 1f) + ox
            val cy = corners[i][1] + oy
            val v = apply3(rot, cx, cy, oz)
            val persp = focal / (focal - v[2]).coerceAtLeast(focal * 0.35f)
            out[i * 2] = anchor.x + v[0] * persp
            out[i * 2 + 1] = anchor.y + v[1] * persp
        }
        return out
    }

    /** Exponential smoothing of landmark arrays with a motion-adaptive factor (One-Euro-lite). */
    class Smoother(private val minAlpha: Float = 0.25f, private val maxAlpha: Float = 0.85f) {
        private var state: FloatArray? = null
        fun reset() { state = null }
        fun apply(input: FloatArray): FloatArray {
            val s = state
            if (s == null || s.size != input.size) { state = input.copyOf(); return input }
            var motion = 0f
            val n = minOf(input.size, 3 * 60)
            var i = 0
            while (i < n) { motion += abs(input[i] - s[i]); i += 3 }
            val alpha = (minAlpha + motion * 25f).coerceIn(minAlpha, maxAlpha)
            for (k in input.indices) s[k] += (input[k] - s[k]) * alpha
            return s
        }
    }

}

internal fun frameAnchor(a: Anchor, sceneAspect: Float): P2 = when (a) {
    Anchor.FRAME_TOP_LEFT -> P2(0f, 0f); Anchor.FRAME_TOP_RIGHT -> P2(sceneAspect, 0f)
    Anchor.FRAME_BOTTOM_LEFT -> P2(0f, 1f); Anchor.FRAME_BOTTOM_RIGHT -> P2(sceneAspect, 1f); else -> P2(sceneAspect / 2f, 0.5f)
}
