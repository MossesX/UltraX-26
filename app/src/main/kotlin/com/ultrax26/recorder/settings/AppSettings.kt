package com.ultrax26.recorder.settings

import com.ultrax26.recorder.calls.CallSettings
import com.ultrax26.recorder.effects.EffectsSettings
import com.ultrax26.recorder.triggers.DefaultRules
import com.ultrax26.recorder.triggers.TriggerRule
import kotlinx.serialization.Serializable

/**
 * Root of every user-configurable option. Persisted as JSON (see [SettingsStore]) and exported /
 * imported as presets. Every field has a default so older files keep loading.
 *
 * Convention for camera controls: a `null` value means "leave the device default / auto";
 * a non-null value is applied verbatim to the CaptureRequest.
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val capture: CaptureSettings = CaptureSettings(),
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val triggers: TriggerSettings = TriggerSettings(),
    val overlays: OverlaySettings = OverlaySettings(),
    val storage: StorageSettings = StorageSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
    val ui: UiSettings = UiSettings(),
    val effects: EffectsSettings = EffectsSettings(),
    val calls: CallSettings = CallSettings(),
)

// ------------------------------------------------------------------------------------------------
// Camera (CaptureRequest) controls
// ------------------------------------------------------------------------------------------------

@Serializable
data class IntRangeS(val lower: Int, val upper: Int)

/** Normalized metering region (0..1 in the active array), weight 1..1000. */
@Serializable
data class MeteringRegionS(val x: Float, val y: Float, val w: Float, val h: Float, val weight: Int = 1000)

/** A raw CaptureRequest / session key set by name; `type` tells the applier how to parse `value`. */
@Serializable
data class CustomKeyValue(
    val keyName: String,
    val type: KeyType,
    val value: String,
    val enabled: Boolean = true,
)

@Serializable
enum class KeyType(val label: String) {
    INT("int"), LONG("long"), FLOAT("float"), DOUBLE("double"), BOOLEAN("boolean"), BYTE("byte"),
    INT_ARRAY("int[]"), FLOAT_ARRAY("float[]"), LONG_ARRAY("long[]"), BYTE_ARRAY("byte[]"), BOOLEAN_ARRAY("boolean[]"), DOUBLE_ARRAY("double[]"),
    STRING("String"), RATIONAL("Rational"), SIZE("Size"), RANGE_INT("Range<Integer>"), RANGE_LONG("Range<Long>"), RANGE_FLOAT("Range<Float>"),
    RECT("Rect"), POINT("Point"), METERING_RECTANGLES("MeteringRectangle[]"),
    RGGB_CHANNEL_VECTOR("RggbChannelVector"), COLOR_SPACE_TRANSFORM("ColorSpaceTransform"), TONEMAP_CURVE("TonemapCurve"),
}

@Serializable
enum class TonemapPreset(val label: String) {
    DEVICE("Device default"),
    SRGB("sRGB"),
    REC709("Rec.709"),
    LINEAR("Linear"),
    FLAT_LOG("Flat / log-like (grade later)"),
    GAMMA("Gamma value"),
    CUSTOM("Custom curve"),
}

@Serializable
enum class LensMode(val label: String) {
    AUTO("Auto (zoom ratio picks the lens)"),
    LOCK_PHYSICAL("Lock to a physical camera"),
}

