package com.ultrax26.recorder.effects.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import com.ultrax26.recorder.util.UxLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun check(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) UxLog.w("GL", "$op: glError 0x${Integer.toHexString(e)}")
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(data); it.position(0) }

    fun createTexture2D(w: Int, h: Int, internalFormat: Int = GLES30.GL_RGBA8, format: Int = GLES20.GL_RGBA, linear: Boolean = true): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, w, h)
        setParams(GLES20.GL_TEXTURE_2D, linear)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        check("createTexture2D"); return ids[0].also { if (format == 0) Unit }
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        setParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, true)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return ids[0]
    }

    fun setParams(target: Int, linear: Boolean) {
        val f = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun uploadBitmap(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        setParams(GLES20.GL_TEXTURE_2D, true)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        check("uploadBitmap")
    }

    fun textureFromBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        uploadBitmap(ids[0], bitmap)
        return ids[0]
    }

    /** Upload/replace RGBA8 bytes (tightly packed). */
    fun uploadRgba(texId: Int, w: Int, h: Int, data: ByteArray, allocated: Boolean) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val buf = ByteBuffer.wrap(data)
        if (!allocated) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        setParams(GLES20.GL_TEXTURE_2D, true)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun deleteTexture(id: Int) { if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
}

/** Compiled + linked program with a uniform location cache. */
class GlProgram(vertexSrc: String, fragmentSrc: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs); GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw RuntimeException("program link failed: $log")
        }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader compile failed (${if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"}): $log\n$src")
        }
        return s
    }

    fun use() { GLES20.glUseProgram(id) }
    fun u(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    fun set1i(name: String, v: Int) { val l = u(name); if (l >= 0) GLES20.glUniform1i(l, v) }
    fun set1f(name: String, v: Float) { val l = u(name); if (l >= 0) GLES20.glUniform1f(l, v) }
    fun set2f(name: String, a: Float, b: Float) { val l = u(name); if (l >= 0) GLES20.glUniform2f(l, a, b) }
    fun set3f(name: String, a: Float, b: Float, c: Float) { val l = u(name); if (l >= 0) GLES20.glUniform3f(l, a, b, c) }
    fun set4f(name: String, a: Float, b: Float, c: Float, d: Float) { val l = u(name); if (l >= 0) GLES20.glUniform4f(l, a, b, c, d) }
    fun setMat4(name: String, m: FloatArray) { val l = u(name); if (l >= 0) GLES20.glUniformMatrix4fv(l, 1, false, m, 0) }
    fun set2fv(name: String, v: FloatArray) { val l = u(name); if (l >= 0) GLES20.glUniform2fv(l, v.size / 2, v, 0) }
    fun set4fv(name: String, v: FloatArray) { val l = u(name); if (l >= 0) GLES20.glUniform4fv(l, v.size / 4, v, 0) }
    fun set1fv(name: String, v: FloatArray) { val l = u(name); if (l >= 0) GLES20.glUniform1fv(l, v.size, v, 0) }
    fun texture(name: String, unit: Int, texId: Int, target: Int = GLES20.GL_TEXTURE_2D) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, texId)
        set1i(name, unit)
    }
    fun release() { GLES20.glDeleteProgram(id) }
}

/** Offscreen render target. */
class Fbo(val width: Int, val height: Int) {
    val texture: Int = GlUtil.createTexture2D(width, height)
    val id: Int
    init {
        val ids = IntArray(1); GLES20.glGenFramebuffers(1, ids, 0); id = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("FBO incomplete: $status")
    }
    fun bind() { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id); GLES20.glViewport(0, 0, width, height) }
    fun release() { GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0); GlUtil.deleteTexture(texture) }
    companion object { fun unbind() { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) } }
}

/**
 * Draws quads. Scene convention: texcoords (0,0) = top-left of the image (bitmap convention);
 * the vertex shader receives positions in scene-normalized space [0,1]² (y down) and flips to clip.
 */
class Quad {
    private val posBuf: FloatBuffer = GlUtil.floatBuffer(FloatArray(8))
    private val texBuf: FloatBuffer = GlUtil.floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
    private val fullPos = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    private val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
    private val idxBuf = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asShortBuffer().also { it.put(indices); it.position(0) }

    /** Full-screen quad; `flipTex` flips the texture vertically (camera FBO vs bitmap convention). */
    fun drawFull(program: GlProgram, flipTex: Boolean = false, flipPos: Boolean = false) {
        val tex = if (flipTex) floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f) else floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val pos = if (flipPos) floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f) else fullPos
        draw(program, pos, tex)
    }

    /** Arbitrary quad; positions in scene-normalized coordinates (x/aspect, y), 4 corners TL, TR, BR, BL. */
    fun draw(program: GlProgram, positions: FloatArray, texcoords: FloatArray) {
        posBuf.position(0); posBuf.put(positions); posBuf.position(0)
        texBuf.position(0); texBuf.put(texcoords); texBuf.position(0)
        val aPos = GLES20.glGetAttribLocation(program.id, "aPos")
        val aTex = GLES20.glGetAttribLocation(program.id, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        if (aTex >= 0) { GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf) }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, idxBuf)
        GLES20.glDisableVertexAttribArray(aPos)
        if (aTex >= 0) GLES20.glDisableVertexAttribArray(aTex)
    }

    /** Triangle fan polygon (convex-ish), positions as x,y pairs in scene-normalized coords. */
    fun drawPolygon(program: GlProgram, points: FloatArray) {
        val n = points.size / 2
        if (n < 3) return
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += points[i * 2]; cy += points[i * 2 + 1] }
        cx /= n; cy /= n
        val verts = FloatArray((n + 2) * 2)
        verts[0] = cx; verts[1] = cy
        for (i in 0..n) { val k = i % n; verts[(i + 1) * 2] = points[k * 2]; verts[(i + 1) * 2 + 1] = points[k * 2 + 1] }
        val buf = GlUtil.floatBuffer(verts)
        val aPos = GLES20.glGetAttribLocation(program.id, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n + 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
