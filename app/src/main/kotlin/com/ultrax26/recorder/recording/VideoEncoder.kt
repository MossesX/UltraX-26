package com.ultrax26.recorder.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import com.ultrax26.recorder.settings.BitrateMode
import com.ultrax26.recorder.settings.HdrMode
import com.ultrax26.recorder.util.UxLog

data class VideoEncoderConfig(
    val mime: String,
    val encoderName: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val bitrateMode: BitrateMode,
    val cqQuality: Int,
    val iFrameIntervalSec: Float,
    val maxBFrames: Int,
    val profile: Int?,
    val level: Int?,
    val hdr: HdrMode,
    val fullRange: Boolean,
    val operatingRate: Int? = null,
)

/**
 * Surface-input MediaCodec video encoder in async mode. It is created once per camera session and
 * kept running across clips (the camera only targets its surface while recording / pre-rolling), so
 * clip start latency is one frame and pre-roll works without re-configuring the session.
 */
class VideoEncoder(val config: VideoEncoderConfig, private val handler: Handler, private val listener: Listener) {
    interface Listener {
        fun onVideoFormat(format: MediaFormat)
        fun onVideoSample(sample: EncodedSample)
        fun onVideoError(e: Throwable)
    }

    private val tag = "VideoEnc"
    private var codec: MediaCodec? = null
    val inputSurface: Surface
    @Volatile var outputFormat: MediaFormat? = null
        private set
    @Volatile var running = false
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* surface input */ }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    listener.onVideoSample(EncodedSample.copyOf(Track.VIDEO, buf, info))
                }
            } catch (t: Throwable) {
                listener.onVideoError(t)
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) { }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            UxLog.e(tag, "codec error: ${e.diagnosticInfo} recoverable=${e.isRecoverable} transient=${e.isTransient}", e)
            listener.onVideoError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
            listener.onVideoFormat(format)
        }
    }

    init {
        val c = if (config.encoderName != null) MediaCodec.createByCodecName(config.encoderName) else MediaCodec.createEncoderByType(config.mime)
        val fmt = buildFormat()
        UxLog.i(tag, "configure ${c.name}: $fmt")
        c.setCallback(callback, handler)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            // Retry without optional keys some encoders reject (B-frames, level, color info).
            UxLog.w(tag, "configure failed (${t.message}); retrying with minimal format")
            c.reset()
            c.setCallback(callback, handler)
            c.configure(buildFormat(minimal = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = c.createInputSurface()
        codec = c
    }

    private fun buildFormat(minimal: Boolean = false): MediaFormat {
        val f = MediaFormat.createVideoFormat(config.mime, config.width, config.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec.coerceAtLeast(0.1f))
        f.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, when (config.bitrateMode) {
            BitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            BitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            BitrateMode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
        })
        if (config.bitrateMode == BitrateMode.CQ) f.setInteger(MediaFormat.KEY_QUALITY, config.cqQuality.coerceIn(0, 100))
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        config.operatingRate?.let { f.setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
        if (!minimal) {
            config.profile?.let { f.setInteger(MediaFormat.KEY_PROFILE, it) }
            config.level?.let { f.setInteger(MediaFormat.KEY_LEVEL, it) }
            if (config.maxBFrames > 0) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, config.maxBFrames)
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, if (config.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
            when (config.hdr) {
                HdrMode.OFF -> {
                    f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                HdrMode.HLG10 -> {
                    f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                }
                HdrMode.HDR10, HdrMode.HDR10_PLUS, HdrMode.DOLBY_VISION -> {
                    f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                }
            }
        }
        return f
    }

    fun start() {
        if (running) return
        codec?.start()
        running = true
    }

    fun requestKeyFrame() {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (t: Throwable) { UxLog.w(tag, "keyframe req: ${t.message}") }
    }

    fun setBitrate(bps: Int) {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) } catch (t: Throwable) { UxLog.w(tag, "bitrate: ${t.message}") }
    }

    /** Signal EOS (only when tearing the whole session down). */
    fun signalEnd() { try { codec?.signalEndOfInputStream() } catch (_: Throwable) { } }

    fun release() {
        running = false
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        try { inputSurface.release() } catch (_: Throwable) { }
        codec = null
    }

}
