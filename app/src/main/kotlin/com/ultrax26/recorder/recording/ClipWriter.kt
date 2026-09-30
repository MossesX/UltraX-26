package com.ultrax26.recorder.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ultrax26.recorder.util.UxLog

/**
 * Writes encoded samples into MP4 segments. Handles: waiting for both track formats, keyframe-aligned
 * starts, pause/resume timestamp compression, size/duration-based segment rotation, and
 * slow-motion / time-lapse timestamp scaling.
 */
class ClipWriter(
    private val storage: StorageTarget,
    private val nextFileName: (segmentIndex: Int) -> String,
    private val mime: String = "video/mp4",
    private val orientationHint: Int = 0,
    private val location: Pair<Double, Double>? = null,
    private val segmentDurationUs: Long = 0L,
    private val segmentBytes: Long = 0L,
    private val hasAudio: Boolean = true,
    /** Output pts = input pts * timeScale (e.g. 4.0 for 120fps→30fps slow motion, 1/N for time-lapse). */
    private val timeScale: Double = 1.0,
) {
    private val tag = "ClipWriter"
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var output: OutputFile? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var segmentIndex = 0
    private var segmentStartPtsUs = -1L      // input-domain pts of the first video sample in the segment
    private var segmentBytesWritten = 0L
    private var lastVideoPtsOut = -1L
    private var lastAudioPtsOut = -1L
    private var pauseOffsetUs = 0L            // accumulated paused time (input domain)
    private var pausedAtUs = -1L
    private var awaitingKeyframe = true
    private var firstVideoPtsUs = -1L
    private var lastInputVideoPtsUs = -1L
    private val bufferInfo = MediaCodec.BufferInfo()
    val outputs = ArrayList<OutputFile>()
    var bytesWritten = 0L
        private set
    var durationUs = 0L
        private set
    val segments: Int get() = segmentIndex + (if (muxer != null) 1 else 0)
    val markers = ArrayList<Long>()

    fun setVideoFormat(f: MediaFormat) { videoFormat = f }
    fun setAudioFormat(f: MediaFormat) { audioFormat = f }
    val isReady: Boolean get() = videoFormat != null && (!hasAudio || audioFormat != null)

    /** Mark a pause boundary at the given input-domain time. */
    fun pause(atInputPtsUs: Long) { if (pausedAtUs < 0) pausedAtUs = atInputPtsUs }
    fun resume(atInputPtsUs: Long) {
        if (pausedAtUs >= 0) { pauseOffsetUs += (atInputPtsUs - pausedAtUs).coerceAtLeast(0); pausedAtUs = -1L }
        awaitingKeyframe = true
    }
    val isPaused: Boolean get() = pausedAtUs >= 0

    fun addMarker() { if (durationUs > 0) markers += durationUs }

    private fun outPts(inputPtsUs: Long): Long = (((inputPtsUs - segmentStartPtsUs - pauseOffsetUs).coerceAtLeast(0)) * timeScale).toLong()

    fun write(s: EncodedSample) {
        if (!isReady) return
        if (isPaused) return
        when (s.track) {
            Track.VIDEO -> writeVideo(s)
            Track.AUDIO -> writeAudio(s)
        }
    }

    private fun writeVideo(s: EncodedSample) {
        if (awaitingKeyframe) {
            if (!s.isKeyFrame) return
            awaitingKeyframe = false
        }
        if (muxer == null) openSegment(s.ptsUs)
        else if (s.isKeyFrame && shouldRotate(s.ptsUs)) { closeSegment(); openSegment(s.ptsUs) }
        val m = muxer ?: return
        var pts = outPts(s.ptsUs)
        if (pts <= lastVideoPtsOut) pts = lastVideoPtsOut + 1
        lastVideoPtsOut = pts
        lastInputVideoPtsUs = s.ptsUs
        if (firstVideoPtsUs < 0) firstVideoPtsUs = s.ptsUs
        bufferInfo.set(0, s.size, pts, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        try { m.writeSampleData(videoTrack, s.data, bufferInfo) } catch (t: Throwable) { UxLog.e(tag, "write video", t); return }
        bytesWritten += s.size; segmentBytesWritten += s.size
        durationUs = pts + (segmentIndex * 0L)
    }

    private fun writeAudio(s: EncodedSample) {
        val m = muxer ?: return
        if (audioTrack < 0) return
        // Drop audio that predates the first video frame of this segment.
        if (s.ptsUs < segmentStartPtsUs) return
        var pts = outPts(s.ptsUs)
        if (pts <= lastAudioPtsOut) pts = lastAudioPtsOut + 1
        lastAudioPtsOut = pts
        bufferInfo.set(0, s.size, pts, 0)
        try { m.writeSampleData(audioTrack, s.data, bufferInfo) } catch (t: Throwable) { UxLog.e(tag, "write audio", t); return }
        bytesWritten += s.size; segmentBytesWritten += s.size
    }

    private fun shouldRotate(inputPtsUs: Long): Boolean {
        if (segmentBytes > 0 && segmentBytesWritten >= segmentBytes) return true
        if (segmentDurationUs > 0 && (inputPtsUs - segmentStartPtsUs - pauseOffsetUs) >= segmentDurationUs) return true
        return false
    }

    private fun openSegment(firstVideoPtsUs: Long) {
        val name = nextFileName(segmentIndex)
        val out = storage.create(name, mime)
        val m = MediaMuxer(out.pfd.fileDescriptor, if (mime == "video/webm") MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(orientationHint)
        location?.let { (lat, lon) -> try { m.setLocation(lat.toFloat(), lon.toFloat()) } catch (_: Throwable) { } }
        videoTrack = m.addTrack(videoFormat!!)
        audioTrack = if (hasAudio && audioFormat != null) m.addTrack(audioFormat!!) else -1
        m.start()
        muxer = m; output = out; started = true
        segmentStartPtsUs = firstVideoPtsUs
        pauseOffsetUs = 0L
        segmentBytesWritten = 0L
        lastVideoPtsOut = -1L; lastAudioPtsOut = -1L
        outputs += out
        UxLog.i(tag, "segment ${segmentIndex + 1} -> $name")
    }

    private fun closeSegment() {
        val m = muxer ?: return
        try { if (started) m.stop() } catch (t: Throwable) { UxLog.w(tag, "muxer stop: ${t.message}") }
        try { m.release() } catch (_: Throwable) { }
        output?.finish()
        muxer = null; output = null; started = false
        segmentIndex++
    }

    /** Finalize everything. Returns the files written. */
    fun finish(): List<OutputFile> {
        closeSegment()
        return outputs.toList()
    }
}
