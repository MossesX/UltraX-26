package com.ultrax26.recorder.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Process
import android.util.Range
import android.util.Size
import android.view.Surface
import com.ultrax26.recorder.settings.CaptureSettings
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import com.ultrax26.recorder.util.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Live values decoded from capture results for the HUD. */
data class FrameInfo(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusDistance: Float? = null,
    val zoom: Float? = null,
    val aeState: Int? = null,
    val afState: Int? = null,
    val awbState: Int? = null,
    val focalLength: Float? = null,
    val aperture: Float? = null,
    val activePhysicalId: String? = null,
    val faceCount: Int = 0,
    val flashState: Int? = null,
    val lensState: Int? = null,
    val evComp: Int? = null,
    val sceneFlicker: Int? = null,
    val fpsEstimate: Float = 0f,
    val timestampNs: Long = 0L,
    val frameNumber: Long = 0L,
    val droppedFrames: Long = 0L,
)

/**
 * Owns the CameraDevice + CameraCaptureSession for one [SessionPlan]. Every public method hops to
 * the camera thread; state is exposed as StateFlows.
 */
class CameraEngine(context: Context, private val catalog: CameraCatalog) {
    sealed interface Status {
        data object Closed : Status
        data object Opening : Status
        data class Ready(val plan: SessionPlan, val analysisFormat: Int?, val analysisSize: Size?, val highSpeedRange: Range<Int>?) : Status
        data class Error(val message: String, val recoverable: Boolean) : Status
    }

    private val tag = "CamEngine"
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraThread = WorkerThread("ux-camera", Process.THREAD_PRIORITY_DISPLAY)
    val analysisThread = WorkerThread("ux-analysis", Process.THREAD_PRIORITY_DEFAULT)

    val status = MutableStateFlow<Status>(Status.Closed)
    val frameInfo = MutableStateFlow(FrameInfo())
    val applyReport = MutableStateFlow<ApplyReport?>(null)
    val resultDump = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    @Volatile var dumpResults: Boolean = false

    /** Called on the camera thread for every started frame: (sensor timestamp ns, CLOCK_BOOTTIME ns). */
    @Volatile var onCaptureTimestamp: ((Long, Long) -> Unit)? = null
    /** Consumer of analysis images (set by FrameDispatcher). Called on [analysisThread]. */
    @Volatile var analysisListener: ImageReader.OnImageAvailableListener? = null

    // ---- camera-thread state ----
    private var plan: SessionPlan? = null
    private var chars: CameraCharacteristics? = null
    private var availableKeys: Set<String> = emptySet()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var analysisReader: ImageReader? = null
    private var highSpeedRange: Range<Int>? = null
    private var capture: CaptureSettings = CaptureSettings()
    private var overrides = LiveOverrides()
    private var recordingTarget = false
    private var lastAwbTransform: ColorSpaceTransform? = null
    private var lastFrameTs = 0L
    private var lastInfoPublishMs = 0L
    private var lastDumpMs = 0L
    private var fpsEma = 0f
    private var dropped = 0L
    private var generation = 0
    private var zoomTarget: Float? = null
    private var zoomRampPerSec = 2f
    private var focusPull: FocusPull? = null
    private var afTapRevertAt = 0L

    private data class FocusPull(val from: Float, val to: Float, val startMs: Long, val durationMs: Long)

    val currentPlan: SessionPlan? get() = plan
    val characteristics: CameraCharacteristics? get() = chars
    val analysisSurface: Surface? get() = analysisReader?.surface

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    fun open(newPlan: SessionPlan) = cameraThread.post {
        closeInternal()
        val gen = ++generation
        plan = newPlan
        capture = newPlan.capture
        status.value = Status.Opening
        try {
            val c = catalog.characteristics(newPlan.cameraId)
            chars = c
            availableKeys = Capabilities.availableKeyNames(c)
            openDevice(newPlan, gen)
        } catch (t: Throwable) {
            fail("Cannot read camera ${newPlan.cameraId}: ${t.message}", true)
        }
    }

    fun close() = cameraThread.post { closeInternal(); status.value = Status.Closed }

