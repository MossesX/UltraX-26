package com.ultrax26.recorder.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.ultrax26.recorder.effects.gl.GlUtil
import com.ultrax26.recorder.util.UxLog

/** Static photo background as a GL texture (cover-fit is handled by the renderer). */
class ImageBackground(context: Context, uri: String) {
    var texture = 0; var aspect = 1f
    init {
        try {
            val u = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while ((bounds.outWidth / sample) > 2048 || (bounds.outHeight / sample) > 2048) sample *= 2
            val bmp = context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            if (bmp != null) { texture = GlUtil.textureFromBitmap(bmp); aspect = bmp.width.toFloat() / bmp.height; bmp.recycle() }
        } catch (t: Throwable) { UxLog.w("Background", "image load failed: ${t.message}") }
    }
    fun release() { GlUtil.deleteTexture(texture); texture = 0 }
}

/** Looping muted video background decoded into an external texture (GL thread owns updateTexImage). */
class VideoBackground(context: Context, uri: String, private val onFrame: () -> Unit) {
    val oesTexture: Int = GlUtil.createOesTexture()
    private val surfaceTexture = SurfaceTexture(oesTexture)
    private val surface = Surface(surfaceTexture)
    private var player: MediaPlayer? = null
    @Volatile private var pending = false
    val stMatrix = FloatArray(16)
    var aspect = 16f / 9f
        private set
    var ready = false
        private set

    init {
        surfaceTexture.setOnFrameAvailableListener { pending = true; onFrame() }
        try {
            val p = MediaPlayer()
            p.setDataSource(context, Uri.parse(uri))
            p.setSurface(surface)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
            p.setOnPreparedListener { it.start(); ready = true }
            p.setOnErrorListener { _, what, extra -> UxLog.w("Background", "video error $what/$extra"); true }
            p.prepareAsync()
            player = p
        } catch (t: Throwable) { UxLog.w("Background", "video failed: ${t.message}") }
    }

    /** Call on the GL thread; returns true if a new frame was latched. */
    fun update(): Boolean {
        if (!pending) return false
        pending = false
        try { surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(stMatrix) } catch (_: Throwable) { return false }
        return true
    }

    fun release() {
        try { player?.stop(); player?.release() } catch (_: Throwable) { }
        player = null
        surface.release(); surfaceTexture.release()
        GlUtil.deleteTexture(oesTexture)
    }
}

/** Layered parallax scene: each layer is a rasterized drawable with a depth factor. */
class ParallaxBackground(context: Context, scene: ParallaxScene, textures: StickerTextures) {
    class Layer(val tex: StickerTextures.Tex, val depth: Float, val scale: Float)
    val layers: List<Layer> = scene.layers.mapNotNull { l -> textures.get("bg:${l.drawable}", l.drawable, null, 16f / 9f)?.let { Layer(it, l.depth, l.scale) } }
}
