package com.ultrax26.recorder.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range
import android.util.Rational
import com.ultrax26.recorder.settings.CaptureSettings
import com.ultrax26.recorder.settings.CustomKeyValue
import com.ultrax26.recorder.settings.TonemapPreset
import com.ultrax26.recorder.util.ColorTemperature
import com.ultrax26.recorder.util.UxLog

/** What happened while translating settings into a request (surfaced in the UI/diagnostics). */
class ApplyReport {
    val applied = ArrayList<String>()
    val skipped = ArrayList<String>()
    val errors = ArrayList<String>()
    override fun toString() = "applied=${applied.size} skipped=${skipped.size} errors=${errors.size}${if (errors.isNotEmpty()) " " + errors.joinToString(" | ") else ""}"
}

/** Everything the applier needs beyond the settings themselves. */
data class ApplyContext(
    val chars: CameraCharacteristics,
    val availableKeys: Set<String>,
    val streamAspect: Float,
    val fps: Int,
    val highSpeed: Boolean,
    val lastAwbTransform: ColorSpaceTransform?,
    /** Live overrides from the camera screen (tap-to-focus, zoom animation, locks, torch). */
    val overrides: LiveOverrides = LiveOverrides(),
)

data class LiveOverrides(
    val zoom: Float? = null,
    val focusDistance: Float? = null,
    val afMode: Int? = null,
    val afTrigger: Int? = null,
    val aeLock: Boolean? = null,
    val awbLock: Boolean? = null,
    val torch: Boolean? = null,
    val afRegions: Array<android.hardware.camera2.params.MeteringRectangle>? = null,
    val aeRegions: Array<android.hardware.camera2.params.MeteringRectangle>? = null,
    val aePrecapture: Boolean = false,
)

/**
 * Translates [CaptureSettings] into CaptureRequest keys. Only keys the device advertises are set;
 * the rest are recorded as skipped so the user can see what their phone doesn't expose.
 */
object RequestApplier {
    private const val TAG = "Apply"

