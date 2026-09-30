package com.ultrax26.recorder.camera

import android.util.Size
import android.view.Surface
import com.ultrax26.recorder.settings.CaptureSettings
import com.ultrax26.recorder.settings.CustomKeyValue

/** How the analysis (gesture/scopes) stream should be configured. */
data class AnalysisPlan(val size: Size, val targetFps: Int, val allowP010: Boolean)

/** A complete description of the streams and modes a camera session should have. */
data class SessionPlan(
    val cameraId: String,
    val physicalCameraId: String?,
    val previewSurface: Surface?,
    val previewSize: Size,
    val encoderSurface: Surface?,
    val recordSize: Size,
    val fps: Int,
    val highSpeed: Boolean,
    val analysis: AnalysisPlan?,
    val dynamicRangeProfile: Long,
    val capture: CaptureSettings,
    val timelapseFactor: Int = 1,
    val mirrorRecording: Boolean = false,
    val useStreamUseCases: Boolean = true,
    val sessionParameters: List<CustomKeyValue> = emptyList(),
) {
    val recordAspect: Float get() = recordSize.width.toFloat() / recordSize.height
}