    fun shutdown() {
        close()
        cameraThread.post { cameraThread.quit(); analysisThread.quit() }
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Throwable) { }
        session = null
        try { device?.close() } catch (_: Throwable) { }
        device = null
        try { analysisReader?.close() } catch (_: Throwable) { }
        analysisReader = null
        recordingTarget = false
        zoomTarget = null
        focusPull = null
        cameraThread.handler.removeCallbacks(zoomTick)
        cameraThread.handler.removeCallbacks(focusPullTick)
    }

    private fun fail(msg: String, recoverable: Boolean) {
        UxLog.e(tag, msg)
        closeInternal()
        status.value = Status.Error(msg, recoverable)
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(p: SessionPlan, gen: Int) {
        try {
            manager.openCamera(p.cameraId, cameraThread.executor, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (gen != generation) { cam.close(); return }
                    device = cam
                    createSession(cam, p, gen, withAnalysis = p.analysis != null)
                }
                override fun onDisconnected(cam: CameraDevice) {
                    if (gen != generation) return
                    fail("Camera disconnected (another app took it?)", true)
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    if (gen != generation) return
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                        ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                        ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Camera error $error"
                    }
                    fail(msg, error != ERROR_CAMERA_DISABLED)
                }
                override fun onClosed(cam: CameraDevice) { }
            })
        } catch (e: CameraAccessException) {
            fail("openCamera: ${e.message}", true)
        } catch (e: SecurityException) {
            fail("Camera permission missing", false)
        } catch (e: IllegalArgumentException) {
            fail("Camera ${p.cameraId} cannot be opened (hidden ID not openable?)", true)
        }
    }

    private fun createSession(cam: CameraDevice, p: SessionPlan, gen: Int, withAnalysis: Boolean) {
        val c = chars ?: return
        val outputs = ArrayList<OutputConfiguration>()
        val useCases = Capabilities.streamUseCases(c)
        val hdr = p.dynamicRangeProfile != DynamicRangeProfiles.STANDARD
        fun configure(oc: OutputConfiguration, useCase: Long?, applyProfile: Boolean, mirror: Boolean = false) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (applyProfile && hdr) oc.dynamicRangeProfile = p.dynamicRangeProfile
                if (p.useStreamUseCases && useCase != null && useCases.contains(useCase)) oc.streamUseCase = useCase
                if (mirror) oc.mirrorMode = OutputConfiguration.MIRROR_MODE_H
            }
            if (p.physicalCameraId != null) oc.setPhysicalCameraId(p.physicalCameraId)
        }

        p.previewSurface?.let { ps ->
            val preview = OutputConfiguration(ps)
            configure(preview, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong(), applyProfile = true)
            outputs += preview
        }

        p.encoderSurface?.let { enc ->
            val rec = OutputConfiguration(enc)
            configure(rec, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong(), applyProfile = true, mirror = p.mirrorRecording)
            outputs += rec
        }

        var analysisFormat: Int? = null
        var analysisSize: Size? = null
        if (withAnalysis && !p.highSpeed && p.analysis != null) {
            val a = p.analysis
            val standardOk = Capabilities.standardAllowedWith(c, p.dynamicRangeProfile)
            val fmt = if (!hdr || standardOk) ImageFormat.YUV_420_888 else if (a.allowP010) ImageFormat.YCBCR_P010 else -1
            if (fmt != -1) {
                val sizes = Capabilities.analysisSizes(c, fmt)
                val wantAspect = p.recordAspect
                val sameAspect = sizes.filter { abs(it.width.toFloat() / it.height - wantAspect) < 0.03f }
                val pool = if (sameAspect.isNotEmpty()) sameAspect else sizes
                val wantPixels = a.size.width.toLong() * a.size.height
                val size = pool.firstOrNull { it.width == a.size.width && it.height == a.size.height }
                    ?: pool.filter { it.width.toLong() * it.height >= wantPixels }.minByOrNull { it.width.toLong() * it.height }
                    ?: pool.lastOrNull()
                if (size != null) {
                    val reader = ImageReader.Builder(size.width, size.height).setImageFormat(fmt).setMaxImages(3).build()
                    reader.setOnImageAvailableListener({ r ->
                        val l = analysisListener
                        if (l != null) l.onImageAvailable(r) else r.acquireLatestImage()?.close()
                    }, analysisThread.handler)
                    analysisReader = reader
                    val an = OutputConfiguration(reader.surface)
                    configure(an, null, applyProfile = fmt == ImageFormat.YCBCR_P010)
                    outputs += an
                    analysisFormat = fmt; analysisSize = size
                }
            }
        }

        if (outputs.isEmpty()) { fail("No output streams for camera ${p.cameraId}", true); return }
        val type = if (p.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val config = SessionConfiguration(type, outputs, cameraThread.executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) { s.close(); return }
                session = s
                status.value = Status.Ready(p, analysisFormat, analysisSize, highSpeedRange)
                startRepeating()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != generation) return
                if (withAnalysis && analysisReader != null) {
                    UxLog.w(tag, "session with analysis stream failed; retrying without it")
                    try { analysisReader?.close() } catch (_: Throwable) { }
                    analysisReader = null
                    createSession(cam, p, gen, withAnalysis = false)
                } else fail("Camera session configuration failed (${p.recordSize} @ ${p.fps}fps, HDR=${hdr})", true)
            }
        })

        try {
            val params = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            if (p.highSpeed) {
                highSpeedRange = pickHighSpeedRange(c, p)
                highSpeedRange?.let { params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            } else {
                RequestApplier.apply(params, capture, applyContext())
                RequestApplier.applyCustomKeys(params, p.sessionParameters, availableKeys, ApplyReport())
            }
            config.sessionParameters = params.build()
        } catch (t: Throwable) {
            UxLog.w(tag, "session parameters: ${t.message}")
        }

        try {
            var supported = true
            try { supported = cam.isSessionConfigurationSupported(config) } catch (_: Throwable) { }
            if (!supported && analysisReader != null) {
                UxLog.w(tag, "isSessionConfigurationSupported=false with analysis; dropping analysis stream")
                try { analysisReader?.close() } catch (_: Throwable) { }
                analysisReader = null
                createSession(cam, p, gen, withAnalysis = false)
                return
            }
            if (!supported) UxLog.w(tag, "isSessionConfigurationSupported=false; trying anyway")
            cam.createCaptureSession(config)
        } catch (t: Throwable) {
            fail("createCaptureSession: ${t.message}", true)
        }
    }

    private fun pickHighSpeedRange(c: CameraCharacteristics, p: SessionPlan): Range<Int>? {
        val ranges = Capabilities.highSpeedFpsRanges(c, p.recordSize)
        // With a preview surface attached the lower bound is the preview rate; prefer [30, fps].
        return ranges.filter { it.upper == p.fps }.minByOrNull { abs(it.lower - 30) }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun applyContext(): ApplyContext {
        val p = plan!!
        return ApplyContext(
            chars = chars!!, availableKeys = availableKeys, streamAspect = p.recordAspect, fps = p.fps,
            highSpeed = p.highSpeed, lastAwbTransform = lastAwbTransform, overrides = overrides,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------------------------------

    private fun buildRequest(withEncoder: Boolean): CaptureRequest.Builder? {
        val cam = device ?: return null
        val p = plan ?: return null
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        p.previewSurface?.let { b.addTarget(it) }
        analysisReader?.let { b.addTarget(it.surface) }
        if (withEncoder && p.encoderSurface != null) b.addTarget(p.encoderSurface)
        if (p.highSpeed) {
            highSpeedRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val zr = Capabilities.zoomRange(chars!!)
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, (overrides.zoom ?: capture.zoomRatio).coerceIn(zr.lower, zr.upper))
            capture.videoStabilization?.let { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
        } else {
            applyReport.value = RequestApplier.apply(b, capture, applyContext())
        }
        return b
    }

    private fun startRepeating() {
        val s = session ?: return
        val p = plan ?: return
        if (p.previewSurface == null && analysisReader == null && !recordingTarget) {
            try { s.stopRepeating() } catch (_: Throwable) { }
            return
        }
        try {
            if (p.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                val req = buildRequest(recordingTarget)?.build() ?: return
                s.setRepeatingBurst(s.createHighSpeedRequestList(req), captureCallback, cameraThread.handler)
            } else if (recordingTarget && p.timelapseFactor > 1 && p.encoderSurface != null) {
                val withEnc = buildRequest(true)?.build() ?: return
                val without = buildRequest(false)?.build() ?: return
                val burst = ArrayList<CaptureRequest>(p.timelapseFactor)
                burst += withEnc
                repeat(p.timelapseFactor - 1) { burst += without }
                s.setRepeatingBurst(burst, captureCallback, cameraThread.handler)
            } else {
                val req = buildRequest(recordingTarget)?.build() ?: return
                s.setRepeatingRequest(req, captureCallback, cameraThread.handler)
            }
        } catch (t: Throwable) {
            UxLog.e(tag, "setRepeating failed", t)
            status.value = Status.Error("Capture request rejected: ${t.message}", true)
        }
    }

    /** Re-apply capture settings (from the settings screen) without rebuilding the session. */
    fun updateCapture(settings: CaptureSettings) = cameraThread.post {
        capture = settings
        if (overrides.zoom == null) zoomTarget = null
        startRepeating()
    }

    /** Add/remove the encoder surface as a target of the repeating request. */
    fun setRecordingTarget(active: Boolean) = cameraThread.post {
        if (recordingTarget == active) return@post
        recordingTarget = active
        startRepeating()
    }

    /** One frame to the encoder (interval / long time-lapse capture). */
    fun captureFrameToEncoder() = cameraThread.post {
        val s = session ?: return@post
        val req = buildRequest(true)?.build() ?: return@post
        try { s.capture(req, captureCallback, cameraThread.handler) } catch (t: Throwable) { UxLog.w(tag, "capture: ${t.message}") }
    }

    // ------------------------------------------------------------------------------------------
    // Live controls
    // ------------------------------------------------------------------------------------------

    fun setZoom(ratio: Float, animate: Boolean, rampPerSec: Float = 2f) = cameraThread.post {
        val c = chars ?: return@post
        val zr = Capabilities.zoomRange(c)
        val target = ratio.coerceIn(zr.lower, zr.upper)
        zoomRampPerSec = rampPerSec.coerceIn(0.1f, 50f)
        if (!animate) {
            zoomTarget = null
            overrides = overrides.copy(zoom = target)
            startRepeating()
        } else {
            zoomTarget = target
            if (overrides.zoom == null) overrides = overrides.copy(zoom = capture.zoomRatio)
            cameraThread.handler.removeCallbacks(zoomTick)
            cameraThread.handler.post(zoomTick)
        }
    }

    fun currentZoom(): Float = overrides.zoom ?: capture.zoomRatio

    private val zoomTick = object : Runnable {
        override fun run() {
            val target = zoomTarget ?: return
            val cur = overrides.zoom ?: capture.zoomRatio
            val step = zoomRampPerSec / 30f
            val next = if (abs(target - cur) <= step) target else if (target > cur) cur + step else cur - step
            overrides = overrides.copy(zoom = next)
            startRepeating()
            if (next != target) cameraThread.handler.postDelayed(this, 33) else zoomTarget = null
        }
    }

    /** Tap-to-focus/meter at sensor-normalized coordinates (see RegionMapper.viewToSensor). */
    fun tapFocusMeter(nx: Float, ny: Float, focus: Boolean = true, meter: Boolean = true) = cameraThread.post {
        val c = chars ?: return@post
        val p = plan ?: return@post
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
        val rect = RegionMapper.meteringRect(active, p.recordAspect, nx, ny)
        val afOk = focus && Capabilities.maxAfRegions(c) > 0 && !capture.manualFocus
        val aeOk = meter && Capabilities.maxAeRegions(c) > 0
        overrides = overrides.copy(
            afRegions = if (afOk) arrayOf(rect) else overrides.afRegions,
            aeRegions = if (aeOk) arrayOf(rect) else overrides.aeRegions,
            afMode = if (afOk) CameraMetadata.CONTROL_AF_MODE_AUTO else overrides.afMode,
            afTrigger = if (afOk) CameraMetadata.CONTROL_AF_TRIGGER_START else null,
        )
        val s = session ?: return@post
        try {
            if (afOk) {
                val trig = buildRequest(recordingTarget)?.build() ?: return@post
                s.capture(trig, captureCallback, cameraThread.handler)
            }
            overrides = overrides.copy(afTrigger = null)
            startRepeating()
            afTapRevertAt = Clock.bootMs() + 5000
            cameraThread.postDelayed(5100) { maybeRevertTapFocus() }
        } catch (t: Throwable) { UxLog.w(tag, "tapFocus: ${t.message}") }
    }

    private fun maybeRevertTapFocus() {
        if (afTapRevertAt == 0L || Clock.bootMs() < afTapRevertAt) return
        if (overrides.afMode == null) return
        // Keep the lock only if the user explicitly locked AF.
        if (afLocked) return
        overrides = overrides.copy(afMode = null, afRegions = null, aeRegions = null)
        afTapRevertAt = 0L
        startRepeating()
    }

    @Volatile private var afLocked = false

    fun lockAf(lock: Boolean) = cameraThread.post {
        afLocked = lock
        overrides = if (lock) overrides.copy(afMode = CameraMetadata.CONTROL_AF_MODE_AUTO) else overrides.copy(afMode = null, afRegions = null)
        startRepeating()
    }

    fun lockAe(lock: Boolean) = cameraThread.post { overrides = overrides.copy(aeLock = lock); startRepeating() }
    fun lockAwb(lock: Boolean) = cameraThread.post { overrides = overrides.copy(awbLock = lock); startRepeating() }
    fun setTorch(on: Boolean) = cameraThread.post { overrides = overrides.copy(torch = on); startRepeating() }
    fun setManualFocusOverride(diopters: Float?) = cameraThread.post { overrides = overrides.copy(focusDistance = diopters); startRepeating() }

    fun startFocusPull(from: Float, to: Float, durationMs: Long) = cameraThread.post {
        focusPull = FocusPull(from, to, Clock.bootMs(), durationMs.coerceAtLeast(100))
        cameraThread.handler.removeCallbacks(focusPullTick)
        cameraThread.handler.post(focusPullTick)
    }

    private val focusPullTick = object : Runnable {
        override fun run() {
            val fp = focusPull ?: return
            val t = ((Clock.bootMs() - fp.startMs).toFloat() / fp.durationMs).coerceIn(0f, 1f)
            val eased = t * t * (3 - 2 * t)
            overrides = overrides.copy(focusDistance = fp.from + (fp.to - fp.from) * eased)
            startRepeating()
            if (t < 1f) cameraThread.handler.postDelayed(this, 33) else focusPull = null
        }
    }

    fun clearOverrides() = cameraThread.post { overrides = LiveOverrides(); afLocked = false; startRepeating() }

    // ------------------------------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------------------------------

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            onCaptureTimestamp?.invoke(timestamp, Clock.bootNs())
            if (lastFrameTs != 0L) {
                val dt = (timestamp - lastFrameTs) / 1e9f
                if (dt > 0f) { val f = 1f / dt; fpsEma = if (fpsEma == 0f) f else fpsEma * 0.9f + f * 0.1f }
            }
            lastFrameTs = timestamp
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastAwbTransform = it }
            val now = Clock.bootMs()
            if (now - lastInfoPublishMs >= 66) {
                lastInfoPublishMs = now
                val physical = if (Build.VERSION.SDK_INT >= 29) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                frameInfo.value = FrameInfo(
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                    focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO),
                    aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                    afState = result.get(CaptureResult.CONTROL_AF_STATE),
                    awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                    focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                    aperture = result.get(CaptureResult.LENS_APERTURE),
                    activePhysicalId = physical,
                    faceCount = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0,
                    flashState = result.get(CaptureResult.FLASH_STATE),
                    lensState = result.get(CaptureResult.LENS_STATE),
                    evComp = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                    sceneFlicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER),
                    fpsEstimate = fpsEma,
                    timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                    frameNumber = result.frameNumber,
                    droppedFrames = dropped,
                )
            }
            if (dumpResults && now - lastDumpMs >= 1000) {
                lastDumpMs = now
                resultDump.value = result.keys.sortedBy { it.name }.map { k -> k.name to GenericKeyCodec.format(try { result.get(k) } catch (_: Throwable) { null }) }
            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            dropped++
            if (failure.reason == CaptureFailure.REASON_ERROR) UxLog.w(tag, "capture failed frame=${failure.frameNumber}")
        }
    }

    /** Async variant of [templateValues] (runs on the camera thread). */
    fun templateValuesAsync(cb: (Map<String, Any?>) -> Unit) = cameraThread.post { cb(templateValues()) }

    /** Snapshot of the current template values for the "All keys" editor (best-effort type discovery). */
    fun templateValues(): Map<String, Any?> {
        val cam = device ?: return emptyMap()
        return try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val out = LinkedHashMap<String, Any?>()
            chars?.availableCaptureRequestKeys?.forEach { k ->
                @Suppress("UNCHECKED_CAST")
                out[k.name] = try { b.get(k as CaptureRequest.Key<Any>) } catch (_: Throwable) { null }
            }
            out
        } catch (_: Throwable) { emptyMap() }
    }
}
