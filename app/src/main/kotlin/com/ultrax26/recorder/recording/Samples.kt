package com.ultrax26.recorder.recording

import android.media.MediaCodec
import java.nio.ByteBuffer

enum class Track { VIDEO, AUDIO }

/** An encoded access unit with its own copy of the payload (safe to hold after the codec buffer is released). */
class EncodedSample(val track: Track, val data: ByteBuffer, val ptsUs: Long, val flags: Int) {
    val isKeyFrame: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
    val isConfig: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
    val isEos: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
    val size: Int get() = data.remaining()

    fun withPts(newPtsUs: Long) = EncodedSample(track, data.duplicate(), newPtsUs, flags)

    companion object {
        fun copyOf(track: Track, src: ByteBuffer, info: MediaCodec.BufferInfo): EncodedSample {
            val dup = src.duplicate()
            dup.position(info.offset)
            dup.limit(info.offset + info.size)
            val copy = ByteBuffer.allocateDirect(info.size)
            copy.put(dup)
            copy.flip()
            return EncodedSample(track, copy, info.presentationTimeUs, info.flags)
        }
    }
}

/** Raw PCM chunk from the microphone. `ptsNs` is CLOCK_BOOTTIME of the first frame. */
class PcmChunk(val samples: ShortArray, val frames: Int, val channels: Int, val sampleRate: Int, val ptsNs: Long, val peak: Float)
