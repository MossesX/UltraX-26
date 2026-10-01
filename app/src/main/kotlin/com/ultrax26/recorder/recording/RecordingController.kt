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
import com.ultrax26.recorder.effects.cleared
import com.ultrax26.recorder.effects.EffectsRenderer
import com.ultrax26.recorder.feedback.Feedback
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.settings.GestureCameraSource
import com.ultrax26.recorder.settings.HdrMode
import com.ultrax26.recorder.settings.LensMode
import com.ultrax26.recorder.settings.SettingsStore
import com.ultrax26.recorder.settings.VideoCodec
import com.ultrax26.recorder.triggers.EngineEvent
import com.ultrax26.recorder.triggers.TriggerEvent
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
import kotlin.math.pow

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
    val effectsActive: Boolean = false,
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
    /** Delay line that silences trigger sounds before they are encoded (null when the option is off). */
    private var audioScrubber: AudioScrubber? = null
    private var pinchBaseZoom: Float? = null
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
    private var renderer: EffectsRenderer? = null
    val rendererState = MutableStateFlow<EffectsRenderer?>(null)
    @Volatile private var callActive = false
    /** Video-call actions routed from triggers (set by the app graph). */
    @Volatile var callActions: CallActions? = null

    interface CallActions { fun answer(); fun hangUp(); fun toggleMic() }

    /** A video call needs the GL pipeline as its video source; rebuild the session when this flips. */
    fun setCallActive(active: Boolean) = ctrl.post {
        if (callActive == active) return@post
        callActive = active
        if (state.value == RecState.RECORDING || state.value == RecState.PAUSED) { toast("Call video starts after this clip"); return@post }
        rebuildInternal(force = true)
    }
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
        renderer?.updateSettings(s.effects)
        renderer?.callRawCamera = !s.calls.effectsInCalls
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
        s.effects.needsPipeline(), s.effects.renderRes, callActive, s.audio.scrubTriggerSounds, s.audio.scrubDelayMs, s.audio.scrubMode,
    ).joinToString("|")

    // ------------------------------------------------------------------------------------------
    // Session construction
    // ------------------------------------------------------------------------------------------

    private fun teardownSession() {
        engine.analysisListener = null
        engine.close()
        renderer?.let { r -> dispatcher.effectsSink = null; try { r.stop() } catch (t: Throwable) { UxLog.w(tag, "renderer stop: ${t.message}") } }
        renderer = null; rendererState.value = null
        gestureEngine?.shutdown(); gestureEngine = null
        videoEncoder?.release(); videoEncoder = null
        audioScrubber?.let { sc -> attachedCapture?.removeListener(sc); sc.clear() }; audioScrubber = null
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

        // --- effects pipeline (GL compositor between camera and encoder) ---
        val fx = (s.effects.needsPipeline() || callActive) && !highSpeed
        if (s.effects.needsPipeline() && highSpeed) notes += "Effects are unavailable in high-speed mode"
        if (fx) {
            if (hdr != HdrMode.OFF) { notes += "HDR is recorded as SDR while effects are active"; hdr = HdrMode.OFF; profile = DynamicRangeProfiles.STANDARD }
            val cap = s.effects.renderRes.maxWidth
            if (cap > 0 && size.width > cap) {
                val aspect = size.width.toFloat() / size.height
                val alt = Capabilities.videoSizes(chars).filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= cap }.maxByOrNull { it.width }
                if (alt != null) { notes += "Effects render resolution caps recording at ${alt.width}×${alt.height}"; size = alt; fps = minOf(fps, Capabilities.maxFpsFor(chars, size)) }
            }
        }

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

        var fxRenderer: EffectsRenderer? = null
        if (fx) {
            try {
                val r = EffectsRenderer(context) { settingsStore.current.effects }
                r.start(size.width, size.height)
                if (r.inputSurface == null) throw IllegalStateException(r.stats.value.error ?: "no GL input surface")
                r.setPreviewSurface(surface)
                r.setEncoderSurface(venc.inputSurface)
                r.setCameraFacing(front, mirrorPreview = front, mirrorRecording = front && s.video.mirrorFrontCamera)
                r.updateSettings(s.effects)
                r.callRawCamera = !s.calls.effectsInCalls
                dispatcher.effectsSink = r
                renderer = r; rendererState.value = r; fxRenderer = r
            } catch (t: Throwable) {
                UxLog.e(tag, "effects renderer failed; recording without effects", t)
                notes += "Effects pipeline failed to start (${t.message}); recording without effects"
            }
        }

        // --- audio (shared capture owned by the app graph) ---
        var audioOk = false
        val cap = audioCaptureProvider()
        if (s.audio.enabled && !(highSpeed && s.video.slowMotionPlaybackFps != null)) {
            if (cap != null) {
                val aenc = AudioEncoder(AudioEncoderConfig(cap.config.sampleRate, cap.config.channels, s.audio.bitrateKbps * 1000), ctrl.handler, this)
                if (s.audio.scrubTriggerSounds) {
                    val scrub = AudioScrubber(aenc, s.audio.scrubDelayMs, if (s.audio.scrubMode == com.ultrax26.recorder.settings.ScrubMode.DUCK) AudioScrubber.Mode.DUCK else AudioScrubber.Mode.MUTE)
                    audioScrubber = scrub
                    cap.addListener(scrub)
                    notes += "Trigger sounds are removed from the audio (${s.audio.scrubDelayMs} ms audio hold-back)"
                } else cap.addListener(aenc)
                audioEncoder = aenc
                attachedCapture = cap
                audioOk = true
            } else notes += "Microphone unavailable (permission?) — recording without audio"
        } else if (highSpeed && s.video.slowMotionPlaybackFps != null) notes += "Slow-motion clips are muxed without audio"

        // --- analysis / gestures ---
        val wantsAnalysis = s.analysis.enabled && (s.triggers.hand.enabled || s.triggers.face.enabled || s.overlays.histogram || s.overlays.zebra || s.overlays.focusPeaking || s.overlays.falseColor || s.overlays.waveform || fxRenderer != null)
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
            cameraId = cameraId, physicalCameraId = physical,
            previewSurface = fxRenderer?.inputSurface ?: surface, previewSize = if (fxRenderer != null) size else previewSize,
            encoderSurface = if (fxRenderer != null) null else venc.inputSurface,
            recordSize = size, fps = fps, highSpeed = highSpeed, analysis = analysisPlan,
            dynamicRangeProfile = profile, capture = s.capture, timelapseFactor = if (s.video.intervalCaptureMs > 0) 1 else s.video.timelapseFactor.coerceIn(1, 120),
            mirrorRecording = fxRenderer == null && front && s.video.mirrorFrontCamera, sessionParameters = s.capture.sessionParameters,
        )
        engine.open(plan)

        if (separateGestureCamera) openGestureCamera(s, cameraId, front, notes)

        val presets = lensPresets(info, chars)
        currentInfo = SessionInfo(cameraId, info, size, fps, highSpeed, codec, encoder.name, bitrate, hdr, audioOk, analysisPlan != null || separateGestureCamera, notes, presets, if (info != null) catalog.physicalInfos(cameraId) else emptyList(), fxRenderer != null)
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
            engine.setRecordingTarget(true); renderer?.setRecording(true)
            videoEncoder?.requestKeyFrame()
            audioEncoder?.enabled = true
        } else if (state.value == RecState.IDLE) {
            engine.setRecordingTarget(false); renderer?.setRecording(false)
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
            RecAction.CANCEL_COUNTDOWN -> { cancelCountdown(); delayedStart?.let { ctrl.handler.removeCallbacks(it); delayedStart = null; toast("Timer cancelled") } }
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
            RecAction.ANSWER_CALL -> callActions?.answer()
            RecAction.HANG_UP -> callActions?.hangUp()
            RecAction.TOGGLE_CALL_MIC -> callActions?.toggleMic()
            else -> performParameterized(action, rule?.actionParam?.trim().orEmpty(), s)
        }
        if (rule != null) triggerLog += "${Clock.wallMs()} ${rule.displayName}"
    }

    private var delayedStart: Runnable? = null

    /** Actions that carry an argument (see RecAction.paramHint). Settings changes rebuild the session as usual. */
    private fun performParameterized(action: RecAction, p: String, s: AppSettings) {
        fun upd(f: (AppSettings) -> AppSettings) = settingsStore.update(f)
        val chars = engine.characteristics
        when (action) {
            RecAction.SET_RESOLUTION -> {
                val m = Regex("(\\d{3,5})\\s*[x×]\\s*(\\d{3,5})").find(p)
                if (m == null) { toast("Resolution needs WIDTHxHEIGHT"); return }
                val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
                upd { it.copy(video = it.video.copy(width = w, height = h, highSpeed = false)) }; toast("Resolution ${w}×$h")
            }
            RecAction.SET_FPS -> p.toIntOrNull()?.let { f -> upd { it.copy(video = it.video.copy(fps = f)) }; toast("$f fps") } ?: toast("Frame rate needs a number")
            RecAction.SET_CODEC -> runCatching { VideoCodec.valueOf(p.uppercase().replace("H.264", "AVC").replace("H.265", "HEVC")) }.getOrNull()?.let { c -> upd { it.copy(video = it.video.copy(codec = c, encoderName = null, profile = null)) }; toast(c.label) } ?: toast("Unknown codec $p")
            RecAction.SET_HDR -> runCatching { HdrMode.valueOf(p.uppercase().replace("+", "_PLUS").replace(' ', '_')) }.getOrNull()?.let { h -> upd { it.copy(video = it.video.copy(hdr = h)) }; toast(h.label) } ?: toast("Unknown HDR mode $p")
            RecAction.TOGGLE_HIGH_SPEED -> upd {
                val on = !it.video.highSpeed
                val hs = chars?.let { c -> Capabilities.highSpeedSizes(c) } ?: emptyList()
                if (on && hs.isEmpty()) { toast("High-speed capture not supported"); it }
                else if (on) { val sz = hs.first(); toast("High-speed on"); it.copy(video = it.video.copy(highSpeed = true, width = sz.width, height = sz.height, fps = 120)) }
                else { toast("High-speed off"); it.copy(video = it.video.copy(highSpeed = false, fps = 30)) }
            }
            RecAction.SET_BITRATE -> p.toFloatOrNull()?.let { mb -> upd { it.copy(video = it.video.copy(bitrateMbps = if (mb <= 0f) null else mb)) }; toast(if (mb <= 0f) "Bitrate automatic" else "Bitrate ${mb.toInt()} Mb/s") }
            RecAction.SELECT_CAMERA -> if (p.isNotBlank()) { upd { it.copy(capture = it.capture.copy(cameraId = p, lockedPhysicalCameraId = null, lensMode = com.ultrax26.recorder.settings.LensMode.AUTO, zoomRatio = 1f)) }; toast("Camera $p") }
            RecAction.FRONT_CAMERA -> catalog.defaultFrontId()?.let { id -> upd { it.copy(capture = it.capture.copy(cameraId = id, lockedPhysicalCameraId = null, zoomRatio = 1f)) }; toast("Front camera") }
            RecAction.BACK_CAMERA -> catalog.defaultBackId()?.let { id -> upd { it.copy(capture = it.capture.copy(cameraId = id, lockedPhysicalCameraId = null, zoomRatio = 1f)) }; toast("Back camera") }
            RecAction.FLIP_CAMERA -> {
                val front = currentInfo?.cameraInfo?.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                val id = if (front) catalog.defaultBackId() else catalog.defaultFrontId()
                if (id != null) { upd { it.copy(capture = it.capture.copy(cameraId = id, lockedPhysicalCameraId = null, zoomRatio = 1f)) }; toast(if (front) "Back camera" else "Front camera") }
            }
            RecAction.SET_ZOOM -> p.replace("x", "").replace("×", "").toFloatOrNull()?.let { z -> engine.setZoom(z, animate = true, rampPerSec = s.capture.zoomRampSpeed); toast("Zoom ${p}×") } ?: toast("Zoom needs a number")
            RecAction.SET_EV -> {
                val ev = p.replace("+", "").toFloatOrNull() ?: run { toast("EV needs a number"); return }
                val step = chars?.let { Capabilities.evStep(it) } ?: 0f
                val range = chars?.let { Capabilities.evRange(it) }
                val steps = if (step > 0f) Math.round(ev / step) else 0
                val clamped = if (range != null) steps.coerceIn(range.lower, range.upper) else steps
                upd { it.copy(capture = it.capture.copy(exposureCompensation = clamped, manualExposure = false)) }; toast("Exposure ${if (ev >= 0) "+" else ""}$ev EV")
            }
            RecAction.AUTO_EXPOSURE -> { aeLocked = false; engine.lockAe(false); upd { it.copy(capture = it.capture.copy(manualExposure = false, exposureCompensation = 0, aeLock = false, iso = null, exposureTimeNs = null)) }; toast("Auto exposure") }
            RecAction.SET_ISO -> p.toIntOrNull()?.let { iso -> upd { it.copy(capture = it.capture.copy(manualExposure = true, iso = iso)) }; toast("ISO $iso") } ?: toast("ISO needs a number")
            RecAction.SET_SHUTTER -> p.removePrefix("1/").toFloatOrNull()?.takeIf { it > 0f }?.let { den -> val ns = (1e9 / den).toLong(); upd { it.copy(capture = it.capture.copy(manualExposure = true, exposureTimeNs = ns)) }; toast("Shutter 1/${den.toInt()} s") } ?: toast("Shutter needs 1/x")
            RecAction.SET_WB -> {
                val kelvin = when (p.lowercase()) { "daylight", "sun", "sunny" -> 5500; "cloudy" -> 6500; "shade" -> 7500; "tungsten", "incandescent" -> 3200; "fluorescent" -> 4200; "auto", "" -> null; else -> p.filter { it.isDigit() }.toIntOrNull() }
                if (p.lowercase() in setOf("auto", "") || (kelvin == null)) { upd { it.copy(capture = it.capture.copy(manualWhiteBalance = false, awbMode = null, awbLock = false)) }; toast("Auto white balance") }
                else { upd { it.copy(capture = it.capture.copy(manualWhiteBalance = true, whiteBalanceKelvin = kelvin.coerceIn(2000, 12000))) }; toast("White balance $kelvin K") }
            }
            RecAction.AUTO_FOCUS -> { afLocked = false; engine.lockAf(false); upd { it.copy(capture = it.capture.copy(manualFocus = false, afMode = null)) }; toast("Auto focus") }
            RecAction.FOCUS_INFINITY -> { upd { it.copy(capture = it.capture.copy(manualFocus = true, focusDistance = 0f)) }; toast("Focus: infinity") }
            RecAction.FOCUS_NEAREST -> { val near = currentInfo?.cameraInfo?.minFocusDistance?.takeIf { it > 0f } ?: 10f; upd { it.copy(capture = it.capture.copy(manualFocus = true, focusDistance = near)) }; toast("Focus: nearest") }
            RecAction.RACK_FOCUS -> { engine.startFocusPull(s.capture.focusPullFrom, s.capture.focusPullTo, s.capture.focusPullDurationMs); toast("Rack focus") }
            RecAction.TOGGLE_STABILIZATION -> upd { val on = it.capture.videoStabilization != 1; toast(if (on) "Stabilization on" else "Stabilization off"); it.copy(capture = it.capture.copy(videoStabilization = if (on) 1 else 0)) }
            RecAction.SET_TONEMAP -> runCatching { com.ultrax26.recorder.settings.TonemapPreset.valueOf(p.uppercase()) }.getOrNull()?.let { t -> upd { it.copy(capture = it.capture.copy(tonemapPreset = t)) }; toast("Tone: ${t.label}") } ?: toast("Unknown tone preset $p")
            RecAction.TOGGLE_OVERLAY -> upd {
                val o = it.overlays
                val n = when (p.lowercase().replace(" ", "")) {
                    "grid" -> o.copy(grid = if (o.grid == com.ultrax26.recorder.settings.GridType.NONE) com.ultrax26.recorder.settings.GridType.THIRDS else com.ultrax26.recorder.settings.GridType.NONE)
                    "level" -> o.copy(level = !o.level); "histogram" -> o.copy(histogram = !o.histogram); "waveform" -> o.copy(waveform = !o.waveform)
                    "zebra", "zebras" -> o.copy(zebra = !o.zebra); "peaking", "focuspeaking" -> o.copy(focusPeaking = !o.focusPeaking); "falsecolor" -> o.copy(falseColor = !o.falseColor)
                    "safeareas", "safe" -> o.copy(safeAreas = !o.safeAreas); "hud", "gesturehud" -> o.copy(gestureHud = !o.gestureHud); "audiometer" -> o.copy(audioMeter = !o.audioMeter)
                    "timecode" -> o.copy(timecode = !o.timecode); "exposure", "exposureinfo" -> o.copy(exposureInfo = !o.exposureInfo); "center", "centermarker" -> o.copy(showCenterMarker = !o.showCenterMarker)
                    else -> { toast("Unknown overlay $p"); o }
                }
                if (n !== o) toast("Overlay $p toggled")
                it.copy(overlays = n)
            }
            RecAction.TOGGLE_AUDIO -> upd { toast(if (it.audio.enabled) "Audio off" else "Audio on"); it.copy(audio = it.audio.copy(enabled = !it.audio.enabled)) }
            RecAction.TOGGLE_PREROLL -> upd { val on = it.video.preRollSeconds == 0; toast(if (on) "Pre-roll on (5 s)" else "Pre-roll off"); it.copy(video = it.video.copy(preRollSeconds = if (on) 5 else 0)) }
            RecAction.TOGGLE_SCRUB -> upd { toast(if (it.audio.scrubTriggerSounds) "Trigger sounds kept" else "Trigger sounds removed"); it.copy(audio = it.audio.copy(scrubTriggerSounds = !it.audio.scrubTriggerSounds)) }
            RecAction.SET_LOOK -> com.ultrax26.recorder.effects.EffectCatalog.look(p)?.let { look -> upd { it.copy(effects = look.apply(it.effects).copy(activeLook = look.id)) }; toast("Look: ${look.name}") } ?: toast("Unknown look $p")
            RecAction.CLEAR_EFFECTS -> { upd { it.copy(effects = it.effects.cleared()) }; toast("Effects cleared") }
            RecAction.SET_BACKGROUND -> upd {
                val bg = it.effects.background
                val next = when (p.lowercase()) {
                    "none", "off", "real" -> bg.copy(type = com.ultrax26.recorder.effects.BackgroundType.NONE)
                    "blur" -> bg.copy(type = com.ultrax26.recorder.effects.BackgroundType.BLUR)
                    "color", "colour", "green" -> bg.copy(type = com.ultrax26.recorder.effects.BackgroundType.COLOR)
                    else -> when {
                        com.ultrax26.recorder.effects.EffectCatalog.parallaxScenes.any { sc -> sc.id == p } -> bg.copy(type = com.ultrax26.recorder.effects.BackgroundType.PARALLAX, id = p)
                        com.ultrax26.recorder.effects.EffectCatalog.proceduralBackgrounds.any { sc -> sc.id == p } -> bg.copy(type = com.ultrax26.recorder.effects.BackgroundType.PROCEDURAL, id = p)
                        else -> { toast("Unknown background $p"); bg }
                    }
                }
                if (next !== bg) toast("Background: $p")
                it.copy(effects = it.effects.copy(background = next))
            }
            RecAction.SET_FACE_MODE -> runCatching { com.ultrax26.recorder.effects.FaceMode.valueOf(p.uppercase().replace(' ', '_')) }.getOrNull()?.let { m -> upd { it.copy(effects = it.effects.copy(faceMode = m)) }; toast("Face: ${m.label}") } ?: toast("Unknown face mode $p")
            RecAction.SET_FUN_MODE -> runCatching { com.ultrax26.recorder.effects.FunMode.valueOf(p.uppercase().replace(' ', '_')) }.getOrNull()?.let { m -> upd { it.copy(effects = it.effects.copy(funMode = m)) }; toast("Effect: ${m.label}") } ?: toast("Unknown fun mode $p")
            RecAction.SET_AGE -> runCatching { com.ultrax26.recorder.effects.AgeMode.valueOf(p.uppercase().replace(' ', '_')) }.getOrNull()?.let { m -> upd { it.copy(effects = it.effects.copy(age = m)) }; toast("Age: ${m.label}") } ?: toast("Unknown age look $p")
            RecAction.SET_COLOR_LOOK -> runCatching { com.ultrax26.recorder.effects.ColorLook.valueOf(p.uppercase().replace(' ', '_').replace("&", "")) }.getOrNull()?.let { m -> upd { it.copy(effects = it.effects.copy(look = m)) }; toast("Color: ${m.label}") } ?: toast("Unknown color look $p")
            RecAction.TOGGLE_STICKER -> {
                val asset = com.ultrax26.recorder.effects.EffectCatalog.sticker(p)
                if (asset == null) { toast("Unknown sticker $p"); return }
                upd { val has = it.effects.stickers.any { l -> l.assetId == p }; toast(if (has) "${asset.name} off" else asset.name); it.copy(effects = it.effects.copy(stickers = if (has) it.effects.stickers.filter { l -> l.assetId != p } else it.effects.stickers + asset.layer())) }
            }
            RecAction.TOGGLE_BEAUTY -> upd { val on = !it.effects.beauty.any(); toast(if (on) "Beauty on" else "Beauty off"); it.copy(effects = it.effects.copy(beauty = if (on) com.ultrax26.recorder.effects.BeautySettings(smoothing = 0.6f, brightening = 0.25f, eyeBrighten = 0.3f, teethWhitening = 0.3f) else com.ultrax26.recorder.effects.BeautySettings())) }
            RecAction.START_TIMER -> {
                val secs = p.toIntOrNull()?.coerceIn(1, 3600) ?: run { toast("Timer needs seconds"); return }
                delayedStart?.let { ctrl.handler.removeCallbacks(it) }
                val r = Runnable { delayedStart = null; if (state.value == RecState.IDLE) startInternal() }
                delayedStart = r; ctrl.handler.postDelayed(r, secs * 1000L)
                toast("Recording starts in $secs s")
            }
            RecAction.SET_COUNTDOWN -> p.toIntOrNull()?.let { n -> upd { it.copy(triggers = it.triggers.copy(countdownSeconds = n.coerceIn(0, 60))) }; toast(if (n == 0) "No countdown" else "Countdown $n s") }
            else -> { }
        }
    }

    @Volatile var torchOn = false
    @Volatile var aeLocked = false
    @Volatile var afLocked = false

    fun onEngineEvent(e: EngineEvent) = ctrl.post {
        when (e) {
            is EngineEvent.Fired -> scrubTriggerSound(e.event)
            is EngineEvent.CountdownTick -> if (state.value == RecState.IDLE) state.value = RecState.COUNTDOWN
            is EngineEvent.CountdownCancelled -> if (state.value == RecState.COUNTDOWN) state.value = RecState.IDLE
            is EngineEvent.Armed -> updatePreRollArming()
            else -> { }
        }
    }

    private fun cancelCountdown() { triggers.cancelCountdownRequest(); if (state.value == RecState.COUNTDOWN) state.value = RecState.IDLE }

    /** Time range (boot-time ms) a fired sound trigger occupies, padded; null for non-audio events. */
    internal fun scrubWindowMs(e: TriggerEvent, s: AppSettings, nowMs: Long): Pair<Long, Long>? {
        val gap = s.triggers.audio.clapMaxGapMs
        val (start, end) = when (e) {
            is TriggerEvent.ClapBurst -> (e.timestampMs - (e.count - 1) * gap - 250) to (e.timestampMs + 150)
            is TriggerEvent.SnapBurst -> (e.timestampMs - (e.count - 1) * gap - 200) to (e.timestampMs + 120)
            is TriggerEvent.Whistle -> (e.timestampMs - e.durationMs - 150) to (e.timestampMs + 150)
            is TriggerEvent.Loud -> (e.timestampMs - 250) to (e.timestampMs + 250)
            is TriggerEvent.Voice -> (e.timestampMs - s.audio.scrubVoicePhraseMs) to (e.timestampMs + 250)
            else -> return null
        }
        val pad = s.audio.scrubPadMs.toLong()
        val tail = if (s.audio.scrubFeedbackSounds) maxOf(end + pad, nowMs + 900) else end + pad
        return (start - pad) to tail
    }

    private fun scrubTriggerSound(e: TriggerEvent) {
        val sc = audioScrubber ?: return
        val s = settingsStore.current
        if (!s.audio.scrubTriggerSounds) return
        val (a, b) = scrubWindowMs(e, s, Clock.bootMs()) ?: return
        sc.mute(a * 1_000_000L, b * 1_000_000L)
    }

    /** Continuous pinch-zoom from the hand interpreter: zoom follows the finger spread while a pinch is held. */
    fun onPinchScale(e: TriggerEvent.PinchScale) = ctrl.post {
        val h = settingsStore.current.triggers.hand
        if (!h.continuousPinchZoom || !(::triggers.isInitialized && triggers.armed.value)) return@post
        if (e.start || pinchBaseZoom == null) pinchBaseZoom = engine.frameInfo.value.zoom ?: settingsStore.current.capture.zoomRatio
        val base = pinchBaseZoom ?: 1f
        val target = base * e.scale.coerceIn(0.2f, 5f).toDouble().pow(h.pinchZoomGain.toDouble()).toFloat()
        engine.setZoom(target, animate = false)
    }

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
            renderer != null -> 1.0   // the GL pipeline renders every frame; time-lapse decimation is direct-path only
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
        engine.setRecordingTarget(true); renderer?.setRecording(true)
        venc.requestKeyFrame()
        audioEncoder?.enabled = true
        if (s.video.intervalCaptureMs > 0 && renderer == null) scheduleInterval(s.video.intervalCaptureMs)
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
        audioScrubber?.flush()
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
        engine.setRecordingTarget(false); renderer?.setRecording(false)
        audioScrubber?.flush()
        ctrl.postDelayed(if (audioScrubber != null) 450L else 0L) { audioEncoder?.enabled = false }
        // Let the last frames (and any held-back audio) drain, then finalize.
        ctrl.postDelayed(if (audioScrubber != null) 900L else 350L) {
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