@Serializable
data class CaptureSettings(
    // ---- Camera / lens ----
    val cameraId: String? = null,
    val lensMode: LensMode = LensMode.AUTO,
    val lockedPhysicalCameraId: String? = null,
    val zoomRatio: Float = 1f,
    val zoomRampSpeed: Float = 2f,           // ratio units per second for animated zoom
    val zoomStep: Float = 0.5f,
    val probeHiddenCameraIds: Boolean = true,
    val hiddenIdProbeMax: Int = 99,

    // ---- Exposure ----
    val aeMode: Int? = null,                 // CONTROL_AE_MODE_*
    val manualExposure: Boolean = false,     // AE off; use iso/exposureTimeNs
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val exposureCompensation: Int = 0,
    val aeLock: Boolean = false,
    val aeAntibanding: Int? = null,          // CONTROL_AE_ANTIBANDING_MODE_*
    val aePrecaptureTrigger: Boolean = false,
    val targetFpsRange: IntRangeS? = null,
    val flashMode: Int? = null,              // FLASH_MODE_*
    val torch: Boolean = false,
    val flashStrengthLevel: Int? = null,     // Android 13+ torch strength
    val postRawSensitivityBoost: Int? = null,

    // ---- Focus ----
    val afMode: Int? = null,                 // CONTROL_AF_MODE_*
    val manualFocus: Boolean = false,
    val focusDistance: Float? = null,        // diopters (0 = infinity)
    val afRegions: List<MeteringRegionS> = emptyList(),
    val aeRegions: List<MeteringRegionS> = emptyList(),
    val awbRegions: List<MeteringRegionS> = emptyList(),
    val focusPullEnabled: Boolean = false,
    val focusPullFrom: Float = 0f,
    val focusPullTo: Float = 2f,
    val focusPullDurationMs: Long = 2000,

    // ---- White balance / color ----
    val awbMode: Int? = null,                // CONTROL_AWB_MODE_*
    val awbLock: Boolean = false,
    val manualWhiteBalance: Boolean = false,
    val whiteBalanceKelvin: Int = 5500,
    val whiteBalanceTint: Float = 0f,
    val colorCorrectionMode: Int? = null,
    val colorCorrectionAberration: Int? = null,
    val colorCorrectionGains: List<Float> = emptyList(), // 4 = manual RGGB gains override
    val colorCorrectionTransform: List<Float> = emptyList(), // 9 = manual 3x3 matrix override

    // ---- Stabilization ----
    val videoStabilization: Int? = null,     // CONTROL_VIDEO_STABILIZATION_MODE_*
    val opticalStabilization: Int? = null,   // LENS_OPTICAL_STABILIZATION_MODE_*

    // ---- Processing ----
    val noiseReduction: Int? = null,
    val edgeMode: Int? = null,
    val tonemapMode: Int? = null,
    val tonemapPreset: TonemapPreset = TonemapPreset.DEVICE,
    val tonemapGamma: Float = 2.2f,
    val tonemapCustomCurve: List<Float> = emptyList(), // (in,out) pairs
    val distortionCorrection: Int? = null,
    val hotPixelMode: Int? = null,
    val shadingMode: Int? = null,
    val sceneMode: Int? = null,
    val effectMode: Int? = null,
    val extendedSceneMode: Int? = null,
    val faceDetectMode: Int? = null,
    val controlMode: Int? = null,
    val captureIntent: Int? = null,
    val blackLevelLock: Boolean = false,
    val settingsOverride: Int? = null,       // Android 14 CONTROL_SETTINGS_OVERRIDE_*
    val autoframing: Int? = null,            // Android 14 CONTROL_AUTOFRAMING_*
    val lensAperture: Float? = null,
    val lensFilterDensity: Float? = null,
    val lensFocalLength: Float? = null,
    val testPatternMode: Int? = null,
    val statisticsLensShadingMap: Boolean = false,
    val statisticsOisData: Boolean = false,
    val statisticsHotPixelMap: Boolean = false,
    val jpegQuality: Int = 95,

    // ---- Raw / everything else ----
    val customKeys: List<CustomKeyValue> = emptyList(),
    val sessionParameters: List<CustomKeyValue> = emptyList(),
)

// ------------------------------------------------------------------------------------------------
// Video / encoder
// ------------------------------------------------------------------------------------------------

@Serializable
enum class VideoCodec(val label: String, val mime: String) {
    AVC("H.264 / AVC", "video/avc"),
    HEVC("H.265 / HEVC", "video/hevc"),
    AV1("AV1", "video/av01"),
    APV("APV (Advanced Professional Video)", "video/apv"),
}

@Serializable
enum class BitrateMode(val label: String) { VBR("Variable (VBR)"), CBR("Constant (CBR)"), CQ("Constant quality (CQ)") }

