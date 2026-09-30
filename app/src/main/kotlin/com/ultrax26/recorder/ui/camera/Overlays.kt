package com.ultrax26.recorder.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ultrax26.recorder.settings.AspectGuide
import com.ultrax26.recorder.settings.GridType
import com.ultrax26.recorder.triggers.vision.VisionHudState
import com.ultrax26.recorder.ui.theme.UxColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

@Composable
fun GridOverlay(grid: GridType, guide: AspectGuide, safeAreas: Boolean, centerMarker: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Color.White.copy(alpha = 0.45f)
        fun v(x: Float) = drawLine(c, Offset(x, 0f), Offset(x, h), 1.5f)
        fun hz(y: Float) = drawLine(c, Offset(0f, y), Offset(w, y), 1.5f)
        when (grid) {
            GridType.THIRDS -> { v(w / 3); v(2 * w / 3); hz(h / 3); hz(2 * h / 3) }
            GridType.GOLDEN -> { v(w * 0.382f); v(w * 0.618f); hz(h * 0.382f); hz(h * 0.618f) }
            GridType.CENTER -> { v(w / 2); hz(h / 2) }
            GridType.DIAGONALS -> { drawLine(c, Offset(0f, 0f), Offset(w, h), 1.5f); drawLine(c, Offset(w, 0f), Offset(0f, h), 1.5f) }
            GridType.SQUARE -> { val s = min(w, h); val x0 = (w - s) / 2; val y0 = (h - s) / 2; drawRect(c, Offset(x0, y0), Size(s, s), style = Stroke(2f)) }
            GridType.NONE -> { }
        }
        if (guide != AspectGuide.NONE && guide.ratio > 0f) {
            val viewAspect = w / h
            val r = if (viewAspect >= 1f) guide.ratio else 1f / guide.ratio
            val shade = Color.Black.copy(alpha = 0.55f)
            if (r > viewAspect) { val gh = w / r; val y0 = (h - gh) / 2; drawRect(shade, Offset(0f, 0f), Size(w, y0)); drawRect(shade, Offset(0f, y0 + gh), Size(w, h - y0 - gh)) }
            else { val gw = h * r; val x0 = (w - gw) / 2; drawRect(shade, Offset(0f, 0f), Size(x0, h)); drawRect(shade, Offset(x0 + gw, 0f), Size(w - x0 - gw, h)) }
        }
        if (safeAreas) {
            drawRect(Color.Yellow.copy(alpha = 0.5f), Offset(w * 0.05f, h * 0.05f), Size(w * 0.9f, h * 0.9f), style = Stroke(1.5f))
            drawRect(Color.Red.copy(alpha = 0.5f), Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), style = Stroke(1.5f))
        }
        if (centerMarker) {
            val cx = w / 2; val cy = h / 2
            drawLine(Color.White, Offset(cx - 18f, cy), Offset(cx + 18f, cy), 2f); drawLine(Color.White, Offset(cx, cy - 18f), Offset(cx, cy + 18f), 2f)
        }
    }
}

/** Draws an analysis-domain bitmap (sensor orientation) rotated/mirrored onto the upright preview. */
@Composable
fun RotatedBitmapOverlay(bitmap: Bitmap?, rotationDegrees: Int, mirrored: Boolean, alpha: Float = 1f, modifier: Modifier = Modifier) {
    if (bitmap == null) return
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val dw = if (swap) h else w; val dh = if (swap) w else h
        val center = Offset(w / 2, h / 2)
        scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f, pivot = center) {
            rotate(rotationDegrees.toFloat(), pivot = center) {
                drawImage(img, dstOffset = IntOffset(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()), alpha = alpha)
            }
        }
    }
}

private val HAND_CONNECTIONS = arrayOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
)

