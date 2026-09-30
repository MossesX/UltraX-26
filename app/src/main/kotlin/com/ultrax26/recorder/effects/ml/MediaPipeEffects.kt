package com.ultrax26.recorder.effects.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.ultrax26.recorder.effects.BodyPoseDetector
import com.ultrax26.recorder.effects.FaceMeshDetector
import com.ultrax26.recorder.effects.FaceMeshResult
import com.ultrax26.recorder.effects.PersonSegmenter
import com.ultrax26.recorder.effects.PoseResult
import com.ultrax26.recorder.effects.SegmentationMasks
import com.ultrax26.recorder.settings.MlDelegate
import com.ultrax26.recorder.triggers.vision.VisionFrame
import com.ultrax26.recorder.util.UxLog
import java.nio.ByteOrder

private fun base(model: String, delegate: MlDelegate) = BaseOptions.builder().setModelAssetPath(model).setDelegate(if (delegate == MlDelegate.GPU) Delegate.GPU else Delegate.CPU).build()

/** MediaPipe Face Landmarker: 478 landmarks + blendshapes + facial transformation matrix. */
class MediaPipeFaceMesh(context: Context, delegate: MlDelegate) : FaceMeshDetector {
    private val landmarker: FaceLandmarker = try { build(context, delegate) } catch (t: Throwable) { UxLog.w("FaceMesh", "delegate failed: ${t.message}"); build(context, MlDelegate.CPU) }
    private var lastTs = 0L

    private fun build(context: Context, delegate: MlDelegate): FaceLandmarker = FaceLandmarker.createFromOptions(context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base("face_landmarker.task", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build())

    override fun detect(frame: VisionFrame): FaceMeshResult? {
        val ts = if (frame.timestampMs <= lastTs) lastTs + 1 else frame.timestampMs
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame.bitmap).build(), ImageProcessingOptions.builder().setRotationDegrees(frame.rotationDegrees).build(), ts)
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val lm = faces[0]
        val arr = FloatArray(lm.size * 3)
        for (i in lm.indices) { val p = lm[i]; arr[i * 3] = p.x(); arr[i * 3 + 1] = p.y(); arr[i * 3 + 2] = p.z() }
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val bs = result.faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }
        return FaceMeshResult(arr, matrix, bs, ts)
    }

    override fun close() { try { landmarker.close() } catch (_: Throwable) { } }
}

/**
 * MediaPipe Image Segmenter. With the multiclass selfie model the confidence masks are
 * [background, hair, body-skin, face-skin, clothes, others]; with the single-class model only [person].
 * Output is packed as RGBA = person, hair, faceSkin, clothes.
 */
class MediaPipeSegmenter(context: Context, private val multiClass: Boolean, delegate: MlDelegate) : PersonSegmenter {
    private val segmenter: ImageSegmenter = try { build(context, delegate) } catch (t: Throwable) { UxLog.w("Segmenter", "delegate failed: ${t.message}"); build(context, MlDelegate.CPU) }
    private var lastTs = 0L
    private val inputW = 256
    private var scaled: Bitmap? = null
    private var previous: ByteArray? = null

