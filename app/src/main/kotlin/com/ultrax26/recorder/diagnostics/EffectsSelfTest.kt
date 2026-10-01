package com.ultrax26.recorder.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.ultrax26.recorder.effects.ml.EffectsDetectorFactory
import com.ultrax26.recorder.settings.MlDelegate
import com.ultrax26.recorder.triggers.vision.DetectorFactory
import com.ultrax26.recorder.triggers.vision.VisionFrame
import com.ultrax26.recorder.settings.HandGestureConfig

/**
 * Loads every on-device model and runs one inference on a synthetic frame, reporting timings and the
 * exact failure message for anything that does not work. Lets a user tell exactly why costumes or
 * backgrounds do nothing on their phone without a debugger.
 */
object EffectsSelfTest {
    fun run(context: Context, delegate: MlDelegate): String {
        val sb = StringBuilder()
        sb.appendLine("== Effects self-test (delegate ${delegate.name}) ==")
        // 1) model assets present?
        val wanted = listOf("gesture_recognizer.task", "face_landmarker.task", "selfie_multiclass_256x256.tflite", "selfie_segmenter.tflite", "pose_landmarker_lite.task")
        for (name in wanted) {
            val size = try { context.assets.openFd(name).use { it.length } } catch (_: Throwable) { try { context.assets.open(name).use { it.available().toLong() } } catch (t: Throwable) { -1L } }
            sb.appendLine(if (size > 0) "asset $name: ${size / 1024} KB" else "asset $name: MISSING ($size)")
        }
        // 2) the GL compositor itself: can the effects renderer start on this GPU?
        try {
            val t0 = System.nanoTime()
            val r = com.ultrax26.recorder.effects.EffectsRenderer(context) { com.ultrax26.recorder.effects.EffectsSettings() }
            r.start(1280, 720)
            val st = r.stats.value
            sb.appendLine(if (r.inputSurface != null) "GL renderer: OK in ${(System.nanoTime() - t0) / 1_000_000} ms — ${st.glInfo}" else "GL renderer: FAILED — ${st.error} (${st.glInfo})")
            r.stop()
        } catch (t: Throwable) { sb.appendLine("GL renderer: CRASHED — ${t.javaClass.simpleName}: ${t.message}") }
        // 3) a synthetic 480×640 upright-ish frame (gradient + a skin-toned ellipse) so detectors have something to chew on
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 480f, Color.rgb(40, 60, 90), Color.rgb(200, 210, 220), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 640f, 480f, paint)
        c.drawOval(220f, 100f, 420f, 380f, Paint().apply { color = Color.rgb(224, 172, 140) })
        val frame = VisionFrame(bmp, 90, 1000L, mirrored = false)
        fun time(label: String, block: () -> String) {
            val t0 = System.nanoTime()
            try { val r = block(); sb.appendLine("$label: OK in ${(System.nanoTime() - t0) / 1_000_000} ms — $r") }
            catch (t: Throwable) { sb.appendLine("$label: FAILED — ${t.javaClass.simpleName}: ${t.message}") }
        }
        time("face mesh") { val d = EffectsDetectorFactory.faceMesh(context, delegate); val r = try { d.detect(frame) } finally { d.close() }; if (r == null) "model loaded, no face in test image (expected)" else "face found (${r.landmarks.size / 3} points)" }
        time("segmentation (multi-class)") { val d = EffectsDetectorFactory.segmenter(context, true, delegate); val r = try { d.segment(frame) } finally { d.close() }; if (r == null) "no mask returned" else "mask ${r.width}×${r.height}" }
        time("segmentation (single-class)") { val d = EffectsDetectorFactory.segmenter(context, false, delegate); val r = try { d.segment(frame) } finally { d.close() }; if (r == null) "no mask returned" else "mask ${r.width}×${r.height}" }
        time("pose") { val d = EffectsDetectorFactory.pose(context, delegate); val r = try { d.detect(frame) } finally { d.close() }; if (r == null) "model loaded, no body in test image (expected)" else "pose found" }
        time("hand gestures") { val d = DetectorFactory.hand(context, HandGestureConfig(delegate = delegate)); val r = try { d.detect(frame) } finally { d.close() }; "model loaded, ${r.size} hands in test image" }
        bmp.recycle()
        return sb.toString()
    }
}