/** Hand skeletons, face boxes and labels. Vision coordinates are upright-normalized; mirror for the front camera. */
@Composable
fun GestureHud(v: VisionHudState, mirrored: Boolean, viewAspect: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val frameAspect = if (v.frameHeight > 0) v.frameWidth.toFloat() / v.frameHeight else viewAspect
        // Center-crop mapping when the analysis aspect differs from the preview aspect.
        fun map(x: Float, y: Float): Offset {
            var nx = x; var ny = y
            if (abs(frameAspect - viewAspect) > 0.02f) {
                if (frameAspect < viewAspect) { val f = frameAspect / viewAspect; ny = (y - (1f - f) / 2f) / f } else { val f = viewAspect / frameAspect; nx = (x - (1f - f) / 2f) / f }
            }
            if (mirrored) nx = 1f - nx
            return Offset(nx * w, ny * h)
        }
        for (hand in v.hands) {
            val lm = hand.landmarks
            if (lm.size >= 21) {
                val col = if (hand.gesture != null) UxColors.Green else UxColors.Sky
                for ((a, b) in HAND_CONNECTIONS) drawLine(col.copy(alpha = 0.8f), map(lm[a].x, lm[a].y), map(lm[b].x, lm[b].y), 3f)
                for (p in lm) drawCircle(Color.White, 4f, map(p.x, p.y))
            }
        }
        for (f in v.faces) {
            val tl = map(f.box.left, f.box.top); val br = map(f.box.right, f.box.bottom)
            val l = min(tl.x, br.x); val t = tl.y
            drawRect(UxColors.Amber.copy(alpha = 0.9f), Offset(l, t), Size(abs(br.x - tl.x), br.y - tl.y), style = Stroke(3f))
            val eyeCol = { p: Float? -> if (p == null) Color.Gray else if (p < 0.3f) UxColors.Red else UxColors.Green }
            drawCircle(eyeCol(f.leftEyeOpen), 8f, Offset(l + 14f, t - 14f))
            drawCircle(eyeCol(f.rightEyeOpen), 8f, Offset(l + 36f, t - 14f))
        }
    }
}

@Composable
fun GestureLabels(v: VisionHudState, blinkText: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        val held = v.heldGesture
        if (held != null) Chip("$held ${(v.heldMs / 100) / 10f}s", UxColors.Green)
        v.fingerCount?.let { if (it > 0) Chip("$it finger${if (it > 1) "s" else ""}", UxColors.Sky) }
        if (blinkText != null) Chip(blinkText, UxColors.Amber)
        if (v.lastError != null) Chip(v.lastError, UxColors.Red)
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.padding(2.dp).background(color.copy(alpha = 0.85f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.Black)
    }
}

@Composable
fun HistogramView(hist: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.Black.copy(alpha = 0.5f))) {
        val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / 256f
        for (i in 0 until 256) {
            val hh = (hist[i] / max) * size.height
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * bw, size.height - hh), Size(bw, hh))
        }
        drawLine(UxColors.Red.copy(alpha = 0.7f), Offset(size.width * 0.95f, 0f), Offset(size.width * 0.95f, size.height), 1f)
    }
}

@Composable
fun AudioMeter(levelDbfs: Float, peakDbfs: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.Black.copy(alpha = 0.5f))) {
        fun frac(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f)
        val l = frac(levelDbfs); val p = frac(peakDbfs)
        val col = if (peakDbfs > -3f) UxColors.Red else if (peakDbfs > -12f) UxColors.Amber else UxColors.Green
        drawRect(col, Offset(0f, 0f), Size(size.width * l, size.height))
        drawRect(Color.White, Offset(size.width * p - 2f, 0f), Size(2f, size.height))
        for (m in listOf(-12f, -6f, -3f)) drawLine(Color.White.copy(alpha = 0.5f), Offset(size.width * frac(m), 0f), Offset(size.width * frac(m), size.height), 1f)
    }
}

/** Gravity-based horizon indicator; green when level within 1°. */
@Composable
fun LevelOverlay(displayRotation: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var angle by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            var gx = 0f; var gy = 0f
            override fun onSensorChanged(e: SensorEvent) {
                gx = gx * 0.8f + e.values[0] * 0.2f; gy = gy * 0.8f + e.values[1] * 0.2f
                var a = Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()
                a -= displayRotation
                while (a > 180f) a -= 360f; while (a < -180f) a += 360f
                angle = a
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    val level = abs(angle) < 1f || abs(abs(angle) - 90f) < 1f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(140.dp, 40.dp)) {
            val c = if (level) UxColors.Green else Color.White.copy(alpha = 0.8f)
            val cx = size.width / 2; val cy = size.height / 2
            drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - 60f, cy), Offset(cx + 60f, cy), 2f)
            rotate(-angle, pivot = Offset(cx, cy)) { drawLine(c, Offset(cx - 60f, cy), Offset(cx + 60f, cy), 3f) }
        }
        Text("${"%.1f".format(angle)}°", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
