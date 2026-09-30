package com.ultrax26.recorder.camera

import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import com.ultrax26.recorder.settings.MeteringRegionS

/**
 * Maps normalized preview coordinates (0..1 across the *displayed* frame) to active-array
 * coordinates. With CONTROL_ZOOM_RATIO in use the active array rectangle already represents the
 * zoomed field of view, so only the aspect-ratio crop between the sensor and the stream matters.
 */
object RegionMapper {

    /** Portion of the active array visible in a stream of the given aspect ratio (center crop). */
    fun visibleRect(activeArray: Rect, streamAspect: Float): Rect {
        val aw = activeArray.width().toFloat()
        val ah = activeArray.height().toFloat()
        val arrayAspect = aw / ah
        return if (streamAspect > arrayAspect) {
            // stream is wider -> crop top/bottom
            val h = aw / streamAspect
            val top = activeArray.top + ((ah - h) / 2f).toInt()
            Rect(activeArray.left, top, activeArray.right, top + h.toInt())
        } else {
            val w = ah * streamAspect
            val left = activeArray.left + ((aw - w) / 2f).toInt()
            Rect(left, activeArray.top, left + w.toInt(), activeArray.bottom)
        }
    }

    /**
     * @param nx,ny normalized tap position in *sensor-oriented* frame coordinates (caller applies
     *              display rotation/mirroring first).
     * @param sizeFrac half-size of the metering box as a fraction of the visible width.
     */
    fun meteringRect(activeArray: Rect, streamAspect: Float, nx: Float, ny: Float, sizeFrac: Float = 0.08f, weight: Int = MeteringRectangle.METERING_WEIGHT_MAX): MeteringRectangle {
        val vis = visibleRect(activeArray, streamAspect)
        val cx = vis.left + nx.coerceIn(0f, 1f) * vis.width()
        val cy = vis.top + ny.coerceIn(0f, 1f) * vis.height()
        val half = sizeFrac * vis.width()
        val l = (cx - half).toInt().coerceIn(activeArray.left, activeArray.right - 1)
        val t = (cy - half).toInt().coerceIn(activeArray.top, activeArray.bottom - 1)
        val r = (cx + half).toInt().coerceIn(l + 1, activeArray.right)
        val b = (cy + half).toInt().coerceIn(t + 1, activeArray.bottom)
        return MeteringRectangle(l, t, r - l, b - t, weight)
    }

    fun toMeteringRectangles(activeArray: Rect, streamAspect: Float, regions: List<MeteringRegionS>, max: Int): Array<MeteringRectangle>? {
        if (max <= 0 || regions.isEmpty()) return null
        val vis = visibleRect(activeArray, streamAspect)
        return regions.take(max).map { r ->
            val l = (vis.left + r.x * vis.width()).toInt().coerceIn(activeArray.left, activeArray.right - 1)
            val t = (vis.top + r.y * vis.height()).toInt().coerceIn(activeArray.top, activeArray.bottom - 1)
            val w = (r.w * vis.width()).toInt().coerceIn(1, activeArray.right - l)
            val h = (r.h * vis.height()).toInt().coerceIn(1, activeArray.bottom - t)
            MeteringRectangle(l, t, w, h, r.weight.coerceIn(MeteringRectangle.METERING_WEIGHT_MIN, MeteringRectangle.METERING_WEIGHT_MAX))
        }.toTypedArray()
    }

    /**
     * Convert a tap in *view* coordinates (0..1, as displayed) to sensor-oriented normalized coords.
     * @param rotation total rotation from sensor to display (0/90/180/270), @param mirrored front camera.
     */
    fun viewToSensor(vx: Float, vy: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        var x = vx; var y = vy
        if (mirrored) x = 1f - x
        return when (((rotation % 360) + 360) % 360) {
            90 -> Pair(y, 1f - x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }
    }
}
