package com.ultrax26.recorder.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.ultrax26.recorder.util.UxLog
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Friendly summary of one camera (logical or physical, public or hidden). */
data class CameraInfo(
    val id: String,
    val hidden: Boolean,
    val facing: Int,
    val hardwareLevel: Int,
    val capabilities: IntArray,
    val isLogical: Boolean,
    val physicalIds: List<String>,
    val focalLengths: FloatArray,
    val apertures: FloatArray,
    val sensorSizeMm: SizeF?,
    val activeArray: Rect?,
    val pixelArray: Size?,
    val zoomRange: Range<Float>?,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDistance: Float?,
    val hyperfocalDistance: Float?,
    val maxVideoSize: Size?,
    val supports8k: Boolean,
    val dynamicRangeProfiles: Set<Long>,
    val highSpeedSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val sensorOrientation: Int,
    val timestampSource: Int,
    val requestKeyNames: List<String>,
    val vendorRequestKeyNames: List<String>,
    val equivalentFocalMm35: Float?,
) {
    val facingLabel: String get() = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_BACK -> "Back"
        else -> "External"
    }
    val levelLabel: String get() = when (hardwareLevel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }
    fun hasCapability(cap: Int) = capabilities.contains(cap)
    val supportsManualSensor get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val supportsManualPostProcessing get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
    val supports10Bit get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
    val supportsHighSpeed get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
    val supportsRaw get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    val supportsStreamUseCase get() = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)

    /** "0.6×", "1×", "3×", "5×" relative to a 24 mm-equivalent main camera. */
    fun zoomLabel(referenceEq35: Float = 24f): String {
        val eq = equivalentFocalMm35 ?: return "${id}"
        val r = eq / referenceEq35
        val s = if (r < 0.95f) String.format("%.1f", r) else if (r < 10f) String.format("%.1f", r).removeSuffix(".0") else r.roundToInt().toString()
        return "$s×"
    }

    val shortName: String get() {
        val eq = equivalentFocalMm35
        val fl = if (eq != null) " ${eq.roundToInt()}mm eq" else ""
        val hidden = if (hidden) " (hidden)" else ""
        val logical = if (isLogical) " logical" else ""
        return "Camera $id · $facingLabel$logical$fl$hidden"
    }
}

/** Enumerates cameras, including Samsung's hidden per-lens IDs, and summarizes their capabilities. */
class CameraCatalog(private val context: Context) {
    private val manager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cache = HashMap<String, CameraCharacteristics>()

    fun characteristics(id: String): CameraCharacteristics =
        cache.getOrPut(id) { manager.getCameraCharacteristics(id) }

    fun publicIds(): List<String> = try { manager.cameraIdList.toList() } catch (t: Throwable) { UxLog.e("Catalog", "cameraIdList", t); emptyList() }

    /**
     * Probe numeric IDs not in [publicIds]. Samsung phones expose only the logical back/front
     * cameras publicly; per-lens sensors (ultra-wide, 3x, 5x) usually answer to hidden IDs.
     */
    fun hiddenIds(maxId: Int): List<String> {
        val pub = publicIds().toSet()
        val found = ArrayList<String>()
        for (i in 0..maxId) {
            val id = i.toString()
            if (id in pub) continue
            try {
                characteristics(id)
                found += id
            } catch (_: Throwable) {
                // not a camera
            }
        }
        return found
    }

    fun allIds(probeHidden: Boolean, maxHidden: Int): List<String> {
        val ids = LinkedHashSet(publicIds())
        if (probeHidden) ids += hiddenIds(maxHidden)
        return ids.toList()
    }

    fun concurrentSets(): Set<Set<String>> = try { manager.concurrentCameraIds } catch (t: Throwable) { emptySet() }

    fun info(id: String, hidden: Boolean = false): CameraInfo? = try {
        val c = characteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()
        val maxVideo = videoSizes.maxByOrNull { it.width.toLong() * it.height }
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq35 = if (focal.isNotEmpty() && sensor != null && sensor.width > 0f) {
            val diag = hypot(sensor.width, sensor.height)
            focal[0] * (43.27f / diag)
        } else null
        val drp: Set<Long> = if (Build.VERSION.SDK_INT >= 33) {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles ?: setOf(DynamicRangeProfiles.STANDARD)
        } else setOf(DynamicRangeProfiles.STANDARD)
        val reqKeys = c.availableCaptureRequestKeys.map { it.name }
        CameraInfo(
            id = id,
            hidden = hidden,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            capabilities = caps,
            isLogical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            physicalIds = c.physicalCameraIds.toList().sorted(),
            focalLengths = focal,
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0),
            sensorSizeMm = sensor,
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            hyperfocalDistance = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE),
            maxVideoSize = maxVideo,
            supports8k = videoSizes.any { it.width >= 7680 && it.height >= 4320 },
            dynamicRangeProfiles = drp,
            highSpeedSizes = map?.highSpeedVideoSizes?.toList() ?: emptyList(),
            fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            requestKeyNames = reqKeys,
            vendorRequestKeyNames = reqKeys.filter { !it.startsWith("android.") },
            equivalentFocalMm35 = eq35,
        )
    } catch (t: Throwable) {
        UxLog.w("Catalog", "info($id) failed: ${t.message}")
        null
    }

    fun allInfos(probeHidden: Boolean, maxHidden: Int): List<CameraInfo> {
        val pub = publicIds()
        val out = ArrayList<CameraInfo>()
        pub.forEach { id -> info(id, hidden = false)?.let(out::add) }
        if (probeHidden) hiddenIds(maxHidden).forEach { id -> info(id, hidden = true)?.let(out::add) }
        return out
    }

    /** Physical sub-cameras of a logical camera, with their infos (they are addressable even if not public). */
    fun physicalInfos(logicalId: String): List<CameraInfo> =
        (info(logicalId)?.physicalIds ?: emptyList()).mapNotNull { info(it, hidden = true) }

    /** Best default: first public back-facing camera, else the first public camera. */
    fun defaultBackId(): String? {
        val ids = publicIds()
        return ids.firstOrNull { (characteristics(it).get(CameraCharacteristics.LENS_FACING) ?: -1) == CameraCharacteristics.LENS_FACING_BACK } ?: ids.firstOrNull()
    }

    fun defaultFrontId(): String? = publicIds().firstOrNull {
        (characteristics(it).get(CameraCharacteristics.LENS_FACING) ?: -1) == CameraCharacteristics.LENS_FACING_FRONT
    }
}
