package com.ultrax26.recorder

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.ultrax26.recorder.camera.CameraCatalog
import com.ultrax26.recorder.camera.CameraEngine
import com.ultrax26.recorder.camera.ThermalMonitor
import com.ultrax26.recorder.feedback.Feedback
import com.ultrax26.recorder.recording.AudioCapture
import com.ultrax26.recorder.recording.RecordingController
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.settings.SettingsStore
import com.ultrax26.recorder.triggers.TriggerEngine
import com.ultrax26.recorder.triggers.audio.AudioTriggerHub
import com.ultrax26.recorder.triggers.audio.KeywordStore
import com.ultrax26.recorder.triggers.audio.SystemSpeechRecognizer
import com.ultrax26.recorder.triggers.device.MediaButtonTriggers
import com.ultrax26.recorder.triggers.device.MotionTriggers
import com.ultrax26.recorder.triggers.device.VolumeKeyTriggers
import com.ultrax26.recorder.triggers.vision.FaceGestureInterpreter
import com.ultrax26.recorder.triggers.vision.FrameDispatcher
import com.ultrax26.recorder.triggers.vision.HandGestureInterpreter
import com.ultrax26.recorder.triggers.vision.ScopesAnalyzer
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide object graph (manual DI). Created once by [UltraXApp]. */
class AppGraph(val app: Application) {
    private val tag = "Graph"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val catalog = CameraCatalog(app)
    val thermal = ThermalMonitor(app).also { it.start() }
    val feedback = Feedback(app)
    val engine = CameraEngine(app, catalog)
    val keywordStore = KeywordStore(app)
    val scopes = ScopesAnalyzer()
    lateinit var triggers: TriggerEngine
    val handInterpreter = HandGestureInterpreter(settings.current.triggers.hand) { e -> triggers.onEvent(e) }
    val faceInterpreter = FaceGestureInterpreter(settings.current.triggers.face) { e -> triggers.onEvent(e) }
    val dispatcher = FrameDispatcher(handInterpreter, faceInterpreter, scopes)
    val controller = RecordingController(app, settings, catalog, engine, dispatcher, feedback, thermal)
    val motion = MotionTriggers(app) { e -> triggers.onEvent(e) }
    val volumeKeys = VolumeKeyTriggers { e -> triggers.onEvent(e) }
    val mediaButtons = MediaButtonTriggers(app) { e -> triggers.onEvent(e) }
    var audioHub: AudioTriggerHub? = null
        private set
    val audioHubState = MutableStateFlow<AudioTriggerHub?>(null)
    var speech: SystemSpeechRecognizer? = null
        private set
    val speechState = MutableStateFlow<SystemSpeechRecognizer?>(null)
    val cameraScreenVisible = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private var lastAudioCfgKey: String? = null
    private var capture: AudioCapture? = null
    private var captureKey: String? = null
    val foreground = MutableStateFlow(false)

    init {
        triggers = TriggerEngine(
            nowMs = { Clock.bootMs() },
            recorderState = { controller.state.value },
            perform = { a, r -> controller.perform(a, r) },
            notify = { e -> feedback.onEngineEvent(e); controller.onEngineEvent(e); if (e is com.ultrax26.recorder.triggers.EngineEvent.Fired) feedback.suppressAudioUntilMs.let { audioHub?.suppressUntilMs = it } },
        )
        controller.triggers = triggers
        triggers.updateSettings(settings.current.triggers)
        feedback.config = settings.current.triggers.feedback
        triggers.setArmed(settings.current.triggers.armedByDefault)
        controller.audioCaptureProvider = { capture }
        scope.launch { settings.settings.collect { s -> onSettings(s) } }
        scope.launch { triggers.armed.collect { controller.onArmedChanged() } }
    }

