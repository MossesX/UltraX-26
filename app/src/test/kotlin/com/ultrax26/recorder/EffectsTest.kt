package com.ultrax26.recorder

import com.ultrax26.recorder.effects.Anchor
import com.ultrax26.recorder.effects.BackgroundSpec
import com.ultrax26.recorder.effects.BackgroundType
import com.ultrax26.recorder.effects.EffectCatalog
import com.ultrax26.recorder.effects.EffectsSettings
import com.ultrax26.recorder.effects.FaceGeometry
import com.ultrax26.recorder.effects.FaceLandmarks
import com.ultrax26.recorder.effects.FaceMeshResult
import com.ultrax26.recorder.effects.MeshFaceAdapter
import com.ultrax26.recorder.effects.RotationMode
import com.ultrax26.recorder.effects.SceneMapping
import com.ultrax26.recorder.effects.StickerLayer
import com.ultrax26.recorder.effects.cleared
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.util.UxJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class EffectsTest {
    /** A synthetic upright face: 478 landmarks placed around a center with plausible key points. */
    private fun syntheticMesh(cx: Float = 0.5f, cy: Float = 0.5f, w: Float = 0.3f): FaceMeshResult {
        val lm = FloatArray(478 * 3)
        for (i in 0 until 478) { lm[i * 3] = cx; lm[i * 3 + 1] = cy; lm[i * 3 + 2] = 0f }
        fun set(i: Int, x: Float, y: Float) { lm[i * 3] = x; lm[i * 3 + 1] = y }
        set(FaceLandmarks.LEFT_FACE, cx - w / 2, cy); set(FaceLandmarks.RIGHT_FACE, cx + w / 2, cy)
        set(FaceLandmarks.FOREHEAD_TOP, cx, cy - w * 0.7f); set(FaceLandmarks.CHIN, cx, cy + w * 0.7f)
        set(FaceLandmarks.LEFT_IRIS, cx - w * 0.2f, cy - w * 0.15f); set(FaceLandmarks.RIGHT_IRIS, cx + w * 0.2f, cy - w * 0.15f)
        set(FaceLandmarks.LEFT_EYE_OUTER, cx - w * 0.3f, cy - w * 0.15f); set(FaceLandmarks.RIGHT_EYE_OUTER, cx + w * 0.3f, cy - w * 0.15f)
        set(FaceLandmarks.LEFT_EYE_INNER, cx - w * 0.1f, cy - w * 0.15f); set(FaceLandmarks.RIGHT_EYE_INNER, cx + w * 0.1f, cy - w * 0.15f)
        set(FaceLandmarks.LEFT_EYE_TOP, cx - w * 0.2f, cy - w * 0.19f); set(FaceLandmarks.LEFT_EYE_BOTTOM, cx - w * 0.2f, cy - w * 0.11f)
        set(FaceLandmarks.RIGHT_EYE_TOP, cx + w * 0.2f, cy - w * 0.19f); set(FaceLandmarks.RIGHT_EYE_BOTTOM, cx + w * 0.2f, cy - w * 0.11f)
        set(FaceLandmarks.NOSE_TIP, cx, cy + w * 0.1f); set(FaceLandmarks.NOSE_BOTTOM, cx, cy + w * 0.18f); set(FaceLandmarks.NOSE_BRIDGE, cx, cy - w * 0.1f)
        set(FaceLandmarks.UPPER_LIP_TOP, cx, cy + w * 0.3f); set(FaceLandmarks.UPPER_LIP_BOTTOM, cx, cy + w * 0.34f)
        set(FaceLandmarks.LOWER_LIP_TOP, cx, cy + w * 0.36f); set(FaceLandmarks.LOWER_LIP_BOTTOM, cx, cy + w * 0.42f)
        set(FaceLandmarks.LEFT_CHEEK, cx - w * 0.3f, cy + w * 0.15f); set(FaceLandmarks.RIGHT_CHEEK, cx + w * 0.3f, cy + w * 0.15f)
        return FaceMeshResult(lm, null, null, 0L)
    }

    @Test fun sceneMappingRotationsAreConsistent() {
        for (rot in listOf(0, 90, 180, 270)) {
            val m = SceneMapping(rot, if (rot % 180 == 0) 16f / 9f else 9f / 16f, 16f / 9f)
            val c = m.toSceneNorm(0.5f, 0.5f)
            assertEquals(0.5f, c.x, 1e-5f); assertEquals(0.5f, c.y, 1e-5f)
            val corner = m.toSceneNorm(0f, 0f)
            assertTrue(corner.x in 0f..1f && corner.y in 0f..1f)
        }
        // 90°: upright top-left (0,0) maps to scene (0, 1) (bottom-left of the landscape buffer)
        val m90 = SceneMapping(90, 9f / 16f, 16f / 9f)
        val p = m90.toSceneNorm(0f, 0f)
        assertEquals(0f, p.x, 1e-5f); assertEquals(1f, p.y, 1e-5f)
        // upright x axis becomes scene -y: moving right in the upright frame moves up in the scene
        val q = m90.toSceneNorm(0.5f, 0f)
        assertTrue(q.y < p.y)
    }

    @Test fun faceFrameHasSensibleGeometry() {
        val mesh = syntheticMesh()
        val map = SceneMapping(0, 16f / 9f, 16f / 9f)
        val f = FaceGeometry.frame(mesh, map)
        assertEquals(0.3f * 16f / 9f, f.width, 1e-3f)
        assertTrue(f.headTop.y < f.forehead.y && f.forehead.y < f.eyes.y && f.eyes.y < f.nose.y && f.nose.y < f.mouth.y && f.mouth.y < f.chin.y && f.chin.y < f.neck.y)
        assertEquals(0f, f.roll, 1e-4f)
        assertTrue(f.leftEye.x < f.rightEye.x)
    }

    @Test fun stickerQuadFollowsAnchorAndScale() {
        val mesh = syntheticMesh()
        val map = SceneMapping(0, 16f / 9f, 16f / 9f)
        val f = FaceGeometry.frame(mesh, map)
        val layer = StickerLayer("l", "crown", Anchor.HEAD_TOP, scale = 1f, rotation = RotationMode.NONE)
        val quad = FaceGeometry.stickerQuad(f, layer, EffectCatalog.sticker("crown"), null, 16f / 9f, 0f, false)
        assertNotNull(quad)
        val w = quad!![2] - quad[0]
        assertEquals(f.width, w, 1e-3f)
        val cx = (quad[0] + quad[2]) / 2; val cy = (quad[1] + quad[5]) / 2
        assertEquals(f.headTop.x, cx, 1e-3f); assertEquals(f.headTop.y, cy, 1e-3f)
        // frame anchor without a face still produces a quad
        val badge = FaceGeometry.stickerQuad(null, StickerLayer("b", "rec_badge", Anchor.FRAME_TOP_LEFT, 0.3f), EffectCatalog.sticker("rec_badge"), null, 16f / 9f, 0f, false)
        assertNotNull(badge)
    }

    @Test fun meshAdapterProducesEyeStates() {
        val obs = MeshFaceAdapter.toObservation(syntheticMesh())
        assertNotNull(obs.leftEyeOpen); assertNotNull(obs.rightEyeOpen)
        assertTrue(obs.box.width() > 0f && obs.box.height() > 0f)
        val closed = syntheticMesh().let { m ->
            val lm = m.landmarks.copyOf()
            lm[FaceLandmarks.LEFT_EYE_TOP * 3 + 1] = lm[FaceLandmarks.LEFT_EYE_BOTTOM * 3 + 1]
            lm[FaceLandmarks.RIGHT_EYE_TOP * 3 + 1] = lm[FaceLandmarks.RIGHT_EYE_BOTTOM * 3 + 1]
            MeshFaceAdapter.toObservation(FaceMeshResult(lm, null, null, 0L))
        }
        assertTrue(closed.leftEyeOpen!! < obs.leftEyeOpen!!)
    }

    @Test fun catalogIsConsistent() {
        val ids = EffectCatalog.stickers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(EffectCatalog.looks.map { it.id }.toSet().size == EffectCatalog.looks.size)
        // Every look must resolve its stickers.
        for (look in EffectCatalog.looks) {
            val s = look.apply(EffectsSettings())
            s.stickers.forEach { l -> assertNotNull("look ${look.id} references unknown sticker ${l.assetId}", EffectCatalog.sticker(l.assetId)) }
            if (s.background.type == BackgroundType.PARALLAX) assertTrue(EffectCatalog.parallaxScenes.any { it.id == s.background.id })
            if (s.background.type == BackgroundType.PROCEDURAL) assertTrue(EffectCatalog.proceduralBackgrounds.any { it.id == s.background.id })
        }
        // Drawables exist when the source tree is available (skipped otherwise).
        val res = listOf("src/main/res/drawable", "app/src/main/res/drawable", "../UltraX-26/app/src/main/res/drawable").map { File(it) }.firstOrNull { it.isDirectory }
        if (res != null) {
            EffectCatalog.stickers.forEach { a -> assertTrue("missing ${a.drawable}.xml", File(res, "${a.drawable}.xml").exists()) }
            EffectCatalog.parallaxScenes.flatMap { it.layers }.forEach { l -> assertTrue("missing ${l.drawable}.xml", File(res, "${l.drawable}.xml").exists()) }
        }
    }

    @Test fun effectsSettingsRoundTripAndActivity() {
        val s = EffectsSettings(background = BackgroundSpec(BackgroundType.BLUR), stickers = listOf(EffectCatalog.sticker("cop_hat")!!.layer("x")))
        val text = UxJson.encodeToString(AppSettings(effects = s))
        val back = UxJson.decodeFromString(AppSettings.serializer(), text)
        assertEquals(s, back.effects)
        assertTrue(s.isActive() && s.needsPipeline() && s.needsSegmentation() && s.needsFaceMesh())
        assertTrue(!EffectsSettings().isActive())
        assertTrue(!s.cleared().isActive())
        assertTrue(abs(EffectsSettings().beauty.smoothing) < 1e-6f)
    }
}
