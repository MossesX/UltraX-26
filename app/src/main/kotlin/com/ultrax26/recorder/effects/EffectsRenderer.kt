package com.ultrax26.recorder.effects

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Process
import android.view.Surface
import com.ultrax26.recorder.effects.gl.EglCore
import com.ultrax26.recorder.effects.gl.Fbo
import com.ultrax26.recorder.effects.gl.GlProgram
import com.ultrax26.recorder.effects.gl.GlUtil
import com.ultrax26.recorder.effects.gl.Quad
import com.ultrax26.recorder.effects.gl.Shaders
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import com.ultrax26.recorder.util.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import kotlin.math.cos
import kotlin.math.sin

data class RendererStats(val fps: Float = 0f, val frameMs: Float = 0f, val faceTracked: Boolean = false, val segTracked: Boolean = false, val error: String? = null)

/**
 * OpenGL ES compositor between the camera and the preview/encoder surfaces.
 *
 *  camera → SurfaceTexture → [background composite] → [face pass] → [style pass] → [stickers] → preview + encoder
 *
 * Everything runs on one GL thread; detector results arrive asynchronously and are smoothed here.
 */
class EffectsRenderer(private val context: Context, private val settingsProvider: () -> EffectsSettings) : com.ultrax26.recorder.triggers.vision.FrameDispatcher.EffectsSink {
    private val tag = "FxRenderer"
    private val thread = WorkerThread("ux-gl", Process.THREAD_PRIORITY_DISPLAY)
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var previewEgl: EGLSurface? = null
    private var encoderEgl: EGLSurface? = null
    private var callEgl: EGLSurface? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var camTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurfaceInternal: Surface? = null
    private var width = 0; private var height = 0
    private var quad: Quad? = null
    private var progExt: GlProgram? = null; private var progCopy: GlProgram? = null; private var progBlur: GlProgram? = null
    private var progComposite: GlProgram? = null; private var progProcedural: GlProgram? = null; private var progFace: GlProgram? = null
    private var progStyle: GlProgram? = null; private var progTexture: GlProgram? = null; private var progSolid: GlProgram? = null; private var progDebug: GlProgram? = null
    private var fboFrame: Fbo? = null; private var fboBg: Fbo? = null; private var fboA: Fbo? = null; private var fboB: Fbo? = null
    private var fboBlurH: Fbo? = null; private var fboBlur: Fbo? = null; private var fboBlurSmallH: Fbo? = null; private var fboBlurSmall: Fbo? = null
    private var fboMask: Fbo? = null; private var fboMaskBlur: Fbo? = null
    private var segTex = 0; private var segAllocated = false; private var segW = 0; private var segH = 0
    private val stMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var stickers: StickerTextures? = null
    private var imageBg: ImageBackground? = null; private var imageBgKey: String? = null
    private var videoBg: VideoBackground? = null; private var videoBgKey: String? = null
    private var parallax: ParallaxBackground? = null; private var parallaxKey: String? = null
    private var startNs = System.nanoTime()
    private var frames = 0; private var lastStatsMs = 0L; private var frameCost = 0f

    // async inputs
    @Volatile private var mesh: FaceMeshResult? = null
    @Volatile private var meshSeenMs = 0L
    @Volatile private var seg: SegmentationMasks? = null
    @Volatile private var segDirty = false
    @Volatile private var pose: PoseResult? = null
    @Volatile private var poseSeenMs = 0L
    @Volatile private var mapping = SceneMapping(90, 16f / 9f, 16f / 9f)
    @Volatile private var frontCamera = false
    @Volatile private var mirrorPreview = false
    @Volatile private var mirrorRecording = false
    @Volatile private var recording = false
    @Volatile private var settings: EffectsSettings = settingsProvider()
    private val smoother = FaceGeometry.Smoother()
    private var smoothedMesh: FaceMeshResult? = null
    private var faceFrame: FaceFrame? = null
    private var poseFrame: PoseFrame? = null
    private var headOffsetX = 0f; private var headOffsetY = 0f

    /** (sensor timestamp ns, CLOCK_BOOTTIME ns) for every rendered frame. */
    @Volatile var onFrameTimestamp: ((Long, Long) -> Unit)? = null
    val stats = MutableStateFlow(RendererStats())

    val inputSurface: Surface? get() = inputSurfaceInternal
    val sceneWidth: Int get() = width
    val sceneHeight: Int get() = height

    // ------------------------------------------------------------------------------------------