    private fun onSettings(s: AppSettings) {
        if (foreground.value) ensureDetectors(s)
        triggers.updateSettings(s.triggers)
        feedback.config = s.triggers.feedback
        if (foreground.value) ensureAudioCapture(s)
        controller.onSettingsChanged(s)
        val hub = audioHub
        if (hub != null) {
            hub.audioTriggersEnabled = s.triggers.audio.enabled
            hub.keywordEnabled = s.triggers.voice.enabled && s.triggers.voice.keywordSpotter
            hub.setKeywordSensitivity(s.triggers.voice.keywordSensitivity, s.triggers.voice.keywordMargin)
            val key = "${s.triggers.audio}"
            if (lastAudioCfgKey != null && key != lastAudioCfgKey) capture?.let { attachAudioHub(it) }
            lastAudioCfgKey = key
        }
        applyDeviceTriggers(s)
        applySpeech(s)
    }

    private fun hasMicPermission() = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** (Re)create the shared microphone capture when audio settings change. */
    private fun ensureAudioCapture(s: AppSettings) {
        if (!hasMicPermission()) return
        val a = s.audio
        val key = listOf(a.source, a.sampleRate, a.channels, a.preferredInputDeviceId, a.privacySensitive, a.gainDb, a.highPassHz, a.limiter, a.muteWhileRecording).joinToString("|")
        if (capture != null && key == captureKey) return
        stopAudioCapture()
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val dev = a.preferredInputDeviceId?.let { id -> am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } }
        val cap = AudioCapture(com.ultrax26.recorder.recording.AudioCaptureConfig(a.source, a.sampleRate, a.channels, dev, a.privacySensitive, a.gainDb, a.highPassHz, a.limiter, a.muteWhileRecording))
        if (cap.start()) {
            capture = cap; captureKey = key
            attachAudioHub(cap)
        } else UxLog.w(tag, "audio capture failed to start")
    }

    private fun stopAudioCapture() {
        audioHub?.let { h -> capture?.removeListener(h); h.stop() }
        audioHub = null; audioHubState.value = null
        capture?.stop(); capture = null; captureKey = null
    }

    /** Activity onStart/onStop: microphone + camera live only while the app is visible. */
    fun setForeground(visible: Boolean) {
        foreground.value = visible
        if (visible) ensureAudioCapture(settings.current)
        controller.setForeground(visible)
        if (!visible) { stopAudioCapture(); speech?.stop(); speech = null; speechState.value = null; motion.stop(); mediaButtons.stop() }
        else { applyDeviceTriggers(settings.current); applySpeech(settings.current) }
    }

    fun onPermissionsChanged() { if (foreground.value) { ensureAudioCapture(settings.current); controller.rebuild() } }

    private fun attachAudioHub(cap: AudioCapture) {
        val s = settings.current
        audioHub?.let { old -> cap.removeListener(old); old.stop() }
        val hub = AudioTriggerHub(cap.config.sampleRate, s.triggers.audio, s.triggers.voice, keywordStore.load()) { e -> triggers.onEvent(e) }
        hub.onTemplatesChanged = { t -> keywordStore.save(t) }
        hub.suppressUntilMs = feedback.suppressAudioUntilMs
        hub.start()
        cap.addListener(hub)
        audioHub = hub
        audioHubState.value = hub
        lastAudioCfgKey = "${s.triggers.audio}"
        UxLog.i(tag, "audio trigger hub attached @${cap.config.sampleRate} Hz")
    }

    private fun applyDeviceTriggers(s: AppSettings) {
        val visible = foreground.value
        volumeKeys.enabled = s.triggers.device.volumeKeys
        if (visible) {
            motion.start(s.triggers.device.shake, s.triggers.device.proximity)
            if (s.triggers.device.bluetoothButtons) mediaButtons.start() else mediaButtons.stop()
        } else { motion.stop(); mediaButtons.stop() }
    }

    private fun applySpeech(s: AppSettings) {
        val want = foreground.value && s.triggers.voice.enabled && s.triggers.voice.systemRecognizer
        if (want && speech == null) {
            val r = SystemSpeechRecognizer(app, s.triggers.voice) { e -> triggers.onEvent(e) }
            speech = r; speechState.value = r; r.start()
        } else if (!want && speech != null) {
            speech?.stop(); speech = null; speechState.value = null
        }
    }

    /** The camera screen tells the graph when it is on screen (detectors, mic, sensors follow). */
    fun setCameraScreenVisible(visible: Boolean) {
        cameraScreenVisible.value = visible
        val s = settings.current
        applyDeviceTriggers(s)
        applySpeech(s)
        if (visible) ensureDetectors(s)
    }

    private var detectorsThread: Thread? = null

    private var segmenterKey: String? = null

    /** MediaPipe/ML Kit init takes a moment; do it off the main thread. */
    fun ensureDetectors(s: AppSettings) {
        val fx = s.effects
        val pipeline = fx.needsPipeline()
        dispatcher.meshEnabled = pipeline && fx.needsFaceMesh()
        dispatcher.segmentationEnabled = pipeline && fx.needsSegmentation()
        dispatcher.poseEnabled = pipeline && fx.needsPose()
        val wantSegKey = if (dispatcher.segmentationEnabled) "seg:${fx.useMultiClassSegmenter}" else null
        if (detectorsThread?.isAlive == true) return
        val needHands = s.triggers.hand.enabled && dispatcher.handDetector == null
        val needFaces = s.triggers.face.enabled && dispatcher.faceDetector == null
        val needMesh = dispatcher.meshEnabled && dispatcher.faceMesh == null
        val needSeg = dispatcher.segmentationEnabled && (dispatcher.segmenter == null || segmenterKey != wantSegKey)
        val needPose = dispatcher.poseEnabled && dispatcher.poseDetector == null
        if (!needHands && !needFaces && !needMesh && !needSeg && !needPose) return
        detectorsThread = Thread({
            val delegate = s.triggers.hand.delegate
            try { if (needMesh) dispatcher.faceMesh = com.ultrax26.recorder.effects.ml.EffectsDetectorFactory.faceMesh(app, delegate) } catch (t: Throwable) { UxLog.e(tag, "face mesh init failed", t); dispatcher.hud.value = dispatcher.hud.value.copy(lastError = "Face mesh failed: ${t.message}") }
            try { if (needSeg) { dispatcher.segmenter?.close(); dispatcher.segmenter = com.ultrax26.recorder.effects.ml.EffectsDetectorFactory.segmenter(app, fx.useMultiClassSegmenter, delegate); segmenterKey = wantSegKey } } catch (t: Throwable) { UxLog.e(tag, "segmenter init failed", t); dispatcher.hud.value = dispatcher.hud.value.copy(lastError = "Segmenter failed: ${t.message}") }
            try { if (needPose) dispatcher.poseDetector = com.ultrax26.recorder.effects.ml.EffectsDetectorFactory.pose(app, delegate) } catch (t: Throwable) { UxLog.e(tag, "pose init failed", t); dispatcher.hud.value = dispatcher.hud.value.copy(lastError = "Pose failed: ${t.message}") }
            try {
                if (needHands) dispatcher.handDetector = com.ultrax26.recorder.triggers.vision.DetectorFactory.hand(app, s.triggers.hand)
            } catch (t: Throwable) { UxLog.e(tag, "MediaPipe init failed", t); dispatcher.hud.value = dispatcher.hud.value.copy(lastError = "Hand model failed: ${t.message}") }
            try {
                if (needFaces) dispatcher.faceDetector = com.ultrax26.recorder.triggers.vision.DetectorFactory.face(s.triggers.face)
            } catch (t: Throwable) { UxLog.e(tag, "ML Kit init failed", t); dispatcher.hud.value = dispatcher.hud.value.copy(lastError = "Face model failed: ${t.message}") }
        }, "ux-ml-init").also { it.start() }
    }

    fun runOnMain(r: () -> Unit) { main.post(r) }
}
