package com.ultrax26.recorder.effects.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import com.ultrax26.recorder.util.UxLog

/** EGL 1.4 display/context with a recordable RGBA8 config (works for SurfaceView and MediaCodec surfaces). */
class EglCore {
    private val tag = "Egl"
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) { display = EGL14.EGL_NO_DISPLAY; throw RuntimeException("eglInitialize failed") }
        config = chooseConfig(3) ?: chooseConfig(2) ?: throw RuntimeException("no EGL config")
        val attrs3 = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        var ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrs3, 0)
        if (EGL14.eglGetError() != EGL14.EGL_SUCCESS || ctx == EGL14.EGL_NO_CONTEXT) {
            UxLog.w(tag, "GLES3 context unavailable, trying GLES2")
            ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        }
        context = ctx
        val values = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0)
        UxLog.i(tag, "EGL context client version ${values[0]}")
    }

    private fun chooseConfig(glVersion: Int): EGLConfig? {
        val renderable = if (glVersion >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
        return configs[0]
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        checkError("eglCreateWindowSurface")
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw RuntimeException("surface was null")
        return s
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        checkError("eglCreatePbufferSurface")
        return s ?: throw RuntimeException("pbuffer was null")
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw RuntimeException("eglMakeCurrent failed")
    }

    fun makeNothingCurrent() { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) { EGLExt.eglPresentationTimeANDROID(display, surface, nanos) }

    fun releaseSurface(surface: EGLSurface) { EGL14.eglDestroySurface(display, surface) }

    fun querySurface(surface: EGLSurface, what: Int): Int { val v = IntArray(1); EGL14.eglQuerySurface(display, surface, what, v, 0); return v[0] }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; config = null
    }

    private fun checkError(msg: String) {
        val e = EGL14.eglGetError()
        if (e != EGL14.EGL_SUCCESS) throw RuntimeException("$msg: EGL error 0x${Integer.toHexString(e)}")
    }

    companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
