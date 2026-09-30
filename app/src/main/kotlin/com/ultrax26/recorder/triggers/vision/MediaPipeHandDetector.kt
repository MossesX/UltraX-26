package com.ultrax26.recorder.triggers.vision

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.ultrax26.recorder.settings.HandGestureConfig
import com.ultrax26.recorder.settings.MlDelegate
import com.ultrax26.recorder.triggers.HandGestureType
import com.ultrax26.recorder.triggers.Handedness
import com.ultrax26.recorder.util.UxLog

/**
 * MediaPipe Tasks GestureRecognizer (canned model: Thumb_Up/Down, Open_Palm, Closed_Fist, Victory,
 * Pointing_Up, ILoveYou + 21 landmarks per hand). Runs in VIDEO mode on the analysis thread.
 */
class MediaPipeHandDetector(context: Context, cfg: HandGestureConfig) : HandDetector {
    private val recognizer: GestureRecognizer

    init {
        recognizer = try { build(context, cfg, cfg.delegate) } catch (t: Throwable) {
            UxLog.w("MediaPipe", "delegate ${cfg.delegate} failed (${t.message}); falling back to CPU")
            build(context, cfg, MlDelegate.CPU)
        }
    }

    private fun build(context: Context, cfg: HandGestureConfig, delegate: MlDelegate): GestureRecognizer {
        val base = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .setDelegate(if (delegate == MlDelegate.GPU) Delegate.GPU else Delegate.CPU)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(cfg.maxHands.coerceIn(1, 2))
            .setMinHandDetectionConfidence(cfg.minDetectionConfidence)
            .setMinHandPresenceConfidence(cfg.minPresenceConfidence)
            .setMinTrackingConfidence(cfg.minTrackingConfidence)
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }

    override fun detect(frame: VisionFrame): List<HandObservation> {
        val mp = BitmapImageBuilder(frame.bitmap).build()
        val ipo = ImageProcessingOptions.builder().setRotationDegrees(frame.rotationDegrees).build()
        val result = recognizer.recognizeForVideo(mp, ipo, frame.timestampMs)
        val gestures = result.gestures()
        val handed = result.handednesses()
        val lms = result.landmarks()
        val out = ArrayList<HandObservation>(lms.size)
        for (i in lms.indices) {
            val g = gestures.getOrNull(i)?.firstOrNull()
            val hcat = handed.getOrNull(i)?.firstOrNull()
            // MediaPipe labels assume a mirrored (selfie) image; our raw frames are not mirrored → swap.
            val rawLeft = hcat?.categoryName()?.equals("Left", ignoreCase = true) ?: false
            val handedness = if (hcat == null) null else if (frame.mirrored) (if (rawLeft) Handedness.LEFT else Handedness.RIGHT) else (if (rawLeft) Handedness.RIGHT else Handedness.LEFT)
            val type = g?.categoryName()?.let { HandGestureType.fromModelName(it) }
            out += HandObservation(
                gesture = type,
                gestureScore = g?.score() ?: 0f,
                handedness = handedness,
                handednessScore = hcat?.score() ?: 0f,
                landmarks = lms[i].map { NormPoint(it.x(), it.y(), it.z()) },
            )
        }
        return out
    }

    override fun close() { try { recognizer.close() } catch (_: Throwable) { } }
}
