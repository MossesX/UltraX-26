package com.ultrax26.recorder.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import com.ultrax26.recorder.settings.HdrMode

/** Named option for enum-style CaptureRequest controls (value + label), filtered by device support. */
data class EnumOption(val value: Int, val label: String)

/**
 * Convenience accessors over CameraCharacteristics used by the engine and the settings UI.
 * Everything here is *what the device advertises* — the UI never shows an option the HAL doesn't list.
 */
object Capabilities {

    /**
     * Every size the camera can deliver to an encoder: the regular PRIVATE / MediaCodec / MediaRecorder /
     * SurfaceTexture output lists plus the "high resolution" PRIVATE sizes (API 23+). Some HALs — Samsung's
     * among them — list 7680×4320 only in the high-resolution set, which is documented as possibly running
     * below 20 fps; [highResolutionOnlySizes] tells the UI which ones those are.
     */
    fun videoSizes(c: CameraCharacteristics): List<Size> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = LinkedHashSet<Size>()
        regularVideoSizes(c).let { sizes += it }
        try { map.getHighResolutionOutputSizes(ImageFormat.PRIVATE)?.let { sizes += it } } catch (_: Throwable) { }
        return sizes.sortedByDescending { it.width.toLong() * it.height }
    }

    /** Sizes guaranteed for normal-rate streaming (no high-resolution set). */
    fun regularVideoSizes(c: CameraCharacteristics): List<Size> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = LinkedHashSet<Size>()
        try { map.getOutputSizes(MediaCodec::class.java)?.let { sizes += it } } catch (_: Throwable) { }
        try { map.getOutputSizes(ImageFormat.PRIVATE)?.let { sizes += it } } catch (_: Throwable) { }
        try { map.getOutputSizes(android.media.MediaRecorder::class.java)?.let { sizes += it } } catch (_: Throwable) { }
        try { map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.let { sizes += it } } catch (_: Throwable) { }
        return sizes.sortedByDescending { it.width.toLong() * it.height }
    }

    /** Sizes that appear only in the high-resolution set (may stream below 20 fps). */
    fun highResolutionOnlySizes(c: CameraCharacteristics): Set<Size> {
        val regular = regularVideoSizes(c).toSet()
        return videoSizes(c).filterNot { it in regular }.toSet()
    }

    fun has8k(c: CameraCharacteristics): Boolean = videoSizes(c).any { it.width >= 7680 && it.height >= 4320 }

    fun previewSizes(c: CameraCharacteristics): List<Size> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return (map.getOutputSizes(android.view.SurfaceHolder::class.java)?.toList() ?: emptyList())
            .sortedByDescending { it.width.toLong() * it.height }
    }

    fun analysisSizes(c: CameraCharacteristics, format: Int = ImageFormat.YUV_420_888): List<Size> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return map.getOutputSizes(format)?.toList()?.sortedBy { it.width.toLong() * it.height } ?: emptyList()
    }

    /** Max fps the camera can deliver for a MediaCodec-consumed size in a regular session. */
    fun maxFpsFor(c: CameraCharacteristics, size: Size): Int {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 30
        val minDur = try { map.getOutputMinFrameDuration(MediaCodec::class.java, size) } catch (_: Throwable) {
            try { map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size) } catch (_: Throwable) { 0L }
        }
        if (minDur <= 0) return 30
        return (1_000_000_000.0 / minDur).toInt().coerceAtLeast(1)
    }

    fun fpsRanges(c: CameraCharacteristics): List<Range<Int>> =
        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

    /** Frame rates a user may pick for a normal session at [size]: fixed ranges the AE supports, capped by stream min duration. */
    fun selectableFps(c: CameraCharacteristics, size: Size): List<Int> {
        val cap = maxFpsFor(c, size)
        val fixed = fpsRanges(c).map { it.upper }.toSortedSet()
        val common = sortedSetOf(24, 25, 30, 48, 50, 60)
        return (fixed + common).filter { it <= cap }.sorted().distinct()
    }

    fun highSpeedSizes(c: CameraCharacteristics): List<Size> =
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoSizes?.toList() ?: emptyList()

    fun highSpeedFpsRanges(c: CameraCharacteristics, size: Size): List<Range<Int>> = try {
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getHighSpeedVideoFpsRangesFor(size)?.toList() ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    fun pickFpsRange(c: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = fpsRanges(c)
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return ranges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower }
            ?: ranges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }
    }

    fun dynamicRangeProfiles(c: CameraCharacteristics): DynamicRangeProfiles? =
        if (Build.VERSION.SDK_INT >= 33) c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) else null

    fun supportedHdrModes(c: CameraCharacteristics): List<HdrMode> {
        val out = arrayListOf(HdrMode.OFF)
        val p = dynamicRangeProfiles(c)?.supportedProfiles ?: return out
        if (DynamicRangeProfiles.HLG10 in p) out += HdrMode.HLG10
        if (DynamicRangeProfiles.HDR10 in p) out += HdrMode.HDR10
        if (DynamicRangeProfiles.HDR10_PLUS in p) out += HdrMode.HDR10_PLUS
        if (p.any { it and DOLBY_VISION_MASK != 0L }) out += HdrMode.DOLBY_VISION
        return out
    }

    private const val DOLBY_VISION_MASK: Long =
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF or DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO or
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM or DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO or
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF or DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO or
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM or DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO

    /** Map the user's HDR mode to a concrete DynamicRangeProfiles constant the device supports. */
    fun profileFor(c: CameraCharacteristics, mode: HdrMode): Long {
        val p = dynamicRangeProfiles(c)?.supportedProfiles ?: return DynamicRangeProfiles.STANDARD
        return when (mode) {
            HdrMode.OFF -> DynamicRangeProfiles.STANDARD
            HdrMode.HLG10 -> if (DynamicRangeProfiles.HLG10 in p) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
            HdrMode.HDR10 -> if (DynamicRangeProfiles.HDR10 in p) DynamicRangeProfiles.HDR10 else DynamicRangeProfiles.STANDARD
            HdrMode.HDR10_PLUS -> if (DynamicRangeProfiles.HDR10_PLUS in p) DynamicRangeProfiles.HDR10_PLUS else DynamicRangeProfiles.STANDARD
            HdrMode.DOLBY_VISION -> listOf(
                DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM, DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO,
                DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF, DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO,
            ).firstOrNull { it in p } ?: DynamicRangeProfiles.STANDARD
        }
    }

    /** Can a STANDARD (8-bit) stream live in the same request as [profile]? */
    fun standardAllowedWith(c: CameraCharacteristics, profile: Long): Boolean {
        if (profile == DynamicRangeProfiles.STANDARD) return true
        val d = dynamicRangeProfiles(c) ?: return false
        val constraints = try { d.getProfileCaptureRequestConstraints(profile) } catch (_: Throwable) { emptySet<Long>() }
        return constraints.isEmpty() || DynamicRangeProfiles.STANDARD in constraints
    }

    fun streamUseCases(c: CameraCharacteristics): LongArray =
        if (Build.VERSION.SDK_INT >= 33) c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES) ?: LongArray(0) else LongArray(0)

    fun availableKeyNames(c: CameraCharacteristics): Set<String> = c.availableCaptureRequestKeys.map { it.name }.toSet()
    fun sessionKeyNames(c: CameraCharacteristics): Set<String> = try { c.availableSessionKeys.map { it.name }.toSet() } catch (_: Throwable) { emptySet() }

    // ---- Enum option lists (only what the device lists) ------------------------------------

    private fun opts(values: IntArray?, names: Map<Int, String>): List<EnumOption> =
        values?.sorted()?.map { EnumOption(it, names[it] ?: "Mode $it") } ?: emptyList()

    fun aeModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), mapOf(
        CameraMetadata.CONTROL_AE_MODE_OFF to "Off (manual)", CameraMetadata.CONTROL_AE_MODE_ON to "On",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH to "On + auto flash", CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH to "On + always flash",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE to "On + red-eye", CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH to "On + external flash",
        CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY to "Low-light boost"))

    fun afModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), mapOf(
        CameraMetadata.CONTROL_AF_MODE_OFF to "Off (manual)", CameraMetadata.CONTROL_AF_MODE_AUTO to "Auto (single)",
        CameraMetadata.CONTROL_AF_MODE_MACRO to "Macro", CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO to "Continuous video",
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE to "Continuous picture", CameraMetadata.CONTROL_AF_MODE_EDOF to "EDOF"))

    fun awbModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), mapOf(
        CameraMetadata.CONTROL_AWB_MODE_OFF to "Off (manual)", CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy", CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
        CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade"))

    fun antibandingModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES), mapOf(
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF to "Off", CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ to "50 Hz",
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ to "60 Hz", CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO to "Auto"))

    fun videoStabilizationModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), mapOf(
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF to "Off", CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON to "On (EIS)",
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION to "Preview stabilization (locked look)"))

    fun oisModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), mapOf(
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF to "Off", CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON to "On"))

    fun noiseReductionModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), mapOf(
        CameraMetadata.NOISE_REDUCTION_MODE_OFF to "Off", CameraMetadata.NOISE_REDUCTION_MODE_FAST to "Fast",
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY to "High quality", CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL to "Minimal",
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG to "Zero shutter lag"))

    fun edgeModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), mapOf(
        CameraMetadata.EDGE_MODE_OFF to "Off", CameraMetadata.EDGE_MODE_FAST to "Fast",
        CameraMetadata.EDGE_MODE_HIGH_QUALITY to "High quality", CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG to "Zero shutter lag"))

    fun tonemapModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), mapOf(
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE to "Contrast curve", CameraMetadata.TONEMAP_MODE_FAST to "Fast",
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY to "High quality", CameraMetadata.TONEMAP_MODE_GAMMA_VALUE to "Gamma value",
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE to "Preset curve"))

    fun aberrationModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), mapOf(
        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF to "Off", CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST to "Fast",
        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY to "High quality"))

    fun distortionModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES), mapOf(
        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF to "Off", CameraMetadata.DISTORTION_CORRECTION_MODE_FAST to "Fast",
        CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY to "High quality"))

    fun hotPixelModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES), mapOf(
        CameraMetadata.HOT_PIXEL_MODE_OFF to "Off", CameraMetadata.HOT_PIXEL_MODE_FAST to "Fast", CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY to "High quality"))

    fun shadingModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES), mapOf(
        CameraMetadata.SHADING_MODE_OFF to "Off", CameraMetadata.SHADING_MODE_FAST to "Fast", CameraMetadata.SHADING_MODE_HIGH_QUALITY to "High quality"))

    fun sceneModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES), mapOf(
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED to "Disabled", CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY to "Face priority",
        CameraMetadata.CONTROL_SCENE_MODE_ACTION to "Action", CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT to "Portrait",
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE to "Landscape", CameraMetadata.CONTROL_SCENE_MODE_NIGHT to "Night",
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT to "Night portrait", CameraMetadata.CONTROL_SCENE_MODE_THEATRE to "Theatre",
        CameraMetadata.CONTROL_SCENE_MODE_BEACH to "Beach", CameraMetadata.CONTROL_SCENE_MODE_SNOW to "Snow",
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET to "Sunset", CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO to "Steady photo",
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS to "Fireworks", CameraMetadata.CONTROL_SCENE_MODE_SPORTS to "Sports",
        CameraMetadata.CONTROL_SCENE_MODE_PARTY to "Party", CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT to "Candlelight",
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE to "Barcode", CameraMetadata.CONTROL_SCENE_MODE_HDR to "HDR"))

    fun effectModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS), mapOf(
        CameraMetadata.CONTROL_EFFECT_MODE_OFF to "Off", CameraMetadata.CONTROL_EFFECT_MODE_MONO to "Mono",
        CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE to "Negative", CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE to "Solarize",
        CameraMetadata.CONTROL_EFFECT_MODE_SEPIA to "Sepia", CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE to "Posterize",
        CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD to "Whiteboard", CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD to "Blackboard",
        CameraMetadata.CONTROL_EFFECT_MODE_AQUA to "Aqua"))

    fun extendedSceneModes(c: CameraCharacteristics): List<EnumOption> {
        val caps = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES) ?: return emptyList()
        val out = arrayListOf(EnumOption(CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_DISABLED, "Disabled"))
        caps.forEach { cap ->
            out += when (cap.mode) {
                CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE -> EnumOption(cap.mode, "Bokeh (still)")
                CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS -> EnumOption(cap.mode, "Bokeh (continuous)")
                else -> EnumOption(cap.mode, "Extended mode ${cap.mode}")
            }
        }
        return out.distinctBy { it.value }
    }

    fun faceDetectModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES), mapOf(
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF to "Off", CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE to "Simple",
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL to "Full"))

    fun testPatternModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES), mapOf(
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_OFF to "Off", CameraMetadata.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR to "Solid color",
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS to "Color bars", CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY to "Color bars fade",
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_PN9 to "PN9", CameraMetadata.SENSOR_TEST_PATTERN_MODE_CUSTOM1 to "Custom 1"))

    fun settingsOverrides(c: CameraCharacteristics): List<EnumOption> =
        if (Build.VERSION.SDK_INT >= 34) opts(c.get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES), mapOf(
            CameraMetadata.CONTROL_SETTINGS_OVERRIDE_OFF to "Off", CameraMetadata.CONTROL_SETTINGS_OVERRIDE_ZOOM to "Zoom (low-latency)"))
        else emptyList()

    fun autoframingAvailable(c: CameraCharacteristics): Boolean =
        Build.VERSION.SDK_INT >= 34 && (c.get(CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE) ?: false)

    fun controlModes(c: CameraCharacteristics) = opts(c.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES), mapOf(
        CameraMetadata.CONTROL_MODE_OFF to "Off (full manual)", CameraMetadata.CONTROL_MODE_AUTO to "Auto",
        CameraMetadata.CONTROL_MODE_USE_SCENE_MODE to "Use scene mode", CameraMetadata.CONTROL_MODE_OFF_KEEP_STATE to "Off, keep state",
        CameraMetadata.CONTROL_MODE_USE_EXTENDED_SCENE_MODE to "Use extended scene mode"))

    val captureIntents = listOf(
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, "Video record"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW, "Preview"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE, "Still capture"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT, "Video snapshot"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG, "Zero shutter lag"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL, "Manual"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_MOTION_TRACKING, "Motion tracking"),
        EnumOption(CameraMetadata.CONTROL_CAPTURE_INTENT_CUSTOM, "Custom"),
    )

    val flashModes = listOf(
        EnumOption(CameraMetadata.FLASH_MODE_OFF, "Off"),
        EnumOption(CameraMetadata.FLASH_MODE_SINGLE, "Single"),
        EnumOption(CameraMetadata.FLASH_MODE_TORCH, "Torch"),
    )

    fun flashAvailable(c: CameraCharacteristics) = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    fun torchMaxStrength(c: CameraCharacteristics): Int =
        if (Build.VERSION.SDK_INT >= 35) c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1

    fun evRange(c: CameraCharacteristics): Range<Int> = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    fun evStep(c: CameraCharacteristics): Float = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    fun isoRange(c: CameraCharacteristics): Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    fun exposureRange(c: CameraCharacteristics): Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    fun maxFrameDuration(c: CameraCharacteristics): Long = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
    fun minFocusDistance(c: CameraCharacteristics): Float = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    fun zoomRange(c: CameraCharacteristics): Range<Float> = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
    fun maxAfRegions(c: CameraCharacteristics) = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    fun maxAeRegions(c: CameraCharacteristics) = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    fun maxAwbRegions(c: CameraCharacteristics) = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
    fun tonemapMaxPoints(c: CameraCharacteristics) = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
    fun apertures(c: CameraCharacteristics): FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)
    fun filterDensities(c: CameraCharacteristics): FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES) ?: FloatArray(0)
    fun focalLengths(c: CameraCharacteristics): FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
    fun postRawBoostRange(c: CameraCharacteristics): Range<Int>? = c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
    fun lensShadingMapModes(c: CameraCharacteristics): IntArray = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES) ?: IntArray(0)
    fun oisDataModes(c: CameraCharacteristics): IntArray = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES) ?: IntArray(0)

    /** The standard CaptureRequest keys as (name -> Key) via reflection, so custom-key rows can reuse the real typed key. */
    val standardRequestKeys: Map<String, CaptureRequest.Key<*>> by lazy {
        val out = HashMap<String, CaptureRequest.Key<*>>()
        try {
            CaptureRequest::class.java.fields.forEach { f ->
                if (CaptureRequest.Key::class.java.isAssignableFrom(f.type)) {
                    val k = f.get(null) as? CaptureRequest.Key<*> ?: return@forEach
                    out[k.name] = k
                }
            }
        } catch (_: Throwable) { }
        out
    }
}
