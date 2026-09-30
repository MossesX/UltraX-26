package com.ultrax26.recorder

import com.ultrax26.recorder.camera.GenericKeyCodec
import com.ultrax26.recorder.camera.Tonemaps
import com.ultrax26.recorder.recording.EncodedSample
import com.ultrax26.recorder.recording.PreRollBuffer
import com.ultrax26.recorder.recording.StorageTarget
import com.ultrax26.recorder.recording.Track
import com.ultrax26.recorder.settings.AppSettings
import com.ultrax26.recorder.settings.KeyType
import com.ultrax26.recorder.settings.TonemapPreset
import com.ultrax26.recorder.triggers.RecAction
import com.ultrax26.recorder.triggers.Trigger
import com.ultrax26.recorder.triggers.TriggerRule
import com.ultrax26.recorder.util.ColorTemperature
import com.ultrax26.recorder.util.UxJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Date

class ModelsAndUtilsTest {
    @Test fun settingsRoundTripJson() {
        val s = AppSettings()
        val text = UxJson.encodeToString(s)
        val back = UxJson.decodeFromString(AppSettings.serializer(), text)
        assertEquals(s, back)
        assertTrue(back.triggers.rules.any { it.trigger is Trigger.Blink })
    }

    @Test fun rulePolymorphismSerializes() {
        val r = TriggerRule("x", Trigger.VoiceCommand("start recording", aliases = listOf("go")), RecAction.START)
        val text = UxJson.encodeToString(r)
        assertTrue(text.contains("\"type\": \"voice\""))
        val back = UxJson.decodeFromString(TriggerRule.serializer(), text)
        assertEquals(r, back)
    }

    @Test fun genericCodecParsesPrimitivesAndArrays() {
        assertEquals(42, GenericKeyCodec.parse(KeyType.INT, " 42 "))
        assertEquals(true, GenericKeyCodec.parse(KeyType.BOOLEAN, "on"))
        assertArrayEquals(intArrayOf(1, 2, 3), GenericKeyCodec.parse(KeyType.INT_ARRAY, "[1, 2, 3]") as IntArray)
        assertEquals(1.5f, GenericKeyCodec.parse(KeyType.FLOAT, "1.5") as Float, 1e-6f)
        assertEquals("1, 2, 3", GenericKeyCodec.format(intArrayOf(1, 2, 3)))
        assertEquals(KeyType.LONG, GenericKeyCodec.inferType(5L))
        assertEquals(KeyType.FLOAT_ARRAY, GenericKeyCodec.inferType(floatArrayOf(1f)))
    }

    @Test fun fileNameTemplate() {
        val name = StorageTarget.fileName("UX26_{date}_{time}_{resname}_{fps}fps_{codec}{seg}", 7680, 4320, 30, "HEVC", "HLG10", "z1.0", 1, "mp4", Date(0))
        assertTrue(name, name.startsWith("UX26_"))
        assertTrue(name, name.contains("_8K_30fps_hevc_p02.mp4"))
        val bad = StorageTarget.fileName("a/b:c", 1920, 1080, 30, "AVC", "SDR", "", 0, "mp4")
        assertTrue(!bad.contains("/") && !bad.contains(":"))
    }

    @Test fun tonemapCurvesAreMonotonic() {
        for (p in listOf(TonemapPreset.SRGB, TonemapPreset.REC709, TonemapPreset.FLAT_LOG, TonemapPreset.GAMMA)) {
            var last = -1f
            for (i in 0..20) {
                val x = i / 20f
                val y = when (p) { TonemapPreset.SRGB -> Tonemaps.srgb(x); TonemapPreset.REC709 -> Tonemaps.rec709(x); TonemapPreset.FLAT_LOG -> Tonemaps.flatLog(x); else -> Math.pow(x.toDouble(), 1 / 2.2).toFloat() }
                assertTrue("$p not monotonic at $x", y >= last - 1e-4f); last = y
            }
        }
        assertTrue(Tonemaps.flatLog(0.1f) > 0.3f) // shadows lifted
    }

    @Test fun whiteBalanceGainsWarmVsCool() {
        val warm = ColorTemperature.gainsForKelvin(3000)
        val cool = ColorTemperature.gainsForKelvin(8000)
        assertTrue(warm[3] > warm[0]) // tungsten scene needs more blue gain
        assertTrue(cool[0] > cool[3]) // shade needs more red gain
        assertEquals(warm[1], warm[2], 1e-6f)
    }

    private fun sample(track: Track, ptsUs: Long, key: Boolean) =
        EncodedSample(track, ByteBuffer.allocate(8), ptsUs, if (key) 1 /* MediaCodec.BUFFER_FLAG_KEY_FRAME */ else 0)

    @Test fun preRollDrainsFromKeyframeBeforeWindow() {
        val pr = PreRollBuffer(3)
        // 8 s of video at 10 fps with a keyframe every second, plus audio every 100 ms.
        for (i in 0 until 80) {
            val pts = i * 100_000L
            pr.add(sample(Track.VIDEO, pts, i % 10 == 0))
            pr.add(sample(Track.AUDIO, pts + 5, false))
        }
        val out = pr.drain()
        assertTrue(out.isNotEmpty())
        val first = out.first()
        assertEquals(Track.VIDEO, first.track)
        assertTrue(first.isKeyFrame)
        val newest = 79 * 100_000L
        assertTrue("start=${first.ptsUs}", first.ptsUs <= newest - 3_000_000L && first.ptsUs >= newest - 4_100_000L)
        assertEquals(0, pr.drain().size)
    }
}
