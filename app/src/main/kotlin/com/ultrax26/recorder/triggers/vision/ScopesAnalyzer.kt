package com.ultrax26.recorder.triggers.vision

import android.graphics.Bitmap
import com.ultrax26.recorder.settings.OverlaySettings
import kotlinx.coroutines.flow.MutableStateFlow

/** Output of the exposure/focus scopes computed from analysis frames. */
data class ScopesResult(
    val histogram: IntArray = IntArray(256),
    val avgLuma: Float = 0f,
    val clippedHighPct: Float = 0f,
    val clippedLowPct: Float = 0f,
    val zebra: Bitmap? = null,
    val peaking: Bitmap? = null,
    val falseColor: Bitmap? = null,
    val waveform: Bitmap? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** Histogram, zebra, focus peaking, false color and waveform from the ARGB analysis pixels. */
class ScopesAnalyzer {
    val result = MutableStateFlow(ScopesResult())
    private var luma = ByteArray(0)
    private var zebraPx = IntArray(0)
    private var peakPx = IntArray(0)
    private var fcPx = IntArray(0)
    private var wfPx = IntArray(0)
    private var zebraBmp: Bitmap? = null
    private var peakBmp: Bitmap? = null
    private var fcBmp: Bitmap? = null
    private var wfBmp: Bitmap? = null
    private var zebraPhase = 0

    fun analyze(argb: IntArray, w: Int, h: Int, o: OverlaySettings) {
        if (!(o.histogram || o.zebra || o.focusPeaking || o.falseColor || o.waveform || o.exposureInfo)) return
        val n = w * h
        if (luma.size != n) { luma = ByteArray(n); zebraPx = IntArray(n); peakPx = IntArray(n); fcPx = IntArray(n) }
        val hist = IntArray(256)
        var sum = 0L
        for (i in 0 until n) {
            val p = argb[i]
            val y = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            luma[i] = y.toByte(); hist[y]++; sum += y
        }
        val avg = sum.toFloat() / n
        val hiThr = (o.zebraThreshold.coerceIn(50, 100) * 255 / 100)
        var hi = 0; var lo = 0
        for (v in hiThr..255) hi += hist[v]
        for (v in 0..8) lo += hist[v]

        var zebra: Bitmap? = null; var peaking: Bitmap? = null; var fc: Bitmap? = null; var wf: Bitmap? = null
        if (o.zebra) {
            zebraPhase = (zebraPhase + 2) % 16
            for (i in 0 until n) {
                val y = luma[i].toInt() and 0xFF
                val x = i % w; val row = i / w
                zebraPx[i] = if (y >= hiThr && ((x + row + zebraPhase) / 6) % 2 == 0) 0xC0FF2D2D.toInt() else 0
            }
            zebra = ensure(zebraBmp, w, h).also { it.setPixels(zebraPx, 0, w, 0, 0, w, h); zebraBmp = it }
        }
        if (o.focusPeaking) {
            val thr = o.focusPeakingThreshold.coerceIn(5, 200)
            for (row in 1 until h - 1) {
                val r0 = (row - 1) * w; val r1 = row * w; val r2 = (row + 1) * w
                for (x in 1 until w - 1) {
                    val gx = (luma[r0 + x + 1].toInt() and 0xFF) + 2 * (luma[r1 + x + 1].toInt() and 0xFF) + (luma[r2 + x + 1].toInt() and 0xFF) -
                             (luma[r0 + x - 1].toInt() and 0xFF) - 2 * (luma[r1 + x - 1].toInt() and 0xFF) - (luma[r2 + x - 1].toInt() and 0xFF)
                    val gy = (luma[r2 + x - 1].toInt() and 0xFF) + 2 * (luma[r2 + x].toInt() and 0xFF) + (luma[r2 + x + 1].toInt() and 0xFF) -
                             (luma[r0 + x - 1].toInt() and 0xFF) - 2 * (luma[r0 + x].toInt() and 0xFF) - (luma[r0 + x + 1].toInt() and 0xFF)
                    val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
                    peakPx[r1 + x] = if (mag > thr * 4) 0xE0FF3B30.toInt() else 0
                }
            }
            peaking = ensure(peakBmp, w, h).also { it.setPixels(peakPx, 0, w, 0, 0, w, h); peakBmp = it }
        }
        if (o.falseColor) {
            for (i in 0 until n) fcPx[i] = falseColor(luma[i].toInt() and 0xFF)
            fc = ensure(fcBmp, w, h).also { it.setPixels(fcPx, 0, w, 0, 0, w, h); fcBmp = it }
        }
        if (o.waveform) {
            val wh = 128
            if (wfPx.size != w * wh) wfPx = IntArray(w * wh)
            java.util.Arrays.fill(wfPx, 0)
            for (i in 0 until n) {
                val x = i % w
                val y = (luma[i].toInt() and 0xFF) shr 1 // 0..127
                val idx = (wh - 1 - y) * w + x
                val cur = wfPx[idx]
                val a = ((cur ushr 24) + 24).coerceAtMost(255)
                wfPx[idx] = (a shl 24) or 0x00E5FF66
            }
            wf = (wfBmp?.takeIf { it.width == w && it.height == wh } ?: Bitmap.createBitmap(w, wh, Bitmap.Config.ARGB_8888)).also { it.setPixels(wfPx, 0, w, 0, 0, w, wh); wfBmp = it }
        }
        result.value = ScopesResult(hist, avg, hi * 100f / n, lo * 100f / n, zebra, peaking, fc, wf, w, h)
    }

    private fun ensure(b: Bitmap?, w: Int, h: Int): Bitmap = b?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** ARRI-style false-color bands (luma %). */
    private fun falseColor(y: Int): Int {
        val pct = y * 100 / 255
        return when {
            pct < 3 -> 0xFF6A0DAD.toInt()   // purple: crushed
            pct < 8 -> 0xFF1E3A8A.toInt()   // blue
            pct < 38 -> 0xFF44403C.toInt()  // grey: normal shadows
            pct < 43 -> 0xFF15803D.toInt()  // green: 18% grey
            pct < 52 -> 0xFF57534E.toInt()  // grey
            pct < 58 -> 0xFFFDA4AF.toInt()  // pink: caucasian skin
            pct < 94 -> 0xFF78716C.toInt()  // grey
            pct < 99 -> 0xFFEAB308.toInt()  // yellow: near clip
            else -> 0xFFDC2626.toInt()      // red: clipped
        }
    }
}
