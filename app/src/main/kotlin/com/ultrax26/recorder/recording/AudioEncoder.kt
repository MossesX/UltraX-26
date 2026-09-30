package com.ultrax26.recorder.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import com.ultrax26.recorder.util.UxLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

data class AudioEncoderConfig(val sampleRate: Int, val channels: Int, val bitrate: Int, val aacProfile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC)

/** AAC encoder fed from [AudioCapture] chunks (async MediaCodec; PCM queued until input buffers arrive). */
class AudioEncoder(val config: AudioEncoderConfig, handler: Handler, private val listener: Listener) : AudioCapture.Listener {
    interface Listener {
        fun onAudioFormat(format: MediaFormat)
        fun onAudioSample(sample: EncodedSample)
        fun onAudioError(e: Throwable)
    }

    private val tag = "AudioEnc"
    private val codec: MediaCodec
    private val lock = Any()
    private val pending = ArrayDeque<PcmChunk>()
    private val freeInputs = ArrayDeque<Int>()
    private var pendingOffsetFrames = 0
    @Volatile var enabled = false   // when false, PCM is dropped (not recording / not pre-rolling)
    @Volatile var outputFormat: MediaFormat? = null
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) { freeInputs.addLast(index); drainLocked() }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf: ByteBuffer? = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    listener.onAudioSample(EncodedSample.copyOf(Track.AUDIO, buf, info))
                }
            } catch (t: Throwable) { listener.onAudioError(t) } finally { try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) { } }
        }
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { UxLog.e(tag, "audio codec error", e); listener.onAudioError(e) }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { outputFormat = format; listener.onAudioFormat(format) }
    }

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, config.aacProfile)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.setCallback(callback, handler)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun onPcm(chunk: PcmChunk) {
        if (!enabled) return
        synchronized(lock) {
            pending.addLast(chunk)
            while (pending.size > 200) pending.removeFirst() // ~4 s backlog cap
            drainLocked()
        }
    }

    private fun drainLocked() {
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val idx = freeInputs.removeFirst()
            val chunk = pending.first()
            val inBuf = try { codec.getInputBuffer(idx) } catch (_: Throwable) { null } ?: continue
            inBuf.clear()
            inBuf.order(ByteOrder.LITTLE_ENDIAN)
            val ch = chunk.channels
            val startFrame = pendingOffsetFrames
            val framesFit = minOf(chunk.frames - startFrame, inBuf.remaining() / (2 * ch))
            val sb = inBuf.asShortBuffer()
            sb.put(chunk.samples, startFrame * ch, framesFit * ch)
            val bytes = framesFit * ch * 2
            val ptsUs = (chunk.ptsNs + startFrame * 1_000_000_000L / chunk.sampleRate) / 1000
            try { codec.queueInputBuffer(idx, 0, bytes, ptsUs, 0) } catch (t: Throwable) { listener.onAudioError(t); return }
            if (startFrame + framesFit >= chunk.frames) { pending.removeFirst(); pendingOffsetFrames = 0 } else pendingOffsetFrames = startFrame + framesFit
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Throwable) { }
        try { codec.release() } catch (_: Throwable) { }
    }

}