    private fun build(context: Context, delegate: MlDelegate): ImageSegmenter = ImageSegmenter.createFromOptions(context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base(if (multiClass) "selfie_multiclass_256x256.tflite" else "selfie_segmenter.tflite", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build())

    override fun segment(frame: VisionFrame): SegmentationMasks? {
        val ts = if (frame.timestampMs <= lastTs) lastTs + 1 else frame.timestampMs
        lastTs = ts
        // Rotate + downscale on the CPU so the output mask is upright and small.
        val src = frame.bitmap
        val upright = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val outW = if (upright) (inputW * src.height / src.width).coerceAtLeast(16) else inputW
        val outH = if (upright) inputW else (inputW * src.height / src.width).coerceAtLeast(16)
        val m = Matrix()
        m.postRotate(frame.rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val small = Bitmap.createScaledBitmap(rotated, outW, outH, true)
        if (rotated !== src) rotated.recycle()
        val result = segmenter.segmentForVideo(BitmapImageBuilder(small).build(), ts)
        val masks = result.confidenceMasks().orElse(null) ?: run { small.recycle(); return null }
        if (masks.isEmpty()) { small.recycle(); return null }
        val w = masks[0].width; val h = masks[0].height
        val n = w * h
        val out = ByteArray(n * 4)
        fun floats(idx: Int): java.nio.FloatBuffer? = masks.getOrNull(idx)?.let { ByteBufferExtractor.extract(it).order(ByteOrder.nativeOrder()).asFloatBuffer() }
        if (multiClass && masks.size >= 5) {
            val bgB = floats(0)!!; val hair = floats(1)!!; val faceSkin = floats(3)!!; val clothes = floats(4)!!
            for (i in 0 until n) {
                out[i * 4] = ((1f - bgB.get(i)).coerceIn(0f, 1f) * 255f).toInt().toByte()
                out[i * 4 + 1] = (hair.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte()
                out[i * 4 + 2] = (faceSkin.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte()
                out[i * 4 + 3] = (clothes.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        } else {
            val person = floats(0)!!
            for (i in 0 until n) { out[i * 4] = (person.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte(); out[i * 4 + 1] = 0; out[i * 4 + 2] = 0; out[i * 4 + 3] = 0 }
        }
        // Temporal smoothing against the previous mask (same size) to reduce flicker.
        val prev = previous
        if (prev != null && prev.size == out.size) for (i in out.indices) { val a = out[i].toInt() and 0xFF; val b = prev[i].toInt() and 0xFF; out[i] = ((a * 0.65f + b * 0.35f).toInt()).toByte() }
        previous = out.copyOf()
        masks.forEach { try { it.close() } catch (_: Throwable) { } }
        small.recycle()
        return SegmentationMasks(w, h, out, ts)
    }

    override fun close() { try { segmenter.close() } catch (_: Throwable) { } }
}

/** MediaPipe Pose Landmarker (lite): 33 body landmarks for costume anchoring. */
class MediaPipePose(context: Context, delegate: MlDelegate) : BodyPoseDetector {
    private val landmarker: PoseLandmarker = try { build(context, delegate) } catch (t: Throwable) { UxLog.w("Pose", "delegate failed: ${t.message}"); build(context, MlDelegate.CPU) }
    private var lastTs = 0L

    private fun build(context: Context, delegate: MlDelegate): PoseLandmarker = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base("pose_landmarker_lite.task", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .build())

    override fun detect(frame: VisionFrame): PoseResult? {
        val ts = if (frame.timestampMs <= lastTs) lastTs + 1 else frame.timestampMs
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame.bitmap).build(), ImageProcessingOptions.builder().setRotationDegrees(frame.rotationDegrees).build(), ts)
        val poses = result.landmarks()
        if (poses.isEmpty()) return null
        val lm = poses[0]
        val arr = FloatArray(lm.size * 4)
        for (i in lm.indices) { val p = lm[i]; arr[i * 4] = p.x(); arr[i * 4 + 1] = p.y(); arr[i * 4 + 2] = p.z(); arr[i * 4 + 3] = p.visibility().orElse(1f) }
        return PoseResult(arr, ts)
    }

    override fun close() { try { landmarker.close() } catch (_: Throwable) { } }
}

/** Factory used by the app graph (a harness stub replaces this file when the ML libraries are absent). */
object EffectsDetectorFactory {
    fun faceMesh(context: Context, delegate: MlDelegate): FaceMeshDetector = MediaPipeFaceMesh(context, delegate)
    fun segmenter(context: Context, multiClass: Boolean, delegate: MlDelegate): PersonSegmenter = MediaPipeSegmenter(context, multiClass, delegate)
    fun pose(context: Context, delegate: MlDelegate): BodyPoseDetector = MediaPipePose(context, delegate)
}
