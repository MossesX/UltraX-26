package com.ultrax26.recorder.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import com.ultrax26.recorder.settings.HdrMode
import com.ultrax26.recorder.settings.VideoCodec

data class ProfileLevel(val profile: Int, val level: Int)

data class EncoderInfo(
    val name: String,
    val mime: String,
    val isHardware: Boolean,
    val isVendor: Boolean,
    val widths: Range<Int>,
    val heights: Range<Int>,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateRange: Range<Int>,
    val profiles: List<ProfileLevel>,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsCq: Boolean,
    val hdrEditing: Boolean,
    val maxInstances: Int,
    val surfaceInput: Boolean,
) {
    private fun videoCaps(): MediaCodecInfo.VideoCapabilities? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }?.getCapabilitiesForType(mime)?.videoCapabilities

    fun supportsSize(w: Int, h: Int): Boolean = try {
        videoCaps()?.isSizeSupported(w, h) ?: (w in widths && h in heights)
    } catch (_: Throwable) { w in widths && h in heights }

    fun maxFps(w: Int, h: Int): Double = try {
        videoCaps()?.getSupportedFrameRatesFor(w, h)?.upper ?: 0.0
    } catch (_: Throwable) { 0.0 }

    fun achievableFps(w: Int, h: Int): Double? = try {
        videoCaps()?.getAchievableFrameRatesFor(w, h)?.upper
    } catch (_: Throwable) { null }

    fun hasProfile(profile: Int) = profiles.any { it.profile == profile }
}

/** Enumerates MediaCodec video encoders so the UI only offers what the SoC can actually do (8K, 10-bit, APV…). */
object EncoderCapabilities {

    fun encoders(mime: String): List<EncoderInfo> {
        val out = ArrayList<EncoderInfo>()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            try {
                val caps = info.getCapabilitiesForType(mime)
                val v = caps.videoCapabilities ?: continue
                val e = caps.encoderCapabilities
                out += EncoderInfo(
                    name = info.name,
                    mime = mime,
                    isHardware = info.isHardwareAccelerated,
                    isVendor = info.isVendor,
                    widths = v.supportedWidths,
                    heights = v.supportedHeights,
                    widthAlignment = v.widthAlignment,
                    heightAlignment = v.heightAlignment,
                    bitrateRange = v.bitrateRange,
                    profiles = caps.profileLevels.map { ProfileLevel(it.profile, it.level) },
                    supportsCbr = e?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ?: false,
                    supportsVbr = e?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ?: true,
                    supportsCq = e?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) ?: false,
                    hdrEditing = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing),
                    maxInstances = caps.maxSupportedInstances,
                    surfaceInput = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface),
                )
            } catch (_: Throwable) { }
        }
        return out.sortedWith(compareByDescending<EncoderInfo> { it.isHardware }.thenByDescending { it.widths.upper.toLong() * it.heights.upper })
    }

    fun availableCodecs(): List<VideoCodec> = VideoCodec.entries.filter { encoders(it.mime).isNotEmpty() }

    fun best(codec: VideoCodec, w: Int, h: Int, pinnedName: String? = null): EncoderInfo? {
        val all = encoders(codec.mime)
        pinnedName?.let { n -> all.firstOrNull { it.name == n }?.let { return it } }
        return all.firstOrNull { it.surfaceInput && it.supportsSize(w, h) } ?: all.firstOrNull { it.supportsSize(w, h) } ?: all.firstOrNull()
    }

    /** Pick a codec profile matching the requested bit depth / HDR flavor if the encoder lists one. */
    fun profileFor(codec: VideoCodec, hdr: HdrMode, enc: EncoderInfo?): Int? {
        if (enc == null) return null
        val tenBit = hdr != HdrMode.OFF
        val candidates: List<Int> = when (codec) {
            VideoCodec.HEVC -> when (hdr) {
                HdrMode.OFF -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                HdrMode.HLG10 -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                HdrMode.HDR10 -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                HdrMode.HDR10_PLUS -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                HdrMode.DOLBY_VISION -> listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
            VideoCodec.AVC -> if (tenBit) listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                              else listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            VideoCodec.AV1 -> when (hdr) {
                HdrMode.OFF -> listOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8)
                HdrMode.HLG10 -> listOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
                HdrMode.HDR10 -> listOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
                HdrMode.HDR10_PLUS -> listOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10)
                HdrMode.DOLBY_VISION -> listOf(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
            }
            // APV profile constants (Android 16 / API 36): 422_10 = 0x01, 422_10 HDR10 = 0x1000, HDR10+ = 0x2000.
            VideoCodec.APV -> when (hdr) {
                HdrMode.HDR10 -> listOf(0x1000, 0x01)
                HdrMode.HDR10_PLUS -> listOf(0x2000, 0x1000, 0x01)
                else -> listOf(0x01)
            }
        }
        return candidates.firstOrNull { enc.hasProfile(it) }
    }

    fun profileLabel(codec: VideoCodec, profile: Int): String = when (codec) {
        VideoCodec.AVC -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"; MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"; MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"; MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
            else -> "Profile $profile"
        }
        VideoCodec.HEVC -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"; MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"; MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> "Profile $profile"
        }
        VideoCodec.AV1 -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "Main 8"; MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "Main 10"
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"; MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
            else -> "Profile $profile"
        }
        VideoCodec.APV -> when (profile) { 0x01 -> "422-10"; 0x1000 -> "422-10 HDR10"; 0x2000 -> "422-10 HDR10+"; else -> "Profile $profile" }
    }

    /** Sensible default bitrate (bits/s) from resolution, frame rate, codec and HDR. */
    fun autoBitrate(w: Int, h: Int, fps: Int, codec: VideoCodec, hdr: HdrMode): Int {
        val bpp = when (codec) { VideoCodec.AVC -> 0.15; VideoCodec.HEVC -> 0.10; VideoCodec.AV1 -> 0.08; VideoCodec.APV -> 1.20 }
        val hdrMul = if (hdr == HdrMode.OFF) 1.0 else 1.2
        val bps = w.toDouble() * h * fps.coerceAtLeast(1) * bpp * hdrMul
        return bps.toLong().coerceIn(1_000_000L, 2_000_000_000L).toInt()
    }

    fun audioEncoderAvailable(mime: String = MediaFormat.MIMETYPE_AUDIO_AAC): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
}
