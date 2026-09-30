package com.ultrax26.recorder.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import com.ultrax26.recorder.effects.gl.GlUtil
import com.ultrax26.recorder.util.UxLog

/** Rasterizes vector drawables / user PNGs to GL textures (GL thread only). */
class StickerTextures(private val context: Context) {
    class Tex(val id: Int, val aspect: Float)
    private val cache = HashMap<String, Tex>()

    fun get(key: String, drawableName: String?, uri: String?, aspectHint: Float): Tex? {
        cache[key]?.let { return it }
        val bmp = try {
            if (uri != null) decodeUri(Uri.parse(uri)) else if (drawableName != null) rasterize(drawableName, aspectHint) else null
        } catch (t: Throwable) { UxLog.w("Stickers", "load $key failed: ${t.message}"); null } ?: return null
        val tex = Tex(GlUtil.textureFromBitmap(bmp), bmp.width.toFloat() / bmp.height)
        bmp.recycle()
        cache[key] = tex
        return tex
    }

    fun rasterize(drawableName: String, aspectHint: Float, size: Int = 512): Bitmap? {
        val id = context.resources.getIdentifier(drawableName, "drawable", context.packageName)
        if (id == 0) { UxLog.w("Stickers", "missing drawable $drawableName"); return null }
        val d: Drawable = context.getDrawable(id) ?: return null
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 100
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 100
        val aspect = iw.toFloat() / ih
        val w = if (aspect >= 1f) size else (size * aspect).toInt().coerceAtLeast(8)
        val h = if (aspect >= 1f) (size / aspect).toInt().coerceAtLeast(8) else size
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while ((bounds.outWidth / sample) > 1024 || (bounds.outHeight / sample) > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun release() { cache.values.forEach { GlUtil.deleteTexture(it.id) }; cache.clear() }
}
