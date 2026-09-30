package com.ultrax26.recorder.ui.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ultrax26.recorder.recording.RecordingController
import com.ultrax26.recorder.util.UxLog

/** SurfaceView preview bound to the recorder. Buffer size is fixed to a camera-native size for the record aspect. */
@Composable
fun PreviewSurface(controller: RecordingController, aspect: Float, modifier: Modifier = Modifier) {
    val fixedW = 1920
    val fixedH = (1920f / aspect).toInt().let { if (it % 2 == 1) it + 1 else it }.coerceIn(2, 1920 * 4)
    val holderRef = remember { arrayOfNulls<SurfaceView>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                holderRef[0] = sv
                sv.holder.setFixedSize(fixedW, fixedH)
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width == fixedW && height == fixedH) controller.attachPreview(holder.surface, width, height)
                        else UxLog.d("Preview", "ignoring intermediate surface size ${width}x$height")
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { controller.detachPreview() }
                })
                controller.snapshotProvider = { cb ->
                    try {
                        val bmp = Bitmap.createBitmap(fixedW, fixedH, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(sv, bmp, { res -> cb(if (res == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
                    } catch (t: Throwable) { cb(null) }
                }
            }
        },
        update = { sv -> if (sv.holder.surfaceFrame.width() != fixedW) sv.holder.setFixedSize(fixedW, fixedH) },
    )
    DisposableEffect(Unit) { onDispose { controller.snapshotProvider = null } }
}
