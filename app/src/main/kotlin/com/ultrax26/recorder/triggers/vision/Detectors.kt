package com.ultrax26.recorder.triggers.vision

import android.content.Context
import com.ultrax26.recorder.settings.FaceGestureConfig
import com.ultrax26.recorder.settings.HandGestureConfig

/** Creates the ML-backed detectors (kept behind interfaces so the core stays testable without the ML libraries). */
object DetectorFactory {
    fun hand(context: Context, cfg: HandGestureConfig): HandDetector = MediaPipeHandDetector(context, cfg)
    fun face(cfg: FaceGestureConfig): FaceDetector = MlKitFaceDetector(cfg)
}
