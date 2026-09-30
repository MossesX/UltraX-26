package com.ultrax26.recorder.recording

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaFormat
import android.os.Process
import android.util.Size
import android.view.Surface
import com.ultrax26.recorder.camera.AnalysisPlan
import com.ultrax26.recorder.camera.CameraCatalog
import com.ultrax26.recorder.camera.CameraEngine
import com.ultrax26.recorder.camera.CameraInfo
import com.ultrax26.recorder.camera.Capabilities
import com.ultrax26.recorder.camera.SessionPlan
import com.ultrax26.recorder.camera.ThermalMonitor
import com.ultrax26.recorder.feedback.Feedback
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.settings.GestureCameraSource
import com.ultrax26.recorder.settings.HdrMode
import com.ultrax26.recorder.settings.LensMode
import com.ultrax26.recorder.settings.SettingsStore
import com.ultrax26.recorder.settings.VideoCodec
import com.ultrax26.recorder.triggers.EngineEvent
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.triggers.RecState
import com.ultrax26.recorder.triggers.TriggerEngine
import com.ultrax26.recorder.triggers.TriggerRule
import com.ultrax26.recorder.triggers.vision.FrameDispatcher
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxJson
import com.ultrax26.recorder.util.UxLog
import com.ultrax26.recorder.util.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.math.abs

/** What the session actually resolved to (may differ from the request when the device can't do it). */
data class SessionInfo(
    val cameraId: String,
    val cameraInfo: CameraInfo?,
    val recordSize: Size,
    val fps: Int,
    val highSpeed: Boolean,
    val codec: VideoCodec,
    val encoderName: String,
    val bitrate: Int,
    val hdr: HdrMode,
    val audio: Boolean,
    val analysis: Boolean,
    val notes: List<String>,
    val lensPresets: List<Float>,
    val physicalLenses: List<CameraInfo>,
)

@Serializable
data class ClipSummary(
    val files: List<String>,
    val durationMs: Long,
    val bytes: Long,
    val segments: Int,
    val markersMs: List<Long>,
    val triggerLog: List<String>,
    val endedAtWall: Long,
)

@Serializable
private data class Sidecar(val app: String = "UltraX 26", val clip: ClipSummary, val settings: AppSettings, val session: String)

/**
 * The recorder's brain: builds camera sessions from settings, owns the encoders/audio/writer, runs the
 * IDLE → COUNTDOWN → RECORDING ⇄ PAUSED → FINALIZING state machine, and executes trigger actions.
 * All mutation happens on [ctrl].
 */