    /** Create the GL context and the camera input surface (blocks until ready). */
    fun start(sceneWidth: Int, sceneHeight: Int) {
        val latch = CountDownLatch(1)
        thread.post {
            try {
                width = sceneWidth; height = sceneHeight
                val core = EglCore(); egl = core
                val pb = core.createOffscreenSurface(1, 1); pbuffer = pb
                core.makeCurrent(pb)
                setupGl()
                camTex = GlUtil.createOesTexture()
                val st = SurfaceTexture(camTex)
                st.setDefaultBufferSize(width, height)
                st.setOnFrameAvailableListener({ thread.post { onFrame() } }, thread.handler)
                surfaceTexture = st
                inputSurfaceInternal = Surface(st)
                startNs = System.nanoTime()
                UxLog.i(tag, "renderer started ${width}x$height")
            } catch (t: Throwable) {
                UxLog.e(tag, "start failed", t)
                stats.value = stats.value.copy(error = "GL init failed: ${t.message}")
            } finally { latch.countDown() }
        }
        latch.await()
    }

    fun stop() {
        val latch = CountDownLatch(1)
        thread.post {
            try {
                releaseOutputs()
                releaseGl()
                inputSurfaceInternal?.release(); inputSurfaceInternal = null
                surfaceTexture?.release(); surfaceTexture = null
                egl?.let { e -> pbuffer?.let { s -> e.releaseSurface(s) }; e.release() }
                egl = null; pbuffer = null
            } catch (t: Throwable) { UxLog.w(tag, "stop: ${t.message}") } finally { latch.countDown() }
        }
        latch.await()
        thread.quit()
    }

    fun setPreviewSurface(surface: Surface?) = thread.post {
        previewEgl?.let { egl?.releaseSurface(it) }; previewEgl = null
        previewSurface = surface
        if (surface != null) try { previewEgl = egl?.createWindowSurface(surface) } catch (t: Throwable) { UxLog.w(tag, "preview surface: ${t.message}") }
    }

    fun setEncoderSurface(surface: Surface?) = thread.post {
        encoderEgl?.let { egl?.releaseSurface(it) }; encoderEgl = null
        encoderSurface = surface
        if (surface != null) try { encoderEgl = egl?.createWindowSurface(surface) } catch (t: Throwable) { UxLog.w(tag, "encoder surface: ${t.message}") }
    }

    /** Third output: a WebRTC capture surface (video calls). Always drawn while set. */
    fun setCallSurface(surface: Surface?) = thread.post {
        callEgl?.let { egl?.releaseSurface(it) }; callEgl = null
        if (surface != null) try { callEgl = egl?.createWindowSurface(surface) } catch (t: Throwable) { UxLog.w(tag, "call surface: ${t.message}") }
    }

    fun setRecording(active: Boolean) { recording = active }
    /** When true the call output carries the plain camera image (effects stay on the recording only). */
    @Volatile var callRawCamera = false
    override fun setMapping(rotationDegrees: Int, frameAspect: Float) { if (width > 0 && height > 0) mapping = SceneMapping(rotationDegrees, frameAspect, width.toFloat() / height) }
    fun setCameraFacing(front: Boolean, mirrorPreview: Boolean, mirrorRecording: Boolean) { frontCamera = front; this.mirrorPreview = mirrorPreview; this.mirrorRecording = mirrorRecording }
    fun updateSettings(s: EffectsSettings) { settings = s }
    override fun onFaceMesh(r: FaceMeshResult?) { mesh = r; if (r != null) meshSeenMs = Clock.bootMs() }
    override fun onSegmentation(m: SegmentationMasks?) { if (m != null) { seg = m; segDirty = true } }
    override fun onPose(p: PoseResult?) { pose = p; if (p != null) poseSeenMs = Clock.bootMs() }

    // ------------------------------------------------------------------------------------------

