package com.ultrax26.recorder.triggers.vision

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.ultrax26.recorder.settings.FaceGestureConfig
import java.util.concurrent.TimeUnit

/** ML Kit face detection (bundled model): eye-open / smile probabilities, Euler angles, landmarks. */
class MlKitFaceDetector(cfg: FaceGestureConfig) : FaceDetector {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(cfg.minFaceSize.coerceIn(0.05f, 0.5f))
            .enableTracking()
            .build()
    )

    override fun detect(frame: VisionFrame): List<FaceObservation> {
        val image = InputImage.fromBitmap(frame.bitmap, frame.rotationDegrees)
        val faces = Tasks.await(detector.process(image), 1500, TimeUnit.MILLISECONDS)
        val upright = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val w = (if (upright) frame.bitmap.height else frame.bitmap.width).toFloat()
        val h = (if (upright) frame.bitmap.width else frame.bitmap.height).toFloat()
        return faces.map { f ->
            val b = f.boundingBox
            val mouthBottom = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
            val noseBase = f.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthRatio = if (mouthBottom != null && noseBase != null && b.height() > 0) (mouthBottom.y - noseBase.y) / b.height() else null
            FaceObservation(
                trackingId = f.trackingId,
                box = NormRect(b.left / w, b.top / h, b.right / w, b.bottom / h),
                leftEyeOpen = f.leftEyeOpenProbability,
                rightEyeOpen = f.rightEyeOpenProbability,
                smile = f.smilingProbability,
                eulerX = f.headEulerAngleX,
                eulerY = f.headEulerAngleY,
                eulerZ = f.headEulerAngleZ,
                mouthOpenRatio = mouthRatio,
            )
        }
    }

    override fun close() { try { detector.close() } catch (_: Throwable) { } }
}