@Serializable
enum class HdrMode(val label: String) {
    OFF("SDR (8-bit)"),
    HLG10("HLG 10-bit"),
    HDR10("HDR10 (PQ, 10-bit)"),
    HDR10_PLUS("HDR10+ (PQ, dynamic metadata)"),
    DOLBY_VISION("Dolby Vision 10-bit (OEM profile)"),
}

@Serializable
enum class Container(val label: String) { MP4("MP4"), WEBM("WebM (VP8/VP9 only)"), THREE_GPP("3GPP") }

@Serializable
data class VideoSettings(
    val width: Int = 3840,
    val height: Int = 2160,
    val fps: Int = 30,
    val highSpeed: Boolean = false,          // constrained high-speed session (e.g. 120/240 fps)
    val slowMotionPlaybackFps: Int? = null,  // when set, high-speed footage is muxed to play back at this rate
    val codec: VideoCodec = VideoCodec.HEVC,
    val encoderName: String? = null,         // pin a specific MediaCodec encoder
    val profile: Int? = null,                // MediaCodecInfo.CodecProfileLevel.* value; null = auto
    val level: Int? = null,
    val bitrateMode: BitrateMode = BitrateMode.VBR,
    val bitrateMbps: Float? = null,          // null = auto (by resolution/fps/codec)
    val cqQuality: Int = 80,
    val iFrameIntervalSec: Float = 1f,
    val maxBFrames: Int = 0,
    val hdr: HdrMode = HdrMode.OFF,
    val fullRange: Boolean = false,
    val orientationLock: Int? = null,        // 0/90/180/270; null = follow device
    val mirrorFrontCamera: Boolean = true,
    val timelapseFactor: Int = 1,            // keep 1 of N frames (camera-side decimation)
    val intervalCaptureMs: Long = 0,         // > 0 = one frame every N ms (long timelapse)
    val segmentMinutes: Int = 0,             // 0 = never split
    val maxFileSizeMb: Int = 0,              // 0 = unlimited
    val preRollSeconds: Int = 0,             // 0 = off; encoder runs while armed and keeps N s in RAM
    val maxDurationSec: Int = 0,             // 0 = unlimited; auto-stop
    val stopOnThermalCritical: Boolean = true,
    val downgradeOnThermalSevere: Boolean = false,
    val container: Container = Container.MP4,
    val embedTimecode: Boolean = true,
    val writeGyroSidecar: Boolean = false,   // CSV of gyro samples for post stabilization
)

// ------------------------------------------------------------------------------------------------
// Audio
// ------------------------------------------------------------------------------------------------

@Serializable
enum class AudioCodec(val label: String, val mime: String) {
    AAC("AAC-LC", "audio/mp4a-latm"),
    PCM_WAV_SIDECAR("AAC in MP4 + lossless WAV sidecar", "audio/mp4a-latm"),
}

@Serializable
data class AudioSettings(
    val enabled: Boolean = true,
    val source: Int = 5,                     // MediaRecorder.AudioSource.CAMCORDER
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bitrateKbps: Int = 256,
    val codec: AudioCodec = AudioCodec.AAC,
    val preferredInputDeviceId: Int? = null, // AudioDeviceInfo.id (Bluetooth / USB mic)
    val gainDb: Float = 0f,
    val highPassHz: Int = 0,                 // wind / rumble filter; 0 = off
    val limiter: Boolean = true,
    val privacySensitive: Boolean? = null,   // null = system default for the source
    val muteWhileRecording: Boolean = false,
    val triggerFeedbackDucking: Boolean = true,
    // ---- trigger-sound removal: the recorded audio is held back a little so the clap / snap / whistle /
    //      spoken command that fired a rule can be silenced before it is encoded ----
    val scrubTriggerSounds: Boolean = false,
    val scrubMode: ScrubMode = ScrubMode.MUTE,
    val scrubDelayMs: Int = 2500,            // audio latency budget (video is unaffected; A/V stays in sync)
    val scrubVoicePhraseMs: Int = 2500,      // how far before a recognized command to start silencing
    val scrubPadMs: Int = 150,               // extra silence around each sound
    val scrubFeedbackSounds: Boolean = true, // also silence the confirmation beep the phone plays
)