    fun apply(b: CaptureRequest.Builder, s: CaptureSettings, ctx: ApplyContext): ApplyReport {
        val r = ApplyReport()
        val c = ctx.chars
        val ov = ctx.overrides

        fun <T> set(key: CaptureRequest.Key<T>, value: T?) {
            if (value == null) return
            if (key.name !in ctx.availableKeys) { r.skipped += key.name; return }
            try { b.set(key, value); r.applied += key.name } catch (t: Throwable) { r.errors += "${key.name}: ${t.message}" }
        }

        // ---- Global mode / intent ----
        val usingScene = s.sceneMode != null && s.sceneMode != CameraMetadata.CONTROL_SCENE_MODE_DISABLED
        set(CaptureRequest.CONTROL_MODE, s.controlMode ?: if (usingScene) CameraMetadata.CONTROL_MODE_USE_SCENE_MODE else CameraMetadata.CONTROL_MODE_AUTO)
        if (usingScene) set(CaptureRequest.CONTROL_SCENE_MODE, s.sceneMode)
        set(CaptureRequest.CONTROL_CAPTURE_INTENT, s.captureIntent ?: CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        set(CaptureRequest.CONTROL_ENABLE_ZSL, false)

        // ---- Zoom ----
        val zr = Capabilities.zoomRange(c)
        val zoom = (ov.zoom ?: s.zoomRatio).coerceIn(zr.lower, zr.upper)
        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)

        // ---- Exposure ----
        val frameNs = if (ctx.fps > 0) 1_000_000_000L / ctx.fps else 33_333_333L
        if (s.manualExposure && !ctx.highSpeed) {
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            Capabilities.isoRange(c)?.let { set(CaptureRequest.SENSOR_SENSITIVITY, (s.iso ?: it.lower * 2).coerceIn(it.lower, it.upper)) }
            val expRange = Capabilities.exposureRange(c)
            val maxByFrame = s.frameDurationNs ?: frameNs
            val exp = (s.exposureTimeNs ?: (frameNs / 2)).coerceIn(expRange?.lower ?: 1L, minOf(expRange?.upper ?: Long.MAX_VALUE, maxByFrame))
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(s.frameDurationNs ?: frameNs, exp))
        } else {
            set(CaptureRequest.CONTROL_AE_MODE, s.aeMode ?: CameraMetadata.CONTROL_AE_MODE_ON)
            val ev = Capabilities.evRange(c)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.exposureCompensation.coerceIn(ev.lower, ev.upper))
            set(CaptureRequest.CONTROL_AE_LOCK, ov.aeLock ?: s.aeLock)
            val fpsRange: Range<Int>? = s.targetFpsRange?.let { Range(it.lower, it.upper) } ?: Capabilities.pickFpsRange(c, ctx.fps)
            if (!ctx.highSpeed && fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, s.aeAntibanding)
        }
        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            if (ov.aePrecapture) CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START else CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        s.postRawSensitivityBoost?.let { boost -> Capabilities.postRawBoostRange(c)?.let { set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost.coerceIn(it.lower, it.upper)) } }

        // ---- Flash / torch ----
        if (Capabilities.flashAvailable(c)) {
            val torch = ov.torch ?: s.torch
            set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else (s.flashMode ?: CameraMetadata.FLASH_MODE_OFF))
            if (Build.VERSION.SDK_INT >= 35 && s.flashStrengthLevel != null) {
                set(CaptureRequest.FLASH_STRENGTH_LEVEL, s.flashStrengthLevel.coerceIn(1, Capabilities.torchMaxStrength(c)))
            }
        }

        // ---- Focus ----
        val minFocus = Capabilities.minFocusDistance(c)
        val manualFocusActive = ov.focusDistance != null || s.manualFocus
        if (manualFocusActive && minFocus > 0f) {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, (ov.focusDistance ?: s.focusDistance ?: 0f).coerceIn(0f, minFocus))
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, ov.afMode ?: s.afMode ?: CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        set(CaptureRequest.CONTROL_AF_TRIGGER, ov.afTrigger ?: CameraMetadata.CONTROL_AF_TRIGGER_IDLE)

        val active: Rect? = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (active != null) {
            val af = ov.afRegions ?: RegionMapper.toMeteringRectangles(active, ctx.streamAspect, s.afRegions, Capabilities.maxAfRegions(c))
            val ae = ov.aeRegions ?: RegionMapper.toMeteringRectangles(active, ctx.streamAspect, s.aeRegions, Capabilities.maxAeRegions(c))
            val awb = RegionMapper.toMeteringRectangles(active, ctx.streamAspect, s.awbRegions, Capabilities.maxAwbRegions(c))
            if (af != null) set(CaptureRequest.CONTROL_AF_REGIONS, af)
            if (ae != null) set(CaptureRequest.CONTROL_AE_REGIONS, ae)
            if (awb != null) set(CaptureRequest.CONTROL_AWB_REGIONS, awb)
        }

        // ---- White balance / color ----
        if (s.manualWhiteBalance && !ctx.highSpeed) {
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val gains = if (s.colorCorrectionGains.size == 4) s.colorCorrectionGains.toFloatArray()
                        else ColorTemperature.gainsForKelvin(s.whiteBalanceKelvin, s.whiteBalanceTint)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains[0], gains[1], gains[2], gains[3]))
            val transform = if (s.colorCorrectionTransform.size == 9) {
                ColorSpaceTransform(s.colorCorrectionTransform.map { Rational((it * 10000).toInt(), 10000) }.toTypedArray())
            } else ctx.lastAwbTransform ?: IDENTITY
            set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode ?: CameraMetadata.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AWB_LOCK, ov.awbLock ?: s.awbLock)
            set(CaptureRequest.COLOR_CORRECTION_MODE, s.colorCorrectionMode)
        }
        set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, s.colorCorrectionAberration)

        // ---- Stabilization ----
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, s.videoStabilization)
        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, s.opticalStabilization)

        // ---- Processing ----
        set(CaptureRequest.NOISE_REDUCTION_MODE, s.noiseReduction)
        set(CaptureRequest.EDGE_MODE, s.edgeMode)
        applyTonemap(s, c, ::set, r)
        set(CaptureRequest.DISTORTION_CORRECTION_MODE, s.distortionCorrection)
        set(CaptureRequest.HOT_PIXEL_MODE, s.hotPixelMode)
        set(CaptureRequest.SHADING_MODE, s.shadingMode)
        set(CaptureRequest.CONTROL_EFFECT_MODE, s.effectMode)
        set(CaptureRequest.CONTROL_EXTENDED_SCENE_MODE, s.extendedSceneMode)
        set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, s.faceDetectMode)
        set(CaptureRequest.BLACK_LEVEL_LOCK, if (s.blackLevelLock) true else null)
        if (Build.VERSION.SDK_INT >= 34) {
            set(CaptureRequest.CONTROL_SETTINGS_OVERRIDE, s.settingsOverride)
            set(CaptureRequest.CONTROL_AUTOFRAMING, s.autoframing)
        }
        set(CaptureRequest.LENS_APERTURE, s.lensAperture)
        set(CaptureRequest.LENS_FILTER_DENSITY, s.lensFilterDensity)
        set(CaptureRequest.LENS_FOCAL_LENGTH, s.lensFocalLength)
        set(CaptureRequest.SENSOR_TEST_PATTERN_MODE, s.testPatternMode)
        set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            if (s.statisticsLensShadingMap) CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON else CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        set(CaptureRequest.STATISTICS_OIS_DATA_MODE,
            if (s.statisticsOisData) CameraMetadata.STATISTICS_OIS_DATA_MODE_ON else CameraMetadata.STATISTICS_OIS_DATA_MODE_OFF)
        set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, if (s.statisticsHotPixelMap) true else null)
        set(CaptureRequest.JPEG_QUALITY, s.jpegQuality.coerceIn(1, 100).toByte())

        // ---- Raw custom keys (vendor tags included) ----
        applyCustomKeys(b, s.customKeys, ctx.availableKeys, r)
        return r
    }

    private fun applyTonemap(s: CaptureSettings, c: CameraCharacteristics, set: (CaptureRequest.Key<Any>, Any?) -> Unit, r: ApplyReport) {
        @Suppress("UNCHECKED_CAST")
        fun <T> k(key: CaptureRequest.Key<T>) = key as CaptureRequest.Key<Any>
        when (s.tonemapPreset) {
            TonemapPreset.DEVICE -> set(k(CaptureRequest.TONEMAP_MODE), s.tonemapMode)
            TonemapPreset.GAMMA -> {
                set(k(CaptureRequest.TONEMAP_MODE), CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                set(k(CaptureRequest.TONEMAP_GAMMA), s.tonemapGamma)
            }
            TonemapPreset.SRGB, TonemapPreset.REC709 -> {
                val modes = Capabilities.tonemapModes(c).map { it.value }
                if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in modes) {
                    set(k(CaptureRequest.TONEMAP_MODE), CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                    set(k(CaptureRequest.TONEMAP_PRESET_CURVE), if (s.tonemapPreset == TonemapPreset.SRGB) CameraMetadata.TONEMAP_PRESET_CURVE_SRGB else CameraMetadata.TONEMAP_PRESET_CURVE_REC709)
                } else {
                    set(k(CaptureRequest.TONEMAP_MODE), CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    set(k(CaptureRequest.TONEMAP_CURVE), Tonemaps.curve(s.tonemapPreset, s.tonemapGamma, s.tonemapCustomCurve, Capabilities.tonemapMaxPoints(c)))
                }
            }
            TonemapPreset.LINEAR, TonemapPreset.FLAT_LOG, TonemapPreset.CUSTOM -> {
                val curve = Tonemaps.curve(s.tonemapPreset, s.tonemapGamma, s.tonemapCustomCurve, Capabilities.tonemapMaxPoints(c))
                if (curve == null) { r.errors += "tonemap: invalid custom curve"; return }
                set(k(CaptureRequest.TONEMAP_MODE), CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                set(k(CaptureRequest.TONEMAP_CURVE), curve)
            }
        }
    }

    /** Resolve a key by name (standard key via reflection, else a vendor key with the chosen type) and set it. */
    fun applyCustomKeys(b: CaptureRequest.Builder, keys: List<CustomKeyValue>, available: Set<String>, r: ApplyReport) {
        keys.filter { it.enabled }.forEach { kv ->
            try {
                val value = GenericKeyCodec.parse(kv.type, kv.value)
                val key: CaptureRequest.Key<*> = Capabilities.standardRequestKeys[kv.keyName]
                    ?: CaptureRequest.Key(kv.keyName, GenericKeyCodec.javaClass(kv.type))
                if (kv.keyName !in available && kv.keyName.startsWith("android.")) { r.skipped += kv.keyName; return@forEach }
                @Suppress("UNCHECKED_CAST")
                b.set(key as CaptureRequest.Key<Any>, value)
                r.applied += kv.keyName
            } catch (t: Throwable) {
                r.errors += "${kv.keyName}: ${t.message}"
                UxLog.w(TAG, "custom key ${kv.keyName} failed: ${t.message}")
            }
        }
    }

    val IDENTITY: ColorSpaceTransform = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
}
