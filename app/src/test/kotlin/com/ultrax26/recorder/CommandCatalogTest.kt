package com.ultrax26.recorder

import com.ultrax26.recorder.triggers.CommandCatalog
import com.ultrax26.recorder.triggers.CommandCatalogInput
import com.ultrax26.recorder.triggers.RecAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogTest {
    private val input = CommandCatalogInput(
        cameras = listOf("0" to "Main 1×", "23" to "Tele 5×"),
        resolutions = listOf(7680 to 4320, 3840 to 2160, 1920 to 1080),
        fpsOptions = listOf(24, 30, 60),
        lensPresets = listOf(0.6f, 1f, 3f, 5f),
        maxZoom = 100f,
        codecs = listOf("HEVC", "AVC", "AV1"),
        hdrModes = listOf("OFF", "HLG10", "HDR10"),
        looks = listOf("cop" to "Cop", "wizard" to "Wizard"),
        stickers = listOf("mustache" to "Mustache"),
        backgrounds = listOf("space" to "Space station"),
        faceModes = listOf("ALIEN" to "Alien"),
        funModes = listOf("FISHEYE" to "Fisheye"),
        ageModes = listOf("OLDER" to "Older"),
        colorLooks = listOf("BW" to "Black & white"),
        tonePresets = listOf("REC709" to "Rec. 709"),
    )

    @Test fun `catalog is dense, unique and covers every group`() {
        val all = CommandCatalog.build(input)
        assertTrue("expected a long list, got ${all.size}", all.size > 120)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        val groups = all.map { it.group }.toSet()
        listOf(CommandCatalog.RECORDING, CommandCatalog.CAMERA, CommandCatalog.ZOOM, CommandCatalog.FORMAT, CommandCatalog.EXPOSURE, CommandCatalog.FOCUS, CommandCatalog.COLOR,
            CommandCatalog.OVERLAYS, CommandCatalog.AUDIO, CommandCatalog.EFFECTS, CommandCatalog.BACKGROUNDS, CommandCatalog.STICKERS, CommandCatalog.TRIGGERS, CommandCatalog.CALLS).forEach { g -> assertTrue("missing group $g", g in groups) }
    }

    @Test fun `device-specific entries carry the right parameters`() {
        val all = CommandCatalog.build(input)
        val res8k = all.first { it.id == "res-7680x4320" }
        assertEquals(RecAction.SET_RESOLUTION, res8k.action); assertEquals("7680x4320", res8k.param); assertTrue(res8k.title.contains("8K"))
        val cam = all.first { it.id == "camera-23" }
        assertEquals(RecAction.SELECT_CAMERA, cam.action); assertEquals("23", cam.param); assertTrue(cam.title.contains("Tele 5×"))
        val zoom = all.first { it.id == "zoom-5×" }
        assertEquals(RecAction.SET_ZOOM, zoom.action); assertEquals("5", zoom.param)
        assertTrue(all.any { it.action == RecAction.SET_ZOOM && it.param == "0.6" })
        assertTrue(all.none { it.action == RecAction.SET_ZOOM && (it.param?.toFloat() ?: 0f) > 100f })
        assertEquals("cop", all.first { it.id == "look-cop" }.param)
        assertEquals(RecAction.TOGGLE_STICKER, all.first { it.id == "sticker-mustache" }.action)
        assertEquals("1000", all.first { it.id == "shutter-1000" }.param)
    }

    @Test fun `search filters on every word across title group keywords and parameter`() {
        val all = CommandCatalog.build(input)
        assertTrue(all.filter { it.matches("4k") }.any { it.id == "res-3840x2160" })
        assertTrue(all.filter { it.matches("zoom 5") }.any { it.id == "zoom-5×" })
        assertTrue(all.filter { it.matches("camera tele") }.all { it.group == CommandCatalog.CAMERA })
        assertTrue(all.filter { it.matches("selfie") }.any { it.action == RecAction.FRONT_CAMERA })
        assertTrue(all.filter { it.matches("mute") }.any { it.action == RecAction.TOGGLE_AUDIO })
        assertTrue(all.filter { it.matches("xyzzy") }.isEmpty())
        assertEquals(all.size, all.filter { it.matches("") }.size)
    }

    @Test fun `suggested phrases are plain lowercase words`() {
        val all = CommandCatalog.build(input)
        all.forEach { c -> assertTrue("bad phrase '${c.phrase}' for ${c.id}", c.phrase.isNotBlank() && c.phrase == c.phrase.lowercase() && !c.phrase.contains("(")) }
        assertEquals("zoom to 5x", all.first { it.id == "zoom-5×" }.phrase)
        assertEquals("start recording", all.first { it.id == "start" }.phrase)
    }
}