@Serializable
enum class ScrubMode(val label: String) { MUTE("Silence"), DUCK("Duck to −30 dB") }

// ------------------------------------------------------------------------------------------------
// Triggers (gesture / voice / device)
// ------------------------------------------------------------------------------------------------

@Serializable
enum class GestureCameraSource(val label: String) {
    SAME("Same camera as the recording"),
    FRONT("Front camera (concurrent)"),
    BACK("Back camera (concurrent)"),
}

@Serializable
enum class MlDelegate(val label: String) { CPU("CPU"), GPU("GPU") }

@Serializable
data class HandGestureConfig(
    val enabled: Boolean = true,
    val maxHands: Int = 2,
    val minDetectionConfidence: Float = 0.5f,
    val minPresenceConfidence: Float = 0.5f,
    val minTrackingConfidence: Float = 0.5f,
    val delegate: MlDelegate = MlDelegate.GPU,
    val stabilityFrames: Int = 2,            // consecutive frames before a gesture counts as "held"
    val releaseGraceMs: Long = 250,          // tolerate classifier flicker
    // ---- pinch zoom (thumb ↔ index finger) ----
    val pinchZoom: Boolean = true,           // emit pinch / unpinch events (rules map them to zoom steps)
    val continuousPinchZoom: Boolean = false,// hold a pinch, then spread/close to drive the zoom ratio live
    val pinchZoomGain: Float = 1.5f,         // zoom = base × spread^gain
    val pinchCloseRatio: Float = 0.35f,      // tip distance / hand size below which fingers count as touching
    val pinchOpenRatio: Float = 0.75f,       // above which they count as spread
    val pinchDeltaRatio: Float = 0.4f,       // or: a change of this much within the window counts, wherever it starts
    val pinchWindowMs: Long = 800,           // max time for the close→open or open→close movement
)

@Serializable
data class FaceGestureConfig(
    val enabled: Boolean = true,
    val eyeClosedThreshold: Float = 0.3f,
    val eyeOpenThreshold: Float = 0.6f,
    val blinkMinMs: Long = 40,
    val blinkMaxMs: Long = 500,
    val blinkBurstGapMs: Long = 700,
    val smileThreshold: Float = 0.8f,
    val mouthOpenRatio: Float = 0.42f,
    val nodDegrees: Float = 8f,
    val shakeDegrees: Float = 10f,
    val tiltDegrees: Float = 18f,
    val minFaceSize: Float = 0.08f,
    val trackLargestFaceOnly: Boolean = true,
)

@Serializable
data class AudioTriggerConfig(
    val enabled: Boolean = true,
    val clapSensitivity: Float = 0.6f,       // 0..1
    val clapMaxGapMs: Long = 600,
    val snapSensitivity: Float = 0.5f,
    val whistleSensitivity: Float = 0.5f,
    val loudThresholdDbfs: Float = -8f,
    val ignoreWhileFeedbackPlaying: Boolean = true,
)

@Serializable
data class VoiceConfig(
    val enabled: Boolean = true,
    val systemRecognizer: Boolean = true,
    val systemLanguageTag: String = "",      // "" = device default
    val preferOffline: Boolean = true,
    val keywordSpotter: Boolean = true,
    val keywordSensitivity: Float = 0.5f,    // 0..1 (higher = more permissive)
    val keywordMargin: Float = 0.08f,
    val minUtteranceMs: Long = 250,
    val maxUtteranceMs: Long = 2000,
    val silenceMs: Long = 300,
)

@Serializable
data class DeviceTriggerConfig(
    val volumeKeys: Boolean = true,
    val bluetoothButtons: Boolean = true,
    val shake: Boolean = false,
    val proximity: Boolean = false,
)

