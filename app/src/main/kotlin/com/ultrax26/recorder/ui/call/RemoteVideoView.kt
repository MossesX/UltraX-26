package com.ultrax26.recorder.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ultrax26.recorder.calls.WebRtcCore
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Renders one WebRTC video track (remote, or our own outgoing track for the self tile) with the
 * WebRTC SurfaceViewRenderer. The renderer shares the call's EGL context so decoded textures are
 * displayed without a copy. It is a media-overlay SurfaceView so it stacks above the camera preview.
 */
@Composable
fun VideoTile(core: WebRtcCore, track: VideoTrack?, mirror: Boolean = false, modifier: Modifier = Modifier) {
    val eglContext = core.eglBase.eglBaseContext
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(true)
            }
        },
        update = { view ->
            view.setMirror(mirror)
            val prev = view.tag as? VideoTrack
            if (prev !== track) {
                try { prev?.removeSink(view) } catch (_: Throwable) { }
                try { track?.addSink(view) } catch (_: Throwable) { }
                view.tag = track
            }
        },
        onRelease = { view ->
            try { (view.tag as? VideoTrack)?.removeSink(view) } catch (_: Throwable) { }
            view.tag = null
            try { view.release() } catch (_: Throwable) { }
        },
    )
}