    private fun setupGl() {
        quad = Quad()
        progExt = GlProgram(Shaders.VERTEX, Shaders.FRAG_EXTERNAL)
        progCopy = GlProgram(Shaders.VERTEX, Shaders.FRAG_COPY)
        progBlur = GlProgram(Shaders.VERTEX, Shaders.FRAG_BLUR)
        progComposite = GlProgram(Shaders.VERTEX, Shaders.FRAG_COMPOSITE)
        progProcedural = GlProgram(Shaders.VERTEX, Shaders.FRAG_PROCEDURAL)
        progFace = GlProgram(Shaders.VERTEX, Shaders.FRAG_FACE)
        progStyle = GlProgram(Shaders.VERTEX, Shaders.FRAG_STYLE)
        progTexture = GlProgram(Shaders.VERTEX, Shaders.FRAG_TEXTURE)
        progSolid = GlProgram(Shaders.VERTEX_SOLID, Shaders.FRAG_SOLID)
        progDebug = GlProgram(Shaders.VERTEX, Shaders.FRAG_DEBUG_MASK)
        fboFrame = Fbo(width, height); fboBg = Fbo(width, height); fboA = Fbo(width, height); fboB = Fbo(width, height)
        val bw = (width / 2).coerceAtLeast(64); val bh = (height / 2).coerceAtLeast(64)
        fboBlurH = Fbo(bw, bh); fboBlur = Fbo(bw, bh)
        val sw = (width / 8).coerceAtLeast(32); val sh = (height / 8).coerceAtLeast(32)
        fboBlurSmallH = Fbo(sw, sh); fboBlurSmall = Fbo(sw, sh)
        fboMask = Fbo((width / 4).coerceAtLeast(64), (height / 4).coerceAtLeast(64)); fboMaskBlur = Fbo((width / 4).coerceAtLeast(64), (height / 4).coerceAtLeast(64))
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); segTex = ids[0]; segAllocated = false
        stickers = StickerTextures(context)
    }

    private fun releaseGl() {
        listOf(progExt, progCopy, progBlur, progComposite, progProcedural, progFace, progStyle, progTexture, progSolid, progDebug).forEach { it?.release() }
        listOf(fboFrame, fboBg, fboA, fboB, fboBlurH, fboBlur, fboBlurSmallH, fboBlurSmall, fboMask, fboMaskBlur).forEach { it?.release() }
        GlUtil.deleteTexture(segTex); GlUtil.deleteTexture(camTex)
        stickers?.release(); imageBg?.release(); videoBg?.release()
        stickers = null; imageBg = null; videoBg = null; parallax = null
    }

    private fun releaseOutputs() {
        previewEgl?.let { egl?.releaseSurface(it) }; previewEgl = null
        encoderEgl?.let { egl?.releaseSurface(it) }; encoderEgl = null
        callEgl?.let { egl?.releaseSurface(it) }; callEgl = null
    }

    private fun setVertexDefaults(p: GlProgram, flipY: Boolean = false, mirror: Boolean = false, st: FloatArray = identity) {
        p.use(); p.set1f("uFlipY", if (flipY) 1f else 0f); p.set1f("uMirrorX", if (mirror) 1f else 0f); p.setMat4("uStMatrix", st)
    }

    // ------------------------------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------------------------------

    private fun onFrame() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        val pb = pbuffer ?: return
        val t0 = System.nanoTime()
        try {
            core.makeCurrent(pb)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val ts = st.timestamp
            onFrameTimestamp?.invoke(ts, Clock.bootNs())
            val s = settings
            val time = (System.nanoTime() - startNs) / 1e9f
            updateTracking()
            renderScene(s, time)
            // outputs
            val final = fboB!!.texture
            previewEgl?.let { out ->
                core.makeCurrent(out)
                GLES20.glViewport(0, 0, core.querySurface(out, EGL14.EGL_WIDTH), core.querySurface(out, EGL14.EGL_HEIGHT))
                drawOutput(final, frontCamera && mirrorPreview)
                if (s.debugMesh) drawDebug()
                core.swapBuffers(out)
            }
            if (recording) encoderEgl?.let { out ->
                core.makeCurrent(out)
                GLES20.glViewport(0, 0, core.querySurface(out, EGL14.EGL_WIDTH), core.querySurface(out, EGL14.EGL_HEIGHT))
                drawOutput(final, frontCamera && mirrorRecording)
                core.setPresentationTime(out, ts)
                core.swapBuffers(out)
            }
            callEgl?.let { out ->
                core.makeCurrent(out)
                GLES20.glViewport(0, 0, core.querySurface(out, EGL14.EGL_WIDTH), core.querySurface(out, EGL14.EGL_HEIGHT))
                drawOutput(if (callRawCamera) fboFrame!!.texture else final, false)
                core.swapBuffers(out)
            }
        } catch (t: Throwable) {
            UxLog.w(tag, "frame failed: ${t.message}")
            stats.value = stats.value.copy(error = t.message)
        }
        frames++
        frameCost = frameCost * 0.9f + ((System.nanoTime() - t0) / 1e6f) * 0.1f
        val now = Clock.bootMs()
        if (now - lastStatsMs > 1000) {
            stats.value = RendererStats(fps = frames * 1000f / (now - lastStatsMs).coerceAtLeast(1), frameMs = frameCost, faceTracked = faceFrame != null, segTracked = seg != null, error = null)
            frames = 0; lastStatsMs = now
        }
    }

    private fun updateTracking() {
        val m = mesh
        val now = Clock.bootMs()
        val s = settings
        if (m != null && now - meshSeenMs < 600) {
            val smoothed = smoother.apply(m.landmarks)
            val sm = FaceMeshResult(smoothed, m.matrix, m.blendshapes, m.timestampMs)
            smoothedMesh = sm
            faceFrame = FaceGeometry.frame(sm, mapping, s.invertYaw, s.invertPitch)
            val c = faceFrame!!.center
            headOffsetX = (c.x / mapping.sceneAspect - 0.5f); headOffsetY = c.y - 0.5f
        } else { faceFrame = null; smoothedMesh = null; smoother.reset() }
        val p = pose
        poseFrame = if (p != null && now - poseSeenMs < 800) FaceGeometry.poseFrame(p, mapping) else null
        if (segDirty) {
            val sg = seg
            if (sg != null) {
                if (!segAllocated || sg.width != segW || sg.height != segH) { GlUtil.uploadRgba(segTex, sg.width, sg.height, sg.rgba, false); segAllocated = true; segW = sg.width; segH = sg.height }
                else GlUtil.uploadRgba(segTex, sg.width, sg.height, sg.rgba, true)
            }
            segDirty = false
        }
    }

    private fun renderScene(s: EffectsSettings, time: Float) {
        val q = quad!!
        // 1) camera → fboFrame (scene orientation, bitmap convention)
        fboFrame!!.bind()
        setVertexDefaults(progExt!!, st = stMatrix)
        progExt!!.texture("uTex", 0, camTex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        q.drawFull(progExt!!, flipTex = !s.flipCameraY)
        // 2) blur pyramid (used by beauty / painterly / blur background)
        val needsBlur = s.beauty.any() || s.age != AgeMode.NONE || s.funMode == FunMode.OIL_PAINT || s.background.type == BackgroundType.BLUR
        if (needsBlur) blurChain(fboFrame!!.texture, fboBlurH!!, fboBlur!!, 1.0f)
        // 3) background
        val hasSeg = seg != null && segAllocated
        val useBg = s.background.type != BackgroundType.NONE && hasSeg || s.stickers.any { it.behindPerson } && hasSeg
        if (useBg) {
            renderBackground(s, time)
            drawStickers(s, time, behind = true, target = fboBg!!)
            fboA!!.bind()
            val p = progComposite!!
            setVertexDefaults(p)
            p.texture("uFrame", 0, fboFrame!!.texture); p.texture("uBackground", 1, fboBg!!.texture); p.texture("uMask", 2, segTex)
            p.set1i("uRot", rotCode()); p.set2f("uFit", fitX(), fitY()); p.set1f("uFlipMask", if (s.flipMaskY) 1f else 0f)
            p.set1f("uFeather", s.background.feather); p.set1f("uEdgeShift", s.background.edgeShift); p.set1f("uLightWrap", s.background.lightWrap)
            p.set1f("uHasMask", 1f); p.set2f("uMaskTexel", 1f / segW.coerceAtLeast(1), 1f / segH.coerceAtLeast(1))
            q.drawFull(p)
        } else {
            // plain copy so the face pass always reads fboA
            fboA!!.bind(); setVertexDefaults(progCopy!!); progCopy!!.set1f("uOpacity", 1f); progCopy!!.texture("uTex", 0, fboFrame!!.texture); q.drawFull(progCopy!!)
        }
        // 4) face masks (polygons) at quarter res
        val face = faceFrame
        renderFaceMasks(face)
        // 5) face pass → fboB
        fboB!!.bind()
        facePass(s, face, time)
        // 6) style pass → fboA (reuse)
        fboA!!.bind()
        stylePass(s, face, time)
        // 7) front stickers on fboA, then copy to fboB as final
        drawStickers(s, time, behind = false, target = fboA!!)
        fboB!!.bind(); setVertexDefaults(progCopy!!); progCopy!!.set1f("uOpacity", 1f); progCopy!!.texture("uTex", 0, fboA!!.texture); q.drawFull(progCopy!!)
        Fbo.unbind()
    }

    private fun rotCode(): Int = when (((mapping.rotationDegrees % 360) + 360) % 360) { 90 -> 1; 180 -> 2; 270 -> 3; else -> 0 }
    private fun fitX(): Float { val f = mapping.frameAspect; val u = mapping.uprightAspect; return if (f > u + 0.01f) u / f else 1f }
    private fun fitY(): Float { val f = mapping.frameAspect; val u = mapping.uprightAspect; return if (f < u - 0.01f) f / u else 1f }

    private fun blurChain(src: Int, h: Fbo, v: Fbo, radius: Float) {
        val q = quad!!; val p = progBlur!!
        h.bind(); setVertexDefaults(p); p.texture("uTex", 0, src); p.set2f("uDir", radius / h.width, 0f); q.drawFull(p)
        v.bind(); setVertexDefaults(p); p.texture("uTex", 0, h.texture); p.set2f("uDir", 0f, radius / v.height); q.drawFull(p)
    }

    private fun renderBackground(s: EffectsSettings, time: Float) {
        val q = quad!!; val bg = s.background
        fboBg!!.bind()
        when (bg.type) {
            BackgroundType.BLUR -> {
                // strong blur: small pyramid, several passes
                blurChain(fboFrame!!.texture, fboBlurSmallH!!, fboBlurSmall!!, 1.5f)
                val passes = (1 + (bg.blur * 4)).toInt()
                repeat(passes) { blurChain(fboBlurSmall!!.texture, fboBlurSmallH!!, fboBlurSmall!!, 1.5f) }
                fboBg!!.bind(); setVertexDefaults(progCopy!!); progCopy!!.set1f("uOpacity", 1f); progCopy!!.texture("uTex", 0, fboBlurSmall!!.texture); q.drawFull(progCopy!!)
            }
            BackgroundType.COLOR -> {
                val c = bg.color
                GLES20.glClearColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            BackgroundType.PROCEDURAL -> {
                val mode = EffectCatalog.proceduralBackgrounds.firstOrNull { it.id == bg.id }?.shaderMode ?: 0
                val p = progProcedural!!
                setVertexDefaults(p)
                p.set1f("uTime", if (bg.animate) time else 0f); p.set1i("uMode", mode); p.set1f("uAspect", mapping.sceneAspect); p.set1i("uRot", rotCode())
                val px = if (s.headParallax) -headOffsetX * bg.parallaxStrength else 0f
                val py = if (s.headParallax) -headOffsetY * bg.parallaxStrength else 0f
                p.set2f("uParallax", px, py)
                q.drawFull(p)
            }
            BackgroundType.IMAGE -> {
                val key = bg.uri ?: ""
                if (imageBgKey != key) { imageBg?.release(); imageBg = bg.uri?.let { ImageBackground(context, it) }; imageBgKey = key }
                val img = imageBg
                if (img != null && img.texture != 0) drawCover(img.texture, img.aspect, 0.03f * bg.parallaxStrength, false, null) else clearDark()
            }
            BackgroundType.VIDEO -> {
                val key = bg.uri ?: ""
                if (videoBgKey != key) { videoBg?.release(); videoBg = bg.uri?.let { VideoBackground(context, it) { } }; videoBgKey = key }
                val v = videoBg
                if (v != null) { v.update(); if (v.ready) drawCover(v.oesTexture, v.aspect, 0.03f * bg.parallaxStrength, true, v.stMatrix) else clearDark() } else clearDark()
            }
            BackgroundType.PARALLAX -> {
                val key = bg.id ?: ""
                if (parallaxKey != key) { parallax = EffectCatalog.parallaxScenes.firstOrNull { it.id == key }?.let { ParallaxBackground(context, it, stickers!!) }; parallaxKey = key }
                val px = parallax
                if (px == null || px.layers.isEmpty()) clearDark() else {
                    clearDark()
                    GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    for (l in px.layers) {
                        val amount = if (s.headParallax) 0.08f * bg.parallaxStrength * l.depth else 0f
                        drawCover(l.tex.id, l.tex.aspect, amount, false, null, l.scale)
                    }
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
            }
            BackgroundType.NONE -> { setVertexDefaults(progCopy!!); progCopy!!.set1f("uOpacity", 1f); progCopy!!.texture("uTex", 0, fboFrame!!.texture); q.drawFull(progCopy!!) }
        }
    }

    private fun clearDark() { GLES20.glClearColor(0.05f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) }

    /**
     * Draw an upright image as a cover-fit background in the (possibly rotated) scene, with head parallax.
     * Background images are upright; the scene is in sensor orientation, so the quad is rotated by -rotation.
     */
    private fun drawCover(tex: Int, imgAspect: Float, parallaxAmount: Float, external: Boolean, st: FloatArray?, scale: Float = 1.06f) {
        val q = quad!!
        val p = if (external) progExt!! else progTexture!!
        setVertexDefaults(p, st = st ?: identity)
        if (!external) { p.texture("uTex", 0, tex); p.set1f("uOpacity", 1f) } else p.texture("uTex", 0, tex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val A = mapping.sceneAspect
        val up = mapping.uprightAspect
        // size of the image in upright scene units (height units) so it covers the upright scene
        var w = up * scale; var h = scale
        if (imgAspect > up) w = h * imgAspect else h = w / imgAspect
        val ox = headOffsetX * parallaxAmount; val oy = headOffsetY * parallaxAmount
        // upright corners (metric, centered)
        val ux = floatArrayOf(-w / 2 + ox, w / 2 + ox, w / 2 + ox, -w / 2 + ox)
        val uy = floatArrayOf(-h / 2 + oy, -h / 2 + oy, h / 2 + oy, h / 2 + oy)
        val pos = FloatArray(8)
        for (i in 0 until 4) {
            // upright metric → upright normalized → scene normalized
            val un = (ux[i] / up) + 0.5f; val vn = uy[i] + 0.5f
            val pt = mapping.rotateOnly(un, vn)
            pos[i * 2] = pt.x; pos[i * 2 + 1] = pt.y
        }
        val tc = if (external) floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f) else floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        q.draw(p, pos, tc)
        if (A <= 0f) Unit
    }

    private fun renderFaceMasks(face: FaceFrame?) {
        val m = fboMask!!; val q = quad!!; val p = progSolid!!
        m.bind()
        GLES20.glClearColor(0f, 0f, 0f, 0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (face != null) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            p.use(); p.set1f("uFlipY", 0f)
            fun poly(points: FloatArray, r: Float, g: Float, b: Float, a: Float) {
                val n = FloatArray(points.size)
                for (i in 0 until points.size / 2) { n[i * 2] = points[i * 2] / mapping.sceneAspect; n[i * 2 + 1] = points[i * 2 + 1] }
                p.set4f("uColor", r, g, b, a); q.drawPolygon(p, n)
            }
            poly(face.faceOval, 1f, 0f, 0f, 0f)
            poly(face.lipsOuter, 0f, 1f, 0f, 0f)
            poly(face.lipsInner, 0f, 0f, 0f, 1f)
            poly(face.leftEyePoly, 0f, 0f, 1f, 0f)
            poly(face.rightEyePoly, 0f, 0f, 1f, 0f)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        blurChain(m.texture, fboMaskBlur!!, fboMask!!, 1.2f)
    }

    private fun facePass(s: EffectsSettings, face: FaceFrame?, time: Float) {
        val p = progFace!!; val q = quad!!
        setVertexDefaults(p)
        p.texture("uFrame", 0, fboA!!.texture); p.texture("uBlur", 1, fboBlur!!.texture); p.texture("uFaceMask", 2, fboMask!!.texture)
        p.texture("uSegMask", 3, segTex); p.texture("uBackground", 4, fboBg!!.texture)
        p.set1i("uRot", rotCode()); p.set2f("uFit", fitX(), fitY()); p.set1f("uFlipMask", if (s.flipMaskY) 1f else 0f)
        p.set1f("uHasSeg", if (seg != null && segAllocated) 1f else 0f); p.set1f("uHasFace", if (face != null) 1f else 0f)
        p.set1f("uAspect", mapping.sceneAspect); p.set1f("uTime", time)
        val b = s.beauty
        val ageK = s.ageIntensity
        var smooth = b.smoothing; var bright = b.brightening
        if (s.age == AgeMode.YOUNGER) { smooth = maxOf(smooth, 0.5f * ageK); bright = maxOf(bright, 0.15f * ageK) }
        p.set1f("uSmooth", smooth); p.set1f("uBright", bright); p.set1f("uTeeth", b.teethWhitening); p.set1f("uLipstick", b.lipstick)
        p.set1f("uBlush", b.blush); p.set1f("uEyeBright", b.eyeBrighten); p.set1f("uSharpen", b.sharpen); p.set1f("uGlow", b.glow)
        p.set3f("uLipColor", ((b.lipColor shr 16) and 0xFF) / 255f, ((b.lipColor shr 8) and 0xFF) / 255f, (b.lipColor and 0xFF) / 255f)
        p.set3f("uBlushColor", ((b.blushColor shr 16) and 0xFF) / 255f, ((b.blushColor shr 8) and 0xFF) / 255f, (b.blushColor and 0xFF) / 255f)
        p.set1i("uFaceMode", s.faceMode.ordinal); p.set1f("uFaceIntensity", s.faceModeIntensity)
        p.set1i("uAge", s.age.ordinal); p.set1f("uAgeIntensity", ageK)
        val A = mapping.sceneAspect
        if (face != null) {
            p.set4f("uCheeks", face.leftCheek.x / A, face.leftCheek.y, face.rightCheek.x / A, face.rightCheek.y); p.set1f("uCheekRadius", face.width * 0.22f)
            p.set2f("uFaceCenter", face.center.x / A, face.center.y)
            val cr = cos(face.roll); val sr = sin(face.roll)
            p.set2f("uFaceAxisX", cr / (face.width / 2f), sr / (face.width / 2f))
            p.set2f("uFaceAxisY", -sr / (face.height / 2f), cr / (face.height / 2f))
            p.set2f("uLeftEye", face.leftEye.x / A, face.leftEye.y); p.set2f("uRightEye", face.rightEye.x / A, face.rightEye.y)
            p.set2f("uNose", face.nose.x / A, face.nose.y); p.set2f("uMouth", face.mouth.x / A, face.mouth.y)
            // warps
            val warps = ArrayList<FloatArray>(); val dirs = ArrayList<FloatArray>()
            fun add(c: P2, radius: Float, strength: Float, type: Int, dx: Float = 0f, dy: Float = 0f) { warps += floatArrayOf(c.x / A, c.y, radius, strength); dirs += floatArrayOf(dx, dy, type.toFloat(), 0f) }
            var eye = b.eyeEnlarge; var slim = b.faceSlim; var nose = b.noseSlim; var chin = b.chinShorten; var head = 0f
            when (s.age) {
                AgeMode.YOUNGER -> eye = maxOf(eye, 0.12f * ageK)
                AgeMode.BABY -> { eye = maxOf(eye, 0.32f * ageK); head = 0.28f * ageK; nose = maxOf(nose, 0.35f * ageK); chin = maxOf(chin, 0.3f * ageK) }
                AgeMode.MUCH_OLDER -> { add(face.leftCheek, face.width * 0.35f, 0.18f * ageK, 1, -sin(face.roll), cos(face.roll)); add(face.rightCheek, face.width * 0.35f, 0.18f * ageK, 1, -sin(face.roll), cos(face.roll)) }
                else -> { }
            }
            when (s.funMode) {
                FunMode.BIG_HEAD -> head = 0.25f + 0.45f * s.funIntensity
                FunMode.TINY_HEAD -> head = -(0.25f + 0.4f * s.funIntensity)
                FunMode.LONG_FACE -> { add(face.forehead, face.height * 0.8f, 0.35f * s.funIntensity, 1, sin(face.roll), -cos(face.roll)); add(face.chin, face.height * 0.8f, 0.35f * s.funIntensity, 1, -sin(face.roll), cos(face.roll)) }
                FunMode.WIDE_FACE -> { add(face.leftCheek, face.height * 0.7f, 0.35f * s.funIntensity, 1, -cos(face.roll), -sin(face.roll)); add(face.rightCheek, face.height * 0.7f, 0.35f * s.funIntensity, 1, cos(face.roll), sin(face.roll)) }
                else -> { }
            }
            if (s.faceMode == FaceMode.ALIEN) { add(face.forehead, face.height * 0.75f, 0.28f * s.faceModeIntensity, 1, sin(face.roll), -cos(face.roll)); slim = maxOf(slim, 0.35f * s.faceModeIntensity) }
            if (s.faceMode == FaceMode.HULK) head = maxOf(head, 0.15f)
            if (eye > 0f) { add(face.leftEye, face.width * 0.25f, 0.45f * eye, 0); add(face.rightEye, face.width * 0.25f, 0.45f * eye, 0) }
            if (slim > 0f) { val toC = 0.35f * slim; add(face.leftCheek + face.right(-0.15f), face.width * 0.45f, toC, 1, cos(face.roll), sin(face.roll)); add(face.rightCheek + face.right(0.15f), face.width * 0.45f, toC, 1, -cos(face.roll), -sin(face.roll)) }
            if (nose > 0f) add(face.nose, face.width * 0.22f, -0.4f * nose, 0)
            if (chin > 0f) add(face.chin, face.width * 0.4f, 0.3f * chin, 1, sin(face.roll), -cos(face.roll))
            if (head != 0f) add(face.center, face.height * 0.95f, head, 0)
            val n = warps.size.coerceAtMost(10)
            p.set1i("uWarpCount", n)
            if (n > 0) {
                val wa = FloatArray(40); val da = FloatArray(40)
                for (i in 0 until n) { System.arraycopy(warps[i], 0, wa, i * 4, 4); System.arraycopy(dirs[i], 0, da, i * 4, 4) }
                p.set4fv("uWarp", wa); p.set4fv("uWarpDir", da)
            }
        } else { p.set1i("uWarpCount", 0); p.set2f("uFaceCenter", 0.5f, 0.5f); p.set2f("uFaceAxisX", 1f, 0f); p.set2f("uFaceAxisY", 0f, 1f); p.set4f("uCheeks", -1f, -1f, -1f, -1f); p.set1f("uCheekRadius", 0.01f) }
        q.drawFull(p)
    }

    private fun stylePass(s: EffectsSettings, face: FaceFrame?, time: Float) {
        val p = progStyle!!; val q = quad!!
        setVertexDefaults(p)
        p.texture("uFrame", 0, fboB!!.texture); p.texture("uBlur", 1, fboBlur!!.texture); p.texture("uFaceMask", 2, fboMask!!.texture)
        p.set1f("uAspect", mapping.sceneAspect); p.set1f("uTime", time); p.set2f("uTexel", 1f / width, 1f / height)
        val fun_ = s.funMode
        val funCode = when (fun_) { FunMode.FISHEYE -> 5; FunMode.MIRROR -> 6; FunMode.PIXEL_FACE -> 7; FunMode.PIXELATE -> 8; FunMode.CARTOON -> 9; FunMode.THERMAL -> 10; FunMode.NEGATIVE -> 11
            FunMode.VHS -> 12; FunMode.GLITCH -> 13; FunMode.HALFTONE -> 14; FunMode.SKETCH -> 15; FunMode.NIGHT_VISION -> 16; FunMode.RAINBOW -> 17; FunMode.KALEIDOSCOPE -> 18; FunMode.OIL_PAINT -> 19; FunMode.SWIRL -> 20; else -> 0 }
        p.set1i("uFun", funCode); p.set1f("uFunK", s.funIntensity)
        val A = mapping.sceneAspect
        if (face != null) { p.set2f("uFaceCenter", face.center.x / A, face.center.y); p.set1f("uFaceRadius", face.height * 0.6f) } else { p.set2f("uFaceCenter", 0.5f, 0.5f); p.set1f("uFaceRadius", 0.3f) }
        val look = s.look
        p.set1i("uLook", look.ordinal); p.set1f("uLookK", s.lookIntensity)
        val lp = lookParams(look)
        p.set4f("uLookA", lp[0], lp[1], lp[2], lp[3]); p.set4f("uLookB", lp[4], lp[5], lp[6], 0f)
        p.set3f("uShadowTint", lp[7], lp[8], lp[9]); p.set3f("uHighTint", lp[10], lp[11], lp[12])
        p.set1f("uVignette", s.vignette); p.set1f("uGrain", s.grain)
        q.drawFull(p)
    }

    /** temperature, tint, saturation, contrast, lift, gamma, splitAmount, shadowRGB, highlightRGB */
    private fun lookParams(look: ColorLook): FloatArray = when (look) {
        ColorLook.WARM -> floatArrayOf(0.08f, 0.02f, 1.1f, 1.05f, 0f, 1f, 0.15f, 0.05f, 0.02f, 0f, 0.1f, 0.05f, 0f)
        ColorLook.COOL -> floatArrayOf(-0.08f, -0.01f, 1.0f, 1.05f, 0f, 1f, 0.15f, 0f, 0.02f, 0.08f, 0f, 0.02f, 0.06f)
        ColorLook.TEAL_ORANGE -> floatArrayOf(0.02f, 0f, 1.15f, 1.12f, 0f, 1f, 0.6f, -0.05f, 0.03f, 0.08f, 0.1f, 0.03f, -0.05f)
        ColorLook.VINTAGE -> floatArrayOf(0.06f, 0.03f, 0.75f, 0.9f, 0.08f, 1.05f, 0.4f, 0.03f, 0.02f, -0.02f, 0.08f, 0.05f, 0f)
        ColorLook.FADED -> floatArrayOf(0f, 0f, 0.7f, 0.85f, 0.12f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        ColorLook.MATTE -> floatArrayOf(0f, 0f, 0.9f, 0.95f, 0.07f, 1.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        ColorLook.VIVID -> floatArrayOf(0f, 0f, 1.45f, 1.15f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        ColorLook.CROSS_PROCESS -> floatArrayOf(0f, 0.04f, 1.25f, 1.2f, 0.02f, 0.95f, 0.7f, 0f, 0.06f, 0.1f, 0.1f, 0.08f, -0.05f)
        ColorLook.CYBERPUNK -> floatArrayOf(-0.03f, 0.02f, 1.3f, 1.15f, 0f, 0.95f, 0.8f, 0.08f, 0f, 0.15f, 0.1f, 0.05f, 0.15f)
        ColorLook.PASTEL -> floatArrayOf(0.02f, 0.02f, 0.8f, 0.85f, 0.15f, 1.15f, 0.3f, 0.06f, 0.04f, 0.08f, 0.06f, 0.04f, 0.06f)
        ColorLook.GOLDEN_HOUR -> floatArrayOf(0.14f, 0.03f, 1.15f, 1.05f, 0.02f, 1f, 0.5f, 0.05f, 0.0f, -0.03f, 0.15f, 0.1f, 0f)
        ColorLook.MOONLIGHT -> floatArrayOf(-0.12f, 0f, 0.8f, 1.1f, 0.02f, 1f, 0.5f, 0f, 0.02f, 0.12f, 0.02f, 0.03f, 0.08f)
        else -> floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    private fun drawStickers(s: EffectsSettings, time: Float, behind: Boolean, target: Fbo) {
        val layers = s.stickers.filter { it.behindPerson == behind }
        if (layers.isEmpty()) return
        val q = quad!!; val p = progTexture!!; val st = stickers ?: return
        target.bind()
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setVertexDefaults(p)
        val face = faceFrame
        val A = mapping.sceneAspect
        for (l in layers) {
            val asset = EffectCatalog.sticker(l.assetId)
            val tex = st.get(l.userUri ?: l.assetId, asset?.drawable, l.userUri, asset?.aspect ?: 1f) ?: continue
            val effectiveAsset = asset?.copy(aspect = tex.aspect) ?: StickerAsset(l.assetId, l.assetId, StickerCategory.USER, "", l.anchor, l.scale, aspect = tex.aspect)
            val quadPts = FaceGeometry.stickerQuad(face, l, effectiveAsset, poseFrame, A, time, mirrorX = false) ?: continue
            val pos = FloatArray(8)
            for (i in 0 until 4) { pos[i * 2] = quadPts[i * 2] / A; pos[i * 2 + 1] = quadPts[i * 2 + 1] }
            p.texture("uTex", 0, tex.id); p.set1f("uOpacity", l.opacity)
            q.draw(p, pos, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawOutput(tex: Int, mirror: Boolean) {
        val p = progCopy!!
        setVertexDefaults(p, flipY = true, mirror = mirror)
        p.set1f("uOpacity", 1f); p.texture("uTex", 0, tex)
        quad!!.drawFull(p)
    }

    private fun drawDebug() {
        val p = progDebug!!
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        setVertexDefaults(p, flipY = true, mirror = frontCamera && mirrorPreview)
        p.texture("uTex", 0, fboMask!!.texture)
        quad!!.drawFull(p)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