@Serializable
data class FeedbackConfig(
    val haptic: Boolean = true,
    val beep: Boolean = true,
    val beepVolume: Float = 0.6f,
    val screenFlash: Boolean = true,
    val speak: Boolean = false,              // TTS announce ("Recording", "Stopped")
    val showHud: Boolean = true,
)

@Serializable
data class TriggerSettings(
    val armedByDefault: Boolean = true,
    val rules: List<TriggerRule> = DefaultRules.build(),
    val globalCooldownMs: Long = 1200,
    val countdownSeconds: Int = 3,
    val countdownAppliesToVoice: Boolean = false,
    val gestureCamera: GestureCameraSource = GestureCameraSource.SAME,
    val hand: HandGestureConfig = HandGestureConfig(),
    val face: FaceGestureConfig = FaceGestureConfig(),
    val audio: AudioTriggerConfig = AudioTriggerConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val device: DeviceTriggerConfig = DeviceTriggerConfig(),
    val feedback: FeedbackConfig = FeedbackConfig(),
    val gestureHoldRequiresRelease: Boolean = true,
)

// ------------------------------------------------------------------------------------------------
// Overlays / analysis / storage / UI
// ------------------------------------------------------------------------------------------------

@Serializable
enum class GridType(val label: String) { NONE("None"), THIRDS("Rule of thirds"), GOLDEN("Golden ratio"), CENTER("Center cross"), DIAGONALS("Diagonals"), SQUARE("Square guide") }

@Serializable
enum class AspectGuide(val label: String, val ratio: Float) {
    NONE("None", 0f), R_2_39("2.39:1 Anamorphic", 2.39f), R_1_85("1.85:1 Flat", 1.85f), R_16_9("16:9", 16f / 9f),
    R_4_3("4:3", 4f / 3f), R_1_1("1:1", 1f), R_9_16("9:16 Vertical", 9f / 16f), R_4_5("4:5 Portrait", 0.8f)
}

@Serializable
data class OverlaySettings(
    val grid: GridType = GridType.NONE,
    val aspectGuide: AspectGuide = AspectGuide.NONE,
    val level: Boolean = true,
    val histogram: Boolean = false,
    val waveform: Boolean = false,
    val zebra: Boolean = false,
    val zebraThreshold: Int = 95,            // % luma
    val focusPeaking: Boolean = false,
    val focusPeakingThreshold: Int = 40,
    val falseColor: Boolean = false,
    val audioMeter: Boolean = true,
    val timecode: Boolean = true,
    val gestureHud: Boolean = true,
    val exposureInfo: Boolean = true,
    val safeAreas: Boolean = false,
    val showCenterMarker: Boolean = false,
)

@Serializable
data class AnalysisSettings(
    val width: Int = 640,
    val height: Int = 480,
    val targetFps: Int = 12,
    val enabled: Boolean = true,
    val preferP010WhenHdr: Boolean = true,
)

@Serializable
enum class StorageLocation(val label: String) {
    MEDIA_STORE("Gallery (Movies/UltraX26)"),
    SAF_TREE("Custom folder / SD card"),
    APP_PRIVATE("App-private storage"),
}

@Serializable
data class StorageSettings(
    val location: StorageLocation = StorageLocation.MEDIA_STORE,
    val safTreeUri: String? = null,
    val subfolder: String = "UltraX26",
    val fileNameTemplate: String = "UX26_{date}_{time}_{res}_{fps}fps_{codec}{seg}",
    val geotag: Boolean = false,
    val minFreeSpaceMb: Int = 500,
    val writeSidecarJson: Boolean = true,    // per-clip metadata (settings, markers, triggers)
)

@Serializable
enum class UiThemeMode(val label: String) { DARK("Dark"), AMOLED("AMOLED black"), SYSTEM("System") }

@Serializable
data class UiSettings(
    val theme: UiThemeMode = UiThemeMode.AMOLED,
    val keepScreenOn: Boolean = true,
    val hapticsOnControls: Boolean = true,
    val dimPreviewWhileRecording: Boolean = false,
    val showAdvancedControls: Boolean = true,
    val lastPresetName: String? = null,
)