class RecordingController(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val catalog: CameraCatalog,
    val engine: CameraEngine,
    val dispatcher: FrameDispatcher,
    private val feedback: Feedback,
    private val thermal: ThermalMonitor,
) : VideoEncoder.Listener, AudioEncoder.Listener {
    private val tag = "Recorder"
    val ctrl = WorkerThread("ux-recorder", Process.THREAD_PRIORITY_MORE_FAVORABLE)
    lateinit var triggers: TriggerEngine

    val state = MutableStateFlow(RecState.IDLE)
    val elapsedMs = MutableStateFlow(0L)
    val bytesWritten = MutableStateFlow(0L)
    val segments = MutableStateFlow(0)
    val sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val lastClip = MutableStateFlow<ClipSummary?>(null)
    val message = MutableStateFlow<Pair<Long, String>?>(null)
    val preRollBuffered = MutableStateFlow(0f)
    val freeBytes = MutableStateFlow(-1L)
    val armedForPreRoll = MutableStateFlow(false)

    /** Provided by the app graph: the shared microphone capture (also feeds the audio triggers). */
    @Volatile var audioCaptureProvider: () -> AudioCapture? = { null }
    private var attachedCapture: AudioCapture? = null
    private var foreground = true
    /** Set by the UI: asks for a preview frame grab (PixelCopy) and hands back the bitmap. */
    @Volatile var snapshotProvider: (((Bitmap?) -> Unit) -> Unit)? = null

    private var previewSurface: Surface? = null
    private var previewSize: Size = Size(1920, 1080)
    private var displayRotation = 0
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var writer: ClipWriter? = null
    private var preRoll: PreRollBuffer = PreRollBuffer(0)
    private var preRollActive = false
    private var pending = ArrayList<EncodedSample>()
    private var lastVideoPtsUs = 0L
    private var recordStartBootMs = 0L
    private var pausedAccumMs = 0L
    private var pausedAtBootMs = 0L
    private var currentSessionKey: String? = null
    private var gestureEngine: CameraEngine? = null
    private var sessionSettings: AppSettings? = null
    private var currentInfo: SessionInfo? = null
    private var triggerLog = ArrayList<String>()
    private var lensIndex = 0

    init {
        ctrl.postDelayed(1000) { tick() }
    }

    // ------------------------------------------------------------------------------------------
    // Preview / lifecycle
    // ------------------------------------------------------------------------------------------

    fun attachPreview(surface: Surface, width: Int, height: Int) = ctrl.post {
        previewSurface = surface
        previewSize = Size(width, height)
        rebuildInternal(force = true)
    }

    /** The preview went away (settings screen). Keep the camera + gestures alive without a preview. */
    fun detachPreview() = ctrl.post {
        if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) stopInternal("preview lost")
        previewSurface = null
        if (foreground) rebuildInternal(force = true) else teardownSession()
    }

    /** App went to background / came back. */
    fun setForeground(visible: Boolean) = ctrl.post {
        foreground = visible
        if (!visible) {
            if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) stopInternal("background")
            teardownSession()
        } else rebuildInternal(force = true)
    }

    fun setDisplayRotation(rotationDegrees: Int) { displayRotation = rotationDegrees; dispatcher.displayRotationDegrees = rotationDegrees }

    fun onSettingsChanged(s: AppSettings) = ctrl.post {
        val key = sessionKey(s)
        if (key != currentSessionKey) {
            if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) { toast("Settings apply after this clip"); return@post }
            rebuildInternal(force = false)
        } else {
            engine.updateCapture(s.capture)
            sessionSettings = s
        }
        dispatcher.overlays = s.overlays
        dispatcher.targetFps = s.analysis.targetFps
        dispatcher.handsEnabled = s.triggers.hand.enabled
        dispatcher.facesEnabled = s.triggers.face.enabled
        updatePreRollArming()
    }

    fun rebuild() = ctrl.post { rebuildInternal(force = true) }

    fun release() = ctrl.post {
        if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) stopInternal("release")
        teardownSession()
        ctrl.quit()
    }

    private fun sessionKey(s: AppSettings): String = listOf(
        s.capture.cameraId, s.capture.lensMode, s.capture.lockedPhysicalCameraId, s.capture.probeHiddenCameraIds,
        s.video.width, s.video.height, s.video.fps, s.video.highSpeed, s.video.codec, s.video.encoderName, s.video.profile, s.video.level,
        s.video.bitrateMode, s.video.bitrateMbps, s.video.cqQuality, s.video.iFrameIntervalSec, s.video.maxBFrames, s.video.hdr, s.video.fullRange,
        s.video.timelapseFactor, s.video.mirrorFrontCamera, s.video.preRollSeconds,
        s.audio, s.analysis, s.triggers.gestureCamera, s.triggers.hand.enabled, s.triggers.face.enabled, previewSize,
    ).joinToString("|")

    // ------------------------------------------------------------------------------------------
    // Session construction
    // ------------------------------------------------------------------------------------------

    private fun teardownSession() {
        engine.analysisListener = null
        engine.close()
        gestureEngine?.shutdown(); gestureEngine = null
        videoEncoder?.release(); videoEncoder = null
        audioEncoder?.let { enc -> attachedCapture?.removeListener(enc); enc.release() }; audioEncoder = null
        attachedCapture = null
        preRoll.clear(); preRollActive = false
        currentSessionKey = null
        sessionInfo.value = null
    }

    private fun rebuildInternal(force: Boolean) {
        val s = settingsStore.current
        val surface = previewSurface
        if (!foreground) { teardownSession(); return }
        val key = sessionKey(s)
        if (!force && key == currentSessionKey) return
        teardownSession()
        currentSessionKey = key
        sessionSettings = s
        val notes = ArrayList<String>()
        try {
            buildSession(s, surface, notes)
        } catch (t: Throwable) {
            UxLog.e(tag, "session build failed", t)
            toast("Camera setup failed: ${t.message}")
        }
    }

    private fun buildSession(s: AppSettings, surface: Surface?, notes: MutableList<String>) {
        val cameraId = s.capture.cameraId?.takeIf { id -> runCatching { catalog.characteristics(id) }.isSuccess } ?: catalog.defaultBackId() ?: throw IllegalStateException("No camera")
        val chars = catalog.characteristics(cameraId)
        val info = catalog.info(cameraId)
        val front = info?.facing == CameraCharacteristics.LENS_FACING_FRONT

        // --- resolution / fps ---
        var size = Size(s.video.width, s.video.height)
        var fps = s.video.fps
        var highSpeed = s.video.highSpeed
        if (highSpeed) {
            val hsSizes = Capabilities.highSpeedSizes(chars)
            if (hsSizes.none { it == size }) {
                val alt = hsSizes.maxByOrNull { it.width.toLong() * it.height }
                if (alt == null) { notes += "High-speed video not supported; using normal session"; highSpeed = false }
                else { notes += "High-speed size adjusted to $alt"; size = alt }
            }
            if (highSpeed) {
                val ranges = Capabilities.highSpeedFpsRanges(chars, size)
                if (ranges.none { it.upper == fps }) { val best = ranges.maxOfOrNull { it.upper } ?: 120; notes += "High-speed fps adjusted to $best"; fps = best }
            }
        }
        if (!highSpeed) {
            val sizes = Capabilities.videoSizes(chars)
            if (sizes.none { it == size }) {
                val aspect = size.width.toFloat() / size.height
                val alt = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= size.width }.maxByOrNull { it.width } ?: sizes.firstOrNull() ?: size
                notes += "Resolution ${size} unavailable; using $alt"
                size = alt
            }
            val maxFps = Capabilities.maxFpsFor(chars, size)
            if (fps > maxFps) { notes += "Camera caps $size at ${maxFps} fps"; fps = maxFps }
        }

        // --- HDR ---
        var hdr = s.video.hdr
        var profile = Capabilities.profileFor(chars, hdr)
        if (hdr != HdrMode.OFF && profile == DynamicRangeProfiles.STANDARD) { notes += "${hdr.label} not offered by this camera; recording SDR"; hdr = HdrMode.OFF }
        if (highSpeed && hdr != HdrMode.OFF) { notes += "HDR not available in high-speed mode"; hdr = HdrMode.OFF; profile = DynamicRangeProfiles.STANDARD }

        // --- encoder ---
        var codec = s.video.codec
        var enc = EncoderCapabilities.best(codec, size.width, size.height, s.video.encoderName)
        if (enc == null || !enc.supportsSize(size.width, size.height)) {
            for (alt in listOf(VideoCodec.HEVC, VideoCodec.AVC)) {
                val e = EncoderCapabilities.best(alt, size.width, size.height)
                if (e != null && e.supportsSize(size.width, size.height)) { notes += "${codec.label} encoder can't do $size; using ${alt.label}"; codec = alt; enc = e; break }
            }
        }
        val encoder = enc ?: throw IllegalStateException("No video encoder for $size")
        var codecProfile = s.video.profile ?: EncoderCapabilities.profileFor(codec, hdr, encoder)
        if (hdr != HdrMode.OFF && (codecProfile == null || !encoder.hasProfile(codecProfile))) {
            notes += "${encoder.name} has no 10-bit profile for ${hdr.label}; recording SDR"; hdr = HdrMode.OFF; profile = DynamicRangeProfiles.STANDARD
            codecProfile = s.video.profile ?: EncoderCapabilities.profileFor(codec, hdr, encoder)
        }
        val wanted = s.video.bitrateMbps?.let { (it * 1_000_000).toInt() } ?: EncoderCapabilities.autoBitrate(size.width, size.height, fps, codec, hdr)
        val bitrate = wanted.coerceIn(encoder.bitrateRange.lower, encoder.bitrateRange.upper)
        if (bitrate != wanted) notes += "Bitrate clamped to ${bitrate / 1_000_000} Mbps by ${encoder.name}"
        val encFps = if (highSpeed && s.video.slowMotionPlaybackFps != null) fps else fps
        val vcfg = VideoEncoderConfig(
            mime = codec.mime, encoderName = encoder.name, width = size.width, height = size.height, fps = encFps, bitrate = bitrate,
            bitrateMode = if (s.video.bitrateMode == com.ultrax26.recorder.settings.BitrateMode.CQ && !encoder.supportsCq) com.ultrax26.recorder.settings.BitrateMode.VBR else s.video.bitrateMode,
            cqQuality = s.video.cqQuality, iFrameIntervalSec = s.video.iFrameIntervalSec, maxBFrames = s.video.maxBFrames,
            profile = codecProfile, level = s.video.level, hdr = hdr, fullRange = s.video.fullRange,
            operatingRate = if (highSpeed) fps else null,
        )
        val venc = VideoEncoder(vcfg, ctrl.handler, this)
        venc.start()
        videoEncoder = venc

        // --- audio (shared capture owned by the app graph) ---
        var audioOk = false
        val cap = audioCaptureProvider()
        if (s.audio.enabled && !(highSpeed && s.video.slowMotionPlaybackFps != null)) {
            if (cap != null) {
                val aenc = AudioEncoder(AudioEncoderConfig(cap.config.sampleRate, cap.config.channels, s.audio.bitrateKbps * 1000), ctrl.handler, this)
                cap.addListener(aenc)
                audioEncoder = aenc
                attachedCapture = cap
                audioOk = true
            } else notes += "Microphone unavailable (permission?) — recording without audio"
        } else if (highSpeed && s.video.slowMotionPlaybackFps != null) notes += "Slow-motion clips are muxed without audio"

        // --- analysis / gestures ---
        val wantsAnalysis = s.analysis.enabled && (s.triggers.hand.enabled || s.triggers.face.enabled || s.overlays.histogram || s.overlays.zebra || s.overlays.focusPeaking || s.overlays.falseColor || s.overlays.waveform)
        val gestureCam = s.triggers.gestureCamera
        val separateGestureCamera = wantsAnalysis && gestureCam != GestureCameraSource.SAME && !highSpeed
        val analysisPlan = if (wantsAnalysis && !separateGestureCamera && !highSpeed) AnalysisPlan(Size(s.analysis.width, s.analysis.height), s.analysis.targetFps, s.analysis.preferP010WhenHdr) else null
        if (highSpeed && wantsAnalysis) notes += "Gesture camera stream is not available in high-speed mode (audio & voice triggers still work)"

        // --- lens lock ---
        val physical = if (s.capture.lensMode == LensMode.LOCK_PHYSICAL) s.capture.lockedPhysicalCameraId?.takeIf { it in (info?.physicalIds ?: emptyList()) } else null
        if (s.capture.lensMode == LensMode.LOCK_PHYSICAL && physical == null) notes += "Physical lens lock unavailable on this camera (Samsung hides per-lens IDs); use hidden camera IDs or zoom presets"

        preRoll = PreRollBuffer(s.video.preRollSeconds)
        dispatcher.sensorOrientation = info?.sensorOrientation ?: 90
        dispatcher.frontFacing = front
        dispatcher.displayRotationDegrees = displayRotation
        dispatcher.overlays = s.overlays
        dispatcher.targetFps = s.analysis.targetFps
        engine.analysisListener = if (analysisPlan != null) dispatcher else null

        val plan = SessionPlan(
            cameraId = cameraId, physicalCameraId = physical, previewSurface = surface, previewSize = previewSize,
            encoderSurface = venc.inputSurface, recordSize = size, fps = fps, highSpeed = highSpeed, analysis = analysisPlan,
            dynamicRangeProfile = profile, capture = s.capture, timelapseFactor = if (s.video.intervalCaptureMs > 0) 1 else s.video.timelapseFactor.coerceIn(1, 120),
            mirrorRecording = front && s.video.mirrorFrontCamera, sessionParameters = s.capture.sessionParameters,
        )
        engine.open(plan)

        if (separateGestureCamera) openGestureCamera(s, cameraId, front, notes)

        val presets = lensPresets(info, chars)
        currentInfo = SessionInfo(cameraId, info, size, fps, highSpeed, codec, encoder.name, bitrate, hdr, audioOk, analysisPlan != null || separateGestureCamera, notes, presets, if (info != null) catalog.physicalInfos(cameraId) else emptyList())
        sessionInfo.value = currentInfo
        notes.forEach { UxLog.i(tag, "note: $it") }
        updatePreRollArming()
    }

    private fun openGestureCamera(s: AppSettings, recordingCameraId: String, recordingFront: Boolean, notes: MutableList<String>) {
        val wantFront = s.triggers.gestureCamera == GestureCameraSource.FRONT
        val id = (if (wantFront) catalog.defaultFrontId() else catalog.defaultBackId()) ?: return
        if (id == recordingCameraId) { engine.analysisListener = dispatcher; return }
        val concurrentOk = catalog.concurrentSets().any { it.contains(id) && it.contains(recordingCameraId) }
        if (!concurrentOk) { notes += "This phone doesn't stream ${if (wantFront) "front" else "back"} + recording camera concurrently; gestures use the recording camera"; return }
        val g = CameraEngine(context, catalog)
        val ginfo = catalog.info(id)
        dispatcher.sensorOrientation = ginfo?.sensorOrientation ?: 270
        dispatcher.frontFacing = wantFront
        g.analysisListener = dispatcher
        g.open(SessionPlan(
            cameraId = id, physicalCameraId = null, previewSurface = null, previewSize = Size(640, 480), encoderSurface = null,
            recordSize = Size(s.analysis.width, s.analysis.height), fps = 30, highSpeed = false,
            analysis = AnalysisPlan(Size(s.analysis.width, s.analysis.height), s.analysis.targetFps, false),
            dynamicRangeProfile = DynamicRangeProfiles.STANDARD, capture = com.ultrax26.recorder.settings.CaptureSettings(),
        ))
        gestureEngine = g
        if (recordingFront == wantFront) notes += "Gesture camera = recording camera"
    }

    private fun lensPresets(info: CameraInfo?, chars: CameraCharacteristics): List<Float> {
        val zr = Capabilities.zoomRange(chars)
        val fromPhysical = info?.let { i -> catalog.physicalInfos(i.id).mapNotNull { p -> p.equivalentFocalMm35 } }?.let { eqs ->
            val ref = info.equivalentFocalMm35 ?: eqs.minOrNull() ?: return@let null
            eqs.map { (it / ref * 10f).let { r -> Math.round(r) / 10f } }
        }
        val base = (fromPhysical?.takeIf { it.size >= 2 } ?: listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 30f, 100f))
        return (base + 1f).filter { it >= zr.lower - 0.01f && it <= zr.upper + 0.01f }.map { it.coerceIn(zr.lower, zr.upper) }.distinct().sorted()
    }

    // ------------------------------------------------------------------------------------------
    // Pre-roll
    // ------------------------------------------------------------------------------------------

    private fun updatePreRollArming() {
        val s = sessionSettings ?: return
        val want = s.video.preRollSeconds > 0 && videoEncoder != null && state.value == RecState.IDLE && (if (::triggers.isInitialized) triggers.armed.value else false)
        if (want == preRollActive) return
        preRollActive = want
        armedForPreRoll.value = want
        if (want) {
            engine.setRecordingTarget(true)
            videoEncoder?.requestKeyFrame()
            audioEncoder?.enabled = true
        } else if (state.value == RecState.IDLE) {
            engine.setRecordingTarget(false)
            audioEncoder?.enabled = false
            preRoll.clear()
            preRollBuffered.value = 0f
        }
    }

    fun onArmedChanged() = ctrl.post { updatePreRollArming() }

    // ------------------------------------------------------------------------------------------
    // Recording control
    // ------------------------------------------------------------------------------------------

    fun start() = ctrl.post { startInternal() }
    fun stop() = ctrl.post { stopInternal("user") }
    fun pause() = ctrl.post { pauseInternal() }
    fun resume() = ctrl.post { resumeInternal() }
    fun toggle() = ctrl.post { if (state.value == RecState.IDLE) startInternal() else if (state.value == RecState.COUNTDOWN) cancelCountdown() else stopInternal("toggle") }
    fun marker() = ctrl.post { writer?.addMarker(); triggerLog += "${elapsedMs.value} marker"; toast("Marker") }

    fun perform(action: RecAction, rule: TriggerRule?) = ctrl.post {
        val s = settingsStore.current
        when (action) {
            RecAction.START -> if (state.value == RecState.IDLE || state.value == RecState.COUNTDOWN) startInternal()
            RecAction.STOP -> if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) stopInternal("trigger") else if (state.value == RecState.COUNTDOWN) cancelCountdown()
            RecAction.PAUSE -> pauseInternal()
            RecAction.RESUME -> resumeInternal()
            RecAction.TOGGLE_RECORD -> when (state.value) { RecState.IDLE -> startInternal(); RecState.COUNTDOWN -> cancelCountdown(); RecState.RECORDING, RecState.PAUSED -> stopInternal("trigger"); else -> { } }
            RecAction.TOGGLE_PAUSE -> if (state.value == RecState.RECORDING) pauseInternal() else if (state.value == RecState.PAUSED) resumeInternal()
            RecAction.SNAPSHOT -> snapshotInternal()
            RecAction.MARK -> { writer?.addMarker(); toast("Marker") }
            RecAction.ARM -> triggers.setArmed(true)
            RecAction.DISARM -> triggers.setArmed(false)
            RecAction.TOGGLE_ARM -> triggers.setArmed(!triggers.armed.value)
            RecAction.CANCEL_COUNTDOWN -> cancelCountdown()
            RecAction.NEXT_LENS, RecAction.PREV_LENS -> {
                val presets = currentInfo?.lensPresets ?: emptyList()
                if (presets.isNotEmpty()) {
                    val cur = engine.currentZoom()
                    val idx = presets.indexOfFirst { abs(it - cur) < 0.05f }.let { if (it < 0) presets.indexOfFirst { p -> p > cur }.coerceAtLeast(0) else it }
                    val next = presets[((idx + (if (action == RecAction.NEXT_LENS) 1 else -1)) % presets.size + presets.size) % presets.size]
                    engine.setZoom(next, animate = true, rampPerSec = s.capture.zoomRampSpeed)
                }
            }
            RecAction.ZOOM_IN -> engine.setZoom(engine.currentZoom() + s.capture.zoomStep, animate = true, rampPerSec = s.capture.zoomRampSpeed)
            RecAction.ZOOM_OUT -> engine.setZoom(engine.currentZoom() - s.capture.zoomStep, animate = true, rampPerSec = s.capture.zoomRampSpeed)
            RecAction.TOGGLE_TORCH -> { torchOn = !torchOn; engine.setTorch(torchOn) }
            RecAction.TOGGLE_AE_LOCK -> { aeLocked = !aeLocked; engine.lockAe(aeLocked) }
            RecAction.TOGGLE_AF_LOCK -> { afLocked = !afLocked; engine.lockAf(afLocked) }
        }
        if (rule != null) triggerLog += "${Clock.wallMs()} ${rule.displayName}"
    }

    @Volatile var torchOn = false
    @Volatile var aeLocked = false
    @Volatile var afLocked = false

    fun onEngineEvent(e: EngineEvent) = ctrl.post {
        when (e) {
            is EngineEvent.CountdownTick -> if (state.value == RecState.IDLE) state.value = RecState.COUNTDOWN
            is EngineEvent.CountdownCancelled -> if (state.value == RecState.COUNTDOWN) state.value = RecState.IDLE
            is EngineEvent.Armed -> updatePreRollArming()
            else -> { }
        }
    }

    private fun cancelCountdown() { triggers.cancelCountdownRequest(); if (state.value == RecState.COUNTDOWN) state.value = RecState.IDLE }

    private fun startInternal() {
        if (state.value == RecState.RECORDING || state.value == RecState.PAUSED || state.value == RecState.FINALIZING) return
        val s = settingsStore.current
        val venc = videoEncoder ?: run { toast("Camera not ready"); return }
        val info = currentInfo ?: run { toast("Camera not ready"); return }
        val storage = StorageTarget(context, s.storage)
        val free = storage.freeBytes()
        if (free in 0 until s.storage.minFreeSpaceMb * 1024L * 1024L) { toast("Not enough free space"); return }
        if (thermal.state.value.criticalOrWorse && s.video.stopOnThermalCritical) { toast("Device too hot to record"); return }

        val timeScale = when {
            info.highSpeed && s.video.slowMotionPlaybackFps != null -> info.fps.toDouble() / s.video.slowMotionPlaybackFps.coerceAtLeast(1)
            s.video.intervalCaptureMs > 0 -> (1_000_000.0 / info.fps) / (s.video.intervalCaptureMs * 1000.0)
            s.video.timelapseFactor > 1 -> 1.0 / s.video.timelapseFactor
            else -> 1.0
        }
        val orientation = s.video.orientationLock ?: orientationHint(info)
        val now = Date()
        val w = ClipWriter(
            storage = storage,
            nextFileName = { seg -> StorageTarget.fileName(s.storage.fileNameTemplate, info.recordSize.width, info.recordSize.height, info.fps, info.codec.name, if (info.hdr == HdrMode.OFF) "SDR" else info.hdr.name, "z${"%.1f".format(engine.currentZoom())}", seg, if (s.video.container == com.ultrax26.recorder.settings.Container.WEBM) "webm" else "mp4", now) },
            mime = if (s.video.container == com.ultrax26.recorder.settings.Container.WEBM) "video/webm" else "video/mp4",
            orientationHint = orientation,
            location = if (s.storage.geotag) lastKnownLocation() else null,
            segmentDurationUs = s.video.segmentMinutes * 60_000_000L,
            segmentBytes = s.video.maxFileSizeMb * 1024L * 1024L,
            hasAudio = audioEncoder != null && info.audio && s.audio.enabled,
            timeScale = timeScale,
        )
        venc.outputFormat?.let { w.setVideoFormat(it) }
        audioEncoder?.outputFormat?.let { w.setAudioFormat(it) }
        writer = w
        pending.clear()
        triggerLog.clear()
        recordStartBootMs = Clock.bootMs(); pausedAccumMs = 0
        state.value = RecState.RECORDING
        elapsedMs.value = 0; bytesWritten.value = 0; segments.value = 0

        // Pre-roll first, then live.
        if (preRollActive) {
            val buffered = preRoll.drain()
            buffered.forEach { deliver(it) }
            preRollActive = false
            armedForPreRoll.value = false
        }
        engine.setRecordingTarget(true)
        venc.requestKeyFrame()
        audioEncoder?.enabled = true
        if (s.video.intervalCaptureMs > 0) scheduleInterval(s.video.intervalCaptureMs)
        RecordingService.start(context)
        feedback.recordingStarted()
        UxLog.i(tag, "recording started ${info.recordSize}@${info.fps} ${info.codec} ${info.hdr}")
    }

    private var intervalRunnable: Runnable? = null
    private fun scheduleInterval(ms: Long) {
        engine.setRecordingTarget(false) // frames come from single captures
        val r = object : Runnable { override fun run() { if (state.value == RecState.RECORDING) { engine.captureFrameToEncoder(); ctrl.handler.postDelayed(this, ms) } } }
        intervalRunnable = r
        ctrl.handler.post(r)
    }

    private fun lastKnownLocation(): Pair<Double, Double>? = try {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) null else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = (if (fine) lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) else null) ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            loc?.let { it.latitude to it.longitude }
        }
    } catch (_: Throwable) { null }

    private fun orientationHint(info: SessionInfo): Int {
        val sensor = info.cameraInfo?.sensorOrientation ?: 90
        val front = info.cameraInfo?.facing == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + displayRotation) % 360 else (sensor - displayRotation + 360) % 360
    }

    private fun pauseInternal() {
        if (state.value != RecState.RECORDING) return
        writer?.pause(lastVideoPtsUs)
        pausedAtBootMs = Clock.bootMs()
        state.value = RecState.PAUSED
        RecordingService.update(context, paused = true)
    }

    private fun resumeInternal() {
        if (state.value != RecState.PAUSED) return
        pausedAccumMs += Clock.bootMs() - pausedAtBootMs
        writer?.resume(lastVideoPtsUs)
        videoEncoder?.requestKeyFrame()
        state.value = RecState.RECORDING
        RecordingService.update(context, paused = false)
    }

    private fun stopInternal(reason: String) {
        val w = writer ?: return
        if (state.value == RecState.FINALIZING) return
        state.value = RecState.FINALIZING
        intervalRunnable?.let { ctrl.handler.removeCallbacks(it) }; intervalRunnable = null
        engine.setRecordingTarget(false)
        audioEncoder?.enabled = false
        // Let the last frames drain, then finalize.
        ctrl.postDelayed(350) {
            pending.clear()
            val files = try { w.finish() } catch (t: Throwable) { UxLog.e(tag, "finish failed", t); emptyList() }
            writer = null
            val s = settingsStore.current
            val summary = ClipSummary(files.map { it.displayName }, w.durationUs / 1000, w.bytesWritten, files.size, w.markers.map { it / 1000 }, triggerLog.toList(), Clock.wallMs())
            lastClip.value = summary
            if (s.storage.writeSidecarJson && files.isNotEmpty()) writeSidecar(s, summary, files.first().displayName)
            files.forEach { StorageTarget(context, s.storage).deleteIfEmpty(it) }
            state.value = RecState.IDLE
            RecordingService.stop(context)
            toast(if (files.isEmpty()) "Nothing recorded" else "Saved ${files.first().displayName}${if (files.size > 1) " (+${files.size - 1})" else ""}")
            UxLog.i(tag, "recording stopped ($reason): $summary")
            updatePreRollArming()
        }
    }

    private fun writeSidecar(s: AppSettings, summary: ClipSummary, clipName: String) {
        try {
            val out = StorageTarget(context, s.storage).create(clipName.substringBeforeLast('.') + ".json", "application/json")
            java.io.FileOutputStream(out.pfd.fileDescriptor).use { it.write(UxJson.encodeToString(Sidecar(clip = summary, settings = s, session = currentInfo.toString())).toByteArray()) }
            out.finish()
        } catch (t: Throwable) { UxLog.w(tag, "sidecar: ${t.message}") }
    }

    private fun snapshotInternal() {
        val provider = snapshotProvider
        if (provider == null) { toast("Snapshot unavailable"); return }
        provider { bmp ->
            if (bmp == null) { toast("Snapshot failed"); return@provider }
            ctrl.post {
                try {
                    val s = settingsStore.current
                    val name = StorageTarget.fileName(s.storage.fileNameTemplate.replace("{seg}", "") + "_grab", bmp.width, bmp.height, 0, "jpg", "", "", 0, "jpg")
                    val out = StorageTarget(context, s.storage).create(name, "image/jpeg")
                    val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, s.capture.jpegQuality.coerceIn(50, 100), it) }.toByteArray()
                    java.io.FileOutputStream(out.pfd.fileDescriptor).use { it.write(bytes) }
                    out.finish()
                    toast("Frame grab saved")
                } catch (t: Throwable) { UxLog.w(tag, "snapshot: ${t.message}"); toast("Snapshot failed: ${t.message}") }
            }
        }
    }

    private fun toast(msg: String) { message.value = Clock.bootMs() to msg }

    // ------------------------------------------------------------------------------------------
    // Sample routing (ctrl thread, from codec callbacks)
    // ------------------------------------------------------------------------------------------

    override fun onVideoFormat(format: MediaFormat) { writer?.setVideoFormat(format); flushPending() }
    override fun onAudioFormat(format: MediaFormat) { writer?.setAudioFormat(format); flushPending() }
    override fun onVideoSample(sample: EncodedSample) { lastVideoPtsUs = sample.ptsUs; deliver(sample) }
    override fun onAudioSample(sample: EncodedSample) { deliver(sample) }
    override fun onVideoError(e: Throwable) { UxLog.e(tag, "video encoder error", e); toast("Encoder error: ${e.message}"); if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) stopInternal("encoder error") }
    override fun onAudioError(e: Throwable) { UxLog.e(tag, "audio encoder error", e) }

    private fun deliver(s: EncodedSample) {
        when (state.value) {
            RecState.RECORDING, RecState.FINALIZING -> {
                val w = writer ?: return
                if (!w.isReady) { if (pending.size < 400) pending += s; return }
                if (pending.isNotEmpty()) flushPending()
                w.write(s)
                bytesWritten.value = w.bytesWritten
                if (w.segments != segments.value) segments.value = w.segments
            }
            RecState.PAUSED -> { }
            else -> if (preRollActive) { preRoll.add(s); if (s.track == Track.VIDEO && s.isKeyFrame) preRollBuffered.value = preRoll.bufferedSeconds() }
        }
    }

    private fun flushPending() {
        val w = writer ?: return
        if (!w.isReady || pending.isEmpty()) return
        val list = pending; pending = ArrayList()
        list.forEach { w.write(it) }
    }

    // ------------------------------------------------------------------------------------------
    // Periodic housekeeping
    // ------------------------------------------------------------------------------------------

    private fun tick() {
        try {
            val s = settingsStore.current
            if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) {
                val paused = if (state.value == RecState.PAUSED) Clock.bootMs() - pausedAtBootMs else 0L
                elapsedMs.value = Clock.bootMs() - recordStartBootMs - pausedAccumMs - paused
                if (s.video.maxDurationSec > 0 && elapsedMs.value >= s.video.maxDurationSec * 1000L) { toast("Max duration reached"); stopInternal("max duration") }
                if (s.video.stopOnThermalCritical && thermal.state.value.criticalOrWorse) { toast("Stopped: device critically hot"); stopInternal("thermal") }
                if (s.video.downgradeOnThermalSevere && thermal.state.value.severeOrWorse) videoEncoder?.let { it.setBitrate((it.config.bitrate * 0.6).toInt()) }
            }
            if (Clock.bootMs() % 5000 < 300) {
                val free = StorageTarget(context, s.storage).freeBytes()
                freeBytes.value = free
                if ((state.value == RecState.RECORDING) && free in 0 until 200L * 1024 * 1024) { toast("Storage full — stopping"); stopInternal("storage") }
            }
        } catch (t: Throwable) { UxLog.w(tag, "tick: ${t.message}") }
        ctrl.postDelayed(250) { tick() }
    }
}
