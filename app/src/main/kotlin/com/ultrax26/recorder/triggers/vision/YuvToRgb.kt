package com.ultrax26.recorder.triggers.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

/**
 * Converts camera analysis frames (YUV_420_888 or 10-bit YCBCR_P010) to ARGB pixels without
 * RenderScript or GPU dependencies. Sized for small analysis streams (≤ 1280×720).
 */
class YuvToRgb {
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)
    private var argb = IntArray(0)

    val pixels: IntArray get() = argb

    /** Fill [bitmap] (ARGB_8888, same size as image) from [image]. Returns false if the format is unsupported. */
    fun convert(image: Image, bitmap: Bitmap): Boolean {
        val w = image.width; val h = image.height
        if (argb.size != w * h) argb = IntArray(w * h)
        val planes = image.planes
        when (image.format) {
            ImageFormat.YUV_420_888 -> convert8(planes, w, h)
            ImageFormat.YCBCR_P010 -> convert10(planes, w, h)
            else -> return false
        }
        bitmap.setPixels(argb, 0, w, 0, 0, w, h)
        return true
    }

    private fun read(plane: Image.Plane, target: ByteArray): ByteArray {
        val b = plane.buffer
        b.rewind()
        val n = b.remaining()
        val arr = if (target.size >= n) target else ByteArray(n)
        b.get(arr, 0, n)
        return arr
    }

    private fun convert8(planes: Array<Image.Plane>, w: Int, h: Int) {
        yBuf = read(planes[0], yBuf); uBuf = read(planes[1], uBuf); vBuf = read(planes[2], vBuf)
        val yRs = planes[0].rowStride; val yPs = planes[0].pixelStride
        val uRs = planes[1].rowStride; val uPs = planes[1].pixelStride
        val vRs = planes[2].rowStride; val vPs = planes[2].pixelStride
        var o = 0
        for (row in 0 until h) {
            val yRow = row * yRs
            val cRow = (row shr 1)
            val uRow = cRow * uRs; val vRow = cRow * vRs
            for (col in 0 until w) {
                val y = (yBuf[yRow + col * yPs].toInt() and 0xFF)
                val ci = col shr 1
                val u = (uBuf[uRow + ci * uPs].toInt() and 0xFF) - 128
                val v = (vBuf[vRow + ci * vPs].toInt() and 0xFF) - 128
                argb[o++] = yuv(y, u, v)
            }
        }
    }

    private fun convert10(planes: Array<Image.Plane>, w: Int, h: Int) {
        yBuf = read(planes[0], yBuf); uBuf = read(planes[1], uBuf); vBuf = read(planes[2], vBuf)
        val yRs = planes[0].rowStride; val yPs = planes[0].pixelStride
        val uRs = planes[1].rowStride; val uPs = planes[1].pixelStride
        val vRs = planes[2].rowStride; val vPs = planes[2].pixelStride
        var o = 0
        for (row in 0 until h) {
            val yRow = row * yRs
            val cRow = (row shr 1)
            val uRow = cRow * uRs; val vRow = cRow * vRs
            for (col in 0 until w) {
                // P010: 16-bit little-endian, 10 significant bits in the MSBs → take the high byte.
                val y = yBuf[yRow + col * yPs + 1].toInt() and 0xFF
                val ci = col shr 1
                val u = (uBuf[uRow + ci * uPs + 1].toInt() and 0xFF) - 128
                val v = (vBuf[vRow + ci * vPs + 1].toInt() and 0xFF) - 128
                argb[o++] = yuv(y, u, v)
            }
        }
    }

    private fun yuv(y: Int, u: Int, v: Int): Int {
        // BT.601 limited-range approximation, integer math.
        val yy = 1192 * (y - 16).coerceAtLeast(0)
        var r = (yy + 1634 * v) shr 10
        var g = (yy - 833 * v - 400 * u) shr 10
        var b = (yy + 2066 * u) shr 10
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
