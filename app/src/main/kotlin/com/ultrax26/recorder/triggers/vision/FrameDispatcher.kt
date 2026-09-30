package com.ultrax26.recorder.triggers.vision

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import com.ultrax26.recorder.settings.OverlaySettings
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pulls analysis images off the camera's ImageReader (on the analysis thread), converts them once to
 * an ARGB bitmap, and feeds the hand/face detectors, their interpreters and the scopes analyzer.
 */
class FrameDispatcher(
    private val handInterpreter: HandGestureInterpreter,
    private val faceInterpreter: FaceGestureInterpreter,
    private val scopes: ScopesAnalyzer,
) : ImageReader.OnImageAvailableListener {
    private val tag = "Frames"
    private val converter = YuvToRgb()
    private var bitmap: Bitmap? = null
    private var lastProcessedMs = 0L
    private var fpsEma = 0f
    private var lastFrameMs = 0L
    private var lastMpTimestamp = 0L

    @Volatile var handDetector: HandDetector? = null
    @Volatile var faceDetector: FaceDetector? = null
    @Volatile var handsEnabled = true
    @Volatile var facesEnabled = true
    @Volatile var targetFps = 12
    @Volatile var sensorOrientation = 90
    @Volatile var displayRotationDegrees = 0
    @Volatile var frontFacing = false
    @Volatile var overlays = OverlaySettings()
    @Volatile var scopesEveryNth = 2
    private var frameCounter = 0

    val hud = MutableStateFlow(VisionHudState())

    /** Rotation that makes the sensor image upright for the current display orientation. */
    fun rotationDegrees(): Int = if (frontFacing) (sensorOrientation + displayRotationDegrees) % 360
                                 else (sensorOrientation - displayRotationDegrees + 360) % 360

    override fun onImageAvailable(reader: ImageReader) {
        val image: Image = reader.acquireLatestImage() ?: return
        try {
            val now = Clock.bootMs()
            val minInterval = 1000L / targetFps.coerceIn(1, 60)
            if (now - lastProcessedMs < minInterval) return
            lastProcessedMs = now
            process(image, now)
        } catch (t: Throwable) {
            UxLog.w(tag, "frame failed: ${t.message}")
            hud.value = hud.value.copy(lastError = t.message)
        } finally {
            image.close()
        }
    }

    private fun process(image: Image, nowMs: Long) {
        val w = image.width; val h = image.height
        val bmp = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        if (!converter.convert(image, bmp)) { hud.value = hud.value.copy(lastError = "unsupported analysis format ${image.format}"); return }
        val rot = rotationDegrees()
        val t0 = System.nanoTime()
        // MediaPipe VIDEO mode needs strictly increasing timestamps.
        val ts = if (nowMs <= lastMpTimestamp) lastMpTimestamp + 1 else nowMs
        lastMpTimestamp = ts
        val frame = VisionFrame(bmp, rot, ts, mirrored = false)

        var hands: List<HandObservation> = emptyList()
        var faces: List<FaceObservation> = emptyList()
        var err: String? = null
        val hd = handDetector
        if (handsEnabled && hd != null) {
            try { hands = hd.detect(frame) } catch (t: Throwable) { err = "hands: ${t.message}" }
            handInterpreter.process(hands, nowMs)
        }
        val fd = faceDetector
        if (facesEnabled && fd != null) {
            try { faces = fd.detect(frame) } catch (t: Throwable) { err = "faces: ${t.message}" }
            faceInterpreter.process(faces, nowMs)
        }
        if (++frameCounter % scopesEveryNth == 0) {
            try { scopes.analyze(converter.pixels, w, h, overlays) } catch (t: Throwable) { UxLog.w(tag, "scopes: ${t.message}") }
        }
        val infMs = (System.nanoTime() - t0) / 1e6f
        if (lastFrameMs != 0L) { val f = 1000f / (nowMs - lastFrameMs).coerceAtLeast(1); fpsEma = if (fpsEma == 0f) f else fpsEma * 0.9f + f * 0.1f }
        lastFrameMs = nowMs
        val upright = rot == 90 || rot == 270
        hud.value = VisionHudState(
            hands = hands, faces = faces, analysisFps = fpsEma, inferenceMs = infMs,
            heldGesture = handInterpreter.currentLabel, heldMs = handInterpreter.currentHeldMs,
            blinkCount = faceInterpreter.blinkCount, fingerCount = handInterpreter.currentFingers,
            frameWidth = if (upright) h else w, frameHeight = if (upright) w else h,
            handsAvailable = hd != null, facesAvailable = fd != null, lastError = err,
        )
    }

    fun close() {
        try { handDetector?.close() } catch (_: Throwable) { }
        try { faceDetector?.close() } catch (_: Throwable) { }
        handDetector = null; faceDetector = null
        bitmap?.recycle(); bitmap = null
    }
}
