package com.ultrax26.recorder.diagnostics

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.os.Build
import com.ultrax26.recorder.camera.CameraCatalog
import com.ultrax26.recorder.camera.Capabilities
import com.ultrax26.recorder.camera.GenericKeyCodec
import com.ultrax26.recorder.recording.EncoderCapabilities
import com.ultrax26.recorder.settings.VideoCodec

/** A shareable text report of everything this phone's cameras, encoders and mics advertise. */
object CameraReport {

    fun build(context: Context, catalog: CameraCatalog, probeHidden: Boolean, maxHidden: Int, fullKeys: Boolean = true): String {
        val sb = StringBuilder()
        sb.appendLine("UltraX 26 device report")
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), One UI build ${Build.DISPLAY}")
        sb.appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}; hardware: ${Build.HARDWARE}")
        sb.appendLine()

        val infos = catalog.allInfos(probeHidden, maxHidden)
        sb.appendLine("== Cameras (${infos.size}; public: ${catalog.publicIds()}) ==")
        for (i in infos) {
            sb.appendLine()
            sb.appendLine("-- ${i.shortName} --")
            sb.appendLine("level=${i.levelLabel} logical=${i.isLogical} physicalIds=${i.physicalIds} facing=${i.facingLabel} sensorOrientation=${i.sensorOrientation}")
            sb.appendLine("focal=${i.focalLengths.joinToString()} mm  aperture=f/${i.apertures.joinToString()}  sensor=${i.sensorSizeMm}  eq35=${i.equivalentFocalMm35?.let { "%.1f".format(it) }} mm  zoom=${i.zoomRange}")
            sb.appendLine("pixelArray=${i.pixelArray} activeArray=${i.activeArray} iso=${i.isoRange} exposure=${i.exposureRange} ns minFocus=${i.minFocusDistance} hyperfocal=${i.hyperfocalDistance}")
            sb.appendLine("capabilities=${i.capabilities.joinToString()} 10-bit=${i.supports10Bit} dynamicRangeProfiles=${i.dynamicRangeProfiles} timestampSource=${i.timestampSource}")
            sb.appendLine("maxVideo=${i.maxVideoSize} 8K=${i.supports8k} highSpeedSizes=${i.highSpeedSizes} fpsRanges=${i.fpsRanges}")
            try {
                val c = catalog.characteristics(i.id)
                sb.appendLine("videoSizes=${Capabilities.videoSizes(c).take(24)}")
                sb.appendLine("highResOnlyVideoSizes=${Capabilities.highResolutionOnlySizes(c)}")
                sb.appendLine("8K advertised=${Capabilities.has8k(c)}")
                i.physicalIds.forEach { pid ->
                    try { val pc = catalog.characteristics(pid); sb.appendLine("  physical $pid videoSizes=${Capabilities.videoSizes(pc).take(12)} 8K=${Capabilities.has8k(pc)}") } catch (t: Throwable) { sb.appendLine("  physical $pid: ${t.message}") }
                }
                Capabilities.highSpeedSizes(c).forEach { hs -> sb.appendLine("  highSpeed $hs -> ${Capabilities.highSpeedFpsRanges(c, hs)}") }
                sb.appendLine("streamUseCases=${Capabilities.streamUseCases(c).joinToString()}")
                sb.appendLine("vendor request keys (${i.vendorRequestKeyNames.size}): ${i.vendorRequestKeyNames.joinToString()}")
                sb.appendLine("session keys: ${Capabilities.sessionKeyNames(c).joinToString()}")
                if (fullKeys) {
                    sb.appendLine("characteristics:")
                    c.keys.sortedBy { it.name }.forEach { k ->
                        @Suppress("UNCHECKED_CAST")
                        val v = try { c.get(k as CameraCharacteristics.Key<Any>) } catch (_: Throwable) { null }
                        val text = GenericKeyCodec.format(v).let { if (it.length > 400) it.take(400) + "…" else it }
                        sb.appendLine("  ${k.name} = $text")
                    }
                }
            } catch (t: Throwable) { sb.appendLine("  (characteristics failed: ${t.message})") }
        }

        sb.appendLine()
        sb.appendLine("== Video encoders ==")
        for (codec in VideoCodec.entries) {
            val encs = EncoderCapabilities.encoders(codec.mime)
            if (encs.isEmpty()) { sb.appendLine("${codec.label}: none"); continue }
            for (e in encs) {
                sb.appendLine("${codec.label}: ${e.name} hw=${e.isHardware} size=${e.widths}x${e.heights} align=${e.widthAlignment}/${e.heightAlignment} bitrate=${e.bitrateRange} cbr=${e.supportsCbr} vbr=${e.supportsVbr} cq=${e.supportsCq} hdrEditing=${e.hdrEditing} instances=${e.maxInstances}")
                sb.appendLine("   profiles=${e.profiles.map { EncoderCapabilities.profileLabel(codec, it.profile) + "/L" + it.level }.distinct()}")
                listOf(7680 to 4320, 3840 to 2160, 1920 to 1080).forEach { (w, h) ->
                    if (e.supportsSize(w, h)) sb.appendLine("   ${w}x${h}: max ${"%.0f".format(e.maxFps(w, h))} fps, achievable ${e.achievableFps(w, h)?.let { "%.0f".format(it) } ?: "?"} fps")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("== Audio inputs ==")
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
                sb.appendLine("id=${d.id} type=${d.type} name=${d.productName} rates=${d.sampleRates.joinToString()} channels=${d.channelCounts.joinToString()} encodings=${d.encodings.joinToString()}")
            }
        } catch (t: Throwable) { sb.appendLine("(audio devices failed: ${t.message})") }
        return sb.toString()
    }
}
